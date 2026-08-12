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
 * {@code RecoveryProbe}, outside this module) acts on: test the resource directly and
 * {@link ResourcePool#report} the result, with no lease involved.
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
