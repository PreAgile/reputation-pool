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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Property specification for {@link PostgresAuditTrail}'s constructor invariants: not just the 0/-1
 * examples, but the whole non-positive space is rejected — an invalid purge batch size can never
 * exist, whatever an operator or a refactoring feeds in. Validation fires before the writer thread
 * starts and before any connection is opened, so the property runs database-free.
 */
class PostgresAuditTrailPropertyTest {

    @Property
    @Label("every non-positive purge batch size is rejected at construction")
    void anyNonPositivePurgeBatchSizeIsRejected(@ForAll @IntRange(min = Integer.MIN_VALUE, max = 0) int batchSize) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        assertThatThrownBy(() -> new PostgresAuditTrail(dataSource, 4, batchSize))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
