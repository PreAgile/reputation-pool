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
package io.github.preagile.reputationpool.rest.dto;

/**
 * One pool event, as delivered on the {@code /v1/events} stream. The domain models these as a sealed
 * hierarchy of seven records with different fields; JSON gets one flat record with a {@code type}
 * discriminator and the union of the fields, absent ones omitted.
 *
 * <p>Flat rather than polymorphic on purpose: an SSE consumer switches on the frame's {@code event:}
 * name (which is exactly this {@code type}) and reads the two or three fields that case has. A nested
 * {@code {"resourceCooled": {...}}} shape would force every consumer to unwrap a single-key object
 * before it could read anything.
 *
 * <p>{@code permanent} exists because the core represents a block that never expires as
 * {@link java.time.Instant#MAX}. That sentinel is technically serializable as ISO-8601, but it comes out
 * as {@code +1000000000-12-31T23:59:59.999999999Z}, which most client date parsers reject outright. So
 * permanence is wire-visible structure — {@code permanent: true} with no {@code until} — the same
 * decision the proto contract made with its {@code oneof}, for the same reason.
 *
 * @param type the event type: one of {@code RESOURCE_COOLED}, {@code RESOURCE_RECOVERED},
 *     {@code RESOURCE_BLOCKLISTED}, {@code RESOURCE_UNBLOCKED}, {@code RESOURCE_LEASED},
 *     {@code LEASE_RELEASED}, {@code ACQUISITION_REJECTED}
 * @param at when the event happened, as an ISO-8601 instant; always present
 * @param resource the resource involved; absent only for {@code ACQUISITION_REJECTED}, which is about a
 *     context finding nothing to lend
 * @param context the context involved; absent for the resource-wide {@code RESOURCE_BLOCKLISTED} and
 *     {@code RESOURCE_UNBLOCKED}
 * @param until the deadline — cooldown end, lease expiry, or block expiry — as an ISO-8601 instant;
 *     absent when the event has no deadline, and absent on a permanent block
 * @param permanent {@code true} on a {@code RESOURCE_BLOCKLISTED} that never expires; absent otherwise
 * @param cause the {@code FailureType} that triggered a {@code RESOURCE_COOLED}; absent otherwise
 */
public record PoolEventDto(
        String type,
        String at,
        ResourceIdDto resource,
        String context,
        String until,
        Boolean permanent,
        String cause) {}
