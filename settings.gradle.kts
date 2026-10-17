pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        maven("https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/")
        maven("https://jitpack.io")
        maven("https://artifacts.consensys.net/public/maven/maven/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    kotlin("jvm") version "2.4.20" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
    id("org.jetbrains.kotlinx.rpc.plugin") version "0.10.4" apply false
}

gradle.beforeProject {
    group = "com.fantamomo.knot"
    version = "1.0-SNAPSHOT"
}

rootProject.name = "Knot"
include(":shared")
include(":cli-shared")
include(":cli-agent")
include(":cli")
include(":node")