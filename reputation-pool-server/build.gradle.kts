plugins {
    `java-library`
    id("com.diffplug.spotless")
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
// this module's coordinates, name, and description live here. Published as the runnable reference host;
// downstream consumers depend on reputation-pool-grpc for the contract, not on this server.
mavenPublishing {
    coordinates("io.github.preagile", "reputation-pool-server", project.version.toString())
    pom {
        name = "Reputation Pool Server"
        description =
            "The reference gRPC server: a thin composition root that assembles the engine, the " +
                "persistence adapter, and the reputation-pool-grpc contract into a runnable host."
    }
}

// Matches the reputation-pool-grpc baseline so the reference host runs on the same gRPC transport
// version as the published stubs.
val grpcVersion = "1.63.0"

dependencies {
    // The server ring depends inward on the pure core; the dependency arrow never points the other way.
    api(project(":reputation-pool-core"))

    // The gRPC surface (advisor.proto stubs + mapping/broadcaster/service) now lives in its own
    // module; the server assembles it rather than owning it.
    implementation(project(":reputation-pool-grpc"))

    // The composition root wires the persistence adapter (a ResourceStore) into the pool's lifecycle.
    // Versions match the persistence module so the driver/Flyway resolve to one artifact each.
    implementation(project(":reputation-pool-persistence"))
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.flywaydb:flyway-core:13.0.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:13.0.0")

    // A concrete transport is only needed to actually run/serve; the grpc module brings the stub API.
    runtimeOnly("io.grpc:grpc-netty-shaded:$grpcVersion")

    // In-process transport: the server's lifecycle tests ride the real gRPC wiring without sockets.
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("net.jqwik:jqwik:1.10.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    // Shared test helpers from core (SettableClock) instead of per-module copies.
    testImplementation(testFixtures(project(":reputation-pool-core")))
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
