param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$kotlinWinRtRoot = Join-Path (Split-Path $repoRoot -Parent) "kotlin-winrt"
$gradleWrapper = Join-Path $kotlinWinRtRoot "gradlew.bat"
$javaHome = "C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot"

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "kotlin-winrt Gradle wrapper not found at '$gradleWrapper'."
}
if (-not (Test-Path -LiteralPath $javaHome -PathType Container)) {
    throw "JDK 25 not found at '$javaHome'."
}

$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

function Invoke-Gradle {
    param(
        [string[]] $Arguments
    )

    & $gradleWrapper -p $repoRoot @Arguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

function Repair-GeneratedWinRtKotlinImports {
    $generatedRoots = @(
        (Join-Path $repoRoot "skiko\skiko-winui\build\generated\kotlin-winrt\src\main\kotlin"),
        (Join-Path $repoRoot "skiko\skiko-winui\build\generated\kotlin-winrt-authoring\src\main\kotlin"),
        (Join-Path $repoRoot "skiko\skiko-winui\build\generated\kotlin-winrt-compiler-authoring\compileKotlinWinuiJvm\src\main\kotlin")
    )
    $existingGeneratedRoots = $generatedRoots | Where-Object { Test-Path -LiteralPath $_ -PathType Container }
    if ($existingGeneratedRoots.Count -eq 0) {
        throw "Generated kotlin-winrt source roots not found."
    }

    $replacements = [ordered]@{
        "import java.lang.AutoCloseable" = "import kotlin.AutoCloseable"
        "import java.lang.Exception" = "import kotlin.Exception"
    }

    $existingGeneratedRoots | ForEach-Object {
        Get-ChildItem -LiteralPath $_ -Recurse -Filter *.kt | ForEach-Object {
            $path = $_.FullName
            $content = Get-Content -LiteralPath $path -Raw
            $updated = $content
            foreach ($entry in $replacements.GetEnumerator()) {
                $updated = $updated.Replace($entry.Key, $entry.Value)
            }
            if ($updated -ne $content) {
                Set-Content -LiteralPath $path -Value $updated -NoNewline
            }
        }
    }
}

function Restore-GeneratedWinRtProjectionIntrinsicImports {
    $generatedRoot = Join-Path $repoRoot "skiko\skiko-winui\build\generated\kotlin-winrt\src\main\kotlin"
    if (-not (Test-Path -LiteralPath $generatedRoot -PathType Container)) {
        throw "Generated kotlin-winrt source root not found."
    }

    $jvmImport = "import io.github.composefluent.winrt.runtime.WinRtProjectionIntrinsic"
    $mingwImport = "import io.github.composefluent.winrt.runtime.WinRtProjectionIntrinsicNativeDisabled as WinRtProjectionIntrinsic"
    $corruptImportPattern = "import io\.github\.composefluent\.winrt\.runtime\.(?:WinRtProjectionIntrinsicNativeDisabled as )+WinRtProjectionIntrinsic"
    Get-ChildItem -LiteralPath $generatedRoot -Recurse -Filter *.kt | ForEach-Object {
        $path = $_.FullName
        $content = Get-Content -LiteralPath $path -Raw
        $updated = $content -replace $corruptImportPattern, $jvmImport
        $updated = $updated.Replace($mingwImport, $jvmImport)
        if ($updated -ne $content) {
            Set-Content -LiteralPath $path -Value $updated -NoNewline
        }
    }
}

function Repair-GeneratedWinRtAuthoringSources {
    $scannerRoot = Join-Path $repoRoot "skiko\skiko-winui\build\generated\kotlin-winrt-authoring\src\main\kotlin"
    $typeDetailsPackagePath = "org\jetbrains\skiko\winui"
    $scannerTypeDetailsRoot = Join-Path $scannerRoot $typeDetailsPackagePath
    $compilerRoots = @(
        (Join-Path $repoRoot "skiko\skiko-winui\build\generated\kotlin-winrt-compiler-authoring\compileKotlinWinuiJvm\src\main\kotlin"),
        (Join-Path $repoRoot "skiko\skiko-winui\build\generated\kotlin-winrt-compiler-authoring\compileKotlinWinuiMingw\src\main\kotlin")
    )

    foreach ($compilerRoot in $compilerRoots) {
        $compilerTypeDetailsRoot = Join-Path $compilerRoot $typeDetailsPackagePath
        if (-not (Test-Path -LiteralPath $compilerTypeDetailsRoot -PathType Container)) {
            continue
        }
        New-Item -ItemType Directory -Force $scannerTypeDetailsRoot | Out-Null
        Get-ChildItem -LiteralPath $compilerTypeDetailsRoot -Filter "WinRT_*_TypeDetails.kt" | ForEach-Object {
            $destination = Join-Path $scannerTypeDetailsRoot $_.Name
            if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
                Copy-Item -LiteralPath $_.FullName -Destination $destination
            }
        }
    }

    if (-not (Test-Path -LiteralPath $scannerTypeDetailsRoot -PathType Container)) {
        return
    }

    $typeDetails = Get-ChildItem -LiteralPath $scannerTypeDetailsRoot -Filter "WinRT_*_TypeDetails.kt" |
        ForEach-Object { [System.IO.Path]::GetFileNameWithoutExtension($_.Name) } |
        Sort-Object
    if ($typeDetails.Count -eq 0) {
        return
    }

    $registrarRoot = Join-Path $scannerRoot "io\github\composefluent\winrt\projections\support"
    New-Item -ItemType Directory -Force $registrarRoot | Out-Null
    $registrarFile = Join-Path $registrarRoot "WinRTAuthoringTypeDetailsRegistrar_skiko_winui.kt"
    $imports = $typeDetails | ForEach-Object { "import org.jetbrains.skiko.winui.$_" }
    $calls = $typeDetails | ForEach-Object { "    $_.register()" }
    $contentLines = @(
        "package io.github.composefluent.winrt.projections.support",
        ""
    ) + $imports + @(
        "",
        "internal object WinRTAuthoringTypeDetailsRegistrar_skiko_winui {",
        "  public fun register() {"
    ) + $calls + @(
        "  }",
        "}"
    )
    $content = $contentLines -join "`r`n"
    Set-Content -LiteralPath $registrarFile -Value $content -NoNewline

    foreach ($compilerRoot in $compilerRoots) {
        $compilerTypeDetailsRoot = Join-Path $compilerRoot $typeDetailsPackagePath
        New-Item -ItemType Directory -Force $compilerTypeDetailsRoot | Out-Null
        Get-ChildItem -LiteralPath $scannerTypeDetailsRoot -Filter "WinRT_*_TypeDetails.kt" | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $compilerTypeDetailsRoot $_.Name) -Force
        }
        $compilerRegistrarRoot = Join-Path $compilerRoot "io\github\composefluent\winrt\projections\support"
        New-Item -ItemType Directory -Force $compilerRegistrarRoot | Out-Null
        Copy-Item -LiteralPath $registrarFile -Destination (Join-Path $compilerRegistrarRoot "WinRTAuthoringTypeDetailsRegistrar_skiko_winui.kt") -Force
    }
}

function Test-GeneratedWinRtProjectionsExist {
    $generatedRoot = Join-Path $repoRoot "skiko\skiko-winui\build\generated\kotlin-winrt\src\main\kotlin"
    $supportFile = Join-Path $generatedRoot "kotlin-winrt-support\type-shape-descriptors.tsv"
    return (Test-Path -LiteralPath $supportFile -PathType Leaf)
}

function Invoke-GenerateWinRtProjectionsIfMissing {
    param(
        [string[]] $Arguments
    )

    if (Test-GeneratedWinRtProjectionsExist) {
        return
    }
    Invoke-Gradle $Arguments
}

if ($GradleArgs.Count -eq 1 -and $GradleArgs[0] -eq "winui-smoke") {
    $GenerateArgs = @(
        "-Dskiko.winui.skipSkikoComposite=true",
        "-Pskiko.winui.jvmTarget=25",
        "-Pskiko.winui.jvmToolchain=25",
        "-Pskiko.winui.localSkikoJar=skiko/build/libs/skiko-awt-0.0.0-SNAPSHOT.jar",
        "-Pskiko.winui.localWinRtRuntimeJar=$kotlinWinRtRoot\winrt-runtime\build\libs\winrt-runtime-jvm.jar",
        "-Pskiko.winui.localWinRtAuthoringJar=$kotlinWinRtRoot\winrt-authoring\build\libs\winrt-authoring-0.1.0-SNAPSHOT.jar",
        "-Dkotlin.daemon.jvmargs=-Xmx8192m",
        "-Dorg.gradle.jvmargs=-Xmx8192m",
        "--console=plain",
        ":skiko-winui:generateWinRtProjections"
    )
    Invoke-GenerateWinRtProjectionsIfMissing $GenerateArgs
    Restore-GeneratedWinRtProjectionIntrinsicImports
    Repair-GeneratedWinRtKotlinImports
    Repair-GeneratedWinRtAuthoringSources
    Repair-GeneratedWinRtKotlinImports

    $GradleArgs = @(
        "-Dskiko.winui.skipSkikoComposite=true",
        "-Pskiko.winui.skipProjectionGeneration=true",
        "-Pskiko.winui.smokeArgs=--use-layer-attach",
        "-Pskiko.winui.jvmTarget=25",
        "-Pskiko.winui.jvmToolchain=25",
        "-Pskiko.winui.localSkikoJar=skiko/build/libs/skiko-awt-0.0.0-SNAPSHOT.jar",
        "-Pskiko.winui.localWinRtRuntimeJar=$kotlinWinRtRoot\winrt-runtime\build\libs\winrt-runtime-jvm.jar",
        "-Pskiko.winui.localWinRtAuthoringJar=$kotlinWinRtRoot\winrt-authoring\build\libs\winrt-authoring-0.1.0-SNAPSHOT.jar",
        "-Dkotlin.daemon.jvmargs=-Xmx8192m",
        "-Dorg.gradle.jvmargs=-Xmx8192m",
        "--console=plain",
        "-x",
        ":skiko-winui:validateCompileKotlinWinuiJvmWinRtAuthoredCandidates",
        ":skiko-winui:runWinuiJvmSmoke"
    )
}

if ($GradleArgs.Count -eq 1 -and $GradleArgs[0] -eq "winui-core-check") {
    $GenerateArgs = @(
        "-Dskiko.winui.skipSkikoComposite=true",
        "-Pskiko.winui.jvmTarget=25",
        "-Pskiko.winui.jvmToolchain=25",
        "-Pskiko.winui.localSkikoJar=skiko/build/libs/skiko-awt-0.0.0-SNAPSHOT.jar",
        "-Pskiko.winui.localWinRtRuntimeJar=$kotlinWinRtRoot\winrt-runtime\build\libs\winrt-runtime-jvm.jar",
        "-Pskiko.winui.localWinRtAuthoringJar=$kotlinWinRtRoot\winrt-authoring\build\libs\winrt-authoring-0.1.0-SNAPSHOT.jar",
        "-Dkotlin.daemon.jvmargs=-Xmx8192m",
        "-Dorg.gradle.jvmargs=-Xmx8192m",
        "--console=plain",
        ":skiko-winui:generateWinRtProjections"
    )
    Invoke-GenerateWinRtProjectionsIfMissing $GenerateArgs
    Restore-GeneratedWinRtProjectionIntrinsicImports
    Repair-GeneratedWinRtKotlinImports
    Repair-GeneratedWinRtAuthoringSources
    Repair-GeneratedWinRtKotlinImports

    $GradleArgs = @(
        "-Dskiko.winui.skipSkikoComposite=true",
        "-Pskiko.winui.skipProjectionGeneration=true",
        "-Pskiko.winui.jvmTarget=25",
        "-Pskiko.winui.jvmToolchain=25",
        "-Pskiko.winui.localSkikoJar=skiko/build/libs/skiko-awt-0.0.0-SNAPSHOT.jar",
        "-Pskiko.winui.localWinRtRuntimeJar=$kotlinWinRtRoot\winrt-runtime\build\libs\winrt-runtime-jvm.jar",
        "-Pskiko.winui.localWinRtAuthoringJar=$kotlinWinRtRoot\winrt-authoring\build\libs\winrt-authoring-0.1.0-SNAPSHOT.jar",
        "-Dkotlin.daemon.jvmargs=-Xmx8192m",
        "-Dorg.gradle.jvmargs=-Xmx8192m",
        "--console=plain",
        "-x",
        ":skiko-winui:validateCompileKotlinWinuiJvmWinRtAuthoredCandidates",
        ":skiko-winui:compileKotlinWinuiJvm",
        ":skiko-winui:compileTestKotlinWinuiJvm",
        ":skiko-winui:checkWinuiAwtFreeBoundary"
    )
}

if ($GradleArgs.Count -eq 1 -and $GradleArgs[0] -eq "winui-mingw-compile") {
    Repair-GeneratedWinRtKotlinImports
    Repair-GeneratedWinRtAuthoringSources
    Repair-GeneratedWinRtKotlinImports
    Restore-GeneratedWinRtProjectionIntrinsicImports
    $GradleArgs = @(
        "-Pskiko.winui.skipProjectionGeneration=true",
        "-Pskiko.winui.jvmTarget=25",
        "-Pskiko.winui.jvmToolchain=25",
        "-Pskiko.native.windows.enabled=true",
        "-Pskiko.winui.localWinRtRuntimeKlib=$kotlinWinRtRoot\winrt-runtime\build\classes\kotlin\mingwX64\main\klib\winrt-runtime",
        "-Dkotlin.daemon.jvmargs=-Xmx8192m",
        "-Dorg.gradle.jvmargs=-Xmx8192m",
        "--console=plain",
        ":skiko-winui:compileKotlinWinuiMingw"
    )
}

if ($GradleArgs.Count -eq 1 -and $GradleArgs[0] -eq "projects-skip") {
    $GradleArgs = @(
        "-Dskiko.winui.skipSkikoComposite=true",
        "--console=plain",
        "projects"
    )
}

Invoke-Gradle $GradleArgs
