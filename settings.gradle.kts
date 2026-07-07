plugins {
    // JDK 25 toolchain auto-provisioning (CI/로컬 어디서든 같은 JDK 로 빌드)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "reputation-pool"

include("reputation-pool-core")
