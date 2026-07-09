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

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.pool.Lease;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto;
import java.time.Duration;
import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class ProtoMappingTest {

    private static final Instant AT = Instant.parse("2026-07-08T00:00:00Z");

    // ---------- round-trip properties: domain -> proto -> domain is the identity ----------

    @Property
    void everyResourceKindRoundTrips(@ForAll ResourceKind kind) {
        assertThat(ProtoMapping.toDomain(ProtoMapping.toProto(kind))).isEqualTo(kind);
    }

    @Property
    void everyFailureTypeRoundTrips(@ForAll FailureType type) {
        assertThat(ProtoMapping.toDomain(ProtoMapping.toProto(type))).isEqualTo(type);
    }

    @Property
    void resourceIdRoundTrips(@ForAll("resourceIds") ResourceId id) {
        assertThat(ProtoMapping.toDomain(ProtoMapping.toProto(id))).isEqualTo(id);
    }

    @Property
    void contextRoundTrips(@ForAll("contexts") Context context) {
        assertThat(ProtoMapping.toDomain(ProtoMapping.toProto(context))).isEqualTo(context);
    }

    @Property
    void outcomeRoundTrips(@ForAll("outcomes") Outcome outcome) {
        assertThat(ProtoMapping.toDomain(ProtoMapping.toProto(outcome))).isEqualTo(outcome);
    }

    @Property
    void leaseRoundTrips(@ForAll("leases") Lease lease) {
        assertThat(ProtoMapping.toDomain(ProtoMapping.toProto(lease))).isEqualTo(lease);
    }

    // ---------- the proto3 zero/unknown values have no domain counterpart: reject, never invent ----------

    @Test
    void anUnspecifiedResourceKindIsRejected() {
        assertThatThrownBy(() -> ProtoMapping.toDomain(AdvisorProto.ResourceKind.RESOURCE_KIND_UNSPECIFIED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnspecifiedFailureTypeIsRejected() {
        assertThatThrownBy(() -> ProtoMapping.toDomain(AdvisorProto.FailureType.FAILURE_TYPE_UNSPECIFIED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOutcomeWithNoKindSetIsRejected() {
        assertThatThrownBy(() -> ProtoMapping.toDomain(AdvisorProto.Outcome.getDefaultInstance()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- PoolEvent: each sealed case maps to its oneof case (outbound only) ----------

    @Test
    void everyPoolEventKindMapsToItsOneofCase() {
        ResourceId res = new ResourceId(ResourceKind.PROXY, "p1");
        Context ctx = new Context("marketplace-a");
        Instant until = AT.plusSeconds(60);

        assertThat(ProtoMapping.toProto(new PoolEvent.ResourceCooled(res, ctx, AT, until, FailureType.BLOCKED))
                        .getEventCase())
                .isEqualTo(AdvisorProto.PoolEvent.EventCase.COOLED);
        assertThat(ProtoMapping.toProto(new PoolEvent.ResourceRecovered(res, ctx, AT))
                        .getEventCase())
                .isEqualTo(AdvisorProto.PoolEvent.EventCase.RECOVERED);
        assertThat(ProtoMapping.toProto(new PoolEvent.ResourceBlocklisted(res, AT, until))
                        .getEventCase())
                .isEqualTo(AdvisorProto.PoolEvent.EventCase.BLOCKLISTED);
        assertThat(ProtoMapping.toProto(new PoolEvent.ResourceUnblocked(res, AT))
                        .getEventCase())
                .isEqualTo(AdvisorProto.PoolEvent.EventCase.UNBLOCKED);
        assertThat(ProtoMapping.toProto(new PoolEvent.ResourceLeased(res, ctx, AT, until))
                        .getEventCase())
                .isEqualTo(AdvisorProto.PoolEvent.EventCase.LEASED);
        assertThat(ProtoMapping.toProto(new PoolEvent.LeaseReleased(res, ctx, AT))
                        .getEventCase())
                .isEqualTo(AdvisorProto.PoolEvent.EventCase.LEASE_RELEASED);
    }

    @Test
    void aCooledEventCarriesItsFieldsAcrossTheWire() {
        ResourceId res = new ResourceId(ResourceKind.ACCOUNT, "acct-7");
        Context ctx = new Context("marketplace-b");
        Instant until = AT.plusSeconds(7200);

        AdvisorProto.PoolEvent proto =
                ProtoMapping.toProto(new PoolEvent.ResourceCooled(res, ctx, AT, until, FailureType.TIMEOUT));

        assertThat(proto.getAt().getSeconds()).isEqualTo(AT.getEpochSecond());
        assertThat(ProtoMapping.toDomain(proto.getCooled().getResource())).isEqualTo(res);
        assertThat(ProtoMapping.toDomain(proto.getCooled().getContext())).isEqualTo(ctx);
        assertThat(proto.getCooled().getUntil().getSeconds()).isEqualTo(until.getEpochSecond());
        assertThat(ProtoMapping.toDomain(proto.getCooled().getCause())).isEqualTo(FailureType.TIMEOUT);
    }

    @Test
    void aPermanentBlockUntilInstantMaxSurvivesTheTimestampRoundTrip() {
        // Instant.MAX is the sentinel for a permanent block; the manual Timestamp mapping must not
        // clamp or overflow it (google.protobuf.util's checkValid would reject it — we don't use it)
        AdvisorProto.PoolEvent proto = ProtoMapping.toProto(
                new PoolEvent.ResourceBlocklisted(new ResourceId(ResourceKind.PROXY, "p1"), AT, Instant.MAX));
        var until = proto.getBlocklisted().getUntil();
        assertThat(until.getSeconds()).isEqualTo(Instant.MAX.getEpochSecond());
        assertThat(until.getNanos()).isEqualTo(Instant.MAX.getNano());
    }

    // ---------- arbitraries ----------

    @Provide
    Arbitrary<ResourceId> resourceIds() {
        return Combinators.combine(Arbitraries.of(ResourceKind.values()), nonBlank())
                .as(ResourceId::new);
    }

    @Provide
    Arbitrary<Context> contexts() {
        return nonBlank().map(Context::new);
    }

    @Provide
    Arbitrary<Outcome> outcomes() {
        Arbitrary<Outcome> successes = durations().map(latency -> new Outcome.Success(latency));
        Arbitrary<Outcome> failures = Combinators.combine(Arbitraries.of(FailureType.values()), durations())
                .as((type, latency) -> new Outcome.Failure(type, latency));
        return Arbitraries.oneOf(successes, failures);
    }

    @Provide
    Arbitrary<Lease> leases() {
        return Combinators.combine(resourceIds(), contexts(), Arbitraries.longs(), instants(), instants())
                .as((resource, context, token, a, b) -> {
                    Instant leasedAt = a.isAfter(b) ? b : a;
                    Instant expiresAt = a.isAfter(b) ? a : b;
                    return new Lease(resource, context, token, leasedAt, expiresAt);
                });
    }

    private static Arbitrary<String> nonBlank() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(24);
    }

    private static Arbitrary<Duration> durations() {
        return Combinators.combine(
                        Arbitraries.longs().between(0, 1_000_000L),
                        Arbitraries.integers().between(0, 999_999_999))
                .as((seconds, nanos) -> Duration.ofSeconds(seconds, nanos));
    }

    private static Arbitrary<Instant> instants() {
        return Combinators.combine(
                        Arbitraries.longs().between(0, 4_000_000_000L),
                        Arbitraries.integers().between(0, 999_999_999))
                .as((seconds, nanos) -> Instant.ofEpochSecond(seconds, nanos));
    }
}
