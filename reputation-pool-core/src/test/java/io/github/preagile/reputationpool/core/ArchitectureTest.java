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
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The design principles of this module, as executable rules — a violating import fails the build,
 * so the architecture cannot erode one convenient dependency at a time.
 */
@AnalyzeClasses(
        packages = "io.github.preagile.reputationpool.core",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Principle 1: core is pure Java. No Spring, Netty, JDBC, gRPC — no dependency outside the JDK
     * and this module. This is what keeps decisions pure functions, invariants property-testable,
     * and production incidents reproducible by replaying inputs; I/O belongs behind {@code port}
     * interfaces implemented in outer modules.
     */
    @ArchTest
    static final ArchRule CORE_DEPENDS_ONLY_ON_THE_JDK_AND_ITSELF = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "io.github.preagile.reputationpool.core..");

    /**
     * The dependency arrow points inward: {@code domain} is the innermost vocabulary and must not
     * know the logic ({@code engine}) or the I/O boundary ({@code port}) built on top of it.
     */
    @ArchTest
    static final ArchRule DOMAIN_IS_THE_INNERMOST_LAYER = noClasses()
            .that()
            .resideInAPackage("..core.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..core.engine..", "..core.port..");

    /**
     * {@code port} declares what the outside world must provide in domain terms; it must not reach
     * into the decision logic. Empty matches are allowed because the package arrives with the
     * concurrency layer — the rule arms itself the moment the first port interface exists.
     */
    @ArchTest
    static final ArchRule PORT_KNOWS_ONLY_THE_DOMAIN = noClasses()
            .that()
            .resideInAPackage("..core.port..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..core.engine..")
            .allowEmptyShould(true);
}
