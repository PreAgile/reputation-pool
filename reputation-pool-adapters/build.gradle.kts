plugins {
    `java-library`
    id("com.diffplug.spotless")
    // On-demand mutation testing (ratchet policy: CONTRIBUTING.md). 1.19.0 matches core.
    id("info.solidsoft.pitest") version "1.19.0"
    // Published to Central so downstream consumers can reuse the reference adapters instead of
    // reimplementing them. Version + apply-false live at the root (shared build service).
    id("com.vanniktech.maven.publish")
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

// Central target, signing, and the shared POM boilerplate come from the root subprojects block; only
// this module's coordinates, name, and description live here. Published so downstream consumers can
// reuse the reference adapters instead of reimplementing them.
mavenPublishing {
    coordinates("io.github.preagile", "reputation-pool-adapters", project.version.toString())
    pom {
        name = "Reputation Pool Adapters"
        description =
            "Reference adapters for the reputation-pool engine: HTTP outcome classifiers for proxies " +
                "and accounts, plus an SLF4J event sink."
    }
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

// PIT targets only the pure HTTP-outcome classifiers. The probing/wiring (AccountProbe,
// Slf4jEventSink) is excluded: its mutants cannot be killed without a live HTTP endpoint
// (WireMock). `targetTests` pins the classifier tests.
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
    // Measured baseline: 7 surviving mutants (43/50 killed), stable across repeated runs since the
    // 2xx edge examples pinned the status-code boundary. The rest are boundary/equivalent mutants
    // (latency exactly at the slow threshold, the unwrapAsync depth cap) plus the unread
    // slowThreshold() accessors. Tighten only, never raise.
    maxSurviving = 7
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
