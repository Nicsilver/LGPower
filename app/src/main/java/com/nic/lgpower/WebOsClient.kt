package com.nic.lgpower

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebOsClient(private val context: Context) {

    private val prefs = context.getSharedPreferences("webos", Context.MODE_PRIVATE)

    companion object {
        const val TV_IP = "192.168.1.157"
        const val TV_PORT = 3000
    }

    sealed class Result {
        object Success : Result()
        object NeedsPairing : Result()
        data class Error(val message: String) : Result()
    }

    fun turnOffScreen(): Result {
        val latch = CountDownLatch(1)
        var result: Result = Result.Error("Timeout — is the TV on?")
        val savedKey = prefs.getString("client_key", null)

        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("ws://$TV_IP:$TV_PORT")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val payload = JSONObject().apply {
                    put("forcePairing", false)
                    put("pairingType", "PROMPT")
                    if (savedKey != null) put("client-key", savedKey)
                    put("manifest", JSONObject().apply {
                        put("manifestVersion", 1)
                        put("permissions", org.json.JSONArray().apply {
                            put("CONTROL_POWER")
                            put("CONTROL_DISPLAY")
                            put("CONTROL_TV_SCREEN")
                        })
                    })
                }
                ws.send(JSONObject().apply {
                    put("id", "reg_0")
                    put("type", "register")
                    put("payload", payload)
                }.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "registered" -> {
                        val key = json.optJSONObject("payload")?.optString("client-key")
                        if (!key.isNullOrEmpty()) {
                            prefs.edit().putString("client_key", key).apply()
                        }
                        ws.send(JSONObject().apply {
                            put("id", "cmd_0")
                            put("type", "request")
                            put("uri", "ssap://com.webos.service.tvpower/power/turnOffScreen")
                            put("payload", JSONObject())
                        }.toString())
                    }
                    "response" -> {
                        when (json.optString("id")) {
                            "reg_0" -> {
                                // TV is showing the pairing prompt — wait for user to accept
                                result = Result.NeedsPairing
                            }
                            "cmd_0" -> {
                                result = Result.Success
                                ws.close(1000, null)
                                latch.countDown()
                            }
                        }
                    }
                    "error" -> {
                        result = Result.Error(
                            json.optJSONObject("payload")?.optString("errorText") ?: "Unknown error"
                        )
                        ws.close(1000, null)
                        latch.countDown()
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                result = Result.Error(t.message ?: "Connection failed")
                latch.countDown()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        }

        client.newWebSocket(request, listener)
        latch.await(10, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()

        return result
    }
}
