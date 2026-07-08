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
 * A single active lease: a caller's temporary, exclusive hold on a resource within a context, valid
 * until {@link #expiresAt()}. Immutable like the other domain values; the {@link LeaseRegistry} that
 * holds it is the only mutable part of the lease machinery.
 *
 * <p>The {@code token} is a monotonically increasing fencing token assigned at acquisition. It lets
 * {@link LeaseRegistry#release} and {@link LeaseRegistry#renew} act only for the current holder, so a
 * caller whose lease already expired and was re-acquired by someone else cannot disturb the new
 * lease.
 *
 * <p>Expiry is exclusive — {@code now.isBefore(expiresAt)} means still held — mirroring the
 * reputation cell's {@code cooldownUntil} convention.
 */
public record Lease(ResourceId resource, Context context, long token, Instant leasedAt, Instant expiresAt) {

    /**
     * @throws NullPointerException if {@code resource}, {@code context}, {@code leasedAt}, or
     *     {@code expiresAt} is null
     * @throws IllegalArgumentException if {@code expiresAt} is before {@code leasedAt} — a lease
     *     cannot end before it began
     */
    public Lease {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(leasedAt, "leasedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (expiresAt.isBefore(leasedAt)) {
            throw new IllegalArgumentException("expiresAt must not be before leasedAt");
        }
    }

    /** Whether this lease has expired at {@code now}; expiry is exclusive. */
    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
