# Skiko WinUI DSL Ordering Design

Date: 2026-07-16

Status: Implemented and validated

## Problem

The latest `kotlin-winrt` source can resolve and project the Windows SDK and
Windows App SDK on its own. In the Skiko composite build, `winui-jvm` succeeds,
but enabling `winui-mingw` can leave the projection generation task with
`emitProjectionSources=false` even though the final `winRT` DSL contains the
expected SDK, package, namespace, and type selections. The generated projection
sources are then absent when Native compilation starts.

The relevant Skiko script currently creates the `winuiMingw` Kotlin/Native
target before configuring the `winRT` extension. Target and source-set setup can
realize the kotlin-winrt local-generation provider during this interval, before
the projection selections are complete.

## Goals

- Configure the complete Skiko `winRT` DSL before creating WinUI JVM or Native
  targets.
- Keep the change local to Skiko's WinUI build script.
- Preserve the existing `winui-jvm` and `winui-mingw` source-set and compiler
  wiring.
- Validate both backend paths using the latest local `kotlin-winrt` composite
  build.

## Non-goals

- Do not change `kotlin-winrt` implementation or tests.
- Do not change Windows SDK or Windows App SDK versions.
- Do not introduce a new public backend API or restructure source sets.
- Do not edit the curated root `PLAN.md`.

## Design

In `skiko/gradle/winui.gradle.kts`, keep `apply<KotlinWinRTPlugin>()` first so
the extension exists, then move the existing `extensions.configure<WinRTExtension>("winRT")`
block ahead of `extensions.configure<KotlinMultiplatformExtension>("kotlin")`.
The block only uses version providers and the projection selection list; it does
not depend on Kotlin targets, so this ordering is valid.

The resulting configuration flow is:

1. Apply the kotlin-winrt Gradle plugin and create its extension.
2. Configure Windows SDK, Windows App SDK, namespace, and explicit projection
   types.
3. Create `winuiJvm` and, when enabled, `winuiMingw` targets.
4. Let the plugin attach WinUI source sets, generated sources, and compiler
   options using the already-complete projection configuration.
5. Apply Skiko's Windows native build script and packaging tasks.

No runtime behavior changes. Existing Gradle failures for missing SDK roots,
native tools, or libraries remain unchanged and continue to report at the
existing task boundary.

## Validation

Use the local composite plugin source:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

& .\gradlew.bat '--no-daemon' `
  '--include-build' 'E:\Documents\AndroidStudioProjects\kotlin-winrt\winrt-gradle-plugin' `
  '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=false' `
  ':skiko:compileKotlinWinuiJvm'

& .\gradlew.bat '--no-daemon' `
  '--include-build' 'E:\Documents\AndroidStudioProjects\kotlin-winrt\winrt-gradle-plugin' `
  '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=true' `
  ':skiko:compileKotlinWinuiMingw'
```

The JVM and Native runs must both succeed. The Native run must retain
`emitProjectionSources=true` and produce the projection sources required by
`winuiMain`; the previous unresolved WinUI projection errors must be absent.

The `kotlin-winrt` working tree must remain unchanged.

The host requirement is JDK 25 or newer because the current kotlin-winrt
Gradle plugin artifact targets JVM 25.

## Rollback

Rollback consists of moving the unchanged `winRT` configuration block back to
its original location. No generated files or public APIs are part of this
change.
