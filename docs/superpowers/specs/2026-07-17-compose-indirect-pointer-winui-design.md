# Compose Indirect Pointer Input for Skiko WinUI Design

Date: 2026-07-17

Status: Draft for written review

## Problem

Compose already defines `IndirectPointerEvent` for input whose coordinates are
relative to an input device rather than the screen. Such events are routed
through the focused Compose hierarchy instead of pointer hit testing. The
current `skiko-winui` input frontend only consumes WinUI XAML
`PointerRoutedEventArgs`. Although XAML reports `PointerDeviceType.Touchpad`,
the `PointerPoint.Position` value is a panel/cursor position in DIPs, not a
contact position on the touchpad. Converting that event into a Compose
`IndirectPointerEvent` would therefore violate the Compose coordinate contract.

Windows 11 exposes a separate, prerelease precision-touchpad path. A window can
opt in with `RegisterTouchpadCapableWindow`, receive touchpad `WM_POINTER`
messages, and retrieve device-relative contact frames with
`GetPointerFrameTouchpadInfoHistory`. The touchpad contact position is carried
in `POINTER_TOUCH_INFO.pointerInfo.ptHimetricLocation`; the pixel fields remain
at the mouse position from the beginning of the gesture and must not be used.

This design adds that HWND frontend to Skiko, keeps it independent from normal
XAML pointer input, and connects it to both the standalone Compose WinUI owner
and the reusable Skiko `ComposeScene` input boundary.

## Scope

This is one vertical input slice with coordinated changes in the Skiko and
Compose forks:

1. `compose-fluent-skiko` defines the WinUI event contract, explicit window
   bindings, shared Windows capture core, and JVM/Mingw adapters.
2. `compose-fluent-compose-multiplatform-core` defines the Skiko platform event,
   `ComposeScene` entry point, focus-change cancellation helper, and the current
   standalone Compose WinUI adapter.
3. Focused tests cover the shared event/state contracts, both Skiko ABIs, and
   Compose focus routing. Hardware validation covers the integrated runtime.

The Skiko artifact remains independently usable: applications that do not use
Compose can handle the new event channel directly, while applications that do
not opt in retain their current behavior.

## Goals

- Deliver precision-touchpad contact frames as a distinct Skiko indirect
  pointer event stream.
- Preserve device-relative positions, pointer IDs, pressure, timestamps,
  device bounds, multi-contact frames, and coalesced history.
- Let focused Compose `IndirectPointerInputModifierNode`s consume the stream.
- Preserve standard Windows touchpad scrolling and zooming whenever Compose
  does not consume the corresponding native message.
- Make HWND-wide touchpad registration an explicit window-owner decision.
- Use one native parsing and lifecycle core for both `winui-jvm` and
  `winui-mingw`, with only thin ABI adapters.
- Keep common contracts free of JVM, AWT, and Swing types.
- Fail safely on Windows versions that do not expose the prerelease APIs.

## Non-goals

- Do not synthesize indirect input from XAML `PointerRoutedEventArgs`.
- Do not translate contacts into Compose pointer hit-testing events.
- Do not implement scroll, zoom, focus-navigation, inertia, or gesture
  recognition in this slice.
- Do not use `TouchpadGesturesController`, which is intended for global
  three-to-five-finger gestures and can override system behavior.
- Do not use raw HID unless a later requirement targets non-precision or custom
  hardware.
- Do not make every `WinUISkiaLayer` implicitly touchpad-capable.
- Do not require AWT ownership or reuse the AWT Windows message loop.
- Do not edit the curated root `PLAN.md` as part of this work.

## Considered Frontends

### 1. XAML pointer events

This is the smallest implementation, but it is invalid for Compose indirect
input. XAML exposes panel-relative cursor coordinates rather than touchpad
contact coordinates, and it does not expose the complete coalesced touchpad
frame history required for faithful multi-contact input.

### 2. HWND precision-touchpad `WM_POINTER` input

This is the selected approach. It provides the required HIMETRIC device
coordinates, full frames, history, IDs, pressure, and source-device handle. It
also preserves Windows fallback behavior because unconsumed messages can be
passed to the next window procedure and converted to the same wheel/zoom input
that non-capable windows receive today.

### 3. WinRT gesture controllers or raw HID

`PhysicalGestureRecognizer` may later provide a system-aligned gesture/default
navigation layer, but it is not the raw `IndirectPointerEvent` source.
`TouchpadGesturesController` and raw HID have different ownership and
compatibility semantics and are outside this slice.

## Ownership and Explicit Opt-in

Touchpad capability is an HWND-wide setting, and Microsoft documents
`RegisterTouchpadCapableWindow` as "last caller wins." A child component must
not enable it unless it controls how the window processes the resulting
`WM_POINTER` messages. The Skiko API therefore exposes an explicit window
binding rather than a mutable layer property.

The input-specific API is:

```kotlin
fun Window.bindWinUIIndirectPointerInput(
    layer: WinUISkiaLayerSurface,
): WinUIIndirectPointerInputBinding

class WinUIIndirectPointerInputBinding : AutoCloseable {
    val isActive: Boolean
    val unavailableReason: WinUIIndirectPointerInputUnavailableReason?
    fun cancel()
    override fun close()
}
```

Calling `bindWinUIIndirectPointerInput` is the opt-in. It does not replace or
otherwise mutate `Window.content`, which lets the current Compose WinUI host
bind the Skiko render layer embedded inside its own root content tree.

`Window.hostWinUISkiaLayer` gains an optional
`enableIndirectPointerInput: Boolean = false` parameter. When enabled, its
existing `WinUISkiaWindowBinding` owns an input binding internally and closes it
with the window binding. This preserves the convenient whole-window hosting
path without forcing embedded hosts to replace their content.

Skiko maintains a process-local HWND ownership registry. Only one live indirect
input binding can own an HWND. A second binding remains inactive with
`ALREADY_BOUND`; it must not call the registration API or alter the existing
subclass chain. This registry prevents conflicts between Skiko bindings, while
the API documentation states that the caller must also prevent non-Skiko code
from independently changing the HWND's touchpad-capable state.

The unavailability reasons are `API_NOT_PRESENT`, `HWND_UNAVAILABLE`,
`ALREADY_BOUND`, `SUBCLASS_FAILED`, and `REGISTRATION_FAILED`. An unavailable
binding leaves normal input and rendering operational. It reports the failure
instead of failing window creation.

Binding creation, `cancel`, and `close` must run on the dispatcher thread that
owns the WinUI `Window` and HWND. Native event and cancellation callbacks are
delivered synchronously on that same thread. Implementations reject a
cross-thread lifecycle call instead of moving HWND subclass work to an
unrelated worker thread.

## Skiko Event Contract

`WinUIInputHandler` gains a separate channel:

```kotlin
fun onIndirectPointerEvent(event: WinUIIndirectPointerEvent): Boolean = false
fun onIndirectPointerCancel() {}
```

It does not overload `onPointerEvent`, and the existing
`WinUIPointerDeviceType.TOUCHPAD` value remains only part of the XAML pointer
model.

The shared `winuiMain` event model contains:

```kotlin
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
    val primaryDirectionalMotionAxis:
        WinUIIndirectPointerPrimaryDirectionalMotionAxis,
    val deviceId: Long,
    val deviceRect: WinUIIndirectPointerDeviceRect?,
    val frameId: Long,
)
```

The `x` and `y` values are device-relative HIMETRIC units (0.01 millimetre).
They are not converted to DIPs, pixels, screen coordinates, or a normalized
zero-to-one range. `deviceRect`, when available from `GetPointerDeviceRects`,
uses the same device coordinate space.

Native pressure is normalized from the Windows `0..1024` range to a Compose-
compatible `0f..1f` value. If the native pressure mask is absent, a pressed
contact uses `1f` and a released contact uses `0f`. Timestamps are monotonic
milliseconds derived from `PerformanceCount` when available, with extended
`dwTime` as the fallback; they are not wall-clock time.

Precision touchpads do not declare a primary one-dimensional motion axis, so
the first version reports `NONE`. The device rectangle is retained for future
device-specific policy, but aspect ratio is not used as an axis heuristic.

Each `WinUIIndirectPointerEvent` represents one native frame and contains every
contact in that frame. A release frame still contains the released contact with
`pressed=false`; that contact is removed from subsequent frames. For the first
press of an ID, previous position and time equal the current values and
`previousPressed=false`. Coalesced history is emitted as multiple events in
oldest-to-newest order, so every event's previous fields refer to the event
immediately before it.

The event type is derived from current-versus-previous contact state, not from
whichever pointer message for the frame happened to be dequeued first. A frame
with any new pressed contact is `PRESS`; otherwise a frame with any released
contact is `RELEASE`; otherwise it is `MOVE`. If a valid frame contains both a
press and a release transition, `PRESS` wins while the individual changes still
carry both transitions. This keeps frame de-duplication deterministic.

## Native Capture and Data Flow

The shared C++ core is placed under a WinUI-owned Windows source directory and
is compiled into both native runtime paths. Its lifecycle is:

1. Receive the WinRT ABI pointer for the `Microsoft.UI.Xaml.Window`.
2. Query `IWindowNative` and obtain the HWND.
3. Resolve the prerelease functions dynamically from `User32.dll`.
4. Install a scoped window subclass with `SetWindowSubclass`; use
   `DefSubclassProc` for the existing WinUI procedure chain rather than
   replacing `GWLP_WNDPROC` directly.
5. Call `RegisterTouchpadCapableWindow(hwnd, TRUE)` only after the callback and
   subclass are ready.
6. Intercept `WM_POINTERDOWN`, `WM_POINTERUPDATE`, and `WM_POINTERUP`; use
   `GetPointerType` to reject every type except `PT_TOUCHPAD`.
7. Query and fetch `GetPointerFrameTouchpadInfoHistory` for the pointer ID,
   validate the two-dimensional `entriesCount * pointerCount` buffer, and parse
   the complete message before invoking managed callbacks.
8. Reverse the API's reverse-chronological rows and deliver new frames from
   oldest to newest.
9. Aggregate callback consumption for the current native message. Suppress the
   message when any delivered frame was consumed; otherwise call
   `DefSubclassProc` exactly once.
10. On close, cancel an active stream, unregister the window if this binding
    still owns it, remove the subclass, release callbacks, and remove the HWND
    from the ownership registry.

The implementation does not call `SkipPointerFrameMessages`, because other
components on the WinUI thread may still expect the individual messages.
Instead, it de-duplicates complete frames by source device, frame ID, and
timestamp. It caches the consumed decision for a frame so that later pointer
messages belonging to the same frame are either all forwarded or all
suppressed without dispatching duplicate Compose events.

The parser reads `ptHimetricLocation`, pointer flags, pressure, `frameId`,
source-device handle, `PerformanceCount`/`dwTime`, and device rectangles. It
never uses `ptPixelLocation` for indirect positions. Buffer dimensions and the
number of active contacts are bounded before allocation; an excessive or
inconsistent payload is treated as malformed input.

## Runtime Capability Detection

The repository currently builds with Windows SDK `10.0.26100.0`, whose headers
do not yet declare these prerelease functions. The installed Windows runtime
does export them, and Microsoft documents their User32 ordinals. The native core
therefore declares private function-pointer signatures and resolves:

- `RegisterTouchpadCapableWindow` from User32 ordinal 2689.
- `GetPointerFrameTouchpadInfoHistory` from User32 ordinal 2694.

No static import is added, so loading the Skiko runtime remains safe on systems
without the APIs. Availability requires both functions, successful
`IWindowNative` lookup, successful subclass installation, and successful
window registration. Function presence and registration success are the
capability check; an OS-version string is not used as a substitute.

## JVM and Mingw Parity

The Windows parsing, state machine, HWND ownership, subclass, capability table,
and consumption logic live in the shared C++ core. It exposes a narrow C ABI
with event and cancellation callbacks.

For `winui-jvm`, a JNI adapter owns a global callback reference, obtains or
attaches a `JNIEnv` for the WndProc thread as necessary, converts the C event
view into the shared Kotlin data classes, and catches all managed exceptions at
the ABI boundary.

For `winui-mingw`, a Kotlin/Native adapter passes `staticCFunction` callbacks
and a `StableRef` context through the same C ABI. Closing the binding releases
the stable reference only after the native subclass can no longer invoke it.

Neither adapter implements parsing rules. Event ordering and error behavior
must be identical in JVM and Native tests. Compose-facing mapping is kept in
`winuiMain`; no Java or JNI type enters the shared API. If the sibling Compose
repository cannot yet run its full Mingw application because of kotlin-winrt
runtime limitations, Skiko's Mingw bridge still has to compile and pass its
native/C-ABI smoke coverage, and the Compose code must remain source-set shared
rather than becoming a JVM-only implementation.

## Compose Bridge

Compose common code already provides `IndirectPointerEvent`,
`IndirectPointerInputChange`, and
`FocusOwner.dispatchIndirectPointerEvent`/`dispatchIndirectPointerCancel`.
Skiko is missing the platform event and scene entry point.

The Compose Skiko source set adds an internal
`SkikoIndirectPointerEvent : PlatformIndirectPointerEvent`. It stores the
mapped changes, event type, primary axis, and optional native Skiko event for
diagnostics.

`ComposeScene` adds:

```kotlin
fun sendIndirectPointerEvent(event: IndirectPointerEvent): Boolean
fun cancelIndirectPointerInput()
```

`BaseComposeScene` wraps dispatch in the same invalidation/effect boundary used
for key and rotary input. `PlatformLayersComposeScene` dispatches to its main
owner. `CanvasLayersComposeScene` dispatches to the currently focused layer,
falling back to the main owner, matching key and rotary routing. Cancellation
is sent to the focused owner for the active stream.

The current standalone Compose WinUI target does not use `ComposeScene`; it has
a direct `WinUIOwner`. `WinUIComposeView(window, ...)` therefore creates the
Skiko input binding through its render host, maps each
`WinUIIndirectPointerEvent` to `SkikoIndirectPointerEvent`, and synchronously
calls `WinUIOwner.focusOwner.dispatchIndirectPointerEvent`. The callback's
Boolean result is returned unchanged to Skiko. A `WinUIComposeView` created
without a `Window` remains explicitly unsupported for HWND indirect input until
its host supplies an owning window binding.

The mapping is one-to-one:

- Skiko pointer IDs become Compose `PointerId`s.
- HIMETRIC `x/y` values become `Offset` without screen conversion.
- Current and previous times, positions, pressed states, and normalized
  pressure populate `IndirectPointerInputChange` directly.
- `PRESS`, `MOVE`, and `RELEASE` map to the corresponding Compose event types.
- The first version maps the primary axis to `None`.

Compose also installs the same focus-change cancellation behavior used by
Android. A `FocusListener` compares the previous and current focused nodes'
`IndirectPointerInput` ancestor chains and calls
`onCancelIndirectPointerInput` only on modifiers that lost focus. This matters
when an indirect gesture itself changes focus: shared ancestors remain active,
while the old focused branch is canceled before later frames route to the new
branch. The helper is shared by Skiko `RootNodeOwner` and `WinUIOwner` rather
than duplicated.

## Consumption and Windows Fallback

Compose consumption is determined by
`FocusOwner.dispatchIndirectPointerEvent`, which returns true when any change
was consumed. Skiko aggregates that result across all historical frames emitted
for one native message.

- If any frame is consumed, the native message is not passed to
  `DefSubclassProc`.
- If no frame is consumed, the message is passed through unchanged, preserving
  the system's normal wheel/zoom conversion.
- If no Compose node is focused or no indirect modifier handles the event, the
  message is unconsumed and follows the Windows fallback path.

Consumption is message-based rather than latched for the entire gesture. A
handler that needs exclusive ownership should consume the press/start of the
stream; consuming only after default processing has begun can naturally allow
some earlier Windows fallback behavior.

The native core fully parses a message before dispatch. Managed exceptions are
caught by the ABI adapter, trigger cancellation, and never unwind through a
WndProc. If an exception occurs after an earlier frame in the same message was
consumed, that message remains suppressed; otherwise it is forwarded.

## Cancellation, Lifecycle, and Errors

The native state machine has one active stream per source device. It emits one
`onIndirectPointerCancel` and clears all previous-state/deduplication data when
an active stream is interrupted by:

- window deactivation or `WM_KILLFOCUS`;
- `WM_CANCELMODE`, pointer/capture loss, or device removal;
- XAML host unload;
- explicit `WinUIIndirectPointerInputBinding.cancel()`;
- layer detach, binding close, window destruction, or runtime shutdown;
- a failed history query after a stream started;
- unknown releases, impossible pointer transitions, inconsistent device IDs,
  or an invalid/oversized frame buffer.

After malformed input, the parser forwards native messages and ignores moves
until it observes a clean new down sequence. Release removes a pointer's saved
state; releasing the final contact ends the stream without a cancellation.

Compose cancellation calls `dispatchIndirectPointerCancel` on the active focus
path. Focus-target changes inside Compose use the more precise `FocusListener`
path described above and do not reset the native contact state. Closing is
idempotent on both ABIs, and callbacks cannot run after close returns.

Capability failures during binding creation are reported through
`unavailableReason` and leave the window usable. Runtime parsing failures are
fail-open for Windows fallback, except when an event from the same native
message was already consumed as described above.

## Validation

### Pure Kotlin and Compose tests

- Map a one-contact press/move/release sequence with exact current and previous
  fields.
- Map multi-contact frames where one pointer is added or released.
- Verify HIMETRIC values are not multiplied by display density or content
  scale.
- Verify pressure normalization and missing-pressure fallback.
- Verify `SkikoIndirectPointerEvent` dispatch reaches only the focused node and
  its indirect-input ancestors in Initial, Main, and Final passes.
- Verify `CanvasLayersComposeScene` selects the focused owner.
- Verify consumption returns synchronously to the Skiko callback.
- Verify explicit cancel and focus changes cancel the correct modifier chain,
  including the shared-ancestor case.

### Native synthetic tests

- Inject a fake API table for missing exports, registration failure, subclass
  failure, and successful activation.
- Parse reverse-chronological history into oldest-to-newest events.
- Validate frame de-duplication when multiple messages reference the same
  frame.
- Verify unconsumed messages call the next procedure exactly once and consumed
  messages do not.
- Verify buffer sizing/retry, allocation bounds, source-device changes,
  timestamp conversion, malformed sequences, cancellation, and idempotent
  cleanup.

### ABI tests

- JVM smoke coverage verifies callback field fidelity, exception containment,
  global-reference cleanup, and native-library packaging.
- Mingw smoke coverage verifies the identical C ABI, `StableRef` lifetime,
  callback consumption, and shared-core linkage.
- Existing `winui-jvm` and `winui-mingw` compile tasks and AWT-free boundary
  checks remain green.

### Windows runtime validation

On a Windows 11 machine with a precision touchpad:

1. Confirm the binding reports active on both Skiko runtime paths where the
   host toolchain can run them.
2. Confirm two-finger contacts report changing HIMETRIC device positions,
   stable pointer IDs, pressure, device bounds, and chronological history.
3. Confirm an unconsumed gesture produces the existing system scroll/zoom
   behavior exactly once.
4. Confirm a focused Compose modifier receives and consumes press, move, and
   release events and suppresses the Windows fallback.
5. Confirm Alt+Tab/window deactivation, capture loss, unload, detach, and close
   each cancel an active stream without later callbacks or leaks.
6. Confirm ordinary mouse, pen, touch, keyboard, text input, rendering, and
   accessibility behavior are unchanged.

## References

- [Precision Touchpad Programming Guide](https://learn.microsoft.com/windows/win32/input-precisiontouchpad/precision-touchpad-guide)
- [RegisterTouchpadCapableWindow](https://learn.microsoft.com/windows/win32/input-precisiontouchpad/registertouchpadcapable)
- [GetPointerTouchpadInfo functions](https://learn.microsoft.com/windows/win32/input-precisiontouchpad/getpointertouchpadinfo)
- [IWindowNative](https://learn.microsoft.com/windows/apps/develop/ui-input/retrieve-hwnd)
- [Compose IndirectPointerEvent](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/input/indirect/IndirectPointerEvent.kt)
- [Skiko ComposeScene](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/scene/ComposeScene.skiko.kt)
