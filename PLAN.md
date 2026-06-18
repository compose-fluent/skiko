# skiko-winui Plan

## Goal

Provide an AWT-free `skiko-winui` backend while keeping both required implementation paths viable:

- `winui-jvm`: JVM + WinUI `SwapChainPanel` + Direct3D/DXGI rendering.
- `winui-mingw`: Kotlin/Native `mingwX64` parity path.

Current scope is a usable WinUI backend: host a WinUI surface, render with Direct3D, resize safely, schedule frames, dispose native resources, expose basic input/focus/text contracts, and publish artifacts that downstream Compose WinUI can consume without AWT runtime coupling.

## Architecture Decisions

- [x] Public backend entry point is `org.jetbrains.skiko.winui.WinUISkiaLayer`.
- [x] `WinUISkiaLayerSurface` remains the shared non-AWT host/scheduler contract for `winui-jvm` and later `winui-mingw`.
- [x] V1 supports `GraphicsApi.DIRECT3D` only.
- [x] WinUI rendering is hosted by `Microsoft.UI.Xaml.Controls.SwapChainPanel`; public `component` is a `FrameworkElement`, currently backed by `WinUISkiaHostPanel : Grid`.
- [x] `WinUISkiaLayerPlatformInterop` owns platform differences. JVM actual code handles JNI, `ISwapChainPanelNative`, D3D/DXGI/Skia surface creation, present, and native disposal.
- [x] WinUI backend code stays out of `skiko/src/awtMain`; AWT/Swing code is reference material only.
- [x] `skiko-winui` is a reusable library project. It does not configure `winRt { application { } }`; final apps own application packaging/runtime staging.
- [x] Skiko WinUI requests only the explicit WinRT projection types it needs. Full `winrt-projections-*` artifacts are not transitive dependencies of `skiko-winui`.
- [x] `skiko-winui-windows.jar` owns the WinUI native runtime payload (`skiko-windows-x64.dll`, `icudtl.dat`) and does not reuse `skiko-awt-runtime`.
- [x] To avoid pulling `skiko-awt` through Gradle metadata, `winuiJvmJar` embeds the needed Skia/Skiko JVM API classes from the existing Skiko JVM API jar and publishes no transitive `org.jetbrains.skiko:skiko` dependency.
- [x] `skiko-winui` does not perform jar-stage filtering of generated WinRT projection classes. If explicit projection type requests still generate shared duplicate classes, that ownership/de-duplication problem belongs in kotlin-winrt, not in Skiko artifact surgery.
- [x] `WinUIRenderDispatcher.needRender()` does not synchronously present from the current WinUI callback stack. Explicit render requests are coalesced onto `DispatcherQueue` before `renderNow` / native present.

## Current Status

- [x] `winui-jvm` module/source-set layout exists under `skiko/skiko-winui`.
- [x] `winui-mingw` source-set boundary exists and shares the same public contracts, but native COM/D3D implementation is still blocked.
- [x] `WinUISkiaLayer` supports component hosting, render panel access, render delegate, input handler, focus state, content scale, lifecycle, frame scheduler, render diagnostics, attach/detach, and dispose.
- [x] Render pipeline records with `PictureRecorder`, draws cached pictures inside native render scope, and tracks render state/result diagnostics.
- [x] Resize/scale invalidation is coalesced and 0-sized render is filtered.
- [x] Pointer/key/text/focus contracts exist in `winuiMain`; CoreText edit context support is wired for JVM WinUI.
- [x] Accessibility foundation exists: layer metadata, provider/snapshot/tree contracts, diffing, automation model, `WinUISkiaHostPanel`, and `WinUISkiaAutomationPeer`.
- [x] `samples/SkiaWinUISample` is the current full app-host validation path for runtime assets and native WinUI app host generation.
- [x] `runWinuiJvmSmoke` is a library smoke task. It can reuse an app package runtime assets directory via `-Pskiko.winui.runtimeAssetsRoot=...`; by default it points at `samples/SkiaWinUISample/build/kotlin-winrt/application-package`.
- [x] `runWinuiJvmSmoke` can use a prebuilt Windows runtime jar via `-Pskiko.winui.windowsRuntimeJar=...` for focused JVM smoke runs. Without this property, it builds the current runtime jar.
- [x] 2026-06-18 synced `origin/master` into `winui_dev`: Gradle wrapper 9.5.0, Skia `m149-ace6f426df`, AGP 9.0, Android KMP library migration, BreakIterator Android crash fix, and Skia symbol visibility buildSrc tasks were merged. Conflict resolution kept `skiko-winui` / WinUI sample Kotlin 2.4 and WinUI source-set work intact.
- [x] 2026-06-18 merge validation found and fixed a buildSrc merge artifact in `BuildLocalSkiaTask.kt`: duplicate injected `execOperations` declaration after taking upstream `executable` / `args` process invocation changes.
- [x] 2026-06-18 post-merge Gradle 9.5 validation passed: `.\gradlew.bat --no-daemon --stacktrace --console=plain -p . --no-configuration-cache --max-workers=1 "-Pskiko.winui.jvmTarget=25" "-Pskiko.winui.jvmToolchain=25" "-Pskiko.winui.mingw.enabled=false" "-Pskiko.winui.skipSamples=true" :skiko-winui:tasks --all`.

## Active Work

- [x] Fix `SKIKO-006`: keep `skiko-winui` reusable without pulling `skiko-awt` transitively.
  - Current shape: `skiko-winui` embeds the required Skia/Skiko JVM API classes and publishes only `winrt-runtime-jvm` plus `skiko-winui-windows` dependencies.

- [x] Fix Skiko-side scope for `SKIKO-007`: request only the WinRT projection types used by `skiko-winui` and avoid broad projection dependencies.
  - Current shape: `winuiJvmJar` keeps generated projection output intact; Skiko does not apply extra `microsoft/**` / `windows/**` artifact excludes.
  - Remaining duplicate projection ownership, if present when multiple libraries request the same WinRT types, is a kotlin-winrt packaging/de-duplication issue.

- [x] Fix `SKIKO-009`: keep published JVM API shape aligned with the Skiko API used by WinUI tests.
  - Current shape: the published `skiko-winui` jar contains `TextStyle.setFontEdging(...)` and `Canvas.drawPicture(..., Paint)`.

- [x] Mitigate `SKIKO-008` and `SKIKO-010`: avoid synchronous render/present from WinUI callbacks and cover diagnostics after attached resize.
  - Current shape: `needRender()` always schedules through `DispatcherQueue`; smoke now waits asynchronously, renders initial/resized/shrunk states, then reads render diagnostics.

- [ ] 正在做: keep `winui-mingw` parity visible while JVM work lands.
  - Shared contracts must remain usable from Kotlin/Native.
  - Current blocker is kotlin-winrt compiler/plugin behavior for Native projection intrinsics, not Skiko public API shape.
  - Native COM/D3D/swapchain implementation and packaging remain future work after the compiler/plugin blocker is cleared.

## Validation Matrix

- [x] JVM compile and smoke compile
  - Command:
    `gradle :skiko-winui:compileKotlinWinuiJvm :skiko-winui:compileTestKotlinWinuiJvm "-Pskiko.winui.jvmTarget=25" "-Pskiko.winui.jvmToolchain=25" --no-configuration-cache --no-daemon --console=plain`
  - Result on 2026-06-10: passed after removing the incorrect `microsoft/**` / `windows/**` jar excludes.
  - Note: KMP dependency checker still reports known `winuiMingw` unresolved `org.jetbrains.skiko:skiko` variant diagnostics; JVM compile tasks exit successfully.

- [x] JVM publication shape
  - Command:
    `gradle :skiko-winui:publishSkikoWinuiJvmPublicationToMavenLocal "-Pskiko.version=0.0.3-local-SNAPSHOT" "-Pskiko.winui.jvmTarget=25" "-Pskiko.winui.jvmToolchain=25" --no-configuration-cache --no-daemon --console=plain --rerun-tasks`
  - Result on 2026-06-10: passed after removing the incorrect `microsoft/**` / `windows/**` jar excludes.
  - Verified local artifact: `F:\Dependencies\maven\io\github\compose-fluent\skiko-winui\0.0.3-local-SNAPSHOT\skiko-winui-0.0.3-local-SNAPSHOT.jar`.
  - Checks: `Canvas.class`, `TextStyle.class`, `GraphicsApi.class`, and `WinUISkiaLayer.class` are present; generated projection classes are not jar-filtered by Skiko. The local jar contains 2861 `microsoft/**` / `windows/**` entries from the explicit kotlin-winrt projection output.
  - POM check: no `org.jetbrains.skiko` or `skiko-awt`; dependencies are `winrt-runtime-jvm` and `skiko-winui-windows`.
  - API check: `javap` confirms `TextStyle.setFontEdging(...)` and `Canvas.drawPicture(..., Paint)`.

- [x] WinUI JVM smoke
  - Command:
    `gradle :skiko-winui:runWinuiJvmSmoke "-Pskiko.winui.jvmTarget=25" "-Pskiko.winui.jvmToolchain=25" "-Pskiko.winui.runtimeAssetsRoot=E:\Documents\AndroidStudioProjects\compose-fluent-skiko\samples\SkiaWinUISample\build\kotlin-winrt\application-package" "-Pskiko.winui.windowsRuntimeJar=F:\Dependencies\maven\io\github\compose-fluent\skiko-winui-windows\0.0.1-local-SNAPSHOT\skiko-winui-windows-0.0.1-local-SNAPSHOT.jar" --no-configuration-cache --no-daemon --console=plain`
  - Result on 2026-06-10: passed.
  - Coverage: creates WinUI `Window`, hosts `WinUISkiaLayer`, renders initial `320x240`, resizes to `480x360`, shrinks to `64x32`, reads render diagnostics, verifies automation peer, and exits cleanly.
  - Key output: `skiko-winui-smoke: shrunk actual 64.0 x 32.0`; `skiko-winui-smoke: diagnostics renderVersion=4 platform=128x64`; `BUILD SUCCESSFUL`.

- [ ] Full `:skiko-winui:publishSkikoWinuiToMavenLocal`
  - Result on 2026-06-10: full publish not rerun after the native log fix.
  - Native lock fix: `compileWinuiSkikoWindowsX64` no longer shares one log file between `vcvars64.bat` and the later `cl/link` phase. `vcvars64.bat` writes `compile-skiko-winui-windows-setup.log`; compile/link writes `compile-skiko-winui-windows.log`.
  - Native compile validation after the lock fix: `gradle :skiko-winui:compileWinuiSkikoWindowsX64 "-Pskiko.winui.jvmTarget=25" "-Pskiko.winui.jvmToolchain=25" "-Pskiko.winui.vsPath=D:\Program Files\Microsoft Visual Studio\2022\Community" --no-configuration-cache --no-daemon --console=plain --rerun-tasks` passed.
  - WinUI runtime native compile now follows the regular Skiko Windows tool preference by using `clang-cl.exe` / `lld-link.exe` when available, with `cl.exe` / `link.exe` fallback. The MSVC STL helper compatibility source is compiled by default under unique `skiko_winui___std_*` symbol names and linked through `/alternatename`, so it only satisfies `__std_search_1`, `__std_find_first_of_trivial_pos_1`, and `__std_remove_8` when the selected CRT does not provide them.

- [ ] `samples/SkiaMultiplatformSample:runWinuiSmoke`
  - Result on 2026-06-10: skipped as a validation signal because the sample build script currently fails script compilation under Kotlin 2.4 deprecation-as-error diagnostics before entering WinUI runtime.
  - Follow-up: modernize the sample build script separately before using this as a gate again.

- [ ] `winui-mingw` compile/runtime validation
  - Current result: blocked.
  - Blocker: kotlin-winrt Native compile path still attempts JVM FFM projection intrinsic lowering and requires JVM target/JDK FFM symbols.

## Open Follow-Ups

- [ ] 正在做: publish a fresh snapshot after the current `SKIKO-006` to `SKIKO-010` fixes are committed and CI confirms full `publishSkikoWinuiToMavenLocal`.
- [ ] Remove downstream compose-winui workarounds for `SKIKO-007`, `SKIKO-008`, `SKIKO-009`, and `SKIKO-010` only after consuming a published snapshot and rerunning downstream sample/test gates.
- [ ] Modernize `samples/SkiaMultiplatformSample` Gradle script so `runWinuiSmoke` can return to the validation matrix.
- [ ] Continue `winui-mingw` once kotlin-winrt provides Native-safe projection intrinsic handling.
