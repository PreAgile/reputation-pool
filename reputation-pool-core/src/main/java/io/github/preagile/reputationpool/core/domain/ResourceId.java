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
 * A resource's logical identifier within the pool.
 *
 * <p>The {@code kind} namespaces the {@code value} so identifiers from different resource kinds
 * never collide as map keys — a proxy {@code "1.2.3.4:8080"} and an account {@code "1.2.3.4:8080"}
 * are distinct ids. The {@code value}'s <em>meaning</em> is owned by the adapter that produces it
 * ({@code host:port} for a proxy, an account id for an account); the core treats it as an opaque,
 * non-blank string and never parses it.
 *
 * <p>Invalid ids cannot be constructed: {@code kind} must be non-null and {@code value} must be
 * non-null and non-blank.
 */
public record ResourceId(ResourceKind kind, String value) {

    /**
     * @throws NullPointerException if {@code kind} is null
     * @throws IllegalArgumentException if {@code value} is null or blank
     */
    public ResourceId {
        Objects.requireNonNull(kind, "kind must not be null");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be null or blank");
        }
    }
}
