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

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.time.Instant;

/**
 * Pure, database-free translation between a {@link PoolEvent} and its {@code audit_event} row form —
 * the audit-trail sibling of {@link SnapshotMapper}. Every method is a static function of its
 * arguments, so the discriminated mapping (the part most likely to hide a bug) is unit-testable
 * without a database. {@link PostgresAuditTrail} does the I/O; this class does the shape-shifting.
 *
 * <p>The sealed {@link PoolEvent} cases collapse into one wide row: an {@code eventType} discriminator
 * plus nullable columns for the fields not every case carries ({@code context}, {@code until},
 * {@code cause}). Both directions {@code switch} exhaustively, so adding a {@code PoolEvent} case is a
 * compile error here until the new row shape is decided.
 *
 * <p>Representation choices are inherited from the snapshot schema and reused via
 * {@link SnapshotMapper}'s helpers: instants travel as lossless epoch-nanosecond {@code bigint}, and a
 * deadline of {@link Instant#MAX} (a permanent block) travels as SQL {@code NULL}. {@code until NULL}
 * is therefore ambiguous on its own — no deadline vs. a permanent one — but never ambiguous given the
 * event type, which fixes whether the case carries a deadline at all.
 */
final class AuditEventMapper {

    private AuditEventMapper() {}

    /** The flat, column-shaped form of one {@link PoolEvent} in the {@code audit_event} table. */
    record AuditRow(
            String eventType,
            String resourceKind,
            String resourceValue,
            String context,
            long occurredAtNanos,
            Long untilNanos,
            String cause) {}

    /**
     * The row form of {@code event}: the case name becomes {@code eventType} and the fields the case
     * does not carry are {@code null}.
     */
    static AuditRow toRow(PoolEvent event) {
        return switch (event) {
            case PoolEvent.ResourceCooled e ->
                new AuditRow(
                        "RESOURCE_COOLED",
                        e.resource().kind().name(),
                        e.resource().value(),
                        e.context().value(),
                        SnapshotMapper.instantToEpochNanos(e.at()),
                        SnapshotMapper.blocklistUntilToEpochNanos(e.until()),
                        e.cause().name());
            case PoolEvent.ResourceRecovered e ->
                new AuditRow(
                        "RESOURCE_RECOVERED",
                        e.resource().kind().name(),
                        e.resource().value(),
                        e.context().value(),
                        SnapshotMapper.instantToEpochNanos(e.at()),
                        null,
                        null);
            case PoolEvent.ResourceBlocklisted e ->
                new AuditRow(
                        "RESOURCE_BLOCKLISTED",
                        e.resource().kind().name(),
                        e.resource().value(),
                        null,
                        SnapshotMapper.instantToEpochNanos(e.at()),
                        SnapshotMapper.blocklistUntilToEpochNanos(e.until()),
                        null);
            case PoolEvent.ResourceUnblocked e ->
                new AuditRow(
                        "RESOURCE_UNBLOCKED",
                        e.resource().kind().name(),
                        e.resource().value(),
                        null,
                        SnapshotMapper.instantToEpochNanos(e.at()),
                        null,
                        null);
            case PoolEvent.ResourceLeased e ->
                new AuditRow(
                        "RESOURCE_LEASED",
                        e.resource().kind().name(),
                        e.resource().value(),
                        e.context().value(),
                        SnapshotMapper.instantToEpochNanos(e.at()),
                        SnapshotMapper.blocklistUntilToEpochNanos(e.until()),
                        null);
            case PoolEvent.LeaseReleased e ->
                new AuditRow(
                        "LEASE_RELEASED",
                        e.resource().kind().name(),
                        e.resource().value(),
                        e.context().value(),
                        SnapshotMapper.instantToEpochNanos(e.at()),
                        null,
                        null);
        };
    }

    /**
     * Rebuilds a {@link PoolEvent} from its stored columns — the inverse of {@link #toRow}, used when
     * replaying the trail.
     *
     * @throws IllegalArgumentException if {@code eventType} is not a known case name
     */
    static PoolEvent toEvent(AuditRow row) {
        ResourceId resource = new ResourceId(ResourceKind.valueOf(row.resourceKind()), row.resourceValue());
        Instant at = SnapshotMapper.epochNanosToInstant(row.occurredAtNanos());
        return switch (row.eventType()) {
            case "RESOURCE_COOLED" ->
                new PoolEvent.ResourceCooled(
                        resource,
                        new Context(row.context()),
                        at,
                        SnapshotMapper.epochNanosToBlocklistUntil(row.untilNanos()),
                        FailureType.valueOf(row.cause()));
            case "RESOURCE_RECOVERED" -> new PoolEvent.ResourceRecovered(resource, new Context(row.context()), at);
            case "RESOURCE_BLOCKLISTED" ->
                new PoolEvent.ResourceBlocklisted(
                        resource, at, SnapshotMapper.epochNanosToBlocklistUntil(row.untilNanos()));
            case "RESOURCE_UNBLOCKED" -> new PoolEvent.ResourceUnblocked(resource, at);
            case "RESOURCE_LEASED" ->
                new PoolEvent.ResourceLeased(
                        resource,
                        new Context(row.context()),
                        at,
                        SnapshotMapper.epochNanosToBlocklistUntil(row.untilNanos()));
            case "LEASE_RELEASED" -> new PoolEvent.LeaseReleased(resource, new Context(row.context()), at);
            default -> throw new IllegalArgumentException("unknown audit event type: " + row.eventType());
        };
    }
}
