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

import io.github.preagile.reputationpool.core.domain.ReputationCell;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Selection for Lincheck harnesses: always the candidate with the smallest resource id, ignoring the
 * injected randomness entirely. Linearizability checking compares a concurrent run against a
 * sequential replay, so the choice must be a deterministic function of the candidate set — a seeded
 * {@link RandomGenerator} is not enough, because different interleavings consume the stream in
 * different orders. The strategy javadoc names "a highest-score pick for determinism" as a valid
 * policy; this is that idea, keyed by id so the pick is stable even when scores tie.
 *
 * <p>Written as an explicit loop, not {@code Stream.min(Comparator.comparing(...))}: Lincheck's
 * model-checking analysis reflects into receiver fields along the call stack, and a JDK lambda proxy
 * ({@code java.util.Comparator$$Lambda}) sits in a package the unnamed module cannot open, crashing
 * the trace collector.
 */
final class FirstByIdSelectionStrategy implements SelectionStrategy {

    @Override
    public Optional<ReputationCell> select(List<ReputationCell> candidates, RandomGenerator random) {
        ReputationCell best = null;
        for (ReputationCell candidate : candidates) {
            if (best == null
                    || candidate
                                    .resourceId()
                                    .value()
                                    .compareTo(best.resourceId().value())
                            < 0) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }
}
