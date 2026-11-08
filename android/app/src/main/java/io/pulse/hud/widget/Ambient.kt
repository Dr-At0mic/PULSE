// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import io.pulse.hud.core.Animated
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Level
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Ambient motion that costs (almost) nothing.
 *
 * Each of these views draws ONCE into its RenderNode. Motion is applied with
 * setTranslationX/Y, setRotation, setAlpha, setScale: RenderNode properties that the
 * RenderThread applies when compositing. onDraw() is never called again, no display
 * list is re-recorded, and the UI thread only sets a float per frame.
 */

/**
 * Faint blueprint grid + scanline texture + vignette, drifting slowly.
 *
 * The whole grid is ONE repeating tile (4 x 4 cells, ~118 KB) used as a BitmapShader.
 * A full-screen hardware layer cost 5 MB of GPU memory on the tablet; the tile costs 118 KB.
 * Drift moves the shader's matrix (two drawRect calls re-recorded per frame, at 30 fps);
 * the vignette stays fixed.
 */
class GridBackgroundView(ctx: Context) : View(ctx), Animated {
    private val cellPx = Math.round(Hud.dp(32f))
    private val tilePx = cellPx * 4
    private val tilePaint = Paint()
    private val vignette = Hud.fill()
    private val matrix = android.graphics.Matrix()
    private var drift = 0f
    var lowPower = false

    init {
        val bmp = android.graphics.Bitmap.createBitmap(tilePx, tilePx, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val minor = Paint().apply { color = Hud.GRID; strokeWidth = 1f }
        val major = Paint().apply { color = Hud.alpha(0xFF1B2A44.toInt(), 0.9f); strokeWidth = 1f }
        val cross = Paint().apply { color = 0xFF26395C.toInt(); strokeWidth = 1f }
        val scan = Paint().apply { color = Hud.alpha(0xFFFFFFFF.toInt(), 0.018f); strokeWidth = 1f }
        // scanline pitch: the integer divisor of the tile closest to 3 dp, so the tile repeats seamlessly
        var gap = Math.round(Hud.dp(3f)).coerceAtLeast(2)
        while (tilePx % gap != 0) gap++
        var y = 0
        while (y < tilePx) { c.drawLine(0f, y + 0.5f, tilePx.toFloat(), y + 0.5f, scan); y += gap }
        for (i in 0 until 4) {
            val p = i * cellPx + 0.5f
            val paint = if (i == 0) major else minor
            c.drawLine(p, 0f, p, tilePx.toFloat(), paint)
            c.drawLine(0f, p, tilePx.toFloat(), p, paint)
        }
        val k = Math.round(Hud.dp(4f)).toFloat()
        // the cross at the tile origin, split across the four corners so it tiles correctly
        c.drawLine(0f, 0.5f, k, 0.5f, cross); c.drawLine(tilePx - k, 0.5f, tilePx.toFloat(), 0.5f, cross)
        c.drawLine(0.5f, 0f, 0.5f, k, cross); c.drawLine(0.5f, tilePx - k, 0.5f, tilePx.toFloat(), cross)
        tilePaint.shader = android.graphics.BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vignette.shader = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.62f,
            intArrayOf(0x00000000, 0x00000000, 0xB0000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, tilePaint)
        c.drawRect(0f, 0f, w, h, vignette)
    }

    override fun step(nowNanos: Long, dt: Float): Int {
        if (lowPower || width == 0) return Level.IDLE
        drift += dt * Hud.dp(5f)
        // wrap per tile (not per cell) so the major lines never jump
        matrix.setTranslate(-(drift % tilePx), -((drift * 0.6f) % tilePx))
        tilePaint.shader.setLocalMatrix(matrix)
        invalidate()
        return Level.AMBIENT
    }
}

/** A soft horizontal scan beam that sweeps down the screen every few seconds. */
class ScanBeamView(ctx: Context) : View(ctx), Animated {
    private val band = Hud.fill()
    private val line = Hud.stroke(Hud.dp(1f), round = false)
    private var y = 0f
    private var color = Hud.CYAN
    var lowPower = false
        set(v) { field = v; visibility = if (v) INVISIBLE else VISIBLE }

    fun setAccent(c: Int) {
        if (c == color) return
        color = c
        rebuild(); invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), Hud.dp(160f).toInt())
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = rebuild()

    private fun rebuild() {
        if (height == 0) return
        band.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(0, Hud.alpha(color, 0.035f), Hud.alpha(color, 0.07f), 0),
            floatArrayOf(0f, 0.7f, 0.92f, 1f), Shader.TileMode.CLAMP)
        line.color = Hud.alpha(color, 0.12f)
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), band)
        val ly = height * 0.92f
        c.drawLine(0f, ly, width.toFloat(), ly, line)
    }

    override fun step(nowNanos: Long, dt: Float): Int {
        if (lowPower) return Level.IDLE
        val parentH = (parent as? View)?.height ?: return Level.IDLE
        y += dt * parentH / 7f
        if (y > parentH + height) y = -height * 1f - parentH * 0.6f   // pause off-screen between sweeps
        translationY = y
        return Level.AMBIENT
    }
}

/** HUD reticle ring (dashes + bracket arcs) around a gauge. Rotated, never redrawn. */
class SpinnerRingView(ctx: Context) : View(ctx) {
    private val dash = Hud.stroke(Hud.dp(1.4f), round = false)
    private val arcPaint = Hud.stroke(Hud.dp(2f), round = false)
    private val arcHalo = Hud.stroke(Hud.dp(6f), round = false)
    private val oval = RectF()
    private var ticks = FloatArray(0)
    private var color = Hud.CYAN
    var dashes = 72


    fun setAccent(c: Int) { if (c != color) { color = c; invalidate() } }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f - Hud.dp(4f)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val inner = r - Hud.dp(5f)
        ticks = FloatArray(dashes * 4)
        for (i in 0 until dashes) {
            val a = (i * 2 * PI / dashes).toFloat()
            val r0 = if (i % 6 == 0) inner - Hud.dp(3f) else inner
            ticks[i * 4] = cx + cos(a) * r0; ticks[i * 4 + 1] = cy + sin(a) * r0
            ticks[i * 4 + 2] = cx + cos(a) * (r - Hud.dp(1f)); ticks[i * 4 + 3] = cy + sin(a) * (r - Hud.dp(1f))
        }
    }

    override fun onDraw(c: Canvas) {
        dash.color = Hud.alpha(color, 0.28f)
        c.drawLines(ticks, dash)
        arcHalo.color = Hud.alpha(color, 0.12f)
        arcPaint.color = Hud.alpha(color, 0.75f)
        var start = -30f
        repeat(2) {
            c.drawArc(oval, start, 42f, false, arcHalo)
            c.drawArc(oval, start, 42f, false, arcPaint)
            start += 180f
        }
        arcPaint.color = Hud.alpha(color, 0.4f)
        c.drawArc(oval, 70f, 12f, false, arcPaint)
        c.drawArc(oval, 250f, 12f, false, arcPaint)
    }
}

/** Online/offline indicator. Breathes via alpha/scale properties. */
class PulseDotView(ctx: Context) : View(ctx) {
    private val glow = Glow(1f)
    private var color = Hud.CYAN

    fun setColor(c: Int) { if (c != color) { color = c; invalidate() } }

    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 7f
        glow.dot(c, width / 2f, height / 2f, r, color)
    }

    /** t in seconds; returns nothing, just sets properties. */
    fun breathe(t: Float, fast: Boolean) {
        val s = sin(t * (if (fast) 9f else 2.6f))
        alpha = 0.65f + 0.35f * s
        val k = 0.9f + 0.12f * s
        scaleX = k; scaleY = k
    }

    fun rest() { alpha = 1f; scaleX = 1f; scaleY = 1f }

    // breathing alpha must not allocate an offscreen buffer every frame
    override fun hasOverlappingRendering() = false
}

internal fun Paint.withAlpha(c: Int, a: Float): Paint { color = Hud.alpha(c, a); return this }
