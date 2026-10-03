@file:Suppress("UNCHECKED_CAST")

import io.github.composefluent.windows.toolkit.gradle.BuildWinAppHostTask
import io.github.composefluent.windows.toolkit.gradle.WindowsExtension
import io.github.composefluent.windows.toolkit.gradle.WindowsPackageType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("io.github.compose-fluent.windows-toolkit")
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
    maven(layout.projectDirectory.dir("../../skiko/build/repo"))
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
val kotlinWinRTVersion = providers.gradleProperty("kotlinWinRT.version")
    .orElse("0.1.0-SNAPSHOT")
val kotlinWinRTGroup = providers.gradleProperty("kotlinWinRT.group")
    .orElse("io.github.compose-fluent")
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
val winuiMingwExecutableBaseName = "skia-winui-sample"
// The toolkit plugin stages one application layout per Kotlin variant.
val winuiMingwDebugLayoutDir =
    layout.buildDirectory.dir("kotlin-winrt/application-layout/winuiMingw_main_debugExecutable/package")
val sampleWindowsAppSdkVersion = "2.2.0"
val sampleWindowsSdkVersion = providers.gradleProperty("skiko.winui.windowsSdkVersion")
    .orElse("10.0.26100.0")
// skiko-winui-mingw links against the Skia bridge DLL, and Skia loads its ICU data from the
// executable directory, so both have to be part of the mingw application layout.
val skikoWinuiMingwRuntimeAssets = listOf("skiko_winui.dll", "skiko_winui_skia.dll").map { name ->
    skikoWinuiMingwRuntimePayloadDir.map { it.file("$skikoWinuiMingwRuntimeAssetPath/$name") }
} + listOf(skikoWinuiWindowsRuntimePayloadDir.map { it.file("icudtl.dat") })

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
                    baseName = winuiMingwExecutableBaseName
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
            // Same contract for the standalone Native projection compilation, which the toolkit
            // plugin also leaves without the kotlin-winrt runtime KLIBs outside its own build.
            matching { it.name == "winuiMingwWinRTProjection" }.configureEach {
                dependencies {
                    implementation("${kotlinWinRTGroup.get()}:winrt-runtime:${kotlinWinRTVersion.get()}")
                    implementation("${kotlinWinRTGroup.get()}:winrt-authoring:${kotlinWinRTVersion.get()}")
                    skikoWinuiCommonDependencyNotations.forEach(::implementation)
                }
            }
        }
    }
}

// The toolkit plugin does not regenerate the WinRT types that skiko-winui already projects, but it
// only puts project dependencies on the classpath of its projection compilations. skiko-winui is
// an external module here, so add it to that classpath explicitly.
configurations.matching { it.name == "kotlinWinRTProjectionWinuiJvmCompileClasspath" }.configureEach {
    skikoWinuiCommonDependencyNotations.forEach { notation ->
        dependencies.add(project.dependencies.create(notation))
    }
}

extensions.configure<WindowsExtension>("windows") {
    application {
        mainClass.set("SkiaWinUISample.MainKt")
        console.set(true)
        // Loose layout that carries its own Windows App SDK runtime, so running the sample does
        // not depend on which framework packages are installed on the machine.
        packageType.set(WindowsPackageType.None)
        selfContained()
        if (winuiMingwEnabled.get()) {
            skikoWinuiMingwRuntimeAssets.forEach { runtimeAsset(it.get().asFile) }
        }
    }
    packageReferences {
        windowsSdk(sampleWindowsSdkVersion.get(), includeExtensions = false, generateProjection = true)
        nugetPackage("Microsoft.WindowsAppSDK", sampleWindowsAppSdkVersion) {
            generateProjection = true
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
}

tasks.matching { it.name == "runWinAppHostWinuiJvmMain" }.configureEach {
    group = "application"
    description = "Runs the JVM WinUI Skiko sample through the generated native app host."
}

if (skikoWinuiUseLocalProject.get()) {
    // The substituted project does not carry the published runtime dependency on skiko-winui-windows.
    tasks.named<BuildWinAppHostTask>("buildWinAppHostWinuiJvmMain") {
        dependsOn(gradle.includedBuild("skiko").task(":skikoWinuiWindowsRuntimeJar"))
        runtimeClasspath.from(localSkikoBuildLibFiles("skiko-winui-windows"))
    }
}

if (winuiMingwEnabled.get()) {
    // The declared runtime assets come out of the unpacked skiko-winui runtime jars.
    tasks.matching { it.name.startsWith("stageWindowsPackageRuntimeAssets") }.configureEach {
        dependsOn("unpackSkikoWinuiMingwRuntime", "unpackSkikoWinuiWindowsRuntime")
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

tasks.register("verifySkikoWinuiMingwSampleRuntime") {
    group = "verification"
    description = "Checks that the staged winui-mingw sample layout has the executable and the skiko-winui runtime files."
    onlyIf { winuiMingwEnabled.get() }
    dependsOn("stageWinAppPackageWinuiMingwMainDebugExecutable")
    doLast {
        val layoutDir = winuiMingwDebugLayoutDir.get().asFile
        layoutDir.resolve("$winuiMingwExecutableBaseName.exe")
            .takeIf(File::isFile)
            ?: throw GradleException(
                "WinUI mingw sample executable is missing in $layoutDir. " +
                    "Run stageWinAppPackageWinuiMingwMainDebugExecutable before running the sample."
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
        val layoutDir = winuiMingwDebugLayoutDir.get().asFile
        val executableFile = layoutDir.resolve("$winuiMingwExecutableBaseName.exe")
        workingDir(layoutDir)
        if (autoExit) {
            environment("SKIKO_WINUI_SAMPLE_AUTO_EXIT", "true")
        }
        commandLine(executableFile.absolutePath)
    }
}

tasks.registerWinuiMingwSampleTask("runWinuiMingwSample")
tasks.registerWinuiMingwSampleTask("runWinuiMingwSampleSmoke", autoExit = true)
