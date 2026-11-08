// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import io.pulse.hud.core.Animated
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Level
import io.pulse.hud.data.DeviceState
import io.pulse.hud.data.Fleet
import io.pulse.hud.data.Prefs
import io.pulse.hud.widget.DeviceCardView
import io.pulse.hud.widget.TopBarView

/** Fleet grid: top bar + one card per device, arranged to maximise card size. */
@SuppressLint("ViewConstructor")
class OverviewScreen(ctx: Context, private val fleet: Fleet, private val prefs: Prefs) : ViewGroup(ctx), Animated {
    val topBar = TopBarView(ctx, fleet)
    val cards = ArrayList<DeviceCardView>()
    var cardListener: DeviceCardView.Listener? = null
    var lowPower = false
        set(v) { field = v; for (i in 0 until cards.size) cards[i].lowPower = v }

    /** False until the boot sequence hands over; cards created before that wait invisible. */
    private var entered = false
    private val empty = Hud.label(11f).apply { textAlign = Paint.Align.CENTER }
    private val emptySub = Hud.label(9f).apply { textAlign = Paint.Align.CENTER }
    private val barH = Hud.dp(52f).toInt()
    private val gap = Hud.dp(12f).toInt()

    init {
        addView(topBar)
        setWillNotDraw(false)
    }

    /** Create cards for new devices; keep existing ones (and their animation state). */
    fun syncCards() {
        for (d in fleet.devices) {
            if (cards.any { it.device === d }) continue
            val card = DeviceCardView(context, d, prefs)
            card.lowPower = lowPower
            card.listener = cardListener
            cards.add(card)
            addView(card)
            if (entered) enter(card, 0) else card.alpha = 0f
        }
        for (c in cards) c.syncAccent()
        topBar.invalidate()
        requestLayout()
    }

    fun card(d: DeviceState): DeviceCardView? = cards.firstOrNull { it.device === d }

    /** Staggered entrance: scale 0.92 -> 1 with a slight overshoot, 60 ms apart. */
    fun enterAll() {
        entered = true
        for ((i, c) in cards.withIndex()) enter(c, i)
    }

    private fun enter(c: DeviceCardView, i: Int) {
        c.alpha = 0f; c.scaleX = 0.92f; c.scaleY = 0.92f; c.translationY = Hud.dp(16f)
        c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(80L + i * 60L).setDuration(420).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    override fun step(nowNanos: Long, dt: Float): Int {
        var level = Level.IDLE
        for (i in 0 until cards.size) { val l = cards[i].step(nowNanos, dt); if (l > level) level = l }
        return level
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        topBar.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(barH, MeasureSpec.EXACTLY))
        val (cols, rows) = grid(w, h)
        if (cards.isEmpty()) return
        val cw = (w - gap * (cols + 1)) / cols
        val ch = (h - barH - gap * (rows + 1)) / rows
        for (c in cards) c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
    }

    /** Pick the column count that maximises the smaller side of each card. */
    private fun grid(w: Int, h: Int): Pair<Int, Int> {
        val n = cards.size.coerceAtLeast(1)
        var best = 1; var bestScore = -1f
        for (cols in 1..n) {
            val rows = (n + cols - 1) / cols
            val cw = (w - gap * (cols + 1)) / cols.toFloat()
            val ch = (h - barH - gap * (rows + 1)) / rows.toFloat()
            val score = minOf(cw, ch * 1.05f)
            if (score > bestScore) { bestScore = score; best = cols }
        }
        return best to (n + best - 1) / best
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        topBar.layout(0, 0, w, barH)
        if (cards.isEmpty()) return
        val (cols, _) = grid(w, b - t)
        for ((i, c) in cards.withIndex()) {
            val col = i % cols; val row = i / cols
            val x = gap + col * (c.measuredWidth + gap)
            val y = barH + gap + row * (c.measuredHeight + gap)
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }

    override fun onDraw(c: Canvas) {
        if (cards.isNotEmpty()) return
        val cx = width / 2f; val cy = height / 2f
        empty.color = Hud.LABEL
        c.drawText(if (fleet.welcomed) "NO NODES REPORTING" else "AWAITING HUB LINK", cx, cy, empty)
        emptySub.color = Hud.DIM
        c.drawText("START AN AGENT, OR RUN tools/mock_agent.py", cx, cy + Hud.dp(22f), emptySub)
    }

    override fun hasOverlappingRendering() = false

    /** Card bounds in this screen's coordinates (for the zoom transition). */
    fun cardBounds(card: DeviceCardView, out: RectF, gauge: RectF) {
        out.set(card.left.toFloat(), card.top.toFloat(), card.right.toFloat(), card.bottom.toFloat())
        gauge.set(card.gauge)
        gauge.offset(card.left.toFloat(), card.top.toFloat())
    }
}
