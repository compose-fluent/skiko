import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

val skikoWinuiDependencyMode = providers.gradleProperty("skiko.winui.dependencyMode")
    .orElse("local")
val skikoWinuiVersion = providers.gradleProperty("skiko.winui.version")
    .orElse(providers.gradleProperty("skiko.version"))
    .orElse("0.0.0-SNAPSHOT")
val kotlinWinRtVersion = providers.gradleProperty("kotlinWinRt.version")
    .orElse("0.1.0-SNAPSHOT")
val kotlinWinRtLocalRepo = providers.gradleProperty("kotlinWinRt.localRepo")
    .orElse("../../../kotlin-winrt")

val skikoWinuiProjectDir = rootProject.file("../../skiko/skiko-winui")
val kotlinWinRtBuild = rootProject.file(kotlinWinRtLocalRepo.get())
val skikoWinuiRuntimeAssetsRoot = providers.gradleProperty("skiko.winui.runtimeAssetsRoot")
    .orElse(skikoWinuiProjectDir.resolve("build/kotlin-winrt/application-package").absolutePath)

val skikoWinuiRequiredLocalFiles = listOf(
    skikoWinuiProjectDir.resolve("build/libs/skiko-winui-winuijvm.jar"),
    skikoWinuiProjectDir.resolve("build/libs/skiko-winui-windows.jar"),
    kotlinWinRtBuild.resolve("winrt-runtime/build/libs/winrt-runtime-jvm.jar"),
)
val skikoWinuiOptionalLocalFiles = listOf(
    rootProject.file("../../skiko/build/libs/skiko-awt-0.0.0-SNAPSHOT.jar"),
).filter(File::isFile)

val skikoWinuiDependencyNotations: List<Any> = when (val mode = skikoWinuiDependencyMode.get()) {
    "local" -> listOf(files(skikoWinuiRequiredLocalFiles + skikoWinuiOptionalLocalFiles))
    "maven" -> listOf(
        "org.jetbrains.skiko:skiko-winui-winuijvm:${skikoWinuiVersion.get()}",
        "org.jetbrains.skiko:skiko-winui-windows:${skikoWinuiVersion.get()}",
        "io.github.composefluent.winrt:winrt-runtime:${kotlinWinRtVersion.get()}",
    )
    else -> throw GradleException(
        "Unsupported skiko.winui.dependencyMode='$mode'. Use 'local' or 'maven'."
    )
}

fun Project.checkSkikoWinuiSampleRuntime() {
    if (skikoWinuiDependencyMode.get() == "local") {
        val missing = skikoWinuiRequiredLocalFiles.filterNot(File::isFile)
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing local skiko-winui sample dependencies. Build :skiko-winui:winuiJvmJar, " +
                    ":skiko-winui:skikoWinuiWindowsRuntimeJar, and kotlin-winrt runtime first, " +
                    "or use -Pskiko.winui.dependencyMode=maven after artifacts are published.\n" +
                    missing.joinToString("\n")
            )
        }
    }

    val assetsRoot = file(skikoWinuiRuntimeAssetsRoot.get())
    if (!assetsRoot.resolve("Microsoft.WindowsAppRuntime.Bootstrap.dll").isFile) {
        throw GradleException(
            "Missing WinUI runtime assets at $assetsRoot. Run :skiko-winui:stageWinRtApplicationPackage " +
                "or set -Pskiko.winui.runtimeAssetsRoot=<application-package path>."
        )
    }
}

extra["skikoWinuiDependencyMode"] = skikoWinuiDependencyMode
extra["skikoWinuiDependencyNotations"] = skikoWinuiDependencyNotations
extra["skikoWinuiRuntimeAssetsRoot"] = skikoWinuiRuntimeAssetsRoot
extra["checkSkikoWinuiSampleRuntime"] = { target: Project ->
    target.checkSkikoWinuiSampleRuntime()
}
