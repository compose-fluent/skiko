import org.gradle.api.GradleException

val skikoWinuiDependencyMode = providers.gradleProperty("skiko.winui.dependencyMode")
    .orElse("maven")
val skikoWinuiVersion = providers.gradleProperty("skiko.winui.version")
    .orElse(providers.gradleProperty("skiko.version"))
    .orElse("0.0.0-SNAPSHOT")
val kotlinWinRtVersion = providers.gradleProperty("kotlinWinRt.version")
    .orElse("0.1.0-SNAPSHOT")
val kotlinWinRtGroup = providers.gradleProperty("kotlinWinRt.group")
    .orElse("io.github.compose-fluent")
val winuiWindowsAppSdkVersion = providers.gradleProperty("skiko.winui.windowsAppSdkVersion")
    .orElse("2.1.3")
val winuiWindowsSdkVersion = providers.gradleProperty("skiko.winui.windowsSdkVersion")
    .orElse("10.0.26100.0")
val winuiWindowsSdkProjectionVersion = winuiWindowsSdkVersion.zip(kotlinWinRtVersion) { sdkVersion, winrtVersion ->
    if (winrtVersion.endsWith("-SNAPSHOT")) "$sdkVersion-kotlin-winrt-$winrtVersion" else sdkVersion
}
val winuiWindowsAppSdkProjectionVersion = winuiWindowsAppSdkVersion.zip(kotlinWinRtVersion) { appSdkVersion, winrtVersion ->
    if (winrtVersion.endsWith("-SNAPSHOT")) "$appSdkVersion-kotlin-winrt-$winrtVersion" else appSdkVersion
}

val skikoWinuiMavenNotations = listOf(
    "io.github.compose-fluent:skiko-winui:${skikoWinuiVersion.get()}",
    "io.github.compose-fluent:skiko-winui-windows:${skikoWinuiVersion.get()}",
    "${kotlinWinRtGroup.get()}:winrt-runtime-jvm:${kotlinWinRtVersion.get()}",
    "${kotlinWinRtGroup.get()}:winrt-projections-windows-sdk:${winuiWindowsSdkProjectionVersion.get()}",
    "${kotlinWinRtGroup.get()}:winrt-projections-windows-app-sdk:${winuiWindowsAppSdkProjectionVersion.get()}",
)

val skikoWinuiDependencyNotations: List<Any> = when (val mode = skikoWinuiDependencyMode.get()) {
    "local", "maven" -> skikoWinuiMavenNotations
    else -> throw GradleException(
        "Unsupported skiko.winui.dependencyMode='$mode'. Use 'local' or 'maven'."
    )
}

extra["skikoWinuiDependencyMode"] = skikoWinuiDependencyMode
extra["skikoWinuiDependencyNotations"] = skikoWinuiDependencyNotations
