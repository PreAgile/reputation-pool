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
import org.junit.jupiter.api.Test;

class OutcomeTest {

    @Test
    void successCarriesLatency() {
        var s = new Outcome.Success(Duration.ofMillis(800));
        assertThat(s.latency()).isEqualTo(Duration.ofMillis(800));
    }

    @Test
    void failureCarriesTypeAndLatency() {
        var f = new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(120));
        assertThat(f.type()).isEqualTo(FailureType.BLOCKED);
        assertThat(f.latency()).isEqualTo(Duration.ofMillis(120));
    }

    @Test
    void rejectsNullOrNegativeLatency() {
        assertThatThrownBy(() -> new Outcome.Success(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Outcome.Success(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Outcome.Failure(FailureType.TIMEOUT, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullFailureType() {
        assertThatThrownBy(() -> new Outcome.Failure(null, Duration.ZERO)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void latencyIsReadableWithoutDiscriminatingCase() {
        // the common accessor lets timing be read uniformly over any Outcome
        Outcome success = new Outcome.Success(Duration.ofMillis(50));
        Outcome failure = new Outcome.Failure(FailureType.SLOW, Duration.ofSeconds(4));
        assertThat(success.latency()).isEqualTo(Duration.ofMillis(50));
        assertThat(failure.latency()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void exhaustiveSwitchNeedsNoDefault() {
        // this compiles only because Outcome is sealed: Success + Failure is the whole set.
        // adding a third permitted case would make classify(...) fail to compile until handled.
        assertThat(classify(new Outcome.Success(Duration.ZERO))).isEqualTo("ok");
        assertThat(classify(new Outcome.Failure(FailureType.BLOCKED, Duration.ZERO)))
                .isEqualTo("blocked");
    }

    private static String classify(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Success s -> "ok";
            case Outcome.Failure f -> f.type().name().toLowerCase();
        };
    }
}
