// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.data

import android.os.SystemClock
import io.pulse.hud.core.Hud

/** Fixed-capacity float ring buffer. Memory is allocated once; pushing never allocates. */
class Ring(val capacity: Int) {
    private val data = FloatArray(capacity)
    private var head = 0
    var size = 0
        private set

    fun push(v: Float) {
        data[head] = v
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** i = 0 is the oldest retained sample, size-1 the newest. */
    operator fun get(i: Int): Float = data[(head - size + i + capacity * 2) % capacity]

    fun last(): Float = if (size == 0) Float.NaN else get(size - 1)
    fun clear() { size = 0; head = 0 }

    fun maxOfLast(n: Int): Float {
        var m = 0f
        val from = (size - n).coerceAtLeast(0)
        for (i in from until size) { val v = get(i); if (v > m) m = v }
        return m
    }
}

/** Everything the UI knows about one machine. Mutated only on the main thread. */
class DeviceState(val id: String) {
    var name = id.uppercase()
    var agentAccent = Hud.CYAN
    var accent = Hud.CYAN            // effective accent (tablet override or agent's)
    var online = false
    var hasInfo = false
    var hasData = false

    var host = ""
    var os = ""
    var cpuModel = ""
    var cores = 0
    var ramTotal = 0f
    private var uptimeBase = 0L
    private var uptimeAt = 0L

    // latest metrics
    var cpu = 0f
    val coreVals = FloatArray(MAX_CORES)
    var coreCount = 0
    var freqMhz = -1
    var ram = 0f
    var ramUsed = 0f
    var ramCached = -1f
    var swapUsed = 0f
    var swapTotal = 0f
    val diskNames = arrayOfNulls<String>(MAX_DISKS)
    val diskPct = FloatArray(MAX_DISKS)
    val diskUsed = FloatArray(MAX_DISKS)
    val diskTotal = FloatArray(MAX_DISKS)
    var diskCount = 0
    var diskRead = 0f
    var diskWrite = 0f
    var netUp = 0f
    var netDown = 0f
    var temp = Float.NaN
    var gpuUtil = Float.NaN
    var gpuMem = 0f
    var gpuMemTotal = 0f
    var gpuTemp = Float.NaN
    val load = FloatArray(3)
    var hasLoad = false

    val procNames = arrayOfNulls<String>(MAX_PROCS)
    val procCpu = FloatArray(MAX_PROCS)
    val procMem = IntArray(MAX_PROCS)
    var procCount = 0
    var procSeq = 0

    // history (fixed memory, however long the app runs)
    val cpuHist = Ring(HISTORY)
    val ramHist = Ring(HISTORY)
    val upHist = Ring(HISTORY)
    val downHist = Ring(HISTORY)
    val tempHist = Ring(HISTORY)

    /** uptimeMillis when the newest sample arrived, and the smoothed sample interval. */
    var sampleAt = 0L
    var interval = 1000f
    var seq = 0

    // alerting
    var alertSince = 0L
    var alerting = false
    var alertKind = 0          // 1 cpu, 2 ram, 3 temp
    var alertValue = 0f

    fun uptimeSeconds(): Long =
        if (uptimeAt == 0L) 0 else uptimeBase + (SystemClock.elapsedRealtime() - uptimeAt) / 1000

    fun setUptime(secs: Long) { uptimeBase = secs; uptimeAt = SystemClock.elapsedRealtime() }

    fun markSample() {
        val now = SystemClock.uptimeMillis()
        if (sampleAt != 0L) {
            val d = (now - sampleAt).toFloat()
            if (d in 200f..5000f) interval += (d - interval) * 0.2f
        }
        sampleAt = now
        seq++
        hasData = true
    }

    /** 0..1 progress from the newest sample toward the next one. Drives graph scrolling. */
    fun sampleProgress(now: Long): Float =
        if (sampleAt == 0L) 1f else ((now - sampleAt) / interval).coerceIn(0f, 1f)

    companion object {
        const val HISTORY = 120
        const val MAX_CORES = 64
        const val MAX_DISKS = 8
        const val MAX_PROCS = 5
    }
}
