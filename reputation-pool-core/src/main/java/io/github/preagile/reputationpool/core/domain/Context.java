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

/**
 * The scope a reputation applies to — typically a platform (e.g. a marketplace).
 *
 * <p>A failure observed in one context only moves that context's cell; another context's reputation
 * for the same resource is untouched. This isolation is what keeps a block on one platform from
 * poisoning the pool everywhere.
 *
 * <p>{@link #GLOBAL} is the reserved base axis of the two-layer score model
 * ({@code effective = globalBase + contextDelta}): it carries the per-resource signal shared across
 * every context, while other {@code Context} values carry the per-context behavioural delta.
 */
public record Context(String value) {

    /** The base axis shared across all contexts — the {@code globalBase} term of the score model. */
    public static final Context GLOBAL = new Context("*");

    /**
     * @throws IllegalArgumentException if {@code value} is null or blank
     */
    public Context {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be null or blank");
        }
    }
}
