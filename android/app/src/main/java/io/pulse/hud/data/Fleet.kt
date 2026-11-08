// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.data

import android.os.SystemClock
import io.pulse.hud.core.Hud
import org.json.JSONArray
import org.json.JSONObject

/** Link state between the tablet and the hub. */
object Link {
    const val OFF = 0
    const val CONNECTING = 1
    const val UP = 2
    const val DOWN = 3
    const val AUTH_FAILED = 4
}

interface FleetListener {
    fun onFleetChanged() {}
    fun onSample(d: DeviceState) {}
    fun onStatus(d: DeviceState) {}
    fun onProcs(d: DeviceState) {}
    fun onHistory(d: DeviceState) {}
    fun onLink(state: Int) {}
    fun onAlertsChanged() {}
}

/**
 * All devices plus link state. Frames are parsed off the main thread (in PulseClient)
 * and applied here on the main thread, so views never see half-updated state.
 */
class Fleet(private val prefs: Prefs) {
    val devices = ArrayList<DeviceState>()
    private val byId = HashMap<String, DeviceState>()
    private val listeners = ArrayList<FleetListener>()
    var link = Link.OFF
        private set
    var hubNodes = -1
        private set
    var welcomed = false
        private set

    fun addListener(l: FleetListener) { listeners.add(l) }
    fun get(id: String): DeviceState? = byId[id]
    fun indexOf(d: DeviceState) = devices.indexOf(d)
    val onlineCount: Int
        get() { var n = 0; for (i in 0 until devices.size) if (devices[i].online) n++; return n }

    fun setLink(state: Int) {
        if (state == link) return
        link = state
        if (state != Link.UP) {
            welcomed = false
            // without the hub we know nothing: show every node as lost
            for (d in devices) if (d.online) { d.online = false; listeners.forEach { it.onStatus(d) } }
        }
        listeners.forEach { it.onLink(state) }
    }

    private fun device(id: String): DeviceState {
        byId[id]?.let { return it }
        val d = DeviceState(id)
        d.agentAccent = Hud.ACCENTS[devices.size % Hud.ACCENTS.size]
        d.accent = effectiveAccent(d)
        byId[id] = d
        devices.add(d)
        return d
    }

    fun effectiveAccent(d: DeviceState): Int {
        val o = prefs.accentFor(d.id)
        return if (o != 0) o else d.agentAccent
    }

    fun refreshAccents() {
        for (d in devices) d.accent = effectiveAccent(d)
        listeners.forEach { it.onFleetChanged() }
    }

    fun apply(o: JSONObject) {
        val id = o.optString("id")
        when (o.optString("t")) {
            "w" -> { hubNodes = o.optInt("n", 0); welcomed = true; listeners.forEach { it.onLink(link) } }
            "i" -> applyInfo(device(id), o)
            "st" -> {
                val d = byId[id] ?: return
                setOnline(d, o.optBoolean("on"))
            }
            "o" -> {
                val d = byId[id] ?: return
                applyCore(d, o)
                d.cpuHist.push(d.cpu); d.ramHist.push(d.ram)
                d.upHist.push(d.netUp); d.downHist.push(d.netDown); d.tempHist.push(d.temp)
                d.markSample()
                sampled(d)
            }
            "m" -> {
                val d = byId[id] ?: return
                applyFull(d, o)
                d.cpuHist.push(d.cpu); d.ramHist.push(d.ram)
                d.upHist.push(d.netUp); d.downHist.push(d.netDown); d.tempHist.push(d.temp)
                d.markSample()
                sampled(d)
            }
            "p" -> {
                val d = byId[id] ?: return
                val ps = o.optJSONArray("ps") ?: return
                d.procCount = minOf(ps.length(), DeviceState.MAX_PROCS)
                for (i in 0 until d.procCount) {
                    val row = ps.optJSONArray(i) ?: continue
                    d.procNames[i] = row.optString(0, "?")
                    d.procCpu[i] = row.optDouble(1, 0.0).toFloat()
                    d.procMem[i] = row.optInt(2, 0)
                }
                d.procSeq++
                listeners.forEach { it.onProcs(d) }
            }
            "hs" -> {
                val d = byId[id] ?: return
                fill(d.cpuHist, o.optJSONArray("c"))
                fill(d.ramHist, o.optJSONArray("r"))
                if (o.has("nu")) fill(d.upHist, o.optJSONArray("nu"))
                if (o.has("nd")) fill(d.downHist, o.optJSONArray("nd"))
                if (o.has("tp")) fill(d.tempHist, o.optJSONArray("tp"))
                d.sampleAt = SystemClock.uptimeMillis() - d.interval.toLong()   // history is fully "settled"
                listeners.forEach { it.onHistory(d) }
            }
        }
    }

    private fun fill(r: Ring, a: JSONArray?) {
        a ?: return
        r.clear()
        for (i in 0 until a.length()) r.push(if (a.isNull(i)) Float.NaN else a.optDouble(i).toFloat())
    }

    private fun applyInfo(d: DeviceState, o: JSONObject) {
        val first = !d.hasInfo
        d.hasInfo = true
        d.name = o.optString("nm", d.id.uppercase()).uppercase()
        d.agentAccent = Hud.parseColor(o.optString("ac"), d.agentAccent)
        d.accent = effectiveAccent(d)
        d.host = o.optString("hn")
        d.os = o.optString("os")
        d.cpuModel = o.optString("cpu")
        d.cores = o.optInt("cores")
        d.ramTotal = o.optDouble("rt", 0.0).toFloat()
        d.setUptime(o.optLong("up"))
        listeners.forEach { it.onFleetChanged() }
        if (o.has("on")) setOnline(d, o.optBoolean("on")) else if (first) setOnline(d, true)
    }

    private fun setOnline(d: DeviceState, on: Boolean) {
        if (d.online == on) return
        d.online = on
        if (!on) { d.alerting = false; d.alertSince = 0 }
        listeners.forEach { it.onStatus(d) }
        listeners.forEach { it.onAlertsChanged() }
    }

    private fun f(o: JSONObject, k: String, def: Float = 0f): Float =
        if (!o.has(k) || o.isNull(k)) def else o.optDouble(k, def.toDouble()).toFloat()

    private fun applyCore(d: DeviceState, o: JSONObject) {
        d.cpu = f(o, "c")
        d.ram = f(o, "r")
        d.netUp = f(o, "nu")
        d.netDown = f(o, "nd")
        d.temp = f(o, "tp", Float.NaN)
        if (!d.online) setOnline(d, true)
    }

    private fun applyFull(d: DeviceState, o: JSONObject) {
        applyCore(d, o)
        o.optJSONArray("cc")?.let { cc ->
            d.coreCount = minOf(cc.length(), DeviceState.MAX_CORES)
            for (i in 0 until d.coreCount) d.coreVals[i] = cc.optDouble(i, 0.0).toFloat()
        }
        d.freqMhz = if (o.isNull("cf")) -1 else o.optInt("cf", -1)
        d.ramUsed = f(o, "ru")
        d.ramTotal = f(o, "rt", d.ramTotal)
        d.ramCached = f(o, "rc", -1f)
        d.swapUsed = f(o, "s")
        d.swapTotal = f(o, "st")
        o.optJSONArray("d")?.let { ds ->
            d.diskCount = minOf(ds.length(), DeviceState.MAX_DISKS)
            for (i in 0 until d.diskCount) {
                val x = ds.optJSONObject(i) ?: continue
                d.diskNames[i] = x.optString("m")
                d.diskPct[i] = f(x, "p")
                d.diskUsed[i] = f(x, "u")
                d.diskTotal[i] = f(x, "t")
            }
        }
        d.diskRead = f(o, "dr")
        d.diskWrite = f(o, "dw")
        val g = o.optJSONObject("g")
        if (g != null) {
            d.gpuUtil = f(g, "u"); d.gpuMem = f(g, "vm"); d.gpuMemTotal = f(g, "vt"); d.gpuTemp = f(g, "tp", Float.NaN)
        } else d.gpuUtil = Float.NaN
        val l = o.optJSONArray("l")
        d.hasLoad = l != null
        if (l != null) for (i in 0 until minOf(3, l.length())) d.load[i] = l.optDouble(i, 0.0).toFloat()
    }

    private fun sampled(d: DeviceState) {
        evaluateAlert(d)
        listeners.forEach { it.onSample(d) }
    }

    /** Alert when CPU / RAM / temperature stay over threshold for 5+ seconds. */
    private fun evaluateAlert(d: DeviceState) {
        val kind = when {
            d.cpu > prefs.cpuAlert -> 1
            d.ram > prefs.ramAlert -> 2
            !d.temp.isNaN() && d.temp > prefs.tempAlert -> 3
            else -> 0
        }
        val now = SystemClock.uptimeMillis()
        val was = d.alerting
        if (kind == 0) { d.alertSince = 0; d.alerting = false }
        else {
            if (d.alertSince == 0L) d.alertSince = now
            d.alertKind = kind
            d.alertValue = when (kind) { 1 -> d.cpu; 2 -> d.ram; else -> d.temp }
            d.alerting = now - d.alertSince >= 4900
        }
        if (was != d.alerting) listeners.forEach { it.onAlertsChanged() }
    }
}
