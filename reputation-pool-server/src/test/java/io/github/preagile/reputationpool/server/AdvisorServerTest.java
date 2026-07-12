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

import io.github.preagile.reputationpool.grpc.v1.AdvisorProto;
import io.github.preagile.reputationpool.grpc.v1.ReputationAdvisorGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One smoke test over a real port: the composition root assembles a working server, serves the
 * lease lifecycle, and shuts down in order — open event streams complete instead of resetting.
 */
class AdvisorServerTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void servesOnARealPortAndCompletesStreamsOnShutdown() throws IOException, InterruptedException {
        AdvisorServer server = AdvisorServer.create(
                        0, Clock.fixed(NOW, ZoneOffset.UTC), new Random(42), Duration.ofSeconds(30))
                .start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.port())
                .usePlaintext()
                .build();
        try {
            var blocking = ReputationAdvisorGrpc.newBlockingStub(channel);

            CountDownLatch completed = new CountDownLatch(1);
            ReputationAdvisorGrpc.newStub(channel)
                    .subscribeEvents(AdvisorProto.SubscribeEventsRequest.getDefaultInstance(), new StreamObserver<>() {
                        @Override
                        public void onNext(AdvisorProto.PoolEvent value) {}

                        @Override
                        public void onError(Throwable t) {}

                        @Override
                        public void onCompleted() {
                            completed.countDown();
                        }
                    });

            blocking.register(AdvisorProto.RegisterRequest.newBuilder()
                    .setResource(AdvisorProto.ResourceId.newBuilder()
                            .setKind(AdvisorProto.ResourceKind.PROXY)
                            .setValue("p1"))
                    .build());
            AdvisorProto.AcquireResponse acquired = blocking.acquire(AdvisorProto.AcquireRequest.newBuilder()
                    .setContext(AdvisorProto.Context.newBuilder().setValue("marketplace-a"))
                    .build());
            assertThat(acquired.getGranted()).isTrue();

            server.shutdown(Duration.ofSeconds(10));

            assertThat(completed.await(10, TimeUnit.SECONDS))
                    .as("an open event stream completes cleanly on shutdown")
                    .isTrue();
        } finally {
            channel.shutdownNow();
        }
    }
}
