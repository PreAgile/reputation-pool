plugins {
    // JDK 25 toolchain auto-provisioning, so CI and local builds use the same JDK
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "reputation-pool"

include("reputation-pool-core")
include("reputation-pool-adapters")
