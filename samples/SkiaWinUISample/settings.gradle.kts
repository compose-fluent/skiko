rootProject.name = "SkiaWinUISample"

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
}

if (providers.gradleProperty("skiko.winui.useLocalProject").map(String::toBoolean).getOrElse(false)) {
    includeBuild("../../skiko") {
        dependencySubstitution {
            substitute(module("io.github.compose-fluent:skiko-winui")).using(project(":"))
            substitute(module("io.github.compose-fluent:skiko-winui-mingw")).using(project(":"))
        }
    }
}
