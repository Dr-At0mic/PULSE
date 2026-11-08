// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.widget

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import io.pulse.hud.core.Hud
import io.pulse.hud.data.Ring

/**
 * Builds a scrolling line + area path from a [Ring], reusing the same Path objects.
 *
 * Smooth scrolling between 1 Hz samples: the newest sample starts one step to the RIGHT
 * of the visible area and slides in as [frac] goes 0 -> 1. The leading-edge dot sits on the
 * right edge at the value interpolated between the last two samples, so the graph glides
 * continuously instead of jumping once per second.
 */
class Trace {
    val line = Path()
    val area = Path()
    var edgeY = Float.NaN
        private set
    var edgeValue = Float.NaN
        private set
    private val areaPaint = Hud.fill()
    private var shaderKey = 0L
    val glow = Glow(Hud.dp(1.6f))

    fun build(r: Ring, points: Int, frac: Float, rect: RectF, max: Float) {
        line.rewind(); area.rewind()
        edgeY = Float.NaN; edgeValue = Float.NaN
        val count = minOf(points + 1, r.size)          // +1: the oldest point slides out on the left
        if (count < 2 || max <= 0f) return
        val step = rect.width() / (points - 1)
        val shift = (1f - frac) * step
        val h = rect.height()
        var open = false
        var lastX = 0f
        val base = r.size - count
        for (k in 0 until count) {
            val v = r[base + k]
            val x = rect.right - (count - 1 - k) * step + shift
            if (v.isNaN()) {
                if (open) { area.lineTo(lastX, rect.bottom); area.close(); open = false }
                continue
            }
            val y = rect.bottom - (v / max).coerceIn(0f, 1f) * h
            if (!open) {
                line.moveTo(x, y); area.moveTo(x, rect.bottom); area.lineTo(x, y); open = true
            } else { line.lineTo(x, y); area.lineTo(x, y) }
            lastX = x
        }
        if (open) { area.lineTo(lastX, rect.bottom); area.close() }
        val a = r[r.size - 2]; val b = r[r.size - 1]
        if (!a.isNaN() && !b.isNaN()) {
            edgeValue = a + (b - a) * frac
            edgeY = rect.bottom - (edgeValue / max).coerceIn(0f, 1f) * h
        }
    }

    /** Gradient fill under the line. Shader rebuilt only when colour or geometry changes. */
    fun drawArea(c: Canvas, rect: RectF, color: Int, strength: Float = 0.30f) {
        val key = (color.toLong() shl 32) xor (rect.top.toLong() shl 16) xor rect.bottom.toLong()
        if (key != shaderKey) {
            shaderKey = key
            areaPaint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                Hud.alpha(color, strength), Hud.alpha(color, 0f), Shader.TileMode.CLAMP)
        }
        c.drawPath(area, areaPaint)
    }

    fun drawLine(c: Canvas, color: Int, intensity: Float = 1f) {
        glow.color(color, intensity)
        glow.path(c, line, withFilament = false)
    }

    fun drawEdge(c: Canvas, rect: RectF, color: Int, radius: Float, intensity: Float = 1f) {
        if (edgeY.isNaN()) return
        glow.dot(c, rect.right, edgeY, radius, color, intensity)
    }

    fun setAreaAlpha(a: Float) { areaPaint.alpha = (255 * a).toInt() }
    fun paint(): Paint = areaPaint
}
