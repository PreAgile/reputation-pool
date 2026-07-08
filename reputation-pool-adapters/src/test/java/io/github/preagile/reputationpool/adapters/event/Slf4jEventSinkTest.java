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
package io.github.preagile.reputationpool.adapters.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class Slf4jEventSinkTest {

    private static final ResourceId RID = new ResourceId(ResourceKind.PROXY, "gw:8080");
    private static final Context CTX = new Context("cpeats");
    private static final Instant AT = Instant.parse("2026-07-08T00:00:00Z");

    @Test
    void formatsEveryEventKind() {
        assertThat(Slf4jEventSink.format(
                        new PoolEvent.ResourceCooled(RID, CTX, AT, AT.plusSeconds(60), FailureType.BLOCKED)))
                .startsWith("cooled")
                .contains("BLOCKED");
        assertThat(Slf4jEventSink.format(new PoolEvent.ResourceRecovered(RID, CTX, AT)))
                .startsWith("recovered");
        assertThat(Slf4jEventSink.format(new PoolEvent.ResourceBlocklisted(RID, AT, AT.plusSeconds(60))))
                .startsWith("blocklisted");
        assertThat(Slf4jEventSink.format(new PoolEvent.ResourceUnblocked(RID, AT)))
                .startsWith("unblocked");
        assertThat(Slf4jEventSink.format(new PoolEvent.ResourceLeased(RID, CTX, AT, AT.plusSeconds(60))))
                .startsWith("leased");
        assertThat(Slf4jEventSink.format(new PoolEvent.LeaseReleased(RID, CTX, AT)))
                .startsWith("released lease");
    }

    @Test
    void emitDoesNotThrow() {
        var sink = new Slf4jEventSink();
        assertThatCode(() -> sink.emit(new PoolEvent.ResourceRecovered(RID, CTX, AT)))
                .doesNotThrowAnyException();
    }
}
