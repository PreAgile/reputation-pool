plugins {
    `java-library`
    id("com.diffplug.spotless")
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
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Shared test helpers from core (SettableClock) instead of per-module copies.
    testImplementation(testFixtures(project(":reputation-pool-core")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    // WireMock drives the integration test: a real HTTP endpoint the adapter probes over java.net.http.
    // Used programmatically (WireMockServer), not via its JUnit extension, to stay off the JUnit version.
    testImplementation("org.wiremock:wiremock:3.9.1")
    // A no-op SLF4J provider so the logging EventSink under test prints nothing and no NOP warning fires.
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
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
