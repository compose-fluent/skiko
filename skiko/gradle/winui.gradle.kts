@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import io.github.composefluent.windows.toolkit.gradle.GenerateWinRTProjectionsTask
import io.github.composefluent.windows.toolkit.gradle.KotlinWindowsToolkitPlugin
import io.github.composefluent.windows.toolkit.gradle.WindowsExtension
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import tasks.configuration.generateVersion
import java.security.MessageDigest

buildscript {
    val kotlinWinRTVersion = providers.gradleProperty("kotlinWinRT.version")
        .orElse("0.1.0-SNAPSHOT")
        .get()
    val kotlinWinRTGroup = providers.gradleProperty("kotlinWinRT.group")
        .orElse("io.github.compose-fluent")
        .get()

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
    dependencies {
        classpath("$kotlinWinRTGroup:windows-toolkit-gradle-plugin:$kotlinWinRTVersion")
    }
}

apply<KotlinWindowsToolkitPlugin>()

// The Windows App SDK components that skiko-winui uses. Microsoft.WindowsAppSDK itself is a
// metapackage that references every component (AI, ML, Widgets, ...), and a self-contained
// application stages the runtime of each package that any library declares: the components
// keep that to what WinUI needs. WinUI brings Foundation and InteractiveExperiences (the
// Microsoft.UI.* namespaces); DWriteCore is the text engine of WinUI 3.
val winuiWindowsAppSdkWinUiVersion = "2.2.1"
val winuiWindowsAppSdkDWriteVersion = "2.1.0"
val winuiWindowsSdkVersion = providers.gradleProperty("skiko.winui.windowsSdkVersion")
    .orElse("10.0.26100.0")
val winuiWindowsSdkRoot = providers.gradleProperty("skiko.winui.windowsSdkRoot")
    .orElse(providers.environmentVariable("WindowsSdkDir"))
    .orElse(providers.environmentVariable("WINDOWSSDKDIR"))
val winuiWindowsSkiaDir = providers.gradleProperty("skiko.winui.windowsSkiaDir")
val winuiMingwNativeArchive = layout.buildDirectory.file("native/winuiMingw/windowsX64/skiko-winui-mingw-windows-x64.a")
val winuiMingwSkikoBridgeDll = layout.buildDirectory.file("native/winuiMingwSkiko/windowsX64/skiko_winui_skia.dll")
val winuiMingwSkikoBridgeImportLib = layout.buildDirectory.file("native/winuiMingwSkiko/windowsX64/skiko_winui_skia.lib")
val winuiMingwSharedLibOutputDir = layout.buildDirectory.dir("bin/winuiMingw/releaseShared")
val winuiMingwTestOutputDir = layout.buildDirectory.dir("bin/winuiMingw/debugTest")
val winuiMingwTestIcuData = providers.provider {
    winuiWindowsSkiaDir.orNull
        ?.let(::File)
        ?.resolve("out/Release-windows-x64/icudtl.dat")
        ?.takeIf(File::isFile)
        ?: fileTree(layout.projectDirectory.dir("dependencies/skia")) {
            include("**/out/Release-windows-x64/icudtl.dat")
        }.files.firstOrNull()
        ?: throw GradleException("Skia Windows icudtl.dat was not found for winui-mingw tests.")
}
val winuiMingwRuntimeResourceDir = layout.buildDirectory.dir("generated/winuiMingwRuntimeResources")
val winuiMingwRuntimeResourcePath = "winui-mingw/windows-x64"

fun windowsSdkRootFromRegistry(): File? {
    val keys = listOf(
        "HKLM\\SOFTWARE\\Microsoft\\Windows Kits\\Installed Roots",
        "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows Kits\\Installed Roots",
    )
    return keys.firstNotNullOfOrNull { key ->
        runCatching {
            val process = ProcessBuilder("reg", "query", key, "/v", "KitsRoot10")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) return@runCatching null
            output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("KitsRoot10") }
                ?.split(Regex("\\s+"), limit = 3)
                ?.getOrNull(2)
                ?.let(::File)
                ?.takeIf(File::isDirectory)
        }.getOrNull()
    }
}

fun windowsSdkRoot(): File =
    winuiWindowsSdkRoot.orNull
        ?.let(::File)
        ?.takeIf(File::isDirectory)
        ?: windowsSdkRootFromRegistry()
        ?: throw GradleException(
            "Windows SDK root was not found. Set -Pskiko.winui.windowsSdkRoot=<Windows Kits 10 root> " +
                "or install a Windows SDK with KitsRoot10 registered."
        )

fun windowsSdkLibDir(version: String): File =
    windowsSdkRoot().resolve("Lib/$version/um/x64")

fun windowsSdkSystemLibFiles(version: String): List<File> {
    val libDir = windowsSdkLibDir(version)
    val requiredLibs = listOf(
        "d3d12.lib",
        "dxgi.lib",
        "dxguid.lib",
        "user32.lib",
        "comctl32.lib",
        "ole32.lib",
    )
    val missingLibs = requiredLibs
        .map { libDir.resolve(it) }
        .filterNot(File::isFile)
    if (missingLibs.isNotEmpty()) {
        throw GradleException("Missing Windows SDK import libraries:\n${missingLibs.joinToString("\n")}")
    }
    return requiredLibs.map { libDir.resolve(it) }
}

fun windowsSdkSystemLibArgs(version: String): List<String> =
    windowsSdkSystemLibFiles(version)
        .flatMap { listOf("-linker-option", it.absolutePath) }

fun winuiMingwSkikoBridgeLinkArgs(): List<String> =
    listOf("-linker-option", winuiMingwSkikoBridgeImportLib.get().asFile.absolutePath)

fun File.cinteropPath(): String =
    absolutePath.replace(File.separatorChar, '/')

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

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

val skikoVersion = providers.gradleProperty("skiko.version")
    .orElse("0.0.0-SNAPSHOT")
val winuiMingwEnabled = providers.gradleProperty("skiko.winui.mingw.enabled")
    .map(String::toBoolean)
    .orElse(true)
val winuiMingwSkikoBridgeCInteropDef = layout.buildDirectory.file("cinterop/winuiMingw/winuiMingwSkiaBridge.def")
val winuiMingwSkikoBridgeCInteropHeader = layout.buildDirectory.file("cinterop/winuiMingw/winuiMingwSkiaBridge.h")
val winuiMingwSkikoBridgeCInteropLibDir = layout.buildDirectory.dir("cinterop/winuiMingw/libs")
val winuiJvmTarget = providers.gradleProperty("skiko.winui.jvmTarget")
    .orElse("25")
val winuiJvmToolchain = providers.gradleProperty("skiko.winui.jvmToolchain")
    .orElse(winuiJvmTarget)
val winuiSkikoProperties = SkikoProperties(rootProject)
val skipProjectionGeneration = providers.gradleProperty("skiko.winui.skipProjectionGeneration")
    .map(String::toBoolean)
    .orElse(false)
val kotlinWinRTAuthoringScannerRuntime = configurations.detachedConfiguration(
    dependencies.create("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.0")
)
val kotlinWinRTArtifactVersion = providers.gradleProperty("kotlinWinRT.version")
    .orElse("0.1.0-SNAPSHOT")
val kotlinWinRTArtifactGroup = providers.gradleProperty("kotlinWinRT.group")
    .orElse("io.github.compose-fluent")
val winuiProjectionTypes = listOf(
    "Microsoft.UI.Xaml.Application",
    "Microsoft.UI.Xaml.FrameworkElement",
    "Microsoft.UI.Xaml.IApplicationOverrides",
    "Microsoft.UI.Xaml.IFrameworkElementOverrides",
    "Microsoft.UI.Xaml.IUIElementOverrides",
    "Microsoft.UI.Xaml.LaunchActivatedEventArgs",
    "Microsoft.UI.Xaml.RoutedEventArgs",
    "Microsoft.UI.Xaml.RoutedEventHandler",
    "Microsoft.UI.Xaml.SizeChangedEventArgs",
    "Microsoft.UI.Xaml.SizeChangedEventHandler",
    "Microsoft.UI.Xaml.UIElement",
    "Microsoft.UI.Xaml.Window",
    "Microsoft.UI.Xaml.WindowActivatedEventArgs",
    "Microsoft.UI.Xaml.WindowActivationState",
    "Microsoft.UI.Xaml.Media.CompositionTarget",
    "Microsoft.UI.Xaml.Media.MicaBackdrop",
    "Microsoft.UI.Xaml.Automation.AutomationProperties",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationControlType",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationEvents",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationNavigationDirection",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationOrientation",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationPeer",
    "Microsoft.UI.Xaml.Automation.Peers.IAutomationPeerOverrides",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationStructureChangeType",
    "Microsoft.UI.Xaml.Automation.Peers.AccessibilityView",
    "Microsoft.UI.Xaml.Automation.Peers.FrameworkElementAutomationPeer",
    "Microsoft.UI.Xaml.Automation.Peers.PatternInterface",
    "Microsoft.UI.Xaml.Controls.Grid",
    "Microsoft.UI.Xaml.Controls.Panel",
    "Microsoft.UI.Xaml.Controls.SwapChainPanel",
    "Microsoft.UI.Xaml.Controls.UIElementCollection",
    "Microsoft.UI.Dispatching.DispatcherQueue",
    "Microsoft.UI.Dispatching.DispatcherQueueTimer",
    "Windows.Foundation.Size",
    "Windows.UI.Text.Core.CoreTextCompositionCompletedEventArgs",
    "Windows.UI.Text.Core.CoreTextCompositionStartedEventArgs",
    "Windows.UI.Text.Core.CoreTextEditContext",
    "Windows.UI.Text.Core.CoreTextFormatUpdatingEventArgs",
    "Windows.UI.Text.Core.CoreTextFormatUpdatingReason",
    "Windows.UI.Text.Core.CoreTextInputPaneDisplayPolicy",
    "Windows.UI.Text.Core.CoreTextInputScope",
    "Windows.UI.Text.Core.CoreTextLayoutRequest",
    "Windows.UI.Text.Core.CoreTextLayoutRequestedEventArgs",
    "Windows.UI.Text.Core.CoreTextRange",
    "Windows.UI.Text.Core.CoreTextSelectionRequest",
    "Windows.UI.Text.Core.CoreTextSelectionRequestedEventArgs",
    "Windows.UI.Text.Core.CoreTextServicesManager",
    "Windows.UI.Text.Core.CoreTextTextRequest",
    "Windows.UI.Text.Core.CoreTextTextRequestedEventArgs",
    "Windows.UI.Text.Core.CoreTextTextUpdatingEventArgs",
    "Windows.UI.Xaml.Interop.NotifyCollectionChangedAction",
    "Windows.UI.Xaml.Interop.Type",
)

repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent {
            snapshotsOnly()
        }
    }
    mavenCentral()
    google()
}

extensions.configure<WindowsExtension>("windows") {
    packageReferences {
        windowsSdk(winuiWindowsSdkVersion.get(), includeExtensions = false, generateProjection = true)
        nugetPackage("Microsoft.WindowsAppSDK.WinUI", winuiWindowsAppSdkWinUiVersion) {
            generateProjection = true
        }
        nugetPackage("Microsoft.WindowsAppSDK.DWrite", winuiWindowsAppSdkDWriteVersion) {
            generateProjection = false
        }
        namespace("Microsoft.UI.Windowing")
        winuiProjectionTypes.forEach(::type)
    }
}

extensions.configure<KotlinMultiplatformExtension>("kotlin") {
    jvmToolchain(winuiJvmToolchain.get().toInt())

    // The projections generated by kotlin-winrt return lambdas as fun-interface delegates
    // (e.g. TypedEventHandler), which does not compile below language version 2.3. The
    // toolkit plugin compiles them with the language version of the WinUI compilations.
    compilerOptions {
        languageVersion.set(maxOf(skikoKotlinLanguageVersion, KotlinVersion.KOTLIN_2_3))
    }

    jvm("winuiJvm") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(JvmTarget.fromTarget(winuiJvmTarget.get()))
            }
        }
        generateVersion(OS.Windows, Arch.X64, winuiSkikoProperties)
    }
    if (winuiMingwEnabled.get()) {
        mingwX64("winuiMingw") {
            binaries {
                sharedLib {
                    baseName = "skiko_winui"
                    linkTaskProvider.configure {
                        dependsOn("compileWinuiMingwSkikoNativeWindowsX64")
                        inputs.file(winuiMingwNativeArchive)
                        finalizedBy("copyWinuiMingwSkikoBridgeRuntime")
                    }
                    freeCompilerArgs += winuiMingwSkikoBridgeLinkArgs()
                }
            }
            compilations.configureEach {
                compileTaskProvider.configure {
                    dependsOn("compileWinuiMingwNativeWindowsX64")
                    inputs.file(winuiMingwNativeArchive)
                    compilerOptions.freeCompilerArgs.addAll(
                        "-include-binary",
                        winuiMingwNativeArchive.get().asFile.absolutePath,
                        "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
                        "-opt-in=kotlin.native.SymbolNameIsInternal",
                    )
                    compilerOptions.freeCompilerArgs.addAll(windowsSdkSystemLibArgs(winuiWindowsSdkVersion.get()))
                }
            }
            compilations.named("main") {
                cinterops.create("winuiMingwSkiaBridge") {
                    definitionFile.set(winuiMingwSkikoBridgeCInteropDef)
                    packageName("org.jetbrains.skiko.winui.internal")
                }
                cinterops.create("winuiIndirectPointer") {
                    packageName("org.jetbrains.skiko.winui.internal.indirect")
                    headers(
                        project.file(
                            "src/winuiMain/cpp/windows/winuiIndirectPointerInput.h"
                        )
                    )
                    includeDirs(
                        project.file("src/winuiMain/cpp/windows")
                    )
                    compilerOpts(
                        "-DWIN32_LEAN_AND_MEAN",
                        "-DNOMINMAX",
                    )
                }
            }
            binaries.configureEach {
                linkTaskProvider.configure {
                    dependsOn("compileWinuiMingwNativeWindowsX64")
                    inputs.file(winuiMingwNativeArchive)
                }
                freeCompilerArgs += listOf(
                    "-include-binary",
                    winuiMingwNativeArchive.get().asFile.absolutePath,
                    "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
                    "-opt-in=kotlin.native.SymbolNameIsInternal",
                )
                freeCompilerArgs += windowsSdkSystemLibArgs(winuiWindowsSdkVersion.get())
            }
        }
    }

    sourceSets {
        named("jvmMain") {
            dependencies {
                implementation(kotlin("stdlib"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
            }
        }
        named("winuiJvmMain") {
            dependencies {
                implementation(kotlin("stdlib"))
            }
        }
        named("winuiJvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation(project(":test-utils"))
            }
        }
        if (winuiMingwEnabled.get()) {
            named("winuiMingwTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }
            // Outside the kotlin-winrt build, the toolkit plugin gives its standalone Native
            // projection compilation the JVM runtime jars it was loaded with, which carry no
            // KLIBs. Resolve the runtime modules the generated projections import instead.
            matching { it.name == "winuiMingwWinRTProjection" }.configureEach {
                dependencies {
                    implementation("${kotlinWinRTArtifactGroup.get()}:winrt-runtime:${kotlinWinRTArtifactVersion.get()}")
                    implementation("${kotlinWinRTArtifactGroup.get()}:winrt-authoring:${kotlinWinRTArtifactVersion.get()}")
                }
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-Xexpect-actual-classes",
        "-opt-in=kotlin.time.ExperimentalTime",
    )
}

apply(from = "gradle/winui-windows-native.gradle.kts")

tasks.register("writeWinuiMingwSkiaBridgeCInteropDef") {
    group = "build"
    description = "Writes cinterop metadata that propagates winui-mingw native linker inputs to executable consumers."
    dependsOn("compileWinuiMingwSkikoNativeWindowsX64")
    val windowsSdkLibs = provider { windowsSdkSystemLibFiles(winuiWindowsSdkVersion.get()) }
    inputs.file(winuiMingwSkikoBridgeImportLib)
    inputs.file(winuiMingwSkikoBridgeDll)
    inputs.files(windowsSdkLibs)
    outputs.file(winuiMingwSkikoBridgeCInteropDef)
    outputs.file(winuiMingwSkikoBridgeCInteropHeader)
    outputs.dir(winuiMingwSkikoBridgeCInteropLibDir)
    doLast {
        val defFile = winuiMingwSkikoBridgeCInteropDef.get().asFile
        val headerFile = winuiMingwSkikoBridgeCInteropHeader.get().asFile
        val libDir = winuiMingwSkikoBridgeCInteropLibDir.get().asFile
        defFile.parentFile.mkdirs()
        libDir.mkdirs()
        headerFile.writeText("/* Native linker inputs for skiko-winui winui-mingw consumers. */\n")
        val systemLibs = windowsSdkLibs.get()
        val bridgeImportLib = winuiMingwSkikoBridgeImportLib.get().asFile
        val importLibs = listOf(bridgeImportLib) + systemLibs
        importLibs.forEach { source ->
            source.copyTo(libDir.resolve(source.name), overwrite = true)
        }
        defFile.writeText(
            buildString {
                val headerPath = headerFile.cinteropPath()
                appendLine("headers = $headerPath")
                appendLine("headerFilter = $headerPath")
                appendLine("staticLibraries = ${importLibs.joinToString(" ") { it.name }}")
                appendLine("libraryPaths = ${libDir.cinteropPath()}")
            }
        )
    }
}

tasks.matching { it.name == "cinteropWinuiMingwSkiaBridgeWinuiMingw" }.configureEach {
    dependsOn("writeWinuiMingwSkiaBridgeCInteropDef")
    inputs.dir(winuiMingwSkikoBridgeCInteropLibDir)
}

// The toolkit plugin puts the WinRT projection KLIB on the main source set's dependencies
// without a producer edge, and only orders the metadata transforms after its compilation.
// The cinterop tasks resolve the same dependencies, so they need that ordering as well.
tasks.matching { it.name.startsWith("cinterop") && it.name.endsWith("WinuiMingw") }.configureEach {
    mustRunAfter("compileWinRTProjectionKotlinWinuiMingw")
}

tasks.register("copyWinuiMingwSkikoBridgeRuntime") {
    group = "build"
    description = "Copies the winui-mingw Skia C ABI bridge DLL next to skiko_winui.dll."
    dependsOn("compileWinuiMingwSkikoNativeWindowsX64")
    val outputFile = winuiMingwSharedLibOutputDir.map { it.file("skiko_winui_skia.dll") }
    inputs.file(winuiMingwSkikoBridgeDll)
    outputs.file(outputFile)
    doLast {
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        copy {
            from(winuiMingwSkikoBridgeDll)
            into(destination.parentFile)
        }
    }
}

if (winuiMingwEnabled.get()) {
    val copyWinuiMingwSkikoBridgeTestRuntime = tasks.register<Copy>(
        "copyWinuiMingwSkikoBridgeTestRuntime"
    ) {
        group = "verification"
        description = "Copies the winui-mingw Skia bridge DLL and ICU data next to the native test executable."
        dependsOn("linkDebugTestWinuiMingw")
        from(winuiMingwSkikoBridgeDll)
        from(winuiMingwTestIcuData)
        into(winuiMingwTestOutputDir)
    }

    tasks.named("winuiMingwTest") {
        dependsOn(copyWinuiMingwSkikoBridgeTestRuntime)
    }
}

tasks.register("prepareWinuiMingwRuntimeResources") {
    group = "build"
    description = "Prepares winui-mingw runtime DLL resources for publication."
    dependsOn("linkReleaseSharedWinuiMingw", "copyWinuiMingwSkikoBridgeRuntime")

    val skikoWinuiDll = winuiMingwSharedLibOutputDir.map { it.file("skiko_winui.dll") }
    val skikoWinuiSkiaDll = winuiMingwSharedLibOutputDir.map { it.file("skiko_winui_skia.dll") }
    inputs.files(skikoWinuiDll, skikoWinuiSkiaDll)
    outputs.dir(winuiMingwRuntimeResourceDir)

    doLast {
        val outputDir = winuiMingwRuntimeResourceDir.get().asFile.resolve(winuiMingwRuntimeResourcePath)
        delete(outputDir)
        outputDir.mkdirs()

        val runtimeFiles = listOf(
            skikoWinuiDll.get().asFile,
            skikoWinuiSkiaDll.get().asFile,
        )
        runtimeFiles.forEach { file ->
            if (!file.isFile) {
                throw GradleException("winui-mingw runtime file not found: $file")
            }
            copy {
                from(file)
                into(outputDir)
            }
        }
        runtimeFiles.forEach { file ->
            outputDir.resolve("${file.name}.sha256").writeText("${sha256(file)}\n")
        }
    }
}

tasks.register<Jar>("skikoWinuiMingwRuntimeJar") {
    group = "build"
    description = "Builds skiko-winui-mingw-runtime.jar with winui-mingw native runtime DLLs."
    dependsOn("prepareWinuiMingwRuntimeResources")
    archiveBaseName.set("skiko-winui-mingw-runtime")
    from(winuiMingwRuntimeResourceDir)
}

tasks.named<GenerateWinRTProjectionsTask>("generateWinRTProjections") {
    onlyIf { !skipProjectionGeneration.get() }
    authoringScannerClasspath.from(kotlinWinRTAuthoringScannerRuntime)
    sourceRoots.setFrom(
        project.file("src/winuiMain/kotlin"),
    )
}

afterEvaluate {
    val mingwOnlyTaskNames = arrayOf(
        "compileKotlinWinuiMingw",
        "generateCompileKotlinWinuiMingwWinRTCompilerAuthoredTypeDetails",
        "validateCompileKotlinWinuiMingwWinRTAuthoredCandidates",
        "validateCompileKotlinWinuiMingwWinRTNativeAuthoringExports",
        "linkReleaseSharedWinuiMingw",
    )
    // The application tasks are registered once per Kotlin variant, with the variant as suffix.
    val winRTPackagingTaskPrefixes = listOf(
        "generateWinRTIdentity",
        "stageWindowsPackageRuntimeAssets",
        "stageWinAppPackage",
        "buildWinAppHost",
    )
    tasks.matching { task -> winRTPackagingTaskPrefixes.any(task.name::startsWith) }.configureEach {
        removeDependenciesNamed(*mingwOnlyTaskNames)
        mustRunAfter(*mingwOnlyTaskNames)
    }
}

apply(from = "gradle/winui-awt-free-boundary.gradle.kts")
apply(from = "gradle/winui-publishing.gradle.kts")
apply(from = "gradle/winui-smoke.gradle.kts")
