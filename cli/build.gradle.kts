plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    id("com.gradleup.shadow")
    id("org.graalvm.buildtools.native")
    application
}

fun sharedDependencies(adder: (Any) -> Dependency?) {
    adder(project(":shared"))
    adder(project(":cli-shared"))
}

dependencies {
    sharedDependencies(::implementation)
    implementation(project(":cli-agent"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}

val agentlessRuntimeClasspath by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    sharedDependencies { agentlessRuntimeClasspath(it) }
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "com.fantamomo.knot.cli.MainKt"
}

tasks.shadowJar {
    archiveBaseName.set("knot-cli")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

val shadowJarWithoutAgent = tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>(
    "shadowJarWithoutAgent"
) {
    archiveBaseName.set("knot-cli")
    archiveClassifier.set("no-agent")
    archiveVersion.set(project.version.toString())

    from(sourceSets.main.get().output)

    configurations = listOf(agentlessRuntimeClasspath)

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.shadowJar {
    finalizedBy(shadowJarWithoutAgent)
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("knot-cli")
            mainClass.set(application.mainClass)
        }
    }
}