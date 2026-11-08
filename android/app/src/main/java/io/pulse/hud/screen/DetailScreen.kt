// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import io.pulse.hud.core.Animated
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Level
import io.pulse.hud.data.DeviceState
import io.pulse.hud.data.Fleet
import io.pulse.hud.data.Prefs
import io.pulse.hud.widget.CoreBarsView
import io.pulse.hud.widget.DiskView
import io.pulse.hud.widget.HeroGaugeView
import io.pulse.hud.widget.HudView
import io.pulse.hud.widget.InfoStripView
import io.pulse.hud.widget.LineGraphView
import io.pulse.hud.widget.MemoryBarView
import io.pulse.hud.widget.ProcessListView
import io.pulse.hud.widget.SpinnerRingView
import io.pulse.hud.widget.TempView
import io.pulse.hud.widget.TopBarView
import kotlin.math.abs

/**
 * One device at full width. A single custom ViewGroup positions every panel
 * (no nested LinearLayouts, one measure pass). Landscape: three columns.
 * Portrait: stacked rows. Swipe left/right to change device.
 */
@SuppressLint("ViewConstructor")
class DetailScreen(ctx: Context, private val fleet: Fleet, private val prefs: Prefs) : ViewGroup(ctx), Animated {
    fun interface SwipeListener { fun onSwipe(direction: Int) }

    val topBar = TopBarView(ctx, fleet).apply { detail = true }
    val hero = HeroGaugeView(ctx, prefs)
    private val heroRing = SpinnerRingView(ctx)
    private val cores = CoreBarsView(ctx)
    private val cpuGraph = LineGraphView(ctx, LineGraphView.CPU)
    private val ramGraph = LineGraphView(ctx, LineGraphView.RAM)
    private val netGraph = LineGraphView(ctx, LineGraphView.NET)
    private val memory = MemoryBarView(ctx, prefs)
    private val temp = TempView(ctx)
    private val disks = DiskView(ctx)
    private val procs = ProcessListView(ctx)
    private val info = InfoStripView(ctx)
    private val panels: Array<HudView> = arrayOf(hero, cores, cpuGraph, ramGraph, netGraph, memory, temp, disks, procs, info)

    var device: DeviceState? = null
        private set
    var swipeListener: SwipeListener? = null
    var lowPower = false
        set(v) { field = v; for (p in panels) p.lowPower = v; if (v) heroRing.rotation = 0f }
    private var t = 0f

    private val barH = Hud.dp(52f).toInt()
    private val stripH = Hud.dp(30f).toInt()
    private val g = Hud.dp(10f).toInt()

    init {
        addView(topBar)
        for (p in panels) addView(p)
        // graph traces: cached textures slid between samples (see ScrollingPlot)
        addView(cpuGraph.plot); addView(ramGraph.plot); addView(netGraph.plot)
        addView(heroRing)
        // expensive drawing only (glow arcs, tick rings, dashed gridlines): see HudView.useLayer
        hero.useLayer(); temp.useLayer(); disks.useLayer()
        cpuGraph.useLayer(); ramGraph.useLayer(); netGraph.useLayer()
    }

    fun bind(d: DeviceState) {
        device = d
        topBar.selected = d
        heroRing.setAccent(d.accent)
        for (p in panels) when (p) {
            is HeroGaugeView -> p.bind(d)
            is CoreBarsView -> p.bind(d)
            is LineGraphView -> p.bind(d)
            is MemoryBarView -> p.bind(d)
            is TempView -> p.bind(d)
            is DiskView -> p.bind(d)
            is ProcessListView -> p.bind(d)
            is InfoStripView -> p.bind(d)
        }
    }

    fun onSample(d: DeviceState) {
        if (d !== device) { topBar.invalidate(); return }
        hero.onSample(); cores.onSample(); cpuGraph.onSample(); ramGraph.onSample(); netGraph.onSample()
        memory.onSample(); temp.onSample(); disks.onSample()
    }

    fun onProcs(d: DeviceState) { if (d === device) procs.onProcs() }

    fun onHistory(d: DeviceState) {
        if (d !== device) return
        cpuGraph.onHistory(); ramGraph.onHistory(); netGraph.onHistory()
        cpuGraph.invalidate(); ramGraph.invalidate(); netGraph.invalidate()
    }

    fun refreshAccent() {
        val d = device ?: return
        heroRing.setAccent(d.accent)
        for (p in panels) p.setAccent(d.accent)
        topBar.invalidate()
    }

    /** Hero gauge oval in this screen's coordinates (for the zoom transition). */
    fun heroBounds(out: RectF) {
        out.set(hero.gaugeOval)
        out.offset(hero.left.toFloat(), hero.top.toFloat())
    }

    /** Content slides out, device is rebound, content slides in from the other side. */
    fun switchTo(d: DeviceState, direction: Int) {
        val dx = Hud.dp(60f) * direction
        for (i in 1 until childCount) {
            val v = getChildAt(i)
            v.animate().translationX(-dx).alpha(0f).setDuration(130).setInterpolator(DecelerateInterpolator()).withEndAction(null).start()
        }
        postDelayed({
            bind(d)
            for (i in 1 until childCount) {
                val v = getChildAt(i)
                v.translationX = dx
                v.animate().translationX(0f).alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }, 135)
    }

    override fun step(nowNanos: Long, dt: Float): Int {
        var level = Level.IDLE
        for (p in panels) { val l = p.step(nowNanos, dt); if (l > level) level = l }
        if (!lowPower) {
            t += dt
            heroRing.rotation = (t * 10f) % 360f      // RenderNode property: no redraw
            if (level < Level.AMBIENT) level = Level.AMBIENT
        }
        return level
    }

    // ------------------------------------------------------------------ swipe

    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (e.y > barH && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 2f) swiping = true
            }
        }
        return swiping
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                if (abs(dx) > Hud.dp(70f)) swipeListener?.onSwipe(if (dx < 0) 1 else -1)
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    private fun place(v: View, l: Int, t: Int, r: Int, b: Int) {
        v.measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY))
        v.layout(l, t, r, b)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        place(topBar, 0, 0, w, barH)
        place(info, g, h - stripH, w - g, h)
        val top = barH + g / 2
        val bottom = h - stripH - g / 2
        if (w >= h) landscape(w, top, bottom) else portrait(w, top, bottom)
        placePlot(cpuGraph); placePlot(ramGraph); placePlot(netGraph)
        val ringPad = 0
        val s = minOf(hero.width, hero.height)
        val cx = hero.left + hero.width / 2; val cy = hero.top + hero.height / 2
        place(heroRing, cx - s / 2 - ringPad, cy - s / 2 - ringPad, cx + s / 2 + ringPad, cy + s / 2 + ringPad)
    }

    private val plotRect = android.graphics.Rect()

    private fun placePlot(g: LineGraphView) {
        g.plotBounds(plotRect)
        plotRect.offset(g.left, g.top)
        place(g.plot, plotRect.left, plotRect.top, plotRect.right, plotRect.bottom)
    }

    override fun hasOverlappingRendering() = false

    private fun landscape(w: Int, top: Int, bottom: Int) {
        val c1 = (w * 0.31f).toInt()
        val c3 = (w * 0.27f).toInt()
        val x1 = g; val x2 = x1 + c1 + g; val x4 = w - g; val x3 = x4 - c3
        val bodyH = bottom - top
        val heroH = minOf(c1, (bodyH * 0.62f).toInt())
        place(hero, x1, top, x1 + c1, top + heroH)
        place(cores, x1, top + heroH + g, x1 + c1, bottom)
        val memH = (bodyH * 0.2f).toInt().coerceIn(Hud.dp(92f).toInt(), Hud.dp(120f).toInt())
        val gh = (bodyH - memH - g * 3) / 3
        var y = top
        place(cpuGraph, x2, y, x3 - g, y + gh); y += gh + g
        place(ramGraph, x2, y, x3 - g, y + gh); y += gh + g
        place(netGraph, x2, y, x3 - g, y + gh); y += gh + g
        place(memory, x2, y, x3 - g, bottom)
        val tH = (bodyH * 0.3f).toInt()
        val dH = (bodyH * 0.3f).toInt()
        place(temp, x3, top, x4, top + tH)
        place(disks, x3, top + tH + g, x4, top + tH + g + dH)
        place(procs, x3, top + tH + dH + g * 2, x4, bottom)
    }

    private fun portrait(w: Int, top: Int, bottom: Int) {
        val bodyH = bottom - top
        val half = (w - g * 3) / 2
        val x1 = g; val x2 = g * 2 + half
        val heroS = minOf(half, (bodyH * 0.3f).toInt())
        place(hero, x1, top, x1 + half, top + heroS)
        val tH = heroS / 2 - g / 2
        place(temp, x2, top, w - g, top + tH)
        place(cores, x2, top + tH + g, w - g, top + heroS)
        var y = top + heroS + g
        val gh = (bodyH * 0.13f).toInt()
        place(cpuGraph, x1, y, w - g, y + gh); y += gh + g
        place(ramGraph, x1, y, w - g, y + gh); y += gh + g
        place(netGraph, x1, y, w - g, y + gh); y += gh + g
        val memH = Hud.dp(96f).toInt()
        place(memory, x1, y, w - g, y + memH); y += memH + g
        place(disks, x1, y, x1 + half, bottom)
        place(procs, x2, y, w - g, bottom)
    }
}
