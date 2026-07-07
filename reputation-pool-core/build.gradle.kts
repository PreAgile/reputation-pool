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
    }
}
