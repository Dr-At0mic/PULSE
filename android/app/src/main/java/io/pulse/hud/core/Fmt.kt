// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.core

/**
 * Allocation-free number formatting into reusable CharArrays, for use inside onDraw().
 * (String.format / toString would allocate on every frame.)
 * Every function writes from index [start] and returns the new end index.
 */
object Fmt {
    private val DIGITS = "0123456789".toCharArray()

    fun str(buf: CharArray, start: Int, s: String): Int {
        var i = start
        for (c in s) { if (i >= buf.size) break; buf[i++] = c }
        return i
    }

    fun int(buf: CharArray, start: Int, value: Long, minDigits: Int = 1): Int {
        var v = value
        var i = start
        if (v < 0) { buf[i++] = '-'; v = -v }
        var n = 0
        var t = v
        do { n++; t /= 10 } while (t > 0)
        if (n < minDigits) n = minDigits
        var p = i + n - 1
        repeat(n) { buf[p--] = DIGITS[(v % 10).toInt()]; v /= 10 }
        return i + n
    }

    fun int(buf: CharArray, start: Int, value: Int, minDigits: Int = 1) = int(buf, start, value.toLong(), minDigits)

    /** "57.8" (one decimal). NaN -> "--". */
    fun fixed1(buf: CharArray, start: Int, value: Float): Int {
        if (value.isNaN()) { buf[start] = '-'; buf[start + 1] = '-'; return start + 2 }
        val tenths = Math.round(value * 10f).toLong()
        var i = start
        var t = tenths
        if (t < 0) { buf[i++] = '-'; t = -t }
        i = int(buf, i, t / 10)
        buf[i++] = '.'
        buf[i++] = DIGITS[(t % 10).toInt()]
        return i
    }

    /** Integer percent "57". */
    fun pct0(buf: CharArray, start: Int, value: Float): Int =
        if (value.isNaN()) str(buf, start, "--") else int(buf, start, Math.round(value))

    /** Bytes per second with three significant figures: "812 B/s", "52.0 KB/s", "1.83 MB/s". */
    fun rate(buf: CharArray, start: Int, bytesPerSec: Float, suffix: Boolean = true): Int {
        var i = size(buf, start, bytesPerSec)
        if (suffix) { buf[i++] = '/'; buf[i++] = 's' }
        return i
    }

    /** Byte size "1.83 MB". */
    fun size(buf: CharArray, start: Int, bytes: Float): Int {
        if (bytes.isNaN()) return str(buf, start, "--")
        var v = bytes
        var unit = 0
        while (v >= 1000f && unit < 4) { v /= 1024f; unit++ }
        var i = if (unit == 0 || v >= 100f) int(buf, start, Math.round(v))
        else if (v >= 10f) fixed1(buf, start, v)
        else fixed2(buf, start, v)
        buf[i++] = ' '
        when (unit) {
            0 -> buf[i++] = 'B'
            1 -> { buf[i++] = 'K'; buf[i++] = 'B' }
            2 -> { buf[i++] = 'M'; buf[i++] = 'B' }
            3 -> { buf[i++] = 'G'; buf[i++] = 'B' }
            else -> { buf[i++] = 'T'; buf[i++] = 'B' }
        }
        return i
    }

    fun fixed2(buf: CharArray, start: Int, value: Float): Int {
        val h = Math.round(value * 100f).toLong()
        var i = int(buf, start, h / 100)
        buf[i++] = '.'
        val f = (h % 100).toInt()
        buf[i++] = DIGITS[f / 10]
        buf[i++] = DIGITS[f % 10]
        return i
    }

    /** "16d 04:12:33" or "04:12:33". */
    fun uptime(buf: CharArray, start: Int, secs: Long): Int {
        var s = secs.coerceAtLeast(0)
        val d = s / 86400; s %= 86400
        var i = start
        if (d > 0) { i = int(buf, i, d); buf[i++] = 'd'; buf[i++] = ' ' }
        i = int(buf, i, s / 3600, 2); buf[i++] = ':'
        i = int(buf, i, (s % 3600) / 60, 2); buf[i++] = ':'
        return int(buf, i, s % 60, 2)
    }

    fun clock(buf: CharArray, start: Int, h: Int, m: Int, s: Int): Int {
        var i = int(buf, start, h, 2); buf[i++] = ':'
        i = int(buf, i, m, 2); buf[i++] = ':'
        return int(buf, i, s, 2)
    }
}
