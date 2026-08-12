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
package io.github.preagile.reputationpool.prober;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.CellKey;
import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.domain.ResourceState;
import io.github.preagile.reputationpool.core.engine.CooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.pool.WeightedRandomSelectionStrategy;
import io.github.preagile.reputationpool.core.port.EventSink;
import io.github.preagile.reputationpool.core.testing.SettableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Behaviour specification for {@link RecoveryScheduler} — the component that closes the gap
 * documented on {@code ResourcePool#dueForRecoveryProbe}: a {@code COOLING} cell past its cooldown
 * has no lease-driven traffic left to recover it, so this class tests it directly instead.
 *
 * <p>Every test uses a real {@link ResourcePool} (it is a {@code final} class, not an interface) with
 * a tiny fixed {@link CooldownPolicy} so the engine's real cooldown curve is genuinely, not just
 * nominally, in play — a few tens of milliseconds rather than the minutes an {@link
 * io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy} would need for even the
 * lightest failure. This keeps the tests fast while still exercising the real engine transitions
 * (COOLING → RECOVERING → HEALTHY), never a mock standing in for them. Waiting on background work
 * uses real {@link CountDownLatch}es and a small polling helper, matching this repo's other
 * concurrency tests (see {@code EventBroadcasterTest}) rather than pulling in a new test dependency.
 */
class RecoverySchedulerTest {

    private static final Context CTX = new Context("cpeats");
    private static final ResourceId PROXY_1 = new ResourceId(ResourceKind.PROXY, "p1");
    private static final Instant NOW = Instant.parse("2026-07-08T00:00:00Z");
    private static final Duration TINY_COOLDOWN = Duration.ofMillis(60);

    /** coolAfter = 1, recoverAfter = 1: one failure cools it, one successful probe fully recovers it. */
    private static ResourcePool poolWithTinyCooldown(SettableClock clock, EventSink sink) {
        CooldownPolicy tiny = (type, consecutiveFailures) -> TINY_COOLDOWN;
        var engine = new ReputationEngine(tiny, 10, 1, 1);
        return new ResourcePool(
                engine, new WeightedRandomSelectionStrategy(), sink, clock, new Random(1), Duration.ofMinutes(5));
    }

    /**
     * A transport failure, deliberately not a {@code BLOCKED} one: since #90 the pool hands a
     * {@code BLOCKED}-cooled cell to half-open admission rather than to a prober, so
     * {@code dueForRecoveryProbe} would never name it and the backstop tests below would pass
     * vacuously. {@code TIMEOUT} is squarely the prober's half of that split.
     */
    private static Outcome timedOut() {
        return new Outcome.Failure(FailureType.TIMEOUT, Duration.ofMillis(1));
    }

    private static Outcome success() {
        return new Outcome.Success(Duration.ofMillis(1));
    }

    private static PoolEvent.ResourceCooled firstCooledEvent(List<PoolEvent> events) {
        return events.stream()
                .filter(PoolEvent.ResourceCooled.class::isInstance)
                .map(PoolEvent.ResourceCooled.class::cast)
                .findFirst()
                .orElseThrow();
    }

    /** Polls a real-time condition, matching how this repo's other concurrency tests wait without a library. */
    private static void awaitTrue(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(10);
        }
    }

    private static ResourceState stateOf(ResourcePool pool, ResourceId resource, Context context) {
        var cell = pool.snapshot().cells().get(new CellKey(resource, context));
        return cell == null ? null : cell.state();
    }

    @Test
    void resourceCooledEventProbesAndFullyRecoversTheResourceWithNoLeaseEverTaken() throws Exception {
        var clock = new SettableClock(NOW);
        List<PoolEvent> recorded = new CopyOnWriteArrayList<>();
        var pool = poolWithTinyCooldown(clock, recorded::add);
        pool.register(PROXY_1);

        var probed = new CountDownLatch(1);
        RecoveryProbe alwaysHealthy = (resource, context) -> {
            probed.countDown();
            return Optional.of(success());
        };
        try (var scheduler = new RecoveryScheduler(
                pool, Map.of(ResourceKind.PROXY, alwaysHealthy), clock, new Random(1), Duration.ZERO)) {
            pool.report(PROXY_1, CTX, timedOut()); // -> COOLING; emits ResourceCooled into `recorded`
            assertThat(pool.acquire(CTX)).isEmpty(); // genuinely excluded, not a vacuous setup

            // The scheduled delay is computed from the clock, but the pool's own `now` (read again inside
            // `report()` when the probe's outcome lands) is that same injected clock too — advancing it
            // past the cooldown here is what makes the eventual success actually satisfy the engine's
            // "cooldown has expired" check, the same as real wall-clock time passing would in production.
            clock.set(NOW.plus(TINY_COOLDOWN).plusMillis(1));
            scheduler.emit(firstCooledEvent(recorded));

            assertThat(probed.await(2, TimeUnit.SECONDS)).isTrue();
            awaitTrue(() -> stateOf(pool, PROXY_1, CTX) == ResourceState.HEALTHY, Duration.ofSeconds(2));
            assertThat(recorded).hasAtLeastOneElementOfType(PoolEvent.ResourceRecovered.class);
        }
    }

    @Test
    void backstopSweepProbesACandidateTheEventPathNeverSaw() throws Exception {
        var clock = new SettableClock(NOW);
        var pool = poolWithTinyCooldown(clock, event -> {});
        pool.register(PROXY_1);
        pool.report(PROXY_1, CTX, timedOut()); // -> COOLING, but no scheduler was listening yet

        clock.set(NOW.plus(TINY_COOLDOWN).plusMillis(1)); // now past the cooldown
        var probed = new CountDownLatch(1);
        RecoveryProbe alwaysHealthy = (resource, context) -> {
            probed.countDown();
            return Optional.of(success());
        };
        try (var scheduler = new RecoveryScheduler(
                pool, Map.of(ResourceKind.PROXY, alwaysHealthy), clock, new Random(1), Duration.ZERO)) {
            scheduler.backstopSweep();
            assertThat(probed.await(2, TimeUnit.SECONDS)).isTrue();
            awaitTrue(() -> stateOf(pool, PROXY_1, CTX) == ResourceState.HEALTHY, Duration.ofSeconds(2));
        }
    }

    @Test
    void aResourceKindWithNoRegisteredProbeIsNeverDispatched() throws Exception {
        var clock = new SettableClock(NOW);
        var pool = poolWithTinyCooldown(clock, event -> {});
        var account = new ResourceId(ResourceKind.ACCOUNT, "acc1");
        pool.register(account);
        pool.report(account, CTX, timedOut());
        clock.set(NOW.plus(TINY_COOLDOWN).plusMillis(1));

        try (var scheduler = new RecoveryScheduler(
                pool,
                Map.of(ResourceKind.PROXY, (r, c) -> Optional.of(success())),
                clock,
                new Random(1),
                Duration.ZERO)) {
            scheduler.backstopSweep(); // PROXY probe registered, but this resource is an ACCOUNT
            Thread.sleep(200); // nothing to await on; give a dispatch a chance to happen if it wrongly would
            assertThat(stateOf(pool, account, CTX)).isEqualTo(ResourceState.COOLING); // no probe ever ran
        }
    }

    @Test
    void aProbeThatReturnsEmptyReportsNothingAndLeavesTheResourceCooling() throws Exception {
        var clock = new SettableClock(NOW);
        var pool = poolWithTinyCooldown(clock, event -> {});
        pool.register(PROXY_1);
        pool.report(PROXY_1, CTX, timedOut());
        clock.set(NOW.plus(TINY_COOLDOWN).plusMillis(1));

        var probed = new CountDownLatch(1);
        RecoveryProbe unresolvable = (resource, context) -> {
            probed.countDown();
            return Optional.empty();
        };
        try (var scheduler = new RecoveryScheduler(
                pool, Map.of(ResourceKind.PROXY, unresolvable), clock, new Random(1), Duration.ZERO)) {
            scheduler.backstopSweep();
            assertThat(probed.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100); // let a (wrongly) dispatched report land, if it ever would
            assertThat(stateOf(pool, PROXY_1, CTX)).isEqualTo(ResourceState.COOLING);
        }
    }

    @Test
    void aCooldownCausedByASiteBlockIsNotProbedOnTheEventPathEither() throws Exception {
        // The event path used to schedule off every ResourceCooled, so in an assembly that wires this
        // scheduler as an event sink a BLOCKED-cooled cell got a synthetic probe anyway — the very cell
        // dueForRecoveryProbe excludes because only real traffic can judge a site block. Both paths have
        // to apply the same rule or the split is not a partition (#99).
        var clock = new SettableClock(NOW);
        List<PoolEvent> recorded = new CopyOnWriteArrayList<>();
        var pool = poolWithTinyCooldown(clock, recorded::add);
        pool.register(PROXY_1);

        var probed = new AtomicInteger();
        RecoveryProbe alwaysHealthy = (resource, context) -> {
            probed.incrementAndGet();
            return Optional.of(success());
        };
        try (var scheduler = new RecoveryScheduler(
                pool, Map.of(ResourceKind.PROXY, alwaysHealthy), clock, new Random(1), Duration.ZERO)) {
            pool.report(PROXY_1, CTX, new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(1)));
            clock.set(NOW.plus(TINY_COOLDOWN).plusMillis(1));

            scheduler.emit(firstCooledEvent(recorded));
            scheduler.backstopSweep(); // the other path already excluded it; assert they now agree

            Thread.sleep(200); // long enough for a (wrongly) scheduled zero-jitter probe to have fired
            assertThat(probed)
                    .as("a site block is half-open's to retry, not the prober's")
                    .hasValue(0);
            // and the cell is left for acquire's half-open admission, exactly where the split puts it
            assertThat(stateOf(pool, PROXY_1, CTX)).isEqualTo(ResourceState.COOLING);
            assertThat(pool.acquire(CTX)).isPresent();
        }
    }

    @Test
    void aProbeThatThrowsIsIsolatedAndFreesTheKeyForTheNextAttempt() throws Exception {
        var clock = new SettableClock(NOW);
        var pool = poolWithTinyCooldown(clock, event -> {});
        pool.register(PROXY_1);
        pool.report(PROXY_1, CTX, timedOut());
        clock.set(NOW.plus(TINY_COOLDOWN).plusMillis(1));

        var attempts = new AtomicInteger();
        var secondAttempt = new CountDownLatch(1);
        RecoveryProbe flaky = (resource, context) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("transient probe failure");
            }
            secondAttempt.countDown();
            return Optional.of(success());
        };
        try (var scheduler =
                new RecoveryScheduler(pool, Map.of(ResourceKind.PROXY, flaky), clock, new Random(1), Duration.ZERO)) {
            scheduler.backstopSweep(); // first attempt throws; must not escape and must not stay "pending" forever
            awaitTrue(() -> attempts.get() == 1, Duration.ofSeconds(2));

            // The key frees itself asynchronously right after the first attempt's `finally` runs — a
            // small, harmless race with this thread. Re-sweeping is idempotent (scheduleAt dedupes), so
            // retrying until it lands is the correct way to wait, not a fixed sleep.
            awaitTrue(
                    () -> {
                        scheduler.backstopSweep();
                        return secondAttempt.getCount() == 0;
                    },
                    Duration.ofSeconds(2));
            awaitTrue(() -> stateOf(pool, PROXY_1, CTX) == ResourceState.HEALTHY, Duration.ofSeconds(2));
        }
    }

    @Test
    void concurrentEmitsForTheSameCellDispatchExactlyOnce() throws Exception {
        var clock = new SettableClock(NOW);
        List<PoolEvent> recorded = new CopyOnWriteArrayList<>();
        var pool = poolWithTinyCooldown(clock, recorded::add);
        pool.register(PROXY_1);
        pool.report(PROXY_1, CTX, timedOut());
        PoolEvent.ResourceCooled cooled = firstCooledEvent(recorded);
        // Past the cooldown before any probe can land, so the eventual success genuinely satisfies the
        // engine's "cooldown has expired" check — same reasoning as the event-path test above.
        clock.set(NOW.plus(TINY_COOLDOWN).plusMillis(1));

        var invocationCount = new AtomicInteger();
        RecoveryProbe counting = (resource, context) -> {
            invocationCount.incrementAndGet();
            return Optional.of(success());
        };
        // A longer-than-instant delay (`until` well ahead of the now-advanced clock) keeps the dedupe
        // window open long enough for every racing thread to land its emit() before dispatch fires.
        var farCooled = new PoolEvent.ResourceCooled(
                cooled.resource(), cooled.context(), cooled.at(), NOW.plusMillis(300), cooled.cause());

        ExecutorService racers = Executors.newFixedThreadPool(8);
        try (var scheduler = new RecoveryScheduler(
                pool, Map.of(ResourceKind.PROXY, counting), clock, new Random(1), Duration.ZERO)) {
            var ready = new CountDownLatch(1);
            var done = new CountDownLatch(16);
            for (int i = 0; i < 16; i++) {
                racers.execute(() -> {
                    try {
                        ready.await();
                        scheduler.emit(farCooled);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.countDown();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();

            awaitTrue(() -> stateOf(pool, PROXY_1, CTX) == ResourceState.HEALTHY, Duration.ofSeconds(2));
            assertThat(invocationCount.get()).isEqualTo(1);
        } finally {
            racers.shutdownNow();
        }
    }

    @RepeatedTest(20)
    void manyResourcesCoolingTogetherEachGetProbedExactlyOnce() throws Exception {
        var clock = new SettableClock(NOW);
        var pool = poolWithTinyCooldown(clock, event -> {});
        int resourceCount = 12;
        var resources = new ArrayList<ResourceId>();
        for (int i = 0; i < resourceCount; i++) {
            var id = new ResourceId(ResourceKind.PROXY, "p" + i);
            resources.add(id);
            pool.register(id);
            pool.report(id, CTX, timedOut());
        }
        clock.set(NOW.plus(TINY_COOLDOWN).plusMillis(1));

        var invocations = new ConcurrentHashMap<ResourceId, AtomicInteger>();
        // Holds every probe until both sweeps have finished scheduling, so the sweeps genuinely
        // overlap. Without it the two are only racing by luck: a probe that finishes before the second
        // sweep reaches its resource frees the key in `dispatch`'s finally, and the second sweep then
        // schedules that resource again — correct behaviour (dedupe covers a window, not all time),
        // but it would make this assertion a coin flip on a loaded machine rather than a statement
        // about dedupe. Same reasoning as the far-future `until` in the concurrent-emit test above.
        var bothSweepsScheduled = new CountDownLatch(1);
        RecoveryProbe counting = (resource, context) -> {
            try {
                bothSweepsScheduled.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            invocations.computeIfAbsent(resource, r -> new AtomicInteger()).incrementAndGet();
            return Optional.of(success());
        };
        try (var scheduler = new RecoveryScheduler(
                pool, Map.of(ResourceKind.PROXY, counting), clock, new Random(1), Duration.ZERO)) {
            // Two overlapping sweeps racing the same candidates, the way an event-path schedule and a
            // backstop sweep could genuinely overlap in production.
            var sweeper = Executors.newFixedThreadPool(2);
            sweeper.execute(scheduler::backstopSweep);
            sweeper.execute(scheduler::backstopSweep);
            sweeper.shutdown();
            try {
                assertThat(sweeper.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                bothSweepsScheduled.countDown(); // never leave a probe thread parked, even on failure
            }

            awaitTrue(
                    () -> resources.stream().allMatch(id -> stateOf(pool, id, CTX) == ResourceState.HEALTHY),
                    Duration.ofSeconds(2));
            for (ResourceId id : resources) {
                assertThat(invocations.get(id))
                        .describedAs("probe count for %s", id)
                        .hasValue(1);
            }
        }
    }

    @Test
    void rejectsNullConstructorArguments() {
        var clock = new SettableClock(NOW);
        var pool = poolWithTinyCooldown(clock, event -> {});
        var probes = Map.<ResourceKind, RecoveryProbe>of();
        var random = new Random(1);
        assertThatThrownBy(() -> new RecoveryScheduler(null, probes, clock, random))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecoveryScheduler(pool, null, clock, random))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecoveryScheduler(pool, probes, null, random))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecoveryScheduler(pool, probes, clock, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecoveryScheduler(pool, probes, clock, random, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANegativeMaxJitter() {
        var clock = new SettableClock(NOW);
        var pool = poolWithTinyCooldown(clock, event -> {});
        assertThatThrownBy(() -> new RecoveryScheduler(pool, Map.of(), clock, new Random(1), Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeStopsAcceptingNewWorkWithoutThrowing() {
        var clock = new SettableClock(NOW);
        var pool = poolWithTinyCooldown(clock, event -> {});
        pool.register(PROXY_1);
        pool.report(PROXY_1, CTX, timedOut());
        clock.set(NOW.plus(TINY_COOLDOWN).plusMillis(1));

        var scheduler = new RecoveryScheduler(
                pool, Map.of(ResourceKind.PROXY, (r, c) -> Optional.of(success())), clock, new Random(1));
        scheduler.close();
        assertThatCode(scheduler::backstopSweep).doesNotThrowAnyException();
    }
}
