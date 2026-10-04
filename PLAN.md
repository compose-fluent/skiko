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
- [x] 2026-06-18 CI run `27761356397` failed in `:skiko-winui:compileKotlinWinuiJvm` because kotlin-winrt authoring support is generated into `commonMain` while hand-written authoring types were only reachable through the intermediate `winuiMain` source set.
- [x] 2026-06-18 fixed the post-merge CI compile failure by compiling `src/winuiMain/kotlin` as part of `commonMain`, keeping `winuiJvmMain` and `winuiMingwMain` directly dependent on `commonMain`, and aligning the publish workflow Gradle version with the 9.5.0 wrapper.
- [x] 2026-10-02 JetBrains/skiko `master` (`c2505676`, #1316; 84 commits since #1215) merged into `winui_dev` (merge commits `20307292`, `21583d08`, `4809539e`): Skia `m154-ab5932137b`, skottie extracted into `:skiko-skottie`, new `:skiko-graphite` module, AWT render stack rewrite (`redrawer` -> `renderer`, `Redrawer` / `ContextHandler` / `makeDefaultRenderFactory` removed from shared source sets), Ganesh Vulkan backend, runtime publication refactor, `benchmarks/SkikoBenchmarks`, Dokka 2.2.0. Conflict resolution kept Kotlin 2.4, the WinUI settings flags, and the Kotlin 2.4 `IrAnnotation` import-generator rewrite (now carrying upstream's `moduleName` parameter).
- [x] 2026-10-04 post-merge WinUI adaptations: removed the `makeDefaultRenderFactory` actual from `Actuals.winuiJvm.kt`; aligned the hand-written WinUI native builds with upstream's m154 Windows settings (`raw_ptr` / `allocator_core` / `allocator_base` libs, `Winmm.lib`, `SK_VULKAN`, `include/third_party/vulkan`, `/Zc:inline`, `/OPT:ICF`, `/ignore:4217`); stopped linking `skottie` / `sksg` / `jsonreader`; limited the WinUI publication short-circuit in `declarePublications()` to the core module so the new extension modules still configure with `skiko.winui.enabled=true`; updated `checkWinuiJvmApiClasspathBoundary` for the `renderer` package.
- [x] 2026-10-04 behavior change from the sync: `org.jetbrains.skia.skottie.*` / `org.jetbrains.skia.sksg.*` are no longer part of the `skiko-winui` artifacts, because upstream moved them into `:skiko-skottie`, which has no WinUI targets yet.
- [x] 2026-10-04 fixed the WinUI Kotlin compilation failure that was reproducible on pre-merge `5f6281ca` (273 errors in generated `WinRTEventProjectionHelper_skiko_dll_00{1,2}.kt`, bare lambdas returned as `TypedEventHandler<...>`). Root cause: the projections kotlin-winrt generates now need Kotlin language version 2.3+, while Skiko compiles with `skiko.kotlin.language.version=2.2` and the plugin copies that onto its projection compilation. `winui.gradle.kts` now raises the language version of the WinUI build to at least 2.3 (2.4 is not usable yet: upstream's `Native.jvm.kt` exposes an internal type from a public inline function, which is an error at 2.4). The stale Gradle plugin was a separate problem, not the cause.
- [x] 2026-10-04 migrated from `winrt-gradle-plugin` (last published 2026-09-13) to kotlin-winrt's `windows-toolkit-gradle-plugin` (`KotlinWindowsToolkitPlugin`, `windows { packageReferences { } application { } }`, per-variant `*WinApp*` tasks and `build/kotlin-winrt/application-layout/<variant>/package` layouts). The WinUI Kotlin sources needed no changes; only `WinUISkiaLayerSmoke` moved to `WindowsAppSdkBootstrap.initializeApplicationHost(...)`. `samples/SkiaWinUISample` now declares the skiko-winui mingw runtime files as `runtimeAsset(...)` instead of copying them by hand and uses a self-contained unpackaged layout.
- [x] 2026-10-04 workarounds for gaps in the published toolkit plugin (kotlin-winrt `master` 2976464e6, also present on `xaml-support`), each marked in the build scripts and removable once fixed there:
  - Native projection compilation gets the plugin's own JVM runtime jars instead of KLIBs outside the kotlin-winrt build (`kotlinWinRTRuntimeClasspathDependency`), so `winrt-runtime` / `winrt-authoring` are added to `winuiMingwWinRTProjection` explicitly.
  - cinterop tasks of the main compilation read the projection KLIB without an ordering edge (the plugin only orders `transform*DependenciesMetadata`), so they `mustRunAfter` the projection compilation.
  - One AppX resources zip per target is attached to the root publication without a classifier, which makes Maven reject a JVM + mingw library; the artifacts get a `<sourceSet>-appx-resources` classifier.
  - Consumers of skiko-winui as an external module: generation skips the types skiko-winui owns, but the projection compilation classpath only accepts project dependencies; the samples add skiko-winui to `kotlinWinRTProjection*CompileClasspath` / `winuiMingwWinRTProjection`. Downstream applications need the same until the plugin handles external modules.
- [ ] 2026-10-04 winui-mingw runtime blocker in kotlin-winrt: passing a lambda where a generic WinRT delegate is expected (`event.add { _, _ -> }` with `TypedEventHandler`) crashes on Kotlin/Native with an access violation in `WinRTDelegateHandle.close()`. `WinRTGenericDelegateSamLowering` hoists the closed descriptor into a compiler-generated static field that is still null when `adaptWinRTTypedEventHandler` receives it. Reproduced in the sample application module itself, so it is independent of Skiko; the JVM path is unaffected. kotlin-winrt fixed this on `xaml-support` (`4a459932c`, the descriptor is read through a property accessor); the published `0.1.0-SNAPSHOT` (`master` 2976464e6) does not contain the fix yet.

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
  - Result on 2026-06-18: `.\gradlew.bat --no-daemon --stacktrace --console=plain -p . --no-configuration-cache --max-workers=1 "-Pskiko.winui.jvmTarget=25" "-Pskiko.winui.jvmToolchain=25" "-Pskiko.winui.mingw.enabled=false" "-Pskiko.winui.skipSamples=true" :skiko-winui:compileKotlinWinuiJvm` passed after moving the WinUI source directory into `commonMain`.
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
  - Result on 2026-10-04: compile and link pass (`compileKotlinWinuiMingw`, `linkReleaseSharedWinuiMingw`); runtime is blocked.
  - Blocker: kotlin-winrt's Native lowering of generic delegate lambdas (see Current Status). The earlier FFM projection intrinsic blocker no longer reproduces.

- [ ] Post-sync validation (2026-10-04, Windows x64, JDK 25 Gradle daemon, clang-cl 21.1)
  - Common flags: `-Pskiko.winui.enabled=true -Pskiko.winui.jvmTarget=25 -Pskiko.winui.jvmToolchain=25 -Pskiko.winui.mingw.enabled=true -Pskiko.winui.skipSamples=true --no-configure-on-demand --no-configuration-cache`.
  - Passed with WinUI enabled: all projects configure (including `:skiko-skottie` / `:skiko-graphite`), `:skiko:checkWinuiAwtFreeBoundary`, `:skiko:runWinuiIndirectPointerNativeTests`, `:skiko:compileWinuiJvmNativeWindowsX64`, `:skiko:compileWinuiMingwNativeWindowsX64`, `:skiko:compileWinuiSkikoWindowsX64` (Skia m154; 938 JNI exports including the Direct3D and Vulkan entry points), `:skiko:compileWinuiMingwSkikoNativeWindowsX64` (exports every `@ExternalSymbolName` used by the Kotlin/Native sources).
  - Passed without WinUI (`-Pskiko.native.windows.enabled=true`): `:skiko:compileKotlinAwt`, `:skiko:skiko-skottie:compileKotlinAwt`, `:skiko:skiko-graphite:compileKotlinAwt`, `:skiko:import-generator:compileKotlinJvm`, `:skiko:compileKotlinMingwX64`; the default root composite (with `benchmarks/SkikoBenchmarks`) configures.
  - Passed after the toolkit plugin migration: `:skiko:compileKotlinWinuiJvm`, `:skiko:compileTestKotlinWinuiJvm`, `:skiko:checkWinuiJvmApiClasspathBoundary`, `:skiko:compileKotlinWinuiMingw`, `:skiko:linkReleaseSharedWinuiMingw`, `:skiko:publishSkikoWinuiToBuildRepo` (same publications as `publishSkikoWinuiToMavenLocal`, written to `skiko/build/repo`).
  - Publication shape: `skiko-winui-jvm` POM depends on `kotlin-stdlib`, `kotlinx-coroutines-core-jvm`, `winrt-runtime-jvm`, `winrt-authoring-jvm` (added by the toolkit plugin), and `skiko-winui-windows`; no `org.jetbrains.skiko` / `skiko-awt`. The jar has `Canvas`, `TextStyle`, `GraphicsApi`, `WinUISkiaLayer`, the `skiko-winui.dll` helper, no AWT-only or skottie classes, and 3459 projection classes. `skiko-winui-mingw` additionally publishes the plugin's `winrt-projection` KLIB.
  - JVM runtime: `samples/SkiaWinUISample:runWinAppHostWinuiJvmMain` (consuming `skiko/build/repo`) launches and auto-exits; `:skiko:runWinuiJvmSmoke` against that sample's staged layout passes (renders 480x360, 720x540, 96x48; `diagnostics renderVersion=5 platform=96x48`; automation peer verified; clean exit).
  - `:skiko:winuiJvmTest`: the 9 WinUI tests pass; the 266 shared Skia tests that also run on this target fail with `Cannot find skiko-windows-x64.dll.sha256` because the task classpath has no `skikoWinuiWindowsRuntimeJar`. Not part of the sync; see follow-ups.
  - `:skiko:winuiMingwTest`: 282 of 299 pass. The 17 failures are ICU/text ones (`BreakIteratorTests`, `ShaperTest`, `TextLineTest`, parts of `ParagraphTest` / `TextBlobTest`) plus `ResourceTest.loadFontTest`; see follow-ups.
  - mingw runtime: `samples/SkiaWinUISample` compiles, links, and stages for `winuiMingw`, then crashes on the first typed event lambda because of the kotlin-winrt Native delegate bug under Current Status.
  - Not validated: `samples/SkiaMultiplatformSample`. Its script compiles after the migration but configuration stops in `org.jetbrains.gradle.apple.applePlugin` (`LanguageSettings.getOptInAnnotationsInUse()` missing in KGP 2.4), as before.

## Open Follow-Ups

- [ ] 正在做: publish a fresh snapshot after the current `SKIKO-006` to `SKIKO-010` fixes are committed and CI confirms full `publishSkikoWinuiToMavenLocal`.
- [ ] Remove downstream compose-winui workarounds for `SKIKO-007`, `SKIKO-008`, `SKIKO-009`, and `SKIKO-010` only after consuming a published snapshot and rerunning downstream sample/test gates.
- [ ] Modernize `samples/SkiaMultiplatformSample` Gradle script so `runWinuiSmoke` can return to the validation matrix.
- [ ] Continue `winui-mingw` once kotlin-winrt provides Native-safe projection intrinsic handling.
- [ ] Remove the toolkit plugin workarounds listed under Current Status once kotlin-winrt fixes them, and rerun `runWinuiMingwSampleSmoke` once the Native generic delegate lowering is fixed.
- [ ] Load ICU data on winui-mingw: only the JVM bindings call `SkLoadICU()`, and the Windows Skia build keeps ICU data in `icudtl.dat`, so `BreakIterator`, `Shaper`, and `TextLine` fail through the mingw Skia bridge.
- [ ] `loadBytesFromPath` in `nativeMain` opens files with `fopen(path, "r")`; on Windows that is text mode and corrupts binary reads (`ResourceTest.loadFontTest` on mingw).
- [ ] Put `skikoWinuiWindowsRuntimeJar` on the `winuiJvmTest` classpath (and decide which shared tests should run there) so the shared Skia tests can load the native runtime.
- [ ] Raise the WinUI language version to 2.4 once upstream fixes the internal-type exposure in `Native.jvm.kt` (`interopScope` / `theScope`).
- [ ] Decide whether WinUI needs Skottie; if so, give `:skiko-skottie` WinUI targets and a native runtime, since it is no longer part of the core artifacts.
- [ ] Port upstream #1298 to `winuiRedrawer.cc`: adapter probing still uses `D3D_FEATURE_LEVEL_11_0` while device creation requires 12.0.
