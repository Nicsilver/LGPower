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

        val client = buildClient()
        client.newWebSocket(
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
        )
        latch.await(timeoutSecs, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        return result
    }

    // ── Pointer socket session ────────────────────────────────────────────────
    // Used both for navigation key presses (shared, persistent) and the
    // touchpad overlay (dedicated, closed when the overlay is dismissed).

    inner class PointerSession {
        private val httpClient = buildClient()
        @Volatile private var pointerWs: WebSocket? = null
        @Volatile private var alive = false
        private val readyLatch = CountDownLatch(1)

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
                                                alive = true
                                                readyLatch.countDown()
                                            }
                                            override fun onClosed(pws: WebSocket, code: Int, reason: String) {
                                                alive = false
                                                pointerWs = null
                                            }
                                            override fun onFailure(pws: WebSocket, t: Throwable, response: Response?) {
                                                alive = false
                                                pointerWs = null
                                                readyLatch.countDown()
                                            }
                                        }
                                    )
                                } else {
                                    readyLatch.countDown()
                                }
                            }
                            "error" -> {
                                ws.close(1000, null)
                                readyLatch.countDown()
                            }
                        }
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        readyLatch.countDown()
                    }
                }
            )
        }

        fun waitUntilReady(timeoutSecs: Long = 6) =
            readyLatch.await(timeoutSecs, TimeUnit.SECONDS) && alive

        val isAlive get() = alive && pointerWs != null

        fun sendKey(keyCode: String) { pointerWs?.send("type:button\nname:$keyCode\n\n") }
        fun move(dx: Float, dy: Float) { pointerWs?.send("type:move\ndx:${dx.toInt()}\ndy:${dy.toInt()}\n\n") }
        fun click() { pointerWs?.send("type:click\n\n") }

        fun close() {
            alive = false
            pointerWs?.close(1000, null)
            httpClient.dispatcher.executorService.shutdown()
        }
    }

    // Shared persistent session reused across all key presses.
    // First press pays the connection cost; subsequent ones are instant.
    @Volatile private var sharedPointerSession: PointerSession? = null

    fun pressKey(keyCode: String): Result {
        val session = synchronized(this) {
            val existing = sharedPointerSession
            if (existing?.isAlive == true) existing
            else {
                existing?.close()
                PointerSession().also { sharedPointerSession = it }
            }
        }
        return if (session.waitUntilReady()) {
            session.sendKey(keyCode)
            Result.Success
        } else {
            synchronized(this) { if (sharedPointerSession === session) sharedPointerSession = null }
            Result.Error("Timeout — is the TV on and reachable?")
        }
    }

    // Dedicated session for the touchpad overlay (caller owns lifecycle).
    fun openPointerSession() = PointerSession()

    // Drop the shared session so the next key press reconnects from scratch.
    // Call from onResume() to recover after the phone was locked.
    fun resetConnection() {
        synchronized(this) {
            sharedPointerSession?.close()
            sharedPointerSession = null
        }
    }

    // ── SSAP commands ─────────────────────────────────────────────────────────

    fun turnOffScreen() = execute("ssap://com.webos.service.tvpower/power/turnOffScreen")

    fun pressEnter() = pressKey("ENTER")
    fun pressUp()    = pressKey("UP")
    fun pressDown()  = pressKey("DOWN")
    fun pressLeft()  = pressKey("LEFT")
    fun pressRight() = pressKey("RIGHT")

    fun volumeUp()   = pressKey("VOLUMEUP")
    fun volumeDown() = pressKey("VOLUMEDOWN")
    fun muteToggle() = pressKey("MUTE")

    fun launchApp(appId: String) = execute(
        "ssap://system.launcher/launch",
        JSONObject().put("id", appId)
    )
    fun launchYouTube() = launchApp("youtube.leanback.v4")
    fun launchStremio() = launchApp("io.strem.tv")

    fun sendText(text: String) = execute(
        "ssap://com.webos.service.ime/insertText",
        JSONObject().put("text", text).put("replace", 0)
    )
}
