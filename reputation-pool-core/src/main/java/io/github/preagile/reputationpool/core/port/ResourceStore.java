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
package io.github.preagile.reputationpool.core.port;

import io.github.preagile.reputationpool.core.domain.PoolSnapshot;
import java.util.Optional;

/**
 * Where the pool's durable state is persisted, so it survives a process restart. As with
 * {@link EventSink}, the core declares the contract in domain terms and an outer module fulfils it —
 * the first implementation being a PostgreSQL store. This inverts the dependency, so no database,
 * SQL, or connection concern ever leaks into the pure core.
 *
 * <p>The unit of persistence is the whole {@link PoolSnapshot}, not an individual cell. A snapshot is
 * a periodic checkpoint of everything durable — cells, blocklist, and registered resources together —
 * which is what lets persistence never drop the blocklist (a cells-only store would, re-lending a
 * blocklisted resource after a restart). Leases are intentionally not part of the contract: they are
 * runtime coordination and are void on restart.
 *
 * <p>Implementations decide their own durability and consistency guarantees (atomic swap, last-write-
 * wins, and so on); the core only requires that a {@link #load()} following a completed {@link #save}
 * returns a snapshot equal to what was saved.
 */
public interface ResourceStore {

    /**
     * Writes {@code snapshot} as the current durable checkpoint of the pool, replacing any previous
     * one. Called periodically while the pool runs, not on every state change.
     *
     * @param snapshot the whole-pool state to persist; never null
     */
    void save(PoolSnapshot snapshot);

    /**
     * Reads the most recently saved checkpoint, to rehydrate the pool at startup.
     *
     * @return the last saved snapshot, or {@link Optional#empty()} on first run when nothing has been
     *     persisted yet
     */
    Optional<PoolSnapshot> load();
}
