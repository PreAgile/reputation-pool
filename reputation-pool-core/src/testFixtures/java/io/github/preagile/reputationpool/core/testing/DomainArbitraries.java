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
package io.github.preagile.reputationpool.core.testing;

import io.github.preagile.reputationpool.core.domain.Blocklist;
import io.github.preagile.reputationpool.core.domain.CellKey;
import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.PoolSnapshot;
import io.github.preagile.reputationpool.core.domain.ReputationCell;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.domain.ResourceState;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * Shared jqwik generators for the domain vocabulary — the property-testing counterpart of
 * {@link SettableClock}. Any module that property-tests against core's domain (mappers, adapters,
 * proto translation) draws its values from here instead of growing a private copy of the same
 * {@code @Provide} methods.
 *
 * <p>Two rules keep the generated space honest:
 *
 * <ul>
 *   <li><b>Every value is built through the real domain constructors.</b> The constructors enforce
 *       the invariants (non-blank ids, finite scores, non-negative latencies, defensive copies), so a
 *       generated value is by construction a value the production system could hold — the generators
 *       never bypass validation to manufacture impossible states.
 *   <li><b>The awkward edges are weighted in, not left to chance.</b> Instants carry nanosecond
 *       fractions (a microsecond-capped mapping must be caught, not missed), blocklist expiries
 *       include the {@link Instant#MAX} permanent-block sentinel, cooldowns include the
 *       {@link Instant#EPOCH} "not cooling" sentinel, and outcome windows include both the empty and
 *       the full window.
 * </ul>
 */
public final class DomainArbitraries {

    /** Outcome windows are generated up to this size; the value mirrors the engine's usual cap. */
    private static final int MAX_WINDOW_SIZE = 10;

    /** Upper bound for generated epoch seconds: 2100-01-01T00:00:00Z, comfortably in the future. */
    private static final long MAX_EPOCH_SECOND = 4_102_444_800L;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private DomainArbitraries() {}

    /** Resource ids across every {@link ResourceKind}, with adapter-flavored characters (:, ., -, |). */
    public static Arbitrary<ResourceId> resourceIds() {
        return Combinators.combine(
                        Arbitraries.of(ResourceKind.values()),
                        Arbitraries.strings()
                                .withCharRange('a', 'z')
                                .numeric()
                                .withChars(':', '.', '-', '|')
                                .ofMinLength(1)
                                .ofMaxLength(24)
                                .filter(value -> !value.isBlank()))
                .as(ResourceId::new);
    }

    /** Contexts, including the reserved {@link Context#GLOBAL} axis. */
    public static Arbitrary<Context> contexts() {
        Arbitrary<Context> named = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(12)
                .map(Context::new);
        return Arbitraries.frequencyOf(Tuple.of(9, named), Tuple.of(1, Arbitraries.just(Context.GLOBAL)));
    }

    /**
     * Instants with deliberate nanosecond fractions, from {@link Instant#EPOCH} to the year 2100. A
     * mapping that silently truncates below the microsecond (e.g. a {@code timestamptz} column) fails
     * against these; a millisecond-precision generator would never notice.
     */
    public static Arbitrary<Instant> instants() {
        return Combinators.combine(
                        Arbitraries.longs().between(0L, MAX_EPOCH_SECOND),
                        Arbitraries.integers().between(0, 999_999_999))
                .as(Instant::ofEpochSecond);
    }

    /**
     * Successes and failures over every {@link FailureType}, with nanosecond-precision latencies from
     * zero to five seconds — sub-millisecond values included on purpose.
     */
    public static Arbitrary<Outcome> outcomes() {
        Arbitrary<Duration> latencies =
                Arbitraries.longs().between(0L, 5 * NANOS_PER_SECOND).map(Duration::ofNanos);
        Arbitrary<Outcome> successes = latencies.map(Outcome.Success::new);
        Arbitrary<Outcome> failures = Combinators.combine(Arbitraries.of(FailureType.values()), latencies)
                .as(Outcome.Failure::new);
        return Arbitraries.oneOf(successes, failures);
    }

    /**
     * Reputation cells over every {@link ResourceState}, with the {@link Instant#EPOCH} "not cooling"
     * sentinel weighted in and outcome windows that are empty, partial, or full.
     */
    public static Arbitrary<ReputationCell> reputationCells() {
        Arbitrary<Double> scores = Arbitraries.doubles().between(-100.0, 100.0);
        Arbitrary<Integer> streaks = Arbitraries.integers().between(0, 50);
        // empty and full windows are the edges a window-size or ordinal bug hides in
        Arbitrary<List<Outcome>> windows = Arbitraries.frequencyOf(
                Tuple.of(2, Arbitraries.just(List.<Outcome>of())),
                Tuple.of(2, outcomes().list().ofSize(MAX_WINDOW_SIZE)),
                Tuple.of(6, outcomes().list().ofMinSize(1).ofMaxSize(MAX_WINDOW_SIZE - 1)));
        Arbitrary<Instant> cooldowns = Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.just(Instant.EPOCH)), // the "not cooling" sentinel
                Tuple.of(7, instants()));
        return Combinators.combine(
                        resourceIds(),
                        contexts(),
                        scores,
                        streaks,
                        streaks,
                        windows,
                        Arbitraries.of(ResourceState.values()),
                        cooldowns)
                .as((resource, context, score, failures, successes, window, state, cooldownUntil) -> new ReputationCell(
                        resource, context, score, failures, successes, window, state, cooldownUntil, Instant.EPOCH))
                .flatMap(cell -> instants()
                        .map(updatedAt -> cell.toBuilder().updatedAt(updatedAt).build()));
    }

    /**
     * Blocklists from empty up to a handful of entries, with the {@link Instant#MAX} permanent-block
     * sentinel weighted in next to finite expiries.
     */
    public static Arbitrary<Blocklist> blocklists() {
        Arbitrary<Instant> untils = Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.just(Instant.MAX)), // the never-expires sentinel
                Tuple.of(7, instants()));
        return Arbitraries.maps(resourceIds(), untils).ofMaxSize(6).map(Blocklist::new);
    }

    /**
     * Whole-pool snapshots: cells keyed by their own {@code (resource, context)} identity — the only
     * keying a store can reproduce — plus a blocklist and a registered set, each independently empty
     * or populated.
     */
    public static Arbitrary<PoolSnapshot> poolSnapshots() {
        Arbitrary<Map<CellKey, ReputationCell>> cells = reputationCells()
                .list()
                .ofMaxSize(6)
                .map(list -> {
                    Map<CellKey, ReputationCell> byKey = new LinkedHashMap<>();
                    for (ReputationCell cell : list) {
                        byKey.putIfAbsent(new CellKey(cell.resourceId(), cell.context()), cell);
                    }
                    return byKey;
                });
        Arbitrary<Set<ResourceId>> registered = resourceIds().set().ofMaxSize(6);
        return Combinators.combine(cells, blocklists(), registered).as(PoolSnapshot::new);
    }

    /**
     * Every sealed {@link PoolEvent} case, with nanosecond-fraction instants, deadlines at or after
     * {@code at}, and {@link Instant#MAX} exercising the permanent-block path of
     * {@code ResourceBlocklisted}.
     */
    public static Arbitrary<PoolEvent> poolEvents() {
        Arbitrary<ResourceId> resources = resourceIds();
        Arbitrary<Context> contexts = contexts();
        Arbitrary<Instant> ats = instants();
        Arbitrary<FailureType> causes = Arbitraries.of(FailureType.values());
        // a deadline at or after `at`, up to a year later
        Arbitrary<Long> untilOffsetNanos = Arbitraries.longs().between(0L, 365L * 24 * 3600 * NANOS_PER_SECOND);

        Arbitrary<PoolEvent> cooled = Combinators.combine(resources, contexts, ats, untilOffsetNanos, causes)
                .as((resource, context, at, offset, cause) ->
                        new PoolEvent.ResourceCooled(resource, context, at, at.plusNanos(offset), cause));
        Arbitrary<PoolEvent> recovered =
                Combinators.combine(resources, contexts, ats).as(PoolEvent.ResourceRecovered::new);
        Arbitrary<PoolEvent> blocklisted = Combinators.combine(
                        resources, ats, untilOffsetNanos, Arbitraries.of(true, false))
                .as((resource, at, offset, permanent) -> new PoolEvent.ResourceBlocklisted(
                        resource, at, permanent ? Instant.MAX : at.plusNanos(offset)));
        Arbitrary<PoolEvent> unblocked = Combinators.combine(resources, ats).as(PoolEvent.ResourceUnblocked::new);
        Arbitrary<PoolEvent> leased = Combinators.combine(resources, contexts, ats, untilOffsetNanos)
                .as((resource, context, at, offset) ->
                        new PoolEvent.ResourceLeased(resource, context, at, at.plusNanos(offset)));
        Arbitrary<PoolEvent> released =
                Combinators.combine(resources, contexts, ats).as(PoolEvent.LeaseReleased::new);

        return Arbitraries.oneOf(cooled, recovered, blocklisted, unblocked, leased, released);
    }
}
