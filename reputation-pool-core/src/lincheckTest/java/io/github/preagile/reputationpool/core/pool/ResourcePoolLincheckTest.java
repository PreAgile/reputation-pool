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
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@link ResourcePool} facade linearizable for the lease lifecycle on one resource:
 * acquire, fencing-token release/renew, and reputation reports racing each other across the pool's
 * three pieces of shared state (cells, blocklist, lease registry).
 *
 * <p>The facade is where side effects enter, so every source of nondeterminism is pinned — the
 * precondition for comparing a concurrent run against a sequential replay:
 *
 * <ul>
 *   <li><b>Time</b>: {@code Clock.fixed} (design rule: time is injected), so lease expiry and
 *       cooldowns never fire mid-scenario.
 *   <li><b>Selection</b>: {@link FirstByIdSelectionStrategy}; a seeded random would not do, since
 *       interleavings consume the stream in different orders.
 *   <li><b>Events</b>: a no-op sink. Event ordering is not part of this spec; the audit trail's own
 *       conservation tests cover that surface.
 * </ul>
 *
 * <p><b>Why one resource and no block/unblock here.</b> Fencing tokens draw from a global counter,
 * and only per-resource token order is contract (see {@link LeaseRegistryLincheckTest} for the trace
 * that taught us this). On one resource, token matching is sound — unless the acquire undo path runs:
 * {@code acquire} re-checks the blocklist after claiming and undoes the claim if a concurrent
 * {@code block} won, which burns a token a sequential replay never draws, skewing every later match.
 * So this class keeps the blocklist quiet and checks lease semantics exhaustively;
 * {@link ResourcePoolBlocklistLincheckTest} takes the complement — acquire racing block/unblock,
 * including that undo path — with token-free operations.
 */
@Param(name = "token", gen = IntGen.class, conf = "1:4")
public class ResourcePoolLincheckTest {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Context CONTEXT = new Context("lincheck");
    private static final ResourceId RESOURCE = new ResourceId(ResourceKind.PROXY, "10.0.0.1:8080");

    private final ResourcePool pool = new ResourcePool(
            new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 2, 2),
            new FirstByIdSelectionStrategy(),
            event -> {},
            Clock.fixed(NOW, ZoneOffset.UTC),
            new Random(42),
            TTL);

    {
        pool.register(RESOURCE);
    }

    @Operation
    public boolean acquire() {
        return pool.acquire(CONTEXT).isPresent();
    }

    @Operation
    public boolean release(@Param(name = "token") int token) {
        return pool.release(new Lease(RESOURCE, CONTEXT, token, NOW, NOW.plus(TTL)));
    }

    @Operation
    public boolean renew(@Param(name = "token") int token) {
        return pool.renew(new Lease(RESOURCE, CONTEXT, token, NOW, NOW.plus(TTL)))
                .isPresent();
    }

    @Operation
    public void reportSuccess() {
        pool.report(RESOURCE, CONTEXT, new Outcome.Success(Duration.ofMillis(100)));
    }

    @Operation
    public void reportFailure() {
        pool.report(RESOURCE, CONTEXT, new Outcome.Failure(FailureType.TIMEOUT, Duration.ofSeconds(2)));
    }

    @Test
    void modelChecking() {
        LincheckSettings.modelChecking().check(getClass());
    }

    @Test
    void stress() {
        LincheckSettings.stress().check(getClass());
    }
}
