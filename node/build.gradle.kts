plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
    application
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.kotlinxPluginAtomicfu)
}

dependencies {
    implementation(project(":shared"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "com.fantamomo.knot.node.MainKt"
}

tasks.shadowJar {
    archiveBaseName.set("knot-node")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.test {
    useJUnitPlatform()
    filter {
        isFailOnNoMatchingTests = false
    }
}