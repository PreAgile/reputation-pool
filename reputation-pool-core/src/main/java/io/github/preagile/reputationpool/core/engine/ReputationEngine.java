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
package io.github.preagile.reputationpool.core.engine;

import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ReputationCell;
import io.github.preagile.reputationpool.core.domain.ResourceState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The reputation decision, as a pure function: {@code apply(cell, outcome, now)} returns the next
 * cell plus any events, with no side effects.
 *
 * <p>The engine advances two distinct signals. <b>Score</b> is a continuous value in
 * {@code [MIN_SCORE, MAX_SCORE]} that moves on every outcome (down on failure, up on success) and is
 * later used to weight selection. <b>State</b> is the gate that decides selectability:
 *
 * <ul>
 *   <li>A failure lowers the score and increments the consecutive-failure count, but only pushes the
 *       resource into {@code COOLING} once that count reaches {@code coolAfter} — a single blip does
 *       not cool a healthy resource. While a cooldown is still active, further failures keep moving
 *       the score but never restart the cooldown or repeat the {@code ResourceCooled} event: a
 *       late-arriving result belongs to the incident already being punished, not to a new one.
 *   <li>Recovery is success-driven: once the cooldown has expired, a success moves {@code COOLING} to
 *       {@code RECOVERING} (probation), and {@code recoverAfter} consecutive successes — counted from
 *       that moment via the cell's {@code consecutiveSuccesses} streak, so successes observed while
 *       still cooling cannot shortcut probation — promote it back to {@code HEALTHY}.
 *   <li>{@code BLOCKLISTED} is terminal here: outcomes still update the score and window (evidence
 *       for a later release decision), but no state transition or event can leave it — release is an
 *       explicit act of the pool layer, never a side effect of traffic.
 * </ul>
 *
 * <p>The engine operates on a single cell and never gates selection, blocklists, or routes contexts —
 * those belong to the pool layer. Concurrency is likewise the caller's concern; this function is pure.
 */
public final class ReputationEngine {

    public static final double MIN_SCORE = -100.0;
    public static final double MAX_SCORE = 100.0;
    private static final double RECOVER_STEP = 5.0;

    private final CooldownPolicy cooldown;
    private final int windowSize;
    private final int coolAfter;
    private final int recoverAfter;

    /**
     * @param cooldown     how long a cooled resource stays excluded
     * @param windowSize   how many recent outcomes to retain
     * @param coolAfter    consecutive failures required before cooling (at least 1)
     * @param recoverAfter consecutive successes required to leave RECOVERING (at least 1)
     * @throws NullPointerException if {@code cooldown} is null
     * @throws IllegalArgumentException if any of the thresholds is below 1
     */
    public ReputationEngine(CooldownPolicy cooldown, int windowSize, int coolAfter, int recoverAfter) {
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown must not be null");
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be at least 1");
        }
        if (coolAfter < 1) {
            throw new IllegalArgumentException("coolAfter must be at least 1");
        }
        if (recoverAfter < 1) {
            throw new IllegalArgumentException("recoverAfter must be at least 1");
        }
        this.windowSize = windowSize;
        this.coolAfter = coolAfter;
        this.recoverAfter = recoverAfter;
    }

    /**
     * The next cell and any events produced; never null, events may be empty.
     */
    public record Result(ReputationCell cell, List<PoolEvent> events) {}

    /**
     * Applies one outcome to a cell. Pure: the input cell is not mutated.
     */
    public Result apply(ReputationCell cell, Outcome outcome, Instant now) {
        Objects.requireNonNull(cell, "cell must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return switch (outcome) {
            case Outcome.Success success -> onSuccess(cell, success, now);
            case Outcome.Failure failure -> onFailure(cell, failure, now);
        };
    }

    private Result onFailure(ReputationCell cell, Outcome.Failure failure, Instant now) {
        int consecutiveFailures = cell.consecutiveFailures() + 1;
        double score = clamp(cell.score() - penalty(failure.type()));
        List<Outcome> window = append(cell.window(), failure, windowSize);

        var builder = cell.toBuilder()
                .score(score)
                .consecutiveFailures(consecutiveFailures)
                .consecutiveSuccesses(0)
                .window(window)
                .updatedAt(now);

        if (shouldCool(cell, consecutiveFailures, now)) {
            Instant until = now.plus(cooldown.cooldownFor(failure.type(), consecutiveFailures));
            ReputationCell next =
                    builder.state(ResourceState.COOLING).cooldownUntil(until).build();
            return new Result(
                    next,
                    List.of(new PoolEvent.ResourceCooled(
                            cell.resourceId(), cell.context(), now, until, failure.type())));
        }
        return new Result(builder.build(), List.of());
    }

    private boolean shouldCool(ReputationCell cell, int consecutiveFailures, Instant now) {
        if (cell.state() == ResourceState.BLOCKLISTED) {
            return false; // terminal: left only by an explicit release, never by traffic
        }
        if (cell.state() == ResourceState.COOLING && now.isBefore(cell.cooldownUntil())) {
            return false; // already being punished for this incident: no extension, no repeat event
        }
        return consecutiveFailures >= coolAfter;
    }

    private Result onSuccess(ReputationCell cell, Outcome.Success success, Instant now) {
        double score = clamp(cell.score() + RECOVER_STEP);
        List<Outcome> window = append(cell.window(), success, windowSize);

        ResourceState state = cell.state();
        int streak = cell.consecutiveSuccesses() + 1;
        var events = new ArrayList<PoolEvent>();
        // BLOCKLISTED matches neither branch below, so it falls through untouched (terminal).
        if (state == ResourceState.COOLING && !now.isBefore(cell.cooldownUntil())) {
            state = ResourceState.RECOVERING;
            streak = 1; // probation starts here: successes recorded while still cooling do not count
        }
        if (state == ResourceState.RECOVERING && streak >= recoverAfter) {
            state = ResourceState.HEALTHY;
            events.add(new PoolEvent.ResourceRecovered(cell.resourceId(), cell.context(), now));
        }

        ReputationCell next = cell.toBuilder()
                .score(score)
                .consecutiveFailures(0)
                .consecutiveSuccesses(streak)
                .window(window)
                .state(state)
                .updatedAt(now)
                .build();
        return new Result(next, List.copyOf(events));
    }

    private static double penalty(FailureType type) {
        return switch (type) {
            case BLOCKED -> 30.0;
            case TLS_HANDSHAKE -> 15.0;
            case CONNECTION_RESET -> 10.0;
            case TIMEOUT -> 5.0;
            case SLOW -> 2.0;
        };
    }

    private static double clamp(double score) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    private static List<Outcome> append(List<Outcome> window, Outcome outcome, int windowSize) {
        var next = new ArrayList<>(window);
        next.add(outcome);
        while (next.size() > windowSize) {
            next.removeFirst(); // drop the oldest once the window is full
        }
        return next;
    }
}
