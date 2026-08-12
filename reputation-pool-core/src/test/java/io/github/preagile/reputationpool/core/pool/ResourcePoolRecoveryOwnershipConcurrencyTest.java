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

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.testing.SettableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The half-open/probe partition under real contention: {@link ResourcePoolHalfOpenPropertyTest} proves
 * it over every cause and every point on the timeline, but single-threaded, where each observation sees
 * one settled cell. This test attacks the same invariant while {@link ResourcePool#report} is
 * concurrently replacing that cell — the race CodeRabbit raised on #91, that {@code claim} evaluates
 * selectability before {@code leases.tryAcquire} and a concurrent report can land in between.
 *
 * <p>What holds the invariant up is not a lock but the shape of the state: a cell is one immutable
 * record swapped by a single reference write, and both halves of the split read
 * {@code cooldownCause} and {@code cooldownUntil} off it, so no <em>version</em> of a cell can satisfy
 * both. This is the regression guard for that property — it fails if the two values are ever allowed to
 * move independently of each other (for instance by deriving the cause from the outcome window again,
 * which #97 removed).
 *
 * <p>Each round takes a fresh pool so the cell is not permanently parked in a re-cooled state, and
 * randomises the cooling cause so both halves are exercised; the counters at the end reject the
 * vacuous pass where neither mechanism ever claimed anything.
 */
class ResourcePoolRecoveryOwnershipConcurrencyTest {

    private static final Context CTX = new Context("cpeats");
    private static final ResourceId RESOURCE = new ResourceId(ResourceKind.PROXY, "p1");
    private static final Instant T0 = Instant.parse("2026-07-08T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int COOL_AFTER = 3;
    private static final int ROUNDS = 200;
    private static final int OBSERVERS = 2;
    private static final int REPORTERS = 2;

    @Test
    void noCellIsEverClaimedByBothHalfOpenAndTheProberWhileReportsAreLandingConcurrently() throws Exception {
        var random = new Random(20260708L);
        var bothAtOnce = new AtomicReference<String>();
        var halfOpenClaims = new AtomicInteger();
        var probeClaims = new AtomicInteger();

        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < ROUNDS; round++) {
                FailureType cause = FailureType.values()[random.nextInt(FailureType.values().length)];
                var clock = new SettableClock(T0);
                ResourcePool pool = freshPool(clock);
                pool.register(RESOURCE);
                for (int i = 0; i < COOL_AFTER; i++) {
                    pool.report(RESOURCE, CTX, new Outcome.Failure(cause, Duration.ofMillis(1)));
                }
                // Past every cooldown on the curve, so the cell is genuinely claimable by whichever half
                // owns it — the state where a collision would actually cost something.
                Instant now = T0.plus(Duration.ofDays(1));
                clock.set(now);

                var start = new CountDownLatch(1);
                var done = new CountDownLatch(OBSERVERS + REPORTERS);
                for (int i = 0; i < REPORTERS; i++) {
                    FailureType reported = FailureType.values()[random.nextInt(FailureType.values().length)];
                    workers.execute(() -> {
                        await(start);
                        try {
                            pool.report(RESOURCE, CTX, new Outcome.Failure(reported, Duration.ofMillis(1)));
                            pool.report(RESOURCE, CTX, new Outcome.Success(Duration.ofMillis(1)));
                        } finally {
                            done.countDown();
                        }
                    });
                }
                for (int i = 0; i < OBSERVERS; i++) {
                    workers.execute(() -> {
                        await(start);
                        try {
                            // Read the probe query first: acquiring takes a lease, and a leased resource
                            // is excluded from dueForRecoveryProbe by an unrelated guard, which would
                            // mask a collision rather than expose it.
                            boolean probeDue = !pool.dueForRecoveryProbe(now).isEmpty();
                            var lease = pool.acquire(CTX);
                            lease.ifPresent(pool::release);
                            if (probeDue) {
                                probeClaims.incrementAndGet();
                            }
                            if (lease.isPresent()) {
                                halfOpenClaims.incrementAndGet();
                            }
                            if (probeDue && lease.isPresent()) {
                                bothAtOnce.compareAndSet(null, "cause=" + cause + " at " + now);
                            }
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS))
                        .as("round %d did not finish", round)
                        .isTrue();
            }
        }

        assertThat(bothAtOnce.get())
                .as("a cell was claimed by half-open admission and the prober at the same time")
                .isNull();
        // Not vacuous: over the rounds both mechanisms did claim cells, so the exclusion above is a
        // real exclusion and not "neither ever fired".
        assertThat(halfOpenClaims.get()).as("half-open never admitted anything").isPositive();
        assertThat(probeClaims.get())
                .as("the prober was never offered anything")
                .isPositive();
    }

    /**
     * Half-open admission is also capped at one in-flight trial by the lease registry — the property
     * {@code isSelectable}'s javadoc leans on for its blast radius. Under contention that cap is what
     * {@code leases.tryAcquire} enforces, so it is worth pinning where many threads reach for the same
     * cooled cell at once rather than one after another.
     */
    @Test
    void halfOpenAdmitsExactlyOneTrialEvenWhenEveryThreadReachesForItAtOnce() throws Exception {
        var clock = new SettableClock(T0);
        ResourcePool pool = freshPool(clock);
        pool.register(RESOURCE);
        for (int i = 0; i < COOL_AFTER; i++) {
            pool.report(RESOURCE, CTX, new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(1)));
        }
        clock.set(T0.plus(Duration.ofDays(1)));

        int threads = 32;
        var start = new CountDownLatch(1);
        var granted = new AtomicInteger();
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                workers.execute(() -> {
                    await(start);
                    if (pool.acquire(CTX).isPresent()) {
                        granted.incrementAndGet(); // deliberately never released
                    }
                });
            }
            start.countDown();
        }

        assertThat(granted)
                .as("the trial is one real request, not one per thread")
                .hasValue(1);
    }

    private static ResourcePool freshPool(SettableClock clock) {
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, COOL_AFTER, 2);
        // One registered resource, so WeightedRandomSelectionStrategy always returns that sole
        // candidate — the same faithful single-candidate setup ResourcePoolHalfOpenPropertyTest uses.
        return new ResourcePool(engine, new WeightedRandomSelectionStrategy(), event -> {}, clock, new Random(1), TTL);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
