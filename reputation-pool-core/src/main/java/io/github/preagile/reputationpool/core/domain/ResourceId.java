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

    public ResourceId {
        Objects.requireNonNull(kind, "kind must not be null");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be null or blank");
        }
    }
}
