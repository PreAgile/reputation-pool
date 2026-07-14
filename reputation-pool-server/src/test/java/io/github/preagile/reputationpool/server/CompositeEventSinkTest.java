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
package io.github.preagile.reputationpool.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.port.EventSink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behavior specification for {@link CompositeEventSink} — the fan-out that lets the live event stream
 * and the durable audit trail sit side by side under the pool's single sink. The isolation case is the
 * one that matters: one misbehaving sink must cost only its own events, never the other sinks'.
 */
class CompositeEventSinkTest {

    private static final PoolEvent EVENT = new PoolEvent.ResourceUnblocked(
            new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080"), Instant.parse("2026-07-13T00:00:00Z"));

    @Test
    @DisplayName("emit fans out to every delegate, in list order")
    void fansOutToEveryDelegate() {
        List<String> calls = new ArrayList<>();
        CompositeEventSink composite =
                new CompositeEventSink(List.of(event -> calls.add("stream"), event -> calls.add("audit")));

        composite.emit(EVENT);

        assertThat(calls).containsExactly("stream", "audit");
    }

    @Test
    @DisplayName("a delegate that throws does not starve the sinks after it")
    void throwingDelegateDoesNotStarveOthers() {
        List<PoolEvent> audited = new ArrayList<>();
        EventSink broken = event -> {
            throw new IllegalStateException("stream is down");
        };
        CompositeEventSink composite = new CompositeEventSink(List.of(broken, audited::add));

        composite.emit(EVENT);

        assertThat(audited).containsExactly(EVENT);
    }

    @Test
    @DisplayName("an empty delegate list is rejected at construction")
    void rejectsEmptyDelegates() {
        assertThatThrownBy(() -> new CompositeEventSink(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delegates");
    }
}
