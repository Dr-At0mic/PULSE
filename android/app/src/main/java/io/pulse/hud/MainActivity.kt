// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud

import android.app.Activity
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import io.pulse.hud.core.Animated
import io.pulse.hud.core.FrameDriver
import io.pulse.hud.core.Hud
import io.pulse.hud.core.Level
import io.pulse.hud.data.DeviceState
import io.pulse.hud.data.Fleet
import io.pulse.hud.data.FleetListener
import io.pulse.hud.data.Link
import io.pulse.hud.data.Prefs
import io.pulse.hud.data.PulseClient
import io.pulse.hud.screen.DetailScreen
import io.pulse.hud.screen.OverviewScreen
import io.pulse.hud.screen.SettingsScreen
import io.pulse.hud.widget.AlertBannerView
import io.pulse.hud.widget.BootView
import io.pulse.hud.widget.DeviceCardView
import io.pulse.hud.widget.GridBackgroundView
import io.pulse.hud.widget.OverlayView
import io.pulse.hud.widget.ScanBeamView
import io.pulse.hud.widget.TopBarView

/**
 * Single activity, plain framework (no AndroidX). Screens are Views swapped in one
 * FrameLayout so transitions can span both of them. One FrameDriver steps everything.
 */
class MainActivity : Activity(), FleetListener, DeviceCardView.Listener, TopBarView.Listener {
    private lateinit var prefs: Prefs
    private lateinit var fleet: Fleet
    private lateinit var client: PulseClient
    private val driver = FrameDriver()

    private lateinit var root: FrameLayout
    private lateinit var grid: GridBackgroundView
    private lateinit var beam: ScanBeamView
    private lateinit var overview: OverviewScreen
    private lateinit var detail: DetailScreen
    private lateinit var settings: SettingsScreen
    private lateinit var banner: AlertBannerView
    private lateinit var overlay: OverlayView
    private var boot: BootView? = null

    private var screen = OVERVIEW
    private var settingsReturn = OVERVIEW
    private val r1 = RectF()
    private val r2 = RectF()
    private val r3 = RectF()
    private val r4 = RectF()

    /** The one Animated registered with the driver: steps whatever is on screen. */
    private val stepper = object : Animated {
        override fun step(nowNanos: Long, dt: Float): Int {
            var l = grid.step(nowNanos, dt)
            l = maxOf(l, beam.step(nowNanos, dt))
            val morphing = overlay.busy
            if (screen == OVERVIEW || morphing) l = maxOf(l, overview.step(nowNanos, dt))
            if (screen == DETAIL || morphing) l = maxOf(l, detail.step(nowNanos, dt))
            l = maxOf(l, banner.step(nowNanos, dt))
            l = maxOf(l, overlay.step(dt))
            boot?.let { l = maxOf(l, it.step(nowNanos, dt)) }
            return if (screen == SETTINGS && boot == null && !morphing) minOf(l, Level.AMBIENT) else l
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hud.init(this)
        prefs = Prefs(this)
        fleet = Fleet(prefs)
        client = PulseClient(fleet, prefs)

        root = FrameLayout(this)
        grid = GridBackgroundView(this)
        beam = ScanBeamView(this)
        overview = OverviewScreen(this, fleet, prefs).apply { cardListener = this@MainActivity; topBar.listener = this@MainActivity }
        detail = DetailScreen(this, fleet, prefs).apply {
            topBar.listener = this@MainActivity
            swipeListener = DetailScreen.SwipeListener { dir -> swipe(dir) }
            visibility = View.INVISIBLE        // laid out (so the hero position is known) but never drawn
        }
        settings = SettingsScreen(this, fleet, prefs, onClose = ::closeSettings, onApply = ::applySettings).apply { visibility = View.GONE }
        banner = AlertBannerView(this, fleet).apply { onTap = ::jumpTo }
        overlay = OverlayView(this)

        val mp = FrameLayout.LayoutParams.MATCH_PARENT
        root.addView(grid, FrameLayout.LayoutParams(mp, mp))
        root.addView(beam, FrameLayout.LayoutParams(mp, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(overview, FrameLayout.LayoutParams(mp, mp))
        root.addView(detail, FrameLayout.LayoutParams(mp, mp))
        root.addView(banner, FrameLayout.LayoutParams(Hud.dp(470f).toInt(), Hud.dp(40f).toInt()))
        placeBanner()
        root.addView(overlay, FrameLayout.LayoutParams(mp, mp))
        root.addView(settings, FrameLayout.LayoutParams(mp, mp))
        setContentView(root)
        root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) root.post { placeBanner() } }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            settings.setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.ime()).bottom)
                insets
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }

        fleet.addListener(this)
        driver.add(stepper)
        applyDisplayPrefs()
        startBoot()
    }

    override fun onStart() {
        super.onStart()
        immersive()
        driver.enabled = true
        client.start()          // reconnect on resume / screen on
    }

    override fun onStop() {
        client.stop()           // background or screen off: no socket, no frames
        driver.enabled = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Deprecated("Pre-API 33 back handling")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = handleBack()

    // ---------------------------------------------------------------- boot

    private fun startBoot() {
        val label = if (prefs.configured) prefs.clientUrl().removeSuffix("/client") else "<NO HUB SET>"
        val b = BootView(this, fleet, label, prefs.lowPower)
        b.onDone = {
            root.removeView(b)
            boot = null
            if (!prefs.configured) showSettings() else overview.enterAll()
        }
        boot = b
        root.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        driver.kick()
    }

    // ---------------------------------------------------------------- navigation

    override fun onCardTapped(card: DeviceCardView) = openDetail(card)

    private fun openDetail(card: DeviceCardView) {
        if (overlay.busy || screen != OVERVIEW) return
        val d = card.device
        detail.bind(d)
        beam.setAccent(d.accent)
        client.subscribe("detail:${d.id}")
        overview.cardBounds(card, r1, r2)
        detail.heroBounds(r4)
        r3.set(0f, 0f, root.width.toFloat(), root.height.toFloat())
        screen = DETAIL
        placeBanner()
        detail.alpha = 0f
        detail.visibility = View.VISIBLE
        overview.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(320).start()
        detail.animate().alpha(1f).setStartDelay(240).setDuration(220).start()
        overlay.morph(r1, r2, r3, r4, d.accent, d.cpu, back = false) { overview.visibility = View.INVISIBLE }
        driver.kick()
    }

    private fun closeDetail() {
        if (overlay.busy || screen != DETAIL) return
        val d = detail.device
        screen = OVERVIEW
        placeBanner()
        client.subscribe("overview")
        beam.setAccent(Hud.CYAN)
        overview.visibility = View.VISIBLE
        overview.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(300).start()
        detail.animate().alpha(0f).setStartDelay(0).setDuration(160).withEndAction { if (screen != DETAIL) detail.visibility = View.INVISIBLE }.start()
        val card = d?.let { overview.card(it) }
        if (d != null && card != null) {
            overview.cardBounds(card, r1, r2)
            detail.heroBounds(r4)
            r3.set(0f, 0f, root.width.toFloat(), root.height.toFloat())
            overlay.morph(r1, r2, r3, r4, d.accent, d.cpu, back = true) {}
        }
        driver.kick()
    }

    private fun switchDevice(d: DeviceState, direction: Int) {
        if (d === detail.device) return
        detail.switchTo(d, direction)
        overlay.wash(d.accent, direction > 0)
        beam.setAccent(d.accent)
        client.subscribe("detail:${d.id}")
        driver.kick()
    }

    private fun swipe(direction: Int) {
        val cur = detail.device ?: return
        val n = fleet.devices.size
        if (n < 2) return
        val i = (fleet.indexOf(cur) + direction + n) % n
        switchDevice(fleet.devices[i], direction)
    }

    private fun jumpTo(d: DeviceState) {
        when (screen) {
            OVERVIEW -> overview.card(d)?.let(::openDetail)
            DETAIL -> switchDevice(d, if (fleet.indexOf(d) > fleet.indexOf(detail.device ?: d)) 1 else -1)
        }
    }

    /** The banner never covers live data: in the overview it docks into the empty centre of
     *  the top bar; in the detail view (where the centre holds the device chips) it docks over
     *  the bottom info strip. */
    private fun placeBanner() {
        val lp = banner.layoutParams as FrameLayout.LayoutParams
        if (screen == DETAIL) {
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            lp.topMargin = 0; lp.bottomMargin = Hud.dp(1f).toInt()
            lp.height = Hud.dp(30f).toInt()
        } else {
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            lp.topMargin = Hud.dp(6f).toInt(); lp.bottomMargin = 0
            lp.height = Hud.dp(40f).toInt()
        }
        // fit between the logo and the settings gear, whatever the orientation
        val w = root.width
        lp.width = if (w == 0) Hud.dp(470f).toInt() else minOf(Hud.dp(470f).toInt(), w - Hud.dp(if (screen == DETAIL) 40f else 130f).toInt())
        banner.layoutParams = lp
    }

    private fun handleBack() {
        when {
            boot != null -> boot?.skip()
            screen == SETTINGS -> settings.close()
            screen == DETAIL -> closeDetail()
            else -> moveTaskToBack(true)
        }
    }

    // TopBarView.Listener
    override fun onBack() = closeDetail()
    override fun onSettings() = showSettings()
    override fun onChip(d: DeviceState) {
        val cur = detail.device ?: return
        switchDevice(d, if (fleet.indexOf(d) > fleet.indexOf(cur)) 1 else -1)
    }

    private fun showSettings() {
        if (screen == SETTINGS) return
        settingsReturn = screen
        screen = SETTINGS
        settings.refresh()
        settings.alpha = 0f
        settings.visibility = View.VISIBLE
        settings.animate().alpha(1f).setDuration(180).start()
    }

    private fun closeSettings() {
        if (screen != SETTINGS) return
        screen = settingsReturn
        settings.animate().alpha(0f).setDuration(140).withEndAction { settings.visibility = View.GONE }.start()
        if (screen == OVERVIEW && overview.cards.isNotEmpty() && overview.cards[0].alpha < 1f) overview.enterAll()
        immersive()
        driver.kick()
    }

    private fun applySettings(linkChanged: Boolean) {
        applyDisplayPrefs()
        if (linkChanged) client.restart()
        driver.kick()
    }

    private fun applyDisplayPrefs() {
        val lp = prefs.lowPower
        driver.lowPower = lp
        grid.lowPower = lp
        beam.lowPower = lp
        overview.lowPower = lp
        detail.lowPower = lp
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    // ---------------------------------------------------------------- fleet events

    override fun onFleetChanged() {
        // device info arrives just after the link opens: refresh the "N NODES" status line
        if (screen == SETTINGS) settings.onLink()
        overview.syncCards()
        detail.refreshAccent()
        detail.device?.let { beam.setAccent(it.accent) }
        driver.kick()
    }

    override fun onSample(d: DeviceState) {
        overview.card(d)?.onSample()
        if (screen == DETAIL) detail.onSample(d)
        driver.kick()
    }

    override fun onStatus(d: DeviceState) {
        overview.card(d)?.onStatus()
        overview.topBar.invalidate()
        detail.topBar.invalidate()
        driver.kick()
    }

    override fun onProcs(d: DeviceState) { detail.onProcs(d); driver.kick() }

    override fun onHistory(d: DeviceState) {
        overview.card(d)?.onHistory()
        detail.onHistory(d)
        driver.kick()
    }

    override fun onLink(state: Int) {
        overview.topBar.invalidate()
        detail.topBar.invalidate()
        if (screen == SETTINGS) settings.onLink()
        if (state == Link.UP && screen == DETAIL) detail.device?.let { client.subscribe("detail:${it.id}") }
        overview.invalidate()
        driver.kick()
    }

    override fun onAlertsChanged() { banner.refresh(); driver.kick() }

    companion object {
        private const val OVERVIEW = 0
        private const val DETAIL = 1
        private const val SETTINGS = 2
    }
}
