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
package io.github.preagile.reputationpool.prober;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.util.Optional;

/**
 * Actively tests one resource outside the lease flow — the thing {@link RecoveryScheduler} calls for
 * every {@code (resource, context)} it decides is due, so the resulting {@link Outcome} can be
 * {@code report}ed with no lease ever taken.
 *
 * <p>An open contract, one implementation per {@link ResourceKind}: what "test it" means is entirely
 * resource-specific (an HTTP call through a proxy, a lightweight auth check for an account), so this
 * module declares only the shape and leaves every concrete probe to an adapter module. Keep probes
 * cheap and side-effect-light — this is a health check, not the operation normal traffic performs.
 *
 * <p>{@code test} may be called concurrently for different resources; {@link RecoveryScheduler} never
 * calls it twice at once for the <em>same</em> {@code (resource, context)}, so an implementation needs
 * no internal locking for that case.
 */
public interface RecoveryProbe {

    /**
     * Tests {@code resource} for {@code context} right now, outside the lease flow.
     *
     * @param resource the resource to test
     * @param context the context to test it for
     * @return the outcome to report, or {@link Optional#empty()} if this resource cannot be tested
     *     right now (e.g. its identity no longer resolves to anything reachable) — a skip, not a
     *     failure, so the caller must not treat it as reputational evidence
     * @throws NullPointerException if {@code resource} or {@code context} is null
     */
    Optional<Outcome> test(ResourceId resource, Context context);
}
