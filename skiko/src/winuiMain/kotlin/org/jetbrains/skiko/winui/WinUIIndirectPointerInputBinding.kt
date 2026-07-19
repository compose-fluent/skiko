package org.jetbrains.skiko.winui

import microsoft.ui.xaml.RoutedEventHandler
import microsoft.ui.xaml.Window

enum class WinUIIndirectPointerInputUnavailableReason {
    API_NOT_PRESENT,
    HWND_UNAVAILABLE,
    ALREADY_BOUND,
    SUBCLASS_FAILED,
    REGISTRATION_FAILED,
}

internal typealias WinUIIndirectPointerInputUnloadRegistrar =
    (() -> Unit) -> AutoCloseable

class WinUIIndirectPointerInputBinding internal constructor(
    private val nativeBinding: WinUIIndirectPointerInputNativeBinding,
    registerUnloaded: WinUIIndirectPointerInputUnloadRegistrar? = null,
) : AutoCloseable {
    private var isClosed = false
    private var unloadedRegistration: AutoCloseable? =
        if (nativeBinding.isActive) {
            registerUnloaded?.invoke {
                if (!isClosed) {
                    cancel()
                }
            }
        } else {
            null
        }

    val isActive: Boolean
        get() = !isClosed && nativeBinding.isActive

    val unavailableReason: WinUIIndirectPointerInputUnavailableReason?
        get() = nativeBinding.unavailableReason

    fun cancel() {
        if (!isClosed) {
            nativeBinding.cancel()
        }
    }

    override fun close() {
        if (isClosed) {
            return
        }
        nativeBinding.close()
        isClosed = true
        unloadedRegistration?.close()
        unloadedRegistration = null
    }
}

internal fun createWinUIIndirectPointerInputBinding(
    windowPointer: Long,
    inputHandlerProvider: () -> WinUIInputHandler?,
    nativeBindingFactory: (
        Long,
        WinUIIndirectPointerInputNativeCallback,
    ) -> WinUIIndirectPointerInputNativeBinding = ::createWinUIIndirectPointerInputNativeBinding,
    registerUnloaded: WinUIIndirectPointerInputUnloadRegistrar? = null,
): WinUIIndirectPointerInputBinding {
    val callback = object : WinUIIndirectPointerInputNativeCallback {
        override fun onEvent(event: WinUIIndirectPointerEvent): Boolean =
            inputHandlerProvider()?.onIndirectPointerEvent(event) ?: false

        override fun onCancel() {
            inputHandlerProvider()?.onIndirectPointerCancel()
        }
    }
    return WinUIIndirectPointerInputBinding(
        nativeBinding = nativeBindingFactory(windowPointer, callback),
        registerUnloaded = registerUnloaded,
    )
}

fun Window.bindWinUIIndirectPointerInput(
    layer: WinUISkiaLayerSurface,
): WinUIIndirectPointerInputBinding {
    val component = layer.component
    return createWinUIIndirectPointerInputBinding(
        windowPointer = nativeObject.pointer.value,
        inputHandlerProvider = { layer.inputHandler },
        registerUnloaded = { onUnloaded ->
            val token = component.unloaded.add(RoutedEventHandler { _, _ ->
                onUnloaded()
            })
            AutoCloseable {
                component.unloaded.remove(token)
            }
        },
    )
}
