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
 * Proves {@link LeaseRegistry} linearizable <em>across</em> resources for the token-free surface:
 * concurrent claims on different resources never disturb each other, and a granted claim becomes
 * visible to {@link LeaseRegistry#isLeased} consistently with some sequential order.
 *
 * <p>Deliberately excludes {@code release}/{@code renew}: their results (and effects) depend on
 * fencing-token matches, and generated integer tokens would forge capabilities the real dataflow
 * cannot produce — re-exposing the global counter's allocation order, which is not part of the
 * contract. See {@link LeaseRegistryLincheckTest} for that story and for the full single-resource
 * fencing semantics; this class checks exactly the complement.
 */
@Param(name = "res", gen = IntGen.class, conf = "1:3")
public class LeaseRegistryIndependenceLincheckTest {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Context CONTEXT = new Context("lincheck");

    private final LeaseRegistry registry = new LeaseRegistry();

    private static ResourceId res(int i) {
        return new ResourceId(ResourceKind.PROXY, "10.0.0." + i + ":8080");
    }

    @Operation
    public boolean tryAcquire(@Param(name = "res") int r) {
        return registry.tryAcquire(res(r), CONTEXT, NOW, TTL).isPresent();
    }

    @Operation
    public boolean isLeased(@Param(name = "res") int r) {
        return registry.isLeased(res(r), NOW);
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
