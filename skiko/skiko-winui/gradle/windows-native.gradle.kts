import org.gradle.jvm.tasks.Jar
import java.security.MessageDigest

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val winuiNativeVsPath = providers.gradleProperty("skiko.winui.vsPath")
    .orElse(providers.environmentVariable("SKIKO_VSBT_PATH"))
val winuiSkikoWindowsDll = providers.gradleProperty("skiko.winui.windowsSkikoDll")
val winuiSkikoWindowsIcuData = providers.gradleProperty("skiko.winui.windowsIcuData")

val winuiJvmNativeSource = layout.projectDirectory.file("src/winuiJvmMain/cpp/windows/winuiRedrawer.cc")
val winuiJvmNativeOutputDir = layout.buildDirectory.dir("native/winuiJvm/windowsX64")
val winuiJvmNativeResourceDir = layout.buildDirectory.dir("generated/winuiJvmNativeResources")
val winuiJvmNativeResourcePath = "org/jetbrains/skiko/winui/native/windows-x64"
val skikoWinuiWindowsRuntimeDir = layout.buildDirectory.dir("generated/skikoWinuiWindowsRuntime")
val skikoWinuiWindowsRuntimeDllName = "skiko-windows-x64.dll"
val skikoWinuiWindowsRuntimeIcuName = "icudtl.dat"
val winuiSkikoWindowsOutputDir = layout.buildDirectory.dir("native/skikoWinui/windowsX64")
val winuiSkikoWindowsObjectsDir = winuiSkikoWindowsOutputDir.map { it.dir("obj") }
val winuiSkikoWindowsDllFile = winuiSkikoWindowsOutputDir.map { it.file(skikoWinuiWindowsRuntimeDllName) }
val winuiSkikoWindowsCompatSource = layout.projectDirectory.file("src/winuiJvmMain/cpp/windows/msvcStlCompat.cc")

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

fun defaultWindowsIcuDataFile(): File? =
    fileTree(rootProject.file("skiko/dependencies/skia")) {
        include("**/out/Release-windows-x64/$skikoWinuiWindowsRuntimeIcuName")
    }.files.firstOrNull()

fun defaultWindowsSkiaDir(): File? =
    rootProject.file("skiko/dependencies/skia")
        .walkTopDown()
        .firstOrNull { candidate ->
            candidate.isDirectory &&
                candidate.name.endsWith("-windows-Release-x64") &&
                candidate.resolve("out/Release-windows-x64/skia.lib").isFile
        }

fun skikoWinuiMainSources(): List<File> {
    val sourceRoots = listOf(
        rootProject.file("skiko/src/commonMain/cpp/common"),
        rootProject.file("skiko/src/jvmMain/cpp/common"),
    )
    val excluded = setOf(
        "awt_jni.cc",
        "jawt.cc",
        "openglapi.cc",
        "render.cc",
    )
    return sourceRoots
        .flatMap { root ->
            root.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.extension in setOf("cc", "cpp") &&
                        file.name !in excluded
                }
                .toList()
        }
        .plus(winuiSkikoWindowsCompatSource.asFile)
        .sortedBy { it.absolutePath }
}

fun File.toResponseFilePath(): String = "\"${absolutePath.replace("\"", "\\\"")}\""

fun File.toCommandLinePath(): String = absolutePath.replace("\"", "\\\"")

fun File.toWinuiObjectName(): String =
    absolutePath
        .replace(rootProject.projectDir.absolutePath, "")
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
        .let { "$it.obj" }

fun windowsSkiaIncludeDirs(skiaDir: File): List<File> = listOf(
    skiaDir,
    skiaDir.resolve("include"),
    skiaDir.resolve("include/core"),
    skiaDir.resolve("include/config"),
    skiaDir.resolve("include/codec"),
    skiaDir.resolve("include/effects"),
    skiaDir.resolve("include/encode"),
    skiaDir.resolve("include/gpu"),
    skiaDir.resolve("include/pathops"),
    skiaDir.resolve("include/ports"),
    skiaDir.resolve("include/svg"),
    skiaDir.resolve("include/utils"),
    skiaDir.resolve("modules/jsonreader"),
    skiaDir.resolve("modules/skottie/include"),
    skiaDir.resolve("modules/skparagraph/include"),
    skiaDir.resolve("modules/skresources/include"),
    skiaDir.resolve("modules/sksg/include"),
    skiaDir.resolve("modules/skshaper/include"),
    skiaDir.resolve("modules/skunicode/include"),
    skiaDir.resolve("modules/svg/include"),
    skiaDir.resolve("src/gpu"),
    skiaDir.resolve("src/base"),
    skiaDir.resolve("third_party/externals/angle2/include"),
    skiaDir.resolve("third_party/icu"),
    skiaDir.resolve("third_party/externals/icu/source/common"),
    skiaDir.resolve("third_party/externals/harfbuzz/src"),
)

fun skikoWinuiMainDefines(): List<String> = listOf(
    "SK_ALLOW_STATIC_GLOBAL_INITIALIZERS=1",
    "SK_FORCE_DISTANCE_FIELD_TEXT=0",
    "SK_GAMMA_APPLY_TO_A8",
    "SK_GAMMA_SRGB",
    "SK_SCALAR_TO_FLOAT_EXCLUDED",
    "SK_SUPPORT_GPU=1",
    "SK_GANESH",
    "SK_GL",
    "SK_SHAPER_HARFBUZZ_AVAILABLE",
    "SK_UNICODE_AVAILABLE",
    "SK_SHAPER_UNICODE_AVAILABLE",
    "SK_SUPPORT_OPENCL=0",
    "SK_USING_THIRD_PARTY_ICU",
    "U_DISABLE_RENAMING=0",
    "U_DISABLE_VERSION_SUFFIX=1",
    "U_HAVE_LIB_SUFFIX=1",
    "U_LIB_SUFFIX_C_NAME=_skiko",
    "NDEBUG",
    "SK_BUILD_FOR_WIN",
    "_CRT_SECURE_NO_WARNINGS",
    "_HAS_EXCEPTIONS=0",
    "WIN32_LEAN_AND_MEAN",
    "NOMINMAX",
    "SK_DIRECT3D",
)

fun skikoWinuiMainSkiaLibs(skiaLibDir: File): List<File> = listOf(
    "skia.lib",
    "skia_ganesh_ext.lib",
    "skunicode_core.lib",
    "skunicode_icu.lib",
    "skshaper.lib",
    "skparagraph.lib",
    "skresources.lib",
    "sksg.lib",
    "skottie.lib",
    "svg.lib",
    "jsonreader.lib",
    "icu.lib",
    "harfbuzz.lib",
    "libpng.lib",
    "libjpeg.lib",
    "libwebp.lib",
    "libwebp_sse41.lib",
    "skcms.lib",
    "expat.lib",
    "wuffs.lib",
    "zlib.lib",
    "d3d12allocator.lib",
    "bentleyottmann.lib",
    "spirv_cross.lib",
).map { skiaLibDir.resolve(it) }

fun skikoWinuiMainSystemLibs(): List<String> = listOf(
    "Advapi32.lib",
    "Dwmapi.lib",
    "Gdi32.lib",
    "Ole32.lib",
    "Propsys.lib",
    "Shcore.lib",
    "Shlwapi.lib",
    "User32.lib",
    "D3D12.lib",
    "Dxgi.lib",
    "D3DCompiler.lib",
    "OpenGL32.lib",
)

tasks.register<Exec>("compileWinuiJvmNativeWindowsX64") {
    group = "build"
    description = "Compiles the skiko-winui JVM Windows native helper without using Skiko AWT native sources."
    dependsOn("stageWinRtRuntimeAssets")

    inputs.file(winuiJvmNativeSource)
    outputs.file(winuiJvmNativeOutputDir.map { it.file("skiko-winui.dll") })

    onlyIf {
        val source = winuiJvmNativeSource.asFile
        val output = winuiJvmNativeOutputDir.get().asFile.resolve("skiko-winui.dll")
        isWindowsHost && (!output.isFile || source.lastModified() > output.lastModified())
    }

    doFirst {
        if (!winuiNativeVsPath.isPresent) {
            throw GradleException(
                "Visual Studio path is required. Set SKIKO_VSBT_PATH or " +
                    "-Pskiko.winui.vsPath=<Visual Studio installation path>."
            )
        }

        val jdkHome = File(System.getProperty("java.home"))
        val outputDir = winuiJvmNativeOutputDir.get().asFile
        val outputDll = outputDir.resolve("skiko-winui.dll")
        val outputObj = outputDir.resolve("winuiRedrawer.obj")
        val outputLib = outputDir.resolve("skiko-winui.lib")
        val source = winuiJvmNativeSource.asFile
        val vcvars64 = File(winuiNativeVsPath.get()).resolve("VC/Auxiliary/Build/vcvars64.bat")
        val winuiDxInteropHeader = fileTree(layout.buildDirectory.dir("tmp").get().asFile) {
            include("**/include/microsoft.ui.xaml.media.dxinterop.h")
        }.files.firstOrNull()
            ?: throw GradleException("WinUI dxinterop header was not staged by stageWinRtRuntimeAssets.")
        val winuiIncludeDir = winuiDxInteropHeader.parentFile

        outputDir.mkdirs()
        if (!vcvars64.isFile) {
            throw GradleException("vcvars64.bat not found at '$vcvars64'.")
        }

        val clCommand = listOf(
            "cl.exe",
            "/nologo",
            "/LD",
            "/std:c++20",
            "/EHsc",
            "/utf-8",
            "/DSK_DIRECT3D",
            "/I\"${jdkHome.resolve("include")}\"",
            "/I\"${jdkHome.resolve("include/win32")}\"",
            "/I\"$winuiIncludeDir\"",
            "/Fo\"$outputObj\"",
            "/Fe\"$outputDll\"",
            "\"$source\"",
            "/link",
            "/NOLOGO",
            "/IMPLIB:\"$outputLib\"",
            "d3d12.lib",
            "dxgi.lib",
        ).joinToString(" ")
        commandLine(
            "cmd.exe",
            "/c",
            "call \"$vcvars64\" >nul && $clCommand"
        )
    }
}

val copyWinuiJvmNativeWindowsX64 by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies the skiko-winui JVM Windows native helper into JVM resources."
    onlyIf { isWindowsHost }
    dependsOn("compileWinuiJvmNativeWindowsX64")

    from(winuiJvmNativeOutputDir.map { it.file("skiko-winui.dll") })
    into(winuiJvmNativeResourceDir.map { it.dir(winuiJvmNativeResourcePath) })
}

tasks.named<Copy>("processWinuiJvmMainResources") {
    dependsOn(copyWinuiJvmNativeWindowsX64)
    from(winuiJvmNativeResourceDir)
}

tasks.named<Copy>("winuiJvmProcessResources") {
    dependsOn(copyWinuiJvmNativeWindowsX64)
    from(winuiJvmNativeResourceDir)
}

val compileWinuiSkikoWindowsX64 by tasks.registering {
    group = "build"
    description = "Builds the AWT-free skiko-winui Windows JVM Skiko native runtime DLL."

    val sources = skikoWinuiMainSources()
    inputs.files(sources)
    inputs.dir(rootProject.file("skiko/src/commonMain/cpp/common/include"))
    inputs.dir(rootProject.file("skiko/src/jvmMain/cpp/common"))
    outputs.file(winuiSkikoWindowsDllFile)

    onlyIf {
        val output = winuiSkikoWindowsDllFile.get().asFile
        isWindowsHost && (!output.isFile || sources.any { it.lastModified() > output.lastModified() })
    }

    doLast {
        if (!winuiNativeVsPath.isPresent) {
            throw GradleException(
                "Visual Studio path is required. Set SKIKO_VSBT_PATH or " +
                    "-Pskiko.winui.vsPath=<Visual Studio installation path>."
            )
        }

        val skiaDir = defaultWindowsSkiaDir()
            ?: throw GradleException("Skia Windows Release-x64 dependency was not found under skiko/dependencies/skia.")
        val skiaLibDir = skiaDir.resolve("out/Release-windows-x64")
        val missingLibs = skikoWinuiMainSkiaLibs(skiaLibDir).filterNot(File::isFile)
        if (missingLibs.isNotEmpty()) {
            throw GradleException("Missing Skia libraries:\n${missingLibs.joinToString("\n")}")
        }

        val jdkHome = File(System.getProperty("java.home"))
        val vcvars64 = File(winuiNativeVsPath.get()).resolve("VC/Auxiliary/Build/vcvars64.bat")
        if (!vcvars64.isFile) {
            throw GradleException("vcvars64.bat not found at '$vcvars64'.")
        }

        val outputDir = winuiSkikoWindowsOutputDir.get().asFile
        val objectsDir = winuiSkikoWindowsObjectsDir.get().asFile
        val dll = winuiSkikoWindowsDllFile.get().asFile
        val importLib = outputDir.resolve("skiko-windows-x64.lib")
        val batchFile = outputDir.resolve("compile-skiko-winui-windows.cmd")
        val logFile = outputDir.resolve("compile-skiko-winui-windows.log")
        val launcherLogFile = outputDir.resolve("compile-skiko-winui-windows-launcher.log")
        val linkResponseFile = outputDir.resolve("link-skiko-winui-windows.rsp")
        outputDir.mkdirs()
        objectsDir.mkdirs()

        val includeDirs = windowsSkiaIncludeDirs(skiaDir) + listOf(
            jdkHome.resolve("include"),
            jdkHome.resolve("include/win32"),
            rootProject.file("skiko/src/commonMain/cpp/common/include"),
            rootProject.file("skiko/src/jvmMain/cpp/common"),
            rootProject.file("skiko/src/jvmMain/cpp/include"),
        )
        val objectFiles = sources.map { source -> objectsDir.resolve(source.toWinuiObjectName()) }
        val compileResponseFiles = sources.zip(objectFiles).map { (source, obj) ->
            val responseFile = objectsDir.resolve("${obj.nameWithoutExtension}.rsp")
            responseFile.writeText(
                buildString {
                    appendLine("/nologo")
                    appendLine("/c")
                    appendLine("/std:c++20")
                    appendLine("/O2")
                    appendLine("/utf-8")
                    appendLine("/GR-")
                    appendLine("/FS")
                    appendLine("/MT")
                    skikoWinuiMainDefines().forEach { appendLine("/D$it") }
                    includeDirs.filter(File::isDirectory).forEach { appendLine("/I${it.toResponseFilePath()}") }
                    appendLine("/Fo${obj.toResponseFilePath()}")
                    appendLine(source.toResponseFilePath())
                }
            )
            source to responseFile
        }

        linkResponseFile.writeText(
            buildString {
                appendLine("/NOLOGO")
                appendLine("/DLL")
                appendLine("/DEBUG")
                appendLine("/OUT:${dll.toResponseFilePath()}")
                appendLine("/IMPLIB:${importLib.toResponseFilePath()}")
                objectFiles.forEach { appendLine(it.toResponseFilePath()) }
                skikoWinuiMainSkiaLibs(skiaLibDir).forEach { appendLine(it.toResponseFilePath()) }
                skikoWinuiMainSystemLibs().forEach { appendLine(it) }
            }
        )

        batchFile.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("setlocal")
                appendLine("set \"LOG=${logFile.toCommandLinePath()}\"")
                appendLine("echo Starting WinUI Skiko native build > \"%LOG%\"")
                appendLine("call ${vcvars64.toResponseFilePath()} >> \"%LOG%\" 2>&1 || goto :fail")
                compileResponseFiles.forEach { (source, responseFile) ->
                    appendLine("echo cl ${source.name} >> \"%LOG%\"")
                    appendLine("cl.exe @${responseFile.toResponseFilePath()} >> \"%LOG%\" 2>&1 || goto :fail")
                }
                appendLine("echo link ${dll.name} >> \"%LOG%\"")
                appendLine("link.exe @${linkResponseFile.toResponseFilePath()} >> \"%LOG%\" 2>&1 || goto :fail")
                appendLine("exit /b 0")
                appendLine(":fail")
                appendLine("set EXIT_CODE=%ERRORLEVEL%")
                appendLine("exit /b %EXIT_CODE%")
            }
        )

        val exitCode = ProcessBuilder("cmd.exe", "/d", "/s", "/c", "\"${batchFile.absolutePath}\"")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(launcherLogFile))
            .start()
            .waitFor()
        if (exitCode != 0) {
            val nativeLog = if (logFile.isFile) {
                logFile.readLines()
                    .takeLast(240)
                    .joinToString(System.lineSeparator())
            } else {
                "<missing native log>"
            }
            val batchTail = batchFile.readLines()
                .takeLast(40)
                .joinToString(System.lineSeparator())
            logger.lifecycle(
                """
                WinUI native compile failed.
                exitCode=$exitCode
                batchFile=${batchFile.absolutePath}
                logFile=${logFile.absolutePath}
                launcherLogFile=${launcherLogFile.absolutePath}
                logExists=${logFile.isFile}
                logLength=${if (logFile.isFile) logFile.length() else 0}
                ---- native log ----
                $nativeLog
                ---- batch tail ----
                $batchTail
                """.trimIndent()
            )
            throw GradleException("WinUI-owned Skiko Windows runtime compilation failed with exit code $exitCode.")
        }
    }
}

val prepareSkikoWinuiWindowsRuntimeResources by tasks.registering {
    group = "build"
    description = "Prepares the AWT-free skiko-winui Windows JVM runtime resources at the Skiko loader root."
    if (!winuiSkikoWindowsDll.isPresent) {
        dependsOn(compileWinuiSkikoWindowsX64)
    }

    inputs.property("winuiSkikoWindowsDll", winuiSkikoWindowsDll.orNull ?: "")
    inputs.property("winuiSkikoWindowsIcuData", winuiSkikoWindowsIcuData.orNull ?: "")
    outputs.dir(skikoWinuiWindowsRuntimeDir)

    doLast {
        val dll = winuiSkikoWindowsDll.orNull
            ?.let { rootProject.file(it) }
            ?: winuiSkikoWindowsDllFile.get().asFile
        if (!dll.isFile) {
            throw GradleException("WinUI Skiko Windows DLL not found: $dll")
        }

        val icuData = winuiSkikoWindowsIcuData.orNull
            ?.let { rootProject.file(it) }
            ?: defaultWindowsIcuDataFile()
            ?: throw GradleException(
                "Missing Windows ICU data. Set -Pskiko.winui.windowsIcuData=<path to $skikoWinuiWindowsRuntimeIcuName>."
            )
        if (!icuData.isFile) {
            throw GradleException("Windows ICU data file not found: $icuData")
        }

        val outputDir = skikoWinuiWindowsRuntimeDir.get().asFile
        delete(outputDir)
        outputDir.mkdirs()

        copy {
            from(dll)
            into(outputDir)
            rename { skikoWinuiWindowsRuntimeDllName }
        }
        copy {
            from(icuData)
            into(outputDir)
            rename { skikoWinuiWindowsRuntimeIcuName }
        }

        outputDir.resolve("$skikoWinuiWindowsRuntimeDllName.sha256")
            .writeText("${sha256(dll)}\n")
    }
}

tasks.register<Jar>("skikoWinuiWindowsRuntimeJar") {
    group = "build"
    description = "Builds skiko-winui-windows.jar with the WinUI-owned Skiko Windows native runtime."
    dependsOn(prepareSkikoWinuiWindowsRuntimeResources)

    archiveBaseName.set("skiko-winui-windows")
    from(skikoWinuiWindowsRuntimeDir)
}
