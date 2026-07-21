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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.persistence.AuditEventMapper.AuditRow;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behavior specification for {@link AuditEventMapper} — the event&#8596;row translation, exercised
 * with no database so it runs in the Docker-free {@code build}. The discriminator plus nullable
 * columns is the part most likely to hide a bug: each sealed case must map to its own {@code
 * eventType} with exactly the columns it carries, and the permanent-block {@link Instant#MAX} must
 * survive the trip through SQL {@code NULL} (the {@code blocklist_entry} convention reused).
 *
 * <p>Both mapping directions are exhaustive {@code switch}es, so adding a {@link PoolEvent} case
 * breaks compilation here until the new row shape is decided — the compile-time half of this
 * specification.
 */
class AuditEventMapperTest {

    private static final ResourceId RID = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
    private static final Context CTX = new Context("marketplace-a");
    private static final Instant AT = Instant.parse("2026-07-13T00:00:00Z");
    private static final Instant UNTIL = AT.plusSeconds(3600);

    @Nested
    @DisplayName("each PoolEvent case maps to its discriminated row")
    class CaseToRow {

        @Test
        @DisplayName("ResourceCooled carries context, until, and its FailureType cause")
        void resourceCooled() {
            AuditRow row =
                    AuditEventMapper.toRow(new PoolEvent.ResourceCooled(RID, CTX, AT, UNTIL, FailureType.BLOCKED));

            assertThat(row.eventType()).isEqualTo("RESOURCE_COOLED");
            assertThat(row.resourceKind()).isEqualTo("PROXY");
            assertThat(row.resourceValue()).isEqualTo("1.2.3.4:8080");
            assertThat(row.context()).isEqualTo("marketplace-a");
            assertThat(row.untilNanos()).isEqualTo(SnapshotMapper.instantToEpochNanos(UNTIL));
            assertThat(row.cause()).isEqualTo("BLOCKED");
        }

        @Test
        @DisplayName("ResourceRecovered carries context but no until and no cause")
        void resourceRecovered() {
            AuditRow row = AuditEventMapper.toRow(new PoolEvent.ResourceRecovered(RID, CTX, AT));

            assertThat(row.eventType()).isEqualTo("RESOURCE_RECOVERED");
            assertThat(row.context()).isEqualTo("marketplace-a");
            assertThat(row.untilNanos()).isNull();
            assertThat(row.cause()).isNull();
        }

        @Test
        @DisplayName("ResourceBlocklisted is resource-global: no context, but an until")
        void resourceBlocklisted() {
            AuditRow row = AuditEventMapper.toRow(new PoolEvent.ResourceBlocklisted(RID, AT, UNTIL));

            assertThat(row.eventType()).isEqualTo("RESOURCE_BLOCKLISTED");
            assertThat(row.context()).isNull();
            assertThat(row.untilNanos()).isEqualTo(SnapshotMapper.instantToEpochNanos(UNTIL));
            assertThat(row.cause()).isNull();
        }

        @Test
        @DisplayName("ResourceUnblocked carries neither context nor until nor cause")
        void resourceUnblocked() {
            AuditRow row = AuditEventMapper.toRow(new PoolEvent.ResourceUnblocked(RID, AT));

            assertThat(row.eventType()).isEqualTo("RESOURCE_UNBLOCKED");
            assertThat(row.context()).isNull();
            assertThat(row.untilNanos()).isNull();
            assertThat(row.cause()).isNull();
        }

        @Test
        @DisplayName("ResourceLeased carries context and the lease deadline")
        void resourceLeased() {
            AuditRow row = AuditEventMapper.toRow(new PoolEvent.ResourceLeased(RID, CTX, AT, UNTIL));

            assertThat(row.eventType()).isEqualTo("RESOURCE_LEASED");
            assertThat(row.context()).isEqualTo("marketplace-a");
            assertThat(row.untilNanos()).isEqualTo(SnapshotMapper.instantToEpochNanos(UNTIL));
        }

        @Test
        @DisplayName("LeaseReleased carries context but no until")
        void leaseReleased() {
            AuditRow row = AuditEventMapper.toRow(new PoolEvent.LeaseReleased(RID, CTX, AT));

            assertThat(row.eventType()).isEqualTo("LEASE_RELEASED");
            assertThat(row.context()).isEqualTo("marketplace-a");
            assertThat(row.untilNanos()).isNull();
        }

        @Test
        @DisplayName("AcquisitionRejected carries a context but no resource, until, or cause")
        void acquisitionRejected() {
            AuditRow row = AuditEventMapper.toRow(new PoolEvent.AcquisitionRejected(CTX, AT));

            assertThat(row.eventType()).isEqualTo("ACQUISITION_REJECTED");
            assertThat(row.resourceKind()).isNull();
            assertThat(row.resourceValue()).isNull();
            assertThat(row.context()).isEqualTo("marketplace-a");
            assertThat(row.untilNanos()).isNull();
            assertThat(row.cause()).isNull();
        }
    }

    @Nested
    @DisplayName("row -> event rebuilds the original")
    class RowToEvent {

        @Test
        @DisplayName("every case round-trips to an equal event")
        void everyCaseRoundTrips() {
            PoolEvent[] all = {
                new PoolEvent.ResourceCooled(RID, CTX, AT, UNTIL, FailureType.TIMEOUT),
                new PoolEvent.ResourceRecovered(RID, CTX, AT),
                new PoolEvent.ResourceBlocklisted(RID, AT, UNTIL),
                new PoolEvent.ResourceUnblocked(RID, AT),
                new PoolEvent.ResourceLeased(RID, CTX, AT, UNTIL),
                new PoolEvent.LeaseReleased(RID, CTX, AT),
                new PoolEvent.AcquisitionRejected(CTX, AT),
            };
            for (PoolEvent event : all) {
                assertThat(AuditEventMapper.toEvent(AuditEventMapper.toRow(event)))
                        .isEqualTo(event);
            }
        }

        @Test
        @DisplayName("a permanent block (Instant.MAX) travels as NULL and comes back as Instant.MAX")
        void permanentBlockRoundTripsThroughNull() {
            PoolEvent permanent = new PoolEvent.ResourceBlocklisted(RID, AT, Instant.MAX);

            AuditRow row = AuditEventMapper.toRow(permanent);
            assertThat(row.untilNanos()).isNull();
            assertThat(AuditEventMapper.toEvent(row)).isEqualTo(permanent);
        }

        @Test
        @DisplayName("occurred_at survives to the nanosecond (epoch-nanos, not timestamptz)")
        void occurredAtIsNanosecondLossless() {
            // The 789 trailing nanos are exactly what a microsecond-capped timestamptz would lose.
            Instant precise = Instant.ofEpochSecond(1_784_000_000L, 123_456_789);
            PoolEvent event = new PoolEvent.ResourceUnblocked(RID, precise);

            assertThat(AuditEventMapper.toEvent(AuditEventMapper.toRow(event)).at())
                    .isEqualTo(precise);
        }

        @Test
        @DisplayName("an unknown event_type is rejected, not silently mis-replayed")
        void unknownEventTypeIsRejected() {
            AuditRow bogus = new AuditRow("RESOURCE_TELEPORTED", "PROXY", "1.2.3.4:8080", null, 0L, null, null);

            assertThatThrownBy(() -> AuditEventMapper.toEvent(bogus))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RESOURCE_TELEPORTED");
        }
    }
}
