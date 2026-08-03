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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.pool.Lease;
import io.github.preagile.reputationpool.rest.dto.LeaseDto;
import io.github.preagile.reputationpool.rest.dto.OutcomeDto;
import io.github.preagile.reputationpool.rest.dto.PoolEventDto;
import io.github.preagile.reputationpool.rest.dto.ResourceIdDto;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the wire actually looks like, and what the boundary refuses.
 *
 * <p>The first half asserts on exact JSON text rather than on DTO fields. That is deliberate: the JSON
 * <em>is</em> the contract, so these assertions double as its documentation and any accidental change to a
 * field name, a field order, or an omitted null fails here — which a DTO-level assertion would sail past.
 *
 * <p>The second half is the rejection catalogue. Every case asserts {@link IllegalArgumentException}
 * specifically, never merely "some exception": the handler maps that type to {@code 400} and everything
 * else to {@code 500}, so a rejection arriving as a {@link NullPointerException} would blame the server for
 * the client's malformed request. The type is the contract, not an implementation detail.
 */
class RestMappingTest {

    private static final Instant AT = Instant.parse("2026-07-08T00:00:00Z");
    private static final ResourceId PROXY = new ResourceId(ResourceKind.PROXY, "10.0.0.7:8080");
    private static final Context NAVER = new Context("naver");

    @Nested
    class TheWireShape {

        @Test
        void aSuccessCarriesOnlyItsResultAndLatency() {
            String json = Json.write(RestMapping.toDto(new Outcome.Success(Duration.ofMillis(120))));

            assertThat(json).isEqualTo("{\"result\":\"SUCCESS\",\"latency\":\"PT0.12S\"}");
        }

        @Test
        void aFailureNamesItsType() {
            String json =
                    Json.write(RestMapping.toDto(new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(800))));

            assertThat(json).isEqualTo("{\"result\":\"FAILURE\",\"latency\":\"PT0.8S\",\"failureType\":\"BLOCKED\"}");
        }

        @Test
        void aLeaseCarriesItsOpaqueIdAlongsideWhatWasLeased() {
            Lease lease = new Lease(PROXY, NAVER, 1L, AT, AT.plusSeconds(30));

            LeaseDto dto = RestMapping.toDto(lease);

            // The id is opaque — asserted here as "decodes back to this lease", never as a literal, so
            // the encoding stays free to change without rewriting the surface's documentation.
            assertThat(LeaseRef.decode(dto.leaseId())).isEqualTo(new LeaseRef(PROXY, NAVER, 1L));
            assertThat(Json.write(dto))
                    .contains("\"resource\":{\"kind\":\"PROXY\",\"value\":\"10.0.0.7:8080\"}")
                    .contains("\"context\":\"naver\"")
                    .contains("\"leasedAt\":\"2026-07-08T00:00:00Z\"")
                    .contains("\"expiresAt\":\"2026-07-08T00:00:30Z\"");
        }

        @Test
        void aCooledEventCarriesItsDeadlineAndCause() {
            PoolEvent event = new PoolEvent.ResourceCooled(PROXY, NAVER, AT, AT.plusSeconds(300), FailureType.BLOCKED);

            assertThat(Json.write(RestMapping.toDto(event)))
                    .isEqualTo("{\"type\":\"RESOURCE_COOLED\",\"at\":\"2026-07-08T00:00:00Z\","
                            + "\"resource\":{\"kind\":\"PROXY\",\"value\":\"10.0.0.7:8080\"},"
                            + "\"context\":\"naver\",\"until\":\"2026-07-08T00:05:00Z\",\"cause\":\"BLOCKED\"}");
        }

        /** Inapplicable fields are omitted, not sent as null: absent is the only way to say "not here". */
        @Test
        void aRecoveredEventOmitsEveryFieldItDoesNotUse() {
            PoolEvent event = new PoolEvent.ResourceRecovered(PROXY, NAVER, AT);

            assertThat(Json.write(RestMapping.toDto(event)))
                    .isEqualTo("{\"type\":\"RESOURCE_RECOVERED\",\"at\":\"2026-07-08T00:00:00Z\","
                            + "\"resource\":{\"kind\":\"PROXY\",\"value\":\"10.0.0.7:8080\"},\"context\":\"naver\"}");
        }

        @Test
        void aRejectedAcquisitionNamesTheContextAndNoResource() {
            PoolEvent event = new PoolEvent.AcquisitionRejected(NAVER, AT);

            assertThat(Json.write(RestMapping.toDto(event)))
                    .isEqualTo("{\"type\":\"ACQUISITION_REJECTED\",\"at\":\"2026-07-08T00:00:00Z\","
                            + "\"context\":\"naver\"}");
        }

        /**
         * A block with no expiry is {@code permanent: true} and no {@code until}. Serialising the core's
         * {@code Instant.MAX} sentinel instead would emit the year 1000000000, which client date parsers
         * reject — so permanence is structure on the wire.
         */
        @Test
        void aPermanentBlockSaysSoInsteadOfSendingAFarFutureTimestamp() {
            PoolEvent event = new PoolEvent.ResourceBlocklisted(PROXY, AT, Instant.MAX);

            assertThat(Json.write(RestMapping.toDto(event)))
                    .isEqualTo("{\"type\":\"RESOURCE_BLOCKLISTED\",\"at\":\"2026-07-08T00:00:00Z\","
                            + "\"resource\":{\"kind\":\"PROXY\",\"value\":\"10.0.0.7:8080\"},\"permanent\":true}")
                    .doesNotContain("1000000000");
        }

        @Test
        void aTimedBlockCarriesItsExpiryAndNoPermanentFlag() {
            PoolEvent event = new PoolEvent.ResourceBlocklisted(PROXY, AT, AT.plusSeconds(3600));

            assertThat(Json.write(RestMapping.toDto(event)))
                    .isEqualTo("{\"type\":\"RESOURCE_BLOCKLISTED\",\"at\":\"2026-07-08T00:00:00Z\","
                            + "\"resource\":{\"kind\":\"PROXY\",\"value\":\"10.0.0.7:8080\"},"
                            + "\"until\":\"2026-07-08T01:00:00Z\"}");
        }

        /**
         * Sub-millisecond latencies are what this engine measures, so the wire format has to keep them.
         * Epoch millis would round this to nothing.
         */
        @Test
        void nanosecondPrecisionSurvivesTheWire() {
            Outcome outcome = new Outcome.Success(Duration.ofNanos(1));

            assertThat(Json.write(RestMapping.toDto(outcome))).contains("\"latency\":\"PT0.000000001S\"");
        }
    }

    @Nested
    class WhatTheBoundaryRefuses {

        @Test
        void aMissingResourceIsRejectedAsBadInputNotAsANullPointer() {
            assertThatThrownBy(() -> RestMapping.toDomain((ResourceIdDto) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resource is required");
        }

        @Test
        void aResourceMissingItsKindIsRejected() {
            assertThatThrownBy(() -> RestMapping.toDomain(new ResourceIdDto(null, "p1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resource.kind is required");
        }

        @Test
        void aResourceMissingItsValueIsRejected() {
            assertThatThrownBy(() -> RestMapping.toDomain(new ResourceIdDto("PROXY", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resource.value is required");
        }

        /** Enum names are matched exactly: one spelling per value, so two spellings can never diverge. */
        @Test
        void aLowercasedResourceKindIsRejectedRatherThanGuessedAt() {
            assertThatThrownBy(() -> RestMapping.toDomain(new ResourceIdDto("proxy", "p1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown resource kind: proxy");
        }

        @Test
        void aBlankResourceValueIsRejectedByTheDomain() {
            assertThatThrownBy(() -> RestMapping.toDomain(new ResourceIdDto("PROXY", "  ")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aMissingContextIsRejected() {
            assertThatThrownBy(() -> RestMapping.contextOf(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("context is required");
        }

        /**
         * An absent nested object is the everyday shape of a wrong body — {@code {}} instead of
         * {@code {"outcome": {...}}} — and it must not arrive as a {@code 500}. Each of the four decode
         * entry points is checked, because each has its own null guard to lose.
         */
        @Test
        void anAbsentOutcomeIsRejectedAsBadInputNotAsANullPointer() {
            assertThatThrownBy(() -> RestMapping.toDomain((OutcomeDto) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outcome is required");
        }

        @Test
        void anOutcomeWithoutItsResultIsRejected() {
            assertThatThrownBy(() -> RestMapping.toDomain(new OutcomeDto(null, "PT1S", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outcome.result is required");
        }

        @Test
        void anAbsentLeaseIsRejectedAsBadInputNotAsANullPointer() {
            assertThatThrownBy(() -> RestMapping.toDomain((LeaseDto) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lease is required");
        }

        @Test
        void anAbsentEventIsRejectedAsBadInputNotAsANullPointer() {
            assertThatThrownBy(() -> RestMapping.toDomain((PoolEventDto) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("event is required");
        }

        @Test
        void anEventWithoutItsTypeIsRejected() {
            assertThatThrownBy(() ->
                            RestMapping.toDomain(new PoolEventDto(null, AT.toString(), null, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("event.type is required");
        }

        @Test
        void anUnknownOutcomeResultIsRejected() {
            assertThatThrownBy(() -> RestMapping.toDomain(new OutcomeDto("MAYBE", "PT1S", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SUCCESS or FAILURE");
        }

        @Test
        void aFailureWithoutItsTypeIsRejected() {
            assertThatThrownBy(() -> RestMapping.toDomain(new OutcomeDto("FAILURE", "PT1S", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("failureType is required");
        }

        /**
         * A success carrying a failure type has two possible readings; ignoring the stray field would pick
         * one silently. The contract stays single-valued instead.
         */
        @Test
        void aSuccessCarryingAFailureTypeIsRejectedRatherThanHavingItIgnored() {
            assertThatThrownBy(() -> RestMapping.toDomain(new OutcomeDto("SUCCESS", "PT1S", "TIMEOUT")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be absent for a SUCCESS");
        }

        @Test
        void aMissingLatencyIsRejected() {
            assertThatThrownBy(() -> RestMapping.toDomain(new OutcomeDto("SUCCESS", null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outcome.latency is required");
        }

        @Test
        void aLatencyThatIsNotAnIso8601DurationIsRejected() {
            assertThatThrownBy(() -> RestMapping.toDomain(new OutcomeDto("SUCCESS", "120ms", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not an ISO-8601 duration");
        }

        @Test
        void aNegativeLatencyIsRejectedByTheDomain() {
            assertThatThrownBy(() -> RestMapping.toDomain(new OutcomeDto("SUCCESS", "PT-1S", null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aLeaseWithoutItsIdIsRejected() {
            assertThatThrownBy(
                            () -> RestMapping.toDomain(new LeaseDto(null, RestMapping.toDto(PROXY), "naver", "x", "y")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lease.leaseId is required");
        }

        /**
         * The id is the authority on identity. A body that disagrees with it has no correct reading, and
         * picking one silently is how a caller ends up releasing a lease it never held.
         */
        @Test
        void aLeaseWhoseBodyContradictsItsIdIsRejected() {
            String leaseId = new LeaseRef(PROXY, NAVER, 1L).encode();
            ResourceIdDto other = new ResourceIdDto("ACCOUNT", "someone-else");

            assertThatThrownBy(() -> RestMapping.toDomain(new LeaseDto(
                            leaseId,
                            other,
                            "naver",
                            AT.toString(),
                            AT.plusSeconds(30).toString())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match");
        }

        @Test
        void aLeaseWhoseContextContradictsItsIdIsRejected() {
            String leaseId = new LeaseRef(PROXY, NAVER, 1L).encode();

            assertThatThrownBy(() -> RestMapping.toDomain(new LeaseDto(
                            leaseId,
                            RestMapping.toDto(PROXY),
                            "some-other-context",
                            AT.toString(),
                            AT.plusSeconds(30).toString())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match");
        }

        @Test
        void aLeaseMissingItsTimestampsIsRejected() {
            String leaseId = new LeaseRef(PROXY, NAVER, 1L).encode();

            assertThatThrownBy(() -> RestMapping.toDomain(new LeaseDto(leaseId, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lease.leasedAt is required");
        }

        @Test
        void anUnknownEventTypeIsRejected() {
            assertThatThrownBy(() -> RestMapping.toDomain(
                            new PoolEventDto("RESOURCE_EXPLODED", AT.toString(), null, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown event type");
        }

        @Test
        void aCooledEventWithoutItsCauseIsRejected() {
            PoolEventDto dto = new PoolEventDto(
                    "RESOURCE_COOLED",
                    AT.toString(),
                    RestMapping.toDto(PROXY),
                    "naver",
                    AT.plusSeconds(60).toString(),
                    null,
                    null);

            assertThatThrownBy(() -> RestMapping.toDomain(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("event.cause is required");
        }

        /** {@code permanent} and {@code until} together say two different things about when a block ends. */
        @Test
        void aPermanentBlockCarryingAnExpiryIsRejected() {
            PoolEventDto dto = new PoolEventDto(
                    "RESOURCE_BLOCKLISTED",
                    AT.toString(),
                    RestMapping.toDto(PROXY),
                    null,
                    AT.plusSeconds(60).toString(),
                    Boolean.TRUE,
                    null);

            assertThatThrownBy(() -> RestMapping.toDomain(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be absent on a permanent block");
        }

        @Test
        void anEventWithoutItsTimestampIsRejected() {
            PoolEventDto dto =
                    new PoolEventDto("RESOURCE_UNBLOCKED", null, RestMapping.toDto(PROXY), null, null, null, null);

            assertThatThrownBy(() -> RestMapping.toDomain(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("event.at is required");
        }

        @Test
        void anEventTimestampThatIsNotAnIso8601InstantIsRejected() {
            PoolEventDto dto = new PoolEventDto(
                    "RESOURCE_UNBLOCKED", "yesterday", RestMapping.toDto(PROXY), null, null, null, null);

            assertThatThrownBy(() -> RestMapping.toDomain(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not an ISO-8601 instant");
        }
    }
}
