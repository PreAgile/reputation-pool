plugins {
    `java-library`
    id("com.diffplug.spotless")
    // On-demand mutation testing (ratchet policy: CONTRIBUTING.md). 1.19.0 matches the other modules.
    id("info.solidsoft.pitest") version "1.19.0"
    // Version + apply-false live at the root (shared build service); applied here without a version.
    id("com.vanniktech.maven.publish")
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

// Central target, signing, and the shared POM boilerplate come from the root subprojects block; only
// this module's coordinates, name, and description live here.
mavenPublishing {
    coordinates("io.github.preagile", "reputation-pool-rest", project.version.toString())
    pom {
        name = "Reputation Pool REST"
        description =
            "The REST/JSON surface of the reputation-pool engine: the OpenAPI contract plus the " +
                "wire<->domain mapping and opaque lease references, so any JVM host can expose the " +
                "engine over HTTP without a code generator."
    }
}

dependencies {
    // The REST adapter depends inward on the pure core; the dependency arrow never points the other
    // way. It deliberately does NOT depend on reputation-pool-grpc: reusing the proto messages via
    // JsonFormat would force protobuf-JSON on the wire and couple the two surfaces, so each surface
    // owns its own DTOs and mapping. An ArchUnit rule enforces it.
    api(project(":reputation-pool-core"))

    // implementation, not api: JSON encoding is an internal choice of this module, confined to the
    // Json codec class. Keeping it off the consumer's compile classpath means a downstream host is
    // free to run a different Jackson version, and swapping the codec later breaks no one.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("net.jqwik:jqwik:1.10.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    // Shared domain generators from core (DomainArbitraries) instead of a per-module copy.
    testImplementation(testFixtures(project(":reputation-pool-core")))

    // Teaches PIT to drive the JUnit Platform (Jupiter + jqwik), same version as the other modules.
    pitest("org.pitest:pitest-junit5-plugin:1.2.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

// PIT targets the pure boundary translation — the mapper and the lease-reference codec. Both are total
// functions over generated input, so every mutant is killable without a transport; the HTTP wiring that
// arrives in later issues stays excluded for the same reason the grpc module excludes its service.
pitest {
    pitestVersion = "1.25.5"
    junit5PluginVersion = "1.2.3"
    targetClasses =
        setOf(
            "io.github.preagile.reputationpool.rest.RestMapping",
            "io.github.preagile.reputationpool.rest.LeaseRef")
    targetTests =
        setOf(
            "io.github.preagile.reputationpool.rest.RestMapping*Test",
            "io.github.preagile.reputationpool.rest.LeaseRef*Test")
    threads = 4
    timestampedReports = false
    // Measured baseline: 0 surviving mutants (75/75 killed). Tighten only.
    maxSurviving = 0
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
