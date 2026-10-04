@file:Suppress("DEPRECATION", "OPT_IN_USAGE", "UNCHECKED_CAST")
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.api.DefaultTask
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import io.github.composefluent.windows.toolkit.gradle.WindowsExtension
import io.github.composefluent.windows.toolkit.gradle.WindowsPackageType
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

plugins {
    kotlin("multiplatform")
    id("io.github.compose-fluent.windows-toolkit")
    id("org.jetbrains.gradle.apple.applePlugin") version "222.3345.143-0.16"
}

apply(from = "../skiko-winui-sample-dependencies.gradle.kts")

repositories {
    google()
    maven(layout.projectDirectory.dir("../../skiko/build/repo"))
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

val skikoWinuiCommonDependencyNotations = extra["skikoWinuiCommonDependencyNotations"] as List<Any>
val skikoWinuiJvmDependencyNotations = extra["skikoWinuiJvmDependencyNotations"] as List<Any>
val skikoWinuiMingwDependencyNotations = extra["skikoWinuiMingwDependencyNotations"] as List<Any>
val skikoWinuiVersion = providers.gradleProperty("skiko.winui.version")
    .orElse(providers.gradleProperty("skiko.version"))
    .orElse("0.0.0-SNAPSHOT")
val skikoWinuiUseLocalProject = providers.gradleProperty("skiko.winui.useLocalProject")
    .map(String::toBoolean)
    .orElse(false)
val skikoWinuiMingwProjectDependency: Any? = null
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
    .orElse(
        layout.projectDirectory
            .dir("../SkiaWinUISample/build/kotlin-winrt/application-layout/winuiJvm_main/package")
            .asFile.absolutePath
    )
val skikoWinuiMingwRuntimePayloadDir = layout.buildDirectory.dir("skiko-winui-mingw-runtime")
val skikoWinuiWindowsRuntimePayloadDir = layout.buildDirectory.dir("skiko-winui-windows-runtime")
val skikoWinuiMingwRuntimeAssetPath = "winui-mingw/windows-x64"
val winuiMingwExecutableBaseName = "skiko-winui-clock-sample"
// The toolkit plugin stages one application layout per Kotlin variant.
val winuiMingwDebugLayoutDir =
    layout.buildDirectory.dir("kotlin-winrt/application-layout/winuiMingw_main_debugExecutable/package")
val sampleWindowsAppSdkVersion = "2.2.0"
val sampleWindowsSdkVersion = providers.gradleProperty("skiko.winui.windowsSdkVersion")
    .orElse("10.0.26100.0")
val kotlinWinRTVersion = providers.gradleProperty("kotlinWinRT.version")
    .orElse("0.1.0-SNAPSHOT")
val kotlinWinRTGroup = providers.gradleProperty("kotlinWinRT.group")
    .orElse("io.github.compose-fluent")
// skiko-winui-mingw links against the Skia bridge DLL, and Skia loads its ICU data from the
// executable directory, so both have to be part of the mingw application layout.
val skikoWinuiMingwRuntimeAssets = listOf("skiko_winui.dll", "skiko_winui_skia.dll").map { name ->
    skikoWinuiMingwRuntimePayloadDir.map { it.file("$skikoWinuiMingwRuntimeAssetPath/$name") }
} + listOf(skikoWinuiWindowsRuntimePayloadDir.map { it.file("icudtl.dat") })

fun checkWinuiJvmSampleRuntime(project: Project) {
    val runtimeAssetsRoot = project.file(skikoWinuiRuntimeAssetsRoot.get())
    if (!runtimeAssetsRoot.isDirectory) {
        throw GradleException(
            "WinUI runtime assets not found: $runtimeAssetsRoot. " +
                "Run samples/SkiaWinUISample:runWinAppHostWinuiJvmMain once or set -Pskiko.winui.runtimeAssetsRoot."
        )
    }
}


dependencies {
    if (!skikoWinuiUseLocalProject.get() && !skikoWinuiWindowsRuntimeJarProvider.isPresent) {
        skikoWinuiWindowsRuntimeFiles("io.github.compose-fluent:skiko-winui-windows:${skikoWinuiVersion.get()}")
    }
    if (!skikoWinuiUseLocalProject.get() && !skikoWinuiMingwRuntimeJarProvider.isPresent) {
        skikoWinuiMingwRuntimeFiles("io.github.compose-fluent:skiko-winui-mingw-runtime:${skikoWinuiVersion.get()}")
    }
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
                    baseName = winuiMingwExecutableBaseName
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

        winuiMain {
            dependencies {
                skikoWinuiCommonDependencyNotations.forEach(::implementation)
            }
        }

        if (!skikoWinuiOnlyTargets.get()) {
            val winuiJvmMain by creating {
                dependencies {
                    skikoWinuiJvmDependencyNotations.forEach(::implementation)
                }
            }
        }

        if (isWindowsHost) {
            val winuiMingwMain by getting {
                dependencies {
                    skikoWinuiMingwProjectDependency?.let(::implementation)
                    skikoWinuiMingwDependencyNotations.forEach(::implementation)
                }
            }
            // The toolkit plugin leaves its standalone Native projection compilation without the
            // kotlin-winrt runtime KLIBs outside its own build, and without external WinRT libraries.
            matching { it.name == "winuiMingwWinRTProjection" }.configureEach {
                dependencies {
                    implementation("${kotlinWinRTGroup.get()}:winrt-runtime:${kotlinWinRTVersion.get()}")
                    implementation("${kotlinWinRTGroup.get()}:winrt-authoring:${kotlinWinRTVersion.get()}")
                    skikoWinuiCommonDependencyNotations.forEach(::implementation)
                }
            }
        }

        if (!skikoWinuiOnlyTargets.get()) {
            val webMain by creating {
                dependsOn(commonMain)
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

    targets.withType<KotlinJsIrTarget>().all { configureSkikoWebRuntime(project, this) }

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
    // The toolkit plugin does not regenerate the WinRT types that skiko-winui already projects, but
    // it only puts project dependencies on the classpath of its projection compilations.
    configurations.matching {
        it.name.startsWith("kotlinWinRTProjection") && it.name.endsWith("CompileClasspath")
    }.configureEach {
        skikoWinuiCommonDependencyNotations.forEach { notation ->
            dependencies.add(project.dependencies.create(notation))
        }
    }

    extensions.configure<WindowsExtension>("windows") {
        application {
            mainClass.set("org.jetbrains.skiko.sample.winuiapp.MainKt")
            console.set(true)
            // Loose layout that carries its own Windows App SDK runtime.
            packageType.set(WindowsPackageType.None)
            selfContained()
            skikoWinuiMingwRuntimeAssets.forEach { runtimeAsset(it.get().asFile) }
        }
        packageReferences {
            windowsSdk(sampleWindowsSdkVersion.get(), includeExtensions = false, generateProjection = true)
            nugetPackage("Microsoft.WindowsAppSDK", sampleWindowsAppSdkVersion) {
                generateProjection = true
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

    // The declared runtime assets come out of the unpacked skiko-winui runtime jars.
    tasks.matching { it.name.startsWith("stageWindowsPackageRuntimeAssets") }.configureEach {
        dependsOn("unpackSkikoWinuiMingwRuntime", "unpackSkikoWinuiWindowsRuntime")
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
        dependsOn(gradle.includedBuild("skiko").task(":skikoWinuiMingwRuntimeJar"))
    }
    val runtimeJar = if (skikoWinuiMingwRuntimeJarProvider.isPresent) {
        skikoWinuiMingwRuntimeJarProvider.map { files(it) }
    } else if (skikoWinuiUseLocalProject.get()) {
        provider { files(layout.projectDirectory.file("../../skiko/build/libs/skiko-winui-mingw-runtime.jar")) }
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
        dependsOn(gradle.includedBuild("skiko").task(":skikoWinuiWindowsRuntimeJar"))
    }
    val runtimeJar = if (skikoWinuiWindowsRuntimeJarProvider.isPresent) {
        skikoWinuiWindowsRuntimeJarProvider.map { files(it) }
    } else if (skikoWinuiUseLocalProject.get()) {
        provider { files(layout.projectDirectory.file("../../skiko/build/libs/skiko-winui-windows.jar")) }
    } else {
        provider { skikoWinuiWindowsRuntimeFiles }
    }
    from(runtimeJar.map { files -> files.map { zipTree(it) } })
    into(skikoWinuiWindowsRuntimePayloadDir)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
    if (name.contains("WinuiMingw")) {
        dependsOn("unpackSkikoWinuiMingwRuntime")
    }
}

tasks.register("verifySkikoWinuiMingwClockRuntime") {
    group = "verification"
    description = "Checks that the staged winui-mingw clock sample layout has the executable and the skiko-winui runtime files."
    onlyIf { isWindowsHost }
    if (isWindowsHost) {
        dependsOn("stageWinAppPackageWinuiMingwMainDebugExecutable")
    }
    doLast {
        val layoutDir = winuiMingwDebugLayoutDir.get().asFile
        layoutDir.resolve("$winuiMingwExecutableBaseName.exe")
            .takeIf(File::isFile)
            ?: throw GradleException(
                "WinUI mingw clock sample executable is missing in $layoutDir. " +
                    "Run stageWinAppPackageWinuiMingwMainDebugExecutable before running the clock sample."
            )
        skikoWinuiMingwRuntimeAssets.map { it.get().asFile.name }.forEach { name ->
            if (!layoutDir.resolve(name).isFile) {
                throw GradleException(
                    "WinUI mingw runtime file $name is missing in $layoutDir. " +
                        "Build skiko-winui before staging the sample."
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
        val layoutDir = winuiMingwDebugLayoutDir.get().asFile
        val executableFile = layoutDir.resolve("$winuiMingwExecutableBaseName.exe")
        workingDir(layoutDir)
        if (autoExit) {
            environment("SKIKO_WINUI_SAMPLE_AUTO_EXIT", "true")
        }
        dispatcherRepro?.let {
            environment("SKIKO_WINUI_SAMPLE_DISPATCHER_REPRO", it)
        }
        commandLine(executableFile.absolutePath)
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

private fun configureSkikoWebRuntime(
    project: Project,
    target: KotlinJsIrTarget,
) {
    val titledTargetName = target.name.replaceFirstChar { it.titlecase() }
    val mainCompilation = target.compilations.findByName(KotlinCompilation.MAIN_COMPILATION_NAME)!!
    val runtimeDepsConfig = project.configurations.findByName(mainCompilation.runtimeDependencyConfigurationName)!!
    val skikoWebRuntimeJarFiles = runtimeDepsConfig.incoming.artifactView {
        @Suppress("UnstableApiUsage")
        withVariantReselection()
        attributes {
            runtimeDepsConfig.attributes.keySet().forEach {
                @Suppress("UNCHECKED_CAST")
                attribute(it as Attribute<Any>, runtimeDepsConfig.attributes.getAttribute(it) as Any)
            }
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, "skiko-runtime"))
        }
    }.files
    val unpackedRuntimeDir = project.layout.buildDirectory.dir("compose/skiko-${target.name}-runtime")

    val unpackRuntime = project.tasks.register(
        "unpackSkikoRuntimeFor$titledTargetName",
        UnpackSkikoRuntimeTask::class.java,
    ) {
        runtimeFiles.from(skikoWebRuntimeJarFiles)
        outputDirectory.set(unpackedRuntimeDir)
    }

    target.compilations.all {
        if (target.wasmTargetType != null) {
            binaries.all {
                linkSyncTask.configure {
                    dependsOn(unpackRuntime)
                    from.from(unpackedRuntimeDir)
                }
            }
        } else {
            project.tasks.named(processResourcesTaskName, ProcessResources::class.java) {
                from(unpackedRuntimeDir)
                dependsOn(unpackRuntime)
                exclude("META-INF")
            }
        }
    }
}

@CacheableTask
abstract class UnpackSkikoRuntimeTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:javax.inject.Inject
    abstract val archiveOperations: ArchiveOperations

    @get:javax.inject.Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun unpack() {
        fileSystemOperations.copy {
            from(runtimeFiles.files.map(archiveOperations::zipTree))
            into(outputDirectory)
            exclude("META-INF/**")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}
