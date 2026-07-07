plugins {
    `java-library`
    id("com.diffplug.spotless") version "7.2.1"
}

java {
    toolchain {
        // Virtual thread 가 synchronized pinning 없이 동작하는 첫 LTS (JEP 491)
        languageVersion = JavaLanguageVersion.of(25)
    }
    // withSourcesJar()/withJavadocJar() 는 Maven Central publish 붙일 때(L2+) 추가한다.
}

repositories {
    mavenCentral()
}

dependencies {
    // core 는 런타임 의존성 0 (JDK only) — 아래는 전부 테스트 스코프
    testImplementation(platform("org.junit:junit-bom:5.14.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("net.jqwik:jqwik:1.9.3")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

spotless {
    java {
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
