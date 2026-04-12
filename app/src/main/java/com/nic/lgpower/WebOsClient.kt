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

    val tvIp: String get() = prefs.getString("tv_ip", DEFAULT_TV_IP) ?: DEFAULT_TV_IP

    fun saveTvIp(ip: String) { prefs.edit().putString("tv_ip", ip).apply() }

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
                put("appVersion", "1.1")
                put("permissions", JSONArray().apply {
                    put("LAUNCH"); put("LAUNCH_WEBAPP"); put("APP_TO_APP"); put("CLOSE")
                    put("TEST_OPEN"); put("TEST_PROTECTED")
                    put("CONTROL_AUDIO"); put("CONTROL_DISPLAY")
                    put("CONTROL_INPUT_JOYSTICK"); put("CONTROL_INPUT_MEDIA_RECORDING")
                    put("CONTROL_INPUT_MEDIA_PLAYBACK"); put("CONTROL_INPUT_TV")
                    put("CONTROL_POWER"); put("CONTROL_TV_SCREEN")
                    put("READ_APP_STATUS"); put("READ_CURRENT_CHANNEL")
                    put("READ_INPUT_DEVICE_LIST"); put("READ_NETWORK_STATE")
                    put("READ_RUNNING_APPS"); put("READ_TV_CHANNEL_LIST")
                    put("WRITE_NOTIFICATION_TOAST"); put("READ_POWER_STATE")
                    put("READ_COUNTRY_INFO"); put("CONTROL_INPUT_TEXT")
                    put("CONTROL_MOUSE_AND_KEYBOARD"); put("READ_INSTALLED_APPS")
                    put("READ_SETTINGS")
                })
                put("signatures", JSONArray().put(JSONObject().apply {
                    put("signatureVersion", 1)
                    put("signature",
                        "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmct" +
                        "Y2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aF" +
                        "NwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWS" +
                        "rthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLK" +
                        "qTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJh" +
                        "GrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o" +
                        "/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw=="
                    )
                }))
                put("signed", JSONObject().apply {
                    put("appId", "com.lge.test")
                    put("created", "20140509")
                    put("vendorId", "com.lge")
                    put("serial", "2f930e2d2cfe083771f68e4fe7bb07")
                    put("localizedAppNames", JSONObject().apply {
                        put("", "LG Remote App")
                        put("ko-KR", "리모컨 앱")
                        put("zxx-XX", "ЛГ Rэмotэ AПП")
                    })
                    put("localizedVendorNames", JSONObject().put("", "LG Electronics"))
                    put("permissions", JSONArray().apply {
                        put("TEST_SECURE"); put("CONTROL_INPUT_TEXT")
                        put("CONTROL_MOUSE_AND_KEYBOARD"); put("READ_INSTALLED_APPS")
                        put("READ_LGE_SDX"); put("READ_NOTIFICATIONS"); put("SEARCH")
                        put("WRITE_SETTINGS"); put("WRITE_NOTIFICATION_ALERT")
                        put("CONTROL_POWER"); put("READ_CURRENT_CHANNEL")
                        put("READ_RUNNING_APPS"); put("READ_UPDATE_INFO")
                        put("UPDATE_FROM_REMOTE_APP"); put("READ_LGE_TV_INPUT_EVENTS")
                        put("READ_TV_CURRENT_TIME")
                    })
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
            Request.Builder().url("wss://$tvIp:$TV_PORT").build(),
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
                Request.Builder().url("wss://$tvIp:$TV_PORT").build(),
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
        fun scroll(dx: Float, dy: Float) { pointerWs?.send("type:scroll\ndx:${dx.toInt()}\ndy:${dy.toInt()}\n\n") }
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
    fun turnOff()       = execute("ssap://system/turnOff")

    /** Returns true if screen is currently off, false if on, null on failure. */
    fun getScreenOff(): Boolean? {
        val latch = CountDownLatch(1)
        var result: Boolean? = null
        val savedKey = prefs.getString("client_key", null)
        val client = buildClient()
        client.newWebSocket(
            Request.Builder().url("wss://$tvIp:$TV_PORT").build(),
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
                                put("id", "cmd_power")
                                put("type", "request")
                                put("uri", "ssap://com.webos.service.tvpower/power/getPowerState")
                                put("payload", JSONObject())
                            }.toString())
                        }
                        "response" -> if (json.optString("id") == "cmd_power") {
                            val state = json.optJSONObject("payload")?.optString("state")
                            if (state != null) result = state == "Screen Off"
                            ws.close(1000, null)
                            latch.countDown()
                        }
                        "error" -> { ws.close(1000, null); latch.countDown() }
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { latch.countDown() }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { latch.countDown() }
            }
        )
        latch.await(6, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        return result
    }

    fun pressEnter() = pressKey("ENTER")
    fun pressUp()    = pressKey("UP")
    fun pressDown()  = pressKey("DOWN")
    fun pressLeft()  = pressKey("LEFT")
    fun pressRight() = pressKey("RIGHT")

    fun volumeUp()      = pressKey("VOLUMEUP")
    fun volumeDown()    = pressKey("VOLUMEDOWN")
    fun muteToggle()    = pressKey("MUTE")

    data class VolumeState(val volume: Int, val muted: Boolean)

    fun getVolume(): VolumeState? {
        val latch = CountDownLatch(1)
        var level: VolumeState? = null
        val savedKey = prefs.getString("client_key", null)
        val client = buildClient()
        client.newWebSocket(
            Request.Builder().url("wss://$tvIp:$TV_PORT").build(),
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
                                put("id", "cmd_vol")
                                put("type", "request")
                                put("uri", "ssap://audio/getVolume")
                                put("payload", JSONObject())
                            }.toString())
                        }
                        "response" -> if (json.optString("id") == "cmd_vol") {
                            val payload = json.optJSONObject("payload")
                            // Some WebOS versions nest volume inside volumeStatus
                            val nested = payload?.optJSONObject("volumeStatus")
                            val src = nested ?: payload
                            val vol = src?.optInt("volume", -1)?.takeIf { it >= 0 }
                            // Top-level uses "muted", nested volumeStatus uses "muteStatus"
                            val muted = if (nested != null)
                                nested.optBoolean("muteStatus", false)
                            else
                                payload?.optBoolean("muted", false) ?: false
                            if (vol != null) level = VolumeState(vol, muted)
                            ws.close(1000, null)
                            latch.countDown()
                        }
                        "error" -> { ws.close(1000, null); latch.countDown() }
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { latch.countDown() }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { latch.countDown() }
            }
        )
        latch.await(6, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        return level
    }

    fun brightnessUp()   = adjustBrightness(+5)
    fun brightnessDown() = adjustBrightness(-5)

    fun setBrightness(level: Int) = execute(
        "ssap://settings/setSystemSettings",
        JSONObject()
            .put("category", "picture")
            .put("settings", JSONObject()
                .put("energySaving", "off")
                .put("backlight", level.toString()))
    )

    fun setVolume(level: Int) = execute(
        "ssap://audio/setVolume",
        JSONObject().put("volume", level)
    )

    fun getBrightness(): Int? {
        val latch = CountDownLatch(1)
        var level: Int? = null
        val savedKey = prefs.getString("client_key", null)
        val client = buildClient()
        client.newWebSocket(
            Request.Builder().url("wss://$tvIp:$TV_PORT").build(),
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
                                put("id", "cmd_get")
                                put("type", "request")
                                put("uri", "ssap://settings/getSystemSettings")
                                put("payload", JSONObject()
                                    .put("category", "picture")
                                    .put("keys", JSONArray().put("backlight")))
                            }.toString())
                        }
                        "response" -> if (json.optString("id") == "cmd_get") {
                            level = json.optJSONObject("payload")
                                ?.optJSONObject("settings")
                                ?.optString("backlight")
                                ?.toIntOrNull()
                            ws.close(1000, null)
                            latch.countDown()
                        }
                        "error" -> { ws.close(1000, null); latch.countDown() }
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { latch.countDown() }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { latch.countDown() }
            }
        )
        latch.await(6, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        return level
    }

    private fun adjustBrightness(delta: Int): Result {
        val latch = CountDownLatch(1)
        var result: Result = Result.Error("Timeout — is the TV on and reachable?")
        val savedKey = prefs.getString("client_key", null)

        val client = buildClient()
        client.newWebSocket(
            Request.Builder().url("wss://$tvIp:$TV_PORT").build(),
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
                                put("id", "cmd_get")
                                put("type", "request")
                                put("uri", "ssap://settings/getSystemSettings")
                                put("payload", JSONObject()
                                    .put("category", "picture")
                                    .put("keys", JSONArray().put("backlight")))
                            }.toString())
                        }
                        "response" -> when (json.optString("id")) {
                            "reg_0" -> { result = Result.NeedsPairing; ws.close(1000, null); latch.countDown() }
                            "cmd_get" -> {
                                val current = json.optJSONObject("payload")
                                    ?.optJSONObject("settings")
                                    ?.optString("backlight")
                                    ?.toIntOrNull()
                                if (current == null) {
                                    result = Result.Error("Could not read backlight")
                                    ws.close(1000, null)
                                    latch.countDown()
                                    return
                                }
                                val newVal = (current + delta).coerceIn(0, 100)
                                ws.send(JSONObject().apply {
                                    put("id", "cmd_set")
                                    put("type", "request")
                                    put("uri", "ssap://settings/setSystemSettings")
                                    put("payload", JSONObject()
                                        .put("category", "picture")
                                        .put("settings", JSONObject()
                                            .put("energySaving", "off")
                                            .put("backlight", newVal.toString())))
                                }.toString())
                            }
                            "cmd_set" -> {
                                val returnValue = json.optJSONObject("payload")?.optBoolean("returnValue", true)
                                result = if (returnValue == false)
                                    Result.Error("Could not set backlight")
                                else
                                    Result.Success
                                ws.close(1000, null)
                                latch.countDown()
                            }
                        }
                        "error" -> {
                            result = Result.Error(json.optJSONObject("payload")?.optString("errorText") ?: "Unknown error")
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
        latch.await(8, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        return result
    }

    data class InputSource(val id: String, val label: String)

    fun getExternalInputList(): Pair<List<InputSource>, String?> {
        val latch = CountDownLatch(1)
        var inputs = emptyList<InputSource>()
        var errorMsg: String? = null
        val savedKey = prefs.getString("client_key", null)
        val client = buildClient()
        client.newWebSocket(
            Request.Builder().url("wss://$tvIp:$TV_PORT").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { ws.send(buildRegistration(savedKey)) }
                override fun onMessage(ws: WebSocket, text: String) {
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (json.optString("type")) {
                        "registered" -> {
                            val key = json.optJSONObject("payload")?.optString("client-key")
                            if (!key.isNullOrEmpty()) prefs.edit().putString("client_key", key).apply()
                            ws.send(JSONObject().apply {
                                put("id", "cmd_0")
                                put("type", "request")
                                put("uri", "ssap://tv/getExternalInputList")
                                put("payload", JSONObject())
                            }.toString())
                        }
                        "response" -> if (json.optString("id") == "cmd_0") {
                            val devices = json.optJSONObject("payload")?.optJSONArray("devices")
                            if (devices != null) {
                                val list = mutableListOf<InputSource>()
                                for (i in 0 until devices.length()) {
                                    val d = devices.getJSONObject(i)
                                    val id = d.optString("id").takeIf { it.isNotEmpty() } ?: continue
                                    val label = d.optString("label").ifEmpty { id }
                                    list.add(InputSource(id, label))
                                }
                                inputs = list
                            } else {
                                errorMsg = "No inputs found"
                            }
                            ws.close(1000, null); latch.countDown()
                        }
                        "error" -> { errorMsg = json.optJSONObject("payload")?.optString("errorText") ?: "Error"; ws.close(1000, null); latch.countDown() }
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { errorMsg = t.message; latch.countDown() }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { latch.countDown() }
            }
        )
        latch.await(8, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        return Pair(inputs, errorMsg)
    }

    fun switchInput(inputId: String) = execute(
        "ssap://tv/switchInput",
        JSONObject().put("inputId", inputId)
    )

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

    // ── App list ──────────────────────────────────────────────────────────────

    data class TvApp(val id: String, val title: String, val iconUrl: String? = null)

    /** Returns (apps, errorMessage). errorMessage is non-null when something went wrong. */
    fun listApps(): Pair<List<TvApp>, String?> {
        val latch = CountDownLatch(1)
        var apps = emptyList<TvApp>()
        var errorMsg: String? = null
        val savedKey = prefs.getString("client_key", null)

        val client = buildClient()
        client.newWebSocket(
            Request.Builder().url("wss://$tvIp:$TV_PORT").build(),
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
                                put("uri", "ssap://com.webos.applicationManager/listLaunchPoints")
                                put("payload", JSONObject())
                            }.toString())
                        }
                        "response" -> when (json.optString("id")) {
                            "reg_0" -> { errorMsg = "Needs pairing"; ws.close(1000, null); latch.countDown() }
                            "cmd_0" -> {
                                val payload = json.optJSONObject("payload")
                                val arr = payload?.optJSONArray("launchPoints")
                                if (arr != null) {
                                    val seen = mutableSetOf<String>()
                                    val list = mutableListOf<TvApp>()
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.getJSONObject(i)
                                        // skip hidden entries
                                        if (!obj.optBoolean("visible", true)) continue
                                        val id = obj.optString("appId").takeIf { it.isNotEmpty() }
                                            ?: obj.optString("id")
                                        val title = obj.optString("title")
                                        val icon = obj.optString("largeIcon").takeIf { it.isNotEmpty() }
                                            ?: obj.optString("icon").takeIf { it.isNotEmpty() }
                                        if (id.isNotEmpty() && title.isNotEmpty() && seen.add(id)) {
                                            list.add(TvApp(id, title, icon))
                                        }
                                    }
                                    apps = list.sortedBy { it.title.lowercase() }
                                } else {
                                    errorMsg = "Unexpected response: ${payload?.toString()?.take(200)}"
                                }
                                ws.close(1000, null)
                                latch.countDown()
                            }
                        }
                        "error" -> {
                            val payload = json.optJSONObject("payload")
                            errorMsg = payload?.optString("errorText")?.takeIf { it.isNotEmpty() }
                                ?: payload?.toString()
                                ?: json.toString().take(300)
                            ws.close(1000, null)
                            latch.countDown()
                        }
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    errorMsg = t.message ?: "Connection failed"
                    latch.countDown()
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { latch.countDown() }
            }
        )
        val completed = latch.await(10, TimeUnit.SECONDS)
        if (!completed) errorMsg = "Timeout"
        client.dispatcher.executorService.shutdown()
        return Pair(apps, errorMsg)
    }

    fun saveShortcuts(apps: List<TvApp>) {
        val arr = JSONArray()
        apps.forEach { app ->
            arr.put(JSONObject().apply {
                put("id", app.id)
                put("title", app.title)
                if (app.iconUrl != null) put("iconUrl", app.iconUrl)
            })
        }
        prefs.edit().putString("app_shortcuts", arr.toString()).apply()
    }

    fun loadShortcuts(): List<TvApp> {
        val json = prefs.getString("app_shortcuts", null) ?: return defaultShortcuts()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                TvApp(obj.getString("id"), obj.getString("title"), obj.optString("iconUrl").takeIf { s -> s.isNotEmpty() })
            }
        }.getOrElse { defaultShortcuts() }
    }

    fun fetchIcon(url: String): android.graphics.Bitmap? = runCatching {
        val response = buildClient().newCall(Request.Builder().url(url).build()).execute()
        response.body?.byteStream()?.use { android.graphics.BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun iconFile(appId: String) =
        java.io.File(context.filesDir, "icon_${appId.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.png")

    /** Fetch icon from TV, persist to disk, and extract+save its dominant color. */
    fun cacheIcon(appId: String, url: String): android.graphics.Bitmap? {
        val bmp = fetchIcon(url) ?: return null
        runCatching {
            iconFile(appId).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        runCatching {
            val palette = androidx.palette.graphics.Palette.from(bmp).generate()
            val raw = palette.getVibrantColor(palette.getDominantColor(0xFF333355.toInt()))
            // darken so it reads well against white text
            val color = android.graphics.Color.rgb(
                (android.graphics.Color.red(raw) * 0.6f).toInt(),
                (android.graphics.Color.green(raw) * 0.6f).toInt(),
                (android.graphics.Color.blue(raw) * 0.6f).toInt()
            )
            prefs.edit().putInt("color_$appId", color).apply()
        }
        return bmp
    }

    /** Load a previously cached icon from disk, or null if not cached yet. */
    fun loadCachedIcon(appId: String): android.graphics.Bitmap? {
        val file = iconFile(appId)
        if (!file.exists()) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** Load the dominant color extracted from the cached icon, or null if not cached. */
    fun loadCachedColor(appId: String): Int? =
        brandColor(appId)
            ?: if (prefs.contains("color_$appId")) prefs.getInt("color_$appId", 0) else null

    companion object {
        const val DEFAULT_TV_IP = ""
        const val TV_PORT = 3001

        // Curated brand colors for common apps — checked before palette extraction
        private val BRAND_COLORS = mapOf(
            // Google / YouTube
            "youtube.leanback.v4"           to 0xFFCC0000.toInt(), // YouTube
            "youtubemusic.leanback.v4"      to 0xFF9C0A28.toInt(), // YouTube Music
            "youtube.kids"                  to 0xFFCC0000.toInt(), // YouTube Kids
            // Netflix
            "netflix"                       to 0xFFAA0A12.toInt(),
            // Amazon
            "amazon"                        to 0xFF0073AA.toInt(), // Prime Video
            // Spotify
            "com.spotify.tvv2"              to 0xFF157A3C.toInt(),
            "com.spotify.tv"                to 0xFF157A3C.toInt(),
            // Disney
            "com.disney.disneyplus-prod"    to 0xFF0D2D9E.toInt(),
            "com.disney.disneyplus"         to 0xFF0D2D9E.toInt(),
            // Apple
            "com.apple.appletv"             to 0xFF2A2A2A.toInt(),
            // HBO / Max
            "com.hbo.hbonow"               to 0xFF3D1580.toInt(),
            "com.wbd.stream"               to 0xFF3D1580.toInt(),
            // Hulu
            "com.hulu.livingroomplus"       to 0xFF0D7A3E.toInt(),
            // Twitch
            "tv.twitch"                     to 0xFF5B2DAD.toInt(),
            // Plex
            "com.plexapp.plex"              to 0xFF7A5600.toInt(),
            // Stremio
            "io.strem.tv"                   to 0xFF7B2FBE.toInt(),
            // Crunchyroll
            "crunchyroll.leanback.v4"       to 0xFFA34D16.toInt(),
            "com.crunchyroll.crunchyroid"   to 0xFFA34D16.toInt(),
            // Tubi
            "com.tubitv"                    to 0xFFA33200.toInt(),
            // Peacock
            "com.peacocktv.peacockandroid"  to 0xFF00407A.toInt(),
            // Paramount+
            "com.paramount.plus"            to 0xFF003399.toInt(),
            "com.cbs.paramount"             to 0xFF003399.toInt(),
            // Discovery+
            "com.discoveryplus.tv"          to 0xFF0047B2.toInt(),
            // SkyShowtime
            "com.skyshowtime.skyshowtime"   to 0xFF001F6E.toInt(),
            // Viaplay
            "com.viaplay.tvapp"             to 0xFF7A003C.toInt(),
            "viaplay.tvapp"                 to 0xFF7A003C.toInt(),
            // Jellyfin
            "com.mb.jellyfin"               to 0xFF005580.toInt(),
            "org.jellyfin.androidtv"        to 0xFF005580.toInt(),
            // DRTV
            "drtv.webos"                    to 0xFFAA0000.toInt(),
            "com.drtv.smart"                to 0xFFAA0000.toInt(),
            // ESPN
            "com.espn.score_center"         to 0xFF8C0000.toInt(),
            // Moonlight
            "com.limelight"                 to 0xFF1A4D80.toInt(),
            // Kodi
            "org.xbmc.kodi"                 to 0xFF1A3A6B.toInt(),
        )

        fun brandColor(appId: String): Int? = BRAND_COLORS[appId]
    }

    private fun defaultShortcuts() = listOf(
        TvApp("youtube.leanback.v4", "YouTube"),
        TvApp("io.strem.tv", "Stremio")
    )
}
