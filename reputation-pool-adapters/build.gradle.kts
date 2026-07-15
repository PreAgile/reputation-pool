plugins {
    `java-library`
    id("com.diffplug.spotless")
    // On-demand mutation testing via `./gradlew :reputation-pool-adapters:pitest`. Same 1.19.0 as core;
    // deliberately NOT wired into `build` or the PR gate.
    id("info.solidsoft.pitest") version "1.19.0"
}

java {
    toolchain {
        // Same JDK 25 toolchain as core; auto-provisioned by the Foojay resolver.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Demo adapters depend inward on the pure core; the dependency arrow never points the other way.
    api(project(":reputation-pool-core"))
    // Adapters may use frameworks (core may not). SLF4J is the only runtime dependency the demo needs.
    implementation("org.slf4j:slf4j-api:2.0.18")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Shared test helpers from core (SettableClock) instead of per-module copies.
    testImplementation(testFixtures(project(":reputation-pool-core")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    // Property tests for the classifiers' totality contract (same version as the other modules).
    // Test scope only: the module's runtime dependency set stays SLF4J-only.
    testImplementation("net.jqwik:jqwik:1.10.1")
    // WireMock drives the integration test: a real HTTP endpoint the adapter probes over java.net.http.
    // Used programmatically (WireMockServer), not via its JUnit extension, to stay off the JUnit version.
    testImplementation("org.wiremock:wiremock:3.13.2")
    // A no-op SLF4J provider so the logging EventSink under test prints nothing and no NOP warning fires.
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.18")

    // Teaches PIT to drive the JUnit Platform (Jupiter + jqwik), same version as core.
    pitest("org.pitest:pitest-junit5-plugin:1.2.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

// Mutation testing is an on-demand quality probe (`./gradlew :reputation-pool-adapters:pitest`), never
// part of `build`/CI. It targets ONLY the pure HTTP-outcome classifiers — the branch-heavy transform
// code a totality suite can silently under-test. The probing/wiring (HttpProxyEndpoint, AccountProbe,
// Slf4jEventSink) is deliberately excluded: its mutants cannot be killed without a live HTTP endpoint
// (WireMock), so they would be noise, not signal. `targetTests` is pinned to the classifier tests.
// `maxSurviving` is a no-regression ratchet, not a percentage: the task fails when survivors exceed the
// recorded baseline. Tightening it is a manual PR edit.
pitest {
    pitestVersion = "1.25.5"
    junit5PluginVersion = "1.2.3"
    targetClasses =
        setOf(
            "io.github.preagile.reputationpool.adapters.proxy.HttpProxyOutcomeClassifier",
            "io.github.preagile.reputationpool.adapters.account.HttpAccountOutcomeClassifier")
    targetTests =
        setOf(
            "io.github.preagile.reputationpool.adapters.proxy.HttpProxyOutcomeClassifier*Test",
            "io.github.preagile.reputationpool.adapters.account.HttpAccountOutcomeClassifier*Test")
    threads = 4
    timestampedReports = false
    // Measured baseline: 8-9 surviving mutants (killed 41-42 of 50). The count jitters by one because
    // the jqwik totality properties draw a random seed each run: the `statusCode < 300` boundary mutant
    // is killed only when a run happens to generate the exact edge (status 300), so it sometimes
    // survives. The cap is set at the observed ceiling (9) so generator jitter never trips a false
    // regression; a real gap adds always-surviving mutants that push past it. Tighten only.
    maxSurviving = 9
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

// Make Javadoc part of the build gate, as in core, so a broken doc reference fails the build.
tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}

spotless {
    java {
        palantirJavaFormat("2.73.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        licenseHeader(
            """
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
            """
                .trimIndent())
    }
}
