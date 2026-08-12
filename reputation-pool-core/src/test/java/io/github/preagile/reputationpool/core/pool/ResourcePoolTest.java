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
import io.github.preagile.reputationpool.core.port.MetricsSink;
import io.github.preagile.reputationpool.core.testing.SettableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
    private final RecordingMetrics metrics = new RecordingMetrics();

    private ResourcePool poolAt(Clock clock) {
        // coolAfter = 3, recoverAfter = 2, windowSize = 10
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 3, 2);
        return new ResourcePool(
                engine, new WeightedRandomSelectionStrategy(), sink, metrics, clock, new Random(1), TTL);
    }

    private static Clock fixed() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static Outcome blocked() {
        return new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(1));
    }

    /** A transport failure — the prober's half of the split, as opposed to {@link #blocked()}. */
    private static Outcome timedOut() {
        return new Outcome.Failure(FailureType.TIMEOUT, Duration.ofMillis(1));
    }

    private static Outcome success() {
        return new Outcome.Success(Duration.ofMillis(1));
    }

    /** Reports {@code coolAfter} identical failures, which is what drives a fresh cell into COOLING. */
    private static void coolWith(ResourcePool pool, ResourceId resource, Outcome failure) {
        for (int i = 0; i < 3; i++) { // coolAfter = 3
            pool.report(resource, CTX, failure);
        }
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
    void acquireUndoesItsClaimWhenTheResourceIsBlockedBetweenSnapshotAndClaim() {
        // Opens the undo window (blocklist changes between acquire's snapshot and its claim) from the
        // inside: the selection strategy runs exactly in that window. This is the path's one
        // deterministic guard — no black-box concurrency oracle can witness the undo's promise
        // (see ResourcePoolBlockBypassLincheckTest in the lincheckTest source set).
        var poolHolder = new ResourcePool[1];
        var sabotaged = new java.util.concurrent.atomic.AtomicBoolean();
        SelectionStrategy blockDuringSelect = (candidates, random) -> {
            var pick = candidates.get(0);
            if (sabotaged.compareAndSet(false, true)) { // only the first acquire gets ambushed
                poolHolder[0].block(pick.resourceId(), Duration.ofMinutes(10));
            }
            return java.util.Optional.of(pick);
        };
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 3, 2);
        poolHolder[0] = new ResourcePool(engine, blockDuringSelect, sink, fixed(), new Random(1), TTL);
        var pool = poolHolder[0];
        pool.register(proxy("p1"));

        assertThat(pool.acquire(CTX)).isEmpty();
        assertThat(sink.events).noneMatch(event -> event instanceof PoolEvent.ResourceLeased);

        // Had the undo leaked the claim, the resource would still be held and this acquire would fail.
        pool.unblock(proxy("p1"));
        assertThat(pool.acquire(CTX)).isPresent();
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
    void dueForRecoveryProbeIsEmptyWithNothingCooling() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        assertThat(pool.dueForRecoveryProbe(NOW)).isEmpty();
    }

    @Test
    void dueForRecoveryProbeExcludesACoolingCellBeforeItsCooldownExpires() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), timedOut());
        // TIMEOUT's cooldown is minutes; NOW is still inside it
        assertThat(pool.dueForRecoveryProbe(NOW)).isEmpty();
    }

    @Test
    void dueForRecoveryProbeIncludesACoolingCellPastItsCooldown() {
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), timedOut()); // TIMEOUT base 60s x 2^(3-1) = 4m
        Instant cooldownUntil = NOW.plus(Duration.ofMinutes(4));
        Instant past = NOW.plus(Duration.ofMinutes(5)); // past the TIMEOUT cooldown
        clock.set(past);

        assertThat(pool.dueForRecoveryProbe(past)).containsExactly(new ProbeCandidate(proxy("p1"), CTX, cooldownUntil));
    }

    @Test
    void dueForRecoveryProbeExcludesACellCooledByASiteBlockBecauseHalfOpenOwnsIt() {
        // The whole point of the split (#90): a synthetic probe cannot judge a site block, so a
        // BLOCKED-cooled cell is never offered as a probe candidate — isSelectable admits it instead.
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), blocked());
        clock.set(NOW.plusSeconds(5 * 3600)); // well past the ~4h BLOCKED cooldown

        assertThat(pool.dueForRecoveryProbe(clock.instant())).isEmpty();
    }

    @Test
    void dueForRecoveryProbeExcludesABlocklistedResourceEvenPastCooldown() {
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), timedOut());
        pool.block(proxy("p1"), Duration.ofDays(1)); // separate mechanism from the cell's own state
        clock.set(NOW.plus(Duration.ofMinutes(5)));

        assertThat(pool.dueForRecoveryProbe(clock.instant())).isEmpty();
    }

    @Test
    void dueForRecoveryProbeExcludesAResourceCurrentlyLeasedUnderAnotherContext() {
        var clock = new SettableClock(NOW);
        // A TTL that outlives the jump below: this test is about the exclusion itself, not about
        // lease expiry timing (covered separately elsewhere in this file).
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 3, 2);
        var pool = new ResourcePool(
                engine, new WeightedRandomSelectionStrategy(), sink, metrics, clock, new Random(1), Duration.ofDays(1));
        var otherContext = new Context("baemin");
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), timedOut()); // CTX's cell cools
        assertThat(pool.acquire(otherContext)).isPresent(); // leases are exclusive per resource id, not per cell
        clock.set(NOW.plus(Duration.ofMinutes(5)));

        assertThat(pool.dueForRecoveryProbe(clock.instant())).isEmpty();
    }

    // --- half-open admission for a site block (#90) ---

    @Test
    void aCellCooledByASiteBlockIsSelectableOnceItsCooldownHasPassed() {
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), blocked());
        assertThat(pool.acquire(CTX)).as("still inside the cooldown").isEmpty();

        clock.set(NOW.plusSeconds(5 * 3600)); // past the ~4h BLOCKED cooldown
        assertThat(pool.acquire(CTX))
                .as("half-open: one real request is admitted to find out whether the block is over")
                .isPresent();
    }

    @Test
    void aCellCooledByATransportFailureStaysUnselectableEvenPastItsCooldown() {
        // SLOW is the prober's business, not half-open's: a neutral target measures it faithfully, so
        // spending real traffic on it would be admitting a resource nothing has vouched for.
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), new Outcome.Failure(FailureType.SLOW, Duration.ofMillis(1)));
        clock.set(NOW.plus(Duration.ofMinutes(5))); // SLOW base 30s x 2^(3-1) = 2m, so well past it

        assertThat(pool.acquire(CTX)).isEmpty();
        assertThat(pool.dueForRecoveryProbe(clock.instant()))
                .as("the prober owns this one instead")
                .isNotEmpty();
    }

    @Test
    void aBlocklistedCellCooledByASiteBlockIsNeverSelectable() {
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), blocked());
        pool.block(proxy("p1"), Duration.ofDays(1)); // an operator's explicit isolation outranks half-open
        clock.set(NOW.plusSeconds(5 * 3600));

        assertThat(pool.acquire(CTX)).isEmpty();
    }

    @Test
    void halfOpenAdmitsAtMostOneTrialRequestAtATime() {
        // The concurrency limit a half-open circuit breaker enforces explicitly comes for free here:
        // claim() already skips a resource with a live lease.
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), blocked());
        clock.set(NOW.plusSeconds(5 * 3600));

        assertThat(pool.acquire(CTX)).isPresent();
        assertThat(pool.acquire(CTX)).as("the trial is already in flight").isEmpty();
    }

    @Test
    void aBlockedProxyRecoversThroughOrdinaryTrafficWithNoProberConfigured() {
        // The case #87's prober could not reach: no RecoveryProbe, no RecoveryScheduler, no synthetic
        // request anywhere — the cell walks COOLING -> RECOVERING -> HEALTHY on real leases alone.
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), blocked());
        clock.set(NOW.plusSeconds(5 * 3600));

        var trial = pool.acquire(CTX).orElseThrow();
        pool.report(proxy("p1"), CTX, success()); // COOLING -> RECOVERING (probation)
        pool.release(trial);

        var second = pool.acquire(CTX).orElseThrow();
        pool.report(proxy("p1"), CTX, success()); // recoverAfter = 2 -> HEALTHY
        pool.release(second);

        assertThat(sink.events).hasAtLeastOneElementOfType(PoolEvent.ResourceRecovered.class);
        assertThat(pool.dueForRecoveryProbe(clock.instant()))
                .as("no prober was ever involved, and none was ever asked to be")
                .isEmpty();
    }

    @Test
    void aFailedHalfOpenTrialReCoolsWithTheNextStepOfTheBackoffCurve() {
        var clock = new SettableClock(NOW);
        var pool = poolAt(clock);
        pool.register(proxy("p1"));
        coolWith(pool, proxy("p1"), blocked()); // 3 failures -> cooled until NOW + 4h
        Instant admittedAt = NOW.plusSeconds(5 * 3600);
        clock.set(admittedAt);

        var trial = pool.acquire(CTX).orElseThrow();
        pool.report(proxy("p1"), CTX, blocked()); // the site is still blocking us
        pool.release(trial);

        // 4th consecutive failure: BLOCKED base 3600s x 2^3 = 8h, and the guard has lapsed with the
        // previous cooldown, so the engine really does re-cool rather than sit on the stale one.
        assertThat(pool.acquire(CTX)).as("re-cooled, not admitted again").isEmpty();
        clock.set(admittedAt.plus(Duration.ofHours(9)));
        assertThat(pool.acquire(CTX))
                .as("the escalated cooldown eventually elapses and half-open reopens")
                .isPresent();
    }

    @Test
    void dueForRecoveryProbeRejectsNullNow() {
        assertThatThrownBy(() -> poolAt(fixed()).dueForRecoveryProbe(null)).isInstanceOf(NullPointerException.class);
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
        assertThatThrownBy(() -> new ResourcePool(engine, strategy, sink, null, fixed(), random, TTL))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metrics");
    }

    // --- observability port: latency, lease occupancy, and the rejection event (#68) ---

    @Test
    void aFailedAcquireEmitsAcquisitionRejectedAndStillReportsMetrics() {
        var pool = poolAt(fixed()); // nothing registered
        assertThat(pool.acquire(CTX)).isEmpty();
        assertThat(sink.events).hasAtLeastOneElementOfType(PoolEvent.AcquisitionRejected.class);
        assertThat(metrics.latencies).isNotEmpty(); // latency is reported even when nothing is lent
        assertThat(metrics.lastLeased).isZero();
        assertThat(metrics.lastRegistered).isZero();
    }

    @Test
    void aGrantedAcquireDoesNotEmitAcquisitionRejected() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        assertThat(pool.acquire(CTX)).isPresent();
        assertThat(sink.events).noneMatch(event -> event instanceof PoolEvent.AcquisitionRejected);
    }

    @Test
    void acquireAndReleaseReportLeaseOccupancyAtEachTransition() {
        var pool = poolAt(fixed());
        pool.register(proxy("p1"));
        pool.register(proxy("p2"));

        var lease = pool.acquire(CTX).orElseThrow();
        assertThat(metrics.lastLeased).isEqualTo(1);
        assertThat(metrics.lastRegistered).isEqualTo(2);

        pool.acquire(CTX);
        assertThat(metrics.lastLeased).isEqualTo(2);

        pool.release(lease);
        assertThat(metrics.lastLeased).isEqualTo(1);
        assertThat(metrics.lastRegistered).isEqualTo(2);
    }

    @Test
    void acquireReportsLatencyMeasuredOnTheInjectedClock() {
        // acquire reads the clock exactly twice — once at entry, once to close the measurement — so a
        // clock that steps by a fixed amount per read makes the reported latency deterministic
        var pool = poolAt(new TickingClock(NOW, Duration.ofMillis(50)));
        pool.register(proxy("p1"));
        pool.acquire(CTX);
        assertThat(metrics.latencies).containsExactly(Duration.ofMillis(50).toNanos());
    }

    @Test
    void metricsDefaultToNoOpWhenLeftUnwired() {
        // the six-arg constructor omits the metrics port; a pool built that way must still run
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 3, 2);
        var pool = new ResourcePool(engine, new WeightedRandomSelectionStrategy(), sink, fixed(), new Random(1), TTL);
        pool.register(proxy("p1"));
        assertThat(pool.acquire(CTX)).isPresent(); // no metrics wired, no failure
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

    /** A {@link MetricsSink} that records each reported latency and the latest lease-occupancy sample. */
    private static final class RecordingMetrics implements MetricsSink {
        private final List<Long> latencies = new CopyOnWriteArrayList<>();
        private volatile int lastLeased;
        private volatile int lastRegistered;

        @Override
        public void acquisitionLatency(long nanos) {
            latencies.add(nanos);
        }

        @Override
        public void leaseOccupancy(int leased, int registered) {
            this.lastLeased = leased;
            this.lastRegistered = registered;
        }
    }

    /** A {@link Clock} that steps forward by a fixed amount on every read, so elapsed time is exact. */
    private static final class TickingClock extends Clock {
        private Instant now;
        private final Duration step;

        private TickingClock(Instant start, Duration step) {
            this.now = start;
            this.step = step;
        }

        @Override
        public Instant instant() {
            Instant reading = now;
            now = now.plus(step);
            return reading;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
