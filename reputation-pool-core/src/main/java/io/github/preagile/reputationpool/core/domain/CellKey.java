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
package io.github.preagile.reputationpool.core.domain;

import java.util.Objects;

/**
 * The identity of a {@link ReputationCell}: one per {@code (resource × context)} pair. Reputation is
 * tracked per context, so a resource that is healthy in one context and cooling in another is two
 * distinct cells under two distinct keys.
 *
 * <p>This is part of the persisted contract: it is the map key of {@link PoolSnapshot#cells()}, so a
 * store must be able to round-trip it. It was promoted from a private detail of the pool facade to a
 * public value object when persistence made the pool's durable state an externally visible whole.
 */
public record CellKey(ResourceId resource, Context context) {

    /**
     * @throws NullPointerException if {@code resource} or {@code context} is null
     */
    public CellKey {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(context, "context must not be null");
    }
}
