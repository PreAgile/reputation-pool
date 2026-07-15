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
package io.github.preagile.reputationpool.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.testing.DomainArbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property specification for {@link AuditEventMapper}: over the whole generated space of {@link
 * PoolEvent}s — every sealed case, every {@link FailureType}, nanosecond-fraction instants, finite and
 * permanent deadlines — mapping to a row and back is the identity. This is the bijection the trail's
 * replay promise rests on; a single lossy column would make an incident irreproducible.
 */
class AuditEventMapperPropertyTest {

    @Property
    @Label("row -> event round-trip is the identity for every generated PoolEvent")
    void roundTripIsIdentity(@ForAll("poolEvents") PoolEvent event) {
        assertThat(AuditEventMapper.toEvent(AuditEventMapper.toRow(event))).isEqualTo(event);
    }

    @Provide
    Arbitrary<PoolEvent> poolEvents() {
        // shared domain generator from core's testFixtures: every sealed case, nanosecond-fraction
        // instants, and Instant.MAX exercising the permanent-block NULL path
        return DomainArbitraries.poolEvents();
    }
}
