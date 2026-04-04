package com.nic.lgpower

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

class OkButtonWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    companion object {
        // LG NEC OK/Enter button: address=0x04, command=0x44
        // Stored as bit-reversed bytes: 0x20 DF 22 DD
        val LG_OK_PATTERN: IntArray = LGPowerWidget.buildNecPattern(0x20DF22DDL)

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_ok_layout)
            val intent = Intent(context, IrActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(IrActivity.EXTRA_PATTERN, LG_OK_PATTERN)

            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
            views.setOnClickPendingIntent(
                R.id.widget_ok_root,
                PendingIntent.getActivity(context, 2, intent, flags)
            )
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
