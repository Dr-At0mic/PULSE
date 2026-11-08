// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.core

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import io.pulse.hud.R

/**
 * Design tokens for the PULSE console. Every colour in the app comes from here.
 *
 * Palette: near-black void, one neon accent per device, orange for warning, red for
 * critical. Text sits on the void at >= 4.5:1 contrast (TEXT ~17:1, LABEL ~6.4:1).
 */
object Hud {
    const val BG = 0xFF05070D.toInt()
    const val PANEL = 0xFF0A101C.toInt()
    const val PANEL_HI = 0xFF0F1828.toInt()
    const val TEXT = 0xFFE6F1FF.toInt()
    const val LABEL = 0xFF8394AE.toInt()
    const val DIM = 0xFF3A4760.toInt()
    const val TRACK = 0xFF141D2E.toInt()
    const val GRID = 0xFF0E1626.toInt()
    const val WARN = 0xFFFF8A1F.toInt()
    const val CRIT = 0xFFFF2E4D.toInt()
    const val OFFLINE = 0xFF56627A.toInt()
    /** Secondary series colour (e.g. upload) that reads against every accent. */
    const val ICE = 0xFFB9CCF0.toInt()

    const val CYAN = 0xFF00F0FF.toInt()
    const val MAGENTA = 0xFFFF2EC4.toInt()
    const val LIME = 0xFFB6FF00.toInt()
    const val AMBER = 0xFFFFB000.toInt()
    val ACCENTS = intArrayOf(CYAN, MAGENTA, LIME, AMBER)
    val ACCENT_NAMES = arrayOf("CYAN", "MAGENTA", "LIME", "AMBER")

    var density = 2f
        private set
    var scaled = 2f
        private set
    lateinit var mono: Typeface
        private set
    lateinit var monoBold: Typeface
        private set

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        density = dm.density
        scaled = dm.density * ctx.resources.configuration.fontScale.coerceIn(0.85f, 1.3f)
        mono = ctx.resources.getFont(R.font.jbmono_regular)
        monoBold = ctx.resources.getFont(R.font.jbmono_bold)
    }

    fun dp(v: Float) = v * density
    fun dp(v: Int) = v * density
    fun sp(v: Float) = v * scaled

    // ---- paint factories (only ever called from constructors / onSizeChanged)
    fun fill(color: Int = TEXT) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    fun stroke(width: Float, color: Int = TEXT, round: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
        if (round) { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    }

    /** Small uppercase, letter-spaced HUD label. */
    fun label(sizeSp: Float = 10f, color: Int = LABEL) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = mono
        textSize = sp(sizeSp)
        this.color = color
        letterSpacing = 0.16f
    }

    /** Monospaced numbers: digits are all the same width, so values never jitter. */
    fun number(sizeSp: Float, bold: Boolean = true, color: Int = TEXT) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = if (bold) monoBold else mono
            textSize = sp(sizeSp)
            this.color = color
            fontFeatureSettings = "tnum"
        }

    // ---- colour math (allocation free)
    fun alpha(color: Int, a: Float): Int {
        val na = (Color.alpha(color) * a.coerceIn(0f, 1f)).toInt()
        return (color and 0x00FFFFFF) or (na shl 24)
    }

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val aa = a ushr 24; val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val ba = b ushr 24; val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        return ((aa + (ba - aa) * u).toInt() shl 24) or ((ar + (br - ar) * u).toInt() shl 16) or
            ((ag + (bg - ag) * u).toInt() shl 8) or (ab + (bb - ab) * u).toInt()
    }

    /** Pulls a colour toward its own grey (t=1 fully grey) for offline devices. */
    fun desaturate(color: Int, t: Float): Int {
        if (t <= 0f) return color
        val r = (color shr 16) and 0xFF; val g = (color shr 8) and 0xFF; val b = color and 0xFF
        val l = ((r * 0.3f + g * 0.59f + b * 0.11f) * 0.8f).toInt()
        val grey = (color and 0xFF000000.toInt()) or (l shl 16) or (l shl 8) or l
        return lerpColor(color, grey, t)
    }

    /**
     * Accent below warn, orange from warn, red from crit. Orange -> red blends smoothly; the
     * accent -> orange step uses a very short band because RGB blends between complementary
     * hues (cyan -> orange) pass through a muddy grey-beige.
     */
    fun level(accent: Int, value: Float, warn: Float, crit: Float): Int = when {
        value.isNaN() -> accent
        value >= crit -> CRIT
        value >= warn -> lerpColor(WARN, CRIT, (value - warn) / (crit - warn))
        value >= warn - 1.5f -> lerpColor(accent, WARN, (value - (warn - 1.5f)) / 1.5f)
        else -> accent
    }

    /** Cool cyan -> amber -> hot red for temperatures. */
    fun heat(celsius: Float): Int = when {
        celsius.isNaN() -> OFFLINE
        celsius < 45f -> CYAN
        celsius < 65f -> lerpColor(CYAN, AMBER, (celsius - 45f) / 20f)
        celsius < 85f -> lerpColor(AMBER, CRIT, (celsius - 65f) / 20f)
        else -> CRIT
    }

    fun parseColor(s: String?, fallback: Int): Int =
        try { if (s.isNullOrEmpty()) fallback else Color.parseColor(s) } catch (e: IllegalArgumentException) { fallback }
}
