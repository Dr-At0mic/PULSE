// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.pulse.hud.core.Animated
import io.pulse.hud.core.Fmt
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Lcg
import io.pulse.hud.core.Level
import io.pulse.hud.core.Smooth
import io.pulse.hud.data.DeviceState
import io.pulse.hud.data.Prefs
import kotlin.math.sin

/**
 * Overview card for one machine.
 *
 * Structure: a ViewGroup with three children
 *   body    - draws everything that changes with data (gauges, sparkline, stats)
 *   spinner - the reticle ring around the gauge, rotated as a RenderNode property
 *   dot     - the online indicator, breathing via alpha/scale properties
 * so ambient motion never forces the body to redraw.
 */
@SuppressLint("ViewConstructor")
class DeviceCardView(ctx: Context, val device: DeviceState, private val prefs: Prefs) : ViewGroup(ctx), Animated {

    fun interface Listener { fun onCardTapped(card: DeviceCardView) }

    var listener: Listener? = null
    var lowPower = false
        set(v) { field = v; plot.lowPower = v; if (v) { spinner.rotation = 0f; dot.rest() } }

    private val body = Body(ctx)
    val spinner = SpinnerRingView(ctx).apply { dashes = 60 }
    private val dot = PulseDotView(ctx)
    /** 60 s CPU sparkline: recorded once per sample into a texture, slid between samples. */
    private val plot = ScrollingPlot(ctx, 60).apply { continuous = false; easeSeconds = 0.5f }

    /** CPU arc oval in card coordinates (used by the zoom transition too). */
    val gauge = RectF()
    private val gaugeBox = RectF()
    private val spark = RectF()
    private val stats = RectF()
    private var pad = Hud.dp(14f)
    private var size = 0f

    private val cpu = Smooth(0f, 0.55f)
    private val ram = Smooth(0f, 0.55f)
    private val off = Smooth(1f, 0.6f)
    private var t = 0f
    private var power = 1f
    private var glitchTimer = 1.5f
    private var glitch = 0f
    private var alertT = 0f
    private val rng = Lcg(device.id.hashCode())
    private val slices = FloatArray(6)
    private var offlineSince = SystemClock.uptimeMillis()
    private var shownSecond = -1L

    init {
        addView(body)
        addView(plot)
        addView(spinner)
        addView(dot)
        isClickable = true
        isFocusable = true
        syncAccent()
        applyStatus(animate = false)
    }

    // ------------------------------------------------------------------ events

    fun syncAccent() {
        body.chrome.base = device.accent
        spinner.setAccent(device.accent)
        dot.setColor(if (device.online) device.accent else Hud.CRIT)
        plotColors()
        body.invalidate()
    }

    private fun plotColors() {
        val line = if (device.online) device.accent else Hud.desaturate(device.accent, 1f)
        plot.setSeries(device.cpuHist, line, if (device.online) device.accent else Hud.OFFLINE, fillStrengthA = 0.32f)
        plot.onHistory()
    }

    fun onSample() {
        cpu.set(device.cpu); ram.set(device.ram)
        plot.onSample(device.interval)
        contentDescription = "${device.name}, CPU ${device.cpu.toInt()} percent, RAM ${device.ram.toInt()} percent"
        body.invalidate()
    }

    fun onHistory() = plot.onHistory()

    fun onStatus() = applyStatus(animate = true)

    private fun applyStatus(animate: Boolean) {
        if (device.online) {
            if (animate && off.target > 0.5f) {           // came back: power-up sequence
                power = 0f
                cpu.snap(0f); ram.snap(0f)
                cpu.set(device.cpu); ram.set(device.ram)
            }
            off.set(0f, animate)
            spinner.alpha = 1f
        } else {
            offlineSince = SystemClock.uptimeMillis()
            off.set(1f, animate)
            spinner.alpha = 0.25f
            glitchTimer = 0.05f                            // glitch immediately on loss
        }
        dot.setColor(if (device.online) device.accent else Hud.CRIT)
        plotColors()
        body.invalidate()
    }

    // ------------------------------------------------------------------ animation

    override fun step(nowNanos: Long, dt: Float): Int {
        t += dt
        val offChanged = off.step(dt)
        var changed = cpu.step(dt) or ram.step(dt) or offChanged
        var level = if (plot.step()) Level.ACTIVE else Level.IDLE   // properties only, no redraw
        if (offChanged) plot.alpha = 1f - 0.5f * off.value
        if (power < 1f) { power = minOf(1f, power + dt / 1.1f); changed = true }
        if (device.alerting && device.online) { alertT += dt; changed = true }
        if (!device.online) {
            val sec = (SystemClock.uptimeMillis() - offlineSince) / 1000
            if (sec != shownSecond) { shownSecond = sec; changed = true }
            if (!lowPower) {
                level = Level.AMBIENT
                glitchTimer -= dt
                if (glitchTimer <= 0f) { glitch = 0.24f; glitchTimer = 2.0f + rng.next() * 2.2f }
                if (glitch > 0f) {
                    glitch -= dt
                    for (i in slices.indices) slices[i] = if (rng.next() < 0.45f) 0f else (rng.next() - 0.5f) * Hud.dp(18f)
                    plot.translationX = if (glitch > 0f) slices[4] else 0f
                    changed = true
                }
            }
        }
        if (!lowPower) {
            if (device.online) spinner.rotation = (t * 14f) % 360f
            dot.breathe(t, device.alerting || !device.online)
            if (level < Level.AMBIENT) level = Level.AMBIENT
        }
        if (changed) { body.invalidate(); level = Level.ACTIVE }
        if (level == Level.IDLE && plot.translationX != 0f) plot.translationX = 0f
        return level
    }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        body.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = (r - l).toFloat(); val h = (b - t).toFloat()
        pad = (minOf(w, h) * 0.045f).coerceIn(Hud.dp(10f), Hud.dp(16f))
        val headerH = Hud.dp(44f)
        val top = pad + headerH
        val bottom = h - pad
        val statsH = Hud.dp(44f)
        if (h >= w * 0.95f) {                                      // tall card
            val sparkH = maxOf(Hud.dp(44f), (bottom - top) * 0.17f)
            stats.set(pad + Hud.dp(4f), bottom - statsH, w - pad - Hud.dp(4f), bottom)
            spark.set(pad + Hud.dp(6f), stats.top - Hud.dp(10f) - sparkH, w - pad - Hud.dp(6f), stats.top - Hud.dp(10f))
            val avail = spark.top - Hud.dp(12f) - top
            size = minOf(w - 2 * pad - Hud.dp(12f), avail)
            gaugeBox.set((w - size) / 2f, top + (avail - size) / 2f, (w + size) / 2f, top + (avail + size) / 2f)
        } else {                                                    // wide card
            size = minOf(bottom - top, w * 0.46f)
            gaugeBox.set(pad + Hud.dp(2f), top + (bottom - top - size) / 2f, pad + Hud.dp(2f) + size, top + (bottom - top + size) / 2f)
            val x0 = gaugeBox.right + Hud.dp(14f)
            stats.set(x0, bottom - statsH, w - pad - Hud.dp(2f), bottom)
            spark.set(x0, top + Hud.dp(20f), w - pad - Hud.dp(6f), stats.top - Hud.dp(12f))
        }
        val inset = size * 0.12f
        gauge.set(gaugeBox.left + inset, gaugeBox.top + inset, gaugeBox.right - inset, gaugeBox.bottom - inset)
        body.layout(0, 0, w.toInt(), h.toInt())
        val pp = plot.pad.toInt()
        plot.measure(MeasureSpec.makeMeasureSpec(spark.width().toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(spark.height().toInt() + 2 * pp, MeasureSpec.EXACTLY))
        plot.layout(spark.left.toInt(), spark.top.toInt() - pp, spark.left.toInt() + spark.width().toInt(), spark.bottom.toInt() + pp)
        spinner.layout(gaugeBox.left.toInt(), gaugeBox.top.toInt(), gaugeBox.right.toInt(), gaugeBox.bottom.toInt())
        val ds = Hud.dp(22f).toInt()
        val dx = (pad - Hud.dp(1f)).toInt(); val dy = (pad + Hud.dp(1f)).toInt()
        dot.layout(dx, dy, dx + ds, dy + ds)
        body.onGeometry(w, h)
    }

    // entrance / transition fades must not allocate an offscreen buffer
    override fun hasOverlappingRendering() = false

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick()
            }
            MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onCardTapped(this)
        return true
    }

    // ------------------------------------------------------------------ drawing

    private inner class Body(ctx: Context) : View(ctx) {
        override fun hasOverlappingRendering() = false
        val chrome = PanelChrome()
        private val track = Hud.stroke(Hud.dp(8f), Hud.TRACK, round = false)
        private val cpuGlow = Glow(Hud.dp(8f))
        private val ramGlow = Glow(Hud.dp(3f))
        private val fx = Hud.stroke(Hud.dp(2f))
        private val baseline = Hud.stroke(Hud.dp(1f), Hud.DIM, round = false)
        private val name = Hud.number(15f).apply { letterSpacing = 0.08f }
        private val sub = Hud.label(8.5f)
        private val status = Hud.label(8.5f).apply { textAlign = Paint.Align.RIGHT }
        private val big = Hud.number(40f)
        private val unit = Hud.number(14f, bold = false, color = Hud.LABEL)
        private val small = Hud.label(9f).apply { textAlign = Paint.Align.CENTER }
        private val statLabel = Hud.label(8f)
        private val statValue = Hud.number(13f)
        private val lost = Hud.number(14f).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.12f }
        private val plate = Hud.fill(Hud.alpha(Hud.BG, 0.78f))
        private val buf = CharArray(32)
        private var w = 0f
        private var h = 0f

        fun onGeometry(w: Float, h: Float) {
            this.w = w; this.h = h
            chrome.layout(Hud.dp(1f), Hud.dp(1f), w - Hud.dp(1f), h - Hud.dp(1f))
            val stroke = (size * 0.052f).coerceAtLeast(Hud.dp(5f))
            track.strokeWidth = stroke
            cpuGlow.width(stroke)
            ramGlow.width(stroke * 0.38f)
            big.textSize = size * 0.20f
            unit.textSize = size * 0.075f
            small.textSize = (size * 0.052f).coerceIn(Hud.sp(8f), Hud.sp(11f))
            lost.textSize = (size * 0.105f).coerceIn(Hud.sp(11f), Hud.sp(20f))
        }

        override fun onDraw(c: Canvas) {
            if (w == 0f) return
            val offT = off.value
            val alertPulse = if (device.alerting && device.online) 0.5f + 0.5f * sin(alertT * 6f) else 0f
            val acc = Hud.desaturate(device.accent, offT)
            val line = if (alertPulse > 0f) Hud.lerpColor(acc, Hud.CRIT, 0.55f + 0.45f * alertPulse) else acc
            chrome.draw(c, line, null, energy = alertPulse * 0.8f + (1f - power) * 0.9f, grey = offT)

            if (glitch > 0f) {
                val sh = h / slices.size
                for (i in slices.indices) {
                    c.save()
                    c.clipRect(0f, i * sh, w, (i + 1) * sh)
                    c.translate(slices[i], 0f)
                    content(c, acc, offT)
                    c.restore()
                }
            } else content(c, acc, offT)

            if (offT > 0.02f) signalLost(c, offT)
            if (power < 1f) powerUp(c, acc)
            if (alertPulse > 0f || (device.alerting && device.online)) ripples(c)
        }

        private fun content(c: Canvas, acc: Int, offT: Float) {
            val dim = 1f - 0.5f * offT
            // header
            name.color = Hud.alpha(Hud.TEXT, dim)
            val nx = pad + Hud.dp(24f)
            c.drawText(device.name, nx, pad + Hud.dp(17f), name)
            sub.color = Hud.alpha(Hud.LABEL, dim)
            val subText = if (device.host.isNotEmpty()) device.host else device.id
            val n = sub.breakText(subText, true, w - nx - pad - Hud.dp(70f), null)
            c.drawText(subText, 0, n, nx, pad + Hud.dp(33f), sub)
            status.color = when {
                !device.online -> Hud.CRIT
                device.alerting -> Hud.CRIT
                !device.hasData -> Hud.LABEL
                else -> acc
            }
            c.drawText(when {
                !device.online -> "SIGNAL LOST"
                device.alerting -> "ALERT"
                !device.hasData -> "SYNCING"
                else -> "ONLINE"
            }, w - pad - Hud.dp(2f), pad + Hud.dp(17f), status)

            // gauge: CPU outer, RAM inner
            val cpuV = cpu.value
            c.drawArc(gauge, 135f, 270f, false, track)
            val cpuColor = Hud.desaturate(Hud.level(device.accent, cpuV, prefs.cpuAlert - 15f, prefs.cpuAlert), offT)
            cpuGlow.color(cpuColor, dim)
            cpuGlow.arc(c, gauge, 135f, 270f * (cpuV / 100f).coerceIn(0f, 1f))
            val r = gauge.width() / 2f
            val cx = gauge.centerX(); val cy = gauge.centerY()
            if (cpuV > 0.5f) {
                val a = Math.toRadians((135f + 270f * (cpuV / 100f).coerceIn(0f, 1f)).toDouble())
                cpuGlow.dot(c, cx + (Math.cos(a) * r).toFloat(), cy + (Math.sin(a) * r).toFloat(), track.strokeWidth * 0.42f, cpuColor, dim)
            }
            val ri = r * 0.80f
            c.save()
            c.scale(ri / r, ri / r, cx, cy)                   // reuse the oval: no RectF allocation
            val oldW = track.strokeWidth
            track.strokeWidth = oldW * 0.38f * r / ri
            c.drawArc(gauge, 135f, 270f, false, track)
            track.strokeWidth = oldW
            val ramV = ram.value
            val ramColor = Hud.desaturate(Hud.lerpColor(Hud.level(device.accent, ramV, prefs.ramAlert - 10f, prefs.ramAlert), Hud.TEXT, 0.25f), offT)
            ramGlow.width(oldW * 0.38f * r / ri)
            ramGlow.color(ramColor, dim * 0.9f)
            ramGlow.arc(c, gauge, 135f, 270f * (ramV / 100f).coerceIn(0f, 1f))
            c.restore()

            // centre readout
            small.color = Hud.alpha(Hud.LABEL, dim)
            c.drawText("CPU", cx, cy - big.textSize * 0.62f, small)
            big.color = Hud.alpha(Hud.TEXT, dim)
            var len = Fmt.pct0(buf, 0, cpuV)
            val bw = big.measureText(buf, 0, len)
            val uw = unit.measureText("%")
            val x0 = cx - (bw + uw) / 2f
            c.drawText(buf, 0, len, x0, cy + big.textSize * 0.36f, big)
            unit.color = Hud.alpha(Hud.LABEL, dim)
            c.drawText("%", x0 + bw + Hud.dp(1f), cy + big.textSize * 0.36f, unit)
            len = Fmt.str(buf, 0, "RAM ")
            len = Fmt.pct0(buf, len, ramV)
            buf[len++] = '%'
            small.color = Hud.alpha(ramColor, dim)
            c.drawText(buf, 0, len, cx, cy + big.textSize * 0.36f + small.textSize * 2.0f, small)

            // sparkline
            baseline.color = Hud.alpha(Hud.DIM, dim)
            c.drawLine(spark.left, spark.bottom, spark.right, spark.bottom, baseline)
            statLabel.color = Hud.alpha(Hud.LABEL, 0.8f * dim)
            c.drawText("CPU · 60s", spark.left, spark.top - Hud.dp(5f), statLabel)
            // (the trace itself is the ScrollingPlot child)

            // stats row: NET down, NET up, TEMP
            val colW = stats.width() / 3f
            val ly = stats.top + Hud.dp(12f)
            val vy = stats.top + Hud.dp(32f)
            statValue.color = Hud.alpha(Hud.TEXT, dim)
            c.drawText("NET ↓", stats.left, ly, statLabel)
            len = Fmt.rate(buf, 0, device.netDown)
            c.drawText(buf, 0, len, stats.left, vy, statValue)
            c.drawText("NET ↑", stats.left + colW, ly, statLabel)
            len = Fmt.rate(buf, 0, device.netUp)
            c.drawText(buf, 0, len, stats.left + colW, vy, statValue)
            c.drawText("TEMP", stats.left + colW * 2, ly, statLabel)
            if (device.temp.isNaN()) {
                statValue.color = Hud.alpha(Hud.LABEL, dim)
                c.drawText("N/A", stats.left + colW * 2, vy, statValue)
            } else {
                statValue.color = Hud.desaturate(Hud.heat(device.temp), offT)
                len = Fmt.fixed1(buf, 0, device.temp)
                buf[len++] = '°'; buf[len++] = 'C'
                c.drawText(buf, 0, len, stats.left + colW * 2, vy, statValue)
            }
        }

        /** "SIGNAL LOST": dark plate, RGB-split text, hazard lines. */
        private fun signalLost(c: Canvas, offT: Float) {
            val cx = gauge.centerX(); val cy = gauge.centerY()
            val tw = lost.measureText("SIGNAL LOST")
            val ph = lost.textSize * 2.9f
            plate.alpha = (200 * offT).toInt()
            c.drawRect(cx - tw / 2f - Hud.dp(14f), cy - ph / 2f, cx + tw / 2f + Hud.dp(14f), cy + ph / 2f, plate)
            fx.strokeWidth = Hud.dp(1f)
            fx.color = Hud.alpha(Hud.CRIT, 0.7f * offT)
            c.drawLine(cx - tw / 2f - Hud.dp(14f), cy - ph / 2f, cx + tw / 2f + Hud.dp(14f), cy - ph / 2f, fx)
            c.drawLine(cx - tw / 2f - Hud.dp(14f), cy + ph / 2f, cx + tw / 2f + Hud.dp(14f), cy + ph / 2f, fx)
            val split = Hud.dp(1.6f) + if (glitch > 0f) Hud.dp(5f) * rng.next() else 0f
            val ty = cy + lost.textSize * 0.1f
            lost.color = Hud.alpha(0xFFFF2E4D.toInt(), 0.75f * offT); c.drawText("SIGNAL LOST", cx - split, ty, lost)
            lost.color = Hud.alpha(0xFF00F0FF.toInt(), 0.55f * offT); c.drawText("SIGNAL LOST", cx + split, ty, lost)
            lost.color = Hud.alpha(Hud.TEXT, offT); c.drawText("SIGNAL LOST", cx, ty, lost)
            var len = Fmt.str(buf, 0, "NO CARRIER · ")
            len = Fmt.uptime(buf, len, (SystemClock.uptimeMillis() - offlineSince) / 1000)
            small.color = Hud.alpha(Hud.CRIT, 0.85f * offT)
            c.drawText(buf, 0, len, cx, ty + lost.textSize * 1.05f, small)
        }

        /** Reconnect power-up: expanding shockwave + a scan line wiping down the card. */
        private fun powerUp(c: Canvas, acc: Int) {
            val p = power
            val u = 1f - p
            fx.strokeWidth = Hud.dp(2.5f) * u + Hud.dp(0.5f)
            fx.color = Hud.alpha(acc, 0.9f * u)
            c.drawCircle(gauge.centerX(), gauge.centerY(), gauge.width() * (0.3f + 0.9f * p), fx)
            val y = h * p
            fx.strokeWidth = Hud.dp(8f); fx.color = Hud.alpha(acc, 0.18f * u)
            c.drawLine(0f, y, w, y, fx)
            fx.strokeWidth = Hud.dp(1.5f); fx.color = Hud.alpha(0xFFFFFFFF.toInt(), 0.8f * u)
            c.drawLine(0f, y, w, y, fx)
        }

        /** Alert: red rings rippling out of the gauge. */
        private fun ripples(c: Canvas) {
            val r = gauge.width() / 2f
            fx.strokeWidth = Hud.dp(2f)
            var k = 0
            while (k < 2) {
                val ph = ((alertT / 1.4f) + k * 0.5f) % 1f
                fx.color = Hud.alpha(Hud.CRIT, (1f - ph) * 0.65f)
                c.drawCircle(gauge.centerX(), gauge.centerY(), r * (1.02f + 0.5f * ph), fx)
                k++
            }
        }
    }
}
