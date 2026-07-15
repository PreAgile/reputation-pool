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
package io.github.preagile.reputationpool.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.preagile.reputationpool.core.domain.Blocklist;
import io.github.preagile.reputationpool.core.domain.CellKey;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolSnapshot;
import io.github.preagile.reputationpool.core.domain.ReputationCell;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.testing.DomainArbitraries;
import io.github.preagile.reputationpool.persistence.SnapshotMapper.OutcomeRow;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Property specification for {@link SnapshotMapper}: over the whole generated space of
 * {@link PoolSnapshot}s — nanosecond-fraction instants, the {@link Instant#MAX} permanent-block
 * sentinel, empty and full outcome windows, empty and populated snapshots — pushing every mapped
 * field through its row form and back is the identity. This is the persistence twin of
 * {@link AuditEventMapperPropertyTest}: the domain round-trip
 * ({@code ResourcePoolSnapshotTest}) already holds by property, and this closes the same
 * asymmetry at the layer where truncation and sentinel bugs would actually live. The mapper is
 * field-level on purpose (the store owns the SQL), so the property composes the fields exactly the
 * way {@link PostgresResourceStore} does when writing and reading a snapshot.
 *
 * <p>jqwik {@code Statistics} coverage checks prove the edges genuinely occur in the generated
 * space instead of trusting the generator's weighting silently.
 */
class SnapshotMapperPropertyTest {

    @Property
    @Label("snapshot -> row shape -> snapshot round-trip is the identity for every generated snapshot")
    void roundTripIsIdentityForWholeSnapshots(@ForAll("poolSnapshots") PoolSnapshot snapshot) {
        assertThat(roundTrip(snapshot)).isEqualTo(snapshot);

        collectAndProveTheEdgesOccur(snapshot);
    }

    @Property
    @Label("a blocklist expiry survives the until column: permanent <-> NULL, finite <-> epoch-nanos")
    void blocklistUntilRoundTripsThroughTheUntilColumn(@ForAll("blocklistUntils") Instant until) {
        Long stored = SnapshotMapper.blocklistUntilToEpochNanos(until);

        if (until.equals(Instant.MAX)) {
            // the permanent block does not fit an epoch-nanos bigint; NULL is its stored form
            assertThat(stored).isNull();
        } else {
            assertThat(stored).isNotNull();
        }
        assertThat(SnapshotMapper.epochNanosToBlocklistUntil(stored)).isEqualTo(until);

        Statistics.label("expiry").collect(until.equals(Instant.MAX) ? "permanent (Instant.MAX)" : "finite");
        Statistics.label("expiry").coverage(coverage -> {
            coverage.check("permanent (Instant.MAX)").count(c -> c > 0);
            coverage.check("finite").count(c -> c > 0);
        });
    }

    @Property
    @Label("an outcome survives its flat row form, whatever the case and however small the latency")
    void outcomeRoundTripsThroughItsRowForm(@ForAll("outcomes") Outcome outcome) {
        OutcomeRow row = SnapshotMapper.outcomeToRow(outcome);

        assertThat(SnapshotMapper.toOutcome(row.success(), row.failureType(), row.latencyNs()))
                .isEqualTo(outcome);

        Statistics.label("latency").collect(outcome.latency().toNanos() % 1_000_000L == 0 ? "whole ms" : "sub-ms");
        Statistics.label("latency")
                .coverage(coverage -> coverage.check("sub-ms").count(c -> c > 0));
    }

    @Property
    @Label("a nanosecond-fraction instant survives the epoch-nanos column exactly")
    void instantRoundTripsThroughEpochNanos(@ForAll("instants") Instant instant) {
        assertThat(SnapshotMapper.epochNanosToInstant(SnapshotMapper.instantToEpochNanos(instant)))
                .isEqualTo(instant);

        // timestamptz would truncate these; the epoch-nanos representation must not
        Statistics.label("precision").collect(instant.getNano() % 1_000 == 0 ? "microsecond-clean" : "sub-microsecond");
        Statistics.label("precision")
                .coverage(coverage -> coverage.check("sub-microsecond").count(c -> c > 0));
    }

    @Provide
    Arbitrary<PoolSnapshot> poolSnapshots() {
        return DomainArbitraries.poolSnapshots();
    }

    @Provide
    Arbitrary<Instant> blocklistUntils() {
        // the shared expiry generator directly: the Instant.MAX weighting stays in one place, and
        // consuming it as a primitive (not extracted from an aggregate) keeps shrinking clean
        return DomainArbitraries.blocklistUntils();
    }

    @Provide
    Arbitrary<Outcome> outcomes() {
        return DomainArbitraries.outcomes();
    }

    @Provide
    Arbitrary<Instant> instants() {
        return DomainArbitraries.instants();
    }

    /**
     * Pushes every field {@link PostgresResourceStore} maps through {@link SnapshotMapper} to its
     * row form and back, mirroring exactly what a save-then-load does to a snapshot. Fields the
     * store writes as plain columns (ids, score, streaks, state) pass through unchanged, as JDBC
     * carries them verbatim.
     */
    private static PoolSnapshot roundTrip(PoolSnapshot snapshot) {
        Map<CellKey, ReputationCell> cells = new LinkedHashMap<>();
        snapshot.cells().forEach((key, cell) -> cells.put(key, roundTripCell(cell)));
        Map<ResourceId, Instant> blocklist = new HashMap<>();
        snapshot.blocklist()
                .entries()
                .forEach((resource, until) -> blocklist.put(
                        resource,
                        SnapshotMapper.epochNanosToBlocklistUntil(SnapshotMapper.blocklistUntilToEpochNanos(until))));
        return new PoolSnapshot(cells, new Blocklist(blocklist), snapshot.registered());
    }

    private static ReputationCell roundTripCell(ReputationCell cell) {
        List<Outcome> window = cell.window().stream()
                .map(SnapshotMapper::outcomeToRow)
                .map(row -> SnapshotMapper.toOutcome(row.success(), row.failureType(), row.latencyNs()))
                .toList();
        return new ReputationCell(
                cell.resourceId(),
                cell.context(),
                cell.score(),
                cell.consecutiveFailures(),
                cell.consecutiveSuccesses(),
                window,
                cell.state(),
                SnapshotMapper.epochNanosToInstant(SnapshotMapper.instantToEpochNanos(cell.cooldownUntil())),
                SnapshotMapper.epochNanosToInstant(SnapshotMapper.instantToEpochNanos(cell.updatedAt())));
    }

    /** The edges the issue names must actually occur in the generated space, not just be possible. */
    private static void collectAndProveTheEdgesOccur(PoolSnapshot snapshot) {
        Statistics.label("cells").collect(snapshot.cells().isEmpty() ? "empty" : "populated");
        Statistics.label("cells").coverage(coverage -> {
            coverage.check("empty").count(c -> c > 0);
            coverage.check("populated").count(c -> c > 0);
        });

        boolean hasPermanentBlock = snapshot.blocklist().entries().containsValue(Instant.MAX);
        Statistics.label("blocklist")
                .collect(
                        snapshot.blocklist().entries().isEmpty()
                                ? "empty"
                                : hasPermanentBlock ? "has permanent block" : "finite expiries only");
        Statistics.label("blocklist").coverage(coverage -> {
            coverage.check("empty").count(c -> c > 0);
            coverage.check("has permanent block").count(c -> c > 0);
            coverage.check("finite expiries only").count(c -> c > 0);
        });

        boolean hasEmptyWindow = snapshot.cells().values().stream()
                .anyMatch(cell -> cell.window().isEmpty());
        boolean hasFilledWindow = snapshot.cells().values().stream()
                .anyMatch(cell -> !cell.window().isEmpty());
        if (hasEmptyWindow) {
            Statistics.label("windows").collect("some empty");
        }
        if (hasFilledWindow) {
            Statistics.label("windows").collect("some non-empty");
        }
        Statistics.label("windows").coverage(coverage -> {
            coverage.check("some empty").count(c -> c > 0);
            coverage.check("some non-empty").count(c -> c > 0);
        });
    }
}
