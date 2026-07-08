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
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks which resources are currently leased, so the pool never hands the same resource to two
 * callers at once. This is the concurrency layer's one piece of mutable shared state: {@code core}
 * keeps domain values immutable, but the design puts concurrency "in the data — atomic map
 * operations at the boundary", and this registry is that boundary. Individual {@link Lease} entries
 * are immutable records; only the map they live in is mutable.
 *
 * <p>Exclusivity is enforced atomically, never by a check-then-act: {@link #tryAcquire} claims a
 * resource with a single {@link ConcurrentHashMap#compute} that succeeds only if the slot is free or
 * its lease has expired, so of many threads racing for one resource exactly one wins.
 *
 * <p>A lease carries a time-to-live and expires by wall-clock comparison, which reclaims a lease held
 * by a caller that crashed without releasing. But expiry is a <em>safety net for a dead holder</em>,
 * not the normal unlock: a live holder that is merely slow must {@link #renew} before expiry, or it
 * can be preempted while still using the resource. The normal return path is {@link #release}.
 *
 * <p>Every lease carries a monotonically increasing fencing token. {@link #release} and {@link #renew}
 * act only when the caller presents the current token, so a holder whose lease already expired and was
 * re-acquired by someone else cannot release or extend the new holder's lease.
 *
 * <p>Thread-safe: all state lives in a {@link ConcurrentHashMap} and an {@link AtomicLong}.
 */
public final class LeaseRegistry {

    private final ConcurrentHashMap<ResourceId, Lease> active = new ConcurrentHashMap<>();
    private final AtomicLong fencing = new AtomicLong();

    /**
     * Attempts to lease {@code resource} for {@code ttl}, succeeding only if it is not currently held
     * by a live lease.
     *
     * @param resource the resource to lease
     * @param context the context the lease is taken for
     * @param now the current instant
     * @param ttl how long the lease stays valid — the expiry safety net, not the normal unlock
     * @return the granted lease, or {@link Optional#empty()} if the resource is already leased
     * @throws NullPointerException if {@code resource}, {@code context}, {@code now}, or {@code ttl}
     *     is null
     * @throws IllegalArgumentException if {@code ttl} is zero or negative
     */
    public Optional<Lease> tryAcquire(ResourceId resource, Context context, Instant now, Duration ttl) {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(now, "now must not be null");
        requirePositive(ttl);
        Lease[] created = {null};
        active.compute(resource, (key, current) -> {
            if (current == null || current.isExpired(now)) {
                // build the lease and burn a fencing token only when the claim actually wins
                created[0] = new Lease(resource, context, fencing.incrementAndGet(), now, now.plus(ttl));
                return created[0];
            }
            return current; // already held by a live lease
        });
        return Optional.ofNullable(created[0]);
    }

    /**
     * Extends a live lease the caller still holds, identified by its fencing {@code token}. A live
     * holder renews before expiry so the TTL safety net never preempts it.
     *
     * @param resource the leased resource
     * @param token the fencing token of the lease the caller holds
     * @param now the current instant
     * @param ttl how long to extend the lease from {@code now}
     * @return the extended lease, or {@link Optional#empty()} if the resource is not leased, the token
     *     does not match, or the lease has already expired
     * @throws NullPointerException if {@code resource}, {@code now}, or {@code ttl} is null
     * @throws IllegalArgumentException if {@code ttl} is zero or negative
     */
    public Optional<Lease> renew(ResourceId resource, long token, Instant now, Duration ttl) {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(now, "now must not be null");
        requirePositive(ttl);
        Lease[] extended = {null};
        active.computeIfPresent(resource, (key, current) -> {
            if (current.token() == token && !current.isExpired(now)) {
                extended[0] = new Lease(resource, current.context(), token, current.leasedAt(), now.plus(ttl));
                return extended[0];
            }
            return current; // wrong token or already expired: leave the mapping unchanged
        });
        return Optional.ofNullable(extended[0]);
    }

    /**
     * Releases {@code resource} if the caller holds it, identified by its fencing {@code token}.
     * Presenting a stale token — because the lease expired and was re-acquired by someone else — is a
     * no-op, so a late release cannot evict the new holder.
     *
     * @param resource the leased resource
     * @param token the fencing token of the lease the caller holds
     * @return whether a lease held under {@code token} was released
     * @throws NullPointerException if {@code resource} is null
     */
    public boolean release(ResourceId resource, long token) {
        Objects.requireNonNull(resource, "resource must not be null");
        boolean[] released = {false};
        active.computeIfPresent(resource, (key, current) -> {
            if (current.token() == token) {
                released[0] = true;
                return null; // token matches: remove the mapping
            }
            return current; // stale token: leave the current holder's lease untouched
        });
        return released[0];
    }

    /**
     * Whether {@code resource} is held by a live (non-expired) lease at {@code now}.
     *
     * @param resource the resource to check
     * @param now the current instant
     * @return whether the resource is currently leased
     * @throws NullPointerException if {@code resource} or {@code now} is null
     */
    public boolean isLeased(ResourceId resource, Instant now) {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Lease current = active.get(resource);
        return current != null && !current.isExpired(now);
    }

    private static void requirePositive(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }
}
