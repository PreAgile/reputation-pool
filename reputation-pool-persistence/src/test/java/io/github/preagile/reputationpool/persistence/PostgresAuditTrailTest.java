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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.pool.Lease;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.pool.WeightedRandomSelectionStrategy;
import io.github.preagile.reputationpool.core.testing.SettableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Behavior specification for {@link PostgresAuditTrail}, database-free via the
 * {@link PostgresAuditTrail.BatchWriter} seam so it runs in the Docker-free {@code build}. Two layers:
 *
 * <ul>
 *   <li><b>The usage story first.</b> The opening test shows what this class is <em>for</em>: plugged
 *       into a real {@link ResourcePool} as its one {@code EventSink}, it turns the pool's
 *       lease&#8594;fail&#8594;cool lifecycle into an ordered, replayable history. Read that test to
 *       learn how the class is wired and what flows through it.
 *   <li><b>Then the contract defense.</b> The remaining tests pin the {@code EventSink} promise the
 *       core relies on: {@code emit} runs on the pool's own thread and must never block, whatever the
 *       database is doing. Overflow therefore drops (and counts) rather than blocks — pool
 *       availability wins over trail completeness, and {@code droppedCount()} is how that loss stays
 *       visible — including under real multi-thread contention and an emit racing {@code close()}.
 * </ul>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class PostgresAuditTrailTest {

    private static final ResourceId RID = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
    private static final Instant AT = Instant.parse("2026-07-13T00:00:00Z");

    /** Distinct events (by timestamp) so order and identity are both assertable. */
    private static PoolEvent event(int i) {
        return new PoolEvent.ResourceUnblocked(RID, AT.plusSeconds(i));
    }

    @Test
    @DisplayName("the trail is the pool's observation terminus: plugged in as its EventSink, it records"
            + " the lease-fail-cool story a pool lives through, in the order it happened")
    void recordsThePoolsLifecycleStory() {
        Context market = new Context("marketplace-a");
        List<PoolEvent> written = Collections.synchronizedList(new ArrayList<>());

        // The production wiring, minus JDBC: the trail IS the pool's one EventSink, receiving every
        // state transition on the pool thread that caused it. (In production the DataSource
        // constructor supplies the BatchWriter; the recording seam here stands in for the database.)
        try (PostgresAuditTrail trail = new PostgresAuditTrail(written::addAll, 64)) {
            ResourcePool pool = new ResourcePool(
                    new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 2, 2),
                    new WeightedRandomSelectionStrategy(),
                    trail,
                    new SettableClock(AT),
                    new Random(42),
                    Duration.ofSeconds(30));

            // A caller borrows the proxy, it times out twice (coolAfter=2), and the lease goes back.
            pool.register(RID);
            Lease lease = pool.acquire(market).orElseThrow();
            pool.report(RID, market, new Outcome.Failure(FailureType.TIMEOUT, Duration.ofSeconds(2)));
            pool.report(RID, market, new Outcome.Failure(FailureType.TIMEOUT, Duration.ofSeconds(2)));
            pool.release(lease);
        }

        // close() flushed the trail; it now tells the story: leased, cooled on the second consecutive
        // failure, released. This ordered history is what the snapshot's whole-replace cannot answer.
        assertThat(written).hasSize(3);
        assertThat(written.get(0)).isInstanceOfSatisfying(PoolEvent.ResourceLeased.class, leased -> {
            assertThat(leased.resource()).isEqualTo(RID);
            assertThat(leased.context()).isEqualTo(market);
            assertThat(leased.until()).isEqualTo(AT.plusSeconds(30));
        });
        assertThat(written.get(1)).isInstanceOfSatisfying(PoolEvent.ResourceCooled.class, cooled -> {
            assertThat(cooled.resource()).isEqualTo(RID);
            assertThat(cooled.cause()).isEqualTo(FailureType.TIMEOUT);
        });
        assertThat(written.get(2)).isInstanceOf(PoolEvent.LeaseReleased.class);
    }

    @Test
    @DisplayName("many pool threads emitting at once lose nothing silently: written + dropped == emitted,"
            + " no duplicates, and each thread's events keep their order")
    void concurrentEmittersLoseNothingSilently() throws InterruptedException {
        int emitters = 8;
        int perEmitter = 200;
        List<PoolEvent> written = Collections.synchronizedList(new ArrayList<>());
        // A small queue against a live (unstalled) writer, so real contention decides what overflows.
        PostgresAuditTrail trail = new PostgresAuditTrail(written::addAll, 64);

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < emitters; t++) {
            int emitterId = t;
            threads.add(Thread.ofPlatform().start(() -> {
                await(start);
                for (int i = 0; i < perEmitter; i++) {
                    trail.emit(event(emitterId * perEmitter + i));
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        trail.close();

        // Conservation: every emitted event was either written or counted as dropped — never lost
        // silently. This is the exact contract the emit/close race would have broken.
        assertThat(written.size() + trail.droppedCount()).isEqualTo((long) emitters * perEmitter);
        assertThat(written).doesNotHaveDuplicates();
        // Per-emitter order: each thread emitted ascending timestamps, and the queue is FIFO, so each
        // thread's accepted events must reappear in that same order.
        for (int t = 0; t < emitters; t++) {
            Instant lo = AT.plusSeconds((long) t * perEmitter);
            Instant hi = AT.plusSeconds((long) (t + 1) * perEmitter);
            List<PoolEvent> mine = written.stream()
                    .filter(e -> !e.at().isBefore(lo) && e.at().isBefore(hi))
                    .toList();
            assertThat(mine).isSortedAccordingTo(Comparator.comparing(PoolEvent::at));
        }
    }

    @Test
    @DisplayName("close() racing active emitters still loses nothing silently: every attempted event"
            + " is either written or counted as dropped")
    void closeRacingEmittersLosesNothingSilently() throws InterruptedException {
        int emitters = 8;
        int perEmitter = 200;
        List<PoolEvent> written = Collections.synchronizedList(new ArrayList<>());
        PostgresAuditTrail trail = new PostgresAuditTrail(written::addAll, 64);

        CountDownLatch start = new CountDownLatch(1);
        // Counted down once by every emitter at its halfway mark, so the close() below is guaranteed
        // to run while all threads are still actively emitting — the window emitLock guards, which
        // the contention test above (emit fully before close) never opens.
        CountDownLatch halfway = new CountDownLatch(emitters);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < emitters; t++) {
            int emitterId = t;
            threads.add(Thread.ofPlatform().start(() -> {
                await(start);
                for (int i = 0; i < perEmitter; i++) {
                    trail.emit(event(emitterId * perEmitter + i));
                    if (i == perEmitter / 2) {
                        halfway.countDown();
                    }
                }
            }));
        }
        start.countDown();
        await(halfway);
        trail.close();
        for (Thread thread : threads) {
            thread.join();
        }

        // Conservation must hold under every interleaving, not just the ones this run happened to
        // schedule: an emit before the flag flip was accepted (and flushed) or overflowed (and
        // counted); one after was rejected (and counted). The defect emitLock closed — offered into a
        // queue whose writer already exited, neither written nor counted — is the only way this sum
        // breaks. (Forcing specific interleavings is Lincheck's job, tracked as follow-up work.)
        assertThat(written.size() + trail.droppedCount()).isEqualTo((long) emitters * perEmitter);
        assertThat(written).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a slow audit writer never blocks the pool thread that emitted")
    void slowWriterNeverBlocksEmit() throws InterruptedException {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch writerReleased = new CountDownLatch(1);
        List<PoolEvent> written = Collections.synchronizedList(new ArrayList<>());
        PostgresAuditTrail trail = new PostgresAuditTrail(
                batch -> {
                    writerEntered.countDown();
                    await(writerReleased);
                    written.addAll(batch);
                },
                4);
        try {
            // Stall the writer inside its first batch, so the queue drains nowhere.
            trail.emit(event(0));
            assertThat(writerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // The class-level @Timeout is the real assertion: were emit ever to block on the stalled
            // writer or the full queue, these calls would hang and fail the test.
            for (int i = 1; i <= 10; i++) {
                trail.emit(event(i));
            }
        } finally {
            writerReleased.countDown();
            trail.close();
        }
        assertThat(written).isNotEmpty();
    }

    @Test
    @DisplayName("overflowing the buffer drops the event and increments the dropped counter")
    void overflowDropsAndCounts() throws InterruptedException {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch writerReleased = new CountDownLatch(1);
        List<PoolEvent> written = Collections.synchronizedList(new ArrayList<>());
        PostgresAuditTrail trail = new PostgresAuditTrail(
                batch -> {
                    writerEntered.countDown();
                    await(writerReleased);
                    written.addAll(batch);
                },
                4);

        // The writer is stalled holding event 0; the queue (capacity 4) then fills with 1..4.
        trail.emit(event(0));
        assertThat(writerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 1; i <= 4; i++) {
            trail.emit(event(i));
        }

        // Three more arrivals overflow: dropped and counted, never blocking the emitter.
        for (int i = 5; i <= 7; i++) {
            trail.emit(event(i));
        }
        assertThat(trail.droppedCount()).isEqualTo(3);

        writerReleased.countDown();
        trail.close();

        // Everything that was accepted survives, in emission order; only the overflow is missing.
        assertThat(written)
                .containsExactlyElementsOf(IntStream.rangeClosed(0, 4)
                        .mapToObj(PostgresAuditTrailTest::event)
                        .toList());
    }

    @Test
    @DisplayName("the writer preserves emission order")
    void writerPreservesEmissionOrder() {
        List<PoolEvent> written = Collections.synchronizedList(new ArrayList<>());
        PostgresAuditTrail trail = new PostgresAuditTrail(written::addAll, 1024);

        List<PoolEvent> emitted =
                IntStream.range(0, 300).mapToObj(PostgresAuditTrailTest::event).toList();
        emitted.forEach(trail::emit);
        trail.close();

        assertThat(written).containsExactlyElementsOf(emitted);
    }

    @Test
    @DisplayName("a failing batch insert is counted as dropped and the writer keeps going")
    void failedBatchIsCountedAndWriterSurvives() throws InterruptedException {
        CountDownLatch firstAttempt = new CountDownLatch(1);
        AtomicBoolean failNext = new AtomicBoolean(true);
        List<PoolEvent> written = Collections.synchronizedList(new ArrayList<>());
        PostgresAuditTrail trail = new PostgresAuditTrail(
                batch -> {
                    try {
                        if (failNext.getAndSet(false)) {
                            throw new PersistenceException("database unavailable", null);
                        }
                        written.addAll(batch);
                    } finally {
                        firstAttempt.countDown();
                    }
                },
                16);

        trail.emit(event(0));
        assertThat(firstAttempt.await(5, TimeUnit.SECONDS)).isTrue();
        trail.emit(event(1));
        trail.close();

        assertThat(trail.droppedCount()).isEqualTo(1);
        assertThat(written).containsExactly(event(1));
    }

    @Test
    @DisplayName("close() flushes every accepted event, then rejects (and counts) late arrivals")
    void closeFlushesThenRejects() {
        List<PoolEvent> written = Collections.synchronizedList(new ArrayList<>());
        PostgresAuditTrail trail = new PostgresAuditTrail(written::addAll, 16);

        trail.emit(event(0));
        trail.emit(event(1));
        trail.close();

        assertThat(written).containsExactly(event(0), event(1));

        trail.emit(event(2));
        assertThat(trail.droppedCount()).isEqualTo(1);
        assertThat(written).containsExactly(event(0), event(1));
    }

    @Test
    @DisplayName("a writer wedged past the close timeout is abandoned loudly: the unflushed tail is"
            + " counted as dropped, never silently lost")
    void wedgedWriterAbandonsTailLoudly() throws InterruptedException {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch databaseAnswers = new CountDownLatch(1);
        List<PoolEvent> written = Collections.synchronizedList(new ArrayList<>());
        // A JDBC call stuck on an unresponsive database: it holds its batch and ignores interrupts,
        // exactly what close() cannot wait forever for. The short timeout keeps the test fast.
        PostgresAuditTrail trail = new PostgresAuditTrail(
                batch -> {
                    writerEntered.countDown();
                    awaitUninterruptibly(databaseAnswers);
                    written.addAll(batch);
                },
                16,
                Duration.ofMillis(200));

        trail.emit(event(0));
        assertThat(writerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        // Two more events queue up behind the wedged batch and can never be flushed.
        trail.emit(event(1));
        trail.emit(event(2));

        trail.close();

        // The queued tail is drained into the dropped counter — the trail's gap is visible. (The
        // in-flight batch, event 0, is accounted by the writer's own failure path if its call ever
        // returns; here it eventually completes once the "database" answers.)
        assertThat(trail.droppedCount()).isEqualTo(2);
        databaseAnswers.countDown();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("a non-positive queue capacity is rejected at construction")
    void rejectsNonPositiveCapacity() {
        PostgresAuditTrail.BatchWriter noop = batch -> {};
        assertThatThrownBy(() -> new PostgresAuditTrail(noop, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queueCapacity");
        assertThatThrownBy(() -> new PostgresAuditTrail(noop, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stalling the writer", e);
        }
    }
}
