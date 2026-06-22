@file:Suppress("DEPRECATION", "OPT_IN_USAGE", "UNCHECKED_CAST")
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.gradle.api.Project
import org.gradle.api.provider.Provider

buildscript {
    val kotlinWinRtVersion = providers.gradleProperty("kotlinWinRt.version")
        .orElse("0.1.0-SNAPSHOT")
        .get()
    val kotlinWinRtGroup = providers.gradleProperty("kotlinWinRt.group")
        .orElse("io.github.compose-fluent")
        .get()

    repositories {
        google()
        mavenCentral {
            url = uri("https://cache-redirector.jetbrains.com/maven-central")
        }
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
    }

    dependencies {
        val kotlinVersion = project.property("kotlin.version") as String
        classpath(kotlin("gradle-plugin", version = kotlinVersion))
        classpath("$kotlinWinRtGroup:winrt-gradle-plugin:$kotlinWinRtVersion")
    }
}

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.gradle.apple.applePlugin") version "222.3345.143-0.16"
}

apply(from = "../skiko-winui-sample-dependencies.gradle.kts")
apply(plugin = "io.github.composefluent.winrt")

repositories {
    google()
    maven(layout.projectDirectory.dir("../../build/repo"))
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent {
            snapshotsOnly()
        }
    }
    mavenCentral {
        url = uri("https://cache-redirector.jetbrains.com/maven-central")
    }
    mavenLocal()
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
}

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

val host = "${hostOs}-${hostArch}"
val isWindowsHost = hostOs == "windows"
val skikoWinuiOnlyTargets = providers.gradleProperty("skiko.winui.onlyTargets")
    .map(String::toBoolean)
    .orElse(false)

val isCompositeBuild = extra.properties.getOrDefault("skiko.composite.build", "") == "1"
if (project.hasProperty("skiko.version") && isCompositeBuild) {
    project.logger.warn("skiko.version property has no effect when skiko.composite.build is set")
}

val skikoWinuiCommonDependencyNotations = extra["skikoWinuiCommonDependencyNotations"] as List<Any>
val skikoWinuiJvmDependencyNotations = extra["skikoWinuiJvmDependencyNotations"] as List<Any>
val skikoWinuiMingwDependencyNotations = extra["skikoWinuiMingwDependencyNotations"] as List<Any>
val kotlinWinRtVersion = providers.gradleProperty("kotlinWinRt.version")
    .orElse("0.1.0-SNAPSHOT")
val kotlinWinRtGroup = providers.gradleProperty("kotlinWinRt.group")
    .orElse("io.github.compose-fluent")
val skikoWinuiVersion = providers.gradleProperty("skiko.winui.version")
    .orElse(providers.gradleProperty("skiko.version"))
    .orElse("0.0.0-SNAPSHOT")
val skikoWinuiUseLocalProject = providers.gradleProperty("skiko.winui.useLocalProject")
    .map(String::toBoolean)
    .orElse(false)
val skikoWinuiMingwProjectDependency = if (skikoWinuiUseLocalProject.get()) project(":skiko-winui") else null
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
val skikoWinuiRuntimeAssetsRoot = providers.gradleProperty("skiko.winui.runtimeAssetsRoot")
    .orElse(layout.projectDirectory.dir("../SkiaWinUISample/build/kotlin-winrt/application-package").asFile.absolutePath)
val skikoWinuiMingwRuntimePayloadDir = layout.buildDirectory.dir("skiko-winui-mingw-runtime")
val skikoWinuiWindowsRuntimePayloadDir = layout.buildDirectory.dir("skiko-winui-windows-runtime")
val skikoWinuiMingwRuntimeAssetPath = "winui-mingw/windows-x64"
val winRtRuntimeAssetsDir = layout.buildDirectory.dir("kotlin-winrt/runtime-assets")
val winuiMingwDebugExecutableDir = layout.buildDirectory.dir("bin/winuiMingw/debugExecutable")
val sampleWindowsAppSdkVersion = providers.gradleProperty("skiko.winui.windowsAppSdkVersion")
    .orElse("2.1.3")
val sampleWindowsSdkVersion = providers.gradleProperty("skiko.winui.windowsSdkVersion")
    .orElse("10.0.26100.0")

fun checkWinuiJvmSampleRuntime(project: Project) {
    val runtimeAssetsRoot = project.file(skikoWinuiRuntimeAssetsRoot.get())
    if (!runtimeAssetsRoot.isDirectory) {
        throw GradleException(
            "WinUI runtime assets not found: $runtimeAssetsRoot. " +
                "Run samples/SkiaWinUISample:runWinRtApplicationHost once or set -Pskiko.winui.runtimeAssetsRoot."
        )
    }
}


val skikoWasm by configurations.creating

dependencies {
    skikoWasm(if (isCompositeBuild) {
        // When we build skiko locally, we have no say in setting skiko.version in the included build.
        // That said, it is always built as "0.0.0-SNAPSHOT" and setting any other version is misleading
        // and can create conflict due to incompatibility of skiko runtime and skiko libs
        files(gradle.includedBuild("skiko").projectDir.resolve("./build/libs/skiko-wasm-0.0.0-SNAPSHOT.jar"))
    } else {
        libs.skiko.wasm.runtime
    })
    if (!skikoWinuiUseLocalProject.get() && !skikoWinuiWindowsRuntimeJarProvider.isPresent) {
        skikoWinuiWindowsRuntimeFiles("io.github.compose-fluent:skiko-winui-windows:${skikoWinuiVersion.get()}")
    }
    if (!skikoWinuiUseLocalProject.get() && !skikoWinuiMingwRuntimeJarProvider.isPresent) {
        skikoWinuiMingwRuntimeFiles("io.github.compose-fluent:skiko-winui-mingw-runtime:${skikoWinuiVersion.get()}")
    }
}

val unpackWasmRuntime = tasks.register("unpackWasmRuntime", Copy::class) {
    destinationDir = file("$buildDir/resources/")
    from(skikoWasm.map { zipTree(it) })

    if (isCompositeBuild) {
        dependsOn(gradle.includedBuild("skiko").task(":skikoWasmJar"))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile>().configureEach {
    dependsOn(unpackWasmRuntime)
}

kotlin {
    if (hostOs == "macos") {
        macosX64() {
            configureToLaunchFromXcode()
        }
        macosArm64() {
            configureToLaunchFromXcode()
        }
        iosSimulatorArm64() {
            configureToLaunchFromAppCode()
            configureToLaunchFromXcode()
        }
        tvosX64() {
            configureToLaunchFromAppCode()
            configureToLaunchFromXcode()
        }
        tvosArm64() {
            configureToLaunchFromAppCode()
            configureToLaunchFromXcode() 
        }
        tvosSimulatorArm64() {
            configureToLaunchFromAppCode()
            configureToLaunchFromXcode()
        }
    }

    if (!skikoWinuiOnlyTargets.get()) {
        jvm("awt") {
            compilations.all {
                compileTaskProvider.configure {
                    compilerOptions {
                        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
                    }
                }
            }
        }
    }

    if (isWindowsHost) {
        mingwX64("winuiMingw") {
            binaries {
                executable {
                    baseName = "skiko-winui-clock-sample"
                }
            }
        }
    }

    if (!skikoWinuiOnlyTargets.get()) {
        js(IR) {
            browser {
                commonWebpackConfig {
                    outputFileName = "webApp.js"
                }
            }
            binaries.executable()
        }

        wasmJs {
            browser {
                commonWebpackConfig {
                    outputFileName = "webApp.js"
                }
            }
            binaries.executable()
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.skiko)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
            }
        }

        if (!skikoWinuiOnlyTargets.get()) {
            val awtMain by getting {
                dependsOn(commonMain)
                dependencies {
                    implementation(libs.skiko.awt.runtime)
                }
            }
        }

        val winuiMain by creating {
            dependsOn(commonMain)
            dependencies {
                skikoWinuiCommonDependencyNotations.forEach(::implementation)
            }
        }

        if (!skikoWinuiOnlyTargets.get()) {
            val winuiJvmMain by creating {
                dependsOn(winuiMain)
                dependencies {
                    skikoWinuiJvmDependencyNotations.forEach(::implementation)
                }
            }
        }

        if (isWindowsHost) {
            val winuiMingwMain by getting {
                dependsOn(winuiMain)
                dependencies {
                    skikoWinuiMingwProjectDependency?.let(::implementation)
                    skikoWinuiMingwDependencyNotations.forEach(::implementation)
                }
            }
        }

        if (!skikoWinuiOnlyTargets.get()) {
            val webMain by creating {
                dependsOn(commonMain)
                resources.setSrcDirs(resources.srcDirs)
                resources.srcDirs(unpackWasmRuntime.map { it.destinationDir })
            }

            val jsMain by getting {
                dependsOn(webMain)
            }

            val wasmJsMain by getting {
                dependsOn(webMain)
            }
        }

        if (hostOs == "macos") {
            val nativeMain by creating {
                dependsOn(commonMain)
            }

            val darwinMain by creating {
                dependsOn(nativeMain)
            }

            val macosMain by creating {
                dependsOn(darwinMain)
            }

            val macosX64Main by getting {
                dependsOn(macosMain)
            }
            val macosArm64Main by getting {
                dependsOn(macosMain)
            }
            val uikitMain by creating {
                dependsOn(darwinMain)
            }
            val iosMain by creating {
                dependsOn(uikitMain)
            }
            val iosSimulatorArm64Main by getting {
                dependsOn(iosMain)
            }
            val tvosMain by creating {
                dependsOn(uikitMain)
            }
            val tvosX64Main by getting {
                dependsOn(tvosMain)
            }
            val tvosArm64Main by getting {
                dependsOn(tvosMain)
            }
            val tvosSimulatorArm64Main by getting {
                dependsOn(tvosMain)
            }
        }
    }

    if (!skikoWinuiOnlyTargets.get()) {
        targets.named<KotlinJvmTarget>("awt") {
            compilations.create("winuiJvm") {
                defaultSourceSet.dependsOn(this@kotlin.sourceSets["winuiJvmMain"])
                compileTaskProvider.configure {
                    compilerOptions {
                        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
                    }
                }
            }
        }
    }
}

if (isWindowsHost) {
    extensions.configure<io.github.composefluent.winrt.gradle.WinRtExtension>("winRt") {
        windowsSdk(sampleWindowsSdkVersion.get(), includeExtensions = false, generateProjection = true)
        nugetPackage("Microsoft.WindowsAppSDK", sampleWindowsAppSdkVersion.get()) {
            generateProjection = true
        }
        application {
            mainClass.set("org.jetbrains.skiko.sample.winuiapp.MainKt")
            console.set(true)
            unpackaged()
            packagePayload(skikoWinuiMingwRuntimePayloadDir.map { it.file("$skikoWinuiMingwRuntimeAssetPath/skiko_winui.dll") }, ".")
            packagePayload(skikoWinuiMingwRuntimePayloadDir.map { it.file("$skikoWinuiMingwRuntimeAssetPath/skiko_winui_skia.dll") }, ".")
        }
        listOf(
            "Microsoft.UI.Dispatching.DispatcherQueue",
            "Microsoft.UI.Dispatching.DispatcherQueueHandler",
            "Microsoft.UI.Dispatching.DispatcherQueueTimer",
            "Microsoft.UI.Xaml.HorizontalAlignment",
            "Microsoft.UI.Input.PointerDeviceType",
            "Microsoft.UI.Input.PointerPointProperties",
            "Microsoft.UI.Input.PointerUpdateKind",
            "Microsoft.UI.Xaml.Application",
            "Microsoft.UI.Xaml.Controls.Grid",
            "Microsoft.UI.Xaml.Controls.SwapChainPanel",
            "Microsoft.UI.Xaml.Controls.UIElementCollection",
            "Microsoft.UI.Xaml.FocusState",
            "Microsoft.UI.Xaml.FrameworkElement",
            "Microsoft.UI.Xaml.Input.CharacterReceivedRoutedEventArgs",
            "Microsoft.UI.Xaml.Input.KeyRoutedEventArgs",
            "Microsoft.UI.Xaml.Input.PointerRoutedEventArgs",
            "Microsoft.UI.Xaml.LaunchActivatedEventArgs",
            "Microsoft.UI.Xaml.Media.MicaBackdrop",
            "Microsoft.UI.Xaml.RoutedEventHandler",
            "Microsoft.UI.Xaml.UIElement",
            "Microsoft.UI.Xaml.VerticalAlignment",
            "Microsoft.UI.Xaml.Window",
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
}

if (isWindowsHost) {
    kotlin.sourceSets.named("commonMain") {
        kotlin.exclude("io/github/composefluent/winrt/application/**")
    }
    kotlin.sourceSets.named("winuiMingwMain") {
        kotlin.srcDir(layout.buildDirectory.dir("generated/kotlin-winrt-application-entry/src/commonMain/kotlin"))
    }
    tasks.matching { it.name == "compileKotlinWinuiMingw" }.configureEach {
        dependsOn("generateWinRtMingwApplicationEntry")
    }
}

if (hostOs == "macos") {
    project.tasks.register<Exec>("runIosSim") {
        val device = "iPhone 11"
        workingDir = project.buildDir
        val linkExecutableTaskName = when (host) {
            "macos-x64" -> "linkReleaseExecutableIosX64"
            "macos-arm64" -> "linkReleaseExecutableIosSimulatorArm64"
            else -> throw GradleException("Host OS is not supported")
        }
        val binTask = project.tasks.named(linkExecutableTaskName)
        dependsOn(binTask)
        commandLine = listOf(
            "xcrun",
            "simctl",
            "spawn",
            "--standalone",
            device
        )
        argumentProviders.add {
            val out = fileTree(binTask.get().outputs.files.files.single()) { include("*.kexe") }
            listOf(out.single { it.name.endsWith(".kexe") }.absolutePath)
        }
    }
    project.tasks.register<Exec>("runNative") {
        workingDir = project.buildDir
        val binTask = project.tasks.named("linkDebugExecutable${hostOs.capitalize()}${hostArch.capitalize()}")
        dependsOn(binTask)
        // Hacky approach.
        commandLine = listOf("bash", "-c")
        argumentProviders.add {
            val out = fileTree(binTask.get().outputs.files.files.single()) { include("*.kexe") }
            println("Run $out")
            listOf(out.single { it.name.endsWith(".kexe") }.absolutePath)
        }
    }
}

if (!skikoWinuiOnlyTargets.get()) {
    project.tasks.register<JavaExec>("runAwt") {
        val kotlinTask = project.tasks.named("compileKotlinAwt")
        dependsOn(kotlinTask)
        systemProperty("skiko.fps.enabled", "true")
        systemProperty("skiko.linux.autodpi", "true")
        systemProperty("skiko.hardwareInfo.enabled", "true")
        systemProperty("skiko.win.exception.logger.enabled", "true")
        systemProperty("skiko.win.exception.handler.enabled", "true")
        jvmArgs("-ea")
        System.getProperties().entries
            .associate {
                (it.key as String) to (it.value as String)
            }
            .filterKeys { it.startsWith("skiko.") }
            .forEach { systemProperty(it.key, it.value) }
        mainClass.set("org.jetbrains.skiko.sample.App_awtKt")
        classpath(kotlinTask.get().outputs)
        classpath(kotlin.jvm("awt").compilations["main"].runtimeDependencyFiles)
    }

    fun TaskContainer.registerWinuiSampleRunTask(
        name: String,
        autoExit: Boolean = false,
        dispatcherRepro: String? = null,
    ) = register<JavaExec>(name) {
        group = "application"
        description = if (autoExit) {
            "Runs the AWT-free WinUI Skiko multiplatform sample and exits automatically."
        } else {
            "Runs the AWT-free WinUI Skiko multiplatform sample."
        }
        onlyIf { isWindowsHost }
        val kotlinTask = project.tasks.named("compileWinuiJvmKotlinAwt")
        dependsOn(kotlinTask)
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
            "-ea",
        )
        systemProperty("kotlin.winrt.runtimeAssetsRoot", skikoWinuiRuntimeAssetsRoot.get())
        if (autoExit) {
            systemProperty("skiko.winui.sample.autoExit", "true")
        }
        dispatcherRepro?.let {
            systemProperty("skiko.winui.sample.dispatcherRepro", it)
        }
        mainClass.set("org.jetbrains.skiko.sample.winuiapp.MainKt")
        classpath(kotlinTask.get().outputs)
        classpath(kotlin.jvm("awt").compilations["winuiJvm"].runtimeDependencyFiles)
        doFirst {
            checkWinuiJvmSampleRuntime(project)
        }
    }

    project.tasks.registerWinuiSampleRunTask("runWinui")
    project.tasks.registerWinuiSampleRunTask("runWinuiSmoke", autoExit = true)
    project.tasks.registerWinuiSampleRunTask("runWinuiTimerSmoke", autoExit = true, dispatcherRepro = "timer")
    project.tasks.registerWinuiSampleRunTask("runWinuiHandlerSmoke", autoExit = true, dispatcherRepro = "handler")
}

tasks.register<Copy>("unpackSkikoWinuiMingwRuntime") {
    group = "build"
    description = "Unpacks skiko-winui-mingw-runtime.jar for WinRT application payload staging."
    onlyIf { isWindowsHost }
    if (skikoWinuiUseLocalProject.get()) {
        dependsOn(":skiko-winui:skikoWinuiMingwRuntimeJar")
    }
    val runtimeJar = if (skikoWinuiMingwRuntimeJarProvider.isPresent) {
        skikoWinuiMingwRuntimeJarProvider.map { files(it) }
    } else if (skikoWinuiUseLocalProject.get()) {
        provider { files(layout.projectDirectory.file("../../skiko/skiko-winui/build/libs/skiko-winui-mingw-runtime.jar")) }
    } else {
        provider { skikoWinuiMingwRuntimeFiles }
    }
    from(runtimeJar.map { files -> files.map { zipTree(it) } })
    into(skikoWinuiMingwRuntimePayloadDir)
}

tasks.register<Copy>("unpackSkikoWinuiWindowsRuntime") {
    group = "build"
    description = "Unpacks skiko-winui-windows.jar for shared ICU data staging."
    onlyIf { isWindowsHost }
    if (skikoWinuiUseLocalProject.get() && !skikoWinuiWindowsRuntimeJarProvider.isPresent) {
        dependsOn(":skiko-winui:skikoWinuiWindowsRuntimeJar")
    }
    val runtimeJar = if (skikoWinuiWindowsRuntimeJarProvider.isPresent) {
        skikoWinuiWindowsRuntimeJarProvider.map { files(it) }
    } else if (skikoWinuiUseLocalProject.get()) {
        provider { files(layout.projectDirectory.file("../../skiko/skiko-winui/build/libs/skiko-winui-windows.jar")) }
    } else {
        provider { skikoWinuiWindowsRuntimeFiles }
    }
    from(runtimeJar.map { files -> files.map { zipTree(it) } })
    into(skikoWinuiWindowsRuntimePayloadDir)
}

tasks.matching { it.name == "stageWinRtRuntimeAssets" }.configureEach {
    dependsOn("unpackSkikoWinuiMingwRuntime")
}

tasks.register("stageSkikoWinuiMingwRuntimeDlls") {
    group = "build"
    description = "Stages skiko-winui mingw runtime DLLs for local executable launch and WinRT app payload."
    onlyIf { isWindowsHost }
    dependsOn("unpackSkikoWinuiMingwRuntime", "unpackSkikoWinuiWindowsRuntime", "stageWinRtRuntimeAssets")
    val runtimeDlls = listOf("skiko_winui.dll", "skiko_winui_skia.dll")
    val sourceFiles = runtimeDlls.map { name ->
        skikoWinuiMingwRuntimePayloadDir.map { it.file("$skikoWinuiMingwRuntimeAssetPath/$name") }
    } + listOf(skikoWinuiWindowsRuntimePayloadDir.map { it.file("icudtl.dat") })
    val outputFiles = runtimeDlls.flatMap { name ->
        listOf(
            winRtRuntimeAssetsDir.map { it.file(name) },
            winuiMingwDebugExecutableDir.map { it.file(name) },
        )
    } + listOf(
        winRtRuntimeAssetsDir.map { it.file("icudtl.dat") },
        winuiMingwDebugExecutableDir.map { it.file("icudtl.dat") },
    )
    inputs.files(sourceFiles)
    outputs.files(outputFiles)
    doLast {
        val destinations = listOf(
            winRtRuntimeAssetsDir.get().asFile,
            winuiMingwDebugExecutableDir.get().asFile,
        )
        destinations.forEach(File::mkdirs)
        sourceFiles.forEach { sourceProvider ->
            val source = sourceProvider.get().asFile
            if (!source.isFile) {
                throw GradleException("skiko-winui mingw runtime DLL not found: $source")
            }
            destinations.forEach { destination ->
                source.copyTo(destination.resolve(source.name), overwrite = true)
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
    if (name.contains("WinuiMingw")) {
        dependsOn("unpackSkikoWinuiMingwRuntime")
    }
}

tasks.matching { it.name == "runDebugExecutableWinuiMingw" }.configureEach {
    dependsOn("stageSkikoWinuiMingwRuntimeDlls")
}

tasks.register("verifySkikoWinuiMingwClockRuntime") {
    group = "verification"
    description = "Checks that the previously built winui-mingw clock sample executable and runtime files are staged."
    onlyIf { isWindowsHost }
    doLast {
        val executableDir = winuiMingwDebugExecutableDir.get().asFile
        val runtimeAssets = winRtRuntimeAssetsDir.get().asFile
        val executable = executableDir
            .listFiles { file -> file.name.endsWith(".exe") || file.name.endsWith(".kexe") }
            ?.singleOrNull()
            ?: throw GradleException(
                "WinUI mingw clock sample executable is missing in $executableDir. " +
                    "Run linkDebugExecutableWinuiMingw before running the clock sample."
            )
        listOf("skiko_winui.dll", "skiko_winui_skia.dll", "icudtl.dat").forEach { name ->
            if (!runtimeAssets.resolve(name).isFile && !executable.parentFile.resolve(name).isFile) {
                throw GradleException(
                    "WinUI mingw runtime file $name is missing. " +
                        "Run stageSkikoWinuiMingwRuntimeDlls after building skiko-winui."
                )
            }
        }
    }
}

fun TaskContainer.registerWinuiMingwClockSampleTask(
    name: String,
    autoExit: Boolean = false,
    dispatcherRepro: String? = null,
) = register<Exec>(name) {
    group = if (autoExit) "verification" else "application"
    description = if (autoExit) {
        "Runs the SkiaMultiplatformSample WinUI clock sample smoke on Kotlin/Native mingw and exits automatically."
    } else {
        "Runs the SkiaMultiplatformSample WinUI clock sample on Kotlin/Native mingw."
    }
    onlyIf { isWindowsHost }
    dependsOn("verifySkikoWinuiMingwClockRuntime")
    doFirst {
        val executableDir = winuiMingwDebugExecutableDir.get().asFile
        val out = fileTree(executableDir) { include("*.exe", "*.kexe") }
        val executableFile = out.single { it.name.endsWith(".exe") || it.name.endsWith(".kexe") }
        val runtimeAssets = winRtRuntimeAssetsDir.get().asFile
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
            dispatcherRepro?.let {
                appendLine("\$env:SKIKO_WINUI_SAMPLE_DISPATCHER_REPRO = '${it.replace("'", "''")}'")
            }
            appendLine("Set-Location -LiteralPath '${runtimeAssets.absolutePath.replace("'", "''")}'")
            appendLine("& '${executableFile.absolutePath.replace("'", "''")}'")
            appendLine("exit \$LASTEXITCODE")
        }
        commandLine("powershell", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", launcher)
    }
}

tasks.registerWinuiMingwClockSampleTask("runWinuiMingwClockSample")
tasks.registerWinuiMingwClockSampleTask(
    name = "runWinuiMingwClockSampleSmoke",
    autoExit = true,
    dispatcherRepro = "timer",
)


enum class Target(val simulator: Boolean, val key: String) {
    WATCHOS_X86(true, "watchos"), 
    WATCHOS_ARM64(false, "watchos"),
    IOS_X64(true, "iosX64"),
    IOS_ARM64(false, "iosArm64"), 
    IOS_SIMULATOR_ARM64(true, "iosSimulatorArm64"),
    TVOS_X64(true, "tvosX64"),
    TVOS_ARM64(true, "tvosArm64"),
    TVOS_SIMULATOR_ARM64(true, "tvosSimulatorArm64"),
}


if (hostOs == "macos") {
// Create Xcode integration tasks.
    val sdkName: String? = System.getenv("SDK_NAME")

    println("Configuring XCode for $sdkName")
    val target = sdkName.orEmpty().let {
        when {
            it.startsWith("iphoneos") -> Target.IOS_ARM64
            it.startsWith("appletvsimulator") -> when (host) {
                "macos-x64" -> Target.TVOS_X64
                "macos-arm64" -> Target.TVOS_SIMULATOR_ARM64
                else -> throw GradleException("Host OS is not supported")
            }
            it.startsWith("appletvos") -> Target.TVOS_ARM64
            it.startsWith("watchos") -> Target.WATCHOS_ARM64
            it.startsWith("watchsimulator") -> Target.WATCHOS_X86
            else -> when (host) {
                "macos-x64" -> Target.IOS_X64
                "macos-arm64" -> Target.IOS_SIMULATOR_ARM64
                else -> throw GradleException("Host OS is not supported")
            }
        }
    }

    val targetBuildDir: String? = System.getenv("TARGET_BUILD_DIR")
    val executablePath: String? = System.getenv("EXECUTABLE_PATH")
    val buildType = System.getenv("CONFIGURATION")?.let {
        org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.valueOf(it.uppercase())
    } ?: org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG

    val currentTarget = kotlin.targets[target.key] as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
    val kotlinBinary = currentTarget.binaries.getExecutable(buildType)
    val xcodeIntegrationGroup = "Xcode integration"

    val packForXCode = if (sdkName == null || targetBuildDir == null || executablePath == null) {
        // The build is launched not by Xcode ->
        // We cannot create a copy task and just show a meaningful error message.
        tasks.create("packForXCode").doLast {
            throw IllegalStateException("Please run the task from Xcode")
        }
    } else {
        // Otherwise copy the executable into the Xcode output directory.
        tasks.create("packForXCode", Copy::class.java) {
            dependsOn(kotlinBinary.linkTaskProvider)
            
            println("Packing for XCode: ${kotlinBinary.target}")

            destinationDir = file(targetBuildDir)

            val dsymSource = kotlinBinary.outputFile.absolutePath + ".dSYM"
            val dsymDestination = File(executablePath).parentFile.name + ".dSYM"
            val oldExecName = kotlinBinary.outputFile.name
            val newExecName = File(executablePath).name

            from(dsymSource) {
                into(dsymDestination)
                rename(oldExecName, newExecName)
            }

            from(kotlinBinary.outputFile) {
                rename { executablePath }
            }
        }
    }
}

if (!skikoWinuiOnlyTargets.get()) {
    apple {
        iosApp {
            productName = "SkikoAppCode"
            sceneDelegateClass = "SceneDelegate"
            dependencies {
                implementation(project(":"))
            }
        }
    }
}

fun KotlinNativeTarget.configureToLaunchFromAppCode() {
    binaries {
        framework {
            baseName = "shared"
            freeCompilerArgs += listOf(
                "-linker-option", "-framework", "-linker-option", "Metal",
                "-linker-option", "-framework", "-linker-option", "CoreText",
                "-linker-option", "-framework", "-linker-option", "CoreGraphics"
            )
        }
    }
}

fun KotlinNativeTarget.configureToLaunchFromXcode() {
    binaries {
        executable {
            entryPoint = "org.jetbrains.skiko.sample.main"
            freeCompilerArgs += listOf(
                "-linker-option", "-framework", "-linker-option", "Metal",
                "-linker-option", "-framework", "-linker-option", "CoreText",
                "-linker-option", "-framework", "-linker-option", "CoreGraphics"
            )
        }
    }
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-opt-in=kotlinx.cinterop.ExperimentalForeignApi")
}
