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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.ReputationCell;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class WeightedRandomSelectionStrategyTest {

    private static final Context CTX = new Context("cpeats");
    private static final Instant NOW = Instant.parse("2026-07-08T00:00:00Z");

    private final SelectionStrategy strategy = new WeightedRandomSelectionStrategy();

    private static ReputationCell cell(String value, double score) {
        return ReputationCell.fresh(new ResourceId(ResourceKind.PROXY, value), CTX, NOW).toBuilder()
                .score(score)
                .build();
    }

    @Test
    void emptyCandidatesSelectNothing() {
        assertThat(strategy.select(List.of(), new Random(1))).isEmpty();
    }

    @Test
    void singleCandidateIsAlwaysChosen() {
        var only = cell("solo", -42.0);
        assertThat(strategy.select(List.of(only), new Random(1))).contains(only);
    }

    @Test
    void selectionIsReproducibleUnderTheSameSeed() {
        var candidates = List.of(cell("a", 10.0), cell("b", 50.0), cell("c", -30.0));
        assertThat(strategy.select(candidates, new Random(99))).isEqualTo(strategy.select(candidates, new Random(99)));
    }

    @Test
    void aDominantScoreIsChosenAlmostAlways() {
        var dominant = cell("hot", 100.0);
        var candidates = List.of(cell("a", -100.0), dominant, cell("b", -100.0), cell("c", -100.0));
        var random = new Random(1);
        int hits = 0;
        int draws = 10_000;
        for (int i = 0; i < draws; i++) {
            if (strategy.select(candidates, random).orElseThrow().equals(dominant)) {
                hits++;
            }
        }
        assertThat((double) hits / draws).isGreaterThan(0.90);
    }

    @Test
    void higherScoreIsChosenMoreOftenThanLower() {
        var high = cell("high", 100.0);
        var low = cell("low", -100.0);
        var candidates = List.of(low, high);
        var random = new Random(3);
        int highHits = 0;
        int lowHits = 0;
        for (int i = 0; i < 10_000; i++) {
            if (strategy.select(candidates, random).orElseThrow().equals(high)) {
                highHits++;
            } else {
                lowHits++;
            }
        }
        assertThat(highHits).isGreaterThan(lowHits);
    }

    @Test
    void equalScoresAreChosenRoughlyUniformly() {
        var x = cell("x", 10.0);
        var y = cell("y", 10.0);
        var candidates = List.of(x, y);
        var random = new Random(7);
        int xHits = 0;
        int draws = 20_000;
        for (int i = 0; i < draws; i++) {
            if (strategy.select(candidates, random).orElseThrow().equals(x)) {
                xHits++;
            }
        }
        assertThat((double) xHits / draws).isBetween(0.45, 0.55);
    }

    @Test
    void aLargerExplorationFloorFlattensTheDistribution() {
        var high = cell("high", 100.0);
        var low = cell("low", 0.0);
        var candidates = List.of(low, high);

        // a huge floor swamps the score gap, pushing the choice toward uniform
        var flat = new WeightedRandomSelectionStrategy(10_000.0);
        var random = new Random(5);
        int lowHits = 0;
        int draws = 20_000;
        for (int i = 0; i < draws; i++) {
            if (flat.select(candidates, random).orElseThrow().equals(low)) {
                lowHits++;
            }
        }
        assertThat((double) lowHits / draws).isBetween(0.45, 0.55);
    }

    @Test
    void aTinyExplorationFloorSharpensTowardTheHighestScore() {
        var high = cell("high", 60.0);
        var low = cell("low", 40.0);
        var candidates = List.of(low, high);

        // a near-zero floor makes the weight almost purely score-driven, so the higher score dominates
        var sharp = new WeightedRandomSelectionStrategy(1e-6);
        var random = new Random(5);
        int highHits = 0;
        int draws = 20_000;
        for (int i = 0; i < draws; i++) {
            if (sharp.select(candidates, random).orElseThrow().equals(high)) {
                highHits++;
            }
        }
        assertThat((double) highHits / draws).isGreaterThan(0.95);
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> strategy.select(null, new Random(1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("candidates");
        assertThatThrownBy(() -> strategy.select(List.of(cell("a", 1.0)), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("random");
    }

    @Test
    void rejectsNonPositiveOrNonFiniteExplorationFloor() {
        for (double bad : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new WeightedRandomSelectionStrategy(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void defaultConstructorUsesTheDefaultFloor() {
        assertThat(new WeightedRandomSelectionStrategy().explorationFloor())
                .isEqualTo(WeightedRandomSelectionStrategy.DEFAULT_EXPLORATION_FLOOR);
    }

    // --- invariants, attacked over many generated inputs (jqwik) ---

    @Property
    void alwaysSelectsOneOfTheCandidates(
            @ForAll @Size(min = 1, max = 12) List<@IntRange(min = -100, max = 100) Integer> scores, @ForAll long seed) {
        var candidates = candidatesOf(scores);
        var pick = strategy.select(candidates, new Random(seed));
        assertThat(pick).isPresent();
        assertThat(candidates).contains(pick.get());
    }

    @Property
    void isDeterministicUnderTheSameSeed(
            @ForAll @Size(min = 1, max = 12) List<@IntRange(min = -100, max = 100) Integer> scores, @ForAll long seed) {
        var candidates = candidatesOf(scores);
        assertThat(strategy.select(candidates, new Random(seed)))
                .isEqualTo(strategy.select(candidates, new Random(seed)));
    }

    private static List<ReputationCell> candidatesOf(List<Integer> scores) {
        var candidates = new ArrayList<ReputationCell>();
        for (int i = 0; i < scores.size(); i++) {
            candidates.add(cell("p" + i, scores.get(i))); // index keeps ids distinct
        }
        return candidates;
    }
}
