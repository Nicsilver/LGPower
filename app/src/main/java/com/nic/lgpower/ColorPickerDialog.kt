package com.nic.lgpower

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A saturation/brightness square plus a hue slider — the familiar pro color
 * picker — with a live swatch and editable hex. Returns the chosen color.
 */
fun Activity.showColorPicker(
    title: String,
    initial: Int,
    onPick: (Int) -> Unit
) {
    val d = resources.displayMetrics.density
    fun dp(v: Float) = (v * d).toInt()
    val theme = ThemeManager.getActiveTheme(this)

    val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
    var current = initial or 0xFF000000.toInt()

    val dialog = Dialog(this)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.CENTER)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.55f)
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20f), dp(20f), dp(20f), dp(16f))
        background = GradientDrawable().apply { setColor(theme.surfaceBg); cornerRadius = 22f * d }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(28f), 0, dp(28f), 0)
        layoutParams = lp
    }

    root.addView(TextView(this).apply {
        text = title
        textSize = 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(theme.primaryText)
    })

    val satVal = SatValView(this).apply {
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
    }
    root.addView(satVal, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200f)).also {
        it.topMargin = dp(16f)
    })

    val hueBar = HueView(this).apply { hue = hsv[0] }
    root.addView(hueBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26f)).also {
        it.topMargin = dp(16f)
    })

    // Swatch + hex row
    val swatch = View(this)
    val hexField = EditText(this).apply {
        setText(ColorUtil.toHex(current))
        setTextColor(theme.primaryText)
        textSize = 16f
        setBackgroundColor(Color.TRANSPARENT)
        filters = arrayOf(InputFilter.LengthFilter(7))
        setSingleLine()
    }
    val swatchBox = GradientDrawable().apply { cornerRadius = 10f * d; setColor(current) }
    swatch.background = swatchBox
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(swatch, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        addView(hexField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dp(14f)
        })
        root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(18f)
        })
    }

    var suppressHex = false
    fun refreshSwatch(updateHex: Boolean) {
        swatchBox.setColor(current)
        if (updateHex) {
            suppressHex = true
            hexField.setText(ColorUtil.toHex(current))
            hexField.setSelection(hexField.text.length)
            suppressHex = false
        }
    }

    satVal.onChange = { s, v ->
        hsv[1] = s; hsv[2] = v
        current = Color.HSVToColor(hsv)
        refreshSwatch(true)
    }
    hueBar.onChange = { h ->
        hsv[0] = h
        satVal.hue = h
        current = Color.HSVToColor(hsv)
        refreshSwatch(true)
    }
    hexField.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (suppressHex) return
            val parsed = ColorUtil.parseOrNull(s?.toString() ?: "") ?: return
            current = parsed or 0xFF000000.toInt()
            Color.colorToHSV(current, hsv)
            satVal.hue = hsv[0]; satVal.sat = hsv[1]; satVal.value = hsv[2]; satVal.invalidate()
            hueBar.hue = hsv[0]; hueBar.invalidate()
            refreshSwatch(false)
        }
    })

    // Cancel / Done
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val cancel = TextView(context).apply {
            text = "Cancel"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(theme.secondaryText)
            setPadding(0, dp(12f), 0, dp(4f))
            setOnClickListener { dialog.dismiss() }
        }
        val done = TextView(context).apply {
            text = "Done"; textSize = 16f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(theme.btnAccentBg)
            setPadding(0, dp(12f), 0, dp(4f))
            setOnClickListener { dialog.dismiss(); onPick(current) }
        }
        addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(done, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(10f)
        })
    }

    dialog.setContentView(root)
    dialog.show()
}

/** Saturation (x) / brightness (y) square for a fixed hue. */
class SatValView @JvmOverloads constructor(context: Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    var hue = 0f
        set(v) { field = v; rebuildSat(); }
    var sat = 1f
    var value = 1f
    var onChange: ((Float, Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.WHITE
        setShadowLayer(3f * d, 0f, 0f, 0x80000000.toInt())
    }
    private val ringInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x40000000 }
    private var satShader: LinearGradient? = null
    private var valShader: LinearGradient? = null
    private val rect = RectF()
    private val radius get() = 12f * d

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        valShader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        rebuildSat()
    }

    private fun rebuildSat() {
        val w = width.toFloat()
        if (w <= 0f) return
        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        satShader = LinearGradient(0f, 0f, w, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        fillPaint.shader = satShader
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        valPaint.shader = valShader
        canvas.drawRoundRect(rect, radius, radius, valPaint)
        val cx = sat * width
        val cy = (1f - value) * height
        canvas.drawCircle(cx, cy, 9f * d, ringPaint)
        canvas.drawCircle(cx, cy, 10.5f * d, ringInner)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        sat = (e.x / width).coerceIn(0f, 1f)
        value = (1f - e.y / height).coerceIn(0f, 1f)
        onChange?.invoke(sat, value)
        invalidate()
        return true
    }
}

/** Horizontal rainbow hue slider, 0..360. */
class HueView @JvmOverloads constructor(context: Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    var hue = 0f
    var onChange: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.WHITE
        setShadowLayer(3f * d, 0f, 0f, 0x80000000.toInt())
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: LinearGradient? = null
    private val rect = RectF()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val colors = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
            0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
        )
        shader = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        barPaint.shader = shader
        canvas.drawRoundRect(rect, r, r, barPaint)
        val cx = (hue / 360f) * width
        val cy = height / 2f
        thumbFill.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawCircle(cx.coerceIn(r, width - r), cy, r - 1.5f * d, thumbFill)
        canvas.drawCircle(cx.coerceIn(r, width - r), cy, r - 1.5f * d, thumbStroke)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        hue = ((e.x / width) * 360f).coerceIn(0f, 360f)
        onChange?.invoke(hue)
        invalidate()
        return true
    }
}
