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
package io.github.preagile.reputationpool.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.pool.Lease;
import io.github.preagile.reputationpool.core.testing.DomainArbitraries;
import io.github.preagile.reputationpool.rest.dto.LeaseDto;
import io.github.preagile.reputationpool.rest.dto.OutcomeDto;
import io.github.preagile.reputationpool.rest.dto.PoolEventDto;
import io.github.preagile.reputationpool.rest.dto.ResourceIdDto;
import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The mapping's invariants, attacked over generated domain values rather than a handful of examples.
 *
 * <p>The central property is that {@code domain -> DTO -> domain} is the identity. It is the only check
 * that actually proves no field was dropped, defaulted, or swapped with its neighbour — a hand-written
 * example test passes just as happily when {@code leasedAt} and {@code expiresAt} are transposed, as long
 * as the example happens to use two values in that order. The generated instants carry nanosecond
 * fractions and the generated blocklist expiries include the permanent-block sentinel, so the awkward
 * edges are hit deliberately instead of by luck.
 *
 * <p>The second family goes one layer further out — {@code domain -> DTO -> JSON text -> DTO -> domain} —
 * because a mapping can be perfect while the codec silently loses a field (an unreadable name, an omitted
 * null that was meaningful). Only the full loop rules that out.
 */
class RestMappingPropertyTest {

    // ---------- domain -> DTO -> domain is the identity ----------

    @Property
    void everyResourceKindSurvivesItsName(@ForAll ResourceKind kind) {
        assertThat(RestMapping.resourceKindOf(kind.name())).isEqualTo(kind);
    }

    @Property
    void everyFailureTypeSurvivesItsName(@ForAll FailureType type) {
        assertThat(RestMapping.failureTypeOf(type.name())).isEqualTo(type);
    }

    @Property
    void everyResourceIdRoundTrips(@ForAll("resourceIds") ResourceId id) {
        assertThat(RestMapping.toDomain(RestMapping.toDto(id))).isEqualTo(id);
    }

    @Property
    void everyContextRoundTrips(@ForAll("contexts") Context context) {
        assertThat(RestMapping.contextOf(context.value())).isEqualTo(context);
    }

    @Property
    void everyOutcomeRoundTrips(@ForAll("outcomes") Outcome outcome) {
        assertThat(RestMapping.toDomain(RestMapping.toDto(outcome))).isEqualTo(outcome);
    }

    @Property
    void everyLeaseRoundTrips(@ForAll("leases") Lease lease) {
        assertThat(RestMapping.toDomain(RestMapping.toDto(lease))).isEqualTo(lease);
    }

    @Property
    void everyPoolEventRoundTrips(@ForAll("poolEvents") PoolEvent event) {
        assertThat(RestMapping.toDomain(RestMapping.toDto(event))).isEqualTo(event);
    }

    // ---------- through the JSON codec as well ----------

    @Property
    void everyResourceIdSurvivesTheJsonCodec(@ForAll("resourceIds") ResourceId id) {
        String json = Json.write(RestMapping.toDto(id));
        assertThat(RestMapping.toDomain(Json.read(json, ResourceIdDto.class))).isEqualTo(id);
    }

    @Property
    void everyOutcomeSurvivesTheJsonCodec(@ForAll("outcomes") Outcome outcome) {
        String json = Json.write(RestMapping.toDto(outcome));
        assertThat(RestMapping.toDomain(Json.read(json, OutcomeDto.class))).isEqualTo(outcome);
    }

    @Property
    void everyLeaseSurvivesTheJsonCodec(@ForAll("leases") Lease lease) {
        String json = Json.write(RestMapping.toDto(lease));
        assertThat(RestMapping.toDomain(Json.read(json, LeaseDto.class))).isEqualTo(lease);
    }

    @Property
    void everyPoolEventSurvivesTheJsonCodec(@ForAll("poolEvents") PoolEvent event) {
        String json = Json.write(RestMapping.toDto(event));
        assertThat(RestMapping.toDomain(Json.read(json, PoolEventDto.class))).isEqualTo(event);
    }

    // ---------- arbitraries ----------

    @Provide
    Arbitrary<ResourceId> resourceIds() {
        return DomainArbitraries.resourceIds();
    }

    @Provide
    Arbitrary<Context> contexts() {
        return DomainArbitraries.contexts();
    }

    @Provide
    Arbitrary<Outcome> outcomes() {
        return DomainArbitraries.outcomes();
    }

    @Provide
    Arbitrary<PoolEvent> poolEvents() {
        return DomainArbitraries.poolEvents();
    }

    /**
     * Leases are not in {@code DomainArbitraries} — {@code Lease} lives in {@code core.pool}, outside the
     * domain vocabulary the shared fixtures cover. Tokens span the whole {@code long} range on purpose:
     * the encoding writes a fixed-width long, and a sign or width bug hides at the extremes.
     */
    @Provide
    Arbitrary<Lease> leases() {
        return Combinators.combine(
                        DomainArbitraries.resourceIds(),
                        DomainArbitraries.contexts(),
                        Arbitraries.longs(),
                        DomainArbitraries.instants(),
                        DomainArbitraries.instants())
                .as((resource, context, token, first, second) -> {
                    Instant leasedAt = first.isAfter(second) ? second : first;
                    Instant expiresAt = first.isAfter(second) ? first : second;
                    return new Lease(resource, context, token, leasedAt, expiresAt);
                });
    }
}
