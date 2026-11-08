// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.pulse.hud.core.Hud
import io.pulse.hud.data.Ring

/**
 * A scrolling line graph that costs almost nothing per frame.
 *
 * Measured on the Galaxy Tab A7 Lite (Helio P22T): rebuilding and re-rasterising glowing
 * 121-point paths on every frame took the detail screen down to ~40 fps. Here the traces are
 * drawn ONCE per sample into a hardware layer (a GPU texture) laid out at the fully-scrolled
 * position; between samples the layer is slid with translationX and the leading-edge dot with
 * translationY. Both are RenderNode properties: no onDraw, no path work, one float each.
 *
 *   ScrollingPlot (no clip: the edge dot may overhang the right edge)
 *     ├─ clip  (clips to the plot width)
 *     │    └─ layer (hardware layer, one step wider than the plot, slides right by up to one step)
 *     └─ dotA, dotB (leading-edge dots)
 */
class ScrollingPlot(ctx: Context, private val points: Int) : ViewGroup(ctx) {
    private val clip = Clip(ctx)
    private val layer = Layer(ctx)
    private val dotA = Dot(ctx)
    private val dotB = Dot(ctx)
    private val traceA = Trace()
    private val traceB = Trace()

    private var ringA: Ring? = null
    private var ringB: Ring? = null
    private var colorA = Hud.CYAN
    private var colorB = Hud.ICE
    private var areaA = Hud.CYAN
    private var areaB = Hud.ICE
    private var strengthA = 0.30f
    private var strengthB = 0.18f
    var max = 100f

    /** Glow headroom above/below the plot area, inside this view. */
    val pad = Hud.dp(5f)
    private val dotSize = Hud.dp(22f).toInt()
    private val rect = RectF()            // plot area in layer coordinates
    private var step = 0f
    private var plotW = 0f
    private var plotTop = 0f
    private var plotH = 0f

    // values captured when the layer was last recorded (for the edge dot)
    private var a0 = Float.NaN; private var a1 = Float.NaN
    private var b0 = Float.NaN; private var b1 = Float.NaN

    /** Continuous mode scrolls across the measured sample interval; eased mode glides for
     *  [easeSeconds] and then stops (overview cards, low-power mode). */
    var continuous = true
    var lowPower = false
    var easeSeconds = 0.5f
    private var intervalMs = 1000f
    private var recordedAt = 0L
    private var pendingFrames = -1
    /** Frames to wait before re-recording after a sample, to spread several plots over frames. */
    var stagger = 0
    private var frac = 1f

    init {
        clipChildren = false
        clip.addView(layer)
        addView(clip)
        addView(dotA)
        addView(dotB)
        dotB.visibility = GONE
        layer.setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setSeries(a: Ring?, lineA: Int, fillA: Int, b: Ring? = null, lineB: Int = Hud.ICE, fillB: Int = Hud.ICE,
                  fillStrengthA: Float = 0.30f, fillStrengthB: Float = 0.18f) {
        ringA = a; ringB = b
        colorA = lineA; colorB = lineB; areaA = fillA; areaB = fillB
        strengthA = fillStrengthA; strengthB = fillStrengthB
        dotA.setColor(lineA); dotB.setColor(lineB)
        dotB.visibility = if (b != null) VISIBLE else GONE
    }

    fun setLineColors(lineA: Int, lineB: Int = colorB) {
        if (lineA == colorA && lineB == colorB) return
        colorA = lineA; colorB = lineB
        dotA.setColor(lineA); dotB.setColor(lineB)
        layer.invalidate()
    }

    /** New data: re-record (after [stagger] frames) and restart the scroll. */
    fun onSample(sampleIntervalMs: Float) {
        intervalMs = sampleIntervalMs.coerceIn(300f, 5000f)
        pendingFrames = stagger
    }

    /** History replaced: show it fully scrolled, no animation. */
    fun onHistory() {
        record()
        recordedAt = SystemClock.uptimeMillis() - 10_000
        frac = 1f
        applyFrac()
    }

    private fun record() {
        val ra = ringA
        a0 = if (ra != null && ra.size >= 2) ra[ra.size - 2] else Float.NaN
        a1 = ra?.last() ?: Float.NaN
        val rb = ringB
        b0 = if (rb != null && rb.size >= 2) rb[rb.size - 2] else Float.NaN
        b1 = rb?.last() ?: Float.NaN
        recordedAt = SystemClock.uptimeMillis()
        layer.invalidate()
    }

    /** Called every frame by the owner. Returns true while scrolling. */
    fun step(): Boolean {
        if (pendingFrames >= 0) {
            if (pendingFrames == 0) { record(); frac = 0f; applyFrac() }
            pendingFrames--
            return true
        }
        val elapsed = (SystemClock.uptimeMillis() - recordedAt).toFloat()
        val f = if (continuous && !lowPower) (elapsed / intervalMs).coerceIn(0f, 1f)
        else {
            val t = (elapsed / 1000f / easeSeconds).coerceIn(0f, 1f)
            val u = 1f - t; 1f - u * u * u
        }
        if (f == frac) return false
        frac = f
        applyFrac()
        return true
    }

    private fun applyFrac() {
        layer.translationX = (1f - frac) * step
        placeDot(dotA, a0, a1)
        if (ringB != null) placeDot(dotB, b0, b1)
    }

    private fun placeDot(d: Dot, v0: Float, v1: Float) {
        if (v0.isNaN() || v1.isNaN() || max <= 0f) { d.visibility = INVISIBLE; return }
        val v = v0 + (v1 - v0) * frac
        val y = plotTop + plotH - (v / max).coerceIn(0f, 1f) * plotH
        d.visibility = VISIBLE
        d.translationY = y - dotSize / 2f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        plotW = w.toFloat()
        plotTop = pad
        plotH = h - 2 * pad
        step = plotW / (points - 1)
        val s = Math.ceil(step.toDouble()).toInt()
        clip.layout(0, 0, w, h)
        // the layer starts one step left of the plot so the oldest point can slide out
        layer.measure(MeasureSpec.makeMeasureSpec(w + s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        layer.layout(-s, 0, w, h)
        rect.set(s.toFloat(), pad, (w + s).toFloat(), h - pad)
        for (d in arrayOf(dotA, dotB)) {
            d.measure(MeasureSpec.makeMeasureSpec(dotSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dotSize, MeasureSpec.EXACTLY))
            d.layout(w - dotSize / 2, 0, w + dotSize / 2, dotSize)
        }
        layer.invalidate()
        applyFrac()
    }

    override fun hasOverlappingRendering() = false

    private class Clip(ctx: Context) : ViewGroup(ctx) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}
        override fun hasOverlappingRendering() = false
    }

    /** Drawn only when a sample arrives (or colours/size change), never per frame. */
    private inner class Layer(ctx: Context) : View(ctx) {
        override fun onDraw(c: Canvas) {
            val a = ringA ?: return
            traceA.build(a, points, 1f, rect, max)
            val b = ringB
            if (b != null) {
                traceB.build(b, points, 1f, rect, max)
                traceB.drawArea(c, rect, areaB, strengthB)
                traceB.drawLine(c, colorB, 0.9f)
            }
            traceA.drawArea(c, rect, areaA, strengthA)
            traceA.drawLine(c, colorA)
        }
    }

    private class Dot(ctx: Context) : View(ctx) {
        private val glow = Glow(1f)
        private var color = Hud.CYAN
        fun setColor(c: Int) { if (c != color) { color = c; invalidate() } }
        override fun onDraw(c: Canvas) = glow.dot(c, width / 2f, height / 2f, Hud.dp(2.8f), color)
        override fun hasOverlappingRendering() = false
    }
}
