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

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import java.time.Instant;
import java.util.Objects;

/**
 * A {@code (resource, context)} cell that {@link ResourcePool#dueForRecoveryProbe} has found sitting
 * in {@code COOLING} past its own cooldown, with nothing left to move it forward.
 *
 * <p>{@code acquire} does not select such a cell — the one exception, a cell cooled by a site block,
 * is admitted as a half-open trial and is therefore never reported here — so once its
 * {@link #cooldownUntil()} has passed there is no lease-driven traffic left to report a success and
 * let it probate into {@code RECOVERING}. This value is what an outer-module prober (a
 * {@code RecoveryProbe}, outside this module) acts on: claim the resource with
 * {@link ResourcePool#tryAcquireForProbe} when the probe is actually dispatched, test it, release the
 * claim, and {@link ResourcePool#report} the result.
 *
 * <p>Naming a candidate takes nothing: this record is the answer to a query, not a reservation, so it
 * carries no ownership of the resource and nothing has to be released if it is dropped. Ownership is
 * taken at dispatch instead, because the gap the prober has to survive is the one between being named
 * here and running (#102), and holding the resource across that gap would take it away from real
 * traffic for a jitter delay in which no probe is running.
 */
public record ProbeCandidate(ResourceId resource, Context context, Instant cooldownUntil) {

    /**
     * @throws NullPointerException if any component is null
     */
    public ProbeCandidate {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(cooldownUntil, "cooldownUntil must not be null");
    }
}
