package com.nic.lgpower

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

class AppShortcutWidgetConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        widgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        setContentView(R.layout.activity_shortcut_widget_config)

        val client = WebOsClient(this)
        val shortcuts = client.loadShortcuts()

        val list = findViewById<ListView>(R.id.shortcut_list)
        list.adapter = ShortcutPickerAdapter(this, client, shortcuts)
        list.setOnItemClickListener { _, _, position, _ ->
            val prefs = getSharedPreferences("shortcut_widgets", MODE_PRIVATE)
            prefs.edit().putInt("slot_$widgetId", position).apply()

            val manager = AppWidgetManager.getInstance(this)
            AppShortcutWidget.updateWidget(this, manager, widgetId)

            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }

    private class ShortcutPickerAdapter(
        private val context: Context,
        private val client: WebOsClient,
        private val apps: List<WebOsClient.TvApp>
    ) : BaseAdapter() {

        override fun getCount() = apps.size
        override fun getItem(pos: Int) = apps[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_shortcut_picker, parent, false)

            val app = apps[position]
            view.findViewById<TextView>(R.id.picker_label).text = app.title

            val iconView = view.findViewById<ImageView>(R.id.picker_icon)
            Thread {
                val bmp = client.loadCachedIcon(app.id) ?: makeLetterBitmap(app.title, 96)
                val circle = toCircle(bmp)
                (context as Activity).runOnUiThread { iconView.setImageBitmap(circle) }
            }.start()

            return view
        }

        private fun toCircle(src: Bitmap): Bitmap {
            val size = minOf(src.width, src.height)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = size / 2f
            canvas.drawCircle(r, r, r, paint)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(src, ((size - src.width) / 2f), ((size - src.height) / 2f), paint)
            return out
        }

        private fun makeLetterBitmap(title: String, size: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555577.toInt() }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = size * 0.4f
                textAlign = Paint.Align.CENTER
            }
            val letter = title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            canvas.drawText(letter, size / 2f, size / 2f - (text.ascent() + text.descent()) / 2f, text)
            return bmp
        }
    }
}
