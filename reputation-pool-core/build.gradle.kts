plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.8.0"
    // On-demand mutation testing via `./gradlew pitest`. 1.19.0 is the first release
    // that supports Gradle 9.x; it is deliberately NOT wired into `build` or CI.
    id("info.solidsoft.pitest") version "1.19.0"
}

java {
    toolchain {
        // First LTS where virtual threads no longer pin the carrier inside synchronized (JEP 491)
        languageVersion = JavaLanguageVersion.of(25)
    }
    // withSourcesJar()/withJavadocJar() are added when Maven Central publishing lands (L2+).
}

repositories {
    mavenCentral()
}

dependencies {
    // core has zero runtime dependencies (JDK only); everything below is test scope
    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("net.jqwik:jqwik:1.10.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    // ArchUnit pulls in slf4j-api with no provider on the test classpath, so every run prints the
    // "No SLF4J providers were found" NOP warning. slf4j-nop is that provider (a no-op sink).
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")

    // Teaches PIT to drive the JUnit Platform (Jupiter + jqwik run on it). 1.2.3 is the
    // current release and requires pitest core >= 1.19.4, satisfied by the version below.
    pitest("org.pitest:pitest-junit5-plugin:1.2.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

tasks.withType<Javadoc>().configureEach {
    // `all,-missing` gates on broken structure (dangling @link/@throws, malformed HTML) without
    // demanding a doc comment on every record component/accessor — that would be noise, not safety.
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

// Make Javadoc part of the `build`/CI gate so a broken doc reference fails the build, not just
// `./gradlew javadoc`. `check` is what `build` depends on.
tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}

// Mutation testing is an on-demand quality probe (`./gradlew pitest`), not part of the
// `build`/CI gate — it is far slower and is used to check that tests actually have teeth.
pitest {
    // Pin a recent pitest core: 1.25.x carries the bytecode/JDK 25 fixes and works with
    // the JUnit Platform brought in by junit-bom 6.1.1 via the junit5 plugin above.
    pitestVersion = "1.25.5"
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("io.github.preagile.reputationpool.core.*")
    threads = 4
    timestampedReports = false
}

spotless {
    java {
        // 2.71.0+ is compatible with JDK 25 javac internals (older versions throw NoSuchMethodError)
        palantirJavaFormat("2.73.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        // Standard short Apache-2.0 header, stamped on every .java file and enforced by
        // `spotlessCheck` (part of `build`). No copyright holder is declared in LICENSE/README,
        // so attribute to the project's authors. Fixed year, not a dynamic one, so the header is
        // reproducible and does not churn every January.
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
