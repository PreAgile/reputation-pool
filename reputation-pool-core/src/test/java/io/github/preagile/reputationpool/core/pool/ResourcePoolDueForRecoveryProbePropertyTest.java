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
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Property specification for {@link ResourcePool#dueForRecoveryProbe}: whatever the failure type, the
 * failure streak, the elapsed time, or the two independent exclusion mechanisms (blocklist, another
 * context's lease), the query must agree exactly with the {@link AdaptiveCooldownPolicy} that put the
 * cell into {@code COOLING} in the first place — and must never name a cell cooled by a site block,
 * which half-open admission owns instead (#90). Unlike {@link ResourcePoolInvariantsPropertyTest} (a
 * stateful shadow-model test over the whole facade), this attacks one query in isolation with a
 * directly computable oracle: the cooldown curve is a closed formula, so the expected cutoff instant
 * is known without inspecting pool internals.
 */
class ResourcePoolDueForRecoveryProbePropertyTest {

    private static final Context CTX = new Context("cpeats");
    private static final Context OTHER_CONTEXT = new Context("baemin");
    private static final Instant T0 = Instant.parse("2026-07-08T00:00:00Z");
    // Long enough to still be held at the largest offsetSeconds this property drives (3600 * 130):
    // the "leased under another context" branch tests the exclusion itself, not lease expiry, which
    // ResourcePoolTest already covers separately.
    private static final Duration TTL = Duration.ofDays(365);
    private static final ResourceId RESOURCE = new ResourceId(ResourceKind.PROXY, "p1");
    private static final int COOL_AFTER = 3;
    /** Bounded by a probe's own budget, the way a prober is expected to size it. */
    private static final Duration PROBE_TTL = Duration.ofSeconds(15);

    private static ResourcePool freshPool(SettableClock clock) {
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 3, 2);
        return new ResourcePool(engine, new WeightedRandomSelectionStrategy(), event -> {}, clock, new Random(1), TTL);
    }

    @Property
    @Label("a cooling cell is due iff now has reached the cooldown the policy computed, its cause was not "
            + "a site block, and it is neither blocklisted nor leased under another context")
    void agreesWithTheCooldownPolicyAndTheThreeExclusionMechanisms(
            @ForAll FailureType cause,
            @ForAll @IntRange(min = 1, max = 8) int consecutiveFailures,
            @ForAll @LongRange(min = -3600, max = 3600 * 130) long offsetSeconds,
            @ForAll boolean blocklisted,
            @ForAll boolean leasedUnderAnotherContext) {
        var clock = new SettableClock(T0);
        var pool = arrange(clock, cause, consecutiveFailures, blocklisted, leasedUnderAnotherContext);

        Instant now = T0.plusSeconds(offsetSeconds);
        clock.set(now);
        var due = pool.dueForRecoveryProbe(now);

        if (expectedDue(cause, consecutiveFailures, now, blocklisted, leasedUnderAnotherContext)) {
            assertThat(due).containsExactly(new ProbeCandidate(RESOURCE, CTX, cooldownUntil(cause)));
        } else {
            assertThat(due).isEmpty();
        }
    }

    /**
     * The acquiring sibling, over the same space (#102). {@code dueForRecoveryProbe} names candidates
     * and the prober claims one at dispatch, seconds later; the two answering the same question is what
     * makes the second call a re-check of the first rather than a second, looser rule of its own. And
     * once the claim is granted the cell is nobody else's: not the query's to name again, not traffic's
     * to lease — which is the ownership the {@code isLeased} guard alone never provided.
     */
    @Property
    @Label("the claim taken at dispatch grants exactly when the query would name the cell, and holding it "
            + "excludes both the query and real traffic")
    void tryAcquireForProbeGrantsExactlyWhatTheQueryNames(
            @ForAll FailureType cause,
            @ForAll @IntRange(min = 1, max = 8) int consecutiveFailures,
            @ForAll @LongRange(min = -3600, max = 3600 * 130) long offsetSeconds,
            @ForAll boolean blocklisted,
            @ForAll boolean leasedUnderAnotherContext) {
        var clock = new SettableClock(T0);
        var pool = arrange(clock, cause, consecutiveFailures, blocklisted, leasedUnderAnotherContext);

        Instant now = T0.plusSeconds(offsetSeconds);
        clock.set(now);
        var claim = pool.tryAcquireForProbe(RESOURCE, CTX, now, PROBE_TTL);

        assertThat(claim.isPresent())
                .isEqualTo(expectedDue(cause, consecutiveFailures, now, blocklisted, leasedUnderAnotherContext));
        if (claim.isPresent()) {
            assertThat(claim.get().expiresAt()).isEqualTo(now.plus(PROBE_TTL));
            assertThat(pool.dueForRecoveryProbe(now))
                    .as("a cell being probed is not a candidate for a second probe")
                    .isEmpty();
            assertThat(pool.acquire(OTHER_CONTEXT))
                    .as("nor is it available to real traffic while the probe runs")
                    .isEmpty();
        }
    }

    private static ResourcePool arrange(
            SettableClock clock,
            FailureType cause,
            int consecutiveFailures,
            boolean blocklisted,
            boolean leasedUnderAnotherContext) {
        var pool = freshPool(clock);
        pool.register(RESOURCE);
        for (int i = 0; i < consecutiveFailures; i++) {
            pool.report(RESOURCE, CTX, new Outcome.Failure(cause, Duration.ofMillis(1)));
        }
        // Order matters: acquire() refuses an already-blocklisted resource, but block() does not
        // revoke an existing lease — so the lease must be taken first for both flags to be true at once.
        if (leasedUnderAnotherContext) {
            assertThat(pool.acquire(OTHER_CONTEXT)).isPresent(); // leases are per resource id, not per cell
        }
        if (blocklisted) {
            pool.block(RESOURCE, Duration.ofDays(365));
        }
        return pool;
    }

    private static Instant cooldownUntil(FailureType cause) {
        return T0.plus(new AdaptiveCooldownPolicy().cooldownFor(cause, COOL_AFTER));
    }

    private static boolean expectedDue(
            FailureType cause,
            int consecutiveFailures,
            Instant now,
            boolean blocklisted,
            boolean leasedUnderAnotherContext) {
        // All reports land at the same instant (the clock has not moved yet), so shouldCool's "already
        // cooling, do not re-extend" guard means only the failure that first crosses coolAfter ever sets
        // the cooldown — any further failures at that same instant just keep incrementing the streak
        // without recomputing it. The engine's own contract (ReputationEngine's class javadoc) is what
        // this oracle mirrors, not a detail of this test.
        boolean cooldownElapsed = consecutiveFailures >= COOL_AFTER && !now.isBefore(cooldownUntil(cause));
        return cooldownElapsed && cause != FailureType.BLOCKED && !blocklisted && !leasedUnderAnotherContext;
    }
}
