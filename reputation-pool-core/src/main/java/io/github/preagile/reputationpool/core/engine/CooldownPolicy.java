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
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if {@code consecutiveFailures} is less than 1
     */
    Duration cooldownFor(FailureType type, int consecutiveFailures);
}
