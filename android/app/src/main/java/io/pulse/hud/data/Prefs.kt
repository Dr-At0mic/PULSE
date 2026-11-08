// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.data

import android.content.Context

/** SharedPreferences-backed settings. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pulse", Context.MODE_PRIVATE)

    var hubUrl: String
        get() = sp.getString("hub", "") ?: ""
        set(v) = sp.edit().putString("hub", v.trim()).apply()
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()
    var cpuAlert: Float
        get() = sp.getFloat("cpu_alert", 90f)
        set(v) = sp.edit().putFloat("cpu_alert", v).apply()
    var ramAlert: Float
        get() = sp.getFloat("ram_alert", 90f)
        set(v) = sp.edit().putFloat("ram_alert", v).apply()
    var tempAlert: Float
        get() = sp.getFloat("temp_alert", 85f)
        set(v) = sp.edit().putFloat("temp_alert", v).apply()
    var lowPower: Boolean
        get() = sp.getBoolean("low_power", false)
        set(v) = sp.edit().putBoolean("low_power", v).apply()
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_on", true)
        set(v) = sp.edit().putBoolean("keep_on", v).apply()

    /** Per-device accent override, 0 = use the agent's colour. */
    fun accentFor(id: String): Int = sp.getInt("accent_$id", 0)
    fun setAccentFor(id: String, color: Int) = sp.edit().putInt("accent_$id", color).apply()

    val configured get() = hubUrl.isNotEmpty() && token.isNotEmpty()

    /** "192.168.1.10" -> "ws://192.168.1.10:8765/client". */
    fun clientUrl(): String {
        var u = hubUrl.trim().trimEnd('/')
        if (!u.startsWith("ws://") && !u.startsWith("wss://")) u = "ws://$u"
        val hostPart = u.substringAfter("://")
        if (!hostPart.contains(':')) u += ":8765"
        if (!u.endsWith("/client")) u += "/client"
        return u
    }
}
