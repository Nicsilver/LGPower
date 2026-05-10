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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebOsClient(private val context: Context) {

    private val prefs = context.getSharedPreferences("webos", Context.MODE_PRIVATE)

    val tvIp: String get() = prefs.getString("tv_ip", DEFAULT_TV_IP) ?: DEFAULT_TV_IP
    val tvMac: String get() = prefs.getString("tv_mac", "") ?: ""

    fun saveTvIp(ip: String) { prefs.edit().putString("tv_ip", ip).apply() }
    fun saveTvMac(mac: String) { prefs.edit().putString("tv_mac", mac).apply() }

    fun getMacFromDevice(): String? {
        if (buildWsRequest() == null) return null
        val reply = commandSession().send(
            "ssap://com.webos.service.connectionmanager/getinfo",
            JSONObject(), timeoutSecs = 8
        )
        val payload = (reply as? CmdReply.Ok)?.payload ?: return null
        return payload.optJSONObject("wifiInfo")?.optString("macAddress")?.takeIf { it.isNotEmpty() }
            ?: payload.optJSONObject("wiredInfo")?.optString("macAddress")?.takeIf { it.isNotEmpty() }
    }

    fun sendWakeOnLan() {
        val mac = tvMac.ifEmpty { return }
        try {
            val macBytes = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
            if (macBytes.size != 6) return
            val packet = ByteArray(6 + 16 * 6)
            for (i in 0..5) packet[i] = 0xFF.toByte()
            for (i in 0..15) for (j in 0..5) packet[6 + i * 6 + j] = macBytes[j]
            java.net.DatagramSocket().use { socket ->
                socket.broadcast = true
                val addr = java.net.InetAddress.getByName("255.255.255.255")
                socket.send(java.net.DatagramPacket(packet, packet.size, addr, 9))
            }
        } catch (_: Exception) { }
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

    private fun buildWsRequest(): Request? =
        if (tvIp.isBlank()) null else Request.Builder().url("wss://$tvIp:$TV_PORT").build()

    // ── Persistent command session ────────────────────────────────────────────
    // One shared WebSocket connection for all SSAP commands. Requests are multiplexed
    // by unique ID — multiple threads can send concurrently without per-command TLS overhead.

    private sealed class CmdReply {
        data class Ok(val payload: JSONObject?) : CmdReply()
        object NeedsPairing : CmdReply()
        data class Err(val msg: String) : CmdReply()
    }

    private enum class CmdState { CONNECTING, READY, NEEDS_PAIRING, DEAD }

    private inner class CommandSession {

        private val http = buildClient()
        @Volatile private var ws: WebSocket? = null
        @Volatile private var state = CmdState.CONNECTING
        private val readyLatch = CountDownLatch(1)
        private val pending = ConcurrentHashMap<String, Pair<AtomicReference<CmdReply>, CountDownLatch>>()
        private val seq = AtomicInteger(0)

        init {
            val req = buildWsRequest()
            if (req == null) {
                state = CmdState.DEAD; readyLatch.countDown()
            } else {
                val savedKey = prefs.getString("client_key", null)
                http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        this@CommandSession.ws = ws
                        ws.send(buildRegistration(savedKey))
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                        val type = json.optString("type")
                        val id   = json.optString("id")
                        when {
                            type == "registered" -> {
                                val key = json.optJSONObject("payload")?.optString("client-key")
                                if (!key.isNullOrEmpty()) prefs.edit().putString("client_key", key).apply()
                                if (prefs.getString("tv_mac", "").isNullOrEmpty()) {
                                    ws.send(JSONObject().apply {
                                        put("id", "s_mac"); put("type", "request")
                                        put("uri", "ssap://com.webos.service.connectionmanager/getinfo")
                                    }.toString())
                                }
                                state = CmdState.READY
                                readyLatch.countDown()
                            }
                            type == "response" && id == "reg_0" -> {
                                // TV challenged registration — user must accept pairing prompt
                                state = CmdState.NEEDS_PAIRING
                                readyLatch.countDown()
                                failAll(CmdReply.NeedsPairing)
                            }
                            type == "response" && id == "s_mac" -> {
                                val p = json.optJSONObject("payload")
                                val mac = p?.optJSONObject("wifiInfo")?.optString("macAddress")?.takeIf { it.isNotEmpty() }
                                    ?: p?.optJSONObject("wiredInfo")?.optString("macAddress")?.takeIf { it.isNotEmpty() }
                                if (!mac.isNullOrEmpty()) prefs.edit().putString("tv_mac", mac).apply()
                            }
                            type == "response" -> {
                                val (ref, latch) = pending.remove(id) ?: return
                                val payload = json.optJSONObject("payload")
                                val reply = if (payload?.optBoolean("returnValue", true) == false)
                                    CmdReply.Err(payload.optString("errorText").ifEmpty { "Command rejected by TV" })
                                else
                                    CmdReply.Ok(payload)
                                ref.set(reply); latch.countDown()
                            }
                            type == "error" -> {
                                val (ref, latch) = pending.remove(id) ?: return
                                ref.set(CmdReply.Err(json.optJSONObject("payload")?.optString("errorText") ?: "Unknown error"))
                                latch.countDown()
                            }
                        }
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        state = CmdState.DEAD; this@CommandSession.ws = null
                        readyLatch.countDown()
                        failAll(CmdReply.Err(t.message ?: "Connection failed"))
                    }
                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        state = CmdState.DEAD; this@CommandSession.ws = null
                        failAll(CmdReply.Err("Connection closed"))
                    }
                })
            }
        }

        private fun failAll(reply: CmdReply) {
            val snapshot = HashMap(pending); pending.clear()
            snapshot.values.forEach { (ref, latch) -> ref.set(reply); latch.countDown() }
        }

        fun send(uri: String, payload: JSONObject = JSONObject(), timeoutSecs: Long = 6): CmdReply {
            readyLatch.await(8, TimeUnit.SECONDS)
            if (state != CmdState.READY) return when (state) {
                CmdState.NEEDS_PAIRING -> CmdReply.NeedsPairing
                else -> CmdReply.Err("Timeout — is the TV on and reachable?")
            }
            val id = "c${seq.incrementAndGet()}"
            val resultRef = AtomicReference<CmdReply>(CmdReply.Err("Timeout — is the TV on and reachable?"))
            val latch = CountDownLatch(1)
            pending[id] = Pair(resultRef, latch)
            val currentWs = ws
            val msg = JSONObject().apply {
                put("id", id); put("type", "request"); put("uri", uri); put("payload", payload)
            }.toString()
            if (currentWs == null || state != CmdState.READY || !currentWs.send(msg)) {
                pending.remove(id)
                return CmdReply.Err("Not connected")
            }
            if (!latch.await(timeoutSecs, TimeUnit.SECONDS)) pending.remove(id)
            return resultRef.get()
        }

        val isAlive get() = state == CmdState.READY && ws != null

        fun close() {
            state = CmdState.DEAD; ws?.close(1000, null)
            failAll(CmdReply.Err("Session closed"))
            http.dispatcher.executorService.shutdown()
        }
    }

    @Volatile private var sharedCommandSession: CommandSession? = null

    private fun commandSession(): CommandSession = synchronized(this) {
        val ex = sharedCommandSession
        if (ex?.isAlive == true) ex
        else { ex?.close(); CommandSession().also { sharedCommandSession = it } }
    }

    private fun execute(uri: String, payload: JSONObject = JSONObject(), timeoutSecs: Long = 6): Result =
        when (val r = commandSession().send(uri, payload, timeoutSecs)) {
            is CmdReply.Ok         -> Result.Success
            is CmdReply.NeedsPairing -> Result.NeedsPairing
            is CmdReply.Err        -> Result.Error(r.msg)
        }

    // ── Pointer socket session ────────────────────────────────────────────────
    // Separate persistent connection for pointer/navigation input — uses a different
    // WebSocket URL obtained at runtime, not the SSAP command socket.

    inner class PointerSession {
        private val httpClient = buildClient()
        @Volatile private var pointerWs: WebSocket? = null
        @Volatile private var alive = false
        private val readyLatch = CountDownLatch(1)

        init {
            val req = buildWsRequest()
            if (req == null) { readyLatch.countDown() } else {
            val savedKey = prefs.getString("client_key", null)
            httpClient.newWebSocket(req,
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
            } // end else
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

    @Volatile private var sharedPointerSession: PointerSession? = null

    fun pressKey(keyCode: String): Result {
        if (buildWsRequest() == null) return Result.Error("No TV IP configured")
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

    fun openPointerSession() = PointerSession()

    fun resetConnection() {
        synchronized(this) {
            sharedPointerSession?.close()
            sharedPointerSession = null
            sharedCommandSession?.close()
            sharedCommandSession = null
        }
    }

    // ── SSAP commands ─────────────────────────────────────────────────────────

    fun turnOffScreen() = execute("ssap://com.webos.service.tvpower/power/turnOffScreen")
    fun turnOff()       = execute("ssap://system/turnOff")

    fun getScreenOff(): Boolean? {
        if (buildWsRequest() == null) return null
        val reply = commandSession().send(
            "ssap://com.webos.service.tvpower/power/getPowerState", JSONObject()
        )
        return (reply as? CmdReply.Ok)?.payload?.optString("state")?.let { it == "Screen Off" }
    }

    fun pressEnter() = pressKey("ENTER")
    fun pressUp()    = pressKey("UP")
    fun pressDown()  = pressKey("DOWN")
    fun pressLeft()  = pressKey("LEFT")
    fun pressRight() = pressKey("RIGHT")

    fun volumeUp()      = pressKey("VOLUMEUP")
    fun volumeDown()    = pressKey("VOLUMEDOWN")
    fun muteToggle()    = pressKey("MUTE")
    fun channelUp()     = pressKey("CHANNELUP")
    fun channelDown()   = pressKey("CHANNELDOWN")

    data class VolumeState(val volume: Int, val muted: Boolean)

    fun getVolume(): VolumeState? {
        if (buildWsRequest() == null) return null
        val reply = commandSession().send("ssap://audio/getVolume", JSONObject())
        val payload = (reply as? CmdReply.Ok)?.payload ?: return null
        val nested = payload.optJSONObject("volumeStatus")
        val src = nested ?: payload
        val vol = src.optInt("volume", -1).takeIf { it >= 0 } ?: return null
        val muted = if (nested != null) nested.optBoolean("muteStatus", false)
                    else payload.optBoolean("muted", false)
        return VolumeState(vol, muted)
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
        if (buildWsRequest() == null) return null
        val reply = commandSession().send(
            "ssap://settings/getSystemSettings",
            JSONObject().put("category", "picture").put("keys", JSONArray().put("backlight"))
        )
        return (reply as? CmdReply.Ok)?.payload
            ?.optJSONObject("settings")?.optString("backlight")?.toIntOrNull()
    }

    private fun adjustBrightness(delta: Int): Result {
        if (buildWsRequest() == null) return Result.Error("No TV IP configured")
        val session = commandSession()
        val current = run {
            val r = session.send(
                "ssap://settings/getSystemSettings",
                JSONObject().put("category", "picture").put("keys", JSONArray().put("backlight"))
            )
            (r as? CmdReply.Ok)?.payload
                ?.optJSONObject("settings")?.optString("backlight")?.toIntOrNull()
        } ?: return Result.Error("Could not read backlight")
        return execute(
            "ssap://settings/setSystemSettings",
            JSONObject().put("category", "picture")
                .put("settings", JSONObject()
                    .put("energySaving", "off")
                    .put("backlight", (current + delta).coerceIn(0, 100).toString()))
        )
    }

    data class TvState(
        val brightness: Int,
        val volume: Int?,
        val muted: Boolean,
        val screenOff: Boolean
    )

    /** Fetch brightness, volume, and screen state in parallel over the shared session.
     *  Returns null if TV is off or not responding (e.g. Quick Start standby). */
    fun getTvState(): TvState? {
        if (buildWsRequest() == null) return null
        val session = commandSession()
        var brightness: Int? = null
        var volume: Int? = null
        var muted = false
        var screenOff = false

        val t1 = Thread {
            val r = session.send("ssap://settings/getSystemSettings",
                JSONObject().put("category", "picture").put("keys", JSONArray().put("backlight")))
            brightness = (r as? CmdReply.Ok)?.payload
                ?.optJSONObject("settings")?.optString("backlight")?.toIntOrNull()
        }
        val t2 = Thread {
            val r = session.send("ssap://audio/getVolume", JSONObject())
            if (r is CmdReply.Ok) {
                val p = r.payload
                val nested = p?.optJSONObject("volumeStatus")
                val src = nested ?: p
                volume = src?.optInt("volume", -1)?.takeIf { it >= 0 }
                muted = if (nested != null) nested.optBoolean("muteStatus", false)
                        else p?.optBoolean("muted", false) ?: false
            }
        }
        val t3 = Thread {
            val r = session.send("ssap://com.webos.service.tvpower/power/getPowerState", JSONObject())
            screenOff = (r as? CmdReply.Ok)?.payload?.optString("state") == "Screen Off"
        }

        t1.start(); t2.start(); t3.start()
        t1.join(5000); t2.join(5000); t3.join(5000)

        return brightness?.let { TvState(it, volume, muted, screenOff) }
    }

    data class InputSource(val id: String, val label: String)

    fun getExternalInputList(): Pair<List<InputSource>, String?> {
        if (buildWsRequest() == null) return Pair(emptyList(), "No TV IP configured")
        return when (val reply = commandSession().send("ssap://tv/getExternalInputList", JSONObject(), 8)) {
            is CmdReply.NeedsPairing -> Pair(emptyList(), "Pairing required")
            is CmdReply.Err          -> Pair(emptyList(), reply.msg)
            is CmdReply.Ok -> {
                val devices = reply.payload?.optJSONArray("devices")
                    ?: return Pair(emptyList(), "No inputs found")
                val list = mutableListOf<InputSource>()
                for (i in 0 until devices.length()) {
                    val d = devices.getJSONObject(i)
                    val id = d.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    list.add(InputSource(id, d.optString("label").ifEmpty { id }))
                }
                Pair(list, if (list.isEmpty()) "No inputs found" else null)
            }
        }
    }

    fun switchInput(inputId: String) = execute(
        "ssap://tv/switchInput",
        JSONObject().put("inputId", inputId)
    )

    fun setPictureMode(mode: String) = execute(
        "ssap://settings/setSystemSettings",
        JSONObject()
            .put("category", "picture")
            .put("settings", JSONObject().put("pictureMode", mode))
    )

    fun getCurrentPictureMode(): String? {
        if (buildWsRequest() == null) return null
        val reply = commandSession().send(
            "ssap://settings/getSystemSettings",
            JSONObject().put("category", "picture").put("keys", JSONArray().put("pictureMode"))
        )
        return (reply as? CmdReply.Ok)?.payload
            ?.optJSONObject("settings")?.optString("pictureMode")
    }

    fun setSoundMode(mode: String) = lunaRequest(
        "com.webos.settingsservice/setSystemSettings",
        JSONObject()
            .put("category", "sound")
            .put("settings", JSONObject().put("soundMode", mode))
    )

    private fun lunaRequest(uri: String, params: JSONObject): Result {
        if (buildWsRequest() == null) return Result.Error("No TV IP configured")
        val lunaUri = "luna://$uri"
        val session = commandSession()

        val alertReply = session.send(
            "ssap://system.notifications/createAlert",
            JSONObject().apply {
                put("message", " ")
                put("buttons", JSONArray().put(JSONObject()
                    .put("label", "").put("onClick", lunaUri).put("params", params)))
                put("onclose", JSONObject().put("uri", lunaUri).put("params", params))
                put("onfail",  JSONObject().put("uri", lunaUri).put("params", params))
            }
        )
        val alertId = (alertReply as? CmdReply.Ok)?.payload?.optString("alertId")
        if (alertId.isNullOrEmpty()) return when (alertReply) {
            is CmdReply.NeedsPairing -> Result.NeedsPairing
            is CmdReply.Err          -> Result.Error(alertReply.msg)
            else -> Result.Error("No alertId returned")
        }

        return when (val r = session.send(
            "ssap://system.notifications/closeAlert",
            JSONObject().put("alertId", alertId)
        )) {
            is CmdReply.Ok           -> Result.Success
            is CmdReply.NeedsPairing -> Result.NeedsPairing
            is CmdReply.Err          -> Result.Error(r.msg)
        }
    }

    fun getSoundMode(): String? {
        if (buildWsRequest() == null) return null
        val reply = commandSession().send(
            "ssap://settings/getSystemSettings",
            JSONObject().put("category", "sound").put("keys", JSONArray().put("soundMode"))
        )
        return (reply as? CmdReply.Ok)?.payload
            ?.optJSONObject("settings")?.optString("soundMode")
    }

    fun launchApp(appId: String) = execute(
        "ssap://system.launcher/launch",
        JSONObject().put("id", appId)
    )
    fun goHome() = execute("ssap://system.launcher/launch", JSONObject().put("id", "com.webos.app.home"), timeoutSecs = 3)
    fun launchYouTube() = launchApp("youtube.leanback.v4")
    fun launchStremio() = launchApp("io.strem.tv")

    fun sendText(text: String) = execute(
        "ssap://com.webos.service.ime/insertText",
        JSONObject().put("text", text).put("replace", 0)
    )

    // ── App list ──────────────────────────────────────────────────────────────

    data class TvApp(val id: String, val title: String, val iconUrl: String? = null)

    fun listApps(): Pair<List<TvApp>, String?> {
        if (buildWsRequest() == null) return Pair(emptyList(), "No TV IP configured")
        return when (val reply = commandSession().send(
            "ssap://com.webos.applicationManager/listLaunchPoints", JSONObject(), timeoutSecs = 10
        )) {
            is CmdReply.NeedsPairing -> Pair(emptyList(), "Needs pairing")
            is CmdReply.Err          -> Pair(emptyList(), reply.msg)
            is CmdReply.Ok -> {
                val arr = reply.payload?.optJSONArray("launchPoints")
                    ?: return Pair(emptyList(), "Unexpected response: ${reply.payload?.toString()?.take(200)}")
                val seen = mutableSetOf<String>()
                val list = mutableListOf<TvApp>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (!obj.optBoolean("visible", true)) continue
                    val id = obj.optString("appId").takeIf { it.isNotEmpty() } ?: obj.optString("id")
                    val title = obj.optString("title")
                    val icon = obj.optString("largeIcon").takeIf { it.isNotEmpty() }
                        ?: obj.optString("icon").takeIf { it.isNotEmpty() }
                    if (id.isNotEmpty() && title.isNotEmpty() && seen.add(id))
                        list.add(TvApp(id, title, icon))
                }
                Pair(list.sortedBy { it.title.lowercase() }, null)
            }
        }
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

    fun cacheIcon(appId: String, url: String): android.graphics.Bitmap? {
        val bmp = fetchIcon(url) ?: return null
        runCatching {
            iconFile(appId).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        runCatching {
            val palette = androidx.palette.graphics.Palette.from(bmp).generate()
            val raw = palette.getVibrantColor(palette.getDominantColor(0xFF333355.toInt()))
            val color = android.graphics.Color.rgb(
                (android.graphics.Color.red(raw) * 0.6f).toInt(),
                (android.graphics.Color.green(raw) * 0.6f).toInt(),
                (android.graphics.Color.blue(raw) * 0.6f).toInt()
            )
            prefs.edit().putInt("color_$appId", color).apply()
        }
        return bmp
    }

    fun loadCachedIcon(appId: String): android.graphics.Bitmap? {
        val file = iconFile(appId)
        if (!file.exists()) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun loadCachedColor(appId: String): Int? =
        brandColor(appId)
            ?: if (prefs.contains("color_$appId")) prefs.getInt("color_$appId", 0) else null

    companion object {
        const val DEFAULT_TV_IP = ""
        const val TV_PORT = 3001

        private val BRAND_COLORS = mapOf(
            "youtube.leanback.v4"           to 0xFFCC0000.toInt(),
            "youtubemusic.leanback.v4"      to 0xFF9C0A28.toInt(),
            "youtube.kids"                  to 0xFFCC0000.toInt(),
            "netflix"                       to 0xFFAA0A12.toInt(),
            "amazon"                        to 0xFF0073AA.toInt(),
            "com.spotify.tvv2"              to 0xFF157A3C.toInt(),
            "com.spotify.tv"                to 0xFF157A3C.toInt(),
            "com.disney.disneyplus-prod"    to 0xFF0D2D9E.toInt(),
            "com.disney.disneyplus"         to 0xFF0D2D9E.toInt(),
            "com.apple.appletv"             to 0xFF2A2A2A.toInt(),
            "com.hbo.hbonow"               to 0xFF3D1580.toInt(),
            "com.wbd.stream"               to 0xFF3D1580.toInt(),
            "com.hulu.livingroomplus"       to 0xFF0D7A3E.toInt(),
            "tv.twitch"                     to 0xFF5B2DAD.toInt(),
            "com.plexapp.plex"              to 0xFF7A5600.toInt(),
            "io.strem.tv"                   to 0xFF7B2FBE.toInt(),
            "crunchyroll.leanback.v4"       to 0xFFA34D16.toInt(),
            "com.crunchyroll.crunchyroid"   to 0xFFA34D16.toInt(),
            "com.tubitv"                    to 0xFFA33200.toInt(),
            "com.peacocktv.peacockandroid"  to 0xFF00407A.toInt(),
            "com.paramount.plus"            to 0xFF003399.toInt(),
            "com.cbs.paramount"             to 0xFF003399.toInt(),
            "com.discoveryplus.tv"          to 0xFF0047B2.toInt(),
            "com.skyshowtime.skyshowtime"   to 0xFF001F6E.toInt(),
            "com.viaplay.tvapp"             to 0xFF7A003C.toInt(),
            "viaplay.tvapp"                 to 0xFF7A003C.toInt(),
            "com.mb.jellyfin"               to 0xFF005580.toInt(),
            "org.jellyfin.androidtv"        to 0xFF005580.toInt(),
            "drtv.webos"                    to 0xFFAA0000.toInt(),
            "com.drtv.smart"                to 0xFFAA0000.toInt(),
            "com.espn.score_center"         to 0xFF8C0000.toInt(),
            "com.limelight"                 to 0xFF1A4D80.toInt(),
            "org.xbmc.kodi"                 to 0xFF1A3A6B.toInt(),
        )

        fun brandColor(appId: String): Int? = BRAND_COLORS[appId]
    }

    private fun defaultShortcuts() = listOf(
        TvApp("youtube.leanback.v4", "YouTube"),
        TvApp("io.strem.tv", "Stremio")
    )
}
