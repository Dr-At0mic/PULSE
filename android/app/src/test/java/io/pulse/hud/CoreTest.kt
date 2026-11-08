// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud

import io.pulse.hud.core.Fmt
import io.pulse.hud.core.Smooth
import io.pulse.hud.core.Spring
import io.pulse.hud.data.Ring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingTest {
    @Test fun keepsOnlyTheNewestCapacitySamples() {
        val r = Ring(120)
        for (i in 0 until 10_000) r.push(i.toFloat())     // an "hour" of pushes: no growth
        assertEquals(120, r.size)
        assertEquals(9_880f, r[0], 0f)
        assertEquals(9_999f, r[119], 0f)
        assertEquals(9_999f, r.last(), 0f)
    }

    @Test fun partialFillAndClear() {
        val r = Ring(4)
        r.push(1f); r.push(2f)
        assertEquals(2, r.size)
        assertEquals(1f, r[0], 0f)
        assertEquals(2f, r.maxOfLast(10), 0f)
        r.clear()
        assertEquals(0, r.size)
        assertTrue(r.last().isNaN())
    }
}

class FmtTest {
    private val buf = CharArray(32)
    private fun s(end: Int) = String(buf, 0, end)

    @Test fun fixedOneDecimal() {
        assertEquals("57.8", s(Fmt.fixed1(buf, 0, 57.8f)))
        assertEquals("0.0", s(Fmt.fixed1(buf, 0, 0.04f)))
        assertEquals("100.0", s(Fmt.fixed1(buf, 0, 99.96f)))
        assertEquals("-3.5", s(Fmt.fixed1(buf, 0, -3.5f)))
        assertEquals("--", s(Fmt.fixed1(buf, 0, Float.NaN)))
    }

    @Test fun rates() {
        assertEquals("812 B/s", s(Fmt.rate(buf, 0, 812f)))
        assertEquals("50.8 KB/s", s(Fmt.rate(buf, 0, 52_000f)))
        assertEquals("1.75 MB/s", s(Fmt.rate(buf, 0, 1_830_000f)))
        assertEquals("128 MB/s", s(Fmt.rate(buf, 0, 128f * 1024 * 1024)))
    }

    @Test fun uptimeAndClock() {
        assertEquals("1d 00:00:01", s(Fmt.uptime(buf, 0, 86_401)))
        assertEquals("04:12:33", s(Fmt.uptime(buf, 0, 4 * 3600 + 12 * 60 + 33)))
        assertEquals("09:05:07", s(Fmt.clock(buf, 0, 9, 5, 7)))
    }
}

class AnimTest {
    @Test fun smoothReachesTargetExactly() {
        val a = Smooth(0f, 0.5f)
        a.set(80f)
        var frames = 0
        while (a.step(1f / 60f)) frames++
        assertEquals(80f, a.value, 0f)
        assertTrue("about 30 frames for 500 ms", frames in 28..31)
    }

    @Test fun springSettles() {
        val s = Spring(0f)
        s.target = 64f
        var t = 0f
        while (s.step(1f / 60f)) { t += 1f / 60f; if (t > 5f) break }
        assertTrue("settles within 2 s", t < 2f)
        assertEquals(64f, s.value, 0f)
    }
}
