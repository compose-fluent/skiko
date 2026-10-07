package org.jetbrains.skiko.winui

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Reads an environment variable; `null` when it is not set. */
internal expect fun winuiEnvironmentVariable(name: String): String?

/**
 * Per-second frame statistics of a [WinUIDirect3DRenderer], printed to stdout when the
 * `SKIKO_WINUI_FRAME_STATS` environment variable is `1` or `true`.
 *
 * The phases of a frame: `wait` waits for the GPU to release the back buffer, `draw` renders the
 * content into it (the content callback), `submit` flushes and submits the GPU work, `present`
 * presents the swap chain.
 */
internal class WinUIFrameStats(private val label: String) {
    enum class Phase { Wait, Draw, Submit, Present }

    private val phaseCount = Phase.entries.size
    private val totals = LongArray(phaseCount)
    private val maxima = LongArray(phaseCount)
    private var frames = 0
    private var frameTotal = 0L
    private var frameMax = 0L
    private var intervalTotal = 0L
    private var intervalMax = 0L
    private var intervals = 0
    private var windowStart: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()
    private var lastFrameStart: TimeSource.Monotonic.ValueTimeMark? = null
    private var frameStart: TimeSource.Monotonic.ValueTimeMark? = null
    private var phaseStart: TimeSource.Monotonic.ValueTimeMark? = null

    fun beginFrame() {
        val now = TimeSource.Monotonic.markNow()
        lastFrameStart?.let { previous ->
            val interval = (now - previous).inWholeMicroseconds
            intervalTotal += interval
            intervals += 1
            if (interval > intervalMax) intervalMax = interval
        }
        lastFrameStart = now
        frameStart = now
        phaseStart = now
    }

    /** Ends the current phase as [phase] and starts the next one. */
    fun mark(phase: Phase) {
        val start = phaseStart ?: return
        val now = TimeSource.Monotonic.markNow()
        val elapsed = (now - start).inWholeMicroseconds
        totals[phase.ordinal] += elapsed
        if (elapsed > maxima[phase.ordinal]) maxima[phase.ordinal] = elapsed
        phaseStart = now
    }

    fun endFrame() {
        val start = frameStart ?: return
        val elapsed = start.elapsedNow().inWholeMicroseconds
        frames += 1
        frameTotal += elapsed
        if (elapsed > frameMax) frameMax = elapsed
        frameStart = null
        phaseStart = null
        val window = windowStart.elapsedNow()
        if (window >= ReportInterval) {
            report(window)
        }
    }

    private fun report(window: Duration) {
        fun ms(micros: Long) = (micros / 100) / 10.0
        fun avg(total: Long, count: Int) = if (count == 0) 0.0 else ms(total / count)
        val fps = frames / window.toDouble(kotlin.time.DurationUnit.SECONDS)
        val phases = Phase.entries.joinToString(" ") { phase ->
            "${phase.name.lowercase()}=${avg(totals[phase.ordinal], frames)}/${ms(maxima[phase.ordinal])}"
        }
        println(
            "skiko-winui frame stats [$label]: fps=${(fps * 10).toInt() / 10.0} " +
                "frame=${avg(frameTotal, frames)}/${ms(frameMax)} " +
                "interval=${avg(intervalTotal, intervals)}/${ms(intervalMax)} $phases (ms avg/max)",
        )
        totals.fill(0)
        maxima.fill(0)
        frames = 0
        frameTotal = 0
        frameMax = 0
        intervalTotal = 0
        intervalMax = 0
        intervals = 0
        windowStart = TimeSource.Monotonic.markNow()
    }

    companion object {
        private val ReportInterval = 1.seconds

        val isEnabled: Boolean by lazy {
            winuiEnvironmentVariable("SKIKO_WINUI_FRAME_STATS")?.lowercase() in setOf("1", "true")
        }
    }
}
