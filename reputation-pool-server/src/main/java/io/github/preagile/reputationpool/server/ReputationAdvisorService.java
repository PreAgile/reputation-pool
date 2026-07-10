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

import io.github.preagile.reputationpool.core.pool.Lease;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.AcquireRequest;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.AcquireResponse;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.PoolEvent;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.RegisterRequest;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.RegisterResponse;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.ReleaseRequest;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.ReleaseResponse;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.RenewRequest;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.RenewResponse;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.ReportRequest;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.ReportResponse;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto.SubscribeEventsRequest;
import io.github.preagile.reputationpool.grpc.v1.ReputationAdvisorGrpc;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The gRPC face of the pool: each handler decodes with {@link ProtoMapping}, makes exactly one
 * {@link ResourcePool} call, and encodes the result. All decisions — who gets leased, whether a
 * renew is honored, what a failure does to reputation — already live inside the pool, so this class
 * stays wiring and never grows logic of its own.
 *
 * <p>Domain semantics map onto wire semantics by one rule: what the domain expresses as a value
 * ({@code Optional.empty()}, a losing fencing token) the wire expresses as a value
 * ({@code granted/renewed/released = false}); what the domain throws (malformed input rejected at
 * the boundary) the wire surfaces as a status ({@code INVALID_ARGUMENT}). An empty pool is a normal
 * answer, not an error.
 */
final class ReputationAdvisorService extends ReputationAdvisorGrpc.ReputationAdvisorImplBase {

    private final ResourcePool pool;
    private final EventBroadcaster broadcaster;

    ReputationAdvisorService(ResourcePool pool, EventBroadcaster broadcaster) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster must not be null");
    }

    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> observer) {
        unary(observer, () -> {
            pool.register(ProtoMapping.toDomain(request.getResource()));
            return RegisterResponse.getDefaultInstance();
        });
    }

    @Override
    public void acquire(AcquireRequest request, StreamObserver<AcquireResponse> observer) {
        unary(observer, () -> {
            Optional<Lease> lease = pool.acquire(ProtoMapping.toDomain(request.getContext()));
            return lease.map(l -> AcquireResponse.newBuilder().setGranted(true).setLease(ProtoMapping.toProto(l)))
                    .orElseGet(() -> AcquireResponse.newBuilder().setGranted(false))
                    .build();
        });
    }

    @Override
    public void report(ReportRequest request, StreamObserver<ReportResponse> observer) {
        unary(observer, () -> {
            pool.report(
                    ProtoMapping.toDomain(request.getResource()),
                    ProtoMapping.toDomain(request.getContext()),
                    ProtoMapping.toDomain(request.getOutcome()));
            return ReportResponse.getDefaultInstance();
        });
    }

    @Override
    public void renew(RenewRequest request, StreamObserver<RenewResponse> observer) {
        unary(observer, () -> {
            Optional<Lease> renewed = pool.renew(ProtoMapping.toDomain(request.getLease()));
            return renewed.map(l -> RenewResponse.newBuilder().setRenewed(true).setLease(ProtoMapping.toProto(l)))
                    .orElseGet(() -> RenewResponse.newBuilder().setRenewed(false))
                    .build();
        });
    }

    @Override
    public void release(ReleaseRequest request, StreamObserver<ReleaseResponse> observer) {
        unary(observer, () -> {
            boolean released = pool.release(ProtoMapping.toDomain(request.getLease()));
            return ReleaseResponse.newBuilder().setReleased(released).build();
        });
    }

    @Override
    public void subscribeEvents(SubscribeEventsRequest request, StreamObserver<PoolEvent> observer) {
        // Registration only — the stream stays open (no onCompleted) until the client cancels or
        // the server closes; delivery is the broadcaster's job.
        broadcaster.subscribe((ServerCallStreamObserver<PoolEvent>) observer);
    }

    /**
     * The one place wire errors are shaped: a boundary rejection (ProtoMapping or a domain
     * constructor saying the input is malformed) becomes {@code INVALID_ARGUMENT} so the client
     * knows not to retry the same request; anything else is {@code INTERNAL}.
     */
    private static <T> void unary(StreamObserver<T> observer, Supplier<T> body) {
        T response;
        try {
            response = body.get();
        } catch (IllegalArgumentException e) {
            observer.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (RuntimeException e) {
            observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        observer.onNext(response);
        observer.onCompleted();
    }
}
