// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import io.pulse.hud.core.Animated
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Level

/**
 * The HUD panel frame used by every widget: a chamfered "faux glass" plate.
 *
 * Real glassmorphism needs a backdrop blur, which this GPU can't afford every frame,
 * so the glass is faked: a vertical accent-tinted gradient fill, a hairline border,
 * bright corner brackets and a chamfer highlight. It reads as glass on a dark void
 * at the cost of one gradient fill and a few lines.
 */
class PanelChrome {
    private val plate = Path()
    private val fill = Hud.fill()
    private val greyFill = Hud.fill()
    private val border = Hud.stroke(Hud.dp(1f), round = false)
    private val bracket = Hud.stroke(Hud.dp(1.6f), round = false).apply { strokeCap = Paint.Cap.SQUARE }
    private val bracketGlow = Hud.stroke(Hud.dp(5f), round = false)
    private val marker = Hud.fill()
    val titlePaint = Hud.label(9.5f)
    private val brackets = FloatArray(16)
    private val rect = RectF()
    private var accent = Hud.CYAN
    private var shaderAccent = 0
    private var h = 0f
    var chamfer = Hud.dp(12f)
    var titleX = 0f
    var titleY = 0f

    fun layout(l: Float, t: Float, r: Float, b: Float) {
        rect.set(l, t, r, b)
        h = b - t
        val c = chamfer
        plate.rewind()
        plate.moveTo(l + c, t); plate.lineTo(r, t); plate.lineTo(r, b - c)
        plate.lineTo(r - c, b); plate.lineTo(l, b); plate.lineTo(l, t + c); plate.close()
        val k = Hud.dp(14f)
        // top-right and bottom-left corner brackets (the chamfered corners get a highlight line instead)
        brackets[0] = r - k; brackets[1] = t; brackets[2] = r; brackets[3] = t
        brackets[4] = r; brackets[5] = t; brackets[6] = r; brackets[7] = t + k
        brackets[8] = l; brackets[9] = b - k; brackets[10] = l; brackets[11] = b
        brackets[12] = l; brackets[13] = b; brackets[14] = l + k; brackets[15] = b
        titleX = l + c + Hud.dp(6f)
        titleY = t + Hud.dp(18f)
        shaderAccent = 0
    }

    private fun gradient(color: Int) = LinearGradient(0f, rect.top, 0f, rect.bottom,
        intArrayOf(Hud.alpha(color, 0.10f), Hud.alpha(Hud.PANEL, 0.72f), Hud.alpha(Hud.PANEL, 0.86f)),
        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)

    /** The device accent the plate gradient is built from. Shaders are rebuilt only when this
     *  or the size changes, never per frame. */
    var base = Hud.CYAN

    private fun ensureShader() {
        if (shaderAccent == base && greyFill.shader != null) return
        shaderAccent = base
        fill.shader = gradient(base)
        greyFill.shader = gradient(Hud.OFFLINE)
    }

    /**
     * @param color  line colour (accent, alert-tinted / desaturated by the caller)
     * @param energy 0..1 extra brightness on the border (alerts, focus)
     * @param grey   0..1 crossfade of the plate toward the offline grey
     */
    fun draw(c: Canvas, color: Int, title: String?, energy: Float = 0f, dim: Float = 1f, grey: Float = 0f) {
        accent = color
        ensureShader()
        if (grey < 1f) { fill.alpha = (255 * dim * (1f - grey)).toInt(); c.drawPath(plate, fill) }
        if (grey > 0f) { greyFill.alpha = (255 * dim * grey).toInt(); c.drawPath(plate, greyFill) }
        border.color = Hud.alpha(color, (0.22f + 0.6f * energy) * dim)
        c.drawPath(plate, border)
        bracketGlow.color = Hud.alpha(color, (0.10f + 0.25f * energy) * dim)
        c.drawLines(brackets, bracketGlow)
        bracket.color = Hud.alpha(color, 0.9f * dim)
        c.drawLines(brackets, bracket)
        // chamfer highlights
        bracket.color = Hud.alpha(color, 0.55f * dim)
        val ch = chamfer
        c.drawLine(rect.left, rect.top + ch, rect.left + ch, rect.top, bracket)
        c.drawLine(rect.right - ch, rect.bottom, rect.right, rect.bottom - ch, bracket)
        if (title != null) {
            marker.color = Hud.alpha(color, dim)
            val s = Hud.dp(4f)
            c.drawRect(titleX, titleY - s * 1.6f, titleX + s, titleY - s * 0.6f, marker)
            titlePaint.color = Hud.alpha(Hud.LABEL, dim)
            c.drawText(title, titleX + s * 2.2f, titleY, titlePaint)
        }
    }
}

/**
 * Neon tube without blur: a faint wide halo, a brighter mid halo, the coloured core and a
 * near-white filament. Four cheap strokes approximate a Gaussian falloff; BlurMaskFilter
 * would be far too slow on this GPU. Arcs use butt caps on the halos so arc starts don't
 * form blobs.
 */
class Glow(width: Float) {
    private val wide = Hud.stroke(width * 3.6f).apply { strokeCap = Paint.Cap.BUTT }
    private val mid = Hud.stroke(width * 1.9f).apply { strokeCap = Paint.Cap.BUTT }
    val core = Hud.stroke(width)
    val filament = Hud.stroke(width * 0.34f)
    private val dotPaint = Hud.fill()

    fun width(w: Float) {
        wide.strokeWidth = w * 3.6f; mid.strokeWidth = w * 1.9f; core.strokeWidth = w; filament.strokeWidth = w * 0.34f
    }

    fun color(c: Int, intensity: Float = 1f) {
        wide.color = Hud.alpha(c, 0.07f * intensity)
        mid.color = Hud.alpha(c, 0.16f * intensity)
        core.color = Hud.alpha(c, intensity)
        filament.color = Hud.alpha(Hud.lerpColor(c, 0xFFFFFFFF.toInt(), 0.7f), 0.9f * intensity)
    }

    fun arc(cv: Canvas, oval: RectF, start: Float, sweep: Float) {
        if (sweep <= 0.01f) return
        cv.drawArc(oval, start, sweep, false, wide)
        cv.drawArc(oval, start, sweep, false, mid)
        cv.drawArc(oval, start, sweep, false, core)
        cv.drawArc(oval, start, sweep, false, filament)
    }

    fun path(cv: Canvas, p: Path, withFilament: Boolean = true) {
        wide.strokeCap = Paint.Cap.ROUND; mid.strokeCap = Paint.Cap.ROUND
        wide.strokeJoin = Paint.Join.ROUND; mid.strokeJoin = Paint.Join.ROUND
        cv.drawPath(p, wide)
        cv.drawPath(p, mid)
        cv.drawPath(p, core)
        if (withFilament) cv.drawPath(p, filament)
        wide.strokeCap = Paint.Cap.BUTT; mid.strokeCap = Paint.Cap.BUTT
    }

    fun dot(cv: Canvas, x: Float, y: Float, r: Float, c: Int, intensity: Float = 1f) {
        dotPaint.color = Hud.alpha(c, 0.10f * intensity); cv.drawCircle(x, y, r * 2.6f, dotPaint)
        dotPaint.color = Hud.alpha(c, 0.25f * intensity); cv.drawCircle(x, y, r * 1.6f, dotPaint)
        dotPaint.color = Hud.alpha(c, intensity); cv.drawCircle(x, y, r, dotPaint)
        dotPaint.color = Hud.alpha(0xFFFFFFFF.toInt(), 0.92f * intensity); cv.drawCircle(x, y, r * 0.5f, dotPaint)
    }
}

/** Base class: a View that is stepped by the FrameDriver and knows its accent. */
abstract class HudView(ctx: Context) : View(ctx), Animated {
    var accent = Hud.CYAN
        private set
    open var lowPower = false

    /**
     * Render this panel into its own GPU texture, re-rendered only when it changes.
     *
     * Measured on the Tab A7 Lite: without panel layers the RenderThread spent ~16 ms per frame
     * re-issuing the detail screen's glow arcs and gridlines; with them, 3 % janky frames. But
     * layers are not free: Skia rounds them up to size bins and never releases a dropped layer
     * (it sits in a "purgeable" pool), so only views with genuinely expensive drawing opt in.
     */
    fun useLayer() = setLayerType(LAYER_TYPE_HARDWARE, null)

    /** Fades (screen transitions, device switches) must not allocate a full-size offscreen
     *  buffer: measured as up to 18 MB of GPU "RenderTarget" memory on the tablet. */
    override fun hasOverlappingRendering() = false

    open fun setAccent(color: Int) {
        if (accent == color) return
        accent = color
        invalidate()
    }

    override fun step(nowNanos: Long, dt: Float): Int = Level.IDLE
}
