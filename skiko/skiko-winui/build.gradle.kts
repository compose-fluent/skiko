@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.security.MessageDigest

buildscript {
    val kotlinWinRtVersion = providers.gradleProperty("kotlinWinRt.version")
        .orElse("0.1.0-SNAPSHOT")
        .get()
    val kotlinWinRtGroup = providers.gradleProperty("kotlinWinRt.group")
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
        classpath("$kotlinWinRtGroup:winrt-gradle-plugin:$kotlinWinRtVersion")
    }
}

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

apply(plugin = "io.github.composefluent.winrt")

val kotlinWinRtVersion = providers.gradleProperty("kotlinWinRt.version")
    .orElse("0.1.0-SNAPSHOT")
val kotlinWinRtGroup = providers.gradleProperty("kotlinWinRt.group")
    .orElse("io.github.compose-fluent")
val winuiWindowsAppSdkVersion = providers.gradleProperty("skiko.winui.windowsAppSdkVersion")
    .orElse("2.1.3")
val winuiWindowsSdkVersion = providers.gradleProperty("skiko.winui.windowsSdkVersion")
    .orElse("10.0.26100.0")
val winuiWindowsSdkRoot = providers.gradleProperty("skiko.winui.windowsSdkRoot")
    .orElse(providers.environmentVariable("WindowsSdkDir"))
    .orElse(providers.environmentVariable("WINDOWSSDKDIR"))
val winuiMingwNativeArchive = layout.buildDirectory.file("native/winuiMingw/windowsX64/skiko-winui-mingw-windows-x64.a")
val winuiMingwSkikoBridgeDll = layout.buildDirectory.file("native/winuiMingwSkiko/windowsX64/skiko_winui_skia.dll")
val winuiMingwSkikoBridgeImportLib = layout.buildDirectory.file("native/winuiMingwSkiko/windowsX64/skiko_winui_skia.lib")
val winuiMingwSharedLibOutputDir = layout.buildDirectory.dir("bin/winuiMingw/releaseShared")
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
    val requiredLibs = listOf("d3d12.lib", "dxgi.lib", "dxguid.lib")
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
val skipProjectionGeneration = providers.gradleProperty("skiko.winui.skipProjectionGeneration")
    .map(String::toBoolean)
    .orElse(false)
val localSkikoJar = providers.gradleProperty("skiko.winui.localSkikoJar")
val skikoWinuiEmbeddedSkikoApi = configurations.create("skikoWinuiEmbeddedSkikoApi") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(
            org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE,
            objects.named(org.gradle.api.attributes.Category.LIBRARY),
        )
        attribute(
            org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE,
            objects.named(org.gradle.api.attributes.Usage.JAVA_API),
        )
        attribute(
            org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(org.gradle.api.attributes.LibraryElements.JAR),
        )
        attribute(
            org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
            objects.named(org.gradle.api.attributes.Bundling.EXTERNAL),
        )
        attribute(
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
        )
    }
}
val kotlinWinRtAuthoringScannerRuntime = configurations.detachedConfiguration(
    dependencies.create("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.0")
)
val winuiProjectionTypes = listOf(
    "Microsoft.UI.Xaml.Application",
    "Microsoft.UI.Xaml.FrameworkElement",
    "Microsoft.UI.Xaml.LaunchActivatedEventArgs",
    "Microsoft.UI.Xaml.RoutedEventArgs",
    "Microsoft.UI.Xaml.RoutedEventHandler",
    "Microsoft.UI.Xaml.SizeChangedEventArgs",
    "Microsoft.UI.Xaml.SizeChangedEventHandler",
    "Microsoft.UI.Xaml.UIElement",
    "Microsoft.UI.Xaml.Window",
    "Microsoft.UI.Xaml.WindowActivatedEventArgs",
    "Microsoft.UI.Xaml.WindowActivationState",
    "Microsoft.UI.Xaml.Media.MicaBackdrop",
    "Microsoft.UI.Xaml.Automation.AutomationProperties",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationControlType",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationEvents",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationNavigationDirection",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationOrientation",
    "Microsoft.UI.Xaml.Automation.Peers.AutomationPeer",
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

kotlin {
    jvmToolchain(winuiJvmToolchain.get().toInt())

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }

    jvm("winuiJvm") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(JvmTarget.fromTarget(winuiJvmTarget.get()))
            }
        }
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
        commonMain {
            dependencies {
                if (localSkikoJar.isPresent) {
                    implementation(files(rootProject.file(localSkikoJar.get())))
                } else {
                    implementation("org.jetbrains.skiko:skiko:${skikoVersion.get()}") {
                        exclude(group = "org.jetbrains.skiko", module = "skiko-awt")
                    }
                }
                implementation("${kotlinWinRtGroup.get()}:winrt-runtime:${kotlinWinRtVersion.get()}")
                implementation("${kotlinWinRtGroup.get()}:winrt-authoring:${kotlinWinRtVersion.get()}")
            }
        }
        val winuiMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir("src/winuiMain/kotlin")
        }
        named("winuiJvmMain") {
            dependsOn(winuiMain)
            dependencies {
                runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
            }
        }
        if (winuiMingwEnabled.get()) {
            named("winuiMingwMain") {
                dependsOn(winuiMain)
            }
            named("winuiMingwTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }
        }
    }
}

dependencies {
    if (localSkikoJar.isPresent) {
        skikoWinuiEmbeddedSkikoApi(files(rootProject.file(localSkikoJar.get())))
    } else {
        skikoWinuiEmbeddedSkikoApi("org.jetbrains.skiko:skiko:${skikoVersion.get()}")
    }
}

tasks.named<Jar>("winuiJvmJar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(skikoWinuiEmbeddedSkikoApi.map { file ->
        zipTree(file).matching {
            include("META-INF/skiko.kotlin_module")
            include("org/jetbrains/skia/**")
            include("org/jetbrains/skiko/**")
            exclude("org/jetbrains/skiko/*AWT*.class")
            exclude("org/jetbrains/skiko/Actuals_awtKt*.class")
            exclude("org/jetbrains/skiko/ClipComponent*.class")
            exclude("org/jetbrains/skiko/ClipRectangle*.class")
            exclude("org/jetbrains/skiko/DisplayKt*.class")
            exclude("org/jetbrains/skiko/DrawingSurface*.class")
            exclude("org/jetbrains/skiko/FullscreenAdapter*.class")
            exclude("org/jetbrains/skiko/HardwareLayer*.class")
            exclude("org/jetbrains/skiko/LinuxDrawingSurface*.class")
            exclude("org/jetbrains/skiko/MainUIDispatcher_awtKt*.class")
            exclude("org/jetbrains/skiko/SkiaLayer*.class")
            exclude("org/jetbrains/skiko/SwingDispatcher*.class")
            exclude("org/jetbrains/skiko/SystemTheme_awtKt*.class")
            exclude("org/jetbrains/skiko/redrawer/**")
            exclude("org/jetbrains/skiko/swing/**")
        }
    })
}

extensions.configure<io.github.composefluent.winrt.gradle.WinRtExtension>("winRt") {
    windowsSdk(winuiWindowsSdkVersion.get(), includeExtensions = false, generateProjection = true)
    nugetPackage("Microsoft.WindowsAppSDK", winuiWindowsAppSdkVersion.get()) {
        generateProjection = true
    }
    winuiProjectionTypes.forEach(::type)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-Xexpect-actual-classes",
        "-opt-in=kotlin.time.ExperimentalTime",
    )
}

apply(from = "gradle/windows-native.gradle.kts")

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

tasks.named<io.github.composefluent.winrt.gradle.GenerateWinRtProjectionsTask>("generateWinRtProjections") {
    onlyIf { !skipProjectionGeneration.get() }
    authoringScannerClasspath.from(kotlinWinRtAuthoringScannerRuntime)
    sourceRoots.setFrom(
        project.file("src/winuiMain/kotlin"),
    )
}

apply(from = "gradle/awt-free-boundary.gradle.kts")
apply(from = "gradle/publishing.gradle.kts")
apply(from = "gradle/smoke.gradle.kts")
