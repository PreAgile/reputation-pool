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
package io.github.preagile.reputationpool.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ReputationCell;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.domain.ResourceState;
import io.github.preagile.reputationpool.core.testing.DomainArbitraries;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class ReputationEngineTest {

    private static final ResourceId RID = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
    private static final Context CTX = new Context("baemin");
    private static final Instant T0 = Instant.parse("2026-07-07T00:00:00Z");
    private static final Outcome SUCCESS = new Outcome.Success(Duration.ofMillis(100));
    private static final Outcome TIMEOUT = new Outcome.Failure(FailureType.TIMEOUT, Duration.ofSeconds(1));

    private static final int COOL_AFTER = 3;
    private static final int RECOVER_AFTER = 2;

    // windowSize 10, cool after COOL_AFTER consecutive failures, recover after RECOVER_AFTER successes
    private static ReputationEngine testEngine() {
        return new ReputationEngine(new AdaptiveCooldownPolicy(), 10, COOL_AFTER, RECOVER_AFTER);
    }

    private static ReputationCell fresh() {
        return ReputationCell.fresh(RID, CTX, T0);
    }

    @Test
    void successOnHealthyStaysHealthyAndRaisesScore() {
        var result = testEngine().apply(fresh(), SUCCESS, T0);
        assertThat(result.cell().state()).isEqualTo(ResourceState.HEALTHY);
        assertThat(result.cell().score()).isGreaterThan(0.0);
        assertThat(result.cell().consecutiveFailures()).isZero();
        assertThat(result.events()).isEmpty();
    }

    @Test
    void failuresBelowThresholdStayHealthyButLowerScore() {
        var engine = testEngine();
        var cell = fresh();
        var now = T0;
        // one failure short of coolAfter must not cool
        for (int i = 0; i < COOL_AFTER - 1; i++) {
            var result = engine.apply(cell, TIMEOUT, now);
            cell = result.cell();
            now = now.plusSeconds(1);
            assertThat(result.events()).isEmpty();
        }
        assertThat(cell.state()).isEqualTo(ResourceState.HEALTHY);
        assertThat(cell.consecutiveFailures()).isEqualTo(COOL_AFTER - 1);
        assertThat(cell.score()).isLessThan(0.0);
    }

    @Test
    void reachingThresholdCoolsAndEmitsResourceCooled() {
        var engine = testEngine();
        var cell = fresh();
        var now = T0;
        ReputationEngine.Result result = null;
        for (int i = 0; i < COOL_AFTER; i++) {
            result = engine.apply(cell, TIMEOUT, now);
            cell = result.cell();
            now = now.plusSeconds(1);
        }
        var cooledCell = cell; // effectively final for the lambda below
        assertThat(cooledCell.state()).isEqualTo(ResourceState.COOLING);
        assertThat(cooledCell.consecutiveFailures()).isEqualTo(COOL_AFTER);
        assertThat(cooledCell.cooldownUntil()).isAfter(cooledCell.updatedAt());
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().getFirst()).isInstanceOfSatisfying(PoolEvent.ResourceCooled.class, cooled -> {
            assertThat(cooled.resource()).isEqualTo(RID);
            assertThat(cooled.context()).isEqualTo(CTX);
            assertThat(cooled.cause()).isEqualTo(FailureType.TIMEOUT);
            assertThat(cooled.until()).isEqualTo(cooledCell.cooldownUntil());
        });
    }

    @Test
    void successDuringActiveCooldownStaysCooling() {
        var engine = testEngine();
        var cooled = coolDownAt(engine); // now == T0 + 3s, cooldown far in the future
        var duringCooldown = cooled.updatedAt().plusSeconds(1);
        var result = engine.apply(cooled, SUCCESS, duringCooldown);
        assertThat(result.cell().state()).isEqualTo(ResourceState.COOLING);
        assertThat(result.events()).isEmpty();
    }

    @Test
    void failureDuringActiveCooldownDoesNotExtendTheCooldownOrRepeatEvents() {
        var engine = testEngine();
        var cooled = coolDownAt(engine);
        var duringCooldown = cooled.updatedAt().plusSeconds(1);
        var result = engine.apply(cooled, TIMEOUT, duringCooldown);
        // a late-arriving failure is the same incident, not a new one: no re-cool, no event spam
        assertThat(result.cell().state()).isEqualTo(ResourceState.COOLING);
        assertThat(result.cell().cooldownUntil()).isEqualTo(cooled.cooldownUntil());
        assertThat(result.events()).isEmpty();
        // the failure is still evidence: score and streak keep tracking it
        assertThat(result.cell().score()).isLessThan(cooled.score());
        assertThat(result.cell().consecutiveFailures()).isEqualTo(cooled.consecutiveFailures() + 1);
    }

    @Test
    void failureAfterCooldownExpiryStartsANewCooldown() {
        var engine = testEngine();
        var cooled = coolDownAt(engine);
        var afterExpiry = cooled.cooldownUntil().plusSeconds(1);
        var result = engine.apply(cooled, TIMEOUT, afterExpiry);
        assertThat(result.cell().state()).isEqualTo(ResourceState.COOLING);
        assertThat(result.cell().cooldownUntil()).isAfter(afterExpiry);
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().getFirst()).isInstanceOf(PoolEvent.ResourceCooled.class);
    }

    @Test
    void successAfterCooldownExpiryMovesToRecovering() {
        var engine = testEngine();
        var cooled = coolDownAt(engine);
        var afterExpiry = cooled.cooldownUntil().plusSeconds(1);
        var result = engine.apply(cooled, SUCCESS, afterExpiry);
        assertThat(result.cell().state()).isEqualTo(ResourceState.RECOVERING);
        assertThat(result.events()).isEmpty(); // one success is not enough to promote (recoverAfter is 2)
    }

    @Test
    void consecutiveSuccessesInRecoveringPromoteToHealthy() {
        var engine = testEngine();
        var cooled = coolDownAt(engine);
        var now = cooled.cooldownUntil().plusSeconds(1);
        var recovering = engine.apply(cooled, SUCCESS, now).cell(); // -> RECOVERING (1 success)
        now = now.plusSeconds(1);
        var result = engine.apply(recovering, SUCCESS, now); // 2nd success -> HEALTHY
        assertThat(result.cell().state()).isEqualTo(ResourceState.HEALTHY);
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().getFirst()).isInstanceOf(PoolEvent.ResourceRecovered.class);
    }

    @Test
    void successesDuringCoolingDoNotShortcutProbation() {
        var engine = testEngine();
        var cooled = coolDownAt(engine);
        // late-arriving successes land while the cooldown is still active
        var cell = cooled;
        var now = cooled.updatedAt();
        for (int i = 0; i < 5; i++) {
            now = now.plusSeconds(1);
            cell = engine.apply(cell, SUCCESS, now).cell();
        }
        assertThat(cell.state()).isEqualTo(ResourceState.COOLING);
        // the first post-expiry success starts probation; the cooling-period successes must not count
        var afterExpiry = cooled.cooldownUntil().plusSeconds(1);
        var probation = engine.apply(cell, SUCCESS, afterExpiry);
        assertThat(probation.cell().state()).isEqualTo(ResourceState.RECOVERING);
        assertThat(probation.events()).isEmpty();
        // only the recoverAfter-th post-expiry success promotes
        var promoted = engine.apply(probation.cell(), SUCCESS, afterExpiry.plusSeconds(1));
        assertThat(promoted.cell().state()).isEqualTo(ResourceState.HEALTHY);
        assertThat(promoted.events()).hasSize(1);
    }

    @Test
    void failuresOnBlocklistedNeverCool() {
        var engine = testEngine();
        var cell = fresh().toBuilder().state(ResourceState.BLOCKLISTED).build();
        var now = T0;
        // enough failures to cross coolAfter — BLOCKLISTED is terminal and must not be overwritten
        for (int i = 0; i < COOL_AFTER + 1; i++) {
            var result = engine.apply(cell, TIMEOUT, now);
            assertThat(result.cell().state()).isEqualTo(ResourceState.BLOCKLISTED);
            assertThat(result.events()).isEmpty();
            cell = result.cell();
            now = now.plusSeconds(1);
        }
        // the evidence is still recorded for a later release decision
        assertThat(cell.score()).isLessThan(0.0);
        assertThat(cell.consecutiveFailures()).isEqualTo(COOL_AFTER + 1);
    }

    @Test
    void successesOnBlocklistedNeverRecover() {
        var engine = testEngine();
        var cell = fresh().toBuilder().state(ResourceState.BLOCKLISTED).build();
        var now = T0;
        for (int i = 0; i < RECOVER_AFTER + 1; i++) {
            var result = engine.apply(cell, SUCCESS, now);
            assertThat(result.cell().state()).isEqualTo(ResourceState.BLOCKLISTED);
            assertThat(result.events()).isEmpty();
            cell = result.cell();
            now = now.plusSeconds(1);
        }
    }

    @Test
    void applyRejectsNullArguments() {
        var engine = testEngine();
        assertThatThrownBy(() -> engine.apply(null, SUCCESS, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.apply(fresh(), null, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.apply(fresh(), SUCCESS, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsInvalidConfig() {
        var cooldown = new AdaptiveCooldownPolicy();
        assertThatThrownBy(() -> new ReputationEngine(null, 10, 3, 2)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReputationEngine(cooldown, 0, 3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReputationEngine(cooldown, 10, 0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReputationEngine(cooldown, 10, 3, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBoundaryConfig() {
        var cooldown = new AdaptiveCooldownPolicy();
        // the smallest valid config; recoverAfter is independent of the window size
        assertThatCode(() -> new ReputationEngine(cooldown, 1, 1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> new ReputationEngine(cooldown, 5, 3, 6)).doesNotThrowAnyException();
    }

    @Test
    void windowIsCappedToWindowSize() {
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 2, 5, 2); // windowSize 2
        var cell = fresh();
        var now = T0;
        for (int i = 0; i < 4; i++) { // apply more outcomes than the window can hold
            cell = engine.apply(cell, SUCCESS, now).cell();
            now = now.plusSeconds(1);
        }
        assertThat(cell.window()).hasSize(2);
    }

    @Test
    void recoveryOutlastingTheWindowStillPromotes() {
        // recovery is a streak, not a window computation: recoverAfter 4 with windowSize 2 still works
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 2, 1, 4);
        var cooled = engine.apply(fresh(), TIMEOUT, T0).cell(); // coolAfter 1 -> COOLING
        var now = cooled.cooldownUntil().plusSeconds(1);
        var cell = cooled;
        for (int i = 0; i < 3; i++) { // successes 1..3 keep it on probation
            var result = engine.apply(cell, SUCCESS, now);
            assertThat(result.cell().state()).isEqualTo(ResourceState.RECOVERING);
            assertThat(result.events()).isEmpty();
            cell = result.cell();
            now = now.plusSeconds(1);
        }
        var promoted = engine.apply(cell, SUCCESS, now); // 4th success -> HEALTHY
        assertThat(promoted.cell().state()).isEqualTo(ResourceState.HEALTHY);
        assertThat(promoted.events()).hasSize(1);
    }

    // --- invariants (jqwik) ---

    @Property
    void scoreStaysBounded(@ForAll("outcomeSequences") List<Outcome> sequence) {
        var engine = testEngine();
        var cell = fresh();
        var now = T0;
        for (var outcome : sequence) {
            cell = engine.apply(cell, outcome, now).cell();
            assertThat(cell.score()).isBetween(ReputationEngine.MIN_SCORE, ReputationEngine.MAX_SCORE);
            now = now.plusSeconds(1);
        }
    }

    @Property
    void recoversMonotonicallyOnSuccessOnly(@ForAll @IntRange(min = 1, max = 50) int successes) {
        var engine = testEngine();
        var cell = fresh();
        var now = T0;
        double previous = cell.score();
        for (int i = 0; i < successes; i++) {
            cell = engine.apply(cell, SUCCESS, now).cell();
            assertThat(cell.score()).isGreaterThanOrEqualTo(previous);
            previous = cell.score();
            now = now.plusSeconds(1);
        }
    }

    @Property
    void declinesMonotonicallyOnFailureOnly(@ForAll @IntRange(min = 1, max = 50) int failures) {
        var engine = testEngine();
        var cell = fresh();
        var now = T0;
        double previous = cell.score();
        for (int i = 0; i < failures; i++) {
            cell = engine.apply(cell, TIMEOUT, now).cell();
            assertThat(cell.score()).isLessThanOrEqualTo(previous);
            previous = cell.score();
            now = now.plusSeconds(1);
        }
    }

    @Property
    void blocklistedIsTerminalUnderAnyOutcomeSequence(@ForAll("outcomeSequences") List<Outcome> sequence) {
        var engine = testEngine();
        var cell = fresh().toBuilder().state(ResourceState.BLOCKLISTED).build();
        var now = T0;
        for (var outcome : sequence) {
            var result = engine.apply(cell, outcome, now);
            assertThat(result.cell().state()).isEqualTo(ResourceState.BLOCKLISTED);
            assertThat(result.events()).isEmpty();
            cell = result.cell();
            now = now.plusSeconds(1);
        }
    }

    @Provide
    Arbitrary<List<Outcome>> outcomeSequences() {
        // shared domain generator from testFixtures: successes and failures over every FailureType
        return DomainArbitraries.outcomes().list().ofMinSize(1).ofMaxSize(200);
    }

    // Drives a fresh cell to COOLING by applying COOL_AFTER consecutive failures.
    private static ReputationCell coolDownAt(ReputationEngine engine) {
        var cell = fresh();
        var now = T0;
        for (int i = 0; i < COOL_AFTER; i++) {
            cell = engine.apply(cell, TIMEOUT, now).cell();
            now = now.plusSeconds(1);
        }
        return cell;
    }
}
