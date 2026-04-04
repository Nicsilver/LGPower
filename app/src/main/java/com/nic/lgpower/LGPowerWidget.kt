package com.nic.lgpower

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

class LGPowerWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    companion object {
        // LG NEC power toggle: address=0x04, command=0x08
        val LG_POWER_PATTERN: IntArray = buildNecPattern(0x20DF10EFL)

        /**
         * Build a standard NEC IR pulse pattern from a 32-bit code.
         *
         * The code is stored as bit-reversed bytes: 0xAABBCCDD where
         * AA = bit-reverse(address), BB = bit-reverse(~address),
         * CC = bit-reverse(command),  DD = bit-reverse(~command).
         * Each byte is transmitted MSB-first so the receiver decodes it LSB-first.
         */
        fun buildNecPattern(code: Long): IntArray {
            val buf = mutableListOf<Int>()
            buf += 9000; buf += 4500  // leader
            for (byteIndex in 3 downTo 0) {
                val b = ((code shr (byteIndex * 8)) and 0xFF).toInt()
                for (bit in 7 downTo 0) {
                    buf += 562
                    buf += if ((b shr bit) and 1 == 1) 1687 else 562
                }
            }
            buf += 562; buf += 562  // stop bit
            return buf.toIntArray()
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val intent = Intent(context, IrActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, intent, flags))
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
