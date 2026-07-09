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

import com.google.protobuf.Timestamp;
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

/**
 * The anti-corruption layer between the gRPC wire types ({@code AdvisorProto.*}) and the core domain.
 * Every method is a pure, total function; the generated protobuf classes never leak past this class
 * into the core, which is what keeps the core dependency-free.
 *
 * <p>Two boundary facts show up here: a proto3 enum always carries an {@code UNSPECIFIED} zero value
 * and an {@code UNRECOGNIZED} value that the domain enums do not have, so decoding rejects them rather
 * than inventing a domain value; and a domain value that violates its own invariants (e.g. a blank id)
 * is rejected by the domain constructor, so malformed wire input fails loudly at the boundary.
 */
final class ProtoMapping {

    private ProtoMapping() {}

    // ---------- ResourceKind ----------

    static AdvisorProto.ResourceKind toProto(ResourceKind kind) {
        return switch (kind) {
            case PROXY -> AdvisorProto.ResourceKind.PROXY;
            case ACCOUNT -> AdvisorProto.ResourceKind.ACCOUNT;
            case SESSION -> AdvisorProto.ResourceKind.SESSION;
        };
    }

    static ResourceKind toDomain(AdvisorProto.ResourceKind kind) {
        return switch (kind) {
            case PROXY -> ResourceKind.PROXY;
            case ACCOUNT -> ResourceKind.ACCOUNT;
            case SESSION -> ResourceKind.SESSION;
            case RESOURCE_KIND_UNSPECIFIED, UNRECOGNIZED ->
                throw new IllegalArgumentException("resource kind is unspecified");
        };
    }

    // ---------- FailureType ----------

    static AdvisorProto.FailureType toProto(FailureType type) {
        return switch (type) {
            case CONNECTION_RESET -> AdvisorProto.FailureType.CONNECTION_RESET;
            case TLS_HANDSHAKE -> AdvisorProto.FailureType.TLS_HANDSHAKE;
            case TIMEOUT -> AdvisorProto.FailureType.TIMEOUT;
            case BLOCKED -> AdvisorProto.FailureType.BLOCKED;
            case SLOW -> AdvisorProto.FailureType.SLOW;
        };
    }

    static FailureType toDomain(AdvisorProto.FailureType type) {
        return switch (type) {
            case CONNECTION_RESET -> FailureType.CONNECTION_RESET;
            case TLS_HANDSHAKE -> FailureType.TLS_HANDSHAKE;
            case TIMEOUT -> FailureType.TIMEOUT;
            case BLOCKED -> FailureType.BLOCKED;
            case SLOW -> FailureType.SLOW;
            case FAILURE_TYPE_UNSPECIFIED, UNRECOGNIZED ->
                throw new IllegalArgumentException("failure type is unspecified");
        };
    }

    // ---------- ResourceId / Context ----------

    static AdvisorProto.ResourceId toProto(ResourceId id) {
        return AdvisorProto.ResourceId.newBuilder()
                .setKind(toProto(id.kind()))
                .setValue(id.value())
                .build();
    }

    static ResourceId toDomain(AdvisorProto.ResourceId id) {
        return new ResourceId(toDomain(id.getKind()), id.getValue());
    }

    static AdvisorProto.Context toProto(Context context) {
        return AdvisorProto.Context.newBuilder().setValue(context.value()).build();
    }

    static Context toDomain(AdvisorProto.Context context) {
        return new Context(context.getValue());
    }

    // ---------- Outcome ----------

    static AdvisorProto.Outcome toProto(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Success success ->
                AdvisorProto.Outcome.newBuilder()
                        .setSuccess(
                                AdvisorProto.Outcome.Success.newBuilder().setLatency(protoDuration(success.latency())))
                        .build();
            case Outcome.Failure failure ->
                AdvisorProto.Outcome.newBuilder()
                        .setFailure(AdvisorProto.Outcome.Failure.newBuilder()
                                .setType(toProto(failure.type()))
                                .setLatency(protoDuration(failure.latency())))
                        .build();
        };
    }

    static Outcome toDomain(AdvisorProto.Outcome outcome) {
        return switch (outcome.getKindCase()) {
            case SUCCESS -> new Outcome.Success(durationOf(outcome.getSuccess().getLatency()));
            case FAILURE ->
                new Outcome.Failure(
                        toDomain(outcome.getFailure().getType()),
                        durationOf(outcome.getFailure().getLatency()));
            case KIND_NOT_SET -> throw new IllegalArgumentException("outcome kind is not set");
        };
    }

    // ---------- Lease <-> LeaseHandle ----------

    static AdvisorProto.LeaseHandle toProto(Lease lease) {
        return AdvisorProto.LeaseHandle.newBuilder()
                .setResource(toProto(lease.resource()))
                .setContext(toProto(lease.context()))
                .setToken(lease.token())
                .setLeasedAt(protoTimestamp(lease.leasedAt()))
                .setExpiresAt(protoTimestamp(lease.expiresAt()))
                .build();
    }

    static Lease toDomain(AdvisorProto.LeaseHandle handle) {
        return new Lease(
                toDomain(handle.getResource()),
                toDomain(handle.getContext()),
                handle.getToken(),
                instantOf(handle.getLeasedAt()),
                instantOf(handle.getExpiresAt()));
    }

    // ---------- PoolEvent (outbound only: core emits, clients observe) ----------

    static AdvisorProto.PoolEvent toProto(PoolEvent event) {
        AdvisorProto.PoolEvent.Builder builder =
                AdvisorProto.PoolEvent.newBuilder().setAt(protoTimestamp(event.at()));
        return switch (event) {
            case PoolEvent.ResourceCooled cooled ->
                builder.setCooled(AdvisorProto.PoolEvent.ResourceCooled.newBuilder()
                                .setResource(toProto(cooled.resource()))
                                .setContext(toProto(cooled.context()))
                                .setUntil(protoTimestamp(cooled.until()))
                                .setCause(toProto(cooled.cause())))
                        .build();
            case PoolEvent.ResourceRecovered recovered ->
                builder.setRecovered(AdvisorProto.PoolEvent.ResourceRecovered.newBuilder()
                                .setResource(toProto(recovered.resource()))
                                .setContext(toProto(recovered.context())))
                        .build();
            case PoolEvent.ResourceBlocklisted blocklisted ->
                builder.setBlocklisted(AdvisorProto.PoolEvent.ResourceBlocklisted.newBuilder()
                                .setResource(toProto(blocklisted.resource()))
                                .setUntil(protoTimestamp(blocklisted.until())))
                        .build();
            case PoolEvent.ResourceUnblocked unblocked ->
                builder.setUnblocked(AdvisorProto.PoolEvent.ResourceUnblocked.newBuilder()
                                .setResource(toProto(unblocked.resource())))
                        .build();
            case PoolEvent.ResourceLeased leased ->
                builder.setLeased(AdvisorProto.PoolEvent.ResourceLeased.newBuilder()
                                .setResource(toProto(leased.resource()))
                                .setContext(toProto(leased.context()))
                                .setUntil(protoTimestamp(leased.until())))
                        .build();
            case PoolEvent.LeaseReleased released ->
                builder.setLeaseReleased(AdvisorProto.PoolEvent.LeaseReleased.newBuilder()
                                .setResource(toProto(released.resource()))
                                .setContext(toProto(released.context())))
                        .build();
        };
    }

    // ---------- time helpers ----------

    private static com.google.protobuf.Duration protoDuration(Duration duration) {
        return com.google.protobuf.Duration.newBuilder()
                .setSeconds(duration.getSeconds())
                .setNanos(duration.getNano())
                .build();
    }

    private static Duration durationOf(com.google.protobuf.Duration duration) {
        return Duration.ofSeconds(duration.getSeconds(), duration.getNanos());
    }

    private static Timestamp protoTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static Instant instantOf(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
