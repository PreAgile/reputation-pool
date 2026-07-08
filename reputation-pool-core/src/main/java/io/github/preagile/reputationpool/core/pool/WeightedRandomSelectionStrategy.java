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
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The default selection policy: a score-weighted random choice. Higher-scored resources are more
 * likely to be picked, but the choice is randomized rather than always taking the maximum, so load
 * spreads across the healthy pool instead of hammering the single best resource.
 *
 * <p>Each candidate's weight is {@code (score - minScore) + explorationFloor}, where {@code minScore}
 * is the lowest score among the candidates presented. Two consequences follow. First, weights are
 * always non-negative even though scores range into the negative. Second, the exploration floor keeps
 * every eligible candidate at a nonzero weight, so the lowest-scored one still gets an occasional
 * turn — useful for re-probing a resource on the edge of recovery rather than starving it forever.
 *
 * <p>Weights are relative to the candidates presented, not to the absolute {@code [-100, 100]} scale:
 * among an already-eligible set, what matters is which are better than the others right now. A larger
 * floor flattens the distribution toward uniform; a smaller one sharpens the preference for the best.
 *
 * <p>Stateless and therefore thread-safe in itself — it has no mutable fields. Under concurrent use,
 * whether the {@link RandomGenerator} passed to {@link #select} may be shared is the caller's concern:
 * give each thread its own generator, or pass one that is itself thread-safe.
 */
public final class WeightedRandomSelectionStrategy implements SelectionStrategy {

    /** Default exploration floor: the minimum weight every eligible candidate receives. */
    public static final double DEFAULT_EXPLORATION_FLOOR = 1.0;

    private final double explorationFloor;

    /** Creates a strategy with the {@link #DEFAULT_EXPLORATION_FLOOR}. */
    public WeightedRandomSelectionStrategy() {
        this(DEFAULT_EXPLORATION_FLOOR);
    }

    /**
     * Creates a strategy with a configurable exploration floor.
     *
     * @param explorationFloor the minimum weight every candidate receives; a larger value flattens the
     *     distribution toward uniform, a smaller one sharpens the preference for higher scores
     * @throws IllegalArgumentException if {@code explorationFloor} is not both finite and positive — a
     *     zero or negative floor could zero out or invert a candidate's weight
     */
    public WeightedRandomSelectionStrategy(double explorationFloor) {
        if (!Double.isFinite(explorationFloor) || explorationFloor <= 0.0) {
            throw new IllegalArgumentException("explorationFloor must be finite and positive");
        }
        this.explorationFloor = explorationFloor;
    }

    public double explorationFloor() {
        return explorationFloor;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs in a single pass modulo the two summations: O(n) in the number of candidates.
     */
    @Override
    public Optional<ReputationCell> select(List<ReputationCell> candidates, RandomGenerator random) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(random, "random must not be null");
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }

        double minScore = Double.POSITIVE_INFINITY;
        for (ReputationCell candidate : candidates) {
            minScore = Math.min(minScore, candidate.score());
        }
        double totalWeight = 0.0;
        for (ReputationCell candidate : candidates) {
            totalWeight += weight(candidate, minScore);
        }

        double target = random.nextDouble() * totalWeight;
        double cumulative = 0.0;
        int last = candidates.size() - 1;
        for (int i = 0; i < last; i++) {
            cumulative += weight(candidates.get(i), minScore);
            if (target < cumulative) {
                return Optional.of(candidates.get(i));
            }
        }
        // The last candidate holds the remaining weight, so it is the natural else rather than a
        // fallback; this also absorbs any floating-point drift between the summed total and the
        // running sum, which could otherwise leave target just above the final cumulative.
        return Optional.of(candidates.get(last));
    }

    // Non-negative by construction: score >= minScore (the batch minimum) and the constructor
    // guarantees explorationFloor > 0, so every candidate's weight is strictly positive.
    private double weight(ReputationCell candidate, double minScore) {
        return (candidate.score() - minScore) + explorationFloor;
    }
}
