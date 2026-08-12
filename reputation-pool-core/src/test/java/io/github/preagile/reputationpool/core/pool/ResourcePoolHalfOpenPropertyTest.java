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
 * Property specification for the split issue #90 introduces: half-open admission and synthetic
 * recovery probing each own a disjoint half of {@link FailureType}, and the partition must hold for
 * every cause and every point on the timeline.
 *
 * <p>The dangerous failure is not either mechanism being too shy — it is both claiming the same cell,
 * which would let a probe report a synthetic {@code Success} for a cell real traffic is concurrently
 * trialling, promoting it out of {@code COOLING} on a signal that observed nothing. So the first
 * assertion is the exclusion itself, and the two that follow pin down which mechanism owns which case
 * so the exclusion cannot be satisfied by neither ever firing.
 *
 * <p>The second property attacks the same partition with a moving target: a failure of a different
 * type reported while the cell is already cooling. That failure joins the outcome window without
 * resizing the cooldown, so it must not move the cell between the halves either — the half is decided
 * by the cause that sized the cooldown being served, not by whatever arrived most recently (#97).
 *
 * <p>{@code isSelectable} is private, so it is observed the only way a caller can: through
 * {@link ResourcePool#acquire}. With a single registered resource that is faithful —
 * {@link WeightedRandomSelectionStrategy} always returns the sole candidate. Order matters: the probe
 * query is read first, because acquiring takes a lease and a leased resource is excluded from
 * {@code dueForRecoveryProbe} by an unrelated guard.
 */
class ResourcePoolHalfOpenPropertyTest {

    private static final Context CTX = new Context("cpeats");
    private static final Instant T0 = Instant.parse("2026-07-08T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final ResourceId RESOURCE = new ResourceId(ResourceKind.PROXY, "p1");
    private static final int COOL_AFTER = 3;

    private static ResourcePool freshPool(SettableClock clock) {
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, COOL_AFTER, 2);
        return new ResourcePool(engine, new WeightedRandomSelectionStrategy(), event -> {}, clock, new Random(1), TTL);
    }

    @Property
    @Label("no cell is ever both a half-open trial candidate and a recovery-probe candidate")
    void halfOpenAndRecoveryProbingPartitionTheFailureTypes(
            @ForAll FailureType cause,
            @ForAll @IntRange(min = 1, max = 6) int consecutiveFailures,
            @ForAll @LongRange(min = -600, max = 3600 * 200) long offsetSeconds) {
        var clock = new SettableClock(T0);
        var pool = freshPool(clock);
        pool.register(RESOURCE);
        for (int i = 0; i < consecutiveFailures; i++) {
            pool.report(RESOURCE, CTX, new Outcome.Failure(cause, Duration.ofMillis(1)));
        }

        Instant now = T0.plusSeconds(offsetSeconds);
        clock.set(now);
        boolean probeCandidate = !pool.dueForRecoveryProbe(now).isEmpty();
        boolean selectable = pool.acquire(CTX).isPresent();

        assertThat(probeCandidate && selectable)
                .as(
                        "cause=%s failures=%d now=%s was claimed by both mechanisms at once",
                        cause, consecutiveFailures, now)
                .isFalse();

        // All the failures land at T0 with the clock still there, so only the one that first crosses
        // coolAfter ever sets the cooldown (ReputationEngine's "already being punished" guard).
        boolean cooled = consecutiveFailures >= COOL_AFTER;
        if (!cooled) {
            return; // never left HEALTHY; the partition is vacuous and says nothing
        }
        Instant cooldownUntil = T0.plus(new AdaptiveCooldownPolicy().cooldownFor(cause, COOL_AFTER));

        if (now.isBefore(cooldownUntil)) {
            assertThat(selectable).as("still cooling, whatever the cause").isFalse();
            assertThat(probeCandidate).as("still cooling, whatever the cause").isFalse();
        } else {
            assertThat(selectable)
                    .as("past its cooldown, exactly the site-block cause earns a half-open trial")
                    .isEqualTo(cause == FailureType.BLOCKED);
            assertThat(probeCandidate)
                    .as("past its cooldown, exactly the transport causes are left to the prober")
                    .isEqualTo(cause != FailureType.BLOCKED);
        }
    }

    @Property
    @Label("a failure landing while the cell is already cooling never moves it between the two halves")
    void aLateFailureLeavesTheCellWithWhicheverMechanismTheCoolingCauseGaveIt(
            @ForAll FailureType cause,
            @ForAll FailureType lateCause,
            @ForAll @LongRange(min = 2, max = 3600 * 200) long offsetSeconds) {
        var clock = new SettableClock(T0);
        var pool = freshPool(clock);
        pool.register(RESOURCE);
        for (int i = 0; i < COOL_AFTER; i++) {
            pool.report(RESOURCE, CTX, new Outcome.Failure(cause, Duration.ofMillis(1)));
        }
        // One second in, still well inside even the shortest cooldown (SLOW: 30s x 2^2 = 2m), an
        // in-flight lease reports a second failure. The engine's "already being punished for this
        // incident" guard leaves cooldownUntil alone, and it leaves the recorded cooldownCause alone
        // too — so lateCause changes the window and nothing else that this split reads.
        clock.set(T0.plusSeconds(1));
        pool.report(RESOURCE, CTX, new Outcome.Failure(lateCause, Duration.ofMillis(1)));

        Instant now = T0.plusSeconds(offsetSeconds);
        clock.set(now);
        boolean probeCandidate = !pool.dueForRecoveryProbe(now).isEmpty();
        boolean selectable = pool.acquire(CTX).isPresent();

        assertThat(probeCandidate && selectable)
                .as("cause=%s lateCause=%s now=%s was claimed by both mechanisms at once", cause, lateCause, now)
                .isFalse();

        Instant cooldownUntil = T0.plus(new AdaptiveCooldownPolicy().cooldownFor(cause, COOL_AFTER));
        if (now.isBefore(cooldownUntil)) {
            assertThat(selectable).as("still cooling, whatever the cause").isFalse();
            assertThat(probeCandidate).as("still cooling, whatever the cause").isFalse();
        } else {
            assertThat(selectable)
                    .as("the cause that sized this cooldown decides the half, whatever landed later")
                    .isEqualTo(cause == FailureType.BLOCKED);
            assertThat(probeCandidate)
                    .as("the cause that sized this cooldown decides the half, whatever landed later")
                    .isEqualTo(cause != FailureType.BLOCKED);
        }
    }
}
