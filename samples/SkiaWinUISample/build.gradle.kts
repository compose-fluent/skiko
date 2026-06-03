@file:Suppress("UNCHECKED_CAST")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
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
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent {
            snapshotsOnly()
        }
    }
    google()
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
}

val skikoWinuiDependencyNotations = extra["skikoWinuiDependencyNotations"] as List<Any>
val skikoWinuiRuntimeAssetsRoot = extra["skikoWinuiRuntimeAssetsRoot"] as Provider<String>
val checkSkikoWinuiSampleRuntime = extra["checkSkikoWinuiSampleRuntime"] as (Project) -> Unit
val javaToolchains = extensions.getByType(JavaToolchainService::class.java)

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    skikoWinuiDependencyNotations.forEach(::implementation)
}

application {
    mainClass.set("SkiaWinUISample.AppKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(22))
    }
}

tasks.named<JavaExec>("run") {
    group = "application"
    description = "Runs the AWT-free WinUI Skiko sample."
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    )
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx512m",
        "-XX:MaxMetaspaceSize=512m",
        "-XX:HeapBaseMinAddress=32g",
        "-XX:ReservedCodeCacheSize=128m",
        "-XX:CICompilerCount=2",
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
