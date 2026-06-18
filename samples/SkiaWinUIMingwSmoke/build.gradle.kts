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
    kotlin("multiplatform") version "2.4.0"
}

apply(plugin = "io.github.composefluent.winrt")

repositories {
    maven(layout.projectDirectory.dir("../../build/repo"))
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent {
            snapshotsOnly()
        }
    }
    mavenCentral()
    google()
}

val kotlinWinRtVersion = providers.gradleProperty("kotlinWinRt.version")
    .orElse("0.1.0-SNAPSHOT")
val kotlinWinRtGroup = providers.gradleProperty("kotlinWinRt.group")
    .orElse("io.github.compose-fluent")
val skikoWinuiVersion = providers.gradleProperty("skiko.version")
    .orElse("0.0.0-SNAPSHOT")
val usePublishedSkikoWinui = providers.gradleProperty("skiko.winui.usePublished")
    .map(String::toBoolean)
    .orElse(false)
val smokeWindowsAppSdkVersion = providers.gradleProperty("skiko.winui.windowsAppSdkVersion")
    .orElse("2.1.3")
val smokeWindowsSdkVersion = providers.gradleProperty("skiko.winui.windowsSdkVersion")
    .orElse("10.0.26100.0")
val skikoWinuiMingwRuntimeJarProvider = providers.gradleProperty("skiko.winui.mingwRuntimeJar")
    .map(layout.projectDirectory::file)
val skikoWinuiMingwRuntimeFiles = configurations.create("skikoWinuiMingwRuntimeFiles") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val skikoWinuiMingwRuntimePayloadDir = layout.buildDirectory.dir("skiko-winui-mingw-runtime")
val skikoWinuiMingwRuntimeAssetPath = "winui-mingw/windows-x64"
val winRtRuntimeAssetsDir = layout.buildDirectory.dir("kotlin-winrt/runtime-assets")
val winuiMingwDebugExecutableDir = layout.buildDirectory.dir("bin/winuiMingw/debugExecutable")

kotlin {
    mingwX64("winuiMingw") {
        binaries {
            executable {
                baseName = "skiko-winui-mingw-smoke"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                if (usePublishedSkikoWinui.get()) {
                    implementation("io.github.compose-fluent:skiko-winui-mingw:${skikoWinuiVersion.get()}")
                } else {
                    implementation(project(":skiko-winui"))
                }
                implementation("${kotlinWinRtGroup.get()}:winrt-runtime:${kotlinWinRtVersion.get()}")
            }
        }
        val winuiMingwMain by getting {
            dependsOn(commonMain)
        }
    }
}

dependencies {
    if (usePublishedSkikoWinui.get() && !skikoWinuiMingwRuntimeJarProvider.isPresent) {
        skikoWinuiMingwRuntimeFiles("io.github.compose-fluent:skiko-winui-mingw-runtime:${skikoWinuiVersion.get()}")
    }
}

extensions.configure<io.github.composefluent.winrt.gradle.WinRtExtension>("winRt") {
    windowsSdk(smokeWindowsSdkVersion.get(), includeExtensions = false, generateProjection = true)
    nugetPackage("Microsoft.WindowsAppSDK", smokeWindowsAppSdkVersion.get()) {
        generateProjection = true
    }
    application {
        mainClass.set("org.jetbrains.skiko.winui.mingw.smoke.MainKt")
        console.set(true)
        unpackaged()
        packagePayload(skikoWinuiMingwRuntimePayloadDir.map { it.file("$skikoWinuiMingwRuntimeAssetPath/skiko_winui.dll") }, ".")
        packagePayload(skikoWinuiMingwRuntimePayloadDir.map { it.file("$skikoWinuiMingwRuntimeAssetPath/skiko_winui_skia.dll") }, ".")
    }
    listOf(
        "Microsoft.UI.Dispatching.DispatcherQueue",
        "Microsoft.UI.Dispatching.DispatcherQueueHandler",
        "Microsoft.UI.Xaml.Application",
        "Microsoft.UI.Xaml.FrameworkElement",
        "Microsoft.UI.Xaml.LaunchActivatedEventArgs",
        "Microsoft.UI.Xaml.Window",
    ).forEach(::type)
}

tasks.register<Copy>("unpackSkikoWinuiMingwRuntime") {
    group = "build"
    description = "Unpacks skiko-winui-mingw-runtime.jar for WinRT application payload staging."
    if (!usePublishedSkikoWinui.get()) {
        dependsOn(":skiko-winui:skikoWinuiMingwRuntimeJar")
    }
    val runtimeJar = if (skikoWinuiMingwRuntimeJarProvider.isPresent) {
        skikoWinuiMingwRuntimeJarProvider.map { files(it) }
    } else if (usePublishedSkikoWinui.get()) {
        provider { skikoWinuiMingwRuntimeFiles }
    } else {
        provider { files(layout.projectDirectory.file("../../skiko/skiko-winui/build/libs/skiko-winui-mingw-runtime.jar")) }
    }
    from(runtimeJar.map { files -> files.map { zipTree(it) } })
    into(skikoWinuiMingwRuntimePayloadDir)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=kotlin.time.ExperimentalTime",
    )
}

tasks.named("stageWinRtRuntimeAssets") {
    dependsOn("unpackSkikoWinuiMingwRuntime")
}

tasks.register("stageSkikoWinuiMingwRuntimeDlls") {
    group = "build"
    description = "Stages skiko-winui mingw runtime DLLs for local executable launch and WinRT app payload."
    dependsOn("unpackSkikoWinuiMingwRuntime", "stageWinRtRuntimeAssets")
    val runtimeDlls = listOf("skiko_winui.dll", "skiko_winui_skia.dll")
    val sourceFiles = runtimeDlls.map { name ->
        skikoWinuiMingwRuntimePayloadDir.map { it.file("$skikoWinuiMingwRuntimeAssetPath/$name") }
    }
    val outputFiles = runtimeDlls.flatMap { name ->
        listOf(
            winRtRuntimeAssetsDir.map { it.file(name) },
            winuiMingwDebugExecutableDir.map { it.file(name) },
        )
    }
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
    dependsOn("unpackSkikoWinuiMingwRuntime")
}

tasks.named("runDebugExecutableWinuiMingw") {
    dependsOn("stageSkikoWinuiMingwRuntimeDlls")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-Xexpect-actual-classes",
        "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
        "-opt-in=kotlin.native.SymbolNameIsInternal",
    )
}

tasks.register("runWinuiMingwSmoke") {
    group = "verification"
    description = "Runs the skiko-winui Kotlin/Native mingw WinUI app-host smoke."
    dependsOn("runDebugExecutableWinuiMingw")
}
