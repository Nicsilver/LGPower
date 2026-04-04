package com.nic.lgpower

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.ConsumerIrManager
import android.os.Build
import android.widget.RemoteViews
import android.widget.Toast

class LGPowerWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_SEND_IR) {
            Toast.makeText(context, "Tapped! Sending IR...", Toast.LENGTH_SHORT).show()
            sendLgPower(context)
        }
    }

    companion object {
        const val ACTION_SEND_IR = "com.nic.lgpower.SEND_IR"

        /**
         * LG NEC protocol power toggle: 0x20DF10EF
         *
         * NEC frame structure (38kHz carrier):
         *   - Leader:    9000µs pulse, 4500µs space
         *   - Bit '1':    562µs pulse, 1687µs space
         *   - Bit '0':    562µs pulse,  562µs space
         *   - Stop bit:   562µs pulse
         *
         * Byte order: address LSB-first, ~address LSB-first, command LSB-first, ~command LSB-first
         * 0x20DF10EF → address=0x04, command=0x08 (standard LG power)
         */
        val LG_POWER_PATTERN: IntArray = buildNecPattern(0x20DF10EF)

        private fun buildNecPattern(code: Long): IntArray {
            val buf = mutableListOf<Int>()

            // Leader
            buf += 9000
            buf += 4500

            // NEC sends 4 bytes in order: address, ~address, command, ~command
            // Each byte is sent LSB first. code format: 0xAABBCCDD (AA=address, DD=~command)
            for (byteIndex in 3 downTo 0) {
                val byte = ((code shr (byteIndex * 8)) and 0xFF).toInt()
                for (bit in 7 downTo 0) {
                    buf += 562
                    buf += if ((byte shr bit) and 1 == 1) 1687 else 562
                }
            }

            // Stop bit
            buf += 562
            buf += 562

            return buf.toIntArray()
        }

        private fun sendLgPower(context: Context) {
            val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

            if (irManager == null || !irManager.hasIrEmitter()) {
                Toast.makeText(context, "No IR blaster found", Toast.LENGTH_SHORT).show()
                return
            }

            try {
                irManager.transmit(38000, LG_POWER_PATTERN)
                // Brief visual feedback: flash the widget
                flashWidget(context)
            } catch (e: Exception) {
                Toast.makeText(context, "IR send failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        private fun flashWidget(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, LGPowerWidget::class.java)
            )
            // Re-render with "active" state briefly — simple approach:
            // just update normally (could add a flash drawable swap here if desired)
            ids.forEach { updateWidget(context, manager, it) }
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Widget tap → transparent Activity (bypasses HANS broadcast blocking)
            val intent = Intent(context, IrActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT

            val pendingIntent = PendingIntent.getActivity(context, 0, intent, piFlags)
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
