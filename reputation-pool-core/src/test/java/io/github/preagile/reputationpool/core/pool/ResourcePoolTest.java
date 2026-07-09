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
package io.github.preagile.reputationpool.core.pool;

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
import io.github.preagile.reputationpool.core.port.EventSink;
import io.github.preagile.reputationpool.core.testing.SettableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class ResourcePoolTest {

    private static final Context CTX = new Context("cpeats");
    private static final Instant NOW = Instant.parse("2026-07-08T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    private static ResourceId proxy(String value) {
        return new ResourceId(ResourceKind.PROXY, value);
    }

    private final CollectingSink sink = new CollectingSink();

    private ResourcePool poolAt(Clock clock) {
        // coolAfter = 3, recoverAfter = 2, windowSize = 10
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 3, 2);
        return new ResourcePool(engine, new WeightedRandomSelectionStrategy(), sink, clock, new Random(1), TTL);
    }

    private static Clock fixed() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static Outcome blocked() {
        return new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(1));
    }

    private static Outcome success() {
        return new Outcome.Success(Duration.ofMillis(1));
    }

    @Test
    void acquireReturnsEmptyWhenNothingIsRegistered() {
        assertThat(poolAt(fixed()).acquire(CTX)).isEmpty();
    }

    @Test
    void registerThenAcquireLendsTheResourceAndEmitsLeased() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        var lease = pool.acquire(CTX);
        assertThat(lease).isPresent();
        assertThat(lease.get().resource()).isEqualTo(proxy("p1"));
        assertThat(sink.events).hasAtLeastOneElementOfType(PoolEvent.ResourceLeased.class);
    }

    @Test
    void acquireExcludesAnAlreadyLeasedResource() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        assertThat(pool.acquire(CTX)).isPresent();
        assertThat(pool.acquire(CTX)).isEmpty(); // the only resource is now leased
    }

    @Test
    void releaseReturnsTheResourceAndEmitsLeaseReleased() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        var lease = pool.acquire(CTX).orElseThrow();
        assertThat(pool.release(lease)).isTrue();
        assertThat(pool.acquire(CTX)).isPresent(); // available again
        assertThat(sink.events).hasAtLeastOneElementOfType(PoolEvent.LeaseReleased.class);
    }

    @Test
    void renewExtendsTheLeaseAcrossWhatWouldHaveBeenExpiry() {
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        var lease = pool.acquire(CTX).orElseThrow(); // leased until NOW+5m

        clock.set(NOW.plusSeconds(240)); // 4m in
        assertThat(pool.renew(lease)).isPresent(); // now until NOW+9m
        clock.set(NOW.plusSeconds(360)); // 6m in: past the original expiry, before the renewed one

        assertThat(pool.acquire(CTX)).isEmpty(); // still held thanks to the renew
    }

    @Test
    void renewFailsForABlocklistedResource() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        var lease = pool.acquire(CTX).orElseThrow();
        pool.block(proxy("p1"), Duration.ofHours(1));
        assertThat(pool.renew(lease)).isEmpty(); // a blocklisted resource cannot be renewed
    }

    @Test
    void blockExcludesFromAcquireAndEmitsBlocklisted() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        pool.block(proxy("p1"), Duration.ofHours(1));
        assertThat(pool.acquire(CTX)).isEmpty();
        assertThat(sink.events).hasAtLeastOneElementOfType(PoolEvent.ResourceBlocklisted.class);
    }

    @Test
    void blockPermanentlySurvivesAnyAmountOfTimeUntilExplicitUnblock() {
        // coverage for #27: blockPermanently had no test at the facade
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        pool.blockPermanently(proxy("p1"));

        assertThat(pool.acquire(CTX)).isEmpty();
        clock.set(NOW.plus(Duration.ofDays(3650)));
        assertThat(pool.acquire(CTX))
                .as("no expiry ever sweeps a permanent block")
                .isEmpty();
        assertThat(sink.events)
                .filteredOn(PoolEvent.ResourceBlocklisted.class::isInstance)
                .anySatisfy(event -> assertThat(((PoolEvent.ResourceBlocklisted) event).until())
                        .isEqualTo(Instant.MAX));

        pool.unblock(proxy("p1"));
        assertThat(pool.acquire(CTX)).as("only an explicit unblock releases it").isPresent();
    }

    @Test
    void unblockMakesItAcquirableAgainAndEmitsUnblocked() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        pool.block(proxy("p1"), Duration.ofHours(1));
        pool.unblock(proxy("p1"));
        assertThat(pool.acquire(CTX)).isPresent();
        assertThat(sink.events).hasAtLeastOneElementOfType(PoolEvent.ResourceUnblocked.class);
    }

    @Test
    void reportedFailuresCoolTheResourceExcludingItAndEmitResourceCooled() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        for (int i = 0; i < 3; i++) { // coolAfter = 3
            pool.report(proxy("p1"), CTX, blocked());
        }
        assertThat(sink.events).hasAtLeastOneElementOfType(PoolEvent.ResourceCooled.class);
        assertThat(pool.acquire(CTX)).isEmpty(); // COOLING is not selectable
    }

    @Test
    void aCooledResourceRecoversAfterCooldownThenSuccesses() {
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        for (int i = 0; i < 3; i++) {
            pool.report(proxy("p1"), CTX, blocked()); // -> COOLING (BLOCKED cooldown is hours)
        }
        assertThat(pool.acquire(CTX)).isEmpty(); // cooling

        clock.set(NOW.plusSeconds(5 * 3600)); // past the BLOCKED cooldown (~4h)
        pool.report(proxy("p1"), CTX, success()); // COOLING -> RECOVERING
        pool.report(proxy("p1"), CTX, success()); // recoverAfter = 2 -> HEALTHY

        assertThat(sink.events).hasAtLeastOneElementOfType(PoolEvent.ResourceRecovered.class);
        assertThat(pool.acquire(CTX)).isPresent(); // selectable again
    }

    @Test
    void rejectsNullConstructorArgumentsAndNonPositiveTtl() {
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 3, 2);
        var strategy = new WeightedRandomSelectionStrategy();
        var random = new Random(1);
        assertThatThrownBy(() -> new ResourcePool(null, strategy, sink, fixed(), random, TTL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResourcePool(engine, null, sink, fixed(), random, TTL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResourcePool(engine, strategy, null, fixed(), random, TTL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResourcePool(engine, strategy, sink, null, random, TTL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResourcePool(engine, strategy, sink, fixed(), null, TTL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResourcePool(engine, strategy, sink, fixed(), random, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullMethodArguments() {
        var pool = poolAt(fixed());
        assertThatThrownBy(() -> pool.acquire(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pool.register(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pool.report(null, CTX, success())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pool.report(proxy("p1"), CTX, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pool.block(proxy("p1"), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pool.block(proxy("p1"), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pool.renew(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pool.release(null)).isInstanceOf(NullPointerException.class);
    }

    // --- concurrency: pool-level lease exclusivity and the report path (#27) ---
    // repeated: a single run can stay green on a 1-in-N race; repetition raises the catch rate

    @RepeatedTest(5)
    void concurrentAcquireNeverLendsTheSameResourceTwice() throws Exception {
        var pool = poolAt(fixed());
        pool.register(proxy("p0"));
        pool.register(proxy("p1"));
        pool.register(proxy("p2"));

        int threads = 32;
        var acquired = new CopyOnWriteArrayList<ResourceId>();
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var startGate = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    pool.acquire(CTX).ifPresent(lease -> acquired.add(lease.resource()));
                    return null;
                }));
            }
            startGate.countDown();
            for (var future : futures) {
                future.get();
            }
        }

        // no resource was handed out twice, and no more than the three registered were leased
        assertThat(new HashSet<>(acquired)).hasSameSizeAs(acquired);
        assertThat(acquired).hasSizeLessThanOrEqualTo(3);
    }

    @RepeatedTest(10)
    void concurrentBlockedReportsNeverLoseTheCoolingTransition() throws Exception {
        // report() is the highest-frequency production call; a lost update on the per-key compute
        // would either miss the coolAfter threshold (no cooled event) or double-fire the transition
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));

        int threads = 16;
        int reportsPerThread = 50;
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var startGate = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    for (int j = 0; j < reportsPerThread; j++) {
                        pool.report(proxy("p1"), CTX, blocked());
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (var future : futures) {
                future.get();
            }
        }

        // 800 racing failures cross coolAfter = 3 exactly once: the clock is fixed, so the first
        // cooldown never expires and every later failure lands inside it (no extension, no re-fire)
        assertThat(sink.events.stream()
                        .filter(PoolEvent.ResourceCooled.class::isInstance)
                        .count())
                .isEqualTo(1);
        assertThat(pool.acquire(CTX)).as("the cooled resource is not lendable").isEmpty();
    }

    /** An {@link EventSink} that records everything it receives, safe for the concurrency test. */
    private static final class CollectingSink implements EventSink {
        private final List<PoolEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(PoolEvent event) {
            events.add(event);
        }
    }
}
