# Skiko WinUI DSL Ordering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Skiko configure its complete WinRT projection DSL before creating `winui-jvm` and `winui-mingw` targets, so the latest local `kotlin-winrt` plugin cannot freeze Native projection generation from incomplete configuration.

**Architecture:** Keep the existing `KotlinWinRTPlugin` application and all target/source-set wiring unchanged. Move the existing `WinRTExtension` configuration in Skiko's WinUI Gradle script ahead of the Kotlin Multiplatform target block; the DSL only consumes version providers and the static projection type list, so it has no target dependency.

**Tech Stack:** Gradle Kotlin DSL, Kotlin Multiplatform, Kotlin/Native `mingwX64`, local Gradle composite build of `kotlin-winrt`.

## Global Constraints

- Modify only Skiko's WinUI Gradle configuration for this fix.
- Do not modify the `kotlin-winrt` repository.
- Preserve the existing `winui-jvm` and `winui-mingw` source-set, compiler, native-link, and packaging wiring.
- Do not edit the curated root `PLAN.md`.
- Preserve unrelated pre-existing changes in `gradle.properties`, `skiko/build.gradle.kts`, and `skiko/buildSrc`.
- Use `--include-build E:\Documents\AndroidStudioProjects\kotlin-winrt\winrt-gradle-plugin` for validation.
- Run Gradle with JDK 25 or newer; the validated installation is `C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot`.

---

### Task 1: Move WinRT DSL Configuration Before Target Creation

**Files:**
- Modify: `skiko/gradle/winui.gradle.kts:306-313` by moving the unchanged `winRT` block to immediately after `winuiProjectionTypes` and before `extensions.configure<KotlinMultiplatformExtension>("kotlin")`.
- Test: `skiko/gradle/winui.gradle.kts` through the baseline and post-change Gradle commands below.

**Interfaces:**
- Consumes: `winuiWindowsSdkVersion`, `winuiWindowsAppSdkVersion`, and `winuiProjectionTypes` already declared in the script.
- Produces: A fully configured `WinRTExtension` before any `winuiJvm` or `winuiMingw` target is created.

- [x] **Step 1: Reproduce the current Native failure as the baseline**

Run from `E:\Documents\AndroidStudioProjects\compose-fluent-skiko`:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& .\gradlew.bat '--no-daemon' `
  '--include-build' 'E:\Documents\AndroidStudioProjects\kotlin-winrt\winrt-gradle-plugin' `
  '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=true' `
  ':skiko:compileKotlinWinuiMingw' '--stacktrace'
~~~

Expected before the edit: the projection generation task reports
`emitProjectionSources=false`, clears the generated projection directory, and
Native compilation fails with unresolved WinUI projection types such as
`Window`, `FrameworkElement`, and `SwapChainPanel`.

- [x] **Step 2: Move the unchanged WinRT block without changing its values**

Place this block after the `winuiProjectionTypes` list and before the Kotlin
Multiplatform target configuration:

~~~kotlin
extensions.configure<io.github.composefluent.winrt.gradle.WinRTExtension>("winRT") {
    windowsSdk(winuiWindowsSdkVersion.get(), includeExtensions = false, generateProjection = true)
    nugetPackage("Microsoft.WindowsAppSDK", winuiWindowsAppSdkVersion.get()) {
        generateProjection = true
    }
    namespace("Microsoft.UI.Windowing")
    winuiProjectionTypes.forEach(::type)
}
~~~

Remove the original block from its old location after the `sourceSets` block.
Do not change the SDK versions, package version providers, namespace, or type
list.

- [x] **Step 3: Check the diff is limited to ordering**

Run:

~~~powershell
git diff -- skiko/gradle/winui.gradle.kts
git diff --check -- skiko/gradle/winui.gradle.kts
~~~

Expected: the diff contains one removed and one added copy of the same `winRT`
block, with no changes to its contents and no whitespace errors.

- [x] **Step 4: Commit the focused implementation**

Run:

~~~powershell
git add -- skiko/gradle/winui.gradle.kts
git commit -m "fix: configure Skiko WinRT DSL before targets"
~~~

Expected: only `skiko/gradle/winui.gradle.kts` is included in the commit; the
pre-existing modified files remain unstaged.

### Task 2: Verify Both Skiko Windows Backend Paths

**Files:**
- Modify: none.
- Test: Gradle compilation tasks and generated projection output under `skiko/build`.

**Interfaces:**
- Consumes: the reordered `WinRTExtension` configuration from Task 1 and the
  local `kotlin-winrt` Gradle plugin composite.
- Produces: successful `winui-jvm` and `winui-mingw` compilation, with generated
  projection Kotlin sources present for the Native path.

- [x] **Step 1: Verify the JVM path with the Native target disabled**

Run:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& .\gradlew.bat '--no-daemon' `
  '--include-build' 'E:\Documents\AndroidStudioProjects\kotlin-winrt\winrt-gradle-plugin' `
  '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=false' `
  ':skiko:compileKotlinWinuiJvm' '--stacktrace'
~~~

Expected: `BUILD SUCCESSFUL`; projection generation remains enabled and the
JVM backend compiles against the generated WinRT types.

- [x] **Step 2: Verify the Native path with the WinUI Mingw target enabled**

Run:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& .\gradlew.bat '--no-daemon' `
  '--include-build' 'E:\Documents\AndroidStudioProjects\kotlin-winrt\winrt-gradle-plugin' `
  '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=true' `
  ':skiko:compileKotlinWinuiMingw' '--stacktrace'
~~~

Expected: `BUILD SUCCESSFUL`; the generation task keeps
`emitProjectionSources=true`, and Native compilation has no unresolved WinUI
projection types.

- [x] **Step 3: Confirm generated projection ownership and repository scope**

Run:

~~~powershell
$projectionRoot = 'skiko/build/generated/kotlin-winrt/src/winuiMain/kotlin'
$projectionCount = (Get-ChildItem -LiteralPath $projectionRoot -Recurse -Filter '*.kt' -ErrorAction Stop).Count
if ($projectionCount -le 0) { throw "No generated projection Kotlin sources found under $projectionRoot" }
Write-Output "GENERATED_PROJECTION_KT=$projectionCount"

git -C E:\Documents\AndroidStudioProjects\kotlin-winrt status --short
git status --short
~~~

Expected: `GENERATED_PROJECTION_KT` is greater than zero; `kotlin-winrt` has no
tracked modifications; Skiko still shows only the known pre-existing changes
plus the focused implementation commit.
