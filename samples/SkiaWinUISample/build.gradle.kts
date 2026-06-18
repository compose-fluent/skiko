@file:Suppress("UNCHECKED_CAST")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.toolchain.JavaLanguageVersion

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
    kotlin("jvm") version "2.4.0"
    application
}

apply(plugin = "io.github.composefluent.winrt")

apply(from = "../skiko-winui-sample-dependencies.gradle.kts")

repositories {
    mavenLocal {
        content {
            includeModule("io.github.compose-fluent", "skiko-winui")
            includeModule("io.github.compose-fluent", "skiko-winui-windows")
        }
    }
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent {
            snapshotsOnly()
        }
    }
    google()
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
}

val skikoWinuiDependencyNotations =
    extra["skikoWinuiCommonDependencyNotations"] as List<Any> +
        extra["skikoWinuiJvmDependencyNotations"] as List<Any>
dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    skikoWinuiDependencyNotations.forEach(::implementation)
}

application {
    mainClass.set("SkiaWinUISample.AppKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

extensions.configure<io.github.composefluent.winrt.gradle.WinRtExtension>("winRt") {
    windowsSdk("10.0.26100.0", includeExtensions = false, generateProjection = true)
    nugetPackage("Microsoft.WindowsAppSDK", "2.1.3") {
        generateProjection = true
    }
    application {
        mainClass.set("SkiaWinUISample.AppKt")
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

tasks.named<JavaExec>("run") {
    group = "application"
    description = "Use runWinRtApplicationHost for the AWT-free WinUI Skiko sample."
    enabled = false
    doFirst {
        throw GradleException("Use runWinRtApplicationHost to launch the WinUI sample through the native app host.")
    }
}

tasks.named("runWinRtApplicationHost") {
    group = "application"
    description = "Runs the AWT-free WinUI Skiko sample through the generated native app host."
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }
}
