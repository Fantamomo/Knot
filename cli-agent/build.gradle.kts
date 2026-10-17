plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    id("com.gradleup.shadow")
    application
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":cli-shared"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "com.fantamomo.knot.cliagent.MainKt"
}

tasks.shadowJar {
    archiveBaseName.set("knot-cli-agent")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}