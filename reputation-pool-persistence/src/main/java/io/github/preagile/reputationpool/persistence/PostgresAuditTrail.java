/*
 * Copyright 2026 the reputation-pool authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.preagile.reputationpool.persistence;

import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.port.EventSink;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * An {@link EventSink} that appends every {@link PoolEvent} to the {@code audit_event} table over
 * plain JDBC — the append-only audit trail. Where {@link PostgresResourceStore} whole-replaces the
 * latest snapshot ("what is the state now"), this trail only ever {@code INSERT}s, preserving the full
 * history of what happened and in what order.
 *
 * <p><b>Never blocks the emitting thread.</b> {@link EventSink} promises the core that {@link #emit}
 * runs on whatever thread performed the pool operation and must not block, and a synchronous
 * {@code INSERT} would break that promise on every acquire. So {@code emit} only ever {@code offer}s
 * into a bounded in-memory queue, and a single background writer thread drains it in batches inside
 * one transaction each. This is the same isolation idea as the event broadcaster's per-subscriber
 * bounded queue: a slow consumer must never hold the pool hostage.
 *
 * <p><b>Overflow drops, and says so.</b> When the queue is full (the database is down or cannot keep
 * up), new events are dropped and counted in {@link #droppedCount()} rather than blocking the pool or
 * growing memory without bound — availability of the pool wins over completeness of the trail. A
 * failed batch insert is likewise logged, counted as dropped, and never rethrown into the writer loop,
 * so one bad batch is skipped, not fatal. The trade-off is real: a trail with drops can no longer
 * replay an incident in full, which is why the counter is exposed.
 *
 * <p>{@link #close()} stops accepting events, flushes what is already queued, and joins the writer —
 * so an orderly shutdown against a responsive database loses nothing that was accepted. When the
 * writer cannot finish within the close timeout (a wedged database call), the remaining tail is
 * abandoned but <em>counted</em>: it lands in {@link #droppedCount()} like any other loss, never
 * silently. The event&#8594;row translation lives in {@link AuditEventMapper} as pure functions; this
 * class owns only the queue, the writer thread, and the SQL.
 *
 * <p><b>Retention trims the oldest tail, nothing else.</b> Left alone the table grows without bound,
 * so {@link #purgeOlderThan(Instant)} deletes the rows strictly older than a caller-supplied cutoff —
 * and only those. Surviving rows keep their {@code seq} and their content untouched, so the history
 * that remains is still the append-only ledger, just shorter at the far end. Purging is an
 * operational concern of this implementation, not a domain concept, which is why the method lives on
 * this concrete class and not on the {@link EventSink} port; scheduling it (and the opt-in retention
 * knob — no knob, no purge, unbounded as before) is the server composition root's business.
 */
public final class PostgresAuditTrail implements EventSink, AutoCloseable {

    static final int DEFAULT_QUEUE_CAPACITY = 1024;

    /**
     * Rows deleted per purge round. Small enough to keep each round's transaction (and its lock
     * footprint) modest, large enough that a nightly tail clears in few round-trips.
     */
    static final int DEFAULT_PURGE_BATCH_SIZE = 5_000;

    /** Upper bound of one drained batch; keeps a single transaction (and its latency) small. */
    private static final int MAX_BATCH_SIZE = 128;

    private static final Duration IDLE_POLL = Duration.ofMillis(100);
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Logger LOG = System.getLogger(PostgresAuditTrail.class.getName());

    private static final String INSERT_EVENT =
            """
            INSERT INTO audit_event (event_type, resource_kind, resource_value, context, occurred_at,
                until, cause)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    /**
     * The O(1) purge guard: the {@code occurred_at} of the seq-oldest row. The single writer thread
     * appends in emission order, so seq order is time order — if the head of the ledger is already
     * fresh, so is everything behind it, and the purge can return without scanning anything. One
     * primary-key read is all a no-op purge ever costs, which is what lets it run hourly.
     */
    private static final String OLDEST_OCCURRED_AT =
            """
            SELECT occurred_at FROM audit_event ORDER BY seq LIMIT 1""";

    /**
     * One purge round: delete the oldest {@code purgeBatchSize} rows still older than the cutoff. The
     * inner select walks the {@code seq} primary key from the low end — no index on
     * {@code occurred_at} exists or is needed, because the old rows are exactly the low-seq tail.
     */
    private static final String DELETE_OLDEST_TAIL_BATCH =
            """
            DELETE FROM audit_event
            WHERE seq IN (
                SELECT seq FROM audit_event
                WHERE occurred_at < ?
                ORDER BY seq
                LIMIT ?)""";

    /** The seam between the queue/writer machinery and JDBC, so the machinery tests need no database. */
    interface BatchWriter {
        void write(List<PoolEvent> batch);
    }

    private final BlockingQueue<PoolEvent> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private final Duration closeTimeout;

    /** Null when built on the {@link BatchWriter} seam — such a trail cannot purge, only append. */
    private final DataSource dataSource;

    private final int purgeBatchSize;
    private volatile boolean running = true;

    // Makes the running-check-and-offer in emit() atomic with close() flipping the flag. Without it,
    // an emit racing a close could offer into a queue whose writer has already drained and exited —
    // an event neither written nor counted, breaking droppedCount()'s "zero means no gaps" contract.
    // Emitters already serialize on the queue's internal lock, so this adds no new contention shape,
    // and close() holds it only for the flag flip.
    private final Object emitLock = new Object();

    /**
     * Creates a trail appending to {@code dataSource} with the default queue capacity.
     *
     * @param dataSource the pooled connection source to PostgreSQL; never null
     */
    public PostgresAuditTrail(DataSource dataSource) {
        this(dataSource, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * Creates a trail appending to {@code dataSource} buffering at most {@code queueCapacity} events.
     *
     * @param dataSource the pooled connection source to PostgreSQL; never null
     * @param queueCapacity how many events may wait for the writer before new ones are dropped
     * @throws IllegalArgumentException if {@code queueCapacity} is not positive
     */
    public PostgresAuditTrail(DataSource dataSource, int queueCapacity) {
        this(dataSource, queueCapacity, DEFAULT_PURGE_BATCH_SIZE);
    }

    /** The purge-batch seam: lets tests drive multi-round purges with a handful of rows. */
    PostgresAuditTrail(DataSource dataSource, int queueCapacity, int purgeBatchSize) {
        this(
                jdbcWriter(Objects.requireNonNull(dataSource, "dataSource must not be null")),
                dataSource,
                queueCapacity,
                purgeBatchSize,
                DEFAULT_CLOSE_TIMEOUT);
    }

    PostgresAuditTrail(BatchWriter batchWriter, int queueCapacity) {
        this(batchWriter, queueCapacity, DEFAULT_CLOSE_TIMEOUT);
    }

    PostgresAuditTrail(BatchWriter batchWriter, int queueCapacity, Duration closeTimeout) {
        this(batchWriter, null, queueCapacity, DEFAULT_PURGE_BATCH_SIZE, closeTimeout);
    }

    private PostgresAuditTrail(
            BatchWriter batchWriter,
            DataSource dataSource,
            int queueCapacity,
            int purgeBatchSize,
            Duration closeTimeout) {
        Objects.requireNonNull(batchWriter, "batchWriter must not be null");
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (purgeBatchSize <= 0) {
            throw new IllegalArgumentException("purgeBatchSize must be positive");
        }
        this.dataSource = dataSource;
        this.purgeBatchSize = purgeBatchSize;
        this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout must not be null");
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.writer = Thread.ofPlatform()
                .name("reputation-pool-audit-writer")
                .daemon(true)
                .start(() -> writeLoop(batchWriter));
    }

    /** Enqueues the event for the background writer; drops (and counts) instead of ever blocking. */
    @Override
    public void emit(PoolEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        synchronized (emitLock) {
            if (!running || !queue.offer(event)) {
                dropped.incrementAndGet();
            }
        }
    }

    /**
     * How many events were dropped instead of written — by queue overflow, by a failed batch insert,
     * or by arriving after {@link #close()}. A non-zero count means the trail has gaps and a replay of
     * that span is incomplete.
     */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * Deletes every row whose {@code occurred_at} is strictly older than {@code cutoff} — the
     * age-based retention that bounds the trail. Only the oldest tail of the ledger is trimmed:
     * surviving rows keep their {@code seq} and their content byte-for-byte, so what remains is still
     * the same append-only history. Emits arriving while a purge runs are unaffected — the writer
     * appends fresh, above-cutoff rows at the high end of {@code seq} while the delete works the low
     * end.
     *
     * <p>Cheap by construction, with no new index and no schema change. An O(1) guard reads the
     * seq-oldest row first (the single writer makes seq order time order, so a fresh head means a
     * fresh table) and returns without deleting when there is nothing old. Otherwise rows are deleted
     * oldest-first in batches over the {@code seq} primary key, each round its own transaction, so a
     * huge backlog never turns into one huge lock-holding delete.
     *
     * <p>Callers decide the policy; this method is only the mechanism. In production the server's
     * composition root schedules it with a cutoff of {@code clock.instant() - retention} — and when no
     * retention is configured, never calls it at all, preserving the original unbounded behavior.
     *
     * @param cutoff rows strictly older than this instant are deleted; never null
     * @return how many rows were deleted, summed across all rounds
     * @throws IllegalStateException if this trail was built on the {@link BatchWriter} seam and has no
     *     database to purge
     * @throws PersistenceException if the guard read or a delete round fails
     */
    public long purgeOlderThan(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        if (dataSource == null) {
            throw new IllegalStateException("this trail has no DataSource to purge; it was built on the"
                    + " BatchWriter seam for machinery tests");
        }
        long cutoffNanos = SnapshotMapper.instantToEpochNanos(cutoff);
        try (Connection connection = dataSource.getConnection()) {
            // Auto-commit on purpose: the guard and every delete round are each their own transaction.
            connection.setAutoCommit(true);
            if (oldestRowIsFresh(connection, cutoffNanos)) {
                return 0;
            }
            long total = 0;
            int deletedInRound;
            do {
                deletedInRound = deleteOldestTailBatch(connection, cutoffNanos);
                total += deletedInRound;
            } while (deletedInRound == purgeBatchSize);
            return total;
        } catch (SQLException e) {
            throw new PersistenceException("failed to purge audit events older than " + cutoff, e);
        }
    }

    private static boolean oldestRowIsFresh(Connection connection, long cutoffNanos) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(OLDEST_OCCURRED_AT);
                ResultSet resultSet = statement.executeQuery()) {
            return !resultSet.next() || resultSet.getLong(1) >= cutoffNanos;
        }
    }

    private int deleteOldestTailBatch(Connection connection, long cutoffNanos) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_OLDEST_TAIL_BATCH)) {
            statement.setLong(1, cutoffNanos);
            statement.setInt(2, purgeBatchSize);
            return statement.executeUpdate();
        }
    }

    /**
     * Stops accepting events, flushes everything already queued, and joins the writer thread. If the
     * writer cannot finish within the close timeout — a wedged database call, say — it is interrupted
     * and the still-queued tail is <em>abandoned loudly</em>: drained into {@link #droppedCount()} so
     * the gap in the trail is visible rather than silent. The batch already in the writer's hands is
     * accounted the same way by the writer's own failure path if its wedged call ever returns.
     */
    @Override
    public void close() {
        synchronized (emitLock) {
            running = false;
        }
        try {
            if (!writer.join(closeTimeout)) {
                writer.interrupt();
                writer.join(closeTimeout);
                List<PoolEvent> abandoned = new ArrayList<>();
                queue.drainTo(abandoned);
                dropped.addAndGet(abandoned.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The writer loop: block briefly for a first event, drain a batch behind it, hand the batch to the
     * {@link BatchWriter}. Keeps flushing after {@link #close()} flips {@code running} until the queue
     * is empty, which is what makes close() lossless for accepted events.
     */
    private void writeLoop(BatchWriter batchWriter) {
        List<PoolEvent> batch = new ArrayList<>(MAX_BATCH_SIZE);
        while (running || !queue.isEmpty()) {
            try {
                PoolEvent first = queue.poll(IDLE_POLL.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH_SIZE - 1);
                try {
                    batchWriter.write(List.copyOf(batch));
                } catch (RuntimeException e) {
                    dropped.addAndGet(batch.size());
                    LOG.log(Level.WARNING, "audit batch insert failed; " + batch.size() + " events dropped", e);
                }
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static BatchWriter jdbcWriter(DataSource dataSource) {
        return batch -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
                    for (PoolEvent event : batch) {
                        AuditEventMapper.AuditRow row = AuditEventMapper.toRow(event);
                        statement.setString(1, row.eventType());
                        statement.setString(2, row.resourceKind());
                        statement.setString(3, row.resourceValue());
                        statement.setString(4, row.context());
                        statement.setLong(5, row.occurredAtNanos());
                        if (row.untilNanos() == null) {
                            statement.setNull(6, Types.BIGINT);
                        } else {
                            statement.setLong(6, row.untilNanos());
                        }
                        statement.setString(7, row.cause());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                    connection.commit();
                } catch (SQLException | RuntimeException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackEx) {
                        // Keep the original failure as the primary exception; a failed rollback is
                        // secondary context, not a replacement for what actually went wrong.
                        e.addSuppressed(rollbackEx);
                    }
                    throw e;
                } finally {
                    try {
                        connection.setAutoCommit(previousAutoCommit);
                    } catch (SQLException ignored) {
                        // Avoid masking the primary exception; the connection is closing anyway.
                    }
                }
            } catch (SQLException e) {
                throw new PersistenceException("failed to append audit events", e);
            }
        };
    }
}
