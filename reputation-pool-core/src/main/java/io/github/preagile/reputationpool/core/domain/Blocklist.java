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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The set of resources currently isolated from selection, each mapped to the instant its block
 * expires — the pool layer's authority for the invariant that a blocklisted resource can never be
 * lent.
 *
 * <p>Blocklisting is a hard, resource-global decision, unlike {@code COOLING}, which the engine
 * drives per {@code (resource × context)} and recovers from automatically as traffic succeeds. A
 * blocklisted resource leaves isolation only by an explicit {@link #release} or by its entry
 * expiring; ordinary traffic can never clear it. This is why the decision lives in the pool layer,
 * not the engine.
 *
 * <p>This is an immutable value: every mutator returns a <em>new</em> {@code Blocklist} and never
 * touches this one, so a reader holding a reference always sees a consistent snapshot. Concurrency
 * is therefore not this type's concern — the pool holds the current blocklist in an
 * {@link java.util.concurrent.atomic.AtomicReference} and swaps it with a compare-and-set. The
 * immutable value carries the correctness; the reference swap carries the atomicity. Writes
 * (block/release) are rare and reads ({@link #isBlocked} on every acquire) are frequent, so a
 * whole-snapshot value is a better fit here than a per-key concurrent map.
 *
 * <p>An entry maps a resource to the instant its block expires, <em>exclusive</em>: {@link #isBlocked}
 * is the pure time comparison {@code now.isBefore(until)}, mirroring the {@code cooldownUntil}
 * convention on the reputation cell. A permanent block uses {@link Instant#MAX} as the never-expires
 * sentinel, which no real {@code now} can reach.
 */
public record Blocklist(Map<ResourceId, Instant> entries) {

    /**
     * @throws NullPointerException if {@code entries}, or any key or value within it, is null
     */
    public Blocklist {
        Objects.requireNonNull(entries, "entries must not be null");
        // defensive, immutable copy; also rejects null keys and values
        entries = Map.copyOf(entries);
    }

    /** An empty blocklist that isolates nothing. */
    public static Blocklist empty() {
        return new Blocklist(Map.of());
    }

    /**
     * Whether {@code resource} is isolated at {@code now} — that is, it has an entry whose expiry is
     * still in the future. Expiry is exclusive, so a block ends exactly at its {@code until}.
     *
     * @throws NullPointerException if {@code resource} or {@code now} is null
     */
    public boolean isBlocked(ResourceId resource, Instant now) {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Instant until = entries.get(resource);
        return until != null && now.isBefore(until);
    }

    /**
     * This blocklist with {@code resource} isolated until {@code until} (exclusive). Replaces any
     * existing entry for the resource: the new expiry wins whether it extends or shortens the block.
     * Passing an {@code until} at or before the caller's {@code now} yields an already-expired entry
     * that {@link #isBlocked} reports as not blocked — the value stays pure by never reading the
     * clock itself. Returns this same blocklist when the entry is unchanged.
     *
     * @throws NullPointerException if {@code resource} or {@code until} is null
     */
    public Blocklist block(ResourceId resource, Instant until) {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(until, "until must not be null");
        if (until.equals(entries.get(resource))) {
            return this;
        }
        var next = new HashMap<>(entries);
        next.put(resource, until);
        return new Blocklist(next);
    }

    /**
     * This blocklist with {@code resource} isolated with no expiry, released only by an explicit
     * {@link #release}. Uses {@link Instant#MAX} as the never-expires sentinel.
     *
     * @throws NullPointerException if {@code resource} is null
     */
    public Blocklist blockPermanently(ResourceId resource) {
        return block(resource, Instant.MAX);
    }

    /**
     * This blocklist with any entry for {@code resource} removed — together with expiry, the only
     * way a resource leaves isolation. Releasing a resource that is not blocked is a no-op and
     * returns this same blocklist.
     *
     * @throws NullPointerException if {@code resource} is null
     */
    public Blocklist release(ResourceId resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        if (!entries.containsKey(resource)) {
            return this;
        }
        var next = new HashMap<>(entries);
        next.remove(resource);
        return new Blocklist(next);
    }

    /**
     * This blocklist with every entry that has expired at {@code now} removed. Purely a compaction:
     * it never changes what {@link #isBlocked} reports at {@code now}, since an expired entry already
     * reports as not blocked. Returns this same blocklist when nothing has expired.
     *
     * @throws NullPointerException if {@code now} is null
     */
    public Blocklist sweepExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        boolean hasExpired = false;
        for (Instant until : entries.values()) {
            if (!now.isBefore(until)) {
                hasExpired = true;
                break;
            }
        }
        if (!hasExpired) {
            return this; // nothing expired (or empty): the value is unchanged
        }
        var next = new HashMap<ResourceId, Instant>();
        for (var entry : entries.entrySet()) {
            if (now.isBefore(entry.getValue())) {
                next.put(entry.getKey(), entry.getValue());
            }
        }
        return new Blocklist(next);
    }
}
