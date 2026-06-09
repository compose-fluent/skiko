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

rootProject.name = "skiko-all"

fun settingsFlag(name: String): Boolean {
    val environmentName = "ORG_GRADLE_PROJECT_" + name.replace('.', '_')
    return providers.gradleProperty(name).orNull?.toBoolean()
        ?: System.getProperty(name)?.toBoolean()
        ?: System.getenv(environmentName)?.toBoolean()
        ?: false
}

if (!settingsFlag("skiko.winui.skipSamples")) {
    includeBuild("samples/SkiaAwtSample")
    includeBuild("samples/SkiaWinUISample")
}
if (!settingsFlag("skiko.winui.skipSkikoComposite")) {
    includeBuild("skiko")
}
include("skiko-winui")
project(":skiko-winui").projectDir = file("skiko/skiko-winui")
