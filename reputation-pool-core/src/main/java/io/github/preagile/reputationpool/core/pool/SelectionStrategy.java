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
 * Chooses one resource to lease from a set of already-eligible candidates, weighting the choice by
 * reputation. The engine advances a score that it documents as "later used to weight selection"; this
 * is where that weighting happens.
 *
 * <p>An open contract: how to turn scores into a choice is a policy, and different strategies may be
 * plugged in — a score-weighted random choice to spread load, a highest-score pick for determinism,
 * an epsilon-greedy variant for exploration. This mirrors {@code CooldownPolicy}, whose cooldown curve
 * is likewise an open contract with a default implementation.
 *
 * <p>Selectability is decided upstream, not here. The pool has already removed blocklisted and
 * non-selectable resources, so every candidate presented is a valid choice. The strategy only ranks;
 * it never gates. It is also indifferent to how a candidate's score was computed (single- or two-layer)
 * — it reads {@link ReputationCell#score()} and nothing else.
 *
 * <p>Randomness is injected, exactly as time is. A score-weighted choice needs a source of randomness,
 * and taking a {@link RandomGenerator} per call — rather than reaching for {@code Math.random()} —
 * keeps the strategy a pure function: seed the generator and the choice is reproducible, so
 * time-independent tests are deterministic and production incidents are replayable.
 */
public interface SelectionStrategy {

    /**
     * Selects one candidate, weighting the choice by reputation.
     *
     * @param candidates the eligible resources to choose among; never null, and no element is null
     * @param random the injected source of randomness
     * @return the chosen candidate, or {@link Optional#empty()} if {@code candidates} is empty
     * @throws NullPointerException if {@code candidates} or {@code random} is null
     */
    Optional<ReputationCell> select(List<ReputationCell> candidates, RandomGenerator random);
}
