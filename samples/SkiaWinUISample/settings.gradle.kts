pluginManagement {
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
}

rootProject.name = "SkiaWinUISample"

if (providers.gradleProperty("skiko.winui.useLocalProject").map(String::toBoolean).getOrElse(false)) {
    includeBuild("../../skiko")
    include("skiko-winui")
    project(":skiko-winui").projectDir = file("../../skiko/skiko-winui")
}
