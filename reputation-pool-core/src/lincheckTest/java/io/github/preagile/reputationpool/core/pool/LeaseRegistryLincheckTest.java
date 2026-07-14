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
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.time.Duration;
import java.time.Instant;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.junit.jupiter.api.Test;

/**
 * Proves the full {@link LeaseRegistry} contract — exclusive claim, fencing-token release/renew,
 * liveness visibility — linearizable <em>on one resource</em>. Lincheck generates concurrent
 * scenarios over the declared operations and verifies every observed outcome matches some sequential
 * ordering; on failure, the model-checking mode replays the exact interleaving and prints a minimal
 * trace. This is the proof the hand-rolled 32-thread stress tests in {@code src/test} can only
 * gesture at.
 *
 * <p><b>Why one resource — found, not assumed.</b> The first draft ran these operations over three
 * resources, and Lincheck's very first runs rejected it with instructive traces: inside
 * {@code ConcurrentHashMap.compute}, a claim draws its fencing token from the global counter and can
 * be switched out <em>before {@code setTabAt} publishes the mapping</em>; a claim on a <em>different</em>
 * resource then draws the next token and becomes visible first. Token order said A-then-B, visibility
 * said B-then-A — no linearization exists once token values (or forged-token match results) reach the
 * observable history. That is not a registry bug: fencing promises <em>per-resource</em> monotonicity,
 * published atomically with the mapping by the same bin-locked {@code compute}, and a caller can only
 * present a token it received from its own acquire — cross-resource allocation order is unobservable
 * under the real dataflow. On a single resource that artifact disappears (same-key computes serialize
 * on the bin lock), so token matching is fully sound here and the whole fencing semantics gets
 * checked: exactly one live holder, a stale token can neither release nor extend, the current token
 * can. Cross-resource independence is covered token-free by {@link LeaseRegistryIndependenceLincheckTest}.
 *
 * <p>Determinism, the precondition for comparing against a sequential replay, is otherwise free: the
 * registry takes {@code now} as an argument (design rule: time is injected), so a constant {@code NOW}
 * means expiry can never fire mid-scenario. The {@code token} parameter range (1..4) is deliberately
 * small: the counter starts at 1 and a scenario performs only a few successful acquires, so generated
 * tokens collide with real ones — producing both genuine matches and stale-token attempts.
 */
@Param(name = "token", gen = IntGen.class, conf = "1:4")
public class LeaseRegistryLincheckTest {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Context CONTEXT = new Context("lincheck");
    private static final ResourceId RESOURCE = new ResourceId(ResourceKind.PROXY, "10.0.0.1:8080");

    private final LeaseRegistry registry = new LeaseRegistry();

    @Operation
    public boolean tryAcquire() {
        return registry.tryAcquire(RESOURCE, CONTEXT, NOW, TTL).isPresent();
    }

    @Operation
    public boolean renew(@Param(name = "token") int token) {
        return registry.renew(RESOURCE, token, NOW, TTL).isPresent();
    }

    @Operation
    public boolean release(@Param(name = "token") int token) {
        return registry.release(RESOURCE, token);
    }

    @Operation
    public boolean isLeased() {
        return registry.isLeased(RESOURCE, NOW);
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
