package io.github.preagile.reputationpool.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.FailureType;
import java.time.Duration;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class AdaptiveCooldownPolicyTest {

    private final CooldownPolicy policy = new AdaptiveCooldownPolicy();

    // The failure type sets the base — an active block cools far longer than a transient slowdown.
    @Test
    void baseCooldownPerFailureTypeAtFirstFailure() {
        assertThat(policy.cooldownFor(FailureType.BLOCKED, 1)).isEqualTo(Duration.ofSeconds(3600));
        assertThat(policy.cooldownFor(FailureType.TLS_HANDSHAKE, 1)).isEqualTo(Duration.ofSeconds(300));
        assertThat(policy.cooldownFor(FailureType.CONNECTION_RESET, 1)).isEqualTo(Duration.ofSeconds(120));
        assertThat(policy.cooldownFor(FailureType.TIMEOUT, 1)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.cooldownFor(FailureType.SLOW, 1)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void backoffDoublesWithEachConsecutiveFailure() {
        assertThat(policy.cooldownFor(FailureType.TIMEOUT, 1)).isEqualTo(Duration.ofSeconds(60)); // 60 * 2^0
        assertThat(policy.cooldownFor(FailureType.TIMEOUT, 2)).isEqualTo(Duration.ofSeconds(120)); // 60 * 2^1
        assertThat(policy.cooldownFor(FailureType.TIMEOUT, 3)).isEqualTo(Duration.ofSeconds(240)); // 60 * 2^2
    }

    @Test
    void backoffPlateausAtTheExponentCap() {
        // default cap is 6 -> maximum factor is 2^6 = 64
        var atCap = policy.cooldownFor(FailureType.TIMEOUT, 7); // consecutiveFailures - 1 == 6 == cap
        var beyondCap = policy.cooldownFor(FailureType.TIMEOUT, 100); // capped back to 6
        assertThat(atCap).isEqualTo(Duration.ofSeconds(60L * 64));
        assertThat(beyondCap).isEqualTo(atCap);
    }

    @Test
    void exponentCapIsConfigurable() {
        var capped = new AdaptiveCooldownPolicy(2); // maximum factor is 2^2 = 4
        assertThat(capped.cooldownFor(FailureType.TIMEOUT, 3)).isEqualTo(Duration.ofSeconds(240)); // 60 * 4
        assertThat(capped.cooldownFor(FailureType.TIMEOUT, 50)).isEqualTo(Duration.ofSeconds(240)); // still capped at 4
    }

    @Test
    void defaultConstructorUsesDefaultExponentCap() {
        assertThat(new AdaptiveCooldownPolicy().maxExponent()).isEqualTo(AdaptiveCooldownPolicy.DEFAULT_MAX_EXPONENT);
    }

    @Test
    void rejectsNonPositiveConsecutiveFailures() {
        assertThatThrownBy(() -> policy.cooldownFor(FailureType.BLOCKED, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullFailureType() {
        assertThatThrownBy(() -> policy.cooldownFor(null, 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsOutOfRangeExponentCap() {
        assertThatThrownBy(() -> new AdaptiveCooldownPolicy(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdaptiveCooldownPolicy(AdaptiveCooldownPolicy.MAX_ALLOWED_EXPONENT + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBoundaryExponentCaps() {
        // the boundary values themselves are valid: 0 and MAX_ALLOWED_EXPONENT
        assertThat(new AdaptiveCooldownPolicy(0).maxExponent()).isZero();
        assertThat(new AdaptiveCooldownPolicy(AdaptiveCooldownPolicy.MAX_ALLOWED_EXPONENT).maxExponent())
                .isEqualTo(AdaptiveCooldownPolicy.MAX_ALLOWED_EXPONENT);
    }

    @Test
    void zeroCapDisablesBackoff() {
        var flat = new AdaptiveCooldownPolicy(0); // factor is always 2^0 = 1
        assertThat(flat.cooldownFor(FailureType.TIMEOUT, 1)).isEqualTo(Duration.ofSeconds(60));
        assertThat(flat.cooldownFor(FailureType.TIMEOUT, 10)).isEqualTo(Duration.ofSeconds(60));
    }

    // --- invariants, attacked over many generated inputs (jqwik) ---

    @Property
    void cooldownIsAlwaysPositive(@ForAll FailureType type, @ForAll @IntRange(min = 1, max = 1000) int consecutive) {
        assertThat(policy.cooldownFor(type, consecutive)).isPositive();
    }

    @Property
    void cooldownNeverDecreasesAsConsecutiveFailuresGrow(
            @ForAll FailureType type, @ForAll @IntRange(min = 1, max = 999) int consecutive) {
        var current = policy.cooldownFor(type, consecutive);
        var next = policy.cooldownFor(type, consecutive + 1);
        assertThat(next).isGreaterThanOrEqualTo(current);
    }
}
