plugins {
    `java-library`
    id("com.diffplug.spotless")
    // On-demand mutation testing (ratchet policy: CONTRIBUTING.md). 1.19.0 matches core.
    id("info.solidsoft.pitest") version "1.19.0"
    // Version + apply-false live at the root (shared build service); applied here without a version.
    id("com.vanniktech.maven.publish")
}

java {
    toolchain {
        // Same JDK 25 toolchain as the rest of the build; auto-provisioned by the Foojay resolver.
        // Also the reason this module can lease one virtual thread per in-flight probe without a pool:
        // JEP 491 (JDK 24+) means a probe blocking inside `synchronized` no longer pins its carrier.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Central target, signing, and the shared POM boilerplate come from the root subprojects block; only
// this module's coordinates, name, and description live here.
mavenPublishing {
    coordinates("io.github.preagile", "reputation-pool-prober", project.version.toString())
    pom {
        name = "Reputation Pool Recovery Prober"
        description =
            "Closes the recovery gap acquire() leaves open: a COOLING resource is never offered as a " +
                "candidate, so once its cooldown has passed nothing lease-driven is left to report a " +
                "success and let it probate. RecoveryScheduler tests it directly instead, off an " +
                "event-driven fast path plus a periodic backstop sweep."
    }
}

dependencies {
    // Depends only inward on the pure core, plus the JDK — no third-party runtime dependency, matching
    // the sibling infrastructure modules (grpc, server), not the demo adapters' SLF4J choice. Resource-
    // kind-specific RecoveryProbe implementations (e.g. an HTTP one for PROXY) live in an adapter module
    // that depends on this one, not the other way around.
    api(project(":reputation-pool-core"))

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    // Shared test helpers from core (SettableClock) instead of a per-module copy.
    testImplementation(testFixtures(project(":reputation-pool-core")))

    // Teaches PIT to drive the JUnit Platform (Jupiter), same version as core.
    pitest("org.pitest:pitest-junit5-plugin:1.2.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

// Make Javadoc part of the build gate, as in the other modules, so a broken doc reference fails the
// build.
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
