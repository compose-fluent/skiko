pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
        mavenCentral {
            url = uri("https://cache-redirector.jetbrains.com/maven-central")
        }
        gradlePluginPortal()
        google()
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
    }

    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.composefluent.winrt") {
                val kotlinWinRTVersion = providers.gradleProperty("kotlinWinRT.version")
                    .orElse("0.1.0-SNAPSHOT")
                    .get()
                val kotlinWinRTGroup = providers.gradleProperty("kotlinWinRT.group")
                    .orElse("io.github.compose-fluent")
                    .get()
                useModule("$kotlinWinRTGroup:winrt-gradle-plugin:$kotlinWinRTVersion")
            }
        }
    }

    plugins {
        val kotlinVersion = extra["kotlin.version"] as String
        kotlin("jvm").version(kotlinVersion)
        kotlin("multiplatform").version(kotlinVersion)
    }
}

// Define version catalog programmatically so we can read versions from gradle.properties
// This overrides the automatic import of gradle/libs.versions.toml for the "libs" catalog.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("skiko", providers.gradleProperty("skiko.version").get())
            library("skiko", "org.jetbrains.skiko", "skiko").versionRef("skiko")
            library("skiko-wasm-runtime", "org.jetbrains.skiko", "skiko-js-wasm-runtime").versionRef("skiko")

            val osName = System.getProperty("os.name")
            val hostOs = when {
                osName == "Mac OS X" -> "macos"
                osName.startsWith("Win") -> "windows"
                osName.startsWith("Linux") -> "linux"
                else -> error("Unsupported OS: $osName")
            }

            val osArch = System.getProperty("os.arch")
            var hostArch = when (osArch) {
                "x86_64", "amd64" -> "x64"
                "aarch64" -> "arm64"
                else -> error("Unsupported arch: $osArch")
            }

            library("skiko-awt-runtime", "org.jetbrains.skiko", "skiko-awt-runtime-$hostOs-$hostArch").versionRef("skiko")
        }
    }
}

rootProject.name = "SkiaMultiplatformSample"

if (providers.gradleProperty("skiko.winui.useLocalProject").map(String::toBoolean).getOrElse(false)) {
    includeBuild("../../skiko") {
        dependencySubstitution {
            substitute(module("org.jetbrains.skiko:skiko")).using(project(":"))
            substitute(module("io.github.compose-fluent:skiko-winui")).using(project(":"))
            substitute(module("io.github.compose-fluent:skiko-winui-mingw")).using(project(":"))
        }
    }
}

if (extra.properties.getOrDefault("skiko.composite.build", "") == "1") {
    includeBuild("../../skiko") {
        dependencySubstitution {
            substitute(module("org.jetbrains.skiko:skiko")).using(project(":"))
        }
    }
}
