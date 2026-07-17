import org.gradle.jvm.tasks.Jar
import java.security.MessageDigest

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val winuiNativeVsPath = providers.gradleProperty("skiko.winui.vsPath")
    .orElse(providers.environmentVariable("SKIKO_VSBT_PATH"))
val winuiNativeWindowsAppSdkVersion = "2.2.0"
val winuiNativeWindowsSdkVersion = providers.gradleProperty("skiko.winui.windowsSdkVersion")
    .orElse("10.0.26100.0")
val winuiNativeWindowsSdkRoot = providers.gradleProperty("skiko.winui.windowsSdkRoot")
    .orElse(providers.environmentVariable("WindowsSdkDir"))
    .orElse(providers.environmentVariable("WINDOWSSDKDIR"))
val winuiNativeWindowsSkiaDir = providers.gradleProperty("skiko.winui.windowsSkiaDir")
    .orElse(providers.environmentVariable("SKIKO_WINUI_WINDOWS_SKIA_DIR"))
val winuiSkikoWindowsDll = providers.gradleProperty("skiko.winui.windowsSkikoDll")
val winuiSkikoWindowsIcuData = providers.gradleProperty("skiko.winui.windowsIcuData")

val winuiJvmNativeSource = layout.projectDirectory.file("src/winuiJvmMain/cpp/windows/winuiRedrawer.cc")
val winuiIndirectPointerNativeHeader =
    layout.projectDirectory.file("src/winuiMain/cpp/windows/winuiIndirectPointerInput.h")
val winuiIndirectPointerNativeSource =
    layout.projectDirectory.file("src/winuiMain/cpp/windows/winuiIndirectPointerInput.cc")
val winuiIndirectPointerNativeTestSource =
    layout.projectDirectory.file("src/winuiTest/cpp/windows/winuiIndirectPointerInputTest.cc")
val winuiIndirectPointerNativeTestOutputDir =
    layout.buildDirectory.dir("native/winuiIndirectPointerTest/windowsX64")
val winuiJvmNativeOutputDir = layout.buildDirectory.dir("native/winuiJvm/windowsX64")
val winuiJvmNativeNuGetInstallDir = layout.buildDirectory.dir("tmp/compileWinuiJvmNativeWindowsX64/nuget-install")
val winuiJvmNativeResourceDir = layout.buildDirectory.dir("generated/winuiJvmNativeResources")
val winuiJvmNativeResourcePath = "org/jetbrains/skiko/winui/native/windows-x64"
val winuiMingwNativeOutputDir = layout.buildDirectory.dir("native/winuiMingw/windowsX64")
val winuiMingwNativeObjectsDir = winuiMingwNativeOutputDir.map { it.dir("obj") }
val winuiMingwNativeArchive = winuiMingwNativeOutputDir.map { it.file("skiko-winui-mingw-windows-x64.a") }
val winuiMingwLlvmDir = providers.gradleProperty("skiko.winui.mingw.llvmDir")
val winuiMingwSysroot = providers.gradleProperty("skiko.winui.mingw.sysroot")
val winuiMingwSkikoNativeOutputDir = layout.buildDirectory.dir("native/winuiMingwSkiko/windowsX64")
val winuiMingwSkikoNativeObjectsDir = winuiMingwSkikoNativeOutputDir.map { it.dir("obj") }
val winuiMingwSkikoNativeDllFile = winuiMingwSkikoNativeOutputDir.map { it.file("skiko_winui_skia.dll") }
val winuiMingwSkikoNativeImportLibFile = winuiMingwSkikoNativeOutputDir.map { it.file("skiko_winui_skia.lib") }
val skikoWinuiWindowsRuntimeDir = layout.buildDirectory.dir("generated/skikoWinuiWindowsRuntime")
val skikoWinuiWindowsRuntimeDllName = "skiko-windows-x64.dll"
val skikoWinuiWindowsRuntimeIcuName = "icudtl.dat"
val winuiSkikoWindowsOutputDir = layout.buildDirectory.dir("native/skikoWinui/windowsX64")
val winuiSkikoWindowsObjectsDir = winuiSkikoWindowsOutputDir.map { it.dir("obj") }
val winuiSkikoWindowsDllFile = winuiSkikoWindowsOutputDir.map { it.file(skikoWinuiWindowsRuntimeDllName) }
val winuiSkikoWindowsCompatSource = layout.projectDirectory.file("src/winuiJvmMain/cpp/windows/msvcStlCompat.cc")
val skikoProjectDir = layout.projectDirectory.asFile

fun skikoProjectFile(path: String): File =
    skikoProjectDir.resolve(path)

fun Task.dependsOnIncludedSkikoWindowsSkia() {
    if (!winuiNativeWindowsSkiaDir.isPresent) {
        dependsOn("unzipSkiaReleaseWindowsX64")
    }
}

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
    winuiNativeWindowsSkiaDir.orNull
        ?.let(::File)
        ?.resolve("out/Release-windows-x64/$skikoWinuiWindowsRuntimeIcuName")
        ?.takeIf(File::isFile)
        ?: fileTree(skikoProjectFile("dependencies/skia")) {
        include("**/out/Release-windows-x64/$skikoWinuiWindowsRuntimeIcuName")
    }.files.firstOrNull()

fun defaultWindowsSkiaDir(): File? =
    winuiNativeWindowsSkiaDir.orNull
        ?.let(::File)
        ?.takeIf { it.isDirectory && it.resolve("out/Release-windows-x64/skia.lib").isFile }
        ?: skikoProjectFile("dependencies/skia")
        .walkTopDown()
        .firstOrNull { candidate ->
            candidate.isDirectory &&
                candidate.name.endsWith("-windows-Release-x64") &&
                candidate.resolve("out/Release-windows-x64/skia.lib").isFile
        }

fun defaultVisualStudioPath(): File? {
    val configured = winuiNativeVsPath.orNull
        ?.let(::File)
        ?.takeIf { it.resolve("VC/Auxiliary/Build/vcvars64.bat").isFile }
    if (configured != null) {
        return configured
    }

    val vswhere = File("C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe")
    if (vswhere.isFile) {
        val output = runCatching {
            val process = ProcessBuilder(
                vswhere.absolutePath,
                "-latest",
                "-products",
                "*",
                "-requires",
                "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                "-property",
                "installationPath",
            )
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) text else ""
        }.getOrDefault("")
        output
            .lineSequence()
            .map(::File)
            .firstOrNull { it.resolve("VC/Auxiliary/Build/vcvars64.bat").isFile }
            ?.let { return it }
    }

    return listOf(
        "D:/Program Files/Microsoft Visual Studio/2022/Community",
        "D:/Program Files/Microsoft Visual Studio/2022/BuildTools",
        "C:/Program Files/Microsoft Visual Studio/2022/Community",
        "C:/Program Files/Microsoft Visual Studio/2022/BuildTools",
        "C:/Program Files/Microsoft Visual Studio/2022/Enterprise",
        "C:/Program Files/Microsoft Visual Studio/2022/Professional",
    )
        .map(::File)
        .firstOrNull { it.resolve("VC/Auxiliary/Build/vcvars64.bat").isFile }
}

fun vcvars64Bat(): File =
    defaultVisualStudioPath()
        ?.resolve("VC/Auxiliary/Build/vcvars64.bat")
        ?: throw GradleException(
            "Visual Studio C++ tools were not found. Set -Pskiko.winui.vsPath=<Visual Studio installation path> " +
                "or SKIKO_VSBT_PATH to a directory containing VC/Auxiliary/Build/vcvars64.bat."
        )

fun defaultKonanDependencyDir(name: String): File? =
    File(System.getProperty("user.home"))
        .resolve(".konan/dependencies")
        .takeIf(File::isDirectory)
        ?.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith(name) }
        ?.maxByOrNull { it.name }

fun defaultMingwSysroot(): File? =
    defaultKonanDependencyDir("msys2-mingw-w64-x86_64")
        ?.resolve("x86_64-w64-mingw32")
        ?.takeIf(File::isDirectory)

fun defaultKonanLlvmDir(): File? =
    defaultKonanDependencyDir("llvm-")
        ?.resolve("bin")
        ?.takeIf { it.resolve("clang++.exe").isFile && it.resolve("llvm-ar.exe").isFile }

fun skikoWinuiMainSources(): List<File> {
    val sourceRoots = listOf(
        skikoProjectFile("src/commonMain/cpp/common"),
        skikoProjectFile("src/jvmMain/cpp/common"),
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
        .replace(skikoProjectDir.absolutePath, "")
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

fun skikoWinuiNativeSourcesForMingwBridge(): List<File> {
    val sourceRoots = listOf(
        skikoProjectFile("src/commonMain/cpp/common"),
        skikoProjectFile("src/nativeJsMain/cpp"),
    )
    val excluded = setOf(
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
        .distinctBy { it.absolutePath }
        .sortedBy { it.absolutePath }
}

fun skikoWinuiNativeExportNames(sources: List<File>): List<String> {
    val exportRegex = Regex("""SKIKO_EXPORT\s+(?:[\w:<>*&]+\s+)+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(|$)""")
    val windowsExcludedExports = setOf(
        "skia_memGetByte",
        "skia_memSetByte",
        "skia_memGetChar",
        "skia_memSetChar",
        "skia_memGetShort",
        "skia_memSetShort",
        "skia_memGetInt",
        "skia_memSetInt",
        "skia_memGetFloat",
        "skia_memSetFloat",
        "skia_memGetDouble",
        "skia_memSetDouble",
    )
    return sources
        .flatMap { source ->
            source.readText()
                .replace("\r\n", "\n")
                .replace(Regex("""\s+"""), " ")
                .let { text -> exportRegex.findAll(text).map { it.groupValues[1] }.toList() }
        }
        .filterNot(windowsExcludedExports::contains)
        .distinct()
        .sorted()
}

fun windowsSdkRoot(): File =
    winuiNativeWindowsSdkRoot.orNull
        ?.let(::File)
        ?.takeIf(File::isDirectory)
        ?: windowsSdkRootFromRegistry()
        ?: throw GradleException(
            "Windows SDK root was not found. Set -Pskiko.winui.windowsSdkRoot=<Windows Kits 10 root> " +
                "or install a Windows SDK with KitsRoot10 registered."
        )

fun windowsSdkIncludeDirs(version: String): List<File> {
    val includeRoot = windowsSdkRoot().resolve("Include/$version")
    return listOf(
        includeRoot.resolve("shared"),
        includeRoot.resolve("ucrt"),
        includeRoot.resolve("um"),
        includeRoot.resolve("winrt"),
    )
}

fun windowsSdkLibDir(version: String): File =
    windowsSdkRoot().resolve("Lib/$version/um/x64")

fun runWinuiProcess(command: List<String>, workingDir: File? = null) {
    val output = StringBuilder()
    val processBuilder = ProcessBuilder(command)
        .redirectErrorStream(true)
    if (workingDir != null) {
        processBuilder.directory(workingDir)
    }
    val process = processBuilder.start()
    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
            println(line)
            output.appendLine(line)
        }
    }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw GradleException(
            "Command failed with exit code $exitCode: ${command.joinToString(" ")}\n" +
                output.lines().takeLast(80).joinToString("\n")
        )
    }
}

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
    "png.lib",
    "jpeg.lib",
    "webp.lib",
    "webp_sse41.lib",
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

val resolveWinuiJvmNativeWindowsAppSdk by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs the Windows App SDK NuGet package used by the skiko-winui JVM native helper."

    onlyIf { isWindowsHost }
    inputs.property("windowsAppSdkVersion", winuiNativeWindowsAppSdkVersion)
    outputs.dir(winuiJvmNativeNuGetInstallDir)
    outputs.upToDateWhen {
        fileTree(winuiJvmNativeNuGetInstallDir.get().asFile) {
            include("**/include/microsoft.ui.xaml.media.dxinterop.h")
        }.files.isNotEmpty()
    }

    doFirst {
        winuiJvmNativeNuGetInstallDir.get().asFile.mkdirs()
        commandLine(
            "nuget",
            "install",
            "Microsoft.WindowsAppSDK",
            "-Version",
            winuiNativeWindowsAppSdkVersion,
            "-OutputDirectory",
            winuiJvmNativeNuGetInstallDir.get().asFile.absolutePath,
            "-NonInteractive",
            "-DirectDownload",
            "-DependencyVersion",
            "Highest",
        )
    }
}

tasks.register("runWinuiIndirectPointerNativeTests") {
    group = "verification"
    description = "Builds and runs the synthetic WinUI indirect pointer parser/state tests."

    inputs.files(
        winuiIndirectPointerNativeHeader,
        winuiIndirectPointerNativeSource,
        winuiIndirectPointerNativeTestSource,
    )
    outputs.upToDateWhen { false }
    onlyIf { isWindowsHost }

    doLast {
        val outputDir = winuiIndirectPointerNativeTestOutputDir.get().asFile
        val outputExe = outputDir.resolve("winuiIndirectPointerInputTest.exe")
        val outputObj = outputDir.resolve("winuiIndirectPointerInputTest.obj")
        val coreObj = outputDir.resolve("winuiIndirectPointerInput.obj")
        val compileLog = outputDir.resolve("compile.log")
        val batchFile = outputDir.resolve("compile.cmd")
        val vcvars64 = vcvars64Bat()
        outputDir.mkdirs()

        batchFile.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("setlocal")
                appendLine("call ${vcvars64.toResponseFilePath()} >nul || exit /b 1")
                appendLine(
                    "cl.exe /nologo /std:c++20 /EHsc /W4 /utf-8 " +
                        "/DWIN32_LEAN_AND_MEAN /DNOMINMAX " +
                        "/I${winuiIndirectPointerNativeHeader.asFile.parentFile.toResponseFilePath()} " +
                        "/Fo${coreObj.toResponseFilePath()} /c " +
                        winuiIndirectPointerNativeSource.asFile.toResponseFilePath() +
                        " || exit /b 1"
                )
                appendLine(
                    "cl.exe /nologo /std:c++20 /EHsc /W4 /utf-8 " +
                        "/DWIN32_LEAN_AND_MEAN /DNOMINMAX " +
                        "/I${winuiIndirectPointerNativeHeader.asFile.parentFile.toResponseFilePath()} " +
                        "/Fo${outputObj.toResponseFilePath()} /c " +
                        winuiIndirectPointerNativeTestSource.asFile.toResponseFilePath() +
                        " || exit /b 1"
                )
                appendLine(
                    "link.exe /NOLOGO /OUT:${outputExe.toResponseFilePath()} " +
                        "${coreObj.toResponseFilePath()} ${outputObj.toResponseFilePath()} " +
                        "User32.lib Comctl32.lib Ole32.lib || exit /b 1"
                )
            }
        )

        val compileExitCode = ProcessBuilder(
            "cmd.exe",
            "/d",
            "/s",
            "/c",
            "\"${batchFile.absolutePath}\"",
        )
            .redirectErrorStream(true)
            .redirectOutput(compileLog)
            .start()
            .waitFor()
        if (compileExitCode != 0) {
            throw GradleException(
                "WinUI indirect pointer native test compilation failed with exit code " +
                    "$compileExitCode.\n${compileLog.readText()}"
            )
        }

        runWinuiProcess(listOf(outputExe.absolutePath), workingDir = outputDir)
    }
}

tasks.register<Exec>("compileWinuiJvmNativeWindowsX64") {
    group = "build"
    description = "Compiles the skiko-winui JVM Windows native helper without using Skiko AWT native sources."
    dependsOn(resolveWinuiJvmNativeWindowsAppSdk)

    inputs.file(winuiJvmNativeSource)
    outputs.file(winuiJvmNativeOutputDir.map { it.file("skiko-winui.dll") })

    onlyIf {
        val source = winuiJvmNativeSource.asFile
        val output = winuiJvmNativeOutputDir.get().asFile.resolve("skiko-winui.dll")
        isWindowsHost && (!output.isFile || source.lastModified() > output.lastModified())
    }

    doFirst {
        val jdkHome = File(System.getProperty("java.home"))
        val outputDir = winuiJvmNativeOutputDir.get().asFile
        val outputDll = outputDir.resolve("skiko-winui.dll")
        val outputObj = outputDir.resolve("winuiRedrawer.obj")
        val outputLib = outputDir.resolve("skiko-winui.lib")
        val source = winuiJvmNativeSource.asFile
        val vcvars64 = vcvars64Bat()
        val winuiDxInteropHeader = fileTree(winuiJvmNativeNuGetInstallDir.get().asFile) {
            include("**/include/microsoft.ui.xaml.media.dxinterop.h")
        }.files.firstOrNull()
            ?: throw GradleException("WinUI dxinterop header was not resolved from Microsoft.WindowsAppSDK NuGet.")
        val winuiIncludeDir = winuiDxInteropHeader.parentFile

        outputDir.mkdirs()
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

val compileWinuiMingwNativeWindowsX64 by tasks.registering {
    group = "build"
    description = "Compiles the skiko-winui Kotlin/Native mingwX64 Direct3D bridge as a static archive."
    dependsOn(resolveWinuiJvmNativeWindowsAppSdk)

    inputs.file(winuiJvmNativeSource)
    inputs.property("windowsSdkVersion", winuiNativeWindowsSdkVersion)
    inputs.property("mingwLlvmDir", winuiMingwLlvmDir.orNull ?: "")
    inputs.property("mingwSysroot", winuiMingwSysroot.orNull ?: "")
    outputs.file(winuiMingwNativeArchive)

    onlyIf { isWindowsHost }

    doLast {
        val llvmDir = winuiMingwLlvmDir.orNull
            ?.let(::File)
            ?: defaultKonanLlvmDir()
            ?: throw GradleException(
                "Kotlin/Native LLVM tools were not found. Set -Pskiko.winui.mingw.llvmDir=<dir with clang++.exe>."
            )
        val sysroot = winuiMingwSysroot.orNull
            ?.let(::File)
            ?: defaultMingwSysroot()
            ?: throw GradleException(
                "Kotlin/Native MinGW sysroot was not found. Set -Pskiko.winui.mingw.sysroot=<x86_64-w64-mingw32>."
            )
        val clang = llvmDir.resolve("clang++.exe")
        val ar = llvmDir.resolve("llvm-ar.exe")
        if (!clang.isFile) {
            throw GradleException("clang++.exe not found at '$clang'.")
        }
        if (!ar.isFile) {
            throw GradleException("llvm-ar.exe not found at '$ar'.")
        }

        val windowsSdkVersion = winuiNativeWindowsSdkVersion.get()
        val windowsSdkIncludes = windowsSdkIncludeDirs(windowsSdkVersion)
        val missingWindowsIncludes = windowsSdkIncludes.filterNot(File::isDirectory)
        if (missingWindowsIncludes.isNotEmpty()) {
            throw GradleException(
                "Windows SDK include directories were not found for $windowsSdkVersion:\n" +
                    missingWindowsIncludes.joinToString("\n")
            )
        }

        val winuiDxInteropHeader = fileTree(winuiJvmNativeNuGetInstallDir.get().asFile) {
            include("**/include/microsoft.ui.xaml.media.dxinterop.h")
        }.files.firstOrNull()
            ?: throw GradleException("WinUI dxinterop header was not resolved from Microsoft.WindowsAppSDK NuGet.")
        val winuiIncludeDir = winuiDxInteropHeader.parentFile

        val outputDir = winuiMingwNativeOutputDir.get().asFile
        val objectsDir = winuiMingwNativeObjectsDir.get().asFile
        val objectFile = objectsDir.resolve("winuiRedrawer.o")
        val archiveFile = winuiMingwNativeArchive.get().asFile
        outputDir.mkdirs()
        objectsDir.mkdirs()

        runWinuiProcess(
            listOf(
                clang.absolutePath,
                "-target",
                "x86_64-w64-windows-gnu",
                "--sysroot=${sysroot.absolutePath}",
                "-c",
                "-std=c++20",
                "-O2",
                "-fno-exceptions",
                "-fno-rtti",
                "-DSK_DIRECT3D",
                "-DSKIKO_WINUI_MINGW",
                "-DCOM_NO_WINDOWS_H",
                "-DWIN32_LEAN_AND_MEAN",
                "-DNOMINMAX",
                "-I${winuiIncludeDir.absolutePath}",
            ) +
                windowsSdkIncludes.flatMap { listOf("-idirafter", it.absolutePath) } +
                listOf(
                "-I${sysroot.resolve("include").absolutePath}",
                "-o",
                objectFile.absolutePath,
                winuiJvmNativeSource.asFile.absolutePath,
            )
        )

        if (archiveFile.isFile) {
            archiveFile.delete()
        }
        runWinuiProcess(
            listOf(
                ar.absolutePath,
                "crs",
                archiveFile.absolutePath,
                objectFile.absolutePath,
            )
        )
    }
}

val compileWinuiMingwSkikoNativeWindowsX64 by tasks.registering {
    group = "build"
    description = "Builds the MSVC ABI Skia C-export bridge DLL/import library used by winui-mingw."

    val sources = skikoWinuiNativeSourcesForMingwBridge()
    inputs.files(sources)
    inputs.dir(skikoProjectFile("src/commonMain/cpp/common/include"))
    inputs.dir(skikoProjectFile("src/nativeJsMain/cpp"))
    outputs.file(winuiMingwSkikoNativeDllFile)
    outputs.file(winuiMingwSkikoNativeImportLibFile)

    onlyIf { isWindowsHost }
    dependsOnIncludedSkikoWindowsSkia()

    doLast {
        val skiaDir = defaultWindowsSkiaDir()
            ?: throw GradleException("Skia Windows Release-x64 dependency was not found under skiko/dependencies/skia.")
        val skiaLibDir = skiaDir.resolve("out/Release-windows-x64")
        val missingLibs = skikoWinuiMainSkiaLibs(skiaLibDir).filterNot(File::isFile)
        if (missingLibs.isNotEmpty()) {
            throw GradleException("Missing Skia libraries:\n${missingLibs.joinToString("\n")}")
        }

        val vcvars64 = vcvars64Bat()
        if (!vcvars64.isFile) {
            throw GradleException("vcvars64.bat not found at '$vcvars64'.")
        }

        val outputDir = winuiMingwSkikoNativeOutputDir.get().asFile
        val objectsDir = winuiMingwSkikoNativeObjectsDir.get().asFile
        val dll = winuiMingwSkikoNativeDllFile.get().asFile
        val importLib = winuiMingwSkikoNativeImportLibFile.get().asFile
        val defFile = outputDir.resolve("skiko_winui_skia.def")
        val batchFile = outputDir.resolve("compile-winui-mingw-skia-bridge.cmd")
        val logFile = outputDir.resolve("compile-winui-mingw-skia-bridge.log")
        val setupLogFile = outputDir.resolve("compile-winui-mingw-skia-bridge-setup.log")
        val launcherLogFile = outputDir.resolve("compile-winui-mingw-skia-bridge-launcher.log")
        val linkResponseFile = outputDir.resolve("link-winui-mingw-skia-bridge.rsp")
        outputDir.mkdirs()
        objectsDir.mkdirs()

        val includeDirs = windowsSkiaIncludeDirs(skiaDir) + listOf(
            skikoProjectFile("src/commonMain/cpp/common/include"),
            skikoProjectFile("src/nativeJsMain/cpp"),
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

        defFile.writeText(
            buildString {
                appendLine("LIBRARY skiko_winui_skia")
                appendLine("EXPORTS")
                skikoWinuiNativeExportNames(sources).forEach { appendLine("    $it") }
            }
        )

        linkResponseFile.writeText(
            buildString {
                appendLine("/NOLOGO")
                appendLine("/DLL")
                appendLine("/DEBUG")
                appendLine("/OUT:${dll.toResponseFilePath()}")
                appendLine("/IMPLIB:${importLib.toResponseFilePath()}")
                appendLine("/DEF:${defFile.toResponseFilePath()}")
                appendLine("/alternatename:__std_search_1=skiko_winui___std_search_1")
                appendLine("/alternatename:__std_find_first_of_trivial_pos_1=skiko_winui___std_find_first_of_trivial_pos_1")
                appendLine("/alternatename:__std_remove_8=skiko_winui___std_remove_8")
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
                appendLine("set \"SETUP_LOG=${setupLogFile.toCommandLinePath()}\"")
                appendLine("echo Starting winui-mingw Skia bridge build > \"%LOG%\"")
                appendLine("echo Initializing MSVC environment > \"%SETUP_LOG%\"")
                appendLine("call ${vcvars64.toResponseFilePath()} >> \"%SETUP_LOG%\" 2>&1 || goto :fail")
                appendLine("where clang-cl.exe >> \"%LOG%\" 2>&1 && set \"CL_CMD=clang-cl.exe\"")
                appendLine("if not defined CL_CMD where cl.exe >> \"%LOG%\" 2>&1 && set \"CL_CMD=cl.exe\"")
                appendLine("if not defined CL_CMD goto :fail")
                appendLine("where lld-link.exe >> \"%LOG%\" 2>&1 && set \"LINK_CMD=lld-link.exe\"")
                appendLine("if not defined LINK_CMD where link.exe >> \"%LOG%\" 2>&1 && set \"LINK_CMD=link.exe\"")
                appendLine("if not defined LINK_CMD goto :fail")
                appendLine("echo Using compiler %CL_CMD% >> \"%LOG%\"")
                appendLine("echo Using linker %LINK_CMD% >> \"%LOG%\"")
                compileResponseFiles.forEach { (source, responseFile) ->
                    appendLine("echo cl ${source.name} >> \"%LOG%\"")
                    appendLine("%CL_CMD% @${responseFile.toResponseFilePath()} >> \"%LOG%\" 2>&1 || goto :fail")
                }
                appendLine("echo link ${dll.name} >> \"%LOG%\"")
                appendLine("%LINK_CMD% @${linkResponseFile.toResponseFilePath()} >> \"%LOG%\" 2>&1 || goto :fail")
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
            val setupLog = if (setupLogFile.isFile) {
                setupLogFile.readLines()
                    .takeLast(80)
                    .joinToString(System.lineSeparator())
            } else {
                "<missing setup log>"
            }
            logger.lifecycle(
                """
                winui-mingw Skia bridge compile failed.
                exitCode=$exitCode
                batchFile=${batchFile.absolutePath}
                logFile=${logFile.absolutePath}
                setupLogFile=${setupLogFile.absolutePath}
                launcherLogFile=${launcherLogFile.absolutePath}
                ---- native log ----
                $nativeLog
                ---- setup log ----
                $setupLog
                """.trimIndent()
            )
            throw GradleException("winui-mingw Skia C ABI bridge compilation failed with exit code $exitCode.")
        }
    }
}

val compileWinuiSkikoWindowsX64 by tasks.registering {
    group = "build"
    description = "Builds the AWT-free skiko-winui Windows JVM Skiko native runtime DLL."

    val sources = skikoWinuiMainSources()
    inputs.files(sources)
    inputs.dir(skikoProjectFile("src/commonMain/cpp/common/include"))
    inputs.dir(skikoProjectFile("src/jvmMain/cpp/common"))
    outputs.file(winuiSkikoWindowsDllFile)

    onlyIf {
        isWindowsHost
    }
    dependsOnIncludedSkikoWindowsSkia()

    doLast {
        val skiaDir = defaultWindowsSkiaDir()
            ?: throw GradleException("Skia Windows Release-x64 dependency was not found under skiko/dependencies/skia.")
        val skiaLibDir = skiaDir.resolve("out/Release-windows-x64")
        val missingLibs = skikoWinuiMainSkiaLibs(skiaLibDir).filterNot(File::isFile)
        if (missingLibs.isNotEmpty()) {
            throw GradleException("Missing Skia libraries:\n${missingLibs.joinToString("\n")}")
        }

        val jdkHome = File(System.getProperty("java.home"))
        val vcvars64 = vcvars64Bat()

        val outputDir = winuiSkikoWindowsOutputDir.get().asFile
        val objectsDir = winuiSkikoWindowsObjectsDir.get().asFile
        val dll = winuiSkikoWindowsDllFile.get().asFile
        val importLib = outputDir.resolve("skiko-windows-x64.lib")
        val batchFile = outputDir.resolve("compile-skiko-winui-windows.cmd")
        val logFile = outputDir.resolve("compile-skiko-winui-windows.log")
        val setupLogFile = outputDir.resolve("compile-skiko-winui-windows-setup.log")
        val launcherLogFile = outputDir.resolve("compile-skiko-winui-windows-launcher.log")
        val linkResponseFile = outputDir.resolve("link-skiko-winui-windows.rsp")
        outputDir.mkdirs()
        objectsDir.mkdirs()
        val includeDirs = windowsSkiaIncludeDirs(skiaDir) + listOf(
            jdkHome.resolve("include"),
            jdkHome.resolve("include/win32"),
            skikoProjectFile("src/commonMain/cpp/common/include"),
            skikoProjectFile("src/jvmMain/cpp/common"),
            skikoProjectFile("src/jvmMain/cpp/include"),
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
                appendLine("/alternatename:__std_search_1=skiko_winui___std_search_1")
                appendLine("/alternatename:__std_find_first_of_trivial_pos_1=skiko_winui___std_find_first_of_trivial_pos_1")
                appendLine("/alternatename:__std_remove_8=skiko_winui___std_remove_8")
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
                appendLine("set \"SETUP_LOG=${setupLogFile.toCommandLinePath()}\"")
                appendLine("echo Starting WinUI Skiko native build > \"%LOG%\"")
                appendLine("echo Initializing MSVC environment > \"%SETUP_LOG%\"")
                appendLine("call ${vcvars64.toResponseFilePath()} >> \"%SETUP_LOG%\" 2>&1 || goto :fail")
                appendLine("where clang-cl.exe >> \"%LOG%\" 2>&1 && set \"CL_CMD=clang-cl.exe\"")
                appendLine("if not defined CL_CMD where cl.exe >> \"%LOG%\" 2>&1 && set \"CL_CMD=cl.exe\"")
                appendLine("if not defined CL_CMD goto :fail")
                appendLine("where lld-link.exe >> \"%LOG%\" 2>&1 && set \"LINK_CMD=lld-link.exe\"")
                appendLine("if not defined LINK_CMD where link.exe >> \"%LOG%\" 2>&1 && set \"LINK_CMD=link.exe\"")
                appendLine("if not defined LINK_CMD goto :fail")
                appendLine("echo Using compiler %CL_CMD% >> \"%LOG%\"")
                appendLine("echo Using linker %LINK_CMD% >> \"%LOG%\"")
                compileResponseFiles.forEach { (source, responseFile) ->
                    appendLine("echo cl ${source.name} >> \"%LOG%\"")
                    appendLine("%CL_CMD% @${responseFile.toResponseFilePath()} >> \"%LOG%\" 2>&1 || goto :fail")
                }
                appendLine("echo link ${dll.name} >> \"%LOG%\"")
                appendLine("%LINK_CMD% @${linkResponseFile.toResponseFilePath()} >> \"%LOG%\" 2>&1 || goto :fail")
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
            val setupLog = if (setupLogFile.isFile) {
                setupLogFile.readLines()
                    .takeLast(80)
                    .joinToString(System.lineSeparator())
            } else {
                "<missing setup log>"
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
                setupLogFile=${setupLogFile.absolutePath}
                launcherLogFile=${launcherLogFile.absolutePath}
                logExists=${logFile.isFile}
                logLength=${if (logFile.isFile) logFile.length() else 0}
                ---- native log ----
                $nativeLog
                ---- setup log ----
                $setupLog
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
    inputs.property("winuiNativeWindowsSkiaDir", winuiNativeWindowsSkiaDir.orNull ?: "")
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
                "Missing Windows ICU data. Set -Pskiko.winui.windowsIcuData=<path to $skikoWinuiWindowsRuntimeIcuName> " +
                    "or use -Pskiko.winui.windowsSkiaDir=<Skia Windows package> with out/Release-windows-x64/$skikoWinuiWindowsRuntimeIcuName."
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
