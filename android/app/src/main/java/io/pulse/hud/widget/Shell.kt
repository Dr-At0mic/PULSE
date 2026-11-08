// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import io.pulse.hud.core.Fmt
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Level
import io.pulse.hud.data.DeviceState
import io.pulse.hud.data.Fleet
import io.pulse.hud.data.Link
import java.util.Calendar
import kotlin.math.sin

/** Header bar. Overview: logo, node count, link state, clock, settings.
 *  Detail: back, device chips, clock, settings. Redraws once per second for the clock. */
@SuppressLint("ViewConstructor")
class TopBarView(ctx: Context, private val fleet: Fleet) : HudView(ctx) {
    interface Listener {
        fun onBack()
        fun onSettings()
        fun onChip(d: DeviceState)
    }

    var listener: Listener? = null
    var detail = false
        set(v) { field = v; invalidate() }
    var selected: DeviceState? = null
        set(v) { field = v; invalidate() }

    private val title = Hud.number(17f).apply { letterSpacing = 0.22f }
    private val tag = Hud.label(9f)
    private val info = Hud.label(9.5f)
    private val clock = Hud.number(14f, bold = false).apply { textAlign = Paint.Align.RIGHT }
    private val chipText = Hud.label(9.5f).apply { letterSpacing = 0.1f }
    private val line = Hud.stroke(Hud.dp(1f), round = false)
    private val icon = Hud.stroke(Hud.dp(1.6f))
    private val iconGlow = Hud.stroke(Hud.dp(5f))
    private val chipFill = Hud.fill()
    private val chipBorder = Hud.stroke(Hud.dp(1f), round = false)
    private val dotPaint = Hud.fill()
    private val logo = Path()
    private val pulse = Path()
    private val back = Path()
    private val chip = Path()
    private val gearTeeth = FloatArray(32)
    private val backHit = RectF()
    private val gearHit = RectF()
    private val chipHits = Array(16) { RectF() }
    private val chipDevices = arrayOfNulls<DeviceState>(16)
    private var chipCount = 0
    private val buf = CharArray(32)
    private val cal = Calendar.getInstance()
    private var blink = false
    private val tick = object : Runnable {
        override fun run() {
            blink = !blink
            invalidate()
            postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cy = h / 2f
        val s = Hud.dp(11f)
        val lx = Hud.dp(26f)
        logo.rewind()
        for (i in 0..5) {
            val a = Math.toRadians(-90.0 + 60.0 * i)
            val x = lx + (Math.cos(a) * s).toFloat(); val y = cy + (Math.sin(a) * s).toFloat()
            if (i == 0) logo.moveTo(x, y) else logo.lineTo(x, y)
        }
        logo.close()
        pulse.rewind()
        pulse.moveTo(lx - s * 0.75f, cy + s * 0.08f); pulse.lineTo(lx - s * 0.3f, cy + s * 0.08f)
        pulse.lineTo(lx - s * 0.12f, cy - s * 0.35f); pulse.lineTo(lx + s * 0.1f, cy + s * 0.45f)
        pulse.lineTo(lx + s * 0.3f, cy - s * 0.55f); pulse.lineTo(lx + s * 0.45f, cy + s * 0.08f)
        pulse.lineTo(lx + s * 0.75f, cy + s * 0.08f)
        back.rewind()
        back.moveTo(Hud.dp(26f), cy - Hud.dp(7f)); back.lineTo(Hud.dp(19f), cy); back.lineTo(Hud.dp(26f), cy + Hud.dp(7f))
        backHit.set(0f, 0f, Hud.dp(92f), h.toFloat())
        val gx = w - Hud.dp(26f)
        gearHit.set(w - Hud.dp(52f), 0f, w.toFloat(), h.toFloat())
        for (i in 0..7) {
            val a = Math.toRadians(22.5 + 45.0 * i)
            val r0 = Hud.dp(6.5f); val r1 = Hud.dp(9.5f)
            gearTeeth[i * 4] = gx + (Math.cos(a) * r0).toFloat(); gearTeeth[i * 4 + 1] = cy + (Math.sin(a) * r0).toFloat()
            gearTeeth[i * 4 + 2] = gx + (Math.cos(a) * r1).toFloat(); gearTeeth[i * 4 + 3] = cy + (Math.sin(a) * r1).toFloat()
        }
        line.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Hud.alpha(Hud.CYAN, 0.6f), Hud.alpha(Hud.CYAN, 0.12f), Hud.alpha(Hud.CYAN, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
        c.drawLine(0f, h - 1f, w, h - 1f, line)
        val acc = selected?.accent ?: Hud.CYAN

        // right side: gear + clock (+ link state in overview)
        iconGlow.color = Hud.alpha(Hud.LABEL, 0.15f); icon.color = Hud.LABEL
        val gx = w - Hud.dp(26f)
        c.drawCircle(gx, cy, Hud.dp(5f), icon)
        c.drawLines(gearTeeth, icon)
        cal.timeInMillis = System.currentTimeMillis()
        var len = Fmt.clock(buf, 0, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND))
        clock.color = Hud.TEXT
        val clockRight = w - Hud.dp(56f)
        c.drawText(buf, 0, len, clockRight, cy + clock.textSize * 0.36f, clock)
        val clockLeft = clockRight - clock.measureText(buf, 0, len)

        if (!detail) {
            iconGlow.color = Hud.alpha(Hud.CYAN, 0.18f)
            c.drawPath(logo, iconGlow)
            icon.color = Hud.CYAN
            c.drawPath(logo, icon)
            icon.color = Hud.TEXT
            c.drawPath(pulse, icon)
            title.color = Hud.TEXT
            c.drawText("PULSE", Hud.dp(46f), cy + title.textSize * 0.36f, title)
            tag.color = Hud.LABEL
            val tx = Hud.dp(46f) + title.measureText("PULSE") + Hud.dp(10f)
            c.drawText("// FLEET CONTROL", tx, cy + tag.textSize * 0.36f, tag)
            // node count, centred
            len = Fmt.str(buf, 0, "NODES ")
            len = Fmt.int(buf, len, fleet.onlineCount); buf[len++] = '/'
            len = Fmt.int(buf, len, fleet.devices.size); len = Fmt.str(buf, len, " ONLINE")
            info.color = if (fleet.onlineCount < fleet.devices.size) Hud.WARN else Hud.LABEL
            val nw = info.measureText(buf, 0, len)
            if (w > Hud.dp(640f)) c.drawText(buf, 0, len, w / 2f - nw / 2f, cy + info.textSize * 0.36f, info)
            // link state, left of the clock
            val (txt, col) = linkLabel()
            info.color = col
            val lw = info.measureText(txt)
            val lx = clockLeft - Hud.dp(18f) - lw
            c.drawText(txt, lx, cy + info.textSize * 0.36f, info)
            dotPaint.color = col
            c.drawCircle(lx - Hud.dp(9f), cy, Hud.dp(3f), dotPaint)
        } else {
            icon.color = acc
            c.drawPath(back, icon)
            tag.color = Hud.LABEL
            c.drawText("FLEET", Hud.dp(34f), cy + tag.textSize * 0.36f, tag)
            drawChips(c, Hud.dp(100f), clockLeft - Hud.dp(16f), cy)
        }
    }

    private fun linkLabel(): Pair<String, Int> = when (fleet.link) {
        Link.UP -> LINK_UP
        Link.CONNECTING -> LINK_CONNECTING
        Link.AUTH_FAILED -> LINK_AUTH
        Link.OFF -> LINK_OFF
        else -> LINK_DOWN
    }

    private fun drawChips(c: Canvas, x0: Float, xMax: Float, cy: Float) {
        var x = x0
        chipCount = 0
        val ch = Hud.dp(28f)
        val k = Hud.dp(6f)
        for (di in 0 until fleet.devices.size) {           // index loop: no iterator in onDraw
            val d = fleet.devices[di]
            if (chipCount >= chipHits.size) break
            val tw = chipText.measureText(d.name)
            val cw = tw + Hud.dp(36f)
            if (x + cw > xMax) break
            val top = cy - ch / 2f; val bottom = cy + ch / 2f
            chip.rewind()
            chip.moveTo(x + k, top); chip.lineTo(x + cw, top); chip.lineTo(x + cw, bottom - k)
            chip.lineTo(x + cw - k, bottom); chip.lineTo(x, bottom); chip.lineTo(x, top + k); chip.close()
            val sel = d === selected
            val alert = d.alerting && d.online
            val col = when { !d.online -> Hud.OFFLINE; alert && blink -> Hud.CRIT; else -> d.accent }
            chipFill.color = Hud.alpha(col, if (sel) 0.16f else 0.04f)
            c.drawPath(chip, chipFill)
            chipBorder.color = Hud.alpha(col, if (sel) 0.95f else 0.35f)
            c.drawPath(chip, chipBorder)
            dotPaint.color = if (d.online) (if (alert) Hud.CRIT else d.accent) else Hud.CRIT
            c.drawCircle(x + Hud.dp(13f), cy, Hud.dp(3f), dotPaint)
            chipText.color = if (sel) Hud.TEXT else Hud.LABEL
            c.drawText(d.name, x + Hud.dp(23f), cy + chipText.textSize * 0.36f, chipText)
            chipHits[chipCount].set(x, 0f, x + cw, height.toFloat())
            chipDevices[chipCount] = d
            chipCount++
            x += cw + Hud.dp(8f)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val x = e.x; val y = e.y
        val l = listener ?: return true
        when {
            gearHit.contains(x, y) -> l.onSettings()
            detail && backHit.contains(x, y) -> l.onBack()
            detail -> for (i in 0 until chipCount) if (chipHits[i].contains(x, y)) { chipDevices[i]?.let(l::onChip); break }
        }
        return true
    }

    companion object {
        private val LINK_UP = "LINK ESTABLISHED" to 0xFF3DFFA8.toInt()
        private val LINK_CONNECTING = "HANDSHAKE…" to Hud.AMBER
        private val LINK_AUTH = "AUTH REJECTED" to Hud.CRIT
        private val LINK_OFF = "NO HUB SET" to Hud.WARN
        private val LINK_DOWN = "LINK DOWN · RETRYING" to Hud.CRIT
    }
}

/** Slides in when any device has been over threshold for 5+ s. Tap to jump to it. */
@SuppressLint("ViewConstructor")
class AlertBannerView(ctx: Context, private val fleet: Fleet) : HudView(ctx) {
    var onTap: ((DeviceState) -> Unit)? = null
    private var target: DeviceState? = null
    private var shown = false
    private var t = 0f
    private val plate = Path()
    private val fill = Hud.fill(Hud.alpha(Hud.CRIT, 0.14f))
    private val bg = Hud.fill(Hud.alpha(Hud.BG, 0.92f))
    private val border = Hud.stroke(Hud.dp(1.2f), round = false)
    private val tri = Path()
    private val triPaint = Hud.stroke(Hud.dp(1.6f))
    private val text = Hud.label(10.5f, Hud.TEXT).apply { letterSpacing = 0.12f }
    private val strong = Hud.number(11f, color = Hud.CRIT).apply { letterSpacing = 0.12f }
    private val buf = CharArray(64)

    init { visibility = GONE; alpha = 0f }

    fun refresh() {
        var best: DeviceState? = null
        for (d in fleet.devices) if (d.alerting && d.online && (best == null || d.alertSince < best.alertSince)) best = d
        target = best
        if (best != null && !shown) {
            shown = true
            visibility = VISIBLE
            translationY = -Hud.dp(60f)
            animate().translationY(0f).alpha(1f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).withEndAction(null).start()
        } else if (best == null && shown) {
            shown = false
            animate().translationY(-Hud.dp(60f)).alpha(0f).setDuration(200).withEndAction { if (!shown) visibility = GONE }.start()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val k = Hud.dp(10f)
        plate.rewind()
        plate.moveTo(k, 0f); plate.lineTo(w.toFloat(), 0f); plate.lineTo(w.toFloat(), h - k)
        plate.lineTo(w - k, h.toFloat()); plate.lineTo(0f, h.toFloat()); plate.lineTo(0f, k); plate.close()
        val cx = Hud.dp(24f); val cy = h / 2f; val s = Hud.dp(8f)
        tri.rewind(); tri.moveTo(cx, cy - s); tri.lineTo(cx + s, cy + s * 0.8f); tri.lineTo(cx - s, cy + s * 0.8f); tri.close()
        tri.moveTo(cx, cy - s * 0.3f); tri.lineTo(cx, cy + s * 0.25f)
    }

    override fun step(nowNanos: Long, dt: Float): Int {
        if (!shown) return Level.IDLE
        t += dt
        invalidate()
        return Level.AMBIENT
    }

    override fun onDraw(c: Canvas) {
        val d = target ?: return
        val p = 0.5f + 0.5f * sin(t * 6f)
        c.drawPath(plate, bg)
        c.drawPath(plate, fill)
        border.color = Hud.alpha(Hud.CRIT, 0.45f + 0.55f * p)
        c.drawPath(plate, border)
        triPaint.color = Hud.alpha(Hud.CRIT, 0.6f + 0.4f * p)
        c.drawPath(tri, triPaint)
        val y = height / 2f + text.textSize * 0.36f
        var x = Hud.dp(44f)
        c.drawText("CRITICAL //", x, y, strong)
        x += strong.measureText("CRITICAL //") + Hud.dp(10f)
        var len = Fmt.str(buf, 0, d.name)
        len = Fmt.str(buf, len, when (d.alertKind) { 1 -> "  CPU "; 2 -> "  RAM "; else -> "  TEMP " })
        len = if (d.alertKind == 3) { val l = Fmt.fixed1(buf, len, d.alertValue); buf[l] = '°'; buf[l + 1] = 'C'; l + 2 }
        else { val l = Fmt.pct0(buf, len, d.alertValue); buf[l] = '%'; l + 1 }
        len = Fmt.str(buf, len, "  FOR ")
        len = Fmt.uptime(buf, len, (SystemClock.uptimeMillis() - d.alertSince) / 1000)
        c.drawText(buf, 0, len, x, y, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) target?.let { onTap?.invoke(it) }
        return true
    }
}

/**
 * Boot / connect sequence (~2 s, tap to skip): typed status lines that report the REAL
 * link state, a scanning reticle and a progress rail. Ends with a beam sweep.
 */
@SuppressLint("ViewConstructor")
class BootView(ctx: Context, private val fleet: Fleet, private val hubLabel: String, private val lowPowerBoot: Boolean) : HudView(ctx) {
    var onDone: (() -> Unit)? = null
    private var t = 0f
    private var finished = false
    private val lines = arrayOfNulls<String>(6)
    private val lineStart = FloatArray(6)
    private var lineCount = 0
    private val text = Hud.number(13f, bold = false, color = Hud.CYAN).apply { letterSpacing = 0.06f }
    private val ok = Hud.number(13f, color = 0xFF3DFFA8.toInt())
    private val small = Hud.label(9f)
    private val ring = Hud.stroke(Hud.dp(2f))
    private val glow = Glow(Hud.dp(2f))
    private val rail = Hud.fill(Hud.TRACK)
    private val railFill = Hud.fill(Hud.CYAN)
    private val beam = Hud.fill()
    private val oval = RectF()
    private var exitT = -1f

    init {
        isClickable = true
        addLine("PULSE // TACTICAL TELEMETRY CONSOLE v1.0", 0f)
        addLine("INITIALIZING LINK… $hubLabel", 0.3f)
    }

    private fun addLine(s: String, at: Float) {
        if (lineCount >= lines.size) return
        lines[lineCount] = s; lineStart[lineCount] = at; lineCount++
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        beam.shader = LinearGradient(0f, 0f, Hud.dp(160f), 0f,
            intArrayOf(0, Hud.alpha(Hud.CYAN, 0.35f), 0xFFFFFFFF.toInt(), 0), floatArrayOf(0f, 0.8f, 0.9f, 1f), Shader.TileMode.CLAMP)
    }

    fun skip() { if (exitT < 0f) exitT = 0f }

    override fun step(nowNanos: Long, dt: Float): Int {
        if (finished) return Level.IDLE
        t += dt
        if (exitT >= 0f) {
            exitT += dt
            if (exitT >= 0.45f) { finished = true; onDone?.invoke(); return Level.IDLE }
        } else script()
        invalidate()
        return Level.ACTIVE
    }

    /** Lines 3-5 are decided by what the link actually does. */
    private fun script() {
        if (lineCount == 2 && t > 0.7f) {
            when (fleet.link) {
                Link.UP -> addLine("HANDSHAKE OK · TOKEN ACCEPTED", t)
                Link.AUTH_FAILED -> addLine("HANDSHAKE REJECTED · CHECK TOKEN", t)
                Link.OFF -> addLine("NO HUB CONFIGURED · OPENING SETTINGS", t)
                else -> if (t > 1.9f) addLine("LINK PENDING · RETRYING IN BACKGROUND", t)
            }
        }
        if (lineCount == 3 && t > lineStart[2] + 0.35f) {
            if (fleet.welcomed || t > lineStart[2] + 0.9f) {
                val n = if (fleet.welcomed) fleet.hubNodes else fleet.devices.size
                addLine(if (n > 0) "$n NODE${if (n == 1) "" else "S"} DETECTED" else "NO NODES REPORTING YET", t)
            }
        }
        if (lineCount == 4 && t > lineStart[3] + 0.3f)
            addLine(if (lowPowerBoot) "RENDER PIPELINE · LOW POWER 30 HZ" else "RENDER PIPELINE · 60 HZ · GLOW ON", t)
        if (lineCount == 5 && t > lineStart[4] + 0.35f) exitT = 0f
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val fade = if (exitT > 0f) (1f - exitT / 0.45f).coerceIn(0f, 1f) else 1f
        c.drawColor(Hud.alpha(Hud.BG, fade))
        // scanning reticle
        val cx = w * 0.74f; val cy = h * 0.5f
        val r = minOf(w, h) * 0.18f
        for (k in 0..2) {
            val ph = ((t * 0.7f) + k / 3f) % 1f
            ring.color = Hud.alpha(Hud.CYAN, (1f - ph) * 0.35f * fade)
            c.drawCircle(cx, cy, r * (0.4f + ph * 0.9f), ring)
        }
        oval.set(cx - r, cy - r, cx + r, cy + r)
        glow.color(Hud.CYAN, fade)
        glow.arc(c, oval, (t * 220f) % 360f, 70f)
        glow.arc(c, oval, (t * 220f + 180f) % 360f, 35f)
        ring.color = Hud.alpha(Hud.CYAN, 0.25f * fade)
        c.drawCircle(cx, cy, r * 0.62f, ring)

        // typed lines
        val x = w * 0.08f
        var y = h * 0.34f
        val lh = text.textSize * 1.9f
        for (i in 0 until lineCount) {
            val s = lines[i] ?: continue
            val chars = ((t - lineStart[i]) / 0.016f).toInt().coerceIn(0, s.length)
            text.color = Hud.alpha(if (s.contains("REJECTED") || s.contains("NO HUB")) Hud.CRIT else Hud.CYAN, fade)
            c.drawText("> ", x, y, text)
            c.drawText(s, 0, chars, x + text.measureText("> "), y, text)
            if (chars == s.length && (s.endsWith("OK · TOKEN ACCEPTED") || s.endsWith("DETECTED"))) {
                ok.color = Hud.alpha(0xFF3DFFA8.toInt(), fade)
                c.drawText("[ OK ]", x + text.measureText("> ") + text.measureText(s) + Hud.dp(12f), y, ok)
            }
            if (i == lineCount - 1 && chars == s.length && (t * 3f).toInt() % 2 == 0) {
                c.drawRect(x + text.measureText("> ") + text.measureText(s) + Hud.dp(4f), y - text.textSize * 0.8f,
                    x + text.measureText("> ") + text.measureText(s) + Hud.dp(11f), y + Hud.dp(2f), railFill)
            }
            y += lh
        }
        // progress rail
        val p = (lineCount / 5f + if (exitT >= 0f) 0.2f else 0f).coerceAtMost(1f)
        val ry = h * 0.34f + lh * 5.6f
        c.drawRect(x, ry, x + w * 0.4f, ry + Hud.dp(3f), rail)
        railFill.alpha = (255 * fade).toInt()
        c.drawRect(x, ry, x + w * 0.4f * p, ry + Hud.dp(3f), railFill)
        small.color = Hud.alpha(Hud.LABEL, fade)
        c.drawText("TAP TO SKIP", x, ry + Hud.dp(22f), small)
        // exit sweep
        if (exitT >= 0f) {
            val bx = -Hud.dp(160f) + (w + Hud.dp(160f)) * (exitT / 0.45f)
            c.save(); c.translate(bx, 0f)
            c.drawRect(0f, 0f, Hud.dp(160f), h, beam)
            c.restore()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) skip()
        return true
    }
}

/**
 * Transition layer drawn over both screens.
 *  MORPH: a HUD frame expands from the tapped card to full screen while its CPU ring
 *         flies and scales into the hero gauge position ("shared element" without
 *         snapshot bitmaps).
 *  WASH:  the new device's accent sweeps across the screen when switching devices.
 */
class OverlayView(ctx: Context) : View(ctx) {
    private var mode = 0
    private var t = 0f
    private var dur = 0.42f
    private var reverse = false
    private val from = RectF()
    private val to = RectF()
    private val gFrom = RectF()
    private val gTo = RectF()
    private val frame = RectF()
    private val oval = RectF()
    private var color = Hud.CYAN
    private var value = 0f
    private var onEnd: (() -> Unit)? = null
    private val chrome = PanelChrome()
    private val veil = Hud.fill()
    private val glow = Glow(Hud.dp(8f))
    private val track = Hud.stroke(Hud.dp(8f), Hud.TRACK, round = false)
    private val wash = Hud.fill()
    private val washMatrix = Matrix()

    val busy get() = mode != 0

    init { isClickable = false; visibility = GONE }

    fun morph(card: RectF, gauge: RectF, target: RectF, hero: RectF, accent: Int, cpu: Float, back: Boolean, end: () -> Unit) {
        from.set(card); gFrom.set(gauge); to.set(target); gTo.set(hero)
        color = accent; value = cpu; reverse = back; onEnd = end
        chrome.base = accent
        mode = MORPH; t = 0f; dur = if (back) 0.34f else 0.42f
        visibility = VISIBLE
        invalidate()
    }

    fun wash(accent: Int, leftToRight: Boolean) {
        color = accent
        mode = WASH; t = 0f; dur = 0.5f; reverse = !leftToRight
        wash.shader = LinearGradient(0f, 0f, width * 0.6f, 0f,
            intArrayOf(0, Hud.alpha(accent, 0.22f), Hud.alpha(accent, 0.45f), 0), floatArrayOf(0f, 0.6f, 0.85f, 1f), Shader.TileMode.CLAMP)
        visibility = VISIBLE
        invalidate()
    }

    /** Called by the activity's FrameDriver. */
    fun step(dt: Float): Int {
        if (mode == 0) return Level.IDLE
        t += dt / dur
        if (t >= 1f) {
            t = 1f
            val end = onEnd
            mode = 0; onEnd = null
            visibility = GONE
            end?.invoke()
            return Level.IDLE
        }
        invalidate()
        return Level.ACTIVE
    }

    private fun ease(x: Float): Float { val u = 1f - x; return 1f - u * u * u * u }

    override fun onDraw(c: Canvas) {
        when (mode) {
            MORPH -> {
                val e = ease(t)
                val p = if (reverse) 1f - e else e
                lerp(frame, from, to, p)
                lerp(oval, gFrom, gTo, p)
                veil.color = Hud.alpha(Hud.BG, 0.85f * p)
                c.drawRect(frame, veil)
                chrome.layout(frame.left, frame.top, frame.right, frame.bottom)
                chrome.draw(c, color, null, energy = 1f - p * 0.6f, dim = 0.9f)
                val sw = Hud.dp(6f) + Hud.dp(6f) * p
                track.strokeWidth = sw; glow.width(sw)
                c.drawArc(oval, 135f, 270f, false, track)
                glow.color(color, 1f)
                glow.arc(c, oval, 135f, 270f * (value / 100f).coerceIn(0f, 1f))
            }
            WASH -> {
                val e = ease(t)
                val w = width.toFloat()
                val x = if (!reverse) -w * 0.6f + (w * 1.6f) * e else w - (w * 1.6f) * e
                washMatrix.setTranslate(x, 0f)
                wash.shader?.setLocalMatrix(washMatrix)
                wash.alpha = (255 * (1f - e * 0.4f)).toInt()
                c.drawRect(0f, 0f, w, height.toFloat(), wash)
            }
        }
    }

    private fun lerp(out: RectF, a: RectF, b: RectF, p: Float) =
        out.set(a.left + (b.left - a.left) * p, a.top + (b.top - a.top) * p, a.right + (b.right - a.right) * p, a.bottom + (b.bottom - a.bottom) * p)

    companion object {
        const val MORPH = 1
        const val WASH = 2
    }
}
