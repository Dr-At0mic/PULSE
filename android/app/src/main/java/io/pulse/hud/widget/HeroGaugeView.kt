// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.pulse.hud.core.Fmt
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Level
import io.pulse.hud.core.Smooth
import io.pulse.hud.data.DeviceState
import io.pulse.hud.data.Prefs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The big CPU ring on the detail screen.
 *  - 60 tick marks that light up to the current value
 *  - a glowing sweep with a brighter "comet head" and a leading dot
 *  - an odometer readout: digits ROLL between values instead of jumping
 *  - red ripples while alerting
 * The rotating reticle around it is a separate SpinnerRingView (RenderNode rotation).
 */
class HeroGaugeView(ctx: Context, private val prefs: Prefs) : HudView(ctx) {
    private var device: DeviceState? = null
    private val value = Smooth(0f, 0.6f)
    private val oval = RectF()
    private val inner = RectF()
    private val track = Hud.stroke(Hud.dp(12f), Hud.TRACK, round = false)
    private val sweep = Glow(Hud.dp(12f))
    private val head = Glow(Hud.dp(12f))
    private val tickLit = Hud.stroke(Hud.dp(1.5f), round = false)
    private val tickDim = Hud.stroke(Hud.dp(1.5f), Hud.DIM, round = false)
    private val ring = Hud.stroke(Hud.dp(1f), Hud.DIM, round = false)
    private val fx = Hud.stroke(Hud.dp(2f))
    private var minorTicks = FloatArray(0)
    private var majorTicks = FloatArray(0)
    private val label = Hud.label(10f).apply { textAlign = Paint.Align.CENTER }
    private val digits = Hud.number(64f)
    private val unit = Hud.number(20f, bold = false, color = Hud.LABEL)
    private val sub = Hud.label(9.5f).apply { textAlign = Paint.Align.CENTER }
    private val buf = CharArray(48)
    private var alertT = 0f
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    val gaugeOval: RectF get() = oval

    fun bind(d: DeviceState) {
        device = d
        value.snap(0f)
        value.set(d.cpu)                    // entrance: sweep up from zero
        setAccent(d.accent)
        invalidate()
    }

    fun onSample() { device?.let { value.set(it.cpu) } }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = minOf(w, h).toFloat()
        cx = w / 2f; cy = h / 2f
        r = s / 2f - s * 0.11f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val sw = s * 0.045f
        track.strokeWidth = sw; sweep.width(sw); head.width(sw * 1.05f)
        val ri = r * 0.80f
        inner.set(cx - ri, cy - ri, cx + ri, cy + ri)
        // 61 ticks across the 270 degree span, every 5th is major
        val n = 61
        val mi = ArrayList<Float>(); val ma = ArrayList<Float>()
        val r0 = r + sw * 0.9f
        for (i in 0 until n) {
            val a = Math.toRadians((135.0 + 270.0 * i / (n - 1)))
            val major = i % 5 == 0
            val r1 = r0 + if (major) s * 0.04f else s * 0.022f
            val list = if (major) ma else mi
            list.add(cx + (cos(a) * r0).toFloat()); list.add(cy + (sin(a) * r0).toFloat())
            list.add(cx + (cos(a) * r1).toFloat()); list.add(cy + (sin(a) * r1).toFloat())
        }
        minorTicks = mi.toFloatArray(); majorTicks = ma.toFloatArray()
        tickLit.strokeWidth = maxOf(Hud.dp(1.2f), s * 0.006f)
        tickDim.strokeWidth = tickLit.strokeWidth
        digits.textSize = s * 0.19f
        unit.textSize = s * 0.06f
        label.textSize = (s * 0.038f).coerceIn(Hud.sp(9f), Hud.sp(13f))
        sub.textSize = (s * 0.034f).coerceIn(Hud.sp(8.5f), Hud.sp(12f))
    }

    override fun step(nowNanos: Long, dt: Float): Int {
        val d = device ?: return Level.IDLE
        var changed = value.step(dt)
        if (d.alerting) { alertT += dt; changed = true }
        if (changed) invalidate()
        return if (changed) Level.ACTIVE else Level.IDLE
    }

    override fun onDraw(c: Canvas) {
        val d = device ?: return
        val v = value.value.coerceIn(0f, 100f)
        val color = Hud.level(accent, v, prefs.cpuAlert - 15f, prefs.cpuAlert)
        val frac = v / 100f

        // ticks: lit up to the value (drawLines over a sub-range: no allocation)
        tickLit.color = Hud.alpha(color, 0.9f)
        val litMinor = countBelow(minorTicks.size / 4, frac, minor = true)
        val litMajor = countBelow(majorTicks.size / 4, frac, minor = false)
        if (litMinor > 0) c.drawLines(minorTicks, 0, litMinor * 4, tickLit)
        if (litMinor * 4 < minorTicks.size) c.drawLines(minorTicks, litMinor * 4, minorTicks.size - litMinor * 4, tickDim)
        if (litMajor > 0) c.drawLines(majorTicks, 0, litMajor * 4, tickLit)
        if (litMajor * 4 < majorTicks.size) c.drawLines(majorTicks, litMajor * 4, majorTicks.size - litMajor * 4, tickDim)

        // track + sweep + comet head
        c.drawArc(oval, 135f, 270f, false, track)
        sweep.color(color, 0.75f)
        sweep.arc(c, oval, 135f, 270f * frac)
        val headSpan = minOf(270f * frac, 28f)
        head.color(color, 1f)
        head.arc(c, oval, 135f + 270f * frac - headSpan, headSpan)
        val a = Math.toRadians((135f + 270f * frac).toDouble())
        if (frac > 0.003f) head.dot(c, cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), track.strokeWidth * 0.45f, color)

        // inner ring with cardinal marks
        ring.color = Hud.alpha(accent, 0.25f)
        c.drawArc(inner, 0f, 360f, false, ring)

        // readout
        label.color = Hud.LABEL
        c.drawText("CPU LOAD", cx, cy - digits.textSize * 0.78f, label)
        drawOdometer(c, v, color)
        var len: Int
        sub.color = Hud.LABEL
        if (d.freqMhz > 0) {
            len = Fmt.fixed2(buf, 0, d.freqMhz / 1000f)
            len = Fmt.str(buf, len, " GHZ · ")
        } else len = 0
        len = Fmt.int(buf, len, if (d.coreCount > 0) d.coreCount else d.cores)
        len = Fmt.str(buf, len, " THREADS")
        c.drawText(buf, 0, len, cx, cy + digits.textSize * 0.75f, sub)
        if (d.hasLoad) {
            len = Fmt.str(buf, 0, "LOAD ")
            for (i in 0..2) { len = Fmt.fixed2(buf, len, d.load[i]); if (i < 2) buf[len++] = ' ' }
            c.drawText(buf, 0, len, cx, cy + digits.textSize * 0.75f + sub.textSize * 1.7f, sub)
        }

        if (d.alerting) {
            fx.strokeWidth = Hud.dp(2f)
            for (k in 0..1) {
                val ph = ((alertT / 1.4f) + k * 0.5f) % 1f
                fx.color = Hud.alpha(Hud.CRIT, (1f - ph) * 0.6f)
                c.drawCircle(cx, cy, r * (1.08f + 0.28f * ph), fx)
            }
        }
    }

    private fun countBelow(n: Int, frac: Float, minor: Boolean): Int {
        // minor ticks are the 61 ticks minus every 5th; count how many sit at or below frac
        var count = 0
        var idx = 0
        for (i in 0 until 61) {
            val isMajor = i % 5 == 0
            if (isMajor == !minor) {
                if (i / 60f <= frac + 0.0001f) count = idx + 1
                idx++
                if (idx >= n) break
            }
        }
        return count
    }

    /**
     * Odometer: each digit column scrolls vertically. The tenths digit rolls continuously;
     * higher digits roll only while every lower digit is passing 9 -> 0 (a true carry),
     * so 57.8 -> 61.2 looks like a mechanical counter, never a jump.
     */
    private fun drawOdometer(c: Canvas, v: Float, color: Int) {
        val dw = digits.measureText("0")
        val dotW = dw * 0.55f
        val lh = digits.textSize * 1.05f
        val baseY = cy + digits.textSize * 0.36f
        val totalW = dw * 3 + dotW + unit.measureText("%")
        // leading digits fade in only as the value actually carries into them (9.9 -> 10.0)
        val visTens = ((v - 9.9f) / 0.1f).coerceIn(0f, 1f)
        val visHundreds = ((v - 99.9f) / 0.1f).coerceIn(0f, 1f)
        // "00.0%" is centred; with the tens digit hidden the number glides left to stay centred
        var x = cx - totalW / 2f - (1f - visTens) * dw * 0.5f
        val top = baseY - digits.textSize * 0.92f
        val bottom = baseY + digits.textSize * 0.22f
        digits.color = Hud.TEXT
        // positions: hundreds(2) tens(1) units(0) . tenths(-1)
        for (p in 2 downTo -1) {
            if (p == -1) {
                digits.color = Hud.alpha(Hud.TEXT, 0.9f)
                c.drawText(".", x - dw * 0.18f, baseY, digits)
                x += dotW
            }
            val colX = if (p == 2) x - dw * 1f else x
            val scaled = v * pow10(-p) + 0.0005f
            val whole = floor(scaled)
            val digit = (whole % 10).toInt()
            val f = scaled - whole
            // The tenths wheel turns continuously. A higher wheel turns only during the last
            // 0.1 before its carry, i.e. while every digit below it reads 9 and tenths roll 9->0.
            val window = pow10(-p - 1)            // p=0: 0.1, p=1: 0.01, p=2: 0.001 (in scaled units)
            val roll = if (p == -1) f else ((f - (1f - window)) / window).coerceIn(0f, 1f)
            val vis = when (p) { 2 -> visHundreds; 1 -> visTens; else -> 1f }
            if (vis > 0.01f) {
                c.save()
                c.clipRect(colX - 1f, top, colX + dw + 1f, bottom)
                digits.color = Hud.alpha(if (p >= 0) Hud.TEXT else Hud.lerpColor(Hud.TEXT, color, 0.4f), vis)
                c.drawText(DIGITS, digit, 1, colX, baseY - roll * lh, digits)
                c.drawText(DIGITS, (digit + 1) % 10, 1, colX, baseY - roll * lh + lh, digits)
                c.restore()
            }
            if (p != 2) x += dw
        }
        unit.color = Hud.LABEL
        c.drawText("%", x + Hud.dp(2f), baseY, unit)
    }

    private fun pow10(e: Int): Float = when (e) { -3 -> 0.001f; -2 -> 0.01f; -1 -> 0.1f; 0 -> 1f; 1 -> 10f; else -> 100f }

    companion object { private val DIGITS = "0123456789".toCharArray() }
}
