// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import io.pulse.hud.core.Fmt
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Level
import io.pulse.hud.core.Spring
import io.pulse.hud.data.DeviceState

/**
 * Per-core "equalizer": one spring-animated bar per logical core, gradient-filled,
 * with a glowing cap and a slowly falling peak-hold marker. Two rows above 32 cores.
 */
class CoreBarsView(ctx: Context) : HudView(ctx) {
    private var device: DeviceState? = null
    // stiff enough to settle in ~0.45 s, so the panel is idle for half of every sample interval
    private val springs = Array(DeviceState.MAX_CORES) { Spring(0f, 320f, 0.68f) }
    private val peaks = FloatArray(DeviceState.MAX_CORES)
    private var n = 0
    private val chrome = PanelChrome()
    private val area = RectF()
    private val trackPaint = Hud.fill(Hud.TRACK)
    private val fillPaint = Hud.fill()
    private val fillPaint2 = Hud.fill()
    private val cap = Hud.stroke(Hud.dp(2f), round = false)
    private val capGlow = Hud.stroke(Hud.dp(6f), round = false)
    private val peak = Hud.stroke(Hud.dp(1.2f), round = false)
    private val idx = Hud.label(7.5f).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0f }
    private val readout = Hud.label(9.5f).apply { textAlign = Paint.Align.RIGHT }
    private val buf = CharArray(24)
    private var rows = 1
    private var shaderAccent = 0

    fun bind(d: DeviceState) {
        device = d
        n = maxOf(d.coreCount, minOf(d.cores, DeviceState.MAX_CORES))
        for (i in 0 until DeviceState.MAX_CORES) { springs[i].value = 0f; springs[i].velocity = 0f; springs[i].target = 0f; peaks[i] = 0f }
        setAccent(d.accent)
        onSample()
        requestLayoutBars()
    }

    fun onSample() {
        val d = device ?: return
        if (d.coreCount > 0 && d.coreCount != n) { n = d.coreCount; requestLayoutBars() }
        for (i in 0 until n) springs[i].target = d.coreVals[i]
    }

    override fun setAccent(color: Int) { chrome.base = color; super.setAccent(color); shaderAccent = 0 }

    private fun requestLayoutBars() { rows = if (n > 32) 2 else 1; shaderAccent = 0; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        chrome.layout(Hud.dp(1f), Hud.dp(1f), w - Hud.dp(1f), h - Hud.dp(1f))
        area.set(Hud.dp(16f), Hud.dp(30f), w - Hud.dp(16f), h - Hud.dp(20f))
        shaderAccent = 0
    }

    private fun ensureShaders() {
        if (shaderAccent == accent) return
        shaderAccent = accent
        val rowH = area.height() / rows
        fillPaint.shader = LinearGradient(0f, area.top + rowH, 0f, area.top,
            intArrayOf(Hud.alpha(accent, 0.15f), Hud.alpha(accent, 0.55f), accent), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        fillPaint2.shader = LinearGradient(0f, area.bottom, 0f, area.bottom - rowH,
            intArrayOf(Hud.alpha(accent, 0.15f), Hud.alpha(accent, 0.55f), accent), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun step(nowNanos: Long, dt: Float): Int {
        var moving = false
        for (i in 0 until n) if (springs[i].step(dt)) moving = true
        // Peak markers rise instantly and fall only while the bars are moving, so once the
        // springs settle the whole panel is static and stops redrawing (was: every frame).
        for (i in 0 until n) {
            val v = springs[i].value
            if (v > peaks[i]) peaks[i] = v
            else if (moving && peaks[i] > v) peaks[i] = maxOf(v, peaks[i] - 18f * dt)
        }
        if (moving) invalidate()
        return if (moving) Level.ACTIVE else Level.IDLE
    }

    override fun onDraw(c: Canvas) {
        val d = device
        var len = Fmt.str(buf, 0, "CORES // ")
        len = Fmt.int(buf, len, n)
        chrome.draw(c, accent, null)
        chrome.titlePaint.color = Hud.LABEL
        c.drawText(buf, 0, len, chrome.titleX + Hud.dp(8.8f), chrome.titleY, chrome.titlePaint)
        if (d != null) {
            len = Fmt.str(buf, 0, "AVG ")
            len = Fmt.fixed1(buf, len, d.cpu); buf[len++] = '%'
            readout.color = Hud.TEXT
            c.drawText(buf, 0, len, width - Hud.dp(18f), chrome.titleY, readout)
        }
        if (n == 0) return
        ensureShaders()
        val perRow = (n + rows - 1) / rows
        val rowGap = if (rows > 1) Hud.dp(10f) else 0f
        val rowH = (area.height() - rowGap * (rows - 1)) / rows
        val slot = area.width() / perRow
        val gap = (slot * 0.28f).coerceIn(Hud.dp(1.5f), Hud.dp(6f))
        val bw = slot - gap
        val showIdx = bw >= Hud.dp(10f) && rows == 1
        for (i in 0 until n) {
            val row = i / perRow
            val col = i % perRow
            val x = area.left + col * slot + gap / 2f
            val bottom = area.top + rowH * (row + 1) + rowGap * row - (if (showIdx) Hud.dp(12f) else 0f)
            val top = area.top + (rowH + rowGap) * row
            val h = bottom - top
            c.drawRect(x, top, x + bw, bottom, trackPaint)
            val v = springs[i].value.coerceIn(0f, 100f)
            val y = bottom - h * v / 100f
            c.drawRect(x, y, x + bw, bottom, if (row == 0) fillPaint else fillPaint2)
            val capColor = Hud.level(accent, v, 75f, 92f)
            capGlow.color = Hud.alpha(capColor, 0.25f)
            c.drawLine(x, y, x + bw, y, capGlow)
            cap.color = capColor
            c.drawLine(x, y, x + bw, y, cap)
            val py = bottom - h * peaks[i].coerceIn(0f, 100f) / 100f
            if (py < y - Hud.dp(2f)) {
                peak.color = Hud.alpha(Hud.TEXT, 0.55f)
                c.drawLine(x, py, x + bw, py, peak)
            }
            if (showIdx) {
                idx.color = Hud.LABEL
                val l = Fmt.int(buf, 0, i)
                c.drawText(buf, 0, l, x + bw / 2f, bottom + Hud.dp(10f), idx)
            }
        }
    }
}
