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
package io.github.preagile.reputationpool.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The reputation of a single {@code (resource × context)} pair — the unit of state the engine reads
 * and produces.
 *
 * <p>It is an immutable record: the engine's decisions are pure functions that return a <em>new</em>
 * cell, and concurrency at the boundary swaps a single reference so readers never see a torn state.
 *
 * <p>Three of the fields are unbounded running values ({@code score}, {@code consecutiveFailures},
 * {@code consecutiveSuccesses}) that the engine advances over the resource's whole life — the two
 * streak counters drive the state transitions (cooling and recovery respectively) — while
 * {@code window} is a bounded slice of the most recent outcomes used for computations that need
 * actual recent history (p95 latency). The window's size cap is a policy owned by the engine; this
 * type only guarantees the stored window is a non-null immutable copy. {@code cooldownUntil} uses
 * {@link Instant#EPOCH} as the "not cooling" sentinel, so a plain time comparison
 * ({@code now.isBefore(cooldownUntil)}) is always false for a fresh cell.
 */
public record ReputationCell(
        ResourceId resourceId,
        Context context,
        double score,
        int consecutiveFailures,
        int consecutiveSuccesses,
        List<Outcome> window,
        ResourceState state,
        Instant cooldownUntil,
        Instant updatedAt) {

    /**
     * @throws NullPointerException if any field, or any element of {@code window}, is null
     * @throws IllegalArgumentException if {@code score} is not finite (NaN/Infinity), or either
     *     streak counter ({@code consecutiveFailures}, {@code consecutiveSuccesses}) is negative
     */
    public ReputationCell {
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(cooldownUntil, "cooldownUntil must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(window, "window must not be null");
        // NaN/Infinity is not out-of-policy-range but not-a-number; reject it so it can't
        // silently corrupt comparisons and ordering downstream.
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException("consecutiveFailures must not be negative");
        }
        if (consecutiveSuccesses < 0) {
            throw new IllegalArgumentException("consecutiveSuccesses must not be negative");
        }
        // defensive, immutable copy; also rejects null elements
        window = List.copyOf(window);
    }

    /** The initial cell for a resource first seen in a context: neutral, healthy, never cooled. */
    public static ReputationCell fresh(ResourceId resourceId, Context context, Instant now) {
        return new ReputationCell(resourceId, context, 0.0, 0, 0, List.of(), ResourceState.HEALTHY, Instant.EPOCH, now);
    }

    /**
     * A builder seeded with this cell's fields, for applying a transition. Identity
     * ({@code resourceId}, {@code context}) is preserved and cannot be changed — only the reputation
     * fields evolve.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Mutable builder for evolving a cell's reputation fields; produced by {@link #toBuilder()}. */
    public static final class Builder {
        private final ResourceId resourceId;
        private final Context context;
        private double score;
        private int consecutiveFailures;
        private int consecutiveSuccesses;
        private List<Outcome> window;
        private ResourceState state;
        private Instant cooldownUntil;
        private Instant updatedAt;

        private Builder(ReputationCell cell) {
            this.resourceId = cell.resourceId;
            this.context = cell.context;
            this.score = cell.score;
            this.consecutiveFailures = cell.consecutiveFailures;
            this.consecutiveSuccesses = cell.consecutiveSuccesses;
            this.window = cell.window;
            this.state = cell.state;
            this.cooldownUntil = cell.cooldownUntil;
            this.updatedAt = cell.updatedAt;
        }

        public Builder score(double score) {
            this.score = score;
            return this;
        }

        public Builder consecutiveFailures(int consecutiveFailures) {
            this.consecutiveFailures = consecutiveFailures;
            return this;
        }

        public Builder consecutiveSuccesses(int consecutiveSuccesses) {
            this.consecutiveSuccesses = consecutiveSuccesses;
            return this;
        }

        public Builder window(List<Outcome> window) {
            this.window = window;
            return this;
        }

        public Builder state(ResourceState state) {
            this.state = state;
            return this;
        }

        public Builder cooldownUntil(Instant cooldownUntil) {
            this.cooldownUntil = cooldownUntil;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public ReputationCell build() {
            return new ReputationCell(
                    resourceId,
                    context,
                    score,
                    consecutiveFailures,
                    consecutiveSuccesses,
                    window,
                    state,
                    cooldownUntil,
                    updatedAt);
        }
    }
}
