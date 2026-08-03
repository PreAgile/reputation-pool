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
package io.github.preagile.reputationpool.rest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The boundaries of this module, as executable rules. Each one is a decision from the design discussion that
 * would otherwise erode one convenient import at a time — and each is cheap to violate accidentally, which
 * is exactly why it is a build failure rather than a review convention.
 */
@AnalyzeClasses(
        packages = "io.github.preagile.reputationpool.rest",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * The two remote surfaces are siblings, never a chain. Driving REST off the generated proto messages
     * (via {@code JsonFormat}) is the tempting shortcut: it would force protobuf-JSON on the wire —
     * {@code "latency": "3s"}, RFC3339 everywhere — and weld the contracts together, so a change to
     * {@code advisor.proto} would reshape the REST payloads of every consumer. The build declines on our
     * behalf.
     */
    @ArchTest
    static final ArchRule REST_NEVER_DEPENDS_ON_THE_GRPC_SURFACE = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.github.preagile.reputationpool.grpc..", "io.grpc..", "com.google.protobuf..");

    /**
     * An allowlist, not a denylist of known offenders: the JDK, the core it adapts, itself, and the one JSON
     * library. Anything else — a web framework, a validation library, a metrics client — is excluded by
     * default and has to be argued for in a reviewed change rather than arriving with someone's convenient
     * import.
     */
    @ArchTest
    static final ArchRule REST_DEPENDS_ONLY_ON_THE_JDK_THE_CORE_AND_ONE_JSON_LIBRARY = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "io.github.preagile.reputationpool.core..",
                    "io.github.preagile.reputationpool.rest..",
                    "com.fasterxml.jackson..");

    /**
     * The DTOs stay plain records of JDK types. No annotations, no library imports — which is what makes the
     * JSON library replaceable and keeps the wire shapes readable as the contract they are. Jackson binds
     * records by component name, so nothing is needed for it to work.
     */
    @ArchTest
    static final ArchRule THE_WIRE_SHAPES_CARRY_NO_LIBRARY_TYPES = classes()
            .that()
            .resideInAPackage("..rest.dto..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..rest.dto..");

    /**
     * JSON lives in exactly one class. Confining it means swapping the library — or dropping it for a
     * hand-rolled codec — is a one-file change, and that the rest of the module can be read and tested
     * without knowing which library is in use.
     */
    @ArchTest
    static final ArchRule THE_JSON_LIBRARY_IS_CONFINED_TO_THE_CODEC = noClasses()
            .that()
            .doNotHaveSimpleName("Json")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.fasterxml.jackson..");
}
