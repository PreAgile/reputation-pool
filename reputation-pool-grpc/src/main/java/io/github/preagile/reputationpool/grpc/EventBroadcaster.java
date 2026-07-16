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
package io.github.preagile.reputationpool.grpc;

import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.port.EventSink;
import io.github.preagile.reputationpool.grpc.v1.AdvisorProto;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@link EventSink} implementation that fans pool events out to {@code SubscribeEvents} streams.
 *
 * <p>{@link EventSink} promises the core that {@link #emit} never blocks: it runs on whatever thread
 * performed the pool operation, so a slow subscriber must never hold an {@code acquire} hostage. The
 * isolation mechanism is a bounded queue per subscriber — {@code emit} only ever {@code offer}s, and
 * a subscriber whose queue overflows is cut with {@code RESOURCE_EXHAUSTED} rather than slowing the
 * pool or growing memory without bound. Events are observations; a consumer that cannot keep up loses
 * its stream, not the server.
 *
 * <p>Delivery is a relay between two kinds of thread. When the client's transport has room
 * ({@code isReady()}), the emitting thread drains the queue on the spot — {@code onNext} is
 * non-blocking, it hands the message to gRPC's outbound buffer. When the client falls behind, the
 * drain stops and the events wait in the queue until gRPC signals readiness again via
 * {@code onReadyHandler}, on its own transport thread. The {@code wip} flag serializes the two so
 * {@code onNext} is never called concurrently (stream observers are not thread-safe).
 */
public final class EventBroadcaster implements EventSink {

    static final int DEFAULT_QUEUE_CAPACITY = 256;

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final int queueCapacity;
    // Guarded by this monitor (subscribe/close are synchronized). emit() stays lock-free and
    // never reads this: after close() the subscriber list is empty, so a late emit is a no-op.
    private boolean closed;

    public EventBroadcaster() {
        this(DEFAULT_QUEUE_CAPACITY);
    }

    EventBroadcaster(int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.queueCapacity = queueCapacity;
    }

    /** Registers a stream; the observer stays open until the client cancels or the server closes. */
    synchronized void subscribe(ServerCallStreamObserver<AdvisorProto.PoolEvent> observer) {
        Subscriber subscriber = new Subscriber(observer, queueCapacity);
        if (closed) {
            // Subscribed during/after shutdown: complete immediately so the stream ends cleanly
            // instead of being left open and reset. Mutual exclusion with close() closes the race.
            subscriber.complete();
            return;
        }
        observer.setOnReadyHandler(subscriber::drain);
        observer.setOnCancelHandler(() -> subscribers.remove(subscriber));
        subscribers.add(subscriber);
    }

    @Override
    public void emit(PoolEvent event) {
        if (subscribers.isEmpty()) {
            return;
        }
        // Mapped once and shared: protobuf messages are immutable.
        AdvisorProto.PoolEvent proto = ProtoMapping.toProto(event);
        for (Subscriber subscriber : subscribers) {
            if (subscriber.queue.offer(proto)) {
                subscriber.drain();
            } else {
                subscribers.remove(subscriber);
                subscriber.terminate(
                        Status.RESOURCE_EXHAUSTED.withDescription("subscriber fell behind: event queue overflowed"));
            }
        }
    }

    /** Completes every open stream; invoked on host shutdown so clients see an orderly end. */
    public synchronized void close() {
        closed = true;
        // Clear once rather than remove-per-element: subscribers is a CopyOnWriteArrayList, so an
        // in-loop remove copies the whole backing array each time (O(n^2)). Complete over the
        // snapshot, then drop them all in a single copy.
        for (Subscriber subscriber : subscribers) {
            subscriber.complete();
        }
        subscribers.clear();
    }

    /** The number of currently subscribed streams. Exposed for host wiring tests. */
    public int subscriberCount() {
        return subscribers.size();
    }

    private static final class Subscriber {

        private final ServerCallStreamObserver<AdvisorProto.PoolEvent> observer;
        private final BlockingQueue<AdvisorProto.PoolEvent> queue;
        private final AtomicBoolean wip = new AtomicBoolean();
        // Set at most once, before the terminal drain; volatile is enough — drain serializes the send.
        private volatile Status terminalStatus;
        private volatile boolean completeRequested;
        // Only read and written while holding wip, so no volatile needed.
        private boolean terminated;

        private Subscriber(ServerCallStreamObserver<AdvisorProto.PoolEvent> observer, int capacity) {
            this.observer = observer;
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        private void terminate(Status status) {
            terminalStatus = status;
            drain();
        }

        private void complete() {
            completeRequested = true;
            drain();
        }

        /**
         * Moves events from the queue to the stream. Loops so that a signal arriving while another
         * thread holds {@code wip} is not lost: the holder re-checks for work after releasing.
         */
        private void drain() {
            while (wip.compareAndSet(false, true)) {
                try {
                    if (terminated) {
                        return;
                    }
                    if (terminalStatus != null) {
                        sendTerminal(() -> observer.onError(terminalStatus.asRuntimeException()));
                        return;
                    }
                    AdvisorProto.PoolEvent next;
                    while (observer.isReady() && (next = queue.poll()) != null) {
                        try {
                            observer.onNext(next);
                        } catch (RuntimeException e) {
                            // Client cancelled or transport closed mid-drain; terminate this
                            // subscriber silently so the exception never escapes drain() -> emit()
                            // into an otherwise-successful pool operation.
                            terminated = true;
                            return;
                        }
                    }
                    if (completeRequested && queue.isEmpty()) {
                        sendTerminal(observer::onCompleted);
                        return;
                    }
                } finally {
                    wip.set(false);
                }
                boolean moreWork = terminalStatus != null
                        || (completeRequested && queue.isEmpty())
                        || (!queue.isEmpty() && observer.isReady());
                if (!moreWork) {
                    return;
                }
            }
        }

        private void sendTerminal(Runnable send) {
            terminated = true;
            try {
                if (!observer.isCancelled()) {
                    send.run();
                }
            } catch (RuntimeException ignored) {
                // The stream may already be dead; terminating a dead stream is a no-op.
            }
        }
    }
}
