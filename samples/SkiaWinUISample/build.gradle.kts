@file:Suppress("UNCHECKED_CAST")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.Project
import org.gradle.api.provider.Provider

plugins {
    kotlin("jvm") version "2.3.20"
    application
}

apply(from = "../skiko-winui-sample-dependencies.gradle.kts")

repositories {
    mavenLocal()
    mavenCentral()
    google()
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
}

val skikoWinuiDependencyNotations = extra["skikoWinuiDependencyNotations"] as List<Any>
val skikoWinuiRuntimeAssetsRoot = extra["skikoWinuiRuntimeAssetsRoot"] as Provider<String>
val checkSkikoWinuiSampleRuntime = extra["checkSkikoWinuiSampleRuntime"] as (Project) -> Unit

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(kotlin("stdlib"))
    skikoWinuiDependencyNotations.forEach(::implementation)
}

application {
    mainClass.set("SkiaWinUISample.AppKt")
}

tasks.named<JavaExec>("run") {
    group = "application"
    description = "Runs the AWT-free WinUI Skiko sample."
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-ea",
    )
    systemProperty("kotlin.winrt.runtimeAssetsRoot", skikoWinuiRuntimeAssetsRoot.get())
    doFirst {
        checkSkikoWinuiSampleRuntime(project)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_22)
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }
}
