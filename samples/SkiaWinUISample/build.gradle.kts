@file:Suppress("UNCHECKED_CAST")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.toolchain.JavaLanguageVersion

buildscript {
    val kotlinWinRtVersion = providers.gradleProperty("kotlinWinRt.version")
        .orElse("0.1.0-SNAPSHOT")
        .get()
    val kotlinWinRtGroup = providers.gradleProperty("kotlinWinRt.group")
        .orElse("io.github.compose-fluent")
        .get()

    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
        mavenCentral()
        gradlePluginPortal()
        google()
    }
    dependencies {
        classpath("$kotlinWinRtGroup:winrt-gradle-plugin:$kotlinWinRtVersion")
    }
}

plugins {
    kotlin("jvm") version "2.4.0"
    application
}

apply(plugin = "io.github.composefluent.winrt")
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
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

extensions.configure<io.github.composefluent.winrt.gradle.WinRtExtension>("winRt") {
    application { }
}

tasks.named<JavaExec>("run") {
    group = "application"
    description = "Use runWinRtApplicationHost for the AWT-free WinUI Skiko sample."
    enabled = false
    doFirst {
        throw GradleException("Use runWinRtApplicationHost to launch the WinUI sample through the native app host.")
    }
}

tasks.named("runWinRtApplicationHost") {
    group = "application"
    description = "Runs the AWT-free WinUI Skiko sample through the generated native app host."
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }
}
