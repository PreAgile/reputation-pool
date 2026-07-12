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
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class EventBroadcasterTest {

    private static final Instant AT = Instant.parse("2026-07-10T00:00:00Z");
    private static final ResourceId RES = new ResourceId(ResourceKind.PROXY, "p1");
    private static final Context CTX = new Context("marketplace-a");

    private static PoolEvent unblocked(String id) {
        return new PoolEvent.ResourceUnblocked(new ResourceId(ResourceKind.PROXY, id), AT);
    }

    @Test
    void aReadySubscriberReceivesEventsInEmitOrder() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        FakeStreamObserver subscriber = new FakeStreamObserver(true);
        broadcaster.subscribe(subscriber);

        broadcaster.emit(unblocked("a"));
        broadcaster.emit(unblocked("b"));

        assertThat(subscriber.received).hasSize(2);
        assertThat(subscriber.received.get(0).getUnblocked().getResource().getValue())
                .isEqualTo("a");
        assertThat(subscriber.received.get(1).getUnblocked().getResource().getValue())
                .isEqualTo("b");
    }

    @Test
    void everySubscriberReceivesEveryEvent() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        FakeStreamObserver first = new FakeStreamObserver(true);
        FakeStreamObserver second = new FakeStreamObserver(true);
        broadcaster.subscribe(first);
        broadcaster.subscribe(second);

        broadcaster.emit(new PoolEvent.ResourceLeased(RES, CTX, AT, AT.plusSeconds(30)));

        assertThat(first.received).hasSize(1);
        assertThat(second.received).hasSize(1);
    }

    @Test
    void aNotReadySubscriberBuffersUntilOnReadyResumesTheDrain() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        FakeStreamObserver subscriber = new FakeStreamObserver(false);
        broadcaster.subscribe(subscriber);

        broadcaster.emit(unblocked("a"));
        broadcaster.emit(unblocked("b"));
        assertThat(subscriber.received).isEmpty();

        subscriber.becomeReady(); // gRPC would fire this on its transport thread

        assertThat(subscriber.received).hasSize(2);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void anOverflowingSubscriberIsCutWithResourceExhaustedAndOthersSurvive() {
        EventBroadcaster broadcaster = new EventBroadcaster(2);
        FakeStreamObserver stuck = new FakeStreamObserver(false);
        FakeStreamObserver healthy = new FakeStreamObserver(true);
        broadcaster.subscribe(stuck);
        broadcaster.subscribe(healthy);

        for (int i = 0; i < 4; i++) {
            broadcaster.emit(unblocked("e" + i)); // never blocks even though `stuck` cannot drain
        }

        assertThat(stuck.error).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) stuck.error).getStatus().getCode())
                .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
        assertThat(healthy.received).hasSize(4);
        assertThat(healthy.error).isNull();
    }

    @Test
    void aCancelledSubscriberIsRemovedAndStopsReceiving() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        FakeStreamObserver subscriber = new FakeStreamObserver(true);
        broadcaster.subscribe(subscriber);

        subscriber.cancelByClient();
        broadcaster.emit(unblocked("after-cancel"));

        assertThat(broadcaster.subscriberCount()).isZero();
        assertThat(subscriber.received).isEmpty();
    }

    @Test
    void closeFlushesTheQueueAndCompletesTheStream() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        FakeStreamObserver subscriber = new FakeStreamObserver(true);
        broadcaster.subscribe(subscriber);
        broadcaster.emit(unblocked("last"));

        broadcaster.close();

        assertThat(subscriber.received).hasSize(1);
        assertThat(subscriber.completed).isTrue();
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    @Test
    void anObserverThatThrowsOnDeliveryIsDroppedAndOthersSurvive() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        FakeStreamObserver throwing = new FakeStreamObserver(true);
        FakeStreamObserver healthy = new FakeStreamObserver(true);
        throwing.throwOnNext = true; // e.g. gRPC "call already closed" when the client cancelled mid-drain
        broadcaster.subscribe(throwing);
        broadcaster.subscribe(healthy);

        // The throwing subscriber's onNext must not escape drain() -> emit() into the pool operation.
        assertThatNoException().isThrownBy(() -> broadcaster.emit(unblocked("a")));

        assertThat(healthy.received).hasSize(1);
        assertThat(healthy.received.get(0).getUnblocked().getResource().getValue())
                .isEqualTo("a");

        // The throwing subscriber was terminated: it receives no further events even once it stops throwing.
        throwing.throwOnNext = false;
        assertThatNoException().isThrownBy(() -> broadcaster.emit(unblocked("b")));
        assertThat(throwing.received).isEmpty();
        assertThat(healthy.received).hasSize(2);
    }

    @Test
    void eventsAreMappedThroughTheWireContract() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        FakeStreamObserver subscriber = new FakeStreamObserver(true);
        broadcaster.subscribe(subscriber);

        broadcaster.emit(new PoolEvent.ResourceBlocklisted(RES, AT, Instant.MAX));

        assertThat(subscriber.received.get(0).getBlocklisted().getUntilCase())
                .isEqualTo(AdvisorProto.PoolEvent.ResourceBlocklisted.UntilCase.PERMANENT);
    }

    /**
     * A stand-in for gRPC's stream observer with hand-cranked flow control: {@code isReady} is a
     * switch, {@link #becomeReady()} plays the role of the transport firing the onReady handler.
     */
    private static final class FakeStreamObserver extends ServerCallStreamObserver<AdvisorProto.PoolEvent> {

        private final List<AdvisorProto.PoolEvent> received = new ArrayList<>();
        private volatile boolean ready;
        private volatile boolean cancelled;
        private volatile boolean completed;
        private volatile Throwable error;
        private volatile boolean throwOnNext;
        private Runnable onReady = () -> {};
        private Runnable onCancel = () -> {};

        private FakeStreamObserver(boolean ready) {
            this.ready = ready;
        }

        private void becomeReady() {
            ready = true;
            onReady.run();
        }

        private void cancelByClient() {
            cancelled = true;
            onCancel.run();
        }

        @Override
        public void onNext(AdvisorProto.PoolEvent value) {
            if (throwOnNext) {
                throw new IllegalStateException("call already closed");
            }
            received.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void setOnReadyHandler(Runnable handler) {
            onReady = handler;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setOnCancelHandler(Runnable handler) {
            onCancel = handler;
        }

        @Override
        public void setOnCloseHandler(Runnable handler) {}

        @Override
        public void setCompression(String compression) {}

        @Override
        public void setMessageCompression(boolean enable) {}

        @Override
        public void disableAutoInboundFlowControl() {}

        @Override
        public void request(int count) {}
    }
}
