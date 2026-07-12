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
package io.github.preagile.reputationpool.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.PoolSnapshot;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.pool.WeightedRandomSelectionStrategy;
import io.github.preagile.reputationpool.core.port.ResourceStore;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Verifies the durable lifecycle the composition root owns when a {@link ResourceStore} is present —
 * restore-on-start, the exception-isolated periodic checkpoint, and the final save on shutdown — and
 * that all three are no-ops when no store is given.
 *
 * <p>Docker-free by design: the store is a {@link FakeResourceStore} holding one snapshot in a field.
 * Assertions ride the {@code checkpoint()} and {@code pool()} hooks directly, never the scheduler, so
 * the tests are deterministic and carry no timing.
 */
class AdvisorServerPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TTL = Duration.ofSeconds(30);

    private static final ResourceId PROXY = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
    private static final Context CONTEXT = new Context("marketplace-a");

    @Test
    void restoreOnStart_rehydratesThePoolFromTheStoreBeforeServingTraffic() {
        // A non-trivial durable state: a registered resource that has been blocklisted.
        ResourcePool source = newPool();
        source.register(PROXY);
        source.block(PROXY, Duration.ofMinutes(5));
        PoolSnapshot saved = source.snapshot();

        FakeResourceStore fakeStore = new FakeResourceStore();
        fakeStore.save(saved);

        AdvisorServer server = AdvisorServer.create(0, CLOCK, new Random(42), TTL, fakeStore);

        assertThat(server.pool().snapshot())
                .as("the pool is restored from the store during create(), before start()")
                .isEqualTo(saved);
    }

    @Test
    void checkpointSaves_writesTheCurrentPoolSnapshotToTheStore() {
        FakeResourceStore fakeStore = new FakeResourceStore();
        AdvisorServer server = AdvisorServer.create(0, CLOCK, new Random(42), TTL, fakeStore);

        server.pool().register(PROXY);
        server.pool().block(PROXY, Duration.ofMinutes(5));
        server.checkpoint();

        assertThat(fakeStore.load())
                .as("checkpoint() persists exactly the pool's current snapshot")
                .contains(server.pool().snapshot());
    }

    @Test
    void finalSaveOnShutdown_persistsTheFinalPoolState() throws IOException, InterruptedException {
        FakeResourceStore fakeStore = new FakeResourceStore();
        AdvisorServer server = AdvisorServer.create(0, CLOCK, new Random(42), TTL, fakeStore);

        server.pool().register(PROXY);
        server.pool().block(PROXY, Duration.ofMinutes(5));

        server.start();
        server.shutdown(Duration.ofSeconds(10));

        assertThat(fakeStore.load())
                .as("shutdown() takes a final checkpoint of the drained, final pool state")
                .contains(server.pool().snapshot());
    }

    @Test
    void emptyFirstRun_startsFreshWhenTheStoreHasNothingSaved() {
        FakeResourceStore fakeStore = new FakeResourceStore();

        AdvisorServer server = AdvisorServer.create(0, CLOCK, new Random(42), TTL, fakeStore);

        PoolSnapshot snapshot = server.pool().snapshot();
        assertThat(snapshot.cells()).isEmpty();
        assertThat(snapshot.registered()).isEmpty();
        assertThat(snapshot.blocklist().entries()).isEmpty();
    }

    @Test
    void noStore_checkpointAndShutdownAreNoOps() throws IOException, InterruptedException {
        AdvisorServer server = AdvisorServer.create(0, CLOCK, new Random(42), TTL);

        // No store: checkpoint() does nothing, start()/shutdown() start no scheduler and throw nothing.
        assertThatCode(() -> {
                    server.checkpoint();
                    server.start();
                    server.shutdown(Duration.ofSeconds(10));
                })
                .doesNotThrowAnyException();
    }

    private static ResourcePool newPool() {
        return new ResourcePool(
                new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 2, 2),
                new WeightedRandomSelectionStrategy(),
                event -> {},
                CLOCK,
                new Random(42),
                TTL);
    }

    /**
     * A {@link ResourceStore} that keeps only the last saved snapshot in a field — enough to prove the
     * lifecycle wiring without a database. {@code load()} returns that snapshot, or empty before any
     * save (the first-run case).
     */
    private static final class FakeResourceStore implements ResourceStore {

        private PoolSnapshot saved;

        @Override
        public void save(PoolSnapshot snapshot) {
            this.saved = snapshot;
        }

        @Override
        public Optional<PoolSnapshot> load() {
            return Optional.ofNullable(saved);
        }
    }
}
