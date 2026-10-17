plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    id("org.jetbrains.kotlinx.rpc.plugin")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":shared"))
    api(libs.kotlinx.rpc.server)
    api(libs.kotlinx.rpc.client)
    api(libs.kotlinx.rpc.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}