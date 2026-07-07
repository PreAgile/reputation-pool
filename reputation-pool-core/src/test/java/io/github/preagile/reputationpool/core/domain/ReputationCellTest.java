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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReputationCellTest {

    private static final ResourceId RID = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
    private static final Context CTX = new Context("baemin");
    private static final Instant NOW = Instant.parse("2026-07-07T00:00:00Z");

    @Test
    void freshStartsNeutralHealthyAndUncooled() {
        var cell = ReputationCell.fresh(RID, CTX, NOW);
        assertThat(cell.resourceId()).isEqualTo(RID);
        assertThat(cell.context()).isEqualTo(CTX);
        assertThat(cell.score()).isZero();
        assertThat(cell.consecutiveFailures()).isZero();
        assertThat(cell.consecutiveSuccesses()).isZero();
        assertThat(cell.window()).isEmpty();
        assertThat(cell.state()).isEqualTo(ResourceState.HEALTHY);
        assertThat(cell.cooldownUntil()).isEqualTo(Instant.EPOCH); // EPOCH == "not cooling"
        assertThat(cell.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void toBuilderRoundTripsToAnEqualCell() {
        var cell = ReputationCell.fresh(RID, CTX, NOW);
        assertThat(cell.toBuilder().build()).isEqualTo(cell);
    }

    @Test
    void builderOverridesOnlyTheGivenField() {
        var cell = ReputationCell.fresh(RID, CTX, NOW);
        var scored = cell.toBuilder().score(50.0).build();
        assertThat(scored.score()).isEqualTo(50.0);
        // everything else is unchanged
        assertThat(scored.toBuilder().score(0.0).build()).isEqualTo(cell);
    }

    @Test
    void builderAppliesAMultiFieldTransitionAtOnce() {
        var until = NOW.plusSeconds(3600);
        var cooled = ReputationCell.fresh(RID, CTX, NOW).toBuilder()
                .score(-30.0)
                .consecutiveFailures(3)
                .consecutiveSuccesses(0)
                .state(ResourceState.COOLING)
                .cooldownUntil(until)
                .updatedAt(NOW.plusSeconds(1))
                .build();
        assertThat(cooled.state()).isEqualTo(ResourceState.COOLING);
        assertThat(cooled.cooldownUntil()).isEqualTo(until);
        assertThat(cooled.consecutiveFailures()).isEqualTo(3);
        assertThat(cooled.score()).isEqualTo(-30.0);
        // identity is preserved across transitions
        assertThat(cooled.resourceId()).isEqualTo(RID);
        assertThat(cooled.context()).isEqualTo(CTX);
    }

    @Test
    void windowIsDefensivelyCopiedAndUnmodifiable() {
        var mutable = new ArrayList<Outcome>();
        mutable.add(new Outcome.Success(Duration.ofMillis(100)));
        var cell =
                ReputationCell.fresh(RID, CTX, NOW).toBuilder().window(mutable).build();

        // mutating the source list must not leak into the cell
        mutable.add(new Outcome.Failure(FailureType.TIMEOUT, Duration.ofMillis(50)));
        assertThat(cell.window()).hasSize(1);

        // the stored window is itself immutable
        assertThatThrownBy(() -> cell.window().add(new Outcome.Success(Duration.ZERO)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullComponents() {
        assertThatThrownBy(() ->
                        new ReputationCell(null, CTX, 0.0, 0, 0, List.of(), ResourceState.HEALTHY, Instant.EPOCH, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resourceId");
        assertThatThrownBy(
                        () -> new ReputationCell(RID, CTX, 0.0, 0, 0, null, ResourceState.HEALTHY, Instant.EPOCH, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new ReputationCell(RID, CTX, 0.0, 0, 0, List.of(), null, Instant.EPOCH, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("state");
    }

    @Test
    void rejectsNegativeConsecutiveFailures() {
        assertThatThrownBy(() ->
                        new ReputationCell(RID, CTX, 0.0, -1, 0, List.of(), ResourceState.HEALTHY, Instant.EPOCH, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeConsecutiveSuccesses() {
        assertThatThrownBy(() ->
                        new ReputationCell(RID, CTX, 0.0, 0, -1, List.of(), ResourceState.HEALTHY, Instant.EPOCH, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteScore() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> new ReputationCell(
                            RID, CTX, bad, 0, 0, List.of(), ResourceState.HEALTHY, Instant.EPOCH, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite");
        }
    }
}
