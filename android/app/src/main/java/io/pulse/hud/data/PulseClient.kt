// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.data

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The single WebSocket to the hub.
 *
 * - JSON is parsed on OkHttp's reader thread (org.json, no reflection) and handed to the
 *   main thread already parsed, so the UI thread only copies a few numbers per frame.
 * - Reconnects with exponential backoff (1 s -> 30 s).
 * - [stop] is called from onStop: no socket, no wakeups while the screen is off.
 */
class PulseClient(private val fleet: Fleet, private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)      // detects a dead hub within ~10 s
        .retryOnConnectionFailure(false)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var wanted = false
    private var generation = 0
    private var backoffMs = 1000L
    var mode = "overview"
        private set

    private val reconnect = Runnable { if (wanted) connect() }

    fun start() {
        if (wanted) return
        wanted = true
        backoffMs = 1000L
        connect()
    }

    fun stop() {
        wanted = false
        generation++
        main.removeCallbacks(reconnect)
        ws?.close(1000, "bye")
        ws = null
        fleet.setLink(Link.OFF)
    }

    fun restart() { stop(); start() }

    fun subscribe(newMode: String) {
        mode = newMode
        ws?.send("{\"t\":\"sub\",\"mode\":\"$newMode\"}")
    }

    private fun connect() {
        if (!prefs.configured) { fleet.setLink(Link.OFF); return }
        val gen = ++generation
        val request = try {
            Request.Builder().url(prefs.clientUrl()).header("Authorization", "Bearer ${prefs.token}").build()
        } catch (e: IllegalArgumentException) {
            fleet.setLink(Link.DOWN); return
        }
        fleet.setLink(Link.CONNECTING)
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (gen != generation) return@post
                    backoffMs = 1000L
                    fleet.setLink(Link.UP)
                    if (mode != "overview") webSocket.send("{\"t\":\"sub\",\"mode\":\"$mode\"}")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = try { JSONObject(text) } catch (e: Exception) { return }
                main.post { if (gen == generation) fleet.apply(obj) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(gen, false)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                dropped(gen, response?.code == 401)
        })
    }

    private fun dropped(gen: Int, authFailed: Boolean) {
        main.post {
            if (gen != generation || !wanted) return@post
            ws = null
            fleet.setLink(if (authFailed) Link.AUTH_FAILED else Link.DOWN)
            main.removeCallbacks(reconnect)
            main.postDelayed(reconnect, backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }
}
