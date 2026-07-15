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

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.StressOptions;

/**
 * Shared Lincheck run settings. The defaults are sized for a quick local/CI sanity pass (the Caffeine
 * numbers); a deeper run overrides them without a code change via system properties:
 *
 * <pre>{@code ./gradlew :reputation-pool-core:lincheckTest -Dlincheck.modelChecking.iterations=200}</pre>
 *
 * <p>Both strategies run everywhere, because they catch different bugs: model checking controls
 * thread switches and yields a deterministic, minimal interleaving trace on failure, but assumes
 * sequential consistency and so cannot see low-level memory errors (a missing {@code volatile});
 * stress testing runs on the real memory model and can, but reproduces nothing.
 */
final class LincheckSettings {

    private LincheckSettings() {}

    /** Model checking: fewer, deeper scenarios — each invocation replays a controlled interleaving. */
    static ModelCheckingOptions modelChecking() {
        return new ModelCheckingOptions()
                .iterations(Integer.getInteger("lincheck.modelChecking.iterations", 25))
                .invocationsPerIteration(Integer.getInteger("lincheck.modelChecking.invocationsPerIteration", 400));
    }

    /** Stress: more invocations — the scheduler, not Lincheck, decides the interleavings. */
    static StressOptions stress() {
        return new StressOptions()
                .iterations(Integer.getInteger("lincheck.stress.iterations", 30))
                .invocationsPerIteration(Integer.getInteger("lincheck.stress.invocationsPerIteration", 3000));
    }
}
