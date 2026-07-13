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
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
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
 * so an orderly shutdown loses nothing that was accepted. The event&#8594;row translation lives in
 * {@link AuditEventMapper} as pure functions; this class owns only the queue, the writer thread, and
 * the SQL.
 */
public final class PostgresAuditTrail implements EventSink, AutoCloseable {

    static final int DEFAULT_QUEUE_CAPACITY = 1024;

    /** Upper bound of one drained batch; keeps a single transaction (and its latency) small. */
    private static final int MAX_BATCH_SIZE = 128;

    private static final Duration IDLE_POLL = Duration.ofMillis(100);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Logger LOG = System.getLogger(PostgresAuditTrail.class.getName());

    private static final String INSERT_EVENT =
            """
            INSERT INTO audit_event (event_type, resource_kind, resource_value, context, occurred_at,
                until, cause)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    /** The seam between the queue/writer machinery and JDBC, so the machinery tests need no database. */
    interface BatchWriter {
        void write(List<PoolEvent> batch);
    }

    private final BlockingQueue<PoolEvent> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private volatile boolean running = true;

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
        this(jdbcWriter(Objects.requireNonNull(dataSource, "dataSource must not be null")), queueCapacity);
    }

    PostgresAuditTrail(BatchWriter batchWriter, int queueCapacity) {
        Objects.requireNonNull(batchWriter, "batchWriter must not be null");
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
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
        if (!running || !queue.offer(event)) {
            dropped.incrementAndGet();
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

    /** Stops accepting events, flushes everything already queued, and joins the writer thread. */
    @Override
    public void close() {
        running = false;
        try {
            if (!writer.join(CLOSE_TIMEOUT)) {
                writer.interrupt();
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
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
                connection.setAutoCommit(false);
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
            } catch (SQLException e) {
                throw new PersistenceException("failed to append audit events", e);
            }
        };
    }
}
