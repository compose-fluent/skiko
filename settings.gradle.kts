pluginManagement {
    if (providers.gradleProperty("skiko.winui.useKotlinWinRtComposite").map(String::toBoolean).getOrElse(false)) {
        val kotlinWinRtLocalRepo = providers.gradleProperty("kotlinWinRt.localRepo")
            .orElse("../kotlin-winrt")
            .get()
        val kotlinWinRtBuild = file(kotlinWinRtLocalRepo)
        if (!kotlinWinRtBuild.isDirectory) {
            throw GradleException(
                "kotlin-winrt sibling clone not found at '$kotlinWinRtLocalRepo'. " +
                    "Clone https://github.com/compose-fluent/kotlin-winrt or set -PkotlinWinRt.localRepo=<path>."
            )
        }
        includeBuild(kotlinWinRtBuild.resolve("winrt-gradle-plugin"))
    }
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

if (settingsFlag("skiko.winui.useKotlinWinRtComposite")) {
    val kotlinWinRtLocalRepo = providers.gradleProperty("kotlinWinRt.localRepo")
        .orElse("../kotlin-winrt")
        .get()
    val kotlinWinRtBuild = file(kotlinWinRtLocalRepo)
    if (!kotlinWinRtBuild.isDirectory) {
        throw GradleException(
            "kotlin-winrt sibling clone not found at '$kotlinWinRtLocalRepo'. " +
                "Clone https://github.com/compose-fluent/kotlin-winrt or set -PkotlinWinRt.localRepo=<path>."
        )
    }
    includeBuild(kotlinWinRtBuild)
}
includeBuild("samples/SkiaAwtSample")
includeBuild("samples/SkiaWinUISample")
if (!settingsFlag("skiko.winui.skipSkikoComposite")) {
    includeBuild("skiko")
}
include("skiko-winui")
project(":skiko-winui").projectDir = file("skiko/skiko-winui")
