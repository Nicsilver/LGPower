package com.nic.lgpower

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.os.Build
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
import androidx.appcompat.content.res.AppCompatResources
import java.net.InetSocketAddress
import java.net.Socket
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val client by lazy { WebOsClient(this) }
    private var pointerSession: WebOsClient.PointerSession? = null
    private var discovering = false
    private var lastAppliedThemeId = ""
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusInterval = 15_000L
    private lateinit var statusRunnable: Runnable

    private enum class TvStatus { CHECKING, CONNECTED, SEARCHING, DISCONNECTED }
    @Volatile private var tvConnected = false
    @Volatile private var wakeHomeGen = 0
    @Volatile private var currentBrightness: Int? = null
    @Volatile private var currentVolume: Int? = null
    @Volatile private var currentMuted: Boolean = false
    @Volatile private var currentScreenOff: Boolean = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var hasMoved = false
    private var isLocked = false
    private var colorRowOpen = false
    private var colorRowJustDismissed = false
    private val lockHandler = Handler(Looper.getMainLooper())
    private val moveThresholdPx = 12f
    private var lockAnimator: ValueAnimator? = null
    private var exitTouchpadFn: (() -> Unit)? = null
    private var moveAccumulator = 0f
    private val hapticMovePx = 32f
    private var brightnessDragLevel = 0
    private var brightnessSendRunnable: Runnable? = null
    private val brightnessSendLoop: Runnable = object : Runnable {
        override fun run() {
            Thread { client.setBrightness(brightnessDragLevel) }.start()
            statusHandler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lastAppliedThemeId = ThemeManager.getActiveThemeId(this)
        applyTheme()
        applyPressAnimations(findViewById(android.R.id.content))

        statusRunnable = Runnable {
            checkStatus()
            statusHandler.postDelayed(statusRunnable, statusInterval)
        }

        // Override recents/task switcher icon with transparent-background bitmap
        val iconDrawable = AppCompatResources.getDrawable(this, R.drawable.ic_launcher_foreground)
        val iconBmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        iconDrawable?.setBounds(0, 0, 192, 192)
        val iconCanvas = Canvas(iconBmp)
        iconCanvas.scale(1.5f, 1.5f, 96f, 96f)
        iconDrawable?.draw(iconCanvas)
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(getString(R.string.app_name), iconBmp, Color.TRANSPARENT))

        // Power — tap = WiFi (probe then turn off or WoL+retry); long-press = IR blaster
        findViewById<View>(R.id.btn_power).setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
            if (irManager?.hasIrEmitter() == true)
                runCatching { irManager.transmit(38000, LGPowerWidget.LG_POWER_PATTERN) }
            true
        }
        findViewById<View>(R.id.btn_power).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val gen = ++wakeHomeGen
            Thread {
                val tvOn = runCatching {
                    Socket().use { it.connect(InetSocketAddress(client.tvIp, 3001), 500) }
                    true
                }.getOrElse { false }
                if (tvOn) {
                    client.turnOff()
                } else {
                    client.sendWakeOnLan()
                    repeat(15) {
                        if (wakeHomeGen != gen) return@Thread
                        Thread {
                            if (wakeHomeGen == gen && client.goHome() is WebOsClient.Result.Success)
                                ++wakeHomeGen
                        }.start()
                        Thread.sleep(1_000)
                    }
                }
            }.start()
            statusHandler.postDelayed({ checkStatus() }, 2500)
        }

        // Screen Off — WiFi
        findViewById<View>(R.id.btn_screen_off).setOnClickListener {
            if (currentScreenOff) return@setOnClickListener
            setScreenOffButton(true)
            sendCommand { client.turnOffScreen() }
        }

        // D-pad
        val dpadHl = setupDpadHighlight()
        setDpadRepeatListener(findViewById(R.id.btn_up),    0, dpadHl) { client.pressUp() }
        setDpadRepeatListener(findViewById(R.id.btn_right), 1, dpadHl) { client.pressRight() }
        setDpadRepeatListener(findViewById(R.id.btn_down),  2, dpadHl) { client.pressDown() }
        setDpadRepeatListener(findViewById(R.id.btn_left),  3, dpadHl) { client.pressLeft() }
        findViewById<View>(R.id.btn_ok).setOnClickListener { sendCommand { client.pressEnter() } }

        // Home / Back / Settings
        findViewById<View>(R.id.btn_home).setOnClickListener     { sendCommand { client.pressKey("HOME") } }
        findViewById<View>(R.id.btn_back).setOnClickListener     { sendCommand { client.pressKey("BACK") } }
        findViewById<View>(R.id.btn_back).setOnLongClickListener { sendCommand { client.pressKey("EXIT") }; true }
        findViewById<View>(R.id.btn_settings).setOnClickListener     { sendCommand { client.pressKey("MENU") } }
        findViewById<View>(R.id.btn_settings).setOnLongClickListener { sendCommand { client.pressKey("HOME") }; true }

        // Volume + Mute
        var volumeDragLevel = 0
        var volumeSendRunnable: Runnable? = null
        val volumeSendLoop: Runnable = object : Runnable {
            override fun run() {
                Thread { client.setVolume(volumeDragLevel) }.start()
                statusHandler.postDelayed(this, 50)
            }
        }
        setupPillDrag(
            pillId = R.id.volume_pill,
            barId  = R.id.volume_bar,
            getLevel = { currentVolume },
            onTapUp   = { currentVolume?.let { setVolumeState((it + 1).coerceIn(0, 100), currentMuted) }
                          Thread { client.volumeUp();   scheduleVolumeRefresh() }.start() },
            onTapDown = { currentVolume?.let { setVolumeState((it - 1).coerceIn(0, 100), currentMuted) }
                          Thread { client.volumeDown(); scheduleVolumeRefresh() }.start() },
            onDragEnd = { level ->
                statusHandler.removeCallbacks(volumeSendLoop)
                volumeSendRunnable = null
                setVolumeState(level, currentMuted)
                Thread { client.setVolume(level); scheduleVolumeRefresh() }.start()
            },
            onDragMove = { level ->
                volumeDragLevel = level
                if (volumeSendRunnable == null) {
                    volumeSendRunnable = volumeSendLoop
                    statusHandler.post(volumeSendLoop)
                }
            },
            sliderEnabled = { appPrefs.getBoolean("vol_slider", true) }
        )
        findViewById<View>(R.id.btn_mute).setOnClickListener {
            currentVolume?.let { v -> runOnUiThread { setVolumeState(v, !currentMuted) } }
            sendCommand {
                val r = client.muteToggle()
                statusHandler.removeCallbacks(volumeRefreshRunnable)
                statusHandler.postDelayed(volumeRefreshRunnable, 1500)
                r
            }
        }

        // Right pill (brightness or channel) — configured in onResume via configureRightPill()

        // Input source
        findViewById<View>(R.id.btn_input).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Thread {
                val (inputs, error) = client.getExternalInputList()
                runOnUiThread {
                    if (error != null || inputs.isEmpty()) {
                        Toast.makeText(this, error ?: "No inputs found", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    showInputPicker(inputs)
                }
            }.start()
        }

        // Picture mode
        findViewById<View>(R.id.btn_picture).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showPicturePicker()
        }

        // Sound mode
        findViewById<View>(R.id.btn_sound).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showSoundPicker()
        }

        // Color buttons — tap Colors to show, any next tap (color or elsewhere) restores normal row
        findViewById<View>(R.id.btn_colors).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (!colorRowJustDismissed) {
                colorRowOpen = true
                findViewById<View>(R.id.color_buttons_row).visibility = View.VISIBLE
                findViewById<View>(R.id.normal_bottom_row).visibility = View.GONE
            }
        }
        val dismissColorRow = {
            colorRowOpen = false
            findViewById<View>(R.id.color_buttons_row).visibility = View.GONE
            findViewById<View>(R.id.normal_bottom_row).visibility = View.VISIBLE
        }
        findViewById<View>(R.id.btn_color_red).setOnClickListener    { dismissColorRow(); sendCommand { client.pressKey("RED") } }
        findViewById<View>(R.id.btn_color_green).setOnClickListener  { dismissColorRow(); sendCommand { client.pressKey("GREEN") } }
        findViewById<View>(R.id.btn_color_yellow).setOnClickListener { dismissColorRow(); sendCommand { client.pressKey("YELLOW") } }
        findViewById<View>(R.id.btn_color_blue).setOnClickListener   { dismissColorRow(); sendCommand { client.pressKey("BLUE") } }

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
        var overlayDragging = false
        var overlayTotalDist = 0f
        var dxCarry = 0f
        var dyCarry = 0f
        var isScrolling = false
        var lastScrollY = 0f
        var scrollCarry = 0f
        val tapThresholdPx = 12 * resources.displayMetrics.density
        val scrollSensitivity = 18f  // px per scroll unit
        touchpadOverlay.setOnTouchListener { v, event ->
            if (!isLocked) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    moveAccumulator = 0f
                    overlayTotalDist = 0f
                    overlayDragging = false
                    dxCarry = 0f
                    dyCarry = 0f
                    isScrolling = false
                    scrollCarry = 0f
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Second finger down — switch to scroll mode
                    if (event.pointerCount == 2) {
                        isScrolling = true
                        overlayDragging = false
                        lastScrollY = (event.getY(0) + event.getY(1)) / 2f
                        scrollCarry = 0f
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isScrolling && event.pointerCount == 2) {
                        val midY = (event.getY(0) + event.getY(1)) / 2f
                        val rawDy = midY - lastScrollY
                        lastScrollY = midY
                        val total = rawDy + scrollCarry
                        val units = (total / scrollSensitivity).toInt()
                        scrollCarry = total - units * scrollSensitivity
                        if (units != 0) pointerSession?.scroll(0f, -units.toFloat())
                    } else if (!isScrolling) {
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        if (!overlayDragging) {
                            overlayTotalDist += dist
                            if (overlayTotalDist >= tapThresholdPx) {
                                overlayDragging = true
                                dxCarry = 0f
                                dyCarry = 0f
                            }
                        } else {
                            val totalDx = dx + dxCarry
                            val totalDy = dy + dyCarry
                            val sendDx = totalDx.toInt()
                            val sendDy = totalDy.toInt()
                            dxCarry = totalDx - sendDx
                            dyCarry = totalDy - sendDy
                            moveAccumulator += dist
                            if (moveAccumulator >= hapticMovePx) {
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                moveAccumulator = 0f
                            }
                            if (sendDx != 0 || sendDy != 0)
                                pointerSession?.move(sendDx.toFloat(), sendDy.toFloat())
                        }
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Back to single finger — stop scrolling
                    isScrolling = false
                    overlayDragging = false
                    overlayTotalDist = 0f
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastTouchX = event.getX(remaining) + (v as View).x
                    lastTouchY = event.getY(remaining) + v.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!overlayDragging && !isScrolling) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        pointerSession?.click()
                    }
                    isScrolling = false
                    true
                }
                else -> true
            }
        }

        findViewById<View>(R.id.btn_touchpad_back).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pointerSession?.sendKey("BACK")
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
                        duration = 1000
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
                    }, 1000)
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
        val currentThemeId = ThemeManager.getActiveThemeId(this)
        if (lastAppliedThemeId.isNotEmpty() && lastAppliedThemeId != currentThemeId) {
            recreate()
            return
        }
        client.resetConnection()
        configureRightPill()
        refreshShortcuts()
        // Restore cached values immediately so bars aren't empty while network fetches
        val cachedVolume = appPrefs.getInt("last_volume", -1)
        val cachedMuted  = appPrefs.getBoolean("last_muted", false)
        if (cachedVolume >= 0) setVolumeState(cachedVolume, cachedMuted)
        val cachedBrightness = appPrefs.getInt("last_brightness", -1)
        if (cachedBrightness >= 0) setBrightnessBar(cachedBrightness)
        checkAndAutoDiscover()
        statusHandler.postDelayed(statusRunnable, statusInterval)
        scheduleBrightnessRefresh()
        scheduleVolumeRefresh()
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)
    }


    @Suppress("DEPRECATION")
    private fun animPress(v: View) =
        v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(70).start()

    private fun animRelease(v: View) =
        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()

    private fun highlightColor() =
        if (ThemeManager.getActiveThemeId(this) != "light") 0x33FFFFFF.toInt() else 0x20000000.toInt()

    private inner class DpadHighlightView(ctx: Context) : View(ctx) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val oval = android.graphics.RectF()
        private val sectorPath = android.graphics.Path()
        private val clipPath = android.graphics.Path()
        private val startAngles = floatArrayOf(225f, 315f, 45f, 135f) // up, right, down, left
        var sector = -1

        init {
            isClickable = false; isFocusable = false
            paint.color = highlightColor()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            if (sector < 0) return
            val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy)
            oval.set(cx - r, cy - r, cx + r, cy + r)
            clipPath.reset(); clipPath.addOval(oval, android.graphics.Path.Direction.CW)
            canvas.clipPath(clipPath)
            sectorPath.reset(); sectorPath.moveTo(cx, cy)
            sectorPath.arcTo(oval, startAngles[sector], 90f); sectorPath.close()
            canvas.drawPath(sectorPath, paint)
        }
    }

    private fun setupDpadHighlight(): DpadHighlightView {
        val container = findViewById<View>(R.id.btn_up).parent as android.widget.FrameLayout
        val hl = DpadHighlightView(this)
        container.addView(hl, 1, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return hl
    }

    private fun setDpadRepeatListener(view: View, sector: Int, hl: DpadHighlightView, block: () -> WebOsClient.Result) {
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
                    hl.sector = sector; hl.invalidate()
                    v.isPressed = true; sendCommand(block)
                    repeatHandler.postDelayed(repeatRunnable, 400L); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hl.sector = -1; hl.invalidate()
                    v.isPressed = false
                    repeatHandler.removeCallbacks(repeatRunnable); true
                }
                else -> false
            }
        }
    }

    private fun applyPressAnimations(root: View) {
        if (root is android.widget.ImageButton || root is android.widget.Button) {
            root.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> animPress(v)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animRelease(v)
                }
                false
            }
        }
        if (root is android.view.ViewGroup) repeat(root.childCount) { applyPressAnimations(root.getChildAt(it)) }
    }

    private fun applyTheme() {
        val theme = ThemeManager.getActiveTheme(this)
        ThemeManager.applyToRoot(findViewById(R.id.main_root), theme)
        val iconTint = ColorStateList.valueOf(theme.circleBtnIconTint)
        listOf(R.id.btn_keyboard, R.id.btn_home, R.id.btn_input,
               R.id.btn_back, R.id.btn_settings, R.id.btn_picture,
               R.id.btn_sound,
               R.id.btn_up, R.id.btn_down, R.id.btn_left, R.id.btn_right,
               R.id.btn_mute, R.id.btn_screen_off,
               R.id.btn_volume_up, R.id.btn_volume_down,
               R.id.btn_brightness_up, R.id.btn_brightness_down)
            .forEach { id -> findViewById<android.widget.ImageButton>(id)?.imageTintList = iconTint }
        // Touchpad icon is an ImageView (no button wrapper has the icon directly)
        findViewById<android.widget.ImageView>(R.id.iv_touchpad_icon)?.imageTintList = iconTint
        // App settings gear: tint with primaryText so it's visible in both modes
        findViewById<android.widget.ImageButton>(R.id.btn_app_settings)?.imageTintList =
            ColorStateList.valueOf(theme.primaryText)
        // OK button: slightly lighter than the d-pad ring, same text color as theme
        val okBtn = findViewById<Button>(R.id.btn_ok)
        okBtn?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.dpadOkBg)
        }
        okBtn?.setTextColor(theme.primaryText)
        window.statusBarColor = theme.windowBg
        window.navigationBarColor = theme.windowBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (theme.statusBarLightIcons)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    private fun setStatus(status: TvStatus) {
        tvConnected = (status == TvStatus.CONNECTED)
        val dot = findViewById<View>(R.id.status_dot)
        val color = when (status) {
            TvStatus.CHECKING     -> 0xFF888888.toInt()
            TvStatus.CONNECTED    -> 0xFF4CAF50.toInt()
            TvStatus.SEARCHING    -> 0xFFFF9800.toInt()
            TvStatus.DISCONNECTED -> 0xFFF44336.toInt()
        }
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun checkAndAutoDiscover() {
        val ip = client.tvIp
        if (ip.isBlank()) {
            // No IP saved yet — run discovery once
            setStatus(TvStatus.SEARCHING)
            autoDiscover()
        } else {
            checkStatus()
        }
    }

    private fun checkStatus() {
        val ip = client.tvIp
        if (ip.isBlank()) return
        runOnUiThread { setStatus(TvStatus.CHECKING) }
        Thread {
            val portOpen = runCatching {
                Socket().use { it.connect(InetSocketAddress(ip, 3001), 2000) }
                true
            }.getOrElse { false }
            if (!portOpen) {
                runOnUiThread { setStatus(TvStatus.DISCONNECTED) }
                return@Thread
            }
            // Port open — show connected immediately, then confirm via WebOS
            runOnUiThread { setStatus(TvStatus.CONNECTED) }
            val brightness = client.getBrightness()
            if (brightness == null) {
                // Port open but WebOS not responding — TV is in standby (Quick Start)
                runOnUiThread { setStatus(TvStatus.DISCONNECTED) }
                return@Thread
            }
            runOnUiThread { setBrightnessBar(brightness) }
            val volumeState = client.getVolume()
            if (volumeState != null) runOnUiThread { setVolumeState(volumeState.volume, volumeState.muted) }
            val screenOff = client.getScreenOff()
            if (screenOff != null) runOnUiThread { setScreenOffButton(screenOff) }
        }.start()
    }

    private val appPrefs by lazy { getSharedPreferences("webos", MODE_PRIVATE) }

    private fun configureRightPill() {
        if (appPrefs.getBoolean("right_pill_channel", false)) setupChannelPill()
        else setupBrightnessPill()
    }

    private fun setupBrightnessPill() {
        findViewById<View>(R.id.brightness_bar).visibility = View.VISIBLE
        findViewById<android.widget.ImageButton>(R.id.btn_brightness_up).setImageResource(R.drawable.ic_brightness_up)
        findViewById<android.widget.ImageButton>(R.id.btn_brightness_down).setImageResource(R.drawable.ic_brightness_down)
        setupPillDrag(
            pillId = R.id.brightness_pill,
            barId  = R.id.brightness_bar,
            getLevel = { currentBrightness },
            onTapUp   = { val lvl = ((currentBrightness ?: 50) + 5).coerceIn(0, 100)
                          setBrightnessBar(lvl)
                          Thread { client.setBrightness(lvl) }.start() },
            onTapDown = { val lvl = ((currentBrightness ?: 50) - 5).coerceIn(0, 100)
                          setBrightnessBar(lvl)
                          Thread { client.setBrightness(lvl) }.start() },
            onDragEnd = { level ->
                statusHandler.removeCallbacks(brightnessSendLoop)
                brightnessSendRunnable = null
                setBrightnessBar(level)
                Thread { client.setBrightness(level); scheduleBrightnessRefresh() }.start()
            },
            onDragMove = { level ->
                brightnessDragLevel = level
                if (brightnessSendRunnable == null) {
                    brightnessSendRunnable = brightnessSendLoop
                    statusHandler.post(brightnessSendLoop)
                }
            },
            sliderEnabled = { appPrefs.getBoolean("brightness_slider", true) },
            onActionUp = { scheduleBrightnessRefresh() }
        )
    }

    private fun setupChannelPill() {
        val bar   = findViewById<View>(R.id.brightness_bar)
        val label = findViewById<TextView>(R.id.brightness_label)
        val pill  = findViewById<View>(R.id.brightness_pill)
        bar.visibility = View.INVISIBLE
        label.text = ""
        findViewById<android.widget.ImageButton>(R.id.btn_brightness_up).setImageResource(R.drawable.ic_channel_up)
        findViewById<android.widget.ImageButton>(R.id.btn_brightness_down).setImageResource(R.drawable.ic_channel_down)
        val pillFrame = pill as? android.widget.FrameLayout
        val hlColor = highlightColor()
        val halfH = pill.layoutParams?.height?.div(2) ?: (115 * resources.displayMetrics.density).toInt()
        pill.findViewWithTag<View?>("pill_hl_top")?.let { pillFrame?.removeView(it) }
        pill.findViewWithTag<View?>("pill_hl_bot")?.let { pillFrame?.removeView(it) }
        val topHl = View(this).apply {
            tag = "pill_hl_top"; isClickable = false; isFocusable = false
            visibility = View.INVISIBLE; setBackgroundColor(hlColor)
        }
        val botHl = View(this).apply {
            tag = "pill_hl_bot"; isClickable = false; isFocusable = false
            visibility = View.INVISIBLE; setBackgroundColor(hlColor)
        }
        pillFrame?.addView(topHl, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, halfH
        ).apply { gravity = android.view.Gravity.TOP })
        pillFrame?.addView(botHl, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, halfH
        ).apply { gravity = android.view.Gravity.BOTTOM })
        pill.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.y < v.height / 2f) { topHl.visibility = View.VISIBLE; botHl.visibility = View.INVISIBLE }
                    else { topHl.visibility = View.INVISIBLE; botHl.visibility = View.VISIBLE }
                }
                MotionEvent.ACTION_UP -> {
                    topHl.visibility = View.INVISIBLE; botHl.visibility = View.INVISIBLE
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (event.y < v.height / 2f) sendCommand { client.channelUp() }
                    else sendCommand { client.channelDown() }
                }
                MotionEvent.ACTION_CANCEL -> { topHl.visibility = View.INVISIBLE; botHl.visibility = View.INVISIBLE }
            }
            true
        }
    }

    private fun setupPillDrag(
        pillId: Int,
        barId: Int,
        getLevel: () -> Int?,
        onTapUp: () -> Unit,
        onTapDown: () -> Unit,
        onDragEnd: (Int) -> Unit,
        onDragMove: ((Int) -> Unit)? = null,
        sliderEnabled: () -> Boolean = { true },
        onActionUp: (() -> Unit)? = null
    ) {
        val pill = findViewById<View>(pillId)
        val bar  = findViewById<View>(barId)
        val pillHeightPx = (230 * resources.displayMetrics.density).toInt()
        val dragThreshold = 10 * resources.displayMetrics.density
        var startY = 0f
        var isDragging = false
        var lastHapticLevel = -1

        val repeatHandler = Handler(Looper.getMainLooper())
        var repeatIsUp = true
        val repeatRunnable = object : Runnable {
            override fun run() {
                if (repeatIsUp) onTapUp() else onTapDown()
                pill.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                repeatHandler.postDelayed(this, 120L)
            }
        }

        // Half-height highlight overlays — clipped to pill shape via clipToOutline on the FrameLayout
        val hlColor = highlightColor()
        val halfH = pillHeightPx / 2
        val pillFrame = pill as? android.widget.FrameLayout
        pill.findViewWithTag<View?>("pill_hl_top")?.let { pillFrame?.removeView(it) }
        pill.findViewWithTag<View?>("pill_hl_bot")?.let { pillFrame?.removeView(it) }
        val topHl = View(this).apply {
            tag = "pill_hl_top"; isClickable = false; isFocusable = false
            visibility = View.INVISIBLE; setBackgroundColor(hlColor)
        }
        val botHl = View(this).apply {
            tag = "pill_hl_bot"; isClickable = false; isFocusable = false
            visibility = View.INVISIBLE; setBackgroundColor(hlColor)
        }
        pillFrame?.addView(topHl, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, halfH
        ).apply { gravity = android.view.Gravity.TOP })
        pillFrame?.addView(botHl, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, halfH
        ).apply { gravity = android.view.Gravity.BOTTOM })

        pill.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    isDragging = false
                    lastHapticLevel = -1
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    if (event.y < pillHeightPx / 2f) { topHl.visibility = View.VISIBLE; botHl.visibility = View.INVISIBLE }
                    else { topHl.visibility = View.INVISIBLE; botHl.visibility = View.VISIBLE }
                    if (!sliderEnabled()) {
                        repeatIsUp = event.y < pillHeightPx / 2f
                        if (repeatIsUp) onTapUp() else onTapDown()
                        repeatHandler.postDelayed(repeatRunnable, 400L)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (sliderEnabled()) {
                        if (!isDragging && abs(event.y - startY) > dragThreshold) {
                            isDragging = true
                            topHl.visibility = View.INVISIBLE; botHl.visibility = View.INVISIBLE
                        }
                        if (isDragging) {
                            val level = ((1f - event.y / pillHeightPx) * 100).toInt().coerceIn(0, 100)
                            val targetHeight = (pillHeightPx * level / 100f).toInt()
                            bar.layoutParams = bar.layoutParams.also { it.height = targetHeight }
                            bar.requestLayout()
                            onDragMove?.invoke(level)
                            val parent = pill.parent as? android.view.ViewGroup
                            (parent?.getChildAt(0) as? TextView)?.text = level.toString()
                            if (lastHapticLevel == -1 || abs(level - lastHapticLevel) >= 3) {
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                lastHapticLevel = level
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    topHl.visibility = View.INVISIBLE; botHl.visibility = View.INVISIBLE
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    repeatHandler.removeCallbacks(repeatRunnable)
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (sliderEnabled()) {
                        if (isDragging) {
                            val level = ((1f - event.y / pillHeightPx) * 100).toInt().coerceIn(0, 100)
                            onDragEnd(level)
                        } else {
                            if (event.y < pillHeightPx / 2f) onTapUp() else onTapDown()
                        }
                    }
                    onActionUp?.invoke()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    topHl.visibility = View.INVISIBLE; botHl.visibility = View.INVISIBLE
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    repeatHandler.removeCallbacks(repeatRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private val volumeRefreshRunnable = Runnable {
        Thread {
            val state = client.getVolume()
            if (state != null) runOnUiThread { setVolumeState(state.volume, state.muted) }
        }.start()
    }

    private fun scheduleVolumeRefresh() {
        statusHandler.removeCallbacks(volumeRefreshRunnable)
        statusHandler.postDelayed(volumeRefreshRunnable, 200)
    }

    private fun setVolumeState(level: Int, muted: Boolean) {
        currentVolume = level
        currentMuted = muted
        appPrefs.edit().putInt("last_volume", level).putBoolean("last_muted", muted).apply()
        findViewById<TextView>(R.id.volume_label)?.text = level.toString()
        val bar = findViewById<View>(R.id.volume_bar) ?: return
        val pillHeightPx = (230 * resources.displayMetrics.density).toInt()
        val targetHeight = (pillHeightPx * level / 100f).toInt()
        bar.layoutParams = bar.layoutParams.also { it.height = targetHeight }
        bar.requestLayout()
        // Gray out bar when muted
        bar.backgroundTintList = ColorStateList.valueOf(
            if (muted) 0x66888888.toInt() else 0x664FC3F7.toInt()
        )
        val muteBtn = findViewById<android.widget.ImageButton>(R.id.btn_mute)
        val theme = ThemeManager.getActiveTheme(this)
        if (muted) {
            muteBtn?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(theme.btnAccentBg)
            }
            muteBtn?.imageTintList = ColorStateList.valueOf(theme.btnAccentText)
        } else {
            muteBtn?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(theme.circleBtnBg)
            }
            muteBtn?.imageTintList = ColorStateList.valueOf(theme.circleBtnIconTint)
        }
    }

    private val brightnessRefreshRunnable = Runnable {
        Thread {
            val level = client.getBrightness()
            if (level != null) runOnUiThread { setBrightnessBar(level) }
        }.start()
    }

    private fun scheduleBrightnessRefresh() {
        statusHandler.removeCallbacks(brightnessRefreshRunnable)
        statusHandler.postDelayed(brightnessRefreshRunnable, 200)
    }

    private fun setBrightnessBar(level: Int) {
        currentBrightness = level
        appPrefs.edit().putInt("last_brightness", level).apply()
        if (appPrefs.getBoolean("right_pill_channel", false)) return
        findViewById<TextView>(R.id.brightness_label)?.text = level.toString()
        val bar = findViewById<View>(R.id.brightness_bar) ?: return
        val pillHeightPx = (230 * resources.displayMetrics.density).toInt()
        val targetHeight = (pillHeightPx * level / 100f).toInt()
        bar.layoutParams = bar.layoutParams.also { it.height = targetHeight }
        bar.requestLayout()
    }

    private val screenOffRefreshRunnable = Runnable {
        Thread {
            val isOff = client.getScreenOff()
            if (isOff != null) runOnUiThread { setScreenOffButton(isOff) }
        }.start()
    }

    private fun setScreenOffButton(isOff: Boolean) {
        currentScreenOff = isOff
        val btn = findViewById<android.widget.ImageButton>(R.id.btn_screen_off) ?: return
        val theme = ThemeManager.getActiveTheme(this)
        if (isOff) {
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(theme.btnAccentBg)
            }
            btn.imageTintList = ColorStateList.valueOf(theme.btnAccentText)
        } else {
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(theme.circleBtnBg)
            }
            btn.imageTintList = ColorStateList.valueOf(theme.circleBtnIconTint)
        }
    }

    private fun autoDiscover() {
        if (discovering) return
        discovering = true
        Thread {
            val found = TvDiscovery.discover(this)
            runOnUiThread {
                discovering = false
                if (found.isNotEmpty()) {
                    client.saveTvIp(found[0])
                    setStatus(TvStatus.CONNECTED)
                } else {
                    setStatus(TvStatus.DISCONNECTED)
                }
            }
        }.start()
    }

    private fun refreshShortcuts() {
        val container = findViewById<LinearLayout>(R.id.shortcuts_row)
        container.removeAllViews()
        val shortcuts = client.loadShortcuts()
        val density = resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val rowHeight = (52 * density).toInt()
        val theme = ThemeManager.getActiveTheme(this)

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
                val pillColor = client.loadCachedColor(app.id) ?: theme.circleBtnBg
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
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).start()
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                        }
                        false
                    }
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        val gen = ++wakeHomeGen
                        Thread {
                            val tvOn = runCatching {
                                Socket().use { s -> s.connect(InetSocketAddress(client.tvIp, 3001), 500) }
                                true
                            }.getOrElse { false }
                            if (tvOn) {
                                client.launchApp(app.id)
                            } else {
                                client.sendWakeOnLan()
                                repeat(15) {
                                    if (wakeHomeGen != gen) return@Thread
                                    Thread {
                                        if (wakeHomeGen == gen && client.launchApp(app.id) is WebOsClient.Result.Success)
                                            ++wakeHomeGen
                                    }.start()
                                    Thread.sleep(1_000)
                                }
                            }
                        }.start()
                        statusHandler.postDelayed({ checkStatus() }, 2500)
                    }
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
            KeyEvent.KEYCODE_VOLUME_UP -> {
                currentVolume?.let { setVolumeState((it + 1).coerceIn(0, 100), currentMuted) }
                sendCommand { client.volumeUp() }
                scheduleVolumeRefresh()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                currentVolume?.let { setVolumeState((it - 1).coerceIn(0, 100), currentMuted) }
                sendCommand { client.volumeDown() }
                scheduleVolumeRefresh()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            colorRowJustDismissed = false
            if (colorRowOpen) {
                val colorRow = findViewById<View>(R.id.color_buttons_row)
                val rect = android.graphics.Rect()
                colorRow.getGlobalVisibleRect(rect)
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    colorRowJustDismissed = true
                    colorRowOpen = false
                    colorRow.visibility = View.GONE
                    findViewById<View>(R.id.normal_bottom_row).visibility = View.VISIBLE
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (isLocked) exitTouchpadFn?.invoke()
        else super.onBackPressed()
    }

    private fun showPicturePicker() {
        // LG C4 SDR picture modes — the API doesn't expose a modes list
        val modes = listOf(
            "vivid"      to "Vivid",
            "standard"   to "Standard",
            "eco"        to "Eco",
            "cinema"     to "Cinema",
            "expert1"    to "Expert (Bright Room)",
            "expert2"    to "Expert (Dark Room)",
            "game"       to "Game Optimizer",
            "filmMaker"  to "Filmmaker Mode",
            "sports"     to "Sports",
        )
        Thread {
            val current = client.getCurrentPictureMode()
            runOnUiThread { displayPicturePicker(modes, current) }
        }.start()
    }

    private fun displayPicturePicker(modes: List<Pair<String, String>>, current: String?) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_input_picker)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.BOTTOM)
            attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
        }

        dialog.findViewById<TextView>(R.id.dialog_picker_title).text = "Picture Mode"

        val container = dialog.findViewById<LinearLayout>(R.id.inputs_container)
        val density = resources.displayMetrics.density

        modes.forEach { (id, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_input_item)
                isClickable = true
                isFocusable = true
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * density).toInt() }
            }

            val icon = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_picture)
                imageTintList = ColorStateList.valueOf(0xFF8888AA.toInt())
                layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
                    .also { it.marginEnd = (14 * density).toInt() }
            }

            val isActive = id == current
            val labelView = TextView(this).apply {
                text = if (isActive) "$label  ✓" else label
                textSize = 16f
                setTextColor(if (isActive) 0xFFFFFFFF.toInt() else 0xFFDDDDEE.toInt())
                if (isActive) setTypeface(null, android.graphics.Typeface.BOLD)
            }

            row.addView(icon)
            row.addView(labelView)
            row.setOnClickListener {
                dialog.dismiss()
                sendCommand { client.setPictureMode(id) }
            }
            container.addView(row)
        }

        dialog.show()
    }

    private fun showSoundPicker() {
        val modes = listOf(
            "standard"    to "Standard",
            "movie"       to "Movie",
            "music"       to "Music",
            "sports"      to "Sports",
            "game"        to "Game",
            "news"        to "News",
            "aiSoundPlus" to "AI Sound+",
        )
        Thread {
            val current = client.getSoundMode()
            runOnUiThread { displaySoundPicker(modes, current) }
        }.start()
    }

    private fun displaySoundPicker(modes: List<Pair<String, String>>, current: String?) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_input_picker)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.BOTTOM)
            attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
        }

        dialog.findViewById<TextView>(R.id.dialog_picker_title).text = "Sound Mode"

        val container = dialog.findViewById<LinearLayout>(R.id.inputs_container)
        val density = resources.displayMetrics.density

        modes.forEach { (id, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_input_item)
                isClickable = true
                isFocusable = true
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * density).toInt() }
            }

            val icon = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_sound)
                imageTintList = ColorStateList.valueOf(0xFF8888AA.toInt())
                layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
                    .also { it.marginEnd = (14 * density).toInt() }
            }

            val isActive = id == current
            val labelView = TextView(this).apply {
                text = if (isActive) "$label  ✓" else label
                textSize = 16f
                setTextColor(if (isActive) 0xFFFFFFFF.toInt() else 0xFFDDDDEE.toInt())
                if (isActive) setTypeface(null, android.graphics.Typeface.BOLD)
            }

            row.addView(icon)
            row.addView(labelView)
            row.setOnClickListener {
                dialog.dismiss()
                sendCommand { client.setSoundMode(id) }
            }
            container.addView(row)
        }

        dialog.show()
    }

    private fun showInputPicker(inputs: List<WebOsClient.InputSource>) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_input_picker)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.BOTTOM)
            attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
        }

        val container = dialog.findViewById<LinearLayout>(R.id.inputs_container)
        val density = resources.displayMetrics.density

        inputs.forEach { input ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_input_item)
                isClickable = true
                isFocusable = true
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * density).toInt() }
            }

            val icon = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_input)
                imageTintList = ColorStateList.valueOf(0xFF8888AA.toInt())
                layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
                    .also { it.marginEnd = (14 * density).toInt() }
            }

            val label = TextView(this).apply {
                text = input.label
                textSize = 16f
                setTextColor(0xFFDDDDEE.toInt())
            }

            row.addView(icon)
            row.addView(label)
            row.setOnClickListener {
                dialog.dismiss()
                sendCommand { client.switchInput(input.id) }
            }
            container.addView(row)
        }

        dialog.show()
    }

    private fun showTextInputDialog() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_keyboard_input)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.88f)
            attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
        }

        val theme = ThemeManager.getActiveTheme(this)
        val d = resources.displayMetrics.density

        dialog.findViewById<LinearLayout>(R.id.keyboard_card).background = GradientDrawable().apply {
            setColor(theme.circleBtnBg)
            cornerRadius = 23 * d
        }

        val editText = dialog.findViewById<EditText>(R.id.keyboard_input)
        editText.setTextColor(theme.primaryText)
        editText.setHintTextColor(theme.secondaryText)

        val sendBtn = dialog.findViewById<ImageButton>(R.id.keyboard_send)
        sendBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#555555"))
        }
        sendBtn.imageTintList = ColorStateList.valueOf(Color.WHITE)

        fun send() {
            val text = editText.text.toString()
            if (text.isNotEmpty()) sendCommand { client.sendText(text) }
            dialog.dismiss()
        }

        sendBtn.setOnClickListener { send() }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
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
                    animPress(v)
                    v.isPressed = true
                    sendCommand(block)
                    repeatHandler.postDelayed(repeatRunnable, 400L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    animRelease(v)
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
        statusHandler.removeCallbacks(screenOffRefreshRunnable)
        statusHandler.postDelayed(screenOffRefreshRunnable, 1200)
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
