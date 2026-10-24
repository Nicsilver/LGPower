package com.nic.lgpower

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.nic.lgpower.TEST_MARK" -> TestLog.mark(intent.getStringExtra("id") ?: "")
            "com.nic.lgpower.TEST_LOG" -> TestLog.enabled = intent.getBooleanExtra("on", true)
            "com.nic.lgpower.TEST_WIDGETS" -> {
                val json = TestLog.widgets()
                if (isOrderedBroadcast) {
                    setResultCode(if (json.contains("\"ok\":true")) -1 else 0)
                    setResultData(json)
                }
            }
        }
    }
}
