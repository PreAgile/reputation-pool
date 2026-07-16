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

// Shared Central-publishing config for every module that applies the vanniktech plugin. The POM
// boilerplate (url, license, developer, scm) plus the Central target and signing are identical across
// modules, so they live here once; each module supplies only what differs — coordinates, name, and
// description. Runs for a module the moment it applies the plugin (plugins.withId), so it composes
// with the module's own `mavenPublishing { }` block regardless of evaluation order.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()
            pom {
                url = "https://github.com/PreAgile/reputation-pool"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "preagile"
                        name = "meyonsoo"
                        url = "https://github.com/PreAgile"
                    }
                }
                scm {
                    url = "https://github.com/PreAgile/reputation-pool"
                    connection = "scm:git:https://github.com/PreAgile/reputation-pool.git"
                    developerConnection = "scm:git:ssh://git@github.com/PreAgile/reputation-pool.git"
                }
            }
        }
    }
}
