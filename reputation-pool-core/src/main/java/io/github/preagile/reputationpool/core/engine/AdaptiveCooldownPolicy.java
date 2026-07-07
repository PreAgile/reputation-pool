package io.github.preagile.reputationpool.core.engine;

import io.github.preagile.reputationpool.core.domain.FailureType;
import java.time.Duration;
import java.util.Objects;

/**
 * The default cooldown curve: a per-failure-type base, doubled for each consecutive failure
 * (exponential backoff), with the growth capped so a long failure streak cannot produce an absurd
 * duration.
 *
 * <p>{@code cooldown = base(type) × 2^min(consecutiveFailures - 1, maxExponent)}. The base encodes
 * how serious each failure is (an active block cools far longer than a transient slowdown). The
 * exponent cap is configurable; the no-arg constructor uses {@link #DEFAULT_MAX_EXPONENT}.
 */
public final class AdaptiveCooldownPolicy implements CooldownPolicy {

    /** Default exponent cap: the backoff factor tops out at 2^6 = 64×. */
    public static final int DEFAULT_MAX_EXPONENT = 6;

    /**
     * Largest exponent cap that may be configured. Bounded at 21 so the resulting duration stays
     * convertible to nanoseconds: {@code Duration.toNanos()} throws above {@code Long.MAX_VALUE} ns
     * (~292 years), and downstream schedulers call it. With the largest base (3600s),
     * {@code 3600 × 2^21 ≈ 239 years} is the largest cooldown that still fits.
     */
    public static final int MAX_ALLOWED_EXPONENT = 21;

    private final int maxExponent;

    public AdaptiveCooldownPolicy() {
        this(DEFAULT_MAX_EXPONENT);
    }

    public AdaptiveCooldownPolicy(int maxExponent) {
        if (maxExponent < 0 || maxExponent > MAX_ALLOWED_EXPONENT) {
            throw new IllegalArgumentException("maxExponent must be in [0, " + MAX_ALLOWED_EXPONENT + "]");
        }
        this.maxExponent = maxExponent;
    }

    public int maxExponent() {
        return maxExponent;
    }

    @Override
    public Duration cooldownFor(FailureType type, int consecutiveFailures) {
        Objects.requireNonNull(type, "type must not be null");
        if (consecutiveFailures < 1) {
            throw new IllegalArgumentException("consecutiveFailures must be at least 1");
        }
        long baseSeconds =
                switch (type) {
                    case BLOCKED -> 3600; // an active block: cool the longest
                    case TLS_HANDSHAKE -> 300;
                    case CONNECTION_RESET -> 120;
                    case TIMEOUT -> 60;
                    case SLOW -> 30; // the lightest signal
                };
        int exponent = Math.min(consecutiveFailures - 1, maxExponent);
        long factor = 1L << exponent; // 2^exponent; exponent is capped so this never overflows
        return Duration.ofSeconds(baseSeconds * factor);
    }
}
