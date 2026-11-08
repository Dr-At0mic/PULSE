// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import io.pulse.hud.core.Fmt
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Level
import io.pulse.hud.core.Smooth
import io.pulse.hud.core.Spring
import io.pulse.hud.data.DeviceState
import io.pulse.hud.data.Prefs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

// =====================================================================================
/** Segmented LED-style memory bar: used / cached / free, plus a swap bar. */
class MemoryBarView(ctx: Context, private val prefs: Prefs) : HudView(ctx) {
    private var device: DeviceState? = null
    private val chrome = PanelChrome()
    private val used = Smooth(0f, 0.55f)
    private val cached = Smooth(0f, 0.55f)
    private val swap = Smooth(0f, 0.55f)
    private val seg = Hud.fill()
    private val bar = RectF()
    private val legend = Hud.label(8.5f)
    private val readout = Hud.number(12f).apply { textAlign = Paint.Align.RIGHT }
    private val buf = CharArray(48)

    fun bind(d: DeviceState) { device = d; setAccent(d.accent); used.snap(0f); cached.snap(0f); swap.snap(0f); onSample() }
    override fun setAccent(color: Int) { chrome.base = color; super.setAccent(color) }

    fun onSample() {
        val d = device ?: return
        val total = if (d.ramTotal > 0f) d.ramTotal else 1f
        val u = if (d.ramUsed > 0f) d.ramUsed / total else d.ram / 100f
        used.set(u.coerceIn(0f, 1f))
        cached.set(if (d.ramCached >= 0f) (d.ramCached / total).coerceIn(0f, 1f - u) else 0f)
        swap.set(if (d.swapTotal > 0f) (d.swapUsed / d.swapTotal).coerceIn(0f, 1f) else 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        chrome.layout(Hud.dp(1f), Hud.dp(1f), w - Hud.dp(1f), h - Hud.dp(1f))
        val top = Hud.dp(30f)
        bar.set(Hud.dp(16f), top, w - Hud.dp(16f), top + (h * 0.22f).coerceIn(Hud.dp(12f), Hud.dp(22f)))
    }

    override fun step(nowNanos: Long, dt: Float): Int {
        val ch = used.step(dt) or cached.step(dt) or swap.step(dt)
        if (ch) invalidate()
        return if (ch) Level.ACTIVE else Level.IDLE
    }

    override fun onDraw(c: Canvas) {
        val d = device ?: return
        chrome.draw(c, accent, "MEMORY")
        var len = Fmt.fixed1(buf, 0, d.ramUsed); len = Fmt.str(buf, len, " / ")
        len = Fmt.fixed1(buf, len, d.ramTotal); len = Fmt.str(buf, len, " GB  ")
        len = Fmt.pct0(buf, len, d.ram); buf[len++] = '%'
        readout.color = Hud.TEXT
        c.drawText(buf, 0, len, width - Hud.dp(18f), chrome.titleY, readout)

        val usedColor = Hud.level(accent, d.ram, prefs.ramAlert - 10f, prefs.ramAlert)
        val count = (bar.width() / Hud.dp(7f)).toInt().coerceAtLeast(10)
        val sw = bar.width() / count
        val gap = Hud.dp(2f)
        val u = used.value; val k = cached.value
        for (i in 0 until count) {
            val f0 = i / count.toFloat(); val f1 = (i + 1) / count.toFloat()
            seg.color = when {
                f1 <= u -> usedColor
                f0 < u -> Hud.lerpColor(Hud.alpha(accent, 0.38f), usedColor, (u - f0) / (f1 - f0))
                f1 <= u + k -> Hud.alpha(accent, 0.34f)
                f0 < u + k -> Hud.lerpColor(Hud.TRACK, Hud.alpha(accent, 0.34f), (u + k - f0) / (f1 - f0))
                else -> Hud.TRACK
            }
            val x = bar.left + i * sw
            c.drawRect(x, bar.top, x + sw - gap, bar.bottom, seg)
        }
        // legend
        val ly = bar.bottom + Hud.dp(16f)
        var x = bar.left
        x = legendItem(c, x, ly, usedColor, "USED ", d.ramUsed)
        // Linux counts some reclaimable memory in both "used" and "cached": clamp so the
        // three legend values always add up to the total
        val cachedShown = if (d.ramCached >= 0f) minOf(d.ramCached, (d.ramTotal - d.ramUsed).coerceAtLeast(0f)) else 0f
        if (d.ramCached >= 0f) x = legendItem(c, x, ly, Hud.alpha(accent, 0.34f), "CACHED ", cachedShown)
        legendItem(c, x, ly, Hud.TRACK, "FREE ", (d.ramTotal - d.ramUsed - cachedShown).coerceAtLeast(0f))

        // swap
        if (d.swapTotal > 0f) {
            val sy = height - Hud.dp(16f)
            legend.color = Hud.LABEL
            c.drawText("SWAP", bar.left, sy, legend)
            val bx = bar.left + Hud.dp(44f)
            len = Fmt.fixed1(buf, 0, d.swapUsed); len = Fmt.str(buf, len, " / "); len = Fmt.fixed1(buf, len, d.swapTotal); len = Fmt.str(buf, len, " GB")
            val tw = legend.measureText(buf, 0, len)
            val bx2 = bar.right - tw - Hud.dp(10f)
            seg.color = Hud.TRACK
            c.drawRect(bx, sy - Hud.dp(6f), bx2, sy - Hud.dp(2f), seg)
            seg.color = Hud.level(Hud.ICE, swap.value * 100f, 60f, 85f)
            c.drawRect(bx, sy - Hud.dp(6f), bx + (bx2 - bx) * swap.value, sy - Hud.dp(2f), seg)
            c.drawText(buf, 0, len, bx2 + Hud.dp(10f), sy, legend)
        }
    }

    private fun legendItem(c: Canvas, x: Float, y: Float, color: Int, label: String, gb: Float): Float {
        seg.color = color
        c.drawRect(x, y - Hud.dp(7f), x + Hud.dp(7f), y, seg)
        var len = Fmt.str(buf, 0, label)
        len = Fmt.fixed1(buf, len, gb); buf[len++] = 'G'
        legend.color = Hud.LABEL
        c.drawText(buf, 0, len, x + Hud.dp(11f), y, legend)
        return x + Hud.dp(11f) + legend.measureText(buf, 0, len) + Hud.dp(14f)
    }
}

// =====================================================================================
/** One usage ring per partition (up to 4) + disk read/write throughput meters. */
class DiskView(ctx: Context) : HudView(ctx) {
    private var device: DeviceState? = null
    private val chrome = PanelChrome()
    private val pct = Array(4) { Smooth(0f, 0.6f) }
    private val rd = Smooth(0f, 0.45f)
    private val wr = Smooth(0f, 0.45f)
    private val oval = RectF()
    private val track = Hud.stroke(Hud.dp(4f), Hud.TRACK, round = false)
    private val glow = Glow(Hud.dp(4f))
    private val center = Hud.number(11f).apply { textAlign = Paint.Align.CENTER }
    private val lbl = Hud.label(8f).apply { textAlign = Paint.Align.CENTER }
    private val io = Hud.label(8.5f)
    private val ioVal = Hud.number(11f)
    private val meter = Hud.fill()
    private val buf = CharArray(32)

    fun bind(d: DeviceState) { device = d; setAccent(d.accent); for (s in pct) s.snap(0f); rd.snap(0f); wr.snap(0f); onSample() }
    override fun setAccent(color: Int) { chrome.base = color; super.setAccent(color) }

    fun onSample() {
        val d = device ?: return
        for (i in 0 until minOf(4, d.diskCount)) pct[i].set(d.diskPct[i])
        rd.set(logScale(d.diskRead)); wr.set(logScale(d.diskWrite))
    }

    /** 1 KB/s .. 1 GB/s mapped to 0..1 on a log scale so small activity is still visible. */
    private fun logScale(bps: Float) = if (bps < 1024f) 0f else (ln(bps / 1024f) / ln(1024f * 1024f)).coerceIn(0f, 1f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) =
        chrome.layout(Hud.dp(1f), Hud.dp(1f), w - Hud.dp(1f), h - Hud.dp(1f))

    override fun step(nowNanos: Long, dt: Float): Int {
        var ch = rd.step(dt) or wr.step(dt)
        for (s in pct) ch = s.step(dt) or ch
        if (ch) invalidate()
        return if (ch) Level.ACTIVE else Level.IDLE
    }

    override fun onDraw(c: Canvas) {
        val d = device ?: return
        chrome.draw(c, accent, "STORAGE")
        val n = minOf(4, d.diskCount)
        val ioH = Hud.dp(40f)
        val top = Hud.dp(30f)
        val areaH = height - top - ioH - Hud.dp(8f)
        if (n > 0) {
            val slot = (width - Hud.dp(28f)) / n
            val ring = minOf(slot * 0.72f, areaH - Hud.dp(26f)).coerceAtLeast(Hud.dp(24f))
            val sw = (ring * 0.09f).coerceIn(Hud.dp(2.5f), Hud.dp(5f))
            track.strokeWidth = sw; glow.width(sw)
            center.textSize = (ring * 0.24f).coerceIn(Hud.sp(9f), Hud.sp(14f))
            for (i in 0 until n) {
                val cx = Hud.dp(14f) + slot * (i + 0.5f)
                val cy = top + (areaH - Hud.dp(22f)) / 2f + Hud.dp(4f)
                val r = ring / 2f
                oval.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(oval, 0f, 360f, false, track)
                val v = pct[i].value
                val col = Hud.level(accent, v, 85f, 95f)
                glow.color(col, 0.95f)
                glow.arc(c, oval, -90f, 360f * v / 100f)
                center.color = Hud.TEXT
                var len = Fmt.pct0(buf, 0, v); buf[len++] = '%'
                c.drawText(buf, 0, len, cx, cy + center.textSize * 0.36f, center)
                lbl.color = Hud.TEXT
                c.drawText(d.diskNames[i] ?: "?", cx, cy + r + Hud.dp(14f), lbl)
                lbl.color = Hud.LABEL
                len = Fmt.int(buf, 0, Math.round(d.diskUsed[i])); buf[len++] = '/'
                len = Fmt.int(buf, len, Math.round(d.diskTotal[i])); buf[len++] = 'G'
                c.drawText(buf, 0, len, cx, cy + r + Hud.dp(25f), lbl)
            }
        }
        // throughput meters
        val y0 = height - ioH
        ioRow(c, y0 + Hud.dp(12f), "READ ", d.diskRead, rd.value, accent)
        ioRow(c, y0 + Hud.dp(30f), "WRITE", d.diskWrite, wr.value, Hud.ICE)
    }

    private fun ioRow(c: Canvas, y: Float, label: String, bps: Float, fill: Float, color: Int) {
        val x = Hud.dp(16f)
        io.color = Hud.LABEL
        c.drawText(label, x, y, io)
        val mx = x + Hud.dp(46f)
        val len = Fmt.rate(buf, 0, bps)
        ioVal.color = Hud.TEXT
        val tw = ioVal.measureText(buf, 0, len)
        val mx2 = width - Hud.dp(18f) - tw - Hud.dp(10f)
        meter.color = Hud.TRACK
        c.drawRect(mx, y - Hud.dp(6f), mx2, y - Hud.dp(1f), meter)
        meter.color = color
        c.drawRect(mx, y - Hud.dp(6f), mx + (mx2 - mx) * fill, y - Hud.dp(1f), meter)
        c.drawText(buf, 0, len, width - Hud.dp(18f) - tw, y, ioVal)
    }
}

// =====================================================================================
/** CPU temperature on a 180 degree arc whose colour runs cool cyan -> amber -> hot red. */
class TempView(ctx: Context) : HudView(ctx) {
    private var device: DeviceState? = null
    private val chrome = PanelChrome()
    private val value = Smooth(20f, 0.7f)
    private val oval = RectF()
    private val arcTrack = Hud.stroke(Hud.dp(8f), round = false)
    private val arcHalo = Hud.stroke(Hud.dp(24f), round = false)
    private val arcCore = Hud.stroke(Hud.dp(8f), round = false)
    private val glow = Glow(Hud.dp(6f))
    private val tick = Hud.stroke(Hud.dp(1f), Hud.DIM, round = false)
    private val big = Hud.number(26f)
    private val lbl = Hud.label(8.5f).apply { textAlign = Paint.Align.CENTER }
    private val scaleLbl = Hud.label(7.5f).apply { textAlign = Paint.Align.CENTER }
    private val gpu = Hud.label(8.5f).apply { textAlign = Paint.Align.CENTER }
    private val buf = CharArray(64)
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    fun bind(d: DeviceState) { device = d; setAccent(d.accent); value.snap(20f); onSample() }
    override fun setAccent(color: Int) { chrome.base = color; super.setAccent(color) }

    fun onSample() { val d = device ?: return; if (!d.temp.isNaN()) value.set(d.temp.coerceIn(20f, 100f)) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        chrome.layout(Hud.dp(1f), Hud.dp(1f), w - Hud.dp(1f), h - Hud.dp(1f))
        val top = Hud.dp(34f)
        val gpuH = Hud.dp(22f)
        r = minOf((w - Hud.dp(48f)) / 2f, (h - top - gpuH - Hud.dp(30f))).coerceAtLeast(Hud.dp(20f))
        cx = w / 2f
        cy = top + r + Hud.dp(6f)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val sw = (r * 0.12f).coerceIn(Hud.dp(4f), Hud.dp(10f))
        arcTrack.strokeWidth = sw; arcCore.strokeWidth = sw; arcHalo.strokeWidth = sw * 3f
        // colour stops mapped onto the 20..100 C span of the top half (sweep positions 0.5..1.0)
        fun pos(t: Float) = 0.5f + (t - 20f) / 80f * 0.5f
        val sh = SweepGradient(cx, cy,
            intArrayOf(Hud.CYAN, Hud.CYAN, Hud.CYAN, Hud.AMBER, Hud.CRIT, Hud.CRIT),
            floatArrayOf(0f, 0.5f, pos(45f), pos(65f), pos(85f), 1f))
        arcTrack.shader = sh; arcCore.shader = sh; arcHalo.shader = sh
        arcTrack.alpha = 46; arcHalo.alpha = 50
        big.textSize = (r * 0.34f).coerceIn(Hud.sp(15f), Hud.sp(28f))
    }

    override fun step(nowNanos: Long, dt: Float): Int {
        val ch = value.step(dt)
        if (ch) invalidate()
        return if (ch) Level.ACTIVE else Level.IDLE
    }

    override fun onDraw(c: Canvas) {
        val d = device ?: return
        chrome.draw(c, accent, "THERMALS")
        c.drawArc(oval, 180f, 180f, false, arcTrack)
        for (t in 0..4) {                                   // 20, 40, 60, 80, 100
            val a = Math.toRadians(180.0 + 45.0 * t)
            val r0 = r + arcTrack.strokeWidth * 0.8f
            val r1 = r0 + Hud.dp(5f)
            c.drawLine(cx + (cos(a) * r0).toFloat(), cy + (sin(a) * r0).toFloat(), cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), tick)
        }
        scaleLbl.color = Hud.LABEL
        // scale labels only when they can't collide with the centre caption
        if (r * 2f > lbl.measureText("CPU PACKAGE") + scaleLbl.measureText("100") * 2f + Hud.dp(24f)) {
            c.drawText("20", oval.left, cy + Hud.dp(14f), scaleLbl)
            c.drawText("100", oval.right, cy + Hud.dp(14f), scaleLbl)
        }
        val has = !d.temp.isNaN()
        if (has) {
            val v = value.value
            val sweep = 180f * (v - 20f) / 80f
            c.drawArc(oval, 180f, sweep, false, arcHalo)
            c.drawArc(oval, 180f, sweep, false, arcCore)
            val a = Math.toRadians((180f + sweep).toDouble())
            glow.dot(c, cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), arcCore.strokeWidth * 0.55f, Hud.heat(v))
            big.color = Hud.heat(v)
            var len = Fmt.fixed1(buf, 0, v); buf[len++] = '°'; buf[len++] = 'C'
            val tw = big.measureText(buf, 0, len)
            c.drawText(buf, 0, len, cx - tw / 2f, cy - Hud.dp(2f), big)
            lbl.color = Hud.LABEL
            c.drawText("CPU PACKAGE", cx, cy + Hud.dp(14f), lbl)
        } else {
            big.color = Hud.LABEL
            val tw = big.measureText("N/A")
            c.drawText("N/A", cx - tw / 2f, cy - Hud.dp(2f), big)
            lbl.color = Hud.LABEL
            c.drawText("NO SENSOR", cx, cy + Hud.dp(14f), lbl)
        }
        if (!d.gpuUtil.isNaN()) {
            var len = Fmt.str(buf, 0, "GPU ")
            if (!d.gpuTemp.isNaN()) { len = Fmt.int(buf, len, Math.round(d.gpuTemp)); buf[len++] = '°'; len = Fmt.str(buf, len, " · ") }
            len = Fmt.pct0(buf, len, d.gpuUtil); buf[len++] = '%'
            len = Fmt.str(buf, len, " · "); len = Fmt.fixed1(buf, len, d.gpuMem); buf[len++] = '/'
            len = Fmt.fixed1(buf, len, d.gpuMemTotal); len = Fmt.str(buf, len, " GB")
            gpu.color = if (d.gpuTemp.isNaN()) Hud.TEXT else Hud.lerpColor(Hud.TEXT, Hud.heat(d.gpuTemp), 0.5f)
            c.drawText(buf, 0, len, cx, height - Hud.dp(12f), gpu)
        }
    }
}

// =====================================================================================
/** Top 5 processes. Rows are keyed by name and slide to their new rank on a spring. */
class ProcessListView(ctx: Context) : HudView(ctx) {
    private class Row {
        var key: String? = null
        var name = ""
        val slot = Spring(0f, 150f, 0.82f)
        val alpha = Smooth(0f, 0.35f)
        val cpu = Smooth(0f, 0.5f)
        var mem = 0
        var active = false
        var seen = false
    }

    private var device: DeviceState? = null
    private val chrome = PanelChrome()
    private val rows = Array(10) { Row() }
    private val keys = arrayOfNulls<String>(DeviceState.MAX_PROCS)
    private val head = Hud.label(8f)
    private val headR = Hud.label(8f).apply { textAlign = Paint.Align.RIGHT }
    private val name = Hud.number(11.5f, bold = false)
    private val num = Hud.number(11.5f).apply { textAlign = Paint.Align.RIGHT }
    private val rank = Hud.number(9f, color = Hud.DIM)
    private val bar = Hud.fill()
    private val buf = CharArray(24)

    fun bind(d: DeviceState) {
        device = d
        setAccent(d.accent)
        for (r in rows) { r.active = false; r.key = null }
        onProcs()
    }

    override fun setAccent(color: Int) { chrome.base = color; super.setAccent(color) }

    fun onProcs() {
        val d = device ?: return
        for (r in rows) r.seen = false
        for (i in 0 until d.procCount) {
            val nm = d.procNames[i] ?: continue
            var dup = 0
            for (j in 0 until i) if (d.procNames[j] == nm) dup++
            val key = if (dup == 0) nm else "$nm#$dup"          // allocation only here, on data, never in onDraw
            keys[i] = key
            var row: Row? = null
            for (r in rows) if (r.active && r.key == key) { row = r; break }
            if (row == null) {
                for (r in rows) if (!r.active) { row = r; break }
                row ?: continue
                row.active = true; row.key = key; row.name = nm
                row.slot.value = i + 0.9f; row.slot.velocity = 0f
                row.alpha.snap(0f); row.cpu.snap(0f)
            }
            row.seen = true
            row.slot.target = i.toFloat()
            row.alpha.set(1f)
            row.cpu.set(d.procCpu[i])
            row.mem = d.procMem[i]
        }
        for (r in rows) if (r.active && !r.seen) { r.alpha.set(0f); r.slot.target = DeviceState.MAX_PROCS.toFloat() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) =
        chrome.layout(Hud.dp(1f), Hud.dp(1f), w - Hud.dp(1f), h - Hud.dp(1f))

    override fun step(nowNanos: Long, dt: Float): Int {
        var ch = false
        for (r in rows) {
            if (!r.active) continue
            ch = r.slot.step(dt) or ch
            ch = r.alpha.step(dt) or ch
            ch = r.cpu.step(dt) or ch
            if (r.alpha.target == 0f && !r.alpha.moving) { r.active = false; r.key = null; ch = true }
        }
        if (ch) invalidate()
        return if (ch) Level.ACTIVE else Level.IDLE
    }

    override fun onDraw(c: Canvas) {
        chrome.draw(c, accent, "TOP PROCESSES")
        val left = Hud.dp(16f); val right = width - Hud.dp(18f)
        val memX = right
        val cpuX = right - Hud.dp(62f)
        val headY = Hud.dp(40f)
        head.color = Hud.LABEL; headR.color = Hud.LABEL
        c.drawText("PROCESS", left + Hud.dp(20f), headY, head)
        c.drawText("CPU", cpuX, headY, headR)
        c.drawText("MEM", memX, headY, headR)
        val top = headY + Hud.dp(8f)
        val rowH = ((height - top - Hud.dp(10f)) / DeviceState.MAX_PROCS).coerceAtMost(Hud.dp(34f))
        var maxCpu = 5f
        for (r in rows) if (r.active && r.cpu.target > maxCpu) maxCpu = r.cpu.target
        c.save()
        c.clipRect(0f, top, width.toFloat(), height - Hud.dp(6f))
        for (r in rows) {
            if (!r.active) continue
            val a = r.alpha.value
            val y = top + r.slot.value * rowH
            val base = y + rowH * 0.62f
            rank.color = Hud.alpha(Hud.LABEL, a * 0.7f)
            var len = Fmt.int(buf, 0, Math.round(r.slot.target) + 1, 2)
            c.drawText(buf, 0, len, left, base, rank)
            name.color = Hud.alpha(Hud.TEXT, a)
            val nameMax = cpuX - Hud.dp(48f) - (left + Hud.dp(20f))
            val n = name.breakText(r.name, true, nameMax, null)
            c.drawText(r.name, 0, n, left + Hud.dp(20f), base, name)
            // CPU bar under the row
            val bx0 = left + Hud.dp(20f)
            val bx1 = cpuX - Hud.dp(44f)
            bar.color = Hud.alpha(Hud.TRACK, a)
            c.drawRect(bx0, y + rowH - Hud.dp(4f), bx1, y + rowH - Hud.dp(2f), bar)
            bar.color = Hud.alpha(Hud.level(accent, r.cpu.value, 50f, 85f), a)
            c.drawRect(bx0, y + rowH - Hud.dp(4f), bx0 + (bx1 - bx0) * (r.cpu.value / maxCpu).coerceIn(0f, 1f), y + rowH - Hud.dp(2f), bar)
            num.color = Hud.alpha(Hud.level(Hud.TEXT, r.cpu.value, 50f, 85f), a)
            len = Fmt.fixed1(buf, 0, r.cpu.value); buf[len++] = '%'
            c.drawText(buf, 0, len, cpuX, base, num)
            num.color = Hud.alpha(Hud.LABEL, a)
            len = if (r.mem >= 1024) { val l = Fmt.fixed1(buf, 0, r.mem / 1024f); buf[l] = 'G'; l + 1 }
            else { val l = Fmt.int(buf, 0, r.mem); buf[l] = 'M'; l + 1 }
            c.drawText(buf, 0, len, memX, base, num)
        }
        c.restore()
    }
}

// =====================================================================================
/** Bottom strip: host, OS, CPU model, live-ticking uptime. Redraws once per second. */
class InfoStripView(ctx: Context) : HudView(ctx) {
    private var device: DeviceState? = null
    private val lbl = Hud.label(8.5f)
    private val value = Hud.number(10.5f, bold = false)
    private val sep = Hud.stroke(Hud.dp(1f), Hud.DIM, round = false)
    private val edge = Hud.stroke(Hud.dp(1f), round = false)
    private val buf = CharArray(32)
    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 1000L - System.currentTimeMillis() % 1000L) }
    }

    fun bind(d: DeviceState) { device = d; setAccent(d.accent); invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        removeCallbacks(tick)
        if (visibility == VISIBLE && isAttachedToWindow) post(tick)
    }

    override fun onDraw(c: Canvas) {
        val d = device ?: return
        edge.color = Hud.alpha(accent, 0.35f)
        c.drawLine(0f, 0f, width.toFloat(), 0f, edge)
        val y = height / 2f + value.textSize * 0.36f
        var x = Hud.dp(4f)
        // uptime first-class (right side), rest flows from the left
        var len = Fmt.uptime(buf, 0, d.uptimeSeconds())
        val upW = lbl.measureText("UPTIME ") + value.measureText(buf, 0, len)
        val limit = width - upW - Hud.dp(24f)
        x = seg(c, x, y, "HOST ", d.host, limit)
        x = seg(c, x, y, "OS ", d.os, limit)
        seg(c, x, y, "CPU ", d.cpuModel, limit)
        lbl.color = Hud.LABEL
        val ux = width - upW - Hud.dp(4f)
        c.drawText("UPTIME ", ux, y, lbl)
        value.color = accent
        c.drawText(buf, 0, len, ux + lbl.measureText("UPTIME "), y, value)
    }

    private fun seg(c: Canvas, x0: Float, y: Float, label: String, text: String, limit: Float): Float {
        if (text.isEmpty() || x0 >= limit) return x0
        var x = x0
        lbl.color = Hud.LABEL
        c.drawText(label, x, y, lbl)
        x += lbl.measureText(label)
        value.color = Hud.TEXT
        val n = value.breakText(text, true, limit - x, null)
        c.drawText(text, 0, n, x, y, value)
        x += value.measureText(text, 0, n) + Hud.dp(12f)
        c.drawLine(x, height * 0.3f, x, height * 0.7f, sep)
        return x + Hud.dp(12f)
    }
}

