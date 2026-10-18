plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.kotlinxPluginAtomicfu)
}

dependencies {
    // Apply the kotlinx bundle of dependencies from the version catalog (`gradle/libs.versions.toml`).
    api(libs.bundles.kotlinxEcosystem)
    //api(libs.bundles.ktorNetworks)
//    api(libs.jvm.lib2p2)

    // while this current module does not direclty use kotlinx-cli, all the other modules do,
    // so instead of adding it to all of them, we just add it here
    api(libs.kotlinxCli)
    api(libs.logback)
    api(libs.netty.all)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutines)
    testImplementation(libs.kotlinxCoroutinesTest)
    implementation(kotlin("reflect"))
}

tasks.test {
    useJUnitPlatform()
}


kotlin {
    jvmToolchain(25)
}