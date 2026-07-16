plugins {
    // Declared once at the root (apply false) so sibling modules load Spotless from one shared plugin
    // classpath — otherwise its shared build service clashes across projects. Each module applies it
    // without a version.
    id("com.diffplug.spotless") version "8.8.0" apply false
    // Same reason as Spotless: vanniktech's MavenCentralBuildService is a shared build service, so
    // applying the plugin with a version in each publishable module loads it on separate classloader
    // scopes and the service clashes. Declared once here (apply false); modules apply it without a
    // version. All four library modules (core, persistence, adapters, server) publish to Central.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    group = "io.github.preagile"
    version = findProperty("releaseVersion")?.toString() ?: "0.1.0-SNAPSHOT"
}
