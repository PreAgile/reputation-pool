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
package io.github.preagile.reputationpool.core.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class MetricsSinkTest {

    @Test
    void noopReportsWithoutThrowing() {
        MetricsSink sink = MetricsSink.noop();
        assertThatCode(() -> {
                    sink.acquisitionLatency(1_234_567L);
                    sink.leaseOccupancy(3, 10);
                })
                .doesNotThrowAnyException();
    }

    @Test
    void noopIsAStableSingleton() {
        // the default sink holds no state, so an assembly can reuse the one instance
        assertThat(MetricsSink.noop()).isSameAs(MetricsSink.noop());
    }
}
