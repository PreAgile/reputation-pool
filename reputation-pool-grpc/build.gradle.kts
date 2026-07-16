import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.diffplug.spotless")
    // Generates the protobuf message classes and the gRPC service stubs from src/main/proto.
    id("com.google.protobuf") version "0.10.0"
    // On-demand mutation testing (ratchet policy: CONTRIBUTING.md). 1.19.0 matches core.
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

// Conservative, widely-compatible baseline shared by every consumer of this contract module (the
// reference server and any downstream host). protobuf 3.x avoids the 4.x runtime break, so a host
// pinned to an older gRPC/protobuf (e.g. via a Spring gRPC starter) can consume these stubs as-is.
val grpcVersion = "1.63.0"
val protobufVersion = "3.25.1"

// Central target, signing, and the shared POM boilerplate come from the root subprojects block; only
// this module's coordinates, name, and description live here.
mavenPublishing {
    coordinates("io.github.preagile", "reputation-pool-grpc", project.version.toString())
    pom {
        name = "Reputation Pool gRPC"
        description =
            "The gRPC surface of the reputation-pool engine: the advisor.proto contract plus the " +
                "wire<->domain mapping, event broadcaster, and advisor service, so any JVM host can " +
                "expose the engine over gRPC without reimplementing the adapter."
    }
}

dependencies {
    // The gRPC adapter depends inward on the pure core; the dependency arrow never points the other way.
    api(project(":reputation-pool-core"))

    // api, not implementation: the generated stubs and message classes are part of this module's
    // published API, so a downstream consumer needs these types on its own compile classpath.
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    // The generated gRPC stubs carry a javax.annotation.Generated annotation; supply it at compile time.
    // Apache-2.0 (as recommended by the grpc-java README), matching this repo's license.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // In-process transport: contract tests ride the real gRPC wiring without sockets or ports.
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("net.jqwik:jqwik:1.10.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    // Shared test helpers from core (SettableClock) instead of a per-module copy.
    testImplementation(testFixtures(project(":reputation-pool-core")))

    // Teaches PIT to drive the JUnit Platform (Jupiter + jqwik), same version as core.
    pitest("org.pitest:pitest-junit5-plugin:1.2.3")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

// PIT targets only the pure proto<->domain mapper (ProtoMapping). The gRPC wiring (the service and
// broadcaster) is excluded: its mutants cannot be killed without a live transport.
pitest {
    pitestVersion = "1.25.5"
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("io.github.preagile.reputationpool.grpc.ProtoMapping")
    targetTests = setOf("io.github.preagile.reputationpool.grpc.ProtoMapping*Test")
    threads = 4
    timestampedReports = false
    // Measured baseline from the server module: 0 surviving mutants (25/25 killed). Tighten only.
    maxSurviving = 0
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
    // Generated protobuf/gRPC sources are not ours to document — lint only the handwritten code.
    exclude("io/github/preagile/reputationpool/grpc/v1/**")
}

// Make Javadoc part of the build gate, as in the other modules, so a broken doc reference fails the
// build.
tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}

spotless {
    java {
        // Only our hand-written sources — the generated protobuf/gRPC code lives under build/ and is
        // left untouched (no license header, no formatting check).
        target("src/*/java/**/*.java")
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
