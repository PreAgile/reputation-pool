plugins {
    `java-library`
    id("com.diffplug.spotless")
}

java {
    toolchain {
        // Same JDK 25 toolchain as the rest of the build; auto-provisioned by the Foojay resolver.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// A separate source set for Testcontainers integration tests. It is deliberately NOT wired into
// `check`/`build`, so `./gradlew build` compiles it but never runs it — the build stays green with no
// Docker daemon. The `integrationTest` task below runs it on demand (and in CI, where Docker exists).
val integrationTest =
    sourceSets.create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }

// The integration source set inherits the unit-test dependencies, then adds its own (Testcontainers).
configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    // The persistence adapter depends inward on the pure core; the arrow never points the other way.
    api(project(":reputation-pool-core"))

    // Plain JDBC only — no Spring, JPA, or Hibernate. The PostgreSQL driver and Flyway (schema
    // migrations) are the module's only runtime dependencies.
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.flywaydb:flyway-core:12.11.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:12.11.0")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    // Property tests for the audit mapper's row<->event bijection (same version as core/server).
    testImplementation("net.jqwik:jqwik:1.10.1")
    // Shared test helpers from core (SettableClock) instead of a per-module copy.
    testImplementation(testFixtures(project(":reputation-pool-core")))

    // Integration-test only: a real PostgreSQL via Testcontainers, migrated by Flyway.
    "integrationTestImplementation"("org.testcontainers:postgresql:1.21.4")
    "integrationTestImplementation"("org.testcontainers:junit-jupiter:1.21.4")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

// On-demand only: `./gradlew :reputation-pool-persistence:integrationTest`. Needs a Docker daemon.
val integrationTestTask =
    tasks.register<Test>("integrationTest") {
        description = "Runs Testcontainers integration tests against a real PostgreSQL. Requires Docker."
        group = "verification"
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(tasks.test)
        testLogging {
            events("failed", "skipped")
        }
    }

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

// Make Javadoc part of the build gate, as in the other modules, so a broken doc reference fails the
// build. `integrationTest` is intentionally left out of `check` so `build` needs no Docker.
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
