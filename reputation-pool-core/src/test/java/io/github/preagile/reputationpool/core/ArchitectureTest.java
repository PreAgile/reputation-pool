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
package io.github.preagile.reputationpool.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The design principles of this module, as executable rules — a violating import fails the build,
 * so the architecture cannot erode one convenient dependency at a time.
 *
 * <p>Every rule is an allowlist ("may depend only on"), not a denylist of known offenders: a
 * package added to core later is excluded by default instead of silently permitted.
 */
@AnalyzeClasses(
        packages = "io.github.preagile.reputationpool.core",
        importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.DoNotIncludeTestFixtures.class})
class ArchitectureTest {

    /**
     * Test fixtures ({@code SettableClock}, {@code DomainArbitraries}) are test-scoped helpers with
     * the same standing as the test sources ArchUnit already excludes: they are consumed only by
     * this module's tests and by sibling modules' tests via {@code testFixtures(project(...))}, and
     * their publication variants are skipped, so they can never reach a runtime classpath. jqwik
     * there does not breach the zero-runtime-dependency principle — the rules below guard the
     * production {@code main} sources only. {@link ImportOption.DoNotIncludeTests} matches only the
     * {@code test} output directory, so the {@code testFixtures} one needs its own exclusion.
     */
    static final class DoNotIncludeTestFixtures implements ImportOption {
        @Override
        public boolean includes(Location location) {
            // the fixtures reach the test classpath as Gradle's test-fixtures jar; the classes
            // directory form is matched too in case the classpath wiring ever changes
            return !location.contains("-test-fixtures.jar") && !location.contains("/testFixtures/");
        }
    }

    /**
     * Principle 1: core is pure Java. No Spring, Netty, JDBC, gRPC — no dependency outside the JDK
     * and this module. This is what keeps decisions pure functions, invariants property-testable,
     * and production incidents reproducible by replaying inputs; I/O belongs behind {@code port}
     * interfaces implemented in outer modules.
     *
     * <p>Deliberately {@code java..} only, not {@code javax..}: a few JDK APIs do live there
     * (crypto, TLS), but the same prefix also covers external Java EE jars (servlet, persistence,
     * annotation), so allowing it wholesale would reopen the hole this rule exists to close. If
     * core ever needs a {@code javax} JDK API, widen the list by that one package in a reviewed
     * change — a loud build failure followed by a conscious one-line widening is this gate working
     * as intended.
     */
    @ArchTest
    static final ArchRule CORE_DEPENDS_ONLY_ON_THE_JDK_AND_ITSELF = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "io.github.preagile.reputationpool.core..");

    /**
     * The dependency arrow points inward: {@code domain} is the innermost vocabulary and may
     * depend only on the JDK and itself — in particular never on the logic ({@code engine}) or the
     * I/O boundary ({@code port}) built on top of it.
     */
    @ArchTest
    static final ArchRule DOMAIN_IS_THE_INNERMOST_LAYER = classes()
            .that()
            .resideInAPackage("..core.domain..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..core.domain..");

    /**
     * {@code port} declares what the outside world must provide, in domain terms only — never the
     * decision logic in {@code engine}. Empty matches are allowed because the package arrives with
     * the concurrency layer — the rule arms itself the moment the first port interface exists.
     */
    @ArchTest
    static final ArchRule PORT_KNOWS_ONLY_THE_DOMAIN = classes()
            .that()
            .resideInAPackage("..core.port..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..core.domain..", "..core.port..")
            .allowEmptyShould(true);

    /**
     * The three rules above are direction checks — each layer's allowlist says where its arrows may
     * point. None of them can see a cycle forming <em>between</em> the top-level packages that the
     * allowlists mutually permit: {@code engine} and {@code pool} may both depend on each other's
     * package under {@code CORE_DEPENDS_ONLY_ON_THE_JDK_AND_ITSELF}, so a back-and-forth dependency
     * would pass every rule above while quietly welding two packages into one. This rule closes
     * that gap: the top-level core packages ({@code domain}, {@code engine}, {@code pool},
     * {@code port}) must form a DAG — never a dependency cycle.
     */
    @ArchTest
    static final ArchRule CORE_PACKAGES_ARE_FREE_OF_CYCLES = slices().matching(
                    "io.github.preagile.reputationpool.core.(*)..")
            .should()
            .beFreeOfCycles();
}
