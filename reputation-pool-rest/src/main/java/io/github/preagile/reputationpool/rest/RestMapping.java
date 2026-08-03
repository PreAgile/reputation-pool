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
import java.time.format.DateTimeParseException;

/**
 * The anti-corruption layer between the REST wire shapes ({@code ...rest.dto}) and the core domain — the
 * counterpart of the gRPC module's {@code ProtoMapping}, doing the same job for a different wire format.
 *
 * <p>Every method is a pure, total function. Three boundary facts shape the code:
 *
 * <ul>
 *   <li><b>A missing field is malformed input, not a bug.</b> JSON has no required-field checking, so any
 *       DTO component can arrive null. Every one of them is checked and reported as
 *       {@link IllegalArgumentException} — never {@link NullPointerException}, because the handler maps
 *       {@code IllegalArgumentException} to {@code 400} and everything else to {@code 500}. A client that
 *       forgot a field must get a {@code 400}; letting an NPE through would blame the server for the
 *       client's mistake and hide the real one behind identical noise.
 *   <li><b>Enums and discriminators are matched exactly.</b> {@code "proxy"} is not {@code "PROXY"}.
 *       Case-insensitive or trimming parsers feel generous right up to the point where two spellings of
 *       the same value diverge in behaviour; the contract names one spelling.
 *   <li><b>Domain constructors are the final gate.</b> Blank ids, negative latencies, and deadlines
 *       before their event are rejected there, so this class does not re-implement those invariants.
 * </ul>
 *
 * <p>The mapping is bidirectional for every type, including {@link PoolEvent} — which the server only
 * ever writes. The inverse direction is what makes the round-trip property test possible ({@code domain
 * -> DTO -> domain} is the identity over thousands of generated values), and that test is the only thing
 * that actually proves no field was dropped or swapped. It is also what a replay of the audit trail onto
 * the event stream will need.
 */
final class RestMapping {

    // Event type discriminators. Also the SSE `event:` names, so the two never drift apart.
    private static final String TYPE_RESOURCE_COOLED = "RESOURCE_COOLED";
    private static final String TYPE_RESOURCE_RECOVERED = "RESOURCE_RECOVERED";
    private static final String TYPE_RESOURCE_BLOCKLISTED = "RESOURCE_BLOCKLISTED";
    private static final String TYPE_RESOURCE_UNBLOCKED = "RESOURCE_UNBLOCKED";
    private static final String TYPE_RESOURCE_LEASED = "RESOURCE_LEASED";
    private static final String TYPE_LEASE_RELEASED = "LEASE_RELEASED";
    private static final String TYPE_ACQUISITION_REJECTED = "ACQUISITION_REJECTED";

    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAILURE = "FAILURE";

    private RestMapping() {}

    // ---------- ResourceId ----------

    static ResourceIdDto toDto(ResourceId id) {
        return new ResourceIdDto(id.kind().name(), id.value());
    }

    static ResourceId toDomain(ResourceIdDto dto) {
        require(dto != null, "resource is required");
        require(dto.kind() != null, "resource.kind is required");
        require(dto.value() != null, "resource.value is required");
        return new ResourceId(resourceKindOf(dto.kind()), dto.value());
    }

    static ResourceKind resourceKindOf(String kind) {
        try {
            return ResourceKind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown resource kind: " + kind, e);
        }
    }

    static FailureType failureTypeOf(String type) {
        try {
            return FailureType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown failure type: " + type, e);
        }
    }

    // ---------- Context ----------

    /**
     * Contexts cross the wire as a bare string rather than a {@code {"value": ...}} object: the domain
     * wraps the string to give it a type, which is a JVM concern, not something a JSON client should have
     * to mirror.
     */
    static Context contextOf(String context) {
        require(context != null, "context is required");
        return new Context(context);
    }

    // ---------- Outcome ----------

    static OutcomeDto toDto(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Success success -> new OutcomeDto(RESULT_SUCCESS, text(success.latency()), null);
            case Outcome.Failure failure ->
                new OutcomeDto(
                        RESULT_FAILURE, text(failure.latency()), failure.type().name());
        };
    }

    static Outcome toDomain(OutcomeDto dto) {
        require(dto != null, "outcome is required");
        require(dto.result() != null, "outcome.result is required");
        Duration latency = durationOf(dto.latency(), "outcome.latency");
        return switch (dto.result()) {
            case RESULT_SUCCESS -> {
                // A success carrying a failure type is a contradiction. Ignoring the stray field would
                // accept two different meanings for one payload; rejecting keeps the contract single-valued.
                require(dto.failureType() == null, "outcome.failureType must be absent for a SUCCESS");
                yield new Outcome.Success(latency);
            }
            case RESULT_FAILURE -> {
                require(dto.failureType() != null, "outcome.failureType is required for a FAILURE");
                yield new Outcome.Failure(failureTypeOf(dto.failureType()), latency);
            }
            default -> throw new IllegalArgumentException("outcome.result must be SUCCESS or FAILURE");
        };
    }

    // ---------- Lease ----------

    static LeaseDto toDto(Lease lease) {
        return new LeaseDto(
                LeaseRef.of(lease).encode(),
                toDto(lease.resource()),
                lease.context().value(),
                text(lease.leasedAt()),
                text(lease.expiresAt()));
    }

    /**
     * Reads a lease back from what a client returns. {@code leaseId} is the authority for identity: the
     * resource, context, and fencing token all come from inside it. The redundant {@code resource} and
     * {@code context} fields, when present, are cross-checked and a mismatch is rejected — a request that
     * says two different things about which lease it means has no correct interpretation, and picking one
     * silently is how a caller ends up releasing a lease it did not mean to.
     */
    static Lease toDomain(LeaseDto dto) {
        require(dto != null, "lease is required");
        require(dto.leaseId() != null, "lease.leaseId is required");
        LeaseRef ref = LeaseRef.decode(dto.leaseId());
        if (dto.resource() != null) {
            require(toDomain(dto.resource()).equals(ref.resource()), "lease.resource does not match lease.leaseId");
        }
        if (dto.context() != null) {
            require(contextOf(dto.context()).equals(ref.context()), "lease.context does not match lease.leaseId");
        }
        return ref.toLease(instantOf(dto.leasedAt(), "lease.leasedAt"), instantOf(dto.expiresAt(), "lease.expiresAt"));
    }

    // ---------- PoolEvent ----------

    static PoolEventDto toDto(PoolEvent event) {
        return switch (event) {
            case PoolEvent.ResourceCooled cooled ->
                event(
                        TYPE_RESOURCE_COOLED,
                        cooled.at(),
                        cooled.resource(),
                        cooled.context(),
                        text(cooled.until()),
                        null,
                        cooled.cause().name());
            case PoolEvent.ResourceRecovered recovered ->
                event(
                        TYPE_RESOURCE_RECOVERED,
                        recovered.at(),
                        recovered.resource(),
                        recovered.context(),
                        null,
                        null,
                        null);
            case PoolEvent.ResourceBlocklisted blocklisted -> blocklistedDto(blocklisted);
            case PoolEvent.ResourceUnblocked unblocked ->
                event(TYPE_RESOURCE_UNBLOCKED, unblocked.at(), unblocked.resource(), null, null, null, null);
            case PoolEvent.ResourceLeased leased ->
                event(
                        TYPE_RESOURCE_LEASED,
                        leased.at(),
                        leased.resource(),
                        leased.context(),
                        text(leased.until()),
                        null,
                        null);
            case PoolEvent.LeaseReleased released ->
                event(TYPE_LEASE_RELEASED, released.at(), released.resource(), released.context(), null, null, null);
            case PoolEvent.AcquisitionRejected rejected ->
                event(TYPE_ACQUISITION_REJECTED, rejected.at(), null, rejected.context(), null, null, null);
        };
    }

    static PoolEvent toDomain(PoolEventDto dto) {
        require(dto != null, "event is required");
        require(dto.type() != null, "event.type is required");
        Instant at = instantOf(dto.at(), "event.at");
        return switch (dto.type()) {
            case TYPE_RESOURCE_COOLED -> {
                onlyUses(dto, Field.RESOURCE, Field.CONTEXT, Field.UNTIL, Field.CAUSE);
                yield new PoolEvent.ResourceCooled(
                        toDomain(dto.resource()),
                        contextOf(dto.context()),
                        at,
                        instantOf(dto.until(), "event.until"),
                        cause(dto));
            }
            case TYPE_RESOURCE_RECOVERED -> {
                onlyUses(dto, Field.RESOURCE, Field.CONTEXT);
                yield new PoolEvent.ResourceRecovered(toDomain(dto.resource()), contextOf(dto.context()), at);
            }
            case TYPE_RESOURCE_BLOCKLISTED -> {
                // Both until and permanent are applicable here; which of the two is required is
                // decided by until(dto), and having both at once is rejected there.
                onlyUses(dto, Field.RESOURCE, Field.UNTIL, Field.PERMANENT);
                yield new PoolEvent.ResourceBlocklisted(toDomain(dto.resource()), at, until(dto));
            }
            case TYPE_RESOURCE_UNBLOCKED -> {
                onlyUses(dto, Field.RESOURCE);
                yield new PoolEvent.ResourceUnblocked(toDomain(dto.resource()), at);
            }
            case TYPE_RESOURCE_LEASED -> {
                onlyUses(dto, Field.RESOURCE, Field.CONTEXT, Field.UNTIL);
                yield new PoolEvent.ResourceLeased(
                        toDomain(dto.resource()), contextOf(dto.context()), at, instantOf(dto.until(), "event.until"));
            }
            case TYPE_LEASE_RELEASED -> {
                onlyUses(dto, Field.RESOURCE, Field.CONTEXT);
                yield new PoolEvent.LeaseReleased(toDomain(dto.resource()), contextOf(dto.context()), at);
            }
            case TYPE_ACQUISITION_REJECTED -> {
                onlyUses(dto, Field.CONTEXT);
                yield new PoolEvent.AcquisitionRejected(contextOf(dto.context()), at);
            }
            default -> throw new IllegalArgumentException("unknown event type: " + dto.type());
        };
    }

    /**
     * The optional components of the flat event DTO. Each event type uses a few of them; naming which ones
     * is what lets {@link #onlyUses} reject the rest.
     */
    private enum Field {
        RESOURCE("event.resource"),
        CONTEXT("event.context"),
        UNTIL("event.until"),
        PERMANENT("event.permanent"),
        CAUSE("event.cause");

        private final String path;

        Field(String path) {
            this.path = path;
        }

        private Object of(PoolEventDto dto) {
            return switch (this) {
                case RESOURCE -> dto.resource();
                case CONTEXT -> dto.context();
                case UNTIL -> dto.until();
                case PERMANENT -> dto.permanent();
                case CAUSE -> dto.cause();
            };
        }
    }

    /**
     * Rejects any optional field this event type does not use — the same "one payload, one meaning" rule
     * {@link #toDomain(OutcomeDto)} applies to a success carrying a failure type.
     *
     * <p>Without it, decoding silently truncates: a {@code RESOURCE_UNBLOCKED} carrying a {@code context},
     * an {@code until}, and a {@code cause} would be accepted and three of its fields dropped, so a client
     * that sent the wrong event type would be told it succeeded. The round-trip property cannot catch this
     * — {@code toDto} never produces those combinations — which is precisely why the check has to be
     * explicit rather than assumed.
     */
    private static void onlyUses(PoolEventDto dto, Field... used) {
        for (Field field : Field.values()) {
            if (!contains(used, field)) {
                require(field.of(dto) == null, field.path + " must be absent for " + dto.type());
            }
        }
    }

    private static boolean contains(Field[] used, Field field) {
        for (Field candidate : used) {
            if (candidate == field) {
                return true;
            }
        }
        return false;
    }

    /**
     * The core says "blocked forever" with {@link Instant#MAX}; the wire says it with {@code permanent}
     * and no {@code until}. Serializing the sentinel as ISO-8601 would technically round-trip, but it
     * produces {@code +1000000000-12-31T23:59:59.999999999Z} — a value most client date parsers reject.
     */
    private static PoolEventDto blocklistedDto(PoolEvent.ResourceBlocklisted blocklisted) {
        boolean permanent = blocklisted.until().equals(Instant.MAX);
        return event(
                TYPE_RESOURCE_BLOCKLISTED,
                blocklisted.at(),
                blocklisted.resource(),
                null,
                permanent ? null : text(blocklisted.until()),
                permanent ? Boolean.TRUE : null,
                null);
    }

    /** The inverse of {@link #blocklistedDto}: {@code permanent} wins, otherwise {@code until} is required. */
    private static Instant until(PoolEventDto dto) {
        if (Boolean.TRUE.equals(dto.permanent())) {
            require(dto.until() == null, "event.until must be absent on a permanent block");
            return Instant.MAX;
        }
        return instantOf(dto.until(), "event.until");
    }

    private static FailureType cause(PoolEventDto dto) {
        require(dto.cause() != null, "event.cause is required for " + TYPE_RESOURCE_COOLED);
        return failureTypeOf(dto.cause());
    }

    private static PoolEventDto event(
            String type,
            Instant at,
            ResourceId resource,
            Context context,
            String until,
            Boolean permanent,
            String cause) {
        return new PoolEventDto(
                type,
                text(at),
                resource == null ? null : toDto(resource),
                context == null ? null : context.value(),
                until,
                permanent,
                cause);
    }

    // ---------- time ----------

    /**
     * Instants and durations travel as ISO-8601 text. {@code Instant.toString()} and
     * {@code Duration.toString()} keep full nanosecond precision and their parsers are exact inverses, so
     * no precision is lost on the wire — unlike epoch millis, which would silently truncate the
     * sub-millisecond latencies this engine measures.
     */
    private static String text(Instant instant) {
        return instant.toString();
    }

    private static String text(Duration duration) {
        return duration.toString();
    }

    private static Instant instantOf(String text, String field) {
        require(text != null, field + " is required");
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " is not an ISO-8601 instant: " + text, e);
        }
    }

    private static Duration durationOf(String text, String field) {
        require(text != null, field + " is required");
        try {
            return Duration.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " is not an ISO-8601 duration: " + text, e);
        }
    }

    /**
     * The single rejection point. It throws {@link IllegalArgumentException} rather than
     * {@link NullPointerException} on purpose — see the class javadoc: at this boundary a missing field is
     * the client's mistake, and only {@code IllegalArgumentException} is mapped to {@code 400}.
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
