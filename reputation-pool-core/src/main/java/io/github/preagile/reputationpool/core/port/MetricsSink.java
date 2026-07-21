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
package io.github.preagile.reputationpool.core.port;

/**
 * Where the pool's continuous operational measurements flow out of the core — the metrics counterpart
 * of {@link EventSink}. {@code EventSink} carries discrete facts (a resource cooled, a lease released);
 * this port carries the sampled quantities that fit a metrics backend rather than an event stream: how
 * long an acquire took, and how much of the pool is leased right now.
 *
 * <p>The core reports primitives only — nanoseconds and counts, never a {@code Duration}, a {@code
 * Timer}, or a gauge object — so it takes on no metrics library. An adapter in an outer module maps the
 * calls onto Micrometer, Prometheus, or whatever the deployment uses, exactly as {@link EventSink}
 * inverts the dependency for events.
 *
 * <p>Same threading contract as {@link EventSink}: a method runs on the thread that performed the pool
 * operation and must not block or throw back into the engine. Buffering, aggregation, and fan-out are
 * the adapter's responsibility, not the pool's.
 */
public interface MetricsSink {

    /**
     * Reports how long one {@code acquire} call took to resolve, whether or not it granted a lease.
     * Callers on the acquisition path see this as their end-to-end latency; feeding both granted and
     * rejected attempts keeps the distribution honest rather than timing only the happy path.
     *
     * @param nanos the elapsed time in nanoseconds; never negative
     */
    void acquisitionLatency(long nanos);

    /**
     * Reports the pool's lease occupancy, sampled at a lease transition (an acquire or a release): how
     * many registered resources are held by a live lease, out of how many are registered. Utilization
     * is {@code leased / registered}; the core leaves the ratio to the adapter so it commits to no
     * convention for the empty-pool ({@code registered == 0}) case.
     *
     * @param leased the number of resources currently held by a live lease; never negative
     * @param registered the number of registered resources; never negative, never below {@code leased}
     */
    void leaseOccupancy(int leased, int registered);

    /**
     * A sink that discards every measurement — the default an assembly gets when it wires no metrics
     * adapter, so adding this port breaks no existing composition.
     *
     * @return the no-op sink
     */
    static MetricsSink noop() {
        return NoOp.INSTANCE;
    }

    /** The no-op default: every measurement is dropped, letting a pool run without a metrics adapter. */
    enum NoOp implements MetricsSink {
        INSTANCE;

        @Override
        public void acquisitionLatency(long nanos) {
            // discarded: no adapter is wired
        }

        @Override
        public void leaseOccupancy(int leased, int registered) {
            // discarded: no adapter is wired
        }
    }
}
