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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
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

    // windowSize 10, coolAfter 3 (cool after 3 consecutive failures), recoverAfter 2
    private static ReputationEngine testEngine() {
        return new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 3, 2);
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
        // coolAfter is 3, so the first two failures must not cool
        for (int i = 0; i < 2; i++) {
            var result = engine.apply(cell, TIMEOUT, now);
            cell = result.cell();
            now = now.plusSeconds(1);
            assertThat(result.events()).isEmpty();
        }
        assertThat(cell.state()).isEqualTo(ResourceState.HEALTHY);
        assertThat(cell.consecutiveFailures()).isEqualTo(2);
        assertThat(cell.score()).isLessThan(0.0);
    }

    @Test
    void reachingThresholdCoolsAndEmitsResourceCooled() {
        var engine = testEngine();
        var cell = fresh();
        var now = T0;
        ReputationEngine.Result result = null;
        for (int i = 0; i < 3; i++) {
            result = engine.apply(cell, TIMEOUT, now);
            cell = result.cell();
            now = now.plusSeconds(1);
        }
        var cooledCell = cell; // effectively final for the lambda below
        assertThat(cooledCell.state()).isEqualTo(ResourceState.COOLING);
        assertThat(cooledCell.consecutiveFailures()).isEqualTo(3);
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
        // recoverAfter must fit within the window, or the resource could never recover
        assertThatThrownBy(() -> new ReputationEngine(cooldown, 5, 3, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBoundaryConfig() {
        var cooldown = new AdaptiveCooldownPolicy();
        // the smallest valid config, and recoverAfter == windowSize, are both accepted
        assertThatCode(() -> new ReputationEngine(cooldown, 1, 1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> new ReputationEngine(cooldown, 3, 1, 3)).doesNotThrowAnyException();
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
    void recoveryPromotesWhenTheWindowFillsWithSuccesses() {
        // windowSize 2, cool on the first failure, recover after 2 successes
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 2, 1, 2);
        var cooled = engine.apply(fresh(), TIMEOUT, T0).cell(); // -> COOLING, window [F]
        var now = cooled.cooldownUntil().plusSeconds(1);
        var recovering = engine.apply(cooled, SUCCESS, now).cell(); // -> RECOVERING, window [F, S]
        now = now.plusSeconds(1);
        var result = engine.apply(recovering, SUCCESS, now); // window caps to [S, S] -> HEALTHY
        assertThat(result.cell().state()).isEqualTo(ResourceState.HEALTHY);
        assertThat(result.cell().window()).hasSize(2);
        assertThat(result.events()).hasSize(1);
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

    @Provide
    Arbitrary<List<Outcome>> outcomeSequences() {
        return anyOutcome().list().ofMinSize(1).ofMaxSize(200);
    }

    private static Arbitrary<Outcome> anyOutcome() {
        Arbitrary<Outcome> successes =
                Arbitraries.integers().between(0, 5000).map(ms -> new Outcome.Success(Duration.ofMillis(ms)));
        Arbitrary<Outcome> failures = Combinators.combine(
                        Arbitraries.of(FailureType.class),
                        Arbitraries.integers().between(0, 5000))
                .as((type, ms) -> new Outcome.Failure(type, Duration.ofMillis(ms)));
        return Arbitraries.oneOf(successes, failures);
    }

    // Drives a fresh cell to COOLING by applying `coolAfter` consecutive failures.
    private static ReputationCell coolDownAt(ReputationEngine engine) {
        var cell = fresh();
        var now = T0;
        for (int i = 0; i < 3; i++) {
            cell = engine.apply(cell, TIMEOUT, now).cell();
            now = now.plusSeconds(1);
        }
        return cell;
    }
}
