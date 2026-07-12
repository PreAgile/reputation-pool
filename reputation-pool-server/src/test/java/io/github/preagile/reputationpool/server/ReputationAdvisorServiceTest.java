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

import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.pool.WeightedRandomSelectionStrategy;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto;
import io.github.preagile.reputationpool.grpc.v1.ReputationAdvisorGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests over the in-process transport: requests ride the real gRPC wiring (marshalling,
 * status propagation, streaming) without sockets. The pool is assembled with a fixed clock and a
 * seeded random so every run is deterministic.
 */
class ReputationAdvisorServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    private Server server;
    private ManagedChannel channel;
    private ReputationAdvisorGrpc.ReputationAdvisorBlockingStub blocking;
    private ReputationAdvisorGrpc.ReputationAdvisorStub async;

    @BeforeEach
    void startInProcessServer() throws IOException {
        EventBroadcaster broadcaster = new EventBroadcaster();
        ResourcePool pool = new ResourcePool(
                new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 2, 2),
                new WeightedRandomSelectionStrategy(),
                broadcaster,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new Random(42),
                Duration.ofSeconds(30));
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new ReputationAdvisorService(pool, broadcaster))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        blocking = ReputationAdvisorGrpc.newBlockingStub(channel);
        async = ReputationAdvisorGrpc.newStub(channel);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    // ---------- the full lease lifecycle crosses the wire ----------

    @Test
    void aLeaseRoundTripsThroughAcquireReportRenewAndRelease() {
        blocking.register(registerRequest("p1"));

        AdvisorProto.AcquireResponse acquired = blocking.acquire(acquireRequest("marketplace-a"));
        assertThat(acquired.getGranted()).isTrue();
        assertThat(acquired.getLease().getResource().getValue()).isEqualTo("p1");

        blocking.report(AdvisorProto.ReportRequest.newBuilder()
                .setResource(acquired.getLease().getResource())
                .setContext(acquired.getLease().getContext())
                .setOutcome(successOutcome())
                .build());

        AdvisorProto.RenewResponse renewed = blocking.renew(AdvisorProto.RenewRequest.newBuilder()
                .setLease(acquired.getLease())
                .build());
        assertThat(renewed.getRenewed()).isTrue();
        assertThat(renewed.getLease().getToken()).isEqualTo(acquired.getLease().getToken());

        AdvisorProto.ReleaseResponse released = blocking.release(AdvisorProto.ReleaseRequest.newBuilder()
                .setLease(renewed.getLease())
                .build());
        assertThat(released.getReleased()).isTrue();
    }

    // ---------- "nothing available" and "you no longer hold it" are answers, not errors ----------

    @Test
    void anEmptyPoolAnswersGrantedFalse() {
        AdvisorProto.AcquireResponse response = blocking.acquire(acquireRequest("marketplace-a"));

        assertThat(response.getGranted()).isFalse();
        assertThat(response.hasLease()).isFalse();
    }

    @Test
    void aReleasedLeaseCannotBeRenewedOrReleasedAgain() {
        blocking.register(registerRequest("p1"));
        AdvisorProto.LeaseHandle handle =
                blocking.acquire(acquireRequest("marketplace-a")).getLease();
        blocking.release(
                AdvisorProto.ReleaseRequest.newBuilder().setLease(handle).build());

        AdvisorProto.RenewResponse renewed = blocking.renew(
                AdvisorProto.RenewRequest.newBuilder().setLease(handle).build());
        AdvisorProto.ReleaseResponse releasedAgain = blocking.release(
                AdvisorProto.ReleaseRequest.newBuilder().setLease(handle).build());

        assertThat(renewed.getRenewed()).isFalse();
        assertThat(releasedAgain.getReleased()).isFalse();
    }

    // ---------- boundary rejections surface as INVALID_ARGUMENT ----------

    @Test
    void anUnspecifiedResourceKindIsRejectedAsInvalidArgument() {
        assertThatThrownBy(() -> blocking.report(AdvisorProto.ReportRequest.newBuilder()
                        .setContext(AdvisorProto.Context.newBuilder().setValue("m"))
                        .setOutcome(successOutcome())
                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void aLeaseHandleWithoutTimestampsIsRejectedAsInvalidArgument() {
        blocking.register(registerRequest("p1"));
        AdvisorProto.LeaseHandle handle =
                blocking.acquire(acquireRequest("marketplace-a")).getLease();

        AdvisorProto.LeaseHandle noTimestamps =
                handle.toBuilder().clearLeasedAt().clearExpiresAt().build();

        assertThatThrownBy(() -> blocking.renew(AdvisorProto.RenewRequest.newBuilder()
                        .setLease(noTimestamps)
                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    // ---------- the event stream observes what the pool does ----------

    @Test
    void aSubscriberSeesLeasedAndReleasedEventsInOrder() {
        RecordingObserver events = new RecordingObserver();
        async.subscribeEvents(AdvisorProto.SubscribeEventsRequest.getDefaultInstance(), events);

        blocking.register(registerRequest("p1"));
        AdvisorProto.LeaseHandle handle =
                blocking.acquire(acquireRequest("marketplace-a")).getLease();
        blocking.release(
                AdvisorProto.ReleaseRequest.newBuilder().setLease(handle).build());

        assertThat(events.received).hasSize(2);
        assertThat(events.received.get(0).getEventCase()).isEqualTo(AdvisorProto.PoolEvent.EventCase.LEASED);
        assertThat(events.received.get(0).getLeased().getResource().getValue()).isEqualTo("p1");
        assertThat(events.received.get(1).getEventCase()).isEqualTo(AdvisorProto.PoolEvent.EventCase.LEASE_RELEASED);
    }

    @Test
    void aSubscriberJoiningLateOnlySeesSubsequentEvents() {
        blocking.register(registerRequest("p1"));
        AdvisorProto.LeaseHandle handle =
                blocking.acquire(acquireRequest("marketplace-a")).getLease();

        RecordingObserver events = new RecordingObserver();
        async.subscribeEvents(AdvisorProto.SubscribeEventsRequest.getDefaultInstance(), events);
        blocking.release(
                AdvisorProto.ReleaseRequest.newBuilder().setLease(handle).build());

        assertThat(events.received).hasSize(1);
        assertThat(events.received.get(0).getEventCase()).isEqualTo(AdvisorProto.PoolEvent.EventCase.LEASE_RELEASED);
    }

    // ---------- helpers ----------

    private static AdvisorProto.RegisterRequest registerRequest(String id) {
        return AdvisorProto.RegisterRequest.newBuilder()
                .setResource(AdvisorProto.ResourceId.newBuilder()
                        .setKind(AdvisorProto.ResourceKind.PROXY)
                        .setValue(id))
                .build();
    }

    private static AdvisorProto.AcquireRequest acquireRequest(String context) {
        return AdvisorProto.AcquireRequest.newBuilder()
                .setContext(AdvisorProto.Context.newBuilder().setValue(context))
                .build();
    }

    private static AdvisorProto.Outcome successOutcome() {
        return AdvisorProto.Outcome.newBuilder()
                .setSuccess(AdvisorProto.Outcome.Success.newBuilder()
                        .setLatency(com.google.protobuf.Duration.newBuilder().setNanos(5_000_000)))
                .build();
    }

    private static final class RecordingObserver implements StreamObserver<AdvisorProto.PoolEvent> {
        private final List<AdvisorProto.PoolEvent> received = new CopyOnWriteArrayList<>();

        @Override
        public void onNext(AdvisorProto.PoolEvent value) {
            received.add(value);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
    }
}
