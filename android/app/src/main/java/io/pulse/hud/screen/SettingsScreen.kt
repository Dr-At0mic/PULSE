// PULSE - LAN system monitor with a sci-fi tablet console
// Copyright (C) 2026 Anandhu Suresh
// SPDX-License-Identifier: GPL-3.0-or-later

package io.pulse.hud.screen

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.pulse.hud.core.Hud
import io.pulse.hud.data.Fleet
import io.pulse.hud.data.Link
import io.pulse.hud.data.Prefs

/** Settings: hub + token, alert thresholds, display options, per-device accent. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class SettingsScreen(
    ctx: Context,
    private val fleet: Fleet,
    private val prefs: Prefs,
    private val onClose: () -> Unit,
    private val onApply: (linkChanged: Boolean) -> Unit,
) : FrameLayout(ctx) {

    private val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val hub = field("192.168.1.10:8765", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    private val token = field("shared token", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    private val hubError = error()
    private val cpu = field("90", InputType.TYPE_CLASS_NUMBER)
    private val ram = field("90", InputType.TYPE_CLASS_NUMBER)
    private val temp = field("85", InputType.TYPE_CLASS_NUMBER)
    private val thresholdError = error()
    private val lowPower = HudToggle(ctx, "LOW-POWER MODE", "Stops ambient motion, caps rendering at 30 fps")
    private val keepOn = HudToggle(ctx, "KEEP SCREEN ON", "For a wall-mounted, always-on display")
    private val accents = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val linkStatus = label("", 10f, Hud.LABEL)

    init {
        setBackgroundColor(Hud.BG)
        isClickable = true
        val scroll = ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val pad = Hud.dp(24f).toInt()
        column.setPadding(pad, Hud.dp(16f).toInt(), pad, Hud.dp(48f).toInt())
        scroll.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(HudButton(ctx, "‹ BACK", Hud.LABEL) { close() }, LinearLayout.LayoutParams(Hud.dp(110f).toInt(), Hud.dp(44f).toInt()))
        header.addView(label("   SETTINGS // CONFIGURATION", 13f, Hud.TEXT))
        column.addView(header)

        section("HUB LINK")
        caption("Address of the hub on your LAN, e.g. 192.168.1.10:8765")
        column.addView(hub, fieldLp())
        caption("Shared token (client_token in the hub's config.yaml)")
        column.addView(token, fieldLp())
        column.addView(hubError)
        column.addView(HudButton(ctx, "SAVE & CONNECT", Hud.CYAN) { save() }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Hud.dp(48f).toInt()).apply { topMargin = Hud.dp(12f).toInt() })
        column.addView(linkStatus, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Hud.dp(8f).toInt() })

        section("ALERT THRESHOLDS")
        caption("Alert when a value stays above its threshold for 5 seconds")
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, pair) in listOf("CPU %" to cpu, "RAM %" to ram, "TEMP °C" to temp).withIndex()) {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(pair.first, 9f, Hud.LABEL))
            col.addView(pair.second, fieldLp())
            row.addView(col, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (i < 2) marginEnd = Hud.dp(12f).toInt() })
        }
        column.addView(row)
        column.addView(thresholdError)
        column.addView(HudButton(ctx, "APPLY THRESHOLDS", Hud.CYAN) { saveThresholds() }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Hud.dp(44f).toInt()).apply { topMargin = Hud.dp(10f).toInt() })

        section("DISPLAY")
        column.addView(lowPower, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Hud.dp(56f).toInt()))
        column.addView(keepOn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Hud.dp(56f).toInt()))
        lowPower.onChange = { prefs.lowPower = it; onApply(false) }
        keepOn.onChange = { prefs.keepScreenOn = it; onApply(false) }

        section("DEVICE ACCENTS")
        caption("AUTO uses the colour from the device's agent config")
        column.addView(accents)
    }

    override fun hasOverlappingRendering() = false

    /** Readable line length: a 660 dp column centred in landscape, full width in portrait. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // set before measuring children so the padding takes effect in this same pass
        val side = maxOf(Hud.dp(20f).toInt(), (MeasureSpec.getSize(widthMeasureSpec) - Hud.dp(660f).toInt()) / 2)
        if (column.paddingLeft != side) column.setPadding(side, column.paddingTop, side, column.paddingBottom)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun refresh() {
        hub.setText(prefs.hubUrl)
        token.setText(prefs.token)
        cpu.setText(prefs.cpuAlert.toInt().toString())
        ram.setText(prefs.ramAlert.toInt().toString())
        temp.setText(prefs.tempAlert.toInt().toString())
        lowPower.setChecked(prefs.lowPower, false)
        keepOn.setChecked(prefs.keepScreenOn, false)
        hubError.visibility = GONE
        thresholdError.visibility = GONE
        accents.removeAllViews()
        if (fleet.devices.isEmpty()) accents.addView(caption("No devices yet", add = false))
        for (d in fleet.devices) {
            accents.addView(SwatchRow(context, d.name, prefs.accentFor(d.id)) { color ->
                prefs.setAccentFor(d.id, color)
                fleet.refreshAccents()
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Hud.dp(52f).toInt()))
        }
        onLink()
    }

    fun onLink() {
        val (t, c) = when (fleet.link) {
            Link.UP -> "● LINK ESTABLISHED · ${fleet.devices.size} NODES" to 0xFF3DFFA8.toInt()
            Link.CONNECTING -> "● HANDSHAKE IN PROGRESS…" to Hud.AMBER
            Link.AUTH_FAILED -> "● TOKEN REJECTED BY HUB (HTTP 401)" to Hud.CRIT
            Link.DOWN -> "● HUB UNREACHABLE · RETRYING WITH BACKOFF" to Hud.CRIT
            else -> "● NOT CONFIGURED" to Hud.LABEL
        }
        linkStatus.text = t
        linkStatus.setTextColor(c)
    }

    private fun save() {
        val h = hub.text.toString().trim()
        val k = token.text.toString().trim()
        val err = when {
            h.isEmpty() -> "Enter the hub address"
            !h.matches(Regex("^(wss?://)?[A-Za-z0-9.\\-]+(:\\d{1,5})?/?$")) -> "Use host or host:port, e.g. 192.168.1.10:8765"
            k.length < 8 -> "Token must be at least 8 characters"
            else -> null
        }
        if (err != null) { hubError.text = err; hubError.visibility = VISIBLE; return }
        hubError.visibility = GONE
        val changed = h != prefs.hubUrl || k != prefs.token
        prefs.hubUrl = h; prefs.token = k
        hideKeyboard()
        onApply(changed)
    }

    private fun saveThresholds() {
        val c = cpu.text.toString().toFloatOrNull()
        val r = ram.text.toString().toFloatOrNull()
        val t = temp.text.toString().toFloatOrNull()
        if (c == null || r == null || t == null || c !in 10f..100f || r !in 10f..100f || t !in 30f..120f) {
            thresholdError.text = "CPU and RAM: 10-100 %. Temperature: 30-120 °C."
            thresholdError.visibility = VISIBLE
            return
        }
        thresholdError.visibility = GONE
        prefs.cpuAlert = c; prefs.ramAlert = r; prefs.tempAlert = t
        hideKeyboard()
        onApply(false)
    }

    fun close() { hideKeyboard(); onClose() }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
        findFocus()?.clearFocus()
    }

    // ---------------------------------------------------------------- builders

    private fun section(title: String) {
        column.addView(View(context).apply { setBackgroundColor(Hud.alpha(Hud.CYAN, 0.25f)) },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Hud.dp(1f).toInt()).apply { topMargin = Hud.dp(28f).toInt() })
        column.addView(label(title, 11f, Hud.CYAN), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Hud.dp(10f).toInt() })
    }

    private fun caption(text: String, add: Boolean = true): TextView {
        val v = label(text, 10f, Hud.LABEL).apply { isAllCaps = false; letterSpacing = 0.02f }
        if (add) column.addView(v, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Hud.dp(8f).toInt(); bottomMargin = Hud.dp(4f).toInt() })
        return v
    }

    private fun label(text: String, sp: Float, color: Int) = TextView(context).apply {
        this.text = text; typeface = Hud.mono; textSize = sp; setTextColor(color); letterSpacing = 0.14f
    }

    private fun error() = label("", 10f, Hud.CRIT).apply { visibility = GONE; letterSpacing = 0.02f; setPadding(0, Hud.dp(6f).toInt(), 0, 0) }

    private fun fieldLp() = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Hud.dp(48f).toInt())

    private fun field(hintText: String, type: Int) = EditText(context).apply {
        typeface = Hud.mono
        textSize = 15f
        setTextColor(Hud.TEXT)
        setHintTextColor(Hud.DIM)
        hint = hintText
        isSingleLine = true          // must come before inputType: setSingleLine() resets password masking
        inputType = type
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
        val pad = Hud.dp(14f).toInt()
        setPadding(pad, 0, pad, 0)
        background = GradientDrawable().apply {
            setColor(Hud.PANEL)
            setStroke(Hud.dp(1f).toInt(), Hud.alpha(Hud.CYAN, 0.35f))
        }
        onFocusChangeListener = OnFocusChangeListener { v, has ->
            (v.background as GradientDrawable).setStroke(Hud.dp(if (has) 1.5f else 1f).toInt(), Hud.alpha(Hud.CYAN, if (has) 0.95f else 0.35f))
        }
    }
}

/** Chamfered HUD button with press feedback. */
@SuppressLint("ViewConstructor")
class HudButton(ctx: Context, private val text: String, private val color: Int, private val action: () -> Unit) : View(ctx) {
    private val path = Path()
    private val stroke = Hud.stroke(Hud.dp(1.2f), round = false)
    private val fill = Hud.fill()
    private val paint = Hud.label(11f, Hud.TEXT).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.2f }
    private var pressed = false

    init { isClickable = true; isFocusable = true; contentDescription = text }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val k = Hud.dp(8f)
        path.rewind()
        path.moveTo(k, 1f); path.lineTo(w - 1f, 1f); path.lineTo(w - 1f, h - k)
        path.lineTo(w - k, h - 1f); path.lineTo(1f, h - 1f); path.lineTo(1f, k); path.close()
    }

    override fun onDraw(c: Canvas) {
        fill.color = Hud.alpha(color, if (pressed) 0.3f else 0.08f)
        c.drawPath(path, fill)
        stroke.color = Hud.alpha(color, if (pressed) 1f else 0.7f)
        c.drawPath(path, stroke)
        paint.color = if (color == Hud.LABEL) Hud.LABEL else Hud.TEXT
        c.drawText(text, width / 2f, height / 2f + paint.textSize * 0.36f, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = true; invalidate() }
            MotionEvent.ACTION_UP -> { pressed = false; invalidate(); if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick() }
            MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); action(); return true }
}

/** Label + description + animated switch. Whole row is the touch target. */
@SuppressLint("ViewConstructor")
class HudToggle(ctx: Context, private val title: String, private val desc: String) : View(ctx) {
    var onChange: ((Boolean) -> Unit)? = null
    private var checked = false
    private var knob = 0f
    private var anim: ValueAnimator? = null
    private val t1 = Hud.label(11f, Hud.TEXT)
    private val t2 = Hud.label(9f, Hud.LABEL).apply { letterSpacing = 0.02f }
    private val track = Hud.fill()
    private val border = Hud.stroke(Hud.dp(1f), round = false)
    private val dot = Hud.fill()

    init { isClickable = true; isFocusable = true; contentDescription = title }

    fun setChecked(v: Boolean, animate: Boolean) {
        checked = v
        anim?.cancel()
        if (!animate) { knob = if (v) 1f else 0f; invalidate(); return }
        anim = ValueAnimator.ofFloat(knob, if (v) 1f else 0f).setDuration(160).apply {
            addUpdateListener { knob = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawText(title, 0f, height * 0.42f, t1)
        c.drawText(desc, 0f, height * 0.42f + Hud.dp(17f), t2)
        val tw = Hud.dp(46f); val th = Hud.dp(20f)
        val x = width - tw; val y = height / 2f - th / 2f
        track.color = Hud.lerpColor(Hud.TRACK, Hud.alpha(Hud.CYAN, 0.3f), knob)
        c.drawRect(x, y, x + tw, y + th, track)
        border.color = Hud.lerpColor(Hud.DIM, Hud.CYAN, knob)
        c.drawRect(x, y, x + tw, y + th, border)
        dot.color = Hud.lerpColor(Hud.LABEL, Hud.CYAN, knob)
        val k = Hud.dp(14f)
        val kx = x + Hud.dp(3f) + (tw - k - Hud.dp(6f)) * knob
        c.drawRect(kx, y + Hud.dp(3f), kx + k, y + th - Hud.dp(3f), dot)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        setChecked(!checked, true)
        onChange?.invoke(checked)
        return true
    }
}

/** Device name + AUTO + four neon swatches. */
@SuppressLint("ViewConstructor")
class SwatchRow(ctx: Context, private val name: String, private var selected: Int, private val onPick: (Int) -> Unit) : View(ctx) {
    private val text = Hud.label(11f, Hud.TEXT)
    private val auto = Hud.label(6.5f, Hud.LABEL).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.02f }
    private val fill = Hud.fill()
    private val ring = Hud.stroke(Hud.dp(2f))
    private val size = Hud.dp(26f)
    private val gap = Hud.dp(14f)

    init { isClickable = true }

    private fun cx(i: Int) = width - (5 - i) * (size + gap) + size / 2f

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        c.drawText(name, 0f, cy + text.textSize * 0.36f, text)
        for (i in 0..4) {
            val x = cx(i)
            val color = if (i == 0) 0 else Hud.ACCENTS[i - 1]
            if (i == 0) {
                ring.color = Hud.LABEL; ring.strokeWidth = Hud.dp(1f)
                c.drawCircle(x, cy, size / 2f - Hud.dp(2f), ring)
                c.drawText("AUTO", x, cy + auto.textSize * 0.36f, auto)
            } else {
                fill.color = color
                c.drawCircle(x, cy, size / 2f - Hud.dp(3f), fill)
            }
            if (color == selected) {
                ring.color = Hud.TEXT; ring.strokeWidth = Hud.dp(2f)
                c.drawCircle(x, cy, size / 2f + Hud.dp(2f), ring)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            for (i in 0..4) if (kotlin.math.abs(e.x - cx(i)) < (size + gap) / 2f) {
                selected = if (i == 0) 0 else Hud.ACCENTS[i - 1]
                invalidate()
                onPick(selected)
                performClick()
                break
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
