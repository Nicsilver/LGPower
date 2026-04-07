package com.nic.lgpower

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebOsClient(private val context: Context) {

    private val prefs = context.getSharedPreferences("webos", Context.MODE_PRIVATE)

    companion object {
        const val TV_IP = "192.168.1.157"
        const val TV_PORT = 3001
    }

    sealed class Result {
        object Success : Result()
        object NeedsPairing : Result()
        data class Error(val message: String) : Result()
    }

    private fun buildClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun buildRegistration(clientKey: String?) = JSONObject().apply {
        put("id", "reg_0")
        put("type", "register")
        put("payload", JSONObject().apply {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            if (clientKey != null) put("client-key", clientKey)
            put("manifest", JSONObject().apply {
                put("manifestVersion", 1)
                put("permissions", JSONArray().apply {
                    put("CONTROL_POWER")
                    put("CONTROL_DISPLAY")
                    put("CONTROL_TV_SCREEN")
                    put("CONTROL_INPUT_TV")
                    put("CONTROL_MOUSE_AND_KEYBOARD")
                    put("CONTROL_AUDIO")
                    put("CONTROL_INPUT_TEXT")
                    put("LAUNCH")
                })
            })
        })
    }.toString()

    private fun execute(
        uri: String,
        payload: JSONObject = JSONObject(),
        timeoutSecs: Long = 6
    ): Result {
        val latch = CountDownLatch(1)
        var result: Result = Result.Error("Timeout — is the TV on and reachable?")
        val savedKey = prefs.getString("client_key", null)
        val request = Request.Builder().url("wss://$TV_IP:$TV_PORT").build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(buildRegistration(savedKey))
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
                            put("uri", uri)
                            put("payload", payload)
                        }.toString())
                    }
                    "response" -> when (json.optString("id")) {
                        "reg_0" -> result = Result.NeedsPairing
                        "cmd_0" -> {
                            result = Result.Success
                            ws.close(1000, null)
                            latch.countDown()
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

        val client = buildClient()
        client.newWebSocket(request, listener)
        latch.await(timeoutSecs, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        return result
    }

    fun turnOffScreen() = execute("ssap://com.webos.service.tvpower/power/turnOffScreen")

    // Navigation keys use the pointer input socket — a secondary WebSocket the TV provides
    // specifically for button/pointer events. sendRemoteKey via SSAP is not supported on WebOS 7+.
    fun pressKey(keyCode: String): Result {
        val latch = CountDownLatch(1)
        var result: Result = Result.Error("Timeout — is the TV on and reachable?")
        val savedKey = prefs.getString("client_key", null)
        val request = Request.Builder().url("wss://$TV_IP:$TV_PORT").build()
        val client = buildClient()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(buildRegistration(savedKey))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "registered" -> {
                        val key = json.optJSONObject("payload")?.optString("client-key")
                        if (!key.isNullOrEmpty()) {
                            prefs.edit().putString("client_key", key).apply()
                        }
                        // Request the pointer input socket URL
                        ws.send(JSONObject().apply {
                            put("id", "ptr_req")
                            put("type", "request")
                            put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                            put("payload", JSONObject())
                        }.toString())
                    }
                    "response" -> when (json.optString("id")) {
                        "reg_0" -> {
                            result = Result.NeedsPairing
                            ws.close(1000, null)
                            latch.countDown()
                        }
                        "ptr_req" -> {
                            val socketPath = json.optJSONObject("payload")?.optString("socketPath")
                            ws.close(1000, null)
                            if (socketPath.isNullOrEmpty()) {
                                result = Result.Error("TV did not provide a pointer socket")
                                latch.countDown()
                                return
                            }
                            // Connect to the pointer socket and send the key event
                            val ptrRequest = Request.Builder().url(socketPath).build()
                            client.newWebSocket(ptrRequest, object : WebSocketListener() {
                                override fun onOpen(pws: WebSocket, response: Response) {
                                    pws.send("type:button\nname:$keyCode\n\n")
                                    Thread.sleep(200) // let TV process before closing
                                    pws.close(1000, null)
                                    result = Result.Success
                                    latch.countDown()
                                }
                                override fun onFailure(pws: WebSocket, t: Throwable, response: Response?) {
                                    result = Result.Error("Ptr: ${t.message}")
                                    latch.countDown()
                                }
                            })
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
                // Do NOT count down here: the pointer socket onOpen hasn't fired yet.
                // The latch is released exclusively by the pointer socket callbacks (onOpen/onFailure)
                // or by the error/NeedsPairing branches above.
            }
        }

        client.newWebSocket(request, listener)
        latch.await(10, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        return result
    }

    fun pressEnter() = pressKey("ENTER")
    fun pressUp() = pressKey("UP")
    fun pressDown() = pressKey("DOWN")
    fun pressLeft() = pressKey("LEFT")
    fun pressRight() = pressKey("RIGHT")

    fun launchApp(appId: String) = execute(
        "ssap://system.launcher/launch",
        JSONObject().put("id", appId)
    )

    fun launchYouTube() = launchApp("youtube.leanback.v4")
    fun launchStremio() = launchApp("io.strem.tv")

    /** Persistent pointer socket session for touchpad use. Call close() when done. */
    inner class PointerSession {
        private val httpClient = buildClient()
        @Volatile private var pointerWs: WebSocket? = null

        init {
            val savedKey = prefs.getString("client_key", null)
            httpClient.newWebSocket(
                Request.Builder().url("wss://$TV_IP:$TV_PORT").build(),
                object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        ws.send(buildRegistration(savedKey))
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                        when (json.optString("type")) {
                            "registered" -> {
                                val key = json.optJSONObject("payload")?.optString("client-key")
                                if (!key.isNullOrEmpty()) prefs.edit().putString("client_key", key).apply()
                                ws.send(JSONObject().apply {
                                    put("id", "ptr_req")
                                    put("type", "request")
                                    put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                                    put("payload", JSONObject())
                                }.toString())
                            }
                            "response" -> if (json.optString("id") == "ptr_req") {
                                val socketPath = json.optJSONObject("payload")?.optString("socketPath")
                                ws.close(1000, null)
                                if (!socketPath.isNullOrEmpty()) {
                                    httpClient.newWebSocket(
                                        Request.Builder().url(socketPath).build(),
                                        object : WebSocketListener() {
                                            override fun onOpen(pws: WebSocket, response: Response) {
                                                pointerWs = pws
                                            }
                                        }
                                    )
                                }
                            }
                            "error" -> ws.close(1000, null)
                        }
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {}
                }
            )
        }

        fun move(dx: Float, dy: Float) {
            pointerWs?.send("type:move\ndx:${dx.toInt()}\ndy:${dy.toInt()}\n\n")
        }

        fun click() {
            pointerWs?.send("type:click\n\n")
        }

        fun close() {
            pointerWs?.close(1000, null)
            httpClient.dispatcher.executorService.shutdown()
        }
    }

    fun openPointerSession() = PointerSession()

    fun volumeUp()     = pressKey("VOLUMEUP")
    fun volumeDown()   = pressKey("VOLUMEDOWN")
    fun muteToggle()   = pressKey("MUTE")
    fun sendText(text: String) = execute(
        "ssap://com.webos.service.ime/insertText",
        JSONObject().put("text", text).put("replace", 0)
    )

}
