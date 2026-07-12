plugins {
    // Declared once at the root (apply false) so sibling modules load Spotless from one shared plugin
    // classpath — otherwise its shared build service clashes across projects. Each module applies it
    // without a version.
    id("com.diffplug.spotless") version "8.8.0" apply false
}

allprojects {
    group = "io.github.preagile"
    version = findProperty("releaseVersion")?.toString() ?: "0.1.0-SNAPSHOT"
}
