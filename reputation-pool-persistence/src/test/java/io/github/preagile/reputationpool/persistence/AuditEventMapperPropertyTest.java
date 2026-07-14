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

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
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
        Arbitrary<ResourceId> resources = Combinators.combine(
                        Arbitraries.of(ResourceKind.values()),
                        Arbitraries.strings()
                                .withCharRange('a', 'z')
                                .numeric()
                                .withChars(':', '.', '-', '|')
                                .ofMinLength(1)
                                .ofMaxLength(24)
                                .filter(value -> !value.isBlank()))
                .as(ResourceId::new);
        Arbitrary<Context> contexts = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(12)
                .map(Context::new);
        // Nanosecond fractions on purpose: the mapping must be lossless below the microsecond.
        Arbitrary<Instant> ats = Combinators.combine(
                        Arbitraries.longs().between(0L, 4_102_444_800L),
                        Arbitraries.integers().between(0, 999_999_999))
                .as(Instant::ofEpochSecond);
        Arbitrary<FailureType> causes = Arbitraries.of(FailureType.values());
        // A deadline at or after `at`; Instant.MAX exercises the permanent-block NULL path.
        Arbitrary<Long> untilOffsetNanos = Arbitraries.longs().between(0L, 365L * 24 * 3600 * 1_000_000_000L);

        Arbitrary<PoolEvent> cooled = Combinators.combine(resources, contexts, ats, untilOffsetNanos, causes)
                .as((resource, context, at, offset, cause) ->
                        new PoolEvent.ResourceCooled(resource, context, at, at.plusNanos(offset), cause));
        Arbitrary<PoolEvent> recovered =
                Combinators.combine(resources, contexts, ats).as(PoolEvent.ResourceRecovered::new);
        Arbitrary<PoolEvent> blocklisted = Combinators.combine(
                        resources, ats, untilOffsetNanos, Arbitraries.of(true, false))
                .as((resource, at, offset, permanent) -> new PoolEvent.ResourceBlocklisted(
                        resource, at, permanent ? Instant.MAX : at.plusNanos(offset)));
        Arbitrary<PoolEvent> unblocked = Combinators.combine(resources, ats).as(PoolEvent.ResourceUnblocked::new);
        Arbitrary<PoolEvent> leased = Combinators.combine(resources, contexts, ats, untilOffsetNanos)
                .as((resource, context, at, offset) ->
                        new PoolEvent.ResourceLeased(resource, context, at, at.plusNanos(offset)));
        Arbitrary<PoolEvent> released =
                Combinators.combine(resources, contexts, ats).as(PoolEvent.LeaseReleased::new);

        return Arbitraries.oneOf(cooled, recovered, blocklisted, unblocked, leased, released);
    }
}
