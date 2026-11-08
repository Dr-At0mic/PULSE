// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.core

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Activity level a widget reports each frame. The driver picks the frame rate from the max. */
object Level {
    const val IDLE = 0      // nothing moving: the frame loop may stop
    const val AMBIENT = 1   // only slow decorative motion: 30 fps is plenty
    const val ACTIVE = 2    // value transitions / gestures: full 60 fps
}

interface Animated {
    /** Advance by [dt] seconds. Return a [Level]. Must not allocate. */
    fun step(nowNanos: Long, dt: Float): Int
}

/**
 * The single Choreographer frame callback for the whole activity.
 *
 * Widgets don't own animators; they register here and get stepped once per vsync.
 * - Any ACTIVE widget -> 60 fps.
 * - Only AMBIENT widgets -> every other vsync (30 fps): slow drift doesn't need more.
 * - Low-power mode -> 30 fps cap, and widgets drop their ambient motion entirely.
 * - Everything IDLE -> the callback is not re-posted. Zero CPU until [kick].
 */
class FrameDriver : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val items = ArrayList<Animated>(32)
    private var running = false
    private var lastNanos = 0L
    private var lastLevel = Level.ACTIVE
    private var parity = false
    var enabled = false
        set(v) { field = v; if (v) kick() else stop() }
    var lowPower = false

    fun add(a: Animated) { if (!items.contains(a)) items.add(a); kick() }
    fun remove(a: Animated) { items.remove(a) }

    /** Call when something changed (new data, touch) so the loop restarts if it was idle. */
    fun kick() {
        if (!enabled || running) return
        running = true
        lastNanos = 0L
        lastLevel = Level.ACTIVE
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val throttle = lowPower || lastLevel == Level.AMBIENT
        if (throttle && lastNanos != 0L) {
            parity = !parity
            if (parity) { choreographer.postFrameCallback(this); return }
        }
        val dt = if (lastNanos == 0L) 1f / 60f else min((frameTimeNanos - lastNanos) / 1e9f, 0.1f)
        lastNanos = frameTimeNanos
        var level = Level.IDLE
        var i = 0
        while (i < items.size) {               // index loop: no iterator allocation
            val l = items[i].step(frameTimeNanos, dt)
            if (l > level) level = l
            i++
        }
        lastLevel = level
        if (level == Level.IDLE) running = false else choreographer.postFrameCallback(this)
    }
}

/** Eased transition toward a target (ease-out cubic). Used for gauges and readouts. */
class Smooth(initial: Float = 0f, var duration: Float = 0.5f) {
    var value = initial
        private set
    var target = initial
        private set
    private var from = initial
    private var t = 1f

    val moving get() = t < 1f

    fun set(v: Float, animate: Boolean = true) {
        if (v.isNaN()) return
        if (!animate) { value = v; target = v; from = v; t = 1f; return }
        if (v == target && t >= 1f) return
        from = value; target = v; t = 0f
    }

    fun snap(v: Float) = set(v, false)

    /** Returns true if the value changed this frame. */
    fun step(dt: Float): Boolean {
        if (t >= 1f) return false
        t = min(1f, t + dt / duration)
        val u = 1f - t
        value = from + (target - from) * (1f - u * u * u)
        return true
    }
}

/**
 * Damped spring (semi-implicit Euler, sub-stepped). Slightly under-damped so bars
 * overshoot a touch and settle, which reads as "physical" rather than "tweened".
 */
class Spring(initial: Float = 0f, private val stiffness: Float = 210f, dampingRatio: Float = 0.72f) {
    private val damping = 2f * sqrt(stiffness) * dampingRatio
    var value = initial
    var target = initial
    var velocity = 0f
    val settled get() = velocity == 0f && value == target

    fun step(dt: Float): Boolean {
        if (settled) return false
        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, 1f / 120f)
            val force = stiffness * (target - value) - damping * velocity
            velocity += force * h
            value += velocity * h
            remaining -= h
        }
        if (abs(velocity) < 0.02f && abs(target - value) < 0.02f) { value = target; velocity = 0f }
        return true
    }
}

/** Tiny deterministic PRNG for glitch offsets (java.util.Random would be fine but this never allocates). */
class Lcg(private var seed: Int = 0x2545F491) {
    fun next(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 8) and 0xFFFF) / 65535f
    }
}
