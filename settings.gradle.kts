pluginManagement {
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
    if (!providers.gradleProperty("skiko.winui.skipKotlinWinRtComposite").map(String::toBoolean).getOrElse(false)) {
        includeBuild(kotlinWinRtBuild.resolve("winrt-gradle-plugin"))
    }
    repositories {
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
if (!settingsFlag("skiko.winui.skipKotlinWinRtComposite")) {
    includeBuild(kotlinWinRtBuild)
}
includeBuild("samples/SkiaAwtSample")
includeBuild("samples/SkiaWinUISample")
if (!settingsFlag("skiko.winui.skipSkikoComposite")) {
    includeBuild("skiko")
}
include("skiko-winui")
project(":skiko-winui").projectDir = file("skiko/skiko-winui")
