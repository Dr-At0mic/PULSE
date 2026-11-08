// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import io.pulse.hud.core.Fmt
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Level
import io.pulse.hud.data.DeviceState

/**
 * Live graph panel (CPU %, RAM %, or network up+down).
 *
 * This view draws only the static frame, gridlines, axis label and the readout, and redraws
 * once per sample. The traces live in [plot] (a [ScrollingPlot] the parent places over the
 * plot area), which scrolls between samples by moving a cached texture, not by redrawing.
 */
class LineGraphView(ctx: Context, private val kind: Int) : HudView(ctx) {
    private var device: DeviceState? = null
    private val chrome = PanelChrome()
    val plot = ScrollingPlot(ctx, POINTS).apply {
        stagger = kind            // CPU, RAM and NET re-record on consecutive frames
    }
    private val grid = Hud.stroke(Hud.dp(1f), Hud.GRID, round = false)
    private var gridDashes = FloatArray(0)      // precomputed dashes: no DashPathEffect per frame
    private val axis = Hud.label(8f)
    private val readout = Hud.number(12f).apply { textAlign = Paint.Align.RIGHT }
    private val readout2 = Hud.number(12f).apply { textAlign = Paint.Align.RIGHT }
    private val buf = CharArray(40)
    private var scale = 100f

    private val title = when (kind) { CPU -> "CPU // 120s"; RAM -> "RAM // 120s"; else -> "NETWORK // 120s" }

    override var lowPower: Boolean
        get() = super.lowPower
        set(v) { super.lowPower = v; plot.lowPower = v }

    fun bind(d: DeviceState) {
        device = d
        setAccent(d.accent)
        when (kind) {
            CPU -> plot.setSeries(d.cpuHist, lineFor(d), d.accent)
            RAM -> plot.setSeries(d.ramHist, Hud.lerpColor(d.accent, Hud.TEXT, 0.2f), d.accent)
            else -> plot.setSeries(d.downHist, d.accent, d.accent, d.upHist, Hud.ICE, Hud.ICE)
        }
        rescale()
        plot.onHistory()
        invalidate()
    }

    private fun lineFor(d: DeviceState) = Hud.level(d.accent, d.cpu, 75f, 92f)

    override fun setAccent(color: Int) { chrome.base = color; super.setAccent(color) }

    fun onSample() {
        val d = device ?: return
        if (kind == CPU) plot.setLineColors(lineFor(d))
        rescale()
        plot.onSample(d.interval)
        invalidate()                                   // readout text only
    }

    fun onHistory() { rescale(); plot.onHistory() }

    /** Network scale snaps to a power-of-two step ("nice" axis label); CPU and RAM are 0-100. */
    private fun rescale() {
        val d = device ?: return
        if (kind != NET) { plot.max = 100f; return }
        val m = maxOf(d.downHist.maxOfLast(POINTS + 1), d.upHist.maxOfLast(POINTS + 1))
        var nice = 64f * 1024f
        while (nice < m * 1.15f) nice *= 2f
        if (nice != scale) { scale = nice; plot.max = nice; invalidate() }
    }

    /** Plot area (including the plot's glow padding) in this view's coordinates. */
    fun plotBounds(out: Rect) {
        out.set(Hud.dp(14f).toInt(), (Hud.dp(30f) - plot.pad).toInt(), width - Hud.dp(16f).toInt(), (height - Hud.dp(12f) + plot.pad).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        chrome.layout(Hud.dp(1f), Hud.dp(1f), w - Hud.dp(1f), h - Hud.dp(1f))
        val left = Hud.dp(14f); val right = w - Hud.dp(16f)
        val top = Hud.dp(30f); val bottom = h - Hud.dp(12f)
        val dash = Hud.dp(2f); val gap = Hud.dp(4f)
        val perLine = ((right - left) / (dash + gap)).toInt().coerceAtLeast(0)
        gridDashes = FloatArray(perLine * 4 * 3)
        var i = 0
        for (row in 1..3) {
            val y = top + (bottom - top) * row / 4f
            var x = left
            repeat(perLine) {
                gridDashes[i++] = x; gridDashes[i++] = y; gridDashes[i++] = x + dash; gridDashes[i++] = y
                x += dash + gap
            }
        }
    }

    override fun step(nowNanos: Long, dt: Float): Int = if (plot.step()) Level.ACTIVE else Level.IDLE

    override fun onDraw(c: Canvas) {
        val d = device ?: return
        chrome.draw(c, accent, title)
        c.drawLines(gridDashes, grid)
        axis.color = Hud.LABEL
        val len0 = if (kind == NET) Fmt.rate(buf, 0, scale) else Fmt.str(buf, 0, "100%")
        c.drawText(buf, 0, len0, Hud.dp(16f), Hud.dp(40f), axis)

        var len: Int
        when (kind) {
            CPU -> { len = Fmt.fixed1(buf, 0, d.cpu); buf[len++] = '%'; readout.color = lineFor(d) }
            RAM -> {
                len = Fmt.fixed1(buf, 0, d.ramUsed); len = Fmt.str(buf, len, " / ")
                len = Fmt.fixed1(buf, len, d.ramTotal); len = Fmt.str(buf, len, " GB")
                readout.color = Hud.TEXT
            }
            else -> {
                len = Fmt.str(buf, 0, "↓ "); len = Fmt.rate(buf, len, d.netDown)
                readout.color = accent
            }
        }
        val ry = chrome.titleY
        c.drawText(buf, 0, len, width - Hud.dp(18f), ry, readout)
        if (kind == NET) {
            val w = readout.measureText(buf, 0, len)
            len = Fmt.str(buf, 0, "↑ "); len = Fmt.rate(buf, len, d.netUp)
            readout2.color = Hud.ICE
            c.drawText(buf, 0, len, width - Hud.dp(18f) - w - Hud.dp(14f), ry, readout2)
        }
    }

    companion object {
        const val CPU = 0
        const val RAM = 1
        const val NET = 2
        const val POINTS = 120
    }
}
