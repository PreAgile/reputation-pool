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

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A point-in-time capture of the pool's whole <em>durable</em> state — everything that must survive a
 * process restart, bundled into one immutable value.
 *
 * <p>That state is three distinct structures, not one:
 *
 * <ul>
 *   <li>{@code cells} — the reputation of every {@code (resource × context)} pair the pool has seen;
 *   <li>{@code blocklist} — the resources currently isolated from selection, with their expiries;
 *   <li>{@code registered} — the set of resources eligible to be lent at all.
 * </ul>
 *
 * <p>All three are carried together on purpose. Persisting only the cells would silently drop the
 * blocklist, so a restart would re-lend a resource an operator had explicitly isolated — the exact
 * failure the persistence layer exists to prevent. Treating the durable state as one whole-pool
 * checkpoint closes that gap by construction.
 *
 * <p>Leases are deliberately <em>excluded</em>: a lease is runtime coordination (who holds what right
 * now, and until when), not durable truth. On restart nothing is held, and the TTL safety net would
 * have reclaimed any stale lease anyway; persisting them would only resurrect phantom holders.
 *
 * <p>Like the rest of the domain this is an immutable value: the compact constructor rejects nulls
 * and takes defensive, unmodifiable copies of the two collections, so a snapshot never changes after
 * it is taken even if the source structures it was built from keep evolving.
 *
 * @param cells the reputation cell for each {@code (resource × context)} pair, keyed by {@link CellKey}
 * @param blocklist the currently isolated resources; already an immutable value
 * @param registered the resources eligible for selection
 */
public record PoolSnapshot(Map<CellKey, ReputationCell> cells, Blocklist blocklist, Set<ResourceId> registered) {

    /**
     * @throws NullPointerException if any argument, or any key/value/element within {@code cells} or
     *     {@code registered}, is null
     */
    public PoolSnapshot {
        Objects.requireNonNull(blocklist, "blocklist must not be null");
        // defensive, immutable copies; also reject null keys, values, and elements
        cells = Map.copyOf(cells);
        registered = Set.copyOf(registered);
    }
}
