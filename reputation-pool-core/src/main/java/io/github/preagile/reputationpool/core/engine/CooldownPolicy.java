package io.github.preagile.reputationpool.core.engine;

import io.github.preagile.reputationpool.core.domain.FailureType;
import java.time.Duration;

/**
 * Decides how long a resource should be excluded from selection after a failure.
 *
 * <p>An open contract: the cooldown curve is a policy, and different strategies may be plugged in.
 * The policy receives an <em>already-classified</em> {@link FailureType} — deciding what counts as a
 * failure (and, for {@code SLOW}, what counts as slow) happens upstream at the client boundary, not
 * here. This policy only maps a failure and its consecutive count to a duration.
 */
public interface CooldownPolicy {

    /**
     * The cooldown for a resource that has just failed.
     *
     * @param type the kind of failure observed
     * @param consecutiveFailures how many failures have occurred in a row (at least 1)
     * @return a positive cooldown duration
     */
    Duration cooldownFor(FailureType type, int consecutiveFailures);
}
