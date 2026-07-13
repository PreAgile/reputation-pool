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

import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Behavior specification for the queue-and-writer machinery of {@link PostgresAuditTrail}, exercised
 * through the {@link PostgresAuditTrail.BatchWriter} seam so no database is involved — these tests run
 * in the Docker-free {@code build}. What is defended here is the {@code EventSink} contract the core
 * relies on: {@code emit} runs on the pool's own thread and must never block, whatever the database is
 * doing. Overflow therefore drops (and counts) rather than blocks — pool availability wins over trail
 * completeness, and the counter is how that loss stays visible.
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
