package com.nic.lgpower

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.widget.RemoteViews

class AppShortcutWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("shortcut_widgets", Context.MODE_PRIVATE)
        appWidgetIds.forEach { prefs.edit().remove("slot_$it").apply() }
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val client = WebOsClient(context)
            val prefs = context.getSharedPreferences("shortcut_widgets", Context.MODE_PRIVATE)
            val slot = prefs.getInt("slot_$widgetId", -1)
            val shortcuts = client.loadShortcuts()
            val app = if (slot in shortcuts.indices) shortcuts[slot] else null

            val views = RemoteViews(context.packageName, R.layout.widget_shortcut_layout)
            val label = app?.title?.firstOrNull()?.uppercaseChar()?.toString() ?: "—"
            val color = app?.let { client.loadCachedColor(it.id) } ?: Color.parseColor("#333355")

            views.setTextViewText(R.id.shortcut_label, label)
            views.setImageViewBitmap(R.id.shortcut_bg, makeRoundedBitmap(color, 200, 200, 55f))

            val intent = if (app != null) {
                Intent(context, AppShortcutActivity::class.java).apply {
                    putExtra("app_id", app.id)
                    putExtra("app_title", app.title)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            } else {
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT

            views.setOnClickPendingIntent(
                R.id.shortcut_bg,
                PendingIntent.getActivity(context, widgetId, intent, flags)
            )
            appWidgetManager.updateAppWidget(widgetId, views)
        }

        private fun makeRoundedBitmap(color: Int, w: Int, h: Int, radius: Float): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
            return bmp
        }
    }
}
