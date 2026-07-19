package org.jetbrains.skiko.winui

import java.lang.reflect.Proxy
import microsoft.ui.xaml.FrameworkElement
import microsoft.ui.xaml.UIElement
import microsoft.ui.xaml.Window
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WinUIIndirectPointerInputBindingTest {
    @Test
    fun exposesActiveAndUnavailableNativeBindings() {
        val active = WinUIIndirectPointerInputBinding(
            nativeBinding = FakeNativeBinding(isActive = true),
        )
        val unavailable = WinUIIndirectPointerInputBinding(
            nativeBinding = FakeNativeBinding(
                isActive = false,
                unavailableReason = WinUIIndirectPointerInputUnavailableReason.API_NOT_PRESENT,
            ),
        )

        assertTrue(active.isActive)
        assertNull(active.unavailableReason)
        assertFalse(unavailable.isActive)
        assertEquals(
            WinUIIndirectPointerInputUnavailableReason.API_NOT_PRESENT,
            unavailable.unavailableReason,
        )
    }

    @Test
    fun dispatchesThroughTheCurrentInputHandlerAndPreservesConsumption() {
        val nativeBinding = FakeNativeBinding(isActive = true)
        var handler: WinUIInputHandler? = null
        lateinit var callback: WinUIIndirectPointerInputNativeCallback
        val binding = createWinUIIndirectPointerInputBinding(
            windowPointer = 123L,
            inputHandlerProvider = { handler },
            nativeBindingFactory = { windowPointer, nativeCallback ->
                assertEquals(123L, windowPointer)
                callback = nativeCallback
                nativeBinding
            },
        )
        val event = sampleEvent()

        assertFalse(callback.onEvent(event))

        var delivered: WinUIIndirectPointerEvent? = null
        var cancelCalls = 0
        handler = object : WinUIInputHandler {
            override fun onIndirectPointerEvent(event: WinUIIndirectPointerEvent): Boolean {
                delivered = event
                return true
            }

            override fun onIndirectPointerCancel() {
                cancelCalls++
            }
        }

        assertTrue(callback.onEvent(event))
        assertSame(event, delivered)
        callback.onCancel()
        assertEquals(1, cancelCalls)
        binding.close()
    }

    @Test
    fun forwardsCancelAndClosesNativeAndUnloadSubscriptionOnce() {
        val nativeBinding = FakeNativeBinding(isActive = true)
        lateinit var onUnloaded: () -> Unit
        var unloadSubscriptionCloseCalls = 0
        val binding = WinUIIndirectPointerInputBinding(
            nativeBinding = nativeBinding,
            registerUnloaded = { callback ->
                onUnloaded = callback
                AutoCloseable { unloadSubscriptionCloseCalls++ }
            },
        )

        binding.cancel()
        onUnloaded()
        assertEquals(2, nativeBinding.cancelCalls)

        binding.close()
        binding.close()
        assertEquals(1, nativeBinding.closeCalls)
        assertEquals(1, unloadSubscriptionCloseCalls)
    }

    @Test
    fun windowBindingClosesIndirectInputBeforeItsLayer() {
        val closeOrder = mutableListOf<String>()
        val indirectBinding = WinUIIndirectPointerInputBinding(
            nativeBinding = FakeNativeBinding(
                isActive = true,
                onClose = { closeOrder += "indirect" },
            ),
        )
        val component = allocateWithoutConstructor(StubFrameworkElement::class.java)
        val window = allocateWithoutConstructor(StubWindow::class.java)
        val layer = fakeLayer(component) { closeOrder += "layer" }
        val windowBinding = WinUISkiaWindowBinding(
            window = window,
            layer = layer,
            previousContent = null,
            closeLayerOnWindowClosed = true,
            indirectPointerInputBinding = indirectBinding,
        )

        windowBinding.close()
        windowBinding.close()

        assertEquals(listOf("indirect", "layer"), closeOrder)
    }

    @Test
    fun jniSmokePreservesEveryEventField() {
        var delivered: WinUIIndirectPointerEvent? = null
        val result = emitWinUIIndirectPointerInputNativeSmoke(
            object : WinUIIndirectPointerInputNativeCallback {
                override fun onEvent(event: WinUIIndirectPointerEvent): Boolean {
                    delivered = event
                    return true
                }

                override fun onCancel() = Unit
            },
        )

        assertEquals(1, result)
        assertEquals(sampleEvent(), delivered)
    }

    @Test
    fun jniReportsUnavailableWhenWindowPointerIsMissing() {
        val binding = createWinUIIndirectPointerInputNativeBinding(
            windowPointer = 0L,
            callback = object : WinUIIndirectPointerInputNativeCallback {
                override fun onEvent(event: WinUIIndirectPointerEvent): Boolean = false
                override fun onCancel() = Unit
            },
        )

        assertFalse(binding.isActive)
        assertTrue(
            binding.unavailableReason in setOf(
                WinUIIndirectPointerInputUnavailableReason.API_NOT_PRESENT,
                WinUIIndirectPointerInputUnavailableReason.HWND_UNAVAILABLE,
            ),
        )
        binding.cancel()
        binding.close()
    }

    @Test
    fun jniSmokeContainsCallbackExceptions() {
        val result = emitWinUIIndirectPointerInputNativeSmoke(
            object : WinUIIndirectPointerInputNativeCallback {
                override fun onEvent(event: WinUIIndirectPointerEvent): Boolean {
                    error("expected callback failure")
                }

                override fun onCancel() = Unit
            },
        )

        assertEquals(-1, result)
    }

    private class FakeNativeBinding(
        override var isActive: Boolean,
        override val unavailableReason: WinUIIndirectPointerInputUnavailableReason? = null,
        private val onClose: () -> Unit = {},
    ) : WinUIIndirectPointerInputNativeBinding {
        var cancelCalls = 0
        var closeCalls = 0

        override fun cancel() {
            cancelCalls++
        }

        override fun close() {
            closeCalls++
            isActive = false
            onClose()
        }
    }

    private class StubWindow : Window() {
        var contentValue: UIElement? = null

        override var content: UIElement?
            get() = contentValue
            set(value) {
                contentValue = value
            }
    }

    private class StubFrameworkElement : FrameworkElement()

    private fun fakeLayer(
        component: FrameworkElement,
        onClose: () -> Unit,
    ): WinUISkiaLayerSurface = Proxy.newProxyInstance(
        WinUISkiaLayerSurface::class.java.classLoader,
        arrayOf(WinUISkiaLayerSurface::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getComponent" -> component
            "close" -> onClose()
            else -> defaultValue(method.returnType)
        }
    } as WinUISkiaLayerSurface

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutConstructor(type: Class<T>): T =
        unsafe.allocateInstance(type) as T

    private fun sampleEvent(): WinUIIndirectPointerEvent = WinUIIndirectPointerEvent(
        type = WinUIIndirectPointerEventType.MOVE,
        changes = listOf(
            WinUIIndirectPointerChange(
                pointerId = 7L,
                timestampMillis = 12L,
                x = 1450f,
                y = 320f,
                pressed = true,
                pressure = 0.5f,
                previousTimestampMillis = 11L,
                previousX = 1400f,
                previousY = 300f,
                previousPressed = true,
            ),
            WinUIIndirectPointerChange(
                pointerId = 8L,
                timestampMillis = 13L,
                x = 2100f,
                y = 640f,
                pressed = false,
                pressure = 0f,
                previousTimestampMillis = 12L,
                previousX = 2050f,
                previousY = 600f,
                previousPressed = true,
            ),
        ),
        primaryDirectionalMotionAxis = WinUIIndirectPointerPrimaryDirectionalMotionAxis.Y,
        deviceId = 99L,
        deviceRect = WinUIIndirectPointerDeviceRect(-10, 20, 12_000, 7_000),
        frameId = 42L,
    )

    companion object {
        private val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let { field ->
            field.isAccessible = true
            field.get(null) as Unsafe
        }
    }
}
