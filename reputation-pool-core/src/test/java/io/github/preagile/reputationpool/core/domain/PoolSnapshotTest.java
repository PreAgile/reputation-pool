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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The invariants of {@link PoolSnapshot} as a value object: it rejects null state and is defended
 * against mutation of the structures it was built from — so a persisted checkpoint is a frozen truth,
 * never a live view of a pool that keeps changing under it.
 */
class PoolSnapshotTest {

    private static final ResourceId RID = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
    private static final Context CTX = new Context("checkout");
    private static final Instant NOW = Instant.parse("2026-07-08T00:00:00Z");

    private static CellKey key() {
        return new CellKey(RID, CTX);
    }

    private static ReputationCell cell() {
        return ReputationCell.fresh(RID, CTX, NOW);
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> new PoolSnapshot(null, Blocklist.empty(), Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PoolSnapshot(Map.of(), null, Set.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PoolSnapshot(Map.of(), Blocklist.empty(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void doesNotSeeLaterMutationsOfTheSourceCollections() {
        var cells = new HashMap<CellKey, ReputationCell>();
        cells.put(key(), cell());
        var registered = new HashSet<ResourceId>();
        registered.add(RID);

        var snapshot = new PoolSnapshot(cells, Blocklist.empty(), registered);

        // mutate the sources after the snapshot was taken
        cells.clear();
        registered.add(new ResourceId(ResourceKind.ACCOUNT, "acct-99"));

        assertThat(snapshot.cells()).containsOnlyKeys(key());
        assertThat(snapshot.registered()).containsExactly(RID);
    }

    @Test
    void exposesUnmodifiableCollections() {
        var snapshot = new PoolSnapshot(Map.of(key(), cell()), Blocklist.empty(), Set.of(RID));
        assertThatThrownBy(() -> snapshot.cells().put(key(), cell())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.registered().add(RID)).isInstanceOf(UnsupportedOperationException.class);
    }
}
