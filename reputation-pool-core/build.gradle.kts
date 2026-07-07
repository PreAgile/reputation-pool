plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.8.0"
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
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
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
