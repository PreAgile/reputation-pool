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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class EventBroadcasterTest {

    private static final Instant AT = Instant.parse("2026-07-10T00:00:00Z");
    private static final ResourceId RES = new ResourceId(ResourceKind.PROXY, "p1");
    private static final Context CTX = new Context("marketplace-a");

    private static PoolEvent unblocked(String id) {
        return new PoolEvent.ResourceUnblocked(new ResourceId(ResourceKind.PROXY, id), AT);
    }

    private static String valueOf(AdvisorProto.PoolEvent event) {
        return event.getUnblocked().getResource().getValue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Runnable capturing(List<Throwable> sink, Runnable body) {
        return () -> {
            try {
                body.run();
            } catch (Throwable t) {
                sink.add(t);
            }
        };
    }

    // ---------- example-based behavior specs ----------

    @Test
    void aReadySubscriberReceivesEventsInEmitOrder() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        FakeStreamObserver subscriber = new FakeStreamObserver(true);
        broadcaster.subscribe(subscriber);

        broadcaster.emit(unblocked("a"));
        broadcaster.emit(unblocked("b"));

        assertThat(subscriber.received).hasSize(2);
        assertThat(valueOf(subscriber.received.get(0))).isEqualTo("a");
        assertThat(valueOf(subscriber.received.get(1))).isEqualTo("b");
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
        assertThat(valueOf(healthy.received.get(0))).isEqualTo("a");

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

    // ---------- property-based invariants (jqwik) ----------
    // These attack the sequential invariants — the ones that hold regardless of thread scheduling —
    // over many generated shapes. True-concurrency invariants live in the stress tests below.

    /** I4: a single ready subscriber sees events in emit order, at any length. */
    @Property
    void aReadySubscriberPreservesEmitOrderAtAnyLength(@ForAll @IntRange(min = 1, max = 60) int count) {
        EventBroadcaster broadcaster = new EventBroadcaster();
        FakeStreamObserver subscriber = new FakeStreamObserver(true);
        broadcaster.subscribe(subscriber);

        for (int i = 0; i < count; i++) {
            broadcaster.emit(unblocked("e" + i));
        }

        assertThat(subscriber.received).hasSize(count);
        for (int i = 0; i < count; i++) {
            assertThat(valueOf(subscriber.received.get(i))).isEqualTo("e" + i);
        }
        assertThat(subscriber.concurrentOnNext).isFalse();
    }

    /** I5: an overflowing subscriber is isolated — it is cut, healthy peers keep receiving — at any capacity. */
    @Property
    void anOverflowingSubscriberIsIsolatedAtAnyCapacity(
            @ForAll @IntRange(min = 1, max = 16) int capacity, @ForAll @IntRange(min = 1, max = 8) int overBy) {
        EventBroadcaster broadcaster = new EventBroadcaster(capacity);
        FakeStreamObserver stuck = new FakeStreamObserver(false);
        FakeStreamObserver healthy = new FakeStreamObserver(true);
        broadcaster.subscribe(stuck);
        broadcaster.subscribe(healthy);

        int total = capacity + overBy; // > capacity, so the stuck (never-draining) queue must overflow
        for (int i = 0; i < total; i++) {
            broadcaster.emit(unblocked("e" + i));
        }

        assertThat(stuck.error).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) stuck.error).getStatus().getCode())
                .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
        assertThat(healthy.received).hasSize(total);
        assertThat(healthy.error).isNull();
        assertThat(healthy.concurrentOnNext).isFalse();
    }

    /** I6: events buffered while not ready are all delivered, in order, once the subscriber becomes ready. */
    @Property
    void bufferedEventsAreAllDeliveredInOrderOnReady(@ForAll @IntRange(min = 1, max = 200) int count) {
        EventBroadcaster broadcaster = new EventBroadcaster(); // capacity 256 > 200, so no overflow
        FakeStreamObserver subscriber = new FakeStreamObserver(false);
        broadcaster.subscribe(subscriber);

        for (int i = 0; i < count; i++) {
            broadcaster.emit(unblocked("e" + i));
        }
        assertThat(subscriber.received).isEmpty();

        subscriber.becomeReady();

        assertThat(subscriber.received).hasSize(count);
        for (int i = 0; i < count; i++) {
            assertThat(valueOf(subscriber.received.get(i))).isEqualTo("e" + i);
        }
    }

    // ---------- multi-threaded concurrency invariants (real contention) ----------
    // The reentrancy detector in FakeStreamObserver turns "onNext called concurrently" from a symptom
    // we hope to observe into a fact caught the instant it happens: if the interleaving occurs in this
    // run, `concurrentOnNext` is set and the assertion after the join fails.

    /**
     * I1 + I3: a producer (emit) and the transport (onReady) drain the SAME subscriber concurrently.
     * {@code wip} must serialize delivery (no concurrent onNext), and the re-check loop must lose no
     * wakeup — every event is eventually delivered, in order for a single producer.
     */
    @RepeatedTest(150)
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void concurrentEmitAndOnReadyNeverDeliverConcurrentlyAndLoseNoEvents() throws InterruptedException {
        EventBroadcaster broadcaster = new EventBroadcaster(); // 256 > n, so a ready subscriber never overflows
        FakeStreamObserver subscriber = new FakeStreamObserver(true);
        broadcaster.subscribe(subscriber);

        int n = 200;
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread producer = new Thread(capturing(failures, () -> {
            awaitQuietly(start);
            for (int i = 0; i < n; i++) {
                broadcaster.emit(unblocked("e" + i));
            }
        }));
        Thread waker = new Thread(capturing(failures, () -> {
            awaitQuietly(start);
            for (int i = 0; i < n * 4; i++) {
                subscriber.becomeReady(); // fires onReady -> drain, contending with the producer's drain
            }
        }));
        producer.start();
        waker.start();
        start.countDown();
        producer.join();
        waker.join();
        subscriber.becomeReady(); // final drain flushes anything still buffered

        assertThat(failures).as("a worker thread threw").isEmpty();
        assertThat(subscriber.concurrentOnNext)
                .as("onNext must never run on two threads at once")
                .isFalse();
        assertThat(subscriber.received).as("no lost wakeup").hasSize(n);
        for (int i = 0; i < n; i++) {
            assertThat(valueOf(subscriber.received.get(i))).isEqualTo("e" + i); // FIFO for a single producer
        }
    }

    /**
     * I1: many producers emit to one ready subscriber at once. Delivery is still serialized (no
     * concurrent onNext) and no event is lost. Order across producers is undefined and not asserted.
     */
    @RepeatedTest(150)
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void concurrentEmitsFromManyThreadsNeverDeliverConcurrentlyAndLoseNoEvents() throws InterruptedException {
        int threads = 4;
        int perThread = 100;
        // Capacity large enough that a ready subscriber never overflows even in the worst interleaving.
        EventBroadcaster broadcaster = new EventBroadcaster(threads * perThread + 1);
        FakeStreamObserver subscriber = new FakeStreamObserver(true);
        broadcaster.subscribe(subscriber);

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            int base = t;
            pool.submit(capturing(failures, () -> {
                awaitQuietly(start);
                for (int i = 0; i < perThread; i++) {
                    broadcaster.emit(unblocked("t" + base + "-" + i));
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
        subscriber.becomeReady(); // flush any tail left buffered

        assertThat(failures).as("a worker thread threw").isEmpty();
        assertThat(subscriber.concurrentOnNext)
                .as("onNext must never run on two threads at once")
                .isFalse();
        assertThat(subscriber.received).as("no event lost").hasSize(threads * perThread);
        assertThat(subscriber.error).isNull();
    }

    /**
     * I7: subscribe() racing close() must never orphan a stream. Whichever order the synchronized
     * methods interleave, the late subscriber ends completed (never left open) and the pool empties.
     */
    @RepeatedTest(200)
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aSubscriberRacingCloseIsAlwaysCompletedNeverOrphaned() throws InterruptedException {
        EventBroadcaster broadcaster = new EventBroadcaster();
        FakeStreamObserver late = new FakeStreamObserver(true);

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread closer = new Thread(capturing(failures, () -> {
            awaitQuietly(start);
            broadcaster.close();
        }));
        Thread subscriber = new Thread(capturing(failures, () -> {
            awaitQuietly(start);
            broadcaster.subscribe(late);
        }));
        closer.start();
        subscriber.start();
        start.countDown();
        closer.join();
        subscriber.join();

        assertThat(failures).as("a worker thread threw").isEmpty();
        assertThat(late.completed)
                .as("a subscriber racing close is completed, not orphaned")
                .isTrue();
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    /**
     * A stand-in for gRPC's stream observer with hand-cranked flow control: {@code isReady} is a
     * switch, {@link #becomeReady()} plays the role of the transport firing the onReady handler.
     *
     * <p>It also carries a reentrancy detector: {@code onNext} claims {@code inCall} with a CAS and, if
     * a second thread is already inside, records {@code concurrentOnNext} and throws. This makes a
     * violation of the "delivery is serialized" invariant deterministically observable in a stress run
     * rather than something we hope shows up as a corrupted list.
     */
    private static final class FakeStreamObserver extends ServerCallStreamObserver<AdvisorProto.PoolEvent> {

        private final List<AdvisorProto.PoolEvent> received = new CopyOnWriteArrayList<>();
        private final AtomicBoolean inCall = new AtomicBoolean();
        private volatile boolean concurrentOnNext;
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
            if (!inCall.compareAndSet(false, true)) {
                concurrentOnNext = true;
                throw new AssertionError("onNext called concurrently by two threads");
            }
            try {
                Thread.onSpinWait(); // widen the delivery window so a real race is likelier to surface
                if (throwOnNext) {
                    throw new IllegalStateException("call already closed");
                }
                received.add(value);
            } finally {
                inCall.set(false);
            }
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
