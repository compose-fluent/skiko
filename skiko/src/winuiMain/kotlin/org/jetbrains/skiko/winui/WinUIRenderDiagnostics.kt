package org.jetbrains.skiko.winui

data class WinUILayerRenderDiagnostics(
    val renderVersion: Long,
    val lastRenderedState: WinUILayerRenderState?,
    val pendingInvalidatedState: WinUILayerRenderState?,
    val lastPlatformResult: WinUIPlatformRenderResult?,
    val lastFailure: WinUILayerRenderFailure?,
)

data class WinUILayerRenderState(
    val logicalWidth: Float,
    val logicalHeight: Float,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val contentScale: Float,
)

data class WinUIPlatformRenderResult(
    val width: Int,
    val height: Int,
    val contentScale: Float,
    val throttledToVsync: Boolean,
    val swapChainCreated: Boolean,
    val swapChainResized: Boolean,
    val bufferIndex: Int,
)

data class WinUILayerRenderFailure(
    val state: WinUILayerRenderState,
    val throttledToVsync: Boolean,
    val renderVersion: Long,
    val exceptionClass: String,
    val message: String?,
    val causeClass: String?,
    val causeMessage: String?,
)
