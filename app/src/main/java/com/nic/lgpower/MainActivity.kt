package com.nic.lgpower

import android.animation.ValueAnimator
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val client by lazy { WebOsClient(this) }
    private var pointerSession: WebOsClient.PointerSession? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var hasMoved = false
    private var isLocked = false
    private val lockHandler = Handler(Looper.getMainLooper())
    private val moveThresholdPx = 12f
    private var lockAnimator: ValueAnimator? = null
    private var exitTouchpadFn: (() -> Unit)? = null
    private var moveAccumulator = 0f
    private val hapticMovePx = 32f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Power — IR toggle
        findViewById<View>(R.id.btn_power).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
            if (irManager?.hasIrEmitter() == true) {
                runCatching { irManager.transmit(38000, LGPowerWidget.LG_POWER_PATTERN) }
            }
        }

        // Screen Off — WiFi
        findViewById<View>(R.id.btn_screen_off).setOnClickListener {
            sendCommand { client.turnOffScreen() }
        }

        // D-pad
        setRepeatListener(findViewById(R.id.btn_up))    { client.pressUp() }
        setRepeatListener(findViewById(R.id.btn_down))  { client.pressDown() }
        setRepeatListener(findViewById(R.id.btn_left))  { client.pressLeft() }
        setRepeatListener(findViewById(R.id.btn_right)) { client.pressRight() }
        findViewById<View>(R.id.btn_ok).setOnClickListener { sendCommand { client.pressEnter() } }

        // Back / Settings
        findViewById<View>(R.id.btn_back).setOnClickListener     { sendCommand { client.pressKey("BACK") } }
        findViewById<View>(R.id.btn_back).setOnLongClickListener { sendCommand { client.pressKey("HOME") }; true }
        findViewById<View>(R.id.btn_settings).setOnClickListener     { sendCommand { client.pressKey("MENU") } }
        findViewById<View>(R.id.btn_settings).setOnLongClickListener { sendCommand { client.pressKey("HOME") }; true }

        // Volume + Mute
        setRepeatListener(findViewById(R.id.btn_volume_up))   { client.volumeUp() }
        setRepeatListener(findViewById(R.id.btn_volume_down)) { client.volumeDown() }
        findViewById<View>(R.id.btn_mute).setOnClickListener  { sendCommand { client.muteToggle() } }

        // Brightness
        setRepeatListener(findViewById(R.id.btn_brightness_up))   { client.brightnessUp() }
        setRepeatListener(findViewById(R.id.btn_brightness_down)) { client.brightnessDown() }

        // App settings
        findViewById<View>(R.id.btn_app_settings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        // Keyboard
        findViewById<View>(R.id.btn_keyboard).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showTextInputDialog()
        }

        // Touchpad
        val touchpadOverlay  = findViewById<View>(R.id.touchpad_overlay)
        val touchpadHint     = findViewById<View>(R.id.touchpad_hint)
        val btnExit          = findViewById<View>(R.id.btn_touchpad_exit)
        val lockBorder       = findViewById<LockBorderView>(R.id.lock_border)

        fun resetBorder() {
            lockAnimator?.cancel()
            lockAnimator = null
            lockBorder.setProgress(0f)
        }

        fun exitTouchpad() {
            lockHandler.removeCallbacksAndMessages(null)
            resetBorder()
            isLocked = false
            hasMoved = false
            pointerSession?.close()
            pointerSession = null
            touchpadOverlay.visibility = View.GONE
            btnExit.visibility = View.GONE
            touchpadHint.visibility = View.VISIBLE
        }
        exitTouchpadFn = ::exitTouchpad

        // Overlay touch listener — active only in locked mode for ongoing movement
        touchpadOverlay.setOnTouchListener { v, event ->
            if (!isLocked) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    moveAccumulator = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    moveAccumulator += Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (moveAccumulator >= hapticMovePx) {
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        moveAccumulator = 0f
                    }
                    pointerSession?.move(dx, dy)
                    true
                }
                else -> true
            }
        }

        findViewById<View>(R.id.btn_touchpad_click).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pointerSession?.click()
        }

        btnExit.setOnClickListener { exitTouchpad() }

        // Touchpad button — hold still to lock, drag to use normally
        findViewById<View>(R.id.btn_touchpad).setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    hasMoved = false
                    isLocked = false
                    moveAccumulator = 0f
                    touchpadOverlay.visibility = View.VISIBLE
                    pointerSession = client.openPointerSession()
                    // Grow the border around the screen over 1.5s
                    resetBorder()
                    lockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 1500
                        interpolator = LinearInterpolator()
                        addUpdateListener { lockBorder.setProgress(it.animatedValue as Float) }
                        start()
                    }
                    // Lock if finger stays still for 1.5s
                    lockHandler.postDelayed({
                        if (!hasMoved) {
                            isLocked = true
                            runOnUiThread {
                                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                touchpadHint.visibility = View.GONE
                                btnExit.visibility = View.VISIBLE
                            }
                        }
                    }, 1500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    // Cancel lock and reset indicator if moved beyond threshold
                    if (!hasMoved && (abs(dx) > moveThresholdPx || abs(dy) > moveThresholdPx)) {
                        hasMoved = true
                        lockHandler.removeCallbacksAndMessages(null)
                        resetBorder()
                    }
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    moveAccumulator += Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (moveAccumulator >= hapticMovePx) {
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        moveAccumulator = 0f
                    }
                    pointerSession?.move(dx, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lockHandler.removeCallbacksAndMessages(null)
                    if (!isLocked) exitTouchpad()
                    // If locked, keep everything alive — overlay touch listener takes over
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        client.resetConnection()
        refreshShortcuts()
    }

    private fun refreshShortcuts() {
        val container = findViewById<LinearLayout>(R.id.shortcuts_row)
        container.removeAllViews()
        val shortcuts = client.loadShortcuts()
        val density = resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val rowHeight = (52 * density).toInt()

        // Split into chunks: 2 per row when 4 selected, otherwise one row
        val chunks = if (shortcuts.size == 4) listOf(shortcuts.take(2), shortcuts.drop(2))
                     else listOf(shortcuts)

        chunks.forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowHeight
                ).also { if (rowIndex > 0) it.topMargin = gap }
            }

            chunk.forEachIndexed { colIndex, app ->
                val pillColor = client.loadCachedColor(app.id) ?: 0xFF1C1C1C.toInt()
                val pillBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16 * density
                    setColor(pillColor)
                }

                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    background = pillBg
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { sendCommand { client.launchApp(app.id) } }
                }

                val label = TextView(this).apply {
                    text = app.title
                    textSize = 14f
                    setTextColor(0xFFFFFFFF.toInt())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                item.addView(label)

                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                if (colIndex > 0) params.marginStart = gap
                row.addView(item, params)
            }

            container.addView(row)
        }
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { sendCommand { client.volumeUp() }; true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { sendCommand { client.volumeDown() }; true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (isLocked) exitTouchpadFn?.invoke()
        else super.onBackPressed()
    }

    private fun showTextInputDialog() {
        val editText = EditText(this).apply {
            hint = "Type to send to TV…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 32)
            imeOptions = EditorInfo.IME_ACTION_SEND
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Send text to TV")
            .setView(editText)
            .setPositiveButton("Send") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) sendCommand { client.sendText(text) }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = editText.text.toString()
                if (text.isNotEmpty()) sendCommand { client.sendText(text) }
                dialog.dismiss()
                true
            } else false
        }

        dialog.show()
        editText.requestFocus()
    }

    private fun setRepeatListener(view: View, block: () -> WebOsClient.Result) {
        val repeatHandler = Handler(Looper.getMainLooper())
        val vibrator = getSystemService(Vibrator::class.java)
        val repeatRunnable = object : Runnable {
            override fun run() {
                vibrator?.vibrate(VibrationEffect.createOneShot(24, 48))
                Thread { block() }.start()
                repeatHandler.postDelayed(this, 120L)
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    sendCommand(block)
                    repeatHandler.postDelayed(repeatRunnable, 400L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeatHandler.removeCallbacks(repeatRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun sendCommand(block: () -> WebOsClient.Result) {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Thread {
            val result = block()
            runOnUiThread {
                when (result) {
                    is WebOsClient.Result.NeedsPairing ->
                        Toast.makeText(this, "Accept pairing on your TV, then try again", Toast.LENGTH_LONG).show()
                    is WebOsClient.Result.Error ->
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    else -> {}
                }
            }
        }.start()
    }
}
