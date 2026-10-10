# Skiko WinUI Indirect Pointer Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit HWND precision-touchpad frontend to `skiko-winui` that emits device-relative indirect pointer frames through one shared native core for `winui-jvm` and `winui-mingw`.

**Architecture:** Keep XAML pointer input unchanged and add a second `WinUIInputHandler` channel. A shared C++ state machine owns HWND subclassing, prerelease User32 API resolution, chronological history parsing, frame de-duplication, cancellation, and Windows fallback; thin JNI and Kotlin/Native adapters only marshal the shared C event view. Window binding remains explicit because touchpad registration is HWND-wide and last-caller-wins.

**Tech Stack:** Kotlin Multiplatform, Kotlin/JVM JNI, Kotlin/Native `mingwX64`, C++20, Win32 `WM_POINTER`, `SetWindowSubclass`, WinRT `IWindowNative`, Gradle Kotlin DSL, Kotlin Test.

## Global Constraints

- Preserve both `winui-jvm` and `winui-mingw`; no shared JVM, AWT, or Swing types.
- Keep XAML `PointerRoutedEventArgs` on the existing `onPointerEvent` path; never synthesize indirect positions from XAML coordinates.
- Use `POINTER_TOUCH_INFO.pointerInfo.ptHimetricLocation`; never use `ptPixelLocation` for indirect positions.
- Resolve `RegisterTouchpadCapableWindow` from User32 ordinal `2689` and `GetPointerFrameTouchpadInfoHistory` from ordinal `2694` at runtime.
- Forward every unconsumed native message exactly once through `DefSubclassProc`.
- Emit history oldest-to-newest and de-duplicate complete frames by source device, frame ID, and timestamp.
- Keep one live Skiko indirect-input owner per HWND.
- Do not edit the curated root `PLAN.md`.
- Preserve the pre-existing modified Gradle/buildSrc files and stage only files owned by each task.
- Run Gradle with JDK 25 or newer and `--no-configuration-cache`.

---

### Task 1: Define the Shared Kotlin Event Contract

**Files:**
- Modify: `skiko/src/winuiMain/kotlin/org/jetbrains/skiko/winui/WinUIInput.kt`
- Create: `skiko/src/winuiJvmTest/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerEventTest.kt`

**Interfaces:**
- Consumes: the existing `WinUIInputHandler` owned by `WinUISkiaLayerSurface.inputHandler`.
- Produces: `WinUIIndirectPointerEvent`, `WinUIIndirectPointerChange`, device rectangle, event type, primary axis, `onIndirectPointerEvent`, and `onIndirectPointerCancel`.

- [ ] **Step 1: Write the failing JVM contract test**

Create a test that instantiates an event with two contacts and verifies every field, then verifies the new handler methods default to `false`/no-op:

```kotlin
class WinUIIndirectPointerEventTest {
    @Test
    fun preservesDeviceRelativeFrameFields() {
        val event = WinUIIndirectPointerEvent(
            type = WinUIIndirectPointerEventType.PRESS,
            changes = listOf(
                WinUIIndirectPointerChange(
                    pointerId = 7,
                    timestampMillis = 12,
                    x = 1450f,
                    y = 320f,
                    pressed = true,
                    pressure = 0.5f,
                    previousTimestampMillis = 12,
                    previousX = 1450f,
                    previousY = 320f,
                    previousPressed = false,
                ),
            ),
            primaryDirectionalMotionAxis = WinUIIndirectPointerPrimaryDirectionalMotionAxis.NONE,
            deviceId = 99,
            deviceRect = WinUIIndirectPointerDeviceRect(0, 0, 12000, 7000),
            frameId = 42,
        )

        assertEquals(1450f, event.changes.single().x)
        assertEquals(WinUIIndirectPointerEventType.PRESS, event.type)
        assertEquals(42L, event.frameId)
        assertFalse(object : WinUIInputHandler {}.onIndirectPointerEvent(event))
        object : WinUIInputHandler {}.onIndirectPointerCancel()
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from the repository root with JDK 25:

```powershell
& .\gradlew.bat '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=false' `
  ':skiko:winuiJvmTest' '--tests' 'org.jetbrains.skiko.winui.WinUIIndirectPointerEventTest' `
  '--no-configuration-cache' '--max-workers=1' '--console=plain'
```

Expected: Kotlin compilation fails because the indirect pointer types and handler methods do not exist.

- [ ] **Step 3: Add the exact shared contract**

Append these methods and declarations to `WinUIInput.kt`:

```kotlin
interface WinUIInputHandler {
    fun onPointerEvent(event: WinUIPointerEvent): Boolean = false
    fun onIndirectPointerEvent(event: WinUIIndirectPointerEvent): Boolean = false
    fun onIndirectPointerCancel() {}
    // Existing key/text/focus methods remain unchanged.
}

enum class WinUIIndirectPointerEventType { PRESS, MOVE, RELEASE }

enum class WinUIIndirectPointerPrimaryDirectionalMotionAxis { NONE, X, Y }

data class WinUIIndirectPointerDeviceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class WinUIIndirectPointerChange(
    val pointerId: Long,
    val timestampMillis: Long,
    val x: Float,
    val y: Float,
    val pressed: Boolean,
    val pressure: Float,
    val previousTimestampMillis: Long,
    val previousX: Float,
    val previousY: Float,
    val previousPressed: Boolean,
)

data class WinUIIndirectPointerEvent(
    val type: WinUIIndirectPointerEventType,
    val changes: List<WinUIIndirectPointerChange>,
    val primaryDirectionalMotionAxis: WinUIIndirectPointerPrimaryDirectionalMotionAxis,
    val deviceId: Long,
    val deviceRect: WinUIIndirectPointerDeviceRect?,
    val frameId: Long,
)
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2. Expected: `WinUIIndirectPointerEventTest` passes.

- [ ] **Step 5: Commit the shared contract**

```powershell
git add -- skiko/src/winuiMain/kotlin/org/jetbrains/skiko/winui/WinUIInput.kt `
  skiko/src/winuiJvmTest/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerEventTest.kt
git commit -m "feat: define Skiko WinUI indirect pointer events"
```

### Task 2: Build the Native Parser and State Machine Test-First

**Files:**
- Create: `skiko/src/winuiMain/cpp/windows/winuiIndirectPointerInput.h`
- Create: `skiko/src/winuiMain/cpp/windows/winuiIndirectPointerInput.cc`
- Create: `skiko/src/winuiTest/cpp/windows/winuiIndirectPointerInputTest.cc`
- Modify: `skiko/gradle/winui-windows-native.gradle.kts`

**Interfaces:**
- Consumes: Windows `POINTER_TOUCH_INFO`, `GetPointerDeviceRects`, and C callback function pointers.
- Produces: the C ABI below plus a C++ `HistoryProcessor` used by both the live binding and synthetic tests.

- [ ] **Step 1: Add the synthetic native test before the implementation**

The test executable must construct reverse-chronological rows for press, move, and release frames; call `skiko::winui::indirect::HistoryProcessor::process`; and assert:

```cpp
assert(events.size() == 3);
assert(events[0].type == SKIKO_WINUI_INDIRECT_POINTER_PRESS);
assert(events[0].changes[0].x == 1200.0f);
assert(events[1].changes[0].previous_x == 1200.0f);
assert(events[1].changes[0].pressure == 0.5f);
assert(events[2].type == SKIKO_WINUI_INDIRECT_POINTER_RELEASE);
assert(events[2].changes[0].pressed == 0);
assert(processor.process(duplicateFrame).empty());
```

Add cases for a second contact joining, a released contact disappearing on the next frame, missing pressure (`1f` while pressed and `0f` when released), press-over-release event priority, malformed dimensions, source-device changes, and timestamp extension.

- [ ] **Step 2: Register and run the native test task to verify RED**

Add `runWinuiIndirectPointerNativeTests`, compiling the test and shared source with `cl.exe /std:c++20 /EHsc`, Windows SDK includes, and `User32.lib Comctl32.lib Ole32.lib`. Run:

```powershell
& .\gradlew.bat '-Pskiko.winui.enabled=true' ':skiko:runWinuiIndirectPointerNativeTests' `
  '--no-configuration-cache' '--max-workers=1' '--console=plain'
```

Expected: compilation fails because the shared header and `HistoryProcessor` implementation are absent.

- [ ] **Step 3: Define the stable C ABI in the header**

Use fixed-width fields and no Kotlin/JNI types:

```cpp
enum SkikoWinUIIndirectPointerEventType : int32_t {
    SKIKO_WINUI_INDIRECT_POINTER_PRESS = 0,
    SKIKO_WINUI_INDIRECT_POINTER_MOVE = 1,
    SKIKO_WINUI_INDIRECT_POINTER_RELEASE = 2,
};

enum SkikoWinUIIndirectPointerUnavailableReason : int32_t {
    SKIKO_WINUI_INDIRECT_POINTER_AVAILABLE = 0,
    SKIKO_WINUI_INDIRECT_POINTER_API_NOT_PRESENT = 1,
    SKIKO_WINUI_INDIRECT_POINTER_HWND_UNAVAILABLE = 2,
    SKIKO_WINUI_INDIRECT_POINTER_ALREADY_BOUND = 3,
    SKIKO_WINUI_INDIRECT_POINTER_SUBCLASS_FAILED = 4,
    SKIKO_WINUI_INDIRECT_POINTER_REGISTRATION_FAILED = 5,
};

struct SkikoWinUIIndirectPointerChangeView {
    uint64_t pointer_id;
    int64_t timestamp_millis;
    float x;
    float y;
    uint8_t pressed;
    float pressure;
    int64_t previous_timestamp_millis;
    float previous_x;
    float previous_y;
    uint8_t previous_pressed;
};

struct SkikoWinUIIndirectPointerEventView {
    int32_t type;
    const SkikoWinUIIndirectPointerChangeView* changes;
    uint32_t change_count;
    int32_t primary_directional_motion_axis;
    uint64_t device_id;
    uint8_t has_device_rect;
    int32_t device_rect_left;
    int32_t device_rect_top;
    int32_t device_rect_right;
    int32_t device_rect_bottom;
    uint64_t frame_id;
};

typedef int32_t (*SkikoWinUIIndirectPointerEventCallback)(
    void* context,
    const SkikoWinUIIndirectPointerEventView* event
);
typedef void (*SkikoWinUIIndirectPointerCancelCallback)(void* context);

extern "C" void* skiko_winui_indirect_pointer_create(
    void* window_inspectable,
    void* context,
    SkikoWinUIIndirectPointerEventCallback event_callback,
    SkikoWinUIIndirectPointerCancelCallback cancel_callback,
    int32_t* unavailable_reason
);
extern "C" bool skiko_winui_indirect_pointer_cancel(void* binding);
extern "C" bool skiko_winui_indirect_pointer_close(void* binding);
extern "C" bool skiko_winui_indirect_pointer_is_active(void* binding);
```

- [ ] **Step 4: Implement chronological parsing and transition state**

Implement bounded dimensions (`entriesCount <= 256`, `pointerCount <= 32`, product overflow checked), reverse history rows, state keyed by pointer ID, pressure normalization (`pressure / 1024f` clamped), `PerformanceCount` conversion using `QueryPerformanceFrequency`, extended `dwTime` fallback, device rectangle lookup, and event type priority `PRESS`, then `RELEASE`, then `MOVE`. Reject unknown releases, impossible source-device changes, canceled pointers, and inconsistent frame rows by canceling active state and entering fail-open-until-clean-down mode.

- [ ] **Step 5: Run native tests and verify GREEN**

Run the command from Step 2. Expected: all synthetic assertions pass and the executable exits `0`.

- [ ] **Step 6: Commit the parser/state machine**

```powershell
git add -- skiko/src/winuiMain/cpp/windows/winuiIndirectPointerInput.h `
  skiko/src/winuiMain/cpp/windows/winuiIndirectPointerInput.cc `
  skiko/src/winuiTest/cpp/windows/winuiIndirectPointerInputTest.cc `
  skiko/gradle/winui-windows-native.gradle.kts
git commit -m "feat: parse WinUI touchpad pointer history"
```

### Task 3: Add HWND Registration, Subclassing, and Fallback

**Files:**
- Modify: `skiko/src/winuiMain/cpp/windows/winuiIndirectPointerInput.cc`
- Modify: `skiko/src/winuiTest/cpp/windows/winuiIndirectPointerInputTest.cc`
- Modify: `skiko/gradle/winui-windows-native.gradle.kts`

**Interfaces:**
- Consumes: a WinRT ABI pointer for `Microsoft.UI.Xaml.Window`, C callbacks, and the Task 2 state machine.
- Produces: one HWND owner, dynamic capability detection, scoped subclass lifetime, cancellation, and exact pass-through behavior.

- [ ] **Step 1: Extend the native test with a fake API table and fake next procedure**

Add cases that assert `API_NOT_PRESENT`, `HWND_UNAVAILABLE`, `ALREADY_BOUND`, `SUBCLASS_FAILED`, and `REGISTRATION_FAILED`; assert unconsumed input invokes the fake next procedure once; consumed input invokes it zero times; a duplicate frame reuses the cached consumed result; explicit cancel emits exactly one cancel callback; and repeated close is harmless.

- [ ] **Step 2: Run the native test and verify RED**

Expected: the new lifecycle/fallback assertions fail because live binding behavior is not implemented.

- [ ] **Step 3: Implement the live HWND frontend**

Implement private `IWindowNative` with IID `45D64A29-A63E-4CB6-B498-5781D298CB4F`, query the HWND, record `GetCurrentThreadId`, resolve the two User32 ordinals, install `SetWindowSubclass`, then register. Handle only `WM_POINTERDOWN`, `WM_POINTERUPDATE`, and `WM_POINTERUP` whose `GetPointerType` is `PT_TOUCHPAD`. Query/fetch `GetPointerFrameTouchpadInfoHistory`, parse the complete payload before callbacks, aggregate consumption, and call `DefSubclassProc` once only when no delivered frame was consumed. Reject cancel/close calls from a different thread by returning `false`. Cancel on focus/capture/window/device/lifecycle interruption, remove the subclass before releasing callbacks, and unregister only if the binding still owns the HWND.

- [ ] **Step 4: Link both native paths against the required Windows libraries**

Compile `winuiIndirectPointerInput.cc` as a second object for the JVM DLL and the Mingw archive. Add `Comctl32.lib`, `User32.lib`, and `Ole32.lib` to JVM/native link inputs, and add `user32.lib`/`comctl32.lib` to `windowsSdkSystemLibFiles` for Kotlin/Native consumers.

- [ ] **Step 5: Run the native test and compile both native helpers**

```powershell
& .\gradlew.bat '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=true' `
  ':skiko:runWinuiIndirectPointerNativeTests' `
  ':skiko:compileWinuiJvmNativeWindowsX64' `
  ':skiko:compileWinuiMingwNativeWindowsX64' `
  '--no-configuration-cache' '--max-workers=1' '--console=plain'
```

Expected: all three tasks pass.

- [ ] **Step 6: Commit the HWND frontend**

```powershell
git add -- skiko/src/winuiMain/cpp/windows/winuiIndirectPointerInput.cc `
  skiko/src/winuiTest/cpp/windows/winuiIndirectPointerInputTest.cc `
  skiko/gradle/winui-windows-native.gradle.kts skiko/gradle/winui.gradle.kts
git commit -m "feat: capture precision touchpad frames on WinUI HWNDs"
```

### Task 4: Add the JVM Adapter and Explicit Window Binding API

**Files:**
- Create: `skiko/src/winuiJvmMain/cpp/windows/winuiIndirectPointerInputJni.cc`
- Create: `skiko/src/winuiMain/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerInputBinding.kt`
- Create: `skiko/src/winuiMain/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerInputNative.kt`
- Create: `skiko/src/winuiJvmMain/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerInputNative.jvm.kt`
- Modify: `skiko/src/winuiMain/kotlin/org/jetbrains/skiko/winui/WinUISkiaWindowBinding.kt`
- Modify: `skiko/gradle/winui-windows-native.gradle.kts`
- Create: `skiko/src/winuiJvmTest/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerInputBindingTest.kt`

**Interfaces:**
- Consumes: Task 3 C ABI and `Window.nativeObject.pointer.value`.
- Produces: `Window.bindWinUIIndirectPointerInput(layer)`, optional hosting integration, synchronous callback conversion, and idempotent lifecycle.

- [ ] **Step 1: Write fake-bridge JVM tests and verify RED**

Test active and unavailable bindings, one callback mapped to the exact Kotlin data class, handler consumption returned unchanged, cancel forwarding, close idempotence, and `WinUISkiaWindowBinding.close()` closing its owned indirect binding. Compilation must fail before production classes exist.

- [ ] **Step 2: Add the public binding API**

```kotlin
enum class WinUIIndirectPointerInputUnavailableReason {
    API_NOT_PRESENT,
    HWND_UNAVAILABLE,
    ALREADY_BOUND,
    SUBCLASS_FAILED,
    REGISTRATION_FAILED,
}

class WinUIIndirectPointerInputBinding internal constructor(...) : AutoCloseable {
    val isActive: Boolean
    val unavailableReason: WinUIIndirectPointerInputUnavailableReason?
    fun cancel()
    override fun close()
}

fun Window.bindWinUIIndirectPointerInput(
    layer: WinUISkiaLayerSurface,
): WinUIIndirectPointerInputBinding
```

Define a shared internal platform boundary:

```kotlin
internal interface WinUIIndirectPointerInputNativeBinding : AutoCloseable {
    val isActive: Boolean
    val unavailableReason: WinUIIndirectPointerInputUnavailableReason?
    fun cancel()
}

internal interface WinUIIndirectPointerInputNativeCallback {
    fun onEvent(event: WinUIIndirectPointerEvent): Boolean
    fun onCancel()
}

internal expect fun createWinUIIndirectPointerInputNativeBinding(
    windowPointer: Long,
    callback: WinUIIndirectPointerInputNativeCallback,
): WinUIIndirectPointerInputNativeBinding
```

The public binding delegates to that interface, which lets JVM tests inject a fake without test-only production hooks. The callback reads `layer.inputHandler` at dispatch time, so handler replacement after binding works. Subscribe to `layer.component.unloaded` and cancel an active stream when the XAML host unloads. `cancel` and `close` throw `IllegalStateException` when the native owner thread rejects the call.

- [ ] **Step 3: Implement the JNI adapter**

Store a `JavaVM*`, global callback reference, and cached method IDs. Marshal primitive arrays into a Kotlin internal callback method, detect and clear Java exceptions, return `-1` to the core on failure, and delete the global reference only after native close has removed the subclass. Add a native smoke emitter used by the JVM test to verify all fields and exception containment.

- [ ] **Step 4: Integrate optional hosting ownership**

Change the host signature to:

```kotlin
fun Window.hostWinUISkiaLayer(
    layer: WinUISkiaLayerSurface,
    width: Double? = null,
    height: Double? = null,
    closeLayerOnWindowClosed: Boolean = true,
    enableIndirectPointerInput: Boolean = false,
): WinUISkiaWindowBinding
```

When enabled, construct one indirect binding after setting content and close it before closing the layer. The default remains `false`.

- [ ] **Step 5: Run JVM tests and native compilation**

Run the focused tests plus `compileKotlinWinuiJvm`; expected: pass with no JNI exception escaping.

- [ ] **Step 6: Commit the JVM/API slice**

```powershell
git add -- skiko/src/winuiJvmMain/cpp/windows/winuiIndirectPointerInputJni.cc `
  skiko/src/winuiMain/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerInputBinding.kt `
  skiko/src/winuiMain/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerInputNative.kt `
  skiko/src/winuiJvmMain/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerInputNative.jvm.kt `
  skiko/src/winuiMain/kotlin/org/jetbrains/skiko/winui/WinUISkiaWindowBinding.kt `
  skiko/src/winuiJvmTest/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerInputBindingTest.kt `
  skiko/gradle/winui-windows-native.gradle.kts
git commit -m "feat: bind WinUI indirect pointer input on JVM"
```

### Task 5: Add the Kotlin/Native Adapter and ABI Parity Tests

**Files:**
- Create: `skiko/src/winuiMingwMain/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerInputNative.mingw.kt`
- Modify: `skiko/src/winuiMingwTest/kotlin/org/jetbrains/skiko/winui/WinUIMingwNativeBridgeSmokeTest.kt`

**Interfaces:**
- Consumes: the same Task 3 C ABI.
- Produces: `staticCFunction` callbacks, `StableRef` lifetime ownership, and the same common binding behavior as JVM.

- [ ] **Step 1: Add Mingw smoke tests and verify RED**

Add a test that uses the native smoke emitter to assert field fidelity and Boolean consumption, plus a throwing handler test that verifies cancellation and no exception crossing the C boundary.

- [ ] **Step 2: Implement the Native adapter**

Use `StableRef<CallbackContext>`, `staticCFunction` event/cancel callbacks, `interpretCPointer`, and `readValue`/indexed reads. Return `-1` after catching Kotlin exceptions. Dispose the stable reference only after `skiko_winui_indirect_pointer_close` succeeds.

- [ ] **Step 3: Run Mingw tests/compile**

```powershell
& .\gradlew.bat '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=true' `
  ':skiko:compileKotlinWinuiMingw' ':skiko:winuiMingwTest' `
  '--no-configuration-cache' '--max-workers=1' '--console=plain'
```

Expected: compilation and tests pass using the shared core archive.

- [ ] **Step 4: Commit Native parity**

```powershell
git add -- skiko/src/winuiMingwMain/kotlin/org/jetbrains/skiko/winui/WinUIIndirectPointerInputNative.mingw.kt `
  skiko/src/winuiMingwTest/kotlin/org/jetbrains/skiko/winui/WinUIMingwNativeBridgeSmokeTest.kt
git commit -m "feat: bridge WinUI indirect pointer input to mingw"
```

### Task 6: Run Skiko Cross-Backend Validation

**Files:**
- Modify: none.

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: a verified Skiko contract ready for the sibling Compose repository.

- [ ] **Step 1: Run focused and full checks**

Run native synthetic tests, JVM tests, JVM compile, Mingw compile/test, AWT-free boundary checks, and `git diff --check`. If physical touchpad hardware is available, run the JVM smoke with indirect input enabled and confirm the binding reports active; otherwise record hardware validation as skipped.

- [ ] **Step 2: Publish a local integration artifact without changing versions in source**

Publish `skiko-winui`, Windows runtime, and Mingw artifacts to Maven Local with a unique `-Pskiko.version=0.0.0-indirect-pointer-local-SNAPSHOT`, then inspect the jar/POM to confirm the new Kotlin API and native DLL are packaged.

- [ ] **Step 3: Review repository scope**

```powershell
git diff --check
git status --short
git log --oneline -6
```

Expected: only the implementation commits are new; the five pre-existing modified build files remain preserved and unstaged.
