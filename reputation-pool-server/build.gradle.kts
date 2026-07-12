import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.diffplug.spotless")
    // Generates the protobuf message classes and the gRPC service stubs from src/main/proto.
    id("com.google.protobuf") version "0.9.4"
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

val grpcVersion = "1.69.0"
val protobufVersion = "4.29.3"

dependencies {
    // The server ring depends inward on the pure core; the dependency arrow never points the other way.
    api(project(":reputation-pool-core"))

    // The composition root wires the persistence adapter (a ResourceStore) into the pool's lifecycle.
    // Versions match the persistence module so the driver/Flyway resolve to one artifact each.
    implementation(project(":reputation-pool-persistence"))
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.flywaydb:flyway-core:12.11.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:12.11.0")

    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    // A concrete transport is only needed to actually run/serve; codegen and the mapper do not need it.
    runtimeOnly("io.grpc:grpc-netty-shaded:$grpcVersion")
    // The generated gRPC stubs carry a javax.annotation.Generated annotation; supply it at compile time.
    // This is the artifact the grpc-java README itself recommends: it is Apache-2.0 like this repo,
    // whereas javax.annotation:javax.annotation-api is CDDL — the "Tomcat 6" vintage is not a defect.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // In-process transport: contract tests ride the real gRPC wiring without sockets or ports.
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Property tests attack the proto <-> domain mapping invariants (round-trip, enum totality).
    testImplementation("net.jqwik:jqwik:1.10.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    // Shared test helpers from core (SettableClock) instead of per-module copies.
    testImplementation(testFixtures(project(":reputation-pool-core")))
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
