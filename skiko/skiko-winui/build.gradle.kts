@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    val kotlinWinRtLocalRepo = gradle.startParameter.projectProperties["kotlinWinRt.localRepo"]
        ?: "../kotlin-winrt"
    val kotlinWinRtBuild = rootDir.resolve(kotlinWinRtLocalRepo)

    fun kotlinWinRtJar(path: String, fallbackPattern: String): File {
        val expected = kotlinWinRtBuild.resolve(path)
        if (expected.isFile) return expected
        return expected.parentFile
            .listFiles { file -> file.isFile && file.name.matches(fallbackPattern.toRegex()) }
            ?.sortedBy { it.name }
            ?.firstOrNull()
            ?: expected
    }

    val kotlinWinRtClasspath = listOf(
        kotlinWinRtJar("winrt-gradle-plugin/build/libs/winrt-gradle-plugin.jar", "(winrt-gradle-plugin|kotlin-winrt-gradle-plugin).*\\.jar"),
        kotlinWinRtJar("winrt-authoring/build/libs/winrt-authoring.jar", "winrt-authoring.*\\.jar"),
        kotlinWinRtJar("winrt-runtime/build/libs/winrt-runtime-jvm.jar", "winrt-runtime-jvm.*\\.jar"),
        kotlinWinRtJar("winrt-metadata/build/libs/winrt-metadata.jar", "winrt-metadata.*\\.jar"),
        kotlinWinRtJar("winrt-generator/build/libs/winrt-generator.jar", "winrt-generator.*\\.jar"),
        kotlinWinRtJar("winrt-compiler-plugin/build/libs/winrt-compiler-plugin.jar", "winrt-compiler-plugin.*\\.jar"),
    )
    val missingKotlinWinRtClasspath = kotlinWinRtClasspath.filterNot(File::isFile)
    if (missingKotlinWinRtClasspath.isNotEmpty()) {
        throw GradleException(
            "kotlin-winrt Gradle plugin jars are missing. Build the sibling kotlin-winrt jars first or " +
                "set -PkotlinWinRt.localRepo=<path>. Missing:\n${missingKotlinWinRtClasspath.joinToString("\n")}"
        )
    }

    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
    dependencies {
        classpath(files(kotlinWinRtClasspath))
        classpath("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
        classpath("com.squareup:kotlinpoet:1.18.1")
        classpath("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        classpath("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
        classpath("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
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
val kotlinWinRtLocalRepo = providers.gradleProperty("kotlinWinRt.localRepo")
    .orElse("../kotlin-winrt")
val kotlinWinRtLocalBuild = rootDir.resolve(kotlinWinRtLocalRepo.get())
val kotlinWinRtCompilerPluginJar = kotlinWinRtLocalBuild.resolve("winrt-compiler-plugin/build/libs/winrt-compiler-plugin.jar")
val winuiWindowsAppSdkVersion = providers.gradleProperty("skiko.winui.windowsAppSdkVersion")
    .orElse("1.8.260416003")
val winuiWindowsSdkVersion = providers.gradleProperty("skiko.winui.windowsSdkVersion")
    .orElse("10.0.26100.0")
val skikoVersion = providers.gradleProperty("skiko.version")
    .orElse("0.0.0-SNAPSHOT")
val winuiMingwEnabled = providers.gradleProperty("skiko.winui.mingw.enabled")
    .map(String::toBoolean)
    .orElse(true)
val winuiJvmTarget = providers.gradleProperty("skiko.winui.jvmTarget")
    .orElse("22")
val winuiJvmToolchain = providers.gradleProperty("skiko.winui.jvmToolchain")
    .orElse(winuiJvmTarget)
val skipProjectionGeneration = providers.gradleProperty("skiko.winui.skipProjectionGeneration")
    .map(String::toBoolean)
    .orElse(false)
val localSkikoJar = providers.gradleProperty("skiko.winui.localSkikoJar")
val localWinRtRuntimeJar = providers.gradleProperty("skiko.winui.localWinRtRuntimeJar")
val localWinRtRuntimeKlib = providers.gradleProperty("skiko.winui.localWinRtRuntimeKlib")
val localWinRtAuthoringJar = providers.gradleProperty("skiko.winui.localWinRtAuthoringJar")
val kotlinWinRtAuthoringScannerRuntime = configurations.detachedConfiguration(
    dependencies.create("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
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
    mavenCentral()
    google()
    flatDir {
        dirs(kotlinWinRtLocalBuild.resolve("winrt-compiler-plugin/build/libs"))
    }
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
        mingwX64("winuiMingw")
    }

    sourceSets {
        commonMain {
            dependencies {
                if (localSkikoJar.isPresent) {
                    implementation(files(rootProject.file(localSkikoJar.get())))
                } else {
                    implementation("org.jetbrains.skiko:skiko:${skikoVersion.get()}")
                }
                if (localWinRtRuntimeJar.isPresent) {
                    implementation(files(rootProject.file(localWinRtRuntimeJar.get())))
                } else {
                    implementation("${kotlinWinRtGroup.get()}:winrt-runtime:${kotlinWinRtVersion.get()}")
                }
            }
        }
        val winuiMain by creating {
            dependsOn(commonMain.get())
        }
        named("winuiJvmMain") {
            dependsOn(winuiMain)
            dependencies {
                if (localWinRtAuthoringJar.isPresent) {
                    implementation(files(rootProject.file(localWinRtAuthoringJar.get())))
                } else {
                    implementation("${kotlinWinRtGroup.get()}:winrt-authoring:${kotlinWinRtVersion.get()}")
                }
                runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
            }
        }
        if (winuiMingwEnabled.get()) {
            named("winuiMingwMain") {
                dependsOn(winuiMain)
                dependencies {
                    if (localWinRtRuntimeKlib.isPresent) {
                        implementation(files(rootProject.file(localWinRtRuntimeKlib.get())))
                    }
                }
            }
        }
    }
}

extensions.configure<io.github.composefluent.winrt.gradle.WinRtExtension>("winRt") {
    application { }
    windowsSdk(winuiWindowsSdkVersion.get(), includeExtensions = false)
    nugetPackage("Microsoft.WindowsAppSDK", winuiWindowsAppSdkVersion.get())
    winuiProjectionTypes.forEach(::type)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-Xexpect-actual-classes",
        "-opt-in=kotlin.time.ExperimentalTime",
    )
}

tasks.withType<KotlinJvmCompile>().configureEach {
    if (kotlinWinRtCompilerPluginJar.isFile) {
        compilerOptions.freeCompilerArgs.add("-Xplugin=${kotlinWinRtCompilerPluginJar.absolutePath}")
    }
}

tasks.named<io.github.composefluent.winrt.gradle.GenerateWinRtProjectionsTask>("generateWinRtProjections") {
    onlyIf { !skipProjectionGeneration.get() }
    authoringScannerClasspath.from(kotlinWinRtAuthoringScannerRuntime)
    sourceRoots.setFrom(
        project.file("src/winuiMain/kotlin"),
    )
}

apply(from = "gradle/awt-free-boundary.gradle.kts")
apply(from = "gradle/windows-native.gradle.kts")
apply(from = "gradle/publishing.gradle.kts")
apply(from = "gradle/smoke.gradle.kts")
