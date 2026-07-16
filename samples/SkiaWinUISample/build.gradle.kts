@file:Suppress("UNCHECKED_CAST")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("io.github.composefluent.winrt")
}

apply(from = "../skiko-winui-sample-dependencies.gradle.kts")

repositories {
    mavenLocal {
        content {
            includeModule("io.github.compose-fluent", "skiko-winui")
            includeModule("io.github.compose-fluent", "skiko-winui-windows")
            includeModule("io.github.compose-fluent", "skiko-winui-mingw")
            includeModule("io.github.compose-fluent", "skiko-winui-mingw-runtime")
        }
    }
    maven(layout.projectDirectory.dir("../../build/repo"))
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent {
            snapshotsOnly()
        }
    }
    google()
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
}

val skikoWinuiCommonDependencyNotations = extra["skikoWinuiCommonDependencyNotations"] as List<Any>
val skikoWinuiJvmDependencyNotations = extra["skikoWinuiJvmDependencyNotations"] as List<Any>
val skikoWinuiMingwDependencyNotations = extra["skikoWinuiMingwDependencyNotations"] as List<Any>
val skikoWinuiVersion = providers.gradleProperty("skiko.winui.version")
    .orElse(providers.gradleProperty("skiko.version"))
    .orElse("0.0.0-SNAPSHOT")
val skikoWinuiUseLocalProject = providers.gradleProperty("skiko.winui.useLocalProject")
    .map(String::toBoolean)
    .orElse(false)
val winuiMingwEnabled = providers.gradleProperty("skiko.winui.mingw.enabled")
    .map(String::toBoolean)
    .orElse(true)
val skikoWinuiWindowsRuntimeJarProvider = providers.gradleProperty("skiko.winui.windowsRuntimeJar")
    .map(layout.projectDirectory::file)
val skikoWinuiMingwRuntimeJarProvider = providers.gradleProperty("skiko.winui.mingwRuntimeJar")
    .map(layout.projectDirectory::file)
val skikoWinuiWindowsRuntimeFiles = configurations.create("skikoWinuiWindowsRuntimeFiles") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val skikoWinuiMingwRuntimeFiles = configurations.create("skikoWinuiMingwRuntimeFiles") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val skikoWinuiMingwRuntimePayloadDir = layout.buildDirectory.dir("skiko-winui-mingw-runtime")
val skikoWinuiWindowsRuntimePayloadDir = layout.buildDirectory.dir("skiko-winui-windows-runtime")
val skikoWinuiMingwRuntimeAssetPath = "winui-mingw/windows-x64"
val winRTRuntimeAssetsDir = layout.buildDirectory.dir("kotlin-winrt/runtime-assets")
val winuiMingwDebugExecutableDir = layout.buildDirectory.dir("bin/winuiMingw/debugExecutable")
val sampleWindowsAppSdkVersion = "2.2.0"
val sampleWindowsSdkVersion = providers.gradleProperty("skiko.winui.windowsSdkVersion")
    .orElse("10.0.26100.0")

fun Task.removeDependenciesNamed(vararg taskNames: String) {
    val names = taskNames.toSet()
    setDependsOn(dependsOn.filterNot { dependency ->
        when (dependency) {
            is Task -> dependency.name in names
            is TaskProvider<*> -> dependency.name in names
            else -> names.any { name -> dependency.toString().contains(name) }
        }
    })
}

fun localSkikoBuildLibFiles(baseName: String) = provider {
    val libsDir = layout.projectDirectory.dir("../../skiko/build/libs").asFile
    val versioned = libsDir.resolve("$baseName-${skikoWinuiVersion.get()}.jar")
    val plain = libsDir.resolve("$baseName.jar")
    files(if (versioned.isFile) versioned else plain)
}

dependencies {
    if (!skikoWinuiUseLocalProject.get() && !skikoWinuiWindowsRuntimeJarProvider.isPresent) {
        skikoWinuiWindowsRuntimeFiles("io.github.compose-fluent:skiko-winui-windows:${skikoWinuiVersion.get()}")
    }
    if (winuiMingwEnabled.get() && !skikoWinuiUseLocalProject.get() && !skikoWinuiMingwRuntimeJarProvider.isPresent) {
        skikoWinuiMingwRuntimeFiles("io.github.compose-fluent:skiko-winui-mingw-runtime:${skikoWinuiVersion.get()}")
    }
}

kotlin {
    jvm("winuiJvm") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_25)
                }
            }
        }
    }

    if (winuiMingwEnabled.get()) {
        mingwX64("winuiMingw") {
            binaries {
                executable {
                    baseName = "skia-winui-sample"
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        winuiMain {
            dependencies {
                skikoWinuiCommonDependencyNotations.forEach(::implementation)
            }
        }
        val winuiJvmMain by getting {
            dependencies {
                skikoWinuiJvmDependencyNotations.forEach(::implementation)
            }
        }
        if (winuiMingwEnabled.get()) {
            val winuiMingwMain by getting {
                dependencies {
                    skikoWinuiMingwDependencyNotations.forEach(::implementation)
                }
            }
        }
    }
}

extensions.configure<io.github.composefluent.winrt.gradle.WinRTExtension>("winRT") {
    windowsSdk(sampleWindowsSdkVersion.get(), includeExtensions = false, generateProjection = true)
    nugetPackage("Microsoft.WindowsAppSDK", sampleWindowsAppSdkVersion) {
        generateProjection = true
    }
    application {
        mainClass.set("SkiaWinUISample.MainKt")
        console.set(true)
        unpackaged()
    }
    listOf(
        "Microsoft.UI.Dispatching.DispatcherQueue",
        "Microsoft.UI.Dispatching.DispatcherQueueTimer",
        "Microsoft.UI.Input.PointerDeviceType",
        "Microsoft.UI.Input.PointerPointProperties",
        "Microsoft.UI.Input.PointerUpdateKind",
        "Microsoft.UI.Xaml.Application",
        "Microsoft.UI.Xaml.Controls.Grid",
        "Microsoft.UI.Xaml.Controls.SwapChainPanel",
        "Microsoft.UI.Xaml.Controls.UIElementCollection",
        "Microsoft.UI.Xaml.FocusState",
        "Microsoft.UI.Xaml.FrameworkElement",
        "Microsoft.UI.Xaml.HorizontalAlignment",
        "Microsoft.UI.Xaml.IApplicationOverrides",
        "Microsoft.UI.Xaml.IFrameworkElementOverrides",
        "Microsoft.UI.Xaml.IUIElementOverrides",
        "Microsoft.UI.Xaml.Input.CharacterReceivedRoutedEventArgs",
        "Microsoft.UI.Xaml.Input.KeyRoutedEventArgs",
        "Microsoft.UI.Xaml.Input.PointerRoutedEventArgs",
        "Microsoft.UI.Xaml.LaunchActivatedEventArgs",
        "Microsoft.UI.Xaml.Media.MicaBackdrop",
        "Microsoft.UI.Xaml.RoutedEventHandler",
        "Microsoft.UI.Xaml.UIElement",
        "Microsoft.UI.Xaml.VerticalAlignment",
        "Microsoft.UI.Xaml.Window",
        "Microsoft.UI.Xaml.WindowEventArgs",
        "Windows.Foundation.Rect",
        "Windows.Foundation.TypedEventHandler",
        "Windows.System.VirtualKey",
        "Windows.System.VirtualKeyModifiers",
        "Windows.UI.Text.Core.CoreTextCompositionCompletedEventArgs",
        "Windows.UI.Text.Core.CoreTextCompositionStartedEventArgs",
        "Windows.UI.Text.Core.CoreTextEditContext",
        "Windows.UI.Text.Core.CoreTextInputPaneDisplayPolicy",
        "Windows.UI.Text.Core.CoreTextInputScope",
        "Windows.UI.Text.Core.CoreTextLayoutRequestedEventArgs",
        "Windows.UI.Text.Core.CoreTextRange",
        "Windows.UI.Text.Core.CoreTextSelectionRequestedEventArgs",
        "Windows.UI.Text.Core.CoreTextSelectionUpdatingEventArgs",
        "Windows.UI.Text.Core.CoreTextSelectionUpdatingResult",
        "Windows.UI.Text.Core.CoreTextServicesManager",
        "Windows.UI.Text.Core.CoreTextTextRequestedEventArgs",
        "Windows.UI.Text.Core.CoreTextTextUpdatingEventArgs",
        "Windows.UI.Text.Core.CoreTextTextUpdatingResult",
    ).forEach(::type)
}

tasks.matching { it.name == "runWinRTApplicationHost" }.configureEach {
    group = "application"
    description = "Runs the JVM WinUI Skiko sample through the generated native app host."
}

tasks.named<io.github.composefluent.winrt.gradle.BuildWinRTApplicationHostTask>("buildWinRTApplicationHost") {
    val stageWinRTRuntimeAssets = tasks.named<io.github.composefluent.winrt.gradle.StageWinRTRuntimeAssetsTask>("stageWinRTRuntimeAssets")
    runtimeAssetsDirectory.setFrom(stageWinRTRuntimeAssets.flatMap { it.outputDirectory })
    dependsOn(stageWinRTRuntimeAssets)
    runtimeClasspath.from(configurations.named("winuiJvmRuntimeClasspath"))
    val winuiJvmJar = tasks.named<Jar>("winuiJvmJar")
    dependsOn(winuiJvmJar)
    runtimeClasspath.from(winuiJvmJar.flatMap { it.archiveFile })
    if (skikoWinuiUseLocalProject.get()) {
        dependsOn(gradle.includedBuild("skiko").task(":skikoWinuiWindowsRuntimeJar"))
        runtimeClasspath.from(localSkikoBuildLibFiles("skiko-winui-windows"))
    }
}

afterEvaluate {
    tasks.named("buildWinRTApplicationHost").configure {
        removeDependenciesNamed("stageWinRTApplicationPackage")
    }
    tasks.named("stageWinRTRuntimeAssets").configure {
        removeDependenciesNamed(
            "generateWinRTMingwApplicationEntry",
            "generateCompileKotlinWinuiMingwWinRTCompilerAuthoredTypeDetails",
            "validateCompileKotlinWinuiMingwWinRTAuthoredCandidates",
            "validateCompileKotlinWinuiMingwWinRTNativeAuthoringExports",
        )
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        removeDependenciesNamed("generateWinRTMingwApplicationEntry")
    }
}

tasks.register<Copy>("unpackSkikoWinuiMingwRuntime") {
    group = "build"
    description = "Unpacks skiko-winui-mingw-runtime.jar for WinRT application payload staging."
    onlyIf { winuiMingwEnabled.get() }
    if (winuiMingwEnabled.get() && skikoWinuiUseLocalProject.get()) {
        dependsOn(gradle.includedBuild("skiko").task(":skikoWinuiMingwRuntimeJar"))
    }
    val runtimeJar = if (skikoWinuiMingwRuntimeJarProvider.isPresent) {
        skikoWinuiMingwRuntimeJarProvider.map { files(it) }
    } else if (skikoWinuiUseLocalProject.get()) {
        localSkikoBuildLibFiles("skiko-winui-mingw-runtime")
    } else {
        provider { skikoWinuiMingwRuntimeFiles }
    }
    from(runtimeJar.map { files -> files.map { zipTree(it) } })
    into(skikoWinuiMingwRuntimePayloadDir)
}

tasks.register<Copy>("unpackSkikoWinuiWindowsRuntime") {
    group = "build"
    description = "Unpacks skiko-winui-windows.jar for shared ICU data staging."
    if (skikoWinuiUseLocalProject.get() && !skikoWinuiWindowsRuntimeJarProvider.isPresent) {
        dependsOn(gradle.includedBuild("skiko").task(":skikoWinuiWindowsRuntimeJar"))
    }
    val runtimeJar = if (skikoWinuiWindowsRuntimeJarProvider.isPresent) {
        skikoWinuiWindowsRuntimeJarProvider.map { files(it) }
    } else if (skikoWinuiUseLocalProject.get()) {
        localSkikoBuildLibFiles("skiko-winui-windows")
    } else {
        provider { skikoWinuiWindowsRuntimeFiles }
    }
    from(runtimeJar.map { files -> files.map { zipTree(it) } })
    into(skikoWinuiWindowsRuntimePayloadDir)
}

tasks.register("stageSkikoWinuiMingwRuntimeDlls") {
    group = "build"
    description = "Stages skiko-winui mingw runtime DLLs for local executable launch and WinRT app payload."
    onlyIf { winuiMingwEnabled.get() }
    dependsOn("unpackSkikoWinuiMingwRuntime", "unpackSkikoWinuiWindowsRuntime", "stageWinRTRuntimeAssets")
    val runtimeDlls = listOf("skiko_winui.dll", "skiko_winui_skia.dll")
    val sourceFiles = runtimeDlls.map { name ->
        skikoWinuiMingwRuntimePayloadDir.map { it.file("$skikoWinuiMingwRuntimeAssetPath/$name") }
    } + listOf(skikoWinuiWindowsRuntimePayloadDir.map { it.file("icudtl.dat") })
    val outputFiles = runtimeDlls.flatMap { name ->
        listOf(
            winRTRuntimeAssetsDir.map { it.file(name) },
            winuiMingwDebugExecutableDir.map { it.file(name) },
        )
    } + listOf(
        winRTRuntimeAssetsDir.map { it.file("icudtl.dat") },
        winuiMingwDebugExecutableDir.map { it.file("icudtl.dat") },
    )
    inputs.files(sourceFiles)
    outputs.files(outputFiles)
    doLast {
        val destinations = listOf(
            winRTRuntimeAssetsDir.get().asFile,
            winuiMingwDebugExecutableDir.get().asFile,
        )
        destinations.forEach(File::mkdirs)
        sourceFiles.forEach { sourceProvider ->
            val source = sourceProvider.get().asFile
            if (!source.isFile) {
                throw GradleException("skiko-winui mingw runtime file not found: $source")
            }
            destinations.forEach { destination ->
                source.copyTo(destination.resolve(source.name), overwrite = true)
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=kotlin.time.ExperimentalTime",
    )
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
    )
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
    if (name.contains("WinuiMingw")) {
        dependsOn("unpackSkikoWinuiMingwRuntime")
    }
}

tasks.matching { it.name == "runDebugExecutableWinuiMingw" }.configureEach {
    dependsOn("stageSkikoWinuiMingwRuntimeDlls")
}

tasks.register("verifySkikoWinuiMingwSampleRuntime") {
    group = "verification"
    description = "Checks that the built winui-mingw sample executable and runtime files are staged."
    onlyIf { winuiMingwEnabled.get() }
    dependsOn("linkDebugExecutableWinuiMingw", "stageSkikoWinuiMingwRuntimeDlls")
    doLast {
        val executableDir = winuiMingwDebugExecutableDir.get().asFile
        val runtimeAssets = winRTRuntimeAssetsDir.get().asFile
        executableDir
            .listFiles { file -> file.name.endsWith(".exe") || file.name.endsWith(".kexe") }
            ?.singleOrNull()
            ?: throw GradleException(
                "WinUI mingw sample executable is missing in $executableDir. " +
                    "Run linkDebugExecutableWinuiMingw before running the sample."
            )
        listOf("skiko_winui.dll", "skiko_winui_skia.dll", "icudtl.dat").forEach { name ->
            if (!runtimeAssets.resolve(name).isFile && !executableDir.resolve(name).isFile) {
                throw GradleException(
                    "WinUI mingw runtime file $name is missing. " +
                        "Run stageSkikoWinuiMingwRuntimeDlls after building skiko-winui."
                )
            }
        }
    }
}

fun TaskContainer.registerWinuiMingwSampleTask(
    name: String,
    autoExit: Boolean = false,
) = register<Exec>(name) {
    group = if (autoExit) "verification" else "application"
    description = if (autoExit) {
        "Runs the SkiaWinUISample WinUI sample smoke on Kotlin/Native mingw and exits automatically."
    } else {
        "Runs the SkiaWinUISample WinUI sample on Kotlin/Native mingw."
    }
    dependsOn("verifySkikoWinuiMingwSampleRuntime")
    doFirst {
        val executableDir = winuiMingwDebugExecutableDir.get().asFile
        val out = fileTree(executableDir) { include("*.exe", "*.kexe") }
        val executableFile = out.single { it.name.endsWith(".exe") || it.name.endsWith(".kexe") }
        val runtimeAssets = winRTRuntimeAssetsDir.get().asFile
        val path = listOf(
            runtimeAssets.absolutePath,
            executableFile.parentFile.absolutePath,
            System.getenv("PATH").orEmpty(),
        ).joinToString(File.pathSeparator)
        val launcher = buildString {
            appendLine("\$ErrorActionPreference = 'Stop'")
            appendLine("\$env:PATH = '${path.replace("'", "''")}'")
            if (autoExit) {
                appendLine("\$env:SKIKO_WINUI_SAMPLE_AUTO_EXIT = 'true'")
            }
            appendLine("Set-Location -LiteralPath '${runtimeAssets.absolutePath.replace("'", "''")}'")
            appendLine("& '${executableFile.absolutePath.replace("'", "''")}'")
            appendLine("exit \$LASTEXITCODE")
        }
        commandLine("powershell", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", launcher)
    }
}

tasks.registerWinuiMingwSampleTask("runWinuiMingwSample")
tasks.registerWinuiMingwSampleTask("runWinuiMingwSampleSmoke", autoExit = true)
