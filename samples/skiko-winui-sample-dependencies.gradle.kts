import org.gradle.api.GradleException

val skikoWinuiDependencyMode = providers.gradleProperty("skiko.winui.dependencyMode")
    .orElse("maven")
val skikoWinuiVersion = providers.gradleProperty("skiko.winui.version")
    .orElse(providers.gradleProperty("skiko.version"))
    .orElse("0.0.0-SNAPSHOT")
val kotlinWinRTVersion = providers.gradleProperty("kotlinWinRT.version")
    .orElse("0.1.0-SNAPSHOT")
val kotlinWinRTGroup = providers.gradleProperty("kotlinWinRT.group")
    .orElse("io.github.compose-fluent")
val skikoWinuiUseLocalProject = providers.gradleProperty("skiko.winui.useLocalProject")
    .map(String::toBoolean)
    .orElse(false)

val skikoWinuiCommonMavenNotations = listOf(
    "io.github.compose-fluent:skiko-winui:${skikoWinuiVersion.get()}",
    "${kotlinWinRTGroup.get()}:winrt-runtime:${kotlinWinRTVersion.get()}",
)
val skikoWinuiJvmMavenNotations = listOf(
    "io.github.compose-fluent:skiko-winui-windows:${skikoWinuiVersion.get()}",
    "${kotlinWinRTGroup.get()}:winrt-runtime-jvm:${kotlinWinRTVersion.get()}",
)
val skikoWinuiMingwMavenNotations = listOf(
    "io.github.compose-fluent:skiko-winui-mingw:${skikoWinuiVersion.get()}",
)

val skikoWinuiCommonDependencyNotations: List<Any> = when (val mode = skikoWinuiDependencyMode.get()) {
    "local", "maven" -> if (skikoWinuiUseLocalProject.get()) {
        skikoWinuiCommonMavenNotations
    } else {
        skikoWinuiCommonMavenNotations
    }
    else -> throw GradleException(
        "Unsupported skiko.winui.dependencyMode='$mode'. Use 'local' or 'maven'."
    )
}

val skikoWinuiJvmDependencyNotations: List<Any> = when (val mode = skikoWinuiDependencyMode.get()) {
    "local", "maven" -> if (skikoWinuiUseLocalProject.get()) {
        listOf(
            "${kotlinWinRTGroup.get()}:winrt-runtime-jvm:${kotlinWinRTVersion.get()}",
        )
    } else {
        skikoWinuiJvmMavenNotations
    }
    else -> throw GradleException(
        "Unsupported skiko.winui.dependencyMode='$mode'. Use 'local' or 'maven'."
    )
}

val skikoWinuiMingwDependencyNotations: List<Any> = when (val mode = skikoWinuiDependencyMode.get()) {
    "local", "maven" -> if (skikoWinuiUseLocalProject.get()) {
        skikoWinuiMingwMavenNotations
    } else {
        skikoWinuiMingwMavenNotations
    }
    else -> throw GradleException(
        "Unsupported skiko.winui.dependencyMode='$mode'. Use 'local' or 'maven'."
    )
}

extra["skikoWinuiDependencyMode"] = skikoWinuiDependencyMode
extra["skikoWinuiCommonDependencyNotations"] = skikoWinuiCommonDependencyNotations
extra["skikoWinuiJvmDependencyNotations"] = skikoWinuiJvmDependencyNotations
extra["skikoWinuiMingwDependencyNotations"] = skikoWinuiMingwDependencyNotations
