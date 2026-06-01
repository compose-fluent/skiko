# skiko-winui Plan

## Goal

实现 AWT-free `skiko-winui` 后端，长期保留两条实现路径：

- `winui-jvm`: JVM + WinUI `SwapChainPanel` + Direct3D/DXGI 渲染。
- `winui-mingw`: Kotlin/Native `mingwX64` parity 路径。

V1 标准是可真实渲染：WinUI `SwapChainPanel` 承载、Direct3D/DXGI swapchain 呈现、resize、基本 frame scheduling、dispose、JVM smoke 可运行。

V2 标准是在 V1 上补齐 AWT-free 的 WinUI 基础输入入口，包括 pointer、keyboard、text、IME/CoreText composition 和 focus。无障碍、software/ANGLE/OpenGL fallback 仍不在当前阶段。

V3 标准是在 V2 上补齐 WinUI host 基础无障碍入口。第一步是把 layer 级别的 name/help/role/live view 映射到 XAML `AutomationProperties`；第二步是建立 Skiko WinUI 自己的 semantics tree、focus/navigation 和 change/event contract；后续再把该 tree 暴露为 WinUI custom automation peer/provider。

## Architecture Decisions

- [x] Public API 只暴露 `org.jetbrains.skiko.winui.WinUISkiaLayer` 作为入口；`WinUISkiaLayerSurface` 只作为非 AWT host/scheduler 使用的 surface contract。
- [x] V1 只支持 `GraphicsApi.DIRECT3D`。
- [x] WinUI 渲染承载面固定为内部 `Microsoft.UI.Xaml.Controls.SwapChainPanel`；public `component` 是 `FrameworkElement` contract，当前由 `WinUISkiaHostPanel : Grid` 提供，host 内部持有 render panel。
- [x] `kotlin-winrt` 来源固定为 `https://github.com/compose-fluent/kotlin-winrt`；Maven 发布前使用 sibling clone/local jars，不复制源码、不加 submodule。
- [x] WinUI 后端代码不放入、不修改 `skiko/src/awtMain`；AWT 只能作为参考。
- [x] WinUI Windows runtime jar 独立命名为 `skiko-winui-windows.jar`，包含 WinUI-owned `skiko-windows-x64.dll`，不复用 `skiko-awt-runtime` jar。
- [x] `WinUISkiaLayer` 拥有共享 WinUI layer 行为：`SwapChainPanel`、size/scale/focus、input interop、render request shaping、draw scope、picture recording、attach/detach/dispose。
- [x] `WinUISkiaLayerPlatformInterop` 只抹平 `winui-jvm` / `winui-mingw` 平台差异；JVM actual 负责 `ISwapChainPanelNative`、JNI/D3D/Skia surface、present、native disposal。
- [x] `winui-mingw` 是真实目标；当前保持同名 API/source-set/interop boundary，native COM/D3D 实现未完成时必须明确记录为 blocker。

## Current Implementation

- [x] Module/source sets
  - `skiko/skiko-winui` 已接入 root `skiko-all`。
  - Source sets: `commonMain`、`winuiMain`、`winuiJvmMain`、`winuiMingwMain`。

- [x] kotlin-winrt projection
  - Projection type list保持显式最小化。
  - 当前必需类型包括 `Application`、`Window`、`FrameworkElement`、`UIElement`、`SwapChainPanel`、Automation peer/model enums、dispatcher timer/handler、size/focus/routed event types、`Windows.UI.Text.Core.CoreText*` IME/text composition types。
  - `Grid` / `Panel` / `UIElementCollection` 已重新进入显式 projection 请求，用于 `WinUISkiaHostPanel` 承载内部 `SwapChainPanel`。
  - `kotlin-winrt` 当前本地最上游 checkout: `3a122ce2 Fix WinUI KMP authoring registration and struct ABI`。
  - 当前 kotlin-winrt local jars 是 class file version 69，需要用 JDK 25 加载；root Gradle 8.13 不支持当前 JDK 25，验证需使用 sibling kotlin-winrt Gradle 9.4 wrapper。
  - 最近 projection 生成结果：2026-05-22 通过，命令为 `E:\Documents\AndroidStudioProjects\kotlin-winrt\gradlew.bat -p E:\Documents\AndroidStudioProjects\compose-fluent-skiko :skiko-winui:generateWinRtProjections`，`JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot`。
  - 2026-05-31 已确认本地 `kotlin-winrt` HEAD 和 `origin/master` 都是 `3a122ce21b43d0f8ff62199b275275cc1d483a7f`；该提交把 projected struct 参数调用改为 sized shape（例如 `Struct8_4`），已解除 Skiko `GetPeerFromPointCore(Point)` ABI blocker。
  - 新版 kotlin-winrt generator worker 依赖通过 composite build 解析；当前验证命令不再使用 `-Pskiko.winui.skipKotlinWinRtComposite=true`。
  - kotlin-winrt upstream issue 草稿统一维护在本地未跟踪文件 `KOTLIN_WINRT_UPSTREAM_ISSUE.md`；不再拆分多个 issue 文件。

- [x] WinUI layer API
  - `WinUISkiaLayer` 是 `winuiMain` concrete class。
  - 已提供 `component`、`renderDelegate`、`inputHandler`、`renderApi`、`contentScale`、`pixelGeometry`、`fullscreen`、`width`、`height`、`focusState`、`requestFocus()`、`attachTo()`、`detach()`、`startFrameScheduler()`、`needRender()`、`needRedraw()`、`dispose()` / `close()`。
  - `WinUISkiaLayerRenderDelegate` 已提供 WinUI 版本的 logical-coordinate render wrapper。
  - `WinUIRenderDispatcher` 已承载 render request coalescing、render-time self-request deferral、dispatcher enqueue 和 close-time pending request cleanup。
  - `WinUISkiaLayer` 创建时捕获 WinUI `DispatcherQueue`；非 WinUI 线程调用 `needRender()` 只 enqueue 到该 queue，不直接执行 render。
  - `WinUIRenderDispatcher` 的 pending/rendering/enqueued/closed 状态已加锁，允许跨线程 `needRender()` 合并请求。
  - `WinUISkiaWindowBinding` 在 attach 时保存旧 `Window.content`，close/detach 时只在 content 仍为当前 layer component 时恢复，避免泄漏 layer component 或误删 host 新 content。
  - `SizeChanged` / `compositionScaleChanged` 已接入异步 render invalidation；event 内不同步 present，只排队到后续 dispatcher turn。
  - Layer 维护当前 render state 快照：logical size、scaled size、contentScale，用于 resize/scale invalidation 去重和 platform render 参数一致性。
  - Layer 提供 internal render diagnostics：render version、last rendered state、pending invalidated state、last platform result、last render failure。

- [x] Render pipeline
  - `WinUISkiaLayer.update(nanoTime)` 使用 `PictureRecorder` 录制 `renderDelegate` 输出。
  - `WinUISkiaLayer.draw(canvas)` 只在 native render scope 内绘制 cached `Picture`。
  - `winui-jvm` platform interop 创建 D3D12 device/queue、DXGI swapchain、Skia D3D surfaces，处理 resize/present/dispose。
  - `needRender()` 通过 `WinUIRenderDispatcher` 防止 render-time recursive re-entry，并把 render-time self-request 延迟到 WinUI dispatcher。
  - resize/scale invalidation 通过 `WinUIRenderDispatcher.scheduleRender(false)` 合并，`renderNow` 继续过滤 0 尺寸。
  - 显式 `needRender()` 不受 resize/scale invalidation 去重影响；动画和主动刷新仍可在相同尺寸下继续 render。
  - `WinUISkiaLayerPlatformInterop.render(...)` 返回 internal `WinUIPlatformRenderResult`，记录 swapchain create/resize、buffer index、present vsync 参数。

- [x] Input V2 foundation
  - `WinUIInputHandler`、pointer/key/text/focus event data types 位于 `winuiMain`。
  - `WinUIInputInterop` 位于 `winuiMain`，订阅 `SwapChainPanel` routed pointer/key/text/focus events。
  - key/text input event 已携带 `WinUIKeyModifiers`；`WinUIInputInterop` 维护内部 keyboard modifier state，pointer event 继续使用 WinUI routed event 的 `keyModifiers`。
  - pointer event 已携带 frame id、contact rect、pressure、orientation、tilt、twist、primary/in-range/canceled、horizontal wheel 和 pen barrel/eraser/inverted 状态。
  - text input 已接 `CoreTextEditContext`：focus enter/leave、text/selection/layout request、text/selection update、composition started/updated/committed/canceled。
  - `WinUISkiaLayerSurface` 已提供 `updateTextInputState()`、`updateTextInputLayout()`、`notifyTextInputLayoutChanged()`，让宿主同步真实 text store、selection 和候选窗布局。
  - `WinUITextCompositionEvent`、`WinUITextRange`、`WinUITextLayoutBounds` 位于 `winuiMain`，`winui-jvm` / `winui-mingw` 共用同一 text/IME contract。

- [x] Accessibility foundation
  - `WinUIAccessibilityInfo`、`WinUIAccessibilityView`、`WinUIAccessibilityLiveSetting`、`WinUIAccessibilityRole` 位于 `winuiMain`。
  - `WinUISkiaLayerSurface.accessibilityInfo` 作为 layer 级 accessibility 状态入口，`winui-jvm` / `winui-mingw` 共用同一 contract。
  - `WinUIAccessibilityInterop` 将 layer 级 name、automationId、helpText、fullDescription、localizedControlType、accessibility view、live setting、role 映射到 WinUI `AutomationProperties`。
  - `WinUIAccessibilityProvider`、`WinUIAccessibilitySnapshot`、`WinUIAccessibilityNode`、`WinUIAccessibilityActionRequest` 已提供 host/Compose 向 Skiko WinUI 提供 semantics tree 的 contract。
  - `WinUIAccessibilityChange`、`WinUIAccessibilityChangeType` 已提供后续 UIA event/property update 所需的 change contract。
  - `WinUIAccessibilityFocusDirection` 已提供 FIRST/LAST/NEXT/PREVIOUS/PARENT/FIRST_CHILD traversal request。
  - `WinUISkiaLayerSurface` 已提供 `accessibilityProvider`、`accessibilitySnapshot`、`invalidateAccessibility()`、`notifyAccessibilityChanged()`、`accessibilityRootNode()`、`accessibilityNode()`、`accessibilityParent()`、`accessibilityChildren()`、`accessibilityNodeAt()`、`accessibilityFocusedNode()`、`moveAccessibilityFocus()`、`requestAccessibilityFocus()`、`performAccessibilityAction()`。
  - `WinUIAccessibilityTree` 已将 snapshot 编成稳定索引，支持 O(1) node lookup、parent lookup、traversal order、focusable traversal 和 duplicate id diagnostics。
  - `WinUIAccessibilityInterop` 已维护当前 indexed tree、bounded versioned change history 和 change version，支持 node lookup、focused node、bounds hit-test、focus traversal target、action delegation，并把 root node metadata 同步到 XAML `AutomationProperties`。
  - `WinUIAccessibilityDiff` 已在 `winuiMain` 中对 provider snapshot 自动生成 structure/node/focus/value/text/live-region changes，供后续 WinUI peer/provider 直接消费。
  - `WinUIAccessibilityAutomationModel` 已在 `winuiMain` 中把 Skiko accessibility tree 转换成 WinUI Automation peer/provider 语义：runtime id、parent/children、hit-test、focused node、control type、supported patterns、UIA event mapping 和 action dispatch。
  - `WinUISkiaHostPanel : Grid` 已接入默认 `WinUISkiaLayer.component`，内部承载 `SwapChainPanel`，并在 Kotlin 侧 override `onCreateAutomationPeer()`。
  - `WinUISkiaAutomationPeer` 的 Skiko 侧实现已存在；`4901cbbe` 后 generated `WinRT_WinUISkiaAutomationPeer_TypeDetails.kt` 已可编译。
  - kotlin-winrt `3285a6aa` 已让 `WinUISkiaHostPanel` 进入 authored metadata，包含 `IFrameworkElementOverrides` / `IUIElementOverrides`。
  - kotlin-winrt `e0683382` 已修复 authored `Grid` composable aggregation interface resolution，`WinUISkiaHostPanel : Grid` 可以构造、host 内部 `SwapChainPanel`、完成 render/resize。
  - downstream Gradle source root 已修正为 `src/winuiMain/kotlin`，让 `build/generated/kotlin-winrt-authoring/src/main/kotlin` 参与 KMP 编译。
  - `WinUISkiaHostPanel` 已实现 `measureOverride` / `arrangeOverride` 并显式布局内部 `SwapChainPanel`；作为 authored XAML element 后不再依赖默认 override projection，修复 smoke 中 `actualWidth` / `actualHeight` 读出未定义值的问题。
  - `4dc2ce22` 后 `rootPeer.getChildren()` collection return 不再触发 native crash，custom peer children 可返回。
  - `3a122ce2` 后 `rootPeer.getPeerFromPoint(Point(24f, 24f))` 已在 smoke 中命中 `Smoke button`，Kotlin override 收到的 point 为 `24.0,24.0`。
  - `WinUISkiaAutomationPeer` 是 internal authored CCW 类型，不作为 public default-activatable WinRT runtime class 导出；`WinUISkiaHostPanel` 仍是 public authored host element。
  - `Windows.Foundation` structs 已随本地最上游 kotlin-winrt 改回 projected package：Skiko 源码重新使用 `windows.foundation.Point/Rect/Size`。

- [x] Samples
  - `samples/SkiaWinUISample` 使用 `WinUISkiaLayer` + `WinUISkiaLayerRenderDelegate`。
  - `samples/SkiaMultiplatformSample` 已有 `winuiMain` / custom `winuiJvm` compilation；WinUI host 在 `winuiMain`，JVM bootstrap 在 `winuiJvmMain`。
  - Multiplatform sample 仍有 KGP source-set tree warning，功能可编译但结构未最终化。

- [x] Gradle organization
  - `skiko/skiko-winui/build.gradle.kts` 已缩减为核心 KMP/WinRT 配置。
  - Build logic 已拆分到：
    - `gradle/awt-free-boundary.gradle.kts`
    - `gradle/windows-native.gradle.kts`
    - `gradle/publishing.gradle.kts`
    - `gradle/smoke.gradle.kts`

## Active Work

- [x] Stabilize Gradle layout after generated authoring source integration.
  - `GenerateWinRtProjectionsTask.sourceRoots` 已从具体 `.kt` 文件改为 `src/winuiMain/kotlin`，否则 kotlin-winrt 插件不会把 generated authoring source root 加入 KMP source set。
  - `:skiko-winui:compileKotlinWinuiJvm :skiko-winui:compileTestKotlinWinuiJvm :skiko-winui:checkWinuiAwtFreeBoundary` 已重新通过。
  - `:skiko-winui:runWinuiJvmSmoke -Pskiko.winui.smokeArgs=--use-layer-attach` 已越过 generated authoring compile、host layout、custom peer dispatch、`GetChildrenCore` collection return 和 `GetPeerFromPointCore(Point)` struct ABI，当前通过。
  - 保持主 `build.gradle.kts` 面向模块声明，不再堆积 native command construction 和 smoke/publishing 细节。
  - 当前 Windows 进程看不到 `V:\VC\Auxiliary\Build\vcvars64.bat`；已有 native outputs 未过期时 native compile tasks 会跳过，缺失/过期时仍需要有效 VS path。

- [x] Keep production implementation ahead of smoke.
  - smoke 只做回归验证，不承载新 backend 功能。
  - 本轮新增能力落在 `winuiMain` 的 `WinUIAccessibilityDiff` / `WinUIAccessibilityInterop`，smoke 只作为回归验证。

- [ ] 正在做: keep `winui-mingw` parity visible.
  - 当前 `WinUISkiaLayerPlatformInterop.mingw.kt` 是同边界 stub。
  - 2026-06-01 已再次尝试 `.\.agent_scripts\run_windows_gradle.ps1 winui-mingw-compile`。
  - 已移除 `skiko-winui` 未使用的 `kotlinx-coroutines-core` 依赖，避免 `winui-mingw` 被无关 `kotlinx-coroutines-core-mingwx64` / `atomicfu-mingwx64` 下载阻塞。
  - `winui-jvm` 运行时仍需要 `skiko-awt` jar 间接使用的 coroutines；已只在 `winuiJvmMain` 加 `kotlinx-coroutines-core-jvm` runtime dependency，不放回 common/winui shared source set。
  - 已修正 `kotlin-winrt` Maven group 默认值为 `io.github.compose-fluent`，并把 `winrt-authoring` 限定在 `winuiJvmMain`，避免 JVM-only authoring 依赖污染 `winui-mingw`。
  - 已给 Skiko 本体加默认关闭的 `skiko.native.windows.enabled` / `mingwX64` API variant，并补齐 Windows native `SkiaLayer` / `currentSystemTheme` stub；`skiko:compileKotlinMingwX64` 当前可通过，`winui-mingw` 不再卡在 Skiko/Skia API 可见性。
  - 已在 `winuiMingwMain` 加临时 no-op `WinRTAuthoringTypeDetailsRegistrar`，用于绕过 kotlin-winrt Native compile 未传 `authoringAssemblyName` / registrar source 的第一层问题；删除条件是 kotlin-winrt 对 Native compile 提供正确 authoring registrar support input。
  - 当前 blocker：`compileKotlinWinuiMingw` 失败在 kotlin-winrt compiler plugin 对 Native compile 执行 JVM FFM projection intrinsic lowering，报 `requires compiling JVM projections with JVM target 25 and a JDK that exposes java.lang.foreign`。这是 compiler plugin target 分流问题，不是 Skiko 源码或 WinDbg 可定位的 native crash。
  - 2026-06-01 已尝试通过本地 ps1 将 generated projection 的 `WinRtProjectionIntrinsic` import 临时 alias 到 compile-only fallback；该绕法不能解除 blocker，compiler plugin 仍能识别/处理 projection intrinsic 调用并继续要求 JVM FFM symbols。脚本已收窄为 restore-only：只幂等清理损坏 alias 并恢复原始 import，不再提供无效 fallback 分支。
  - 2026-06-01 进一步确认：`kotlinCompilerPluginClasspathWinuiMingwMain` / `kotlinNativeCompilerPluginClasspath` 显示无依赖，但 Native compiler 仍加载 kotlin-winrt compiler plugin；原因很可能是 downstream buildscript classpath 中的 `winrt-compiler-plugin.jar` 被 K/N service loader 发现。Skiko 侧无法仅靠 Native plugin classpath 过滤解除，需要 kotlin-winrt compiler plugin 自身做 target guard。
  - 2026-06-01 已在 `KOTLIN_WINRT_UPSTREAM_ISSUE.md` 记录最小待验证 upstream patch：在 `lowerProjectionIntrinsics(...)` 开头用 `pluginContext.platform.isJvm()` 跳过非 JVM target。当前尚未应用到 sibling `kotlin-winrt`，因为写入 sibling repo 的提权请求被自动审核服务 503 拒绝，需要用户明确放行后继续验证。
  - 已在 `KOTLIN_WINRT_UPSTREAM_ISSUE.md` 记录 `mingwX64` authoring registrar input 和 Native/JVM FFM lowering 问题；待 kotlin-winrt 能为 Native compile 跳过/替换 JVM FFM lowering 后补 native COM/D3D implementation。
  - 临时验证脚本 `.\.agent_scripts\run_windows_gradle.ps1` 已删除 Foundation struct import 重写，只保留 Kotlin/JVM 生成源码兼容修补和旧 intrinsic alias 损坏产物的 restore-only cleanup；mingw 仍需要 kotlin-winrt 原生支持 compiler-plugin registrar input 和 Native-safe intrinsic lowering。

## Next Steps

- [x] Validate split Gradle files.
  - Core compile/boundary and JVM smoke both pass after the split.

- [x] Implement production render dispatcher behavior.
  - `WinUIRenderDispatcher` 已从 `WinUISkiaLayer` 中接管 pending render queue、render-time deferral 和 dispatcher continuation。
  - `WinUIFrameScheduler` 仍作为 host-facing timer helper，负责周期性调用 `needRender()`。

- [x] Improve size/scale invalidation behavior.
  - `SizeChanged` 和 `compositionScaleChanged` 现在触发异步 render invalidation。
  - invalidation 不在 XAML layout event 中同步 present，避免重入 XAML layout/event source。
  - Host 仍可显式 `needRender()`；resize/scale event 会自动补一次后续 render request。

- [x] Improve resize/surface state tracking.
  - `WinUISkiaLayer` 已维护 `WinUILayerRenderState`，包含 logical size、scaled size、contentScale。
  - size/scale invalidation 会跳过 0 尺寸、已渲染状态和已排队状态。
  - `renderNow()` 使用同一个 state 快照传入 platform interop，避免 width/height/scale 分别读取导致不一致。

- [x] Add lightweight render diagnostics.
  - `WinUILayerRenderDiagnostics` 已提供 internal/debug-only 状态，不扩大 public API。
  - JVM platform interop 已返回 `WinUIPlatformRenderResult`，便于定位 swapchain create/resize/present。

- [x] Improve render error recovery hooks.
  - native/JVM failure path 继续抛 `WinUIRenderException` 或原始异常，不吞错误。
  - `WinUISkiaLayer` 现在记录最近失败对应的 render state、vsync 参数、render version、异常类型/message 和 cause，用于内部 diagnostics。
  - 成功渲染后清除最近失败，避免 stale failure 干扰后续定位。
  - JVM surface 创建/resize 中途失败时会清理半初始化 `Surface` / `BackendRenderTarget`，下一次 render 不复用残留对象。

- [x] Improve WinUI keyboard input event completeness.
  - `WinUIKeyEvent` 现在包含 modifiers 和 deviceId。
  - `WinUITextInputEvent` 现在包含 modifiers。
  - modifier tracking 位于 `winuiMain`，用 pressed-key set 处理 left/right modifier 组合，不引入 JVM/JNI 或 AWT 依赖。

- [x] Improve WinUI pointer input event completeness.
  - `WinUIPointerEvent` 现在保留 WinUI `PointerPoint` / `PointerPointProperties` 的触控和笔输入上下文。
  - 新增 `WinUIPointerContactRect`，避免把 WinRT `Rect` 类型泄漏到 backend public input data class。
  - 该能力位于 `winuiMain`，`winui-jvm` / `winui-mingw` 共用同一输入事件 contract。

- [x] Improve WinUI host attach/detach cleanup.
  - `hostWinUISkiaLayer` 现在记录 attach 前的 `Window.content`。
  - `WinUISkiaWindowBinding.close()` 会停止 scheduler、移除 closed token，并在 window 仍承载当前 layer component 时恢复旧 content。
  - 该逻辑避免 `detach()` 后 WinUI `Window` 继续持有 layer component，同时不覆盖 host 在 binding close 前设置的新 content。

- [x] Improve WinUI render thread dispatching.
  - `WinUIRenderDispatcher` 不再在 schedule 时使用调用线程的 `DispatcherQueue.getForCurrentThread()`。
  - `WinUISkiaLayer` 捕获 layer 创建线程的 WinUI `DispatcherQueue`，render scheduling 和 frame scheduler 复用同一个 queue。
  - `needRender()` 在非 WinUI thread 调用时会合并请求并 enqueue 到 WinUI dispatcher，避免跨线程直接 touching WinUI/D3D/Skia surface。

- [x] Improve WinUI render dispatcher thread safety.
  - `WinUIRenderDispatcher` 现在用 lock 保护 pending/rendering/enqueued/closed 状态。
  - render 仍在 lock 外执行，避免 native render 阻塞其他线程提交后续 render request。
  - render-time self-request 会在 lock 内合并状态，在 lock 外 enqueue，保持递归防护且不丢帧。

- [x] Implement IME/text design.
  - 保留 `CharacterReceived` 简单字符输入，并映射为 `WinUITextCompositionEventType.COMMITTED` fallback。
  - 新增 `WinUITextCompositionEvent`、`WinUITextRange`、`WinUITextLayoutBounds`，不套 AWT input abstractions。
  - `WinUITextCompositionInterop` 使用 WinUI `CoreTextEditContext` 处理 IME composition lifecycle、text/selection/layout requests 和 update results。
  - 候选窗定位通过宿主调用 `updateTextInputLayout()` 提供 text/control bounds；默认 fallback 为整个 `SwapChainPanel`。

- [x] Implement accessibility foundation.
  - `WinUIAccessibilityInfo` 已定义 WinUI layer 级 metadata contract。
  - `WinUIAccessibilityInterop` 已写入 XAML `AutomationProperties`，smoke 验证 name/automationId/helpText round-trip。

- [x] Implement accessibility semantics tree contract.
  - 已定义 Skiko/Compose 可向 WinUI 提供的 node tree、bounds、state、actions 和 focus snapshot。
  - 已实现 tree snapshot invalidation、logical-coordinate hit testing 和 action dispatch 到 provider。

- [x] Implement accessibility change/event contract.
  - 已定义 structure/node/focus/value/text/live-region change 类型。
  - `notifyAccessibilityChanged()` 会刷新 provider snapshot 并记录版本化 changes，供后续 WinUI peer/provider raise UIA events。
  - `invalidateAccessibility()` 现在会比较前后 `WinUIAccessibilitySnapshot`，自动记录 node add/remove/update、focus、value/text、live-region 和 child structure changes。
  - change history 是 bounded/versioned 读取模型；同一个版本重复读取稳定，不会因为一个消费者读取而清空其他消费者需要的 changes。

- [x] Implement accessibility focus/navigation contract.
  - 已定义 shared focus traversal direction。
  - 已实现 focused node query、focus target selection、focus action dispatch、focus change recording。
  - Smoke 覆盖 provider focus snapshot、NEXT traversal、explicit focus request。

- [x] Implement accessibility indexed tree bridge.
  - `WinUIAccessibilityTree` 已集中处理 snapshot indexing、parent mapping、duplicate id detection 和 traversal。
  - `WinUISkiaLayerSurface.accessibilityDiagnostics` 暴露 duplicate id / missing focused node diagnostics，供 host 和后续 peer bridge 定位无障碍树问题。
  - Smoke 覆盖 diagnostics clean path。

- [x] Remove placeholder accessibility bridge.
  - 已移除没有实际 UIA 行为的 `WinUIAccessibilityAutomationBridge`。
  - 保留有实际作用的 indexed tree、diagnostics、change queue、focus/navigation contract。
  - 后续实现 custom automation peer/provider 时再引入有真实 WinUI/UIA 行为的 bridge。

- [x] Implement accessibility peer-query surface.
  - `WinUISkiaLayerSurface` 已提供 root/node/parent/children 查询，后续 UIA provider 可以直接按 node id 实现 tree navigation。
  - Smoke 覆盖 root lookup、node metadata lookup、parent lookup、children order。

- [x] Implement accessibility snapshot diffing.
  - `WinUIAccessibilityDiff` 位于 `winuiMain`，不区分 `winui-jvm` / `winui-mingw`。
  - Snapshot diff 会为后续 UIA event bridge 提供稳定增量事件，避免 host 只能手写粗粒度 structure changed。

- [x] Implement accessibility automation model.
  - `WinUIAccessibilityAutomationModel` 位于 `winuiMain`，不区分 `winui-jvm` / `winui-mingw`。
  - 已把 role 映射到 `AutomationControlType`，把 actions/state 映射到 `PatternInterface`，把 accessibility changes 映射到 `AutomationEvents` / `AutomationStructureChangeType`。
  - 已提供 stable runtime id、root/node/parent/children/hit-test/focused-node 查询和 invoke/focus/value/expand-collapse/range action dispatch，后续 authored peer 或 raw provider 直接接这一层。

- [x] Implement WinUI custom automation peer/provider.
  - 需要把 `WinUIAccessibilitySnapshot` 暴露给 UI Automation：node tree、hit testing、focus navigation、property/event update。
  - 已接入 `WinUISkiaHostPanel : Grid` 和 `WinUISkiaAutomationPeer`；`Grid().children.add(SwapChainPanel())` 在 kotlin-winrt `1bd45755` 后已通过验证，不再触发 `0xC000027B`。
  - inherited override scanner blocker 已在 kotlin-winrt `3285a6aa` 修复；`authored-metadata.tsv` 现在包含 `WinUISkiaHostPanel`。
  - authored `Grid` composable construction blocker 已在 kotlin-winrt `e0683382` 修复；`WinUISkiaLayer()` 不再因 `IGrid` 缺失失败。
  - `4901cbbe` 后 generated `WinRT_WinUISkiaAutomationPeer_TypeDetails.kt` 已可编译；`FrameworkElementAutomationPeer.createPeerForElement(layer.component)` 已返回 custom peer，`getClassName()` 为 `WinUISkiaLayer`。
  - `4dc2ce22` 后 `GetChildrenCore()` collection return 已通过 smoke，不再 native crash。
  - 前一轮首先卡在 `GetPeerFromPointCore(Point)` struct 参数 ABI：smoke 传入 `Point(24f, 24f)`，Kotlin authored override 收到 `Point(-366855.0, 6.8E-43)`。
  - 本地最上游 kotlin-winrt `3a122ce2` 已包含 struct ABI 修复；generated projected `IAutomationPeer.getPeerFromPoint(Point)` 使用 sized `Struct8_4`。
  - 2026-05-31 smoke 已验证 `GetPeerFromPointCore(Point)` 收到 `24.0,24.0` 并返回 `Smoke button`。
  - 为适配 `3a122ce2`，`WinUISkiaAutomationPeer` 改为 internal authored type，避免 public default activation constructor 要求。
  - 2026-06-01 本地最上游 kotlin-winrt 已把 `Point` / `Rect` / `Size` 改回 `windows.foundation` package；Skiko 源码和 smoke 已同步验证。
  - 单文件 upstream issue `KOTLIN_WINRT_UPSTREAM_ISSUE.md` 已更新为 resolved downstream validation。

- [ ] Implement `winui-mingw` native render path.
  - 明确 cinterop/COM pointer ownership、D3D device/swapchain/surface creation、runtime packaging。
  - 2026-06-01 compile validation 已运行；已越过 kotlin-winrt runtime dependency、Skiko `mingwX64` variant、Skiko native API compile 和第一层 Native authoring registrar lookup，当前失败在 kotlin-winrt Native compile 的 JVM FFM intrinsic lowering。
  - 完成 kotlin-winrt Native authoring registrar wiring / Native-safe intrinsic lowering 后继续 `winuiMingwMain` compile validation，并进入 WinUI COM/D3D stub/native implementation。

- [ ] Replace local sibling validation flags with Maven coordinates.
  - 等 `kotlin-winrt` runtime/authoring/projection/plugin 发布后，切换集中依赖配置，不在业务代码保留本地路径。

## Validation Matrix

- [x] `:skiko-winui:compileKotlinWinuiJvm :skiko-winui:compileTestKotlinWinuiJvm :skiko-winui:checkWinuiAwtFreeBoundary`
  - 最近结果：2026-06-01 通过。命令：`.\.agent_scripts\run_windows_gradle.ps1 winui-core-check`。
  - 2026-06-01 restore-only projection intrinsic import cleanup 后复跑仍通过。
  - 当前命令使用 sibling kotlin-winrt Gradle 9.4 wrapper、JDK 25、`-Pskiko.winui.jvmTarget=25 -Pskiko.winui.jvmToolchain=25`、`-Dkotlin.daemon.jvmargs=-Xmx8192m -Dorg.gradle.jvmargs=-Xmx8192m`，保留 kotlin-winrt composite build，并通过 `-Dskiko.winui.skipSkikoComposite=true` 跳过旧 Skiko composite build。默认属性仍保留 JVM target/toolchain 22，以便后续 kotlin-winrt jars 重新按 JDK 22 发布或 Maven 化后恢复。
  - 覆盖 JVM source set 编译、test source 编译、AWT-free boundary。
  - 临时说明：当前脚本仍跳过 `:skiko-winui:validateCompileKotlinWinuiJvmWinRtAuthoredCandidates`，但不再重写 `Point` / `Rect` / `Size` package；后续需在 authoring 生成/校验完全稳定后恢复该校验。

- [x] `:skiko-winui:runWinuiJvmSmoke -Pskiko.winui.smokeArgs=--use-layer-attach`
  - 最近结果：2026-06-01 通过。命令：`.\.agent_scripts\run_windows_gradle.ps1 winui-smoke`。
  - 2026-06-01 restore-only projection intrinsic import cleanup 后复跑仍通过。
  - 已验证 `WinUISkiaLayer()` 创建、WinUI host、320x240 render、480x360 resize render、custom automation peer dispatch、`getClassName()` 为 `WinUISkiaLayer`、`rootPeer.getChildren()` child peer 返回、first child / next sibling navigation、`getPeerFromPoint(Point(24f, 24f))` 返回 `Smoke button`。
  - 关键输出：`skiko-winui-smoke: automation peer hit-test Smoke button calls=1 point=24.0,24.0`。
  - 临时说明：同 core check，当前 smoke 使用本地脚本跳过 TypeDetails parity 校验；runtime 行为已通过。

- [x] `:skiko-winui:generateWinRtProjections`
  - 最近完整生成结果：2026-06-01 通过。命令：`.\.agent_scripts\run_windows_gradle.ps1 "-Dskiko.winui.skipSkikoComposite=true" "-Pskiko.winui.jvmTarget=25" "-Pskiko.winui.jvmToolchain=25" "-Pskiko.winui.localSkikoJar=skiko/build/libs/skiko-awt-0.0.0-SNAPSHOT.jar" "-Pskiko.winui.localWinRtRuntimeJar=E:\Documents\AndroidStudioProjects\kotlin-winrt\winrt-runtime\build\libs\winrt-runtime-jvm.jar" "-Pskiko.winui.localWinRtAuthoringJar=E:\Documents\AndroidStudioProjects\kotlin-winrt\winrt-authoring\build\libs\winrt-authoring-0.1.0-SNAPSHOT.jar" "-Dkotlin.daemon.jvmargs=-Xmx8192m" "-Dorg.gradle.jvmargs=-Xmx8192m" "--console=plain" ":skiko-winui:generateWinRtProjections"`。
  - 生成产物使用 `windows.foundation.Point/Rect/Size` package；Skiko 源码已同步，不再需要 downstream struct import rewrite。
  - 当前显式 projection 请求包含 `Grid` / `Panel` / `UIElementCollection`，用于 `WinUISkiaHostPanel`。
  - `authored-metadata.tsv` 当前只导出 public `WinUISkiaHostPanel`；internal `WinUISkiaAutomationPeer` 仍生成 CCW type details，用于 `onCreateAutomationPeer()` 返回给 WinUI。

- [x] `samples/SkiaWinUISample:compileKotlin`
  - 最近结果：通过。
  - 覆盖 standalone WinUI sample 消费 `WinUISkiaLayerRenderDelegate`。

- [x] `samples/SkiaMultiplatformSample:compileWinuiJvmKotlinAwt`
  - 最近结果：通过。
  - 已知 warning：KGP reports `winuiMain` / `commonMain` source-set tree mismatch。

- [x] `:skiko-winui:compileWinuiJvmNativeWindowsX64`
  - 最近结果：通过。
  - 覆盖 WinUI JVM native helper DLL。

- [x] `:skiko-winui:compileWinuiSkikoWindowsX64`
  - 最近结果：通过。
  - 构建 WinUI-owned `skiko-windows-x64.dll`，使用 Skiko common/jvm native sources，排除 AWT/JAWT/OpenGL/render entry points。

- [x] `:skiko-winui:skikoWinuiWindowsRuntimeJar`
  - 最近结果：通过。
  - Jar root contains `icudtl.dat`、`skiko-windows-x64.dll`、`skiko-windows-x64.dll.sha256`。

- [x] `:skiko-winui:publishSkikoWinuiToMavenLocal`
  - 最近结果：通过。
  - Published focused JVM API jar and Windows runtime jar。

- [ ] Blocked: Maven dependency mode sample compile.
  - `samples/SkiaWinUISample -Pskiko.winui.dependencyMode=maven` 仍依赖未发布的 `io.github.composefluent.winrt:winrt-runtime:0.1.0-SNAPSHOT`。

- [ ] Blocked: `winui-mingw` runtime validation.
  - 2026-06-01 `.\.agent_scripts\run_windows_gradle.ps1 winui-mingw-compile` 失败。
  - 第一层已解除：移除未使用 coroutines 依赖后，不再需要下载 `kotlinx-coroutines-core-mingwx64` / `atomicfu-mingwx64`。
  - 2026-06-01 已确认新增的 `winuiJvmMain` runtime-only `kotlinx-coroutines-core-jvm` 没有污染 `winui-mingw`。
  - 第二层已解除：`kotlin-winrt:winrt-runtime:compileKotlinMingwX64` 和 `exportCrossCompilationMetadataForMingwX64ApiElements` 可被 composite/local klib 路径解析，generated projection 不再报 `io.github.composefluent.winrt.runtime` unresolved。
  - 第三层已解除：Skiko 本体新增默认关闭的 `mingwX64` API variant 后，`skiko:compileKotlinMingwX64` 可通过；期间修正了 `Resources.native.kt` 中 `ftell()` 在 mingw 下返回 `Int` 导致的 `Int`/`Long` 比较错误。
  - 第四层已解除：`winuiMingwMain` 临时 no-op `WinRTAuthoringTypeDetailsRegistrar` 让 compiler plugin 不再停在 `WinRtAuthoringSupportIntrinsic.ensureInitialized()` 的 registrar lookup。
  - 当前失败：kotlin-winrt compiler plugin 在 Native compile 中仍尝试执行 JVM FFM projection intrinsic lowering，要求 `java.lang.foreign` / JVM target 25；该 lowering 应只用于 JVM projection，Native 需要跳过或使用 Native-safe lowering/runtime path。
  - 2026-06-01 复测：尝试把 generated projection intrinsic import 改写到 compile-only fallback object 后仍失败在同一 kotlin-winrt JVM FFM lowering 诊断；说明 downstream 源码 alias 不是可行绕法，需要 kotlin-winrt compiler plugin 目标感知或 Native lowering。随后脚本恢复为原始 import 路径，`winui-mingw-compile` 仍稳定复现同一 blocker。
  - 2026-06-01 进一步诊断：Native compiler plugin classpath 配置为空仍复现，说明本地 workaround 不能通过 Gradle 配置精准移除插件；建议 kotlin-winrt 在 `lowerProjectionIntrinsics(...)` 中按 `pluginContext.platform.isJvm()` 做最小 target guard。
  - 后续仍缺 Kotlin/Native WinUI COM/D3D implementation 和对应 packaging。
