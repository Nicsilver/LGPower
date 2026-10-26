package com.nic.lgpower

import android.content.Context
import kotlin.math.abs
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** The `tv_mac` pref is a list; older installs hold a single address. Newlines count as separators because the detail screen's field wraps. */
fun macList(s: String): List<String> = s.split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }

class WebOsClient(appContext: Context) {

    // Sessions outlive the Activity that opened them, so nothing here may hold an Activity
    private val context: Context = appContext.applicationContext

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
        return macsFrom((reply as? CmdReply.Ok)?.payload).joinToString(", ").ifEmpty { null }
    }

    // getinfo reports the Wi-Fi and the wired MAC whichever interface is plugged in,
    // and only the live one wakes the TV, so both are kept and both get a packet.
    private fun macsFrom(payload: JSONObject?): List<String> =
        listOf("wifiInfo", "wiredInfo").mapNotNull {
            payload?.optJSONObject(it)?.optString("macAddress")?.takeIf { m -> m.isNotEmpty() }
        }

    /** Adds what the TV reports to the saved MACs without dropping one typed in by hand. */
    private fun mergeMacs(found: List<String>) {
        val saved = macList(tvMac)
        val merged = (saved + found).distinctBy { it.uppercase() }
        if (merged != saved) prefs.edit().putString("tv_mac", merged.joinToString(", ")).apply()
    }

    fun sendWakeOnLan() {
        val macs = macList(tvMac).ifEmpty { return }
        val ip = tvIp
        // A TV on another VLAN never sees the limited broadcast, so also try the
        // directed broadcast of its subnet and a plain unicast (works while the
        // gateway still has an ARP entry for the sleeping TV).
        val targets = listOf("255.255.255.255", ip.substringBeforeLast('.') + ".255", ip)
        val lan = if (LanNetwork.isTethered(context, ip)) null else LanNetwork.get(context)
        for (mac in macs) {
            val macBytes = runCatching { mac.split(":", "-").map { it.toInt(16).toByte() }.toByteArray() }
                .getOrNull()?.takeIf { it.size == 6 } ?: continue
            val packet = ByteArray(6 + 16 * 6)
            for (i in 0..5) packet[i] = 0xFF.toByte()
            for (i in 0..15) for (j in 0..5) packet[6 + i * 6 + j] = macBytes[j]
            for (target in targets) {
                try {
                    java.net.DatagramSocket().use { socket ->
                        lan?.bindSocket(socket)
                        socket.broadcast = true
                        val addr = java.net.InetAddress.getByName(target)
                        socket.send(java.net.DatagramPacket(packet, packet.size, addr, 9))
                    }
                } catch (_: Exception) { }
            }
        }
    }

    sealed class Result {
        object Success : Result()
        object NeedsPairing : Result()
        data class Error(val message: String) : Result()
    }

    private fun buildClient(pingIntervalSecs: Long = 0): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .socketFactory(NoDelaySocketFactory(
                (if (LanNetwork.isTethered(context, tvIp)) null else LanNetwork.get(context))
                    ?.socketFactory ?: SocketFactory.getDefault()
            ))
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            // Read timeout bounds the TLS/upgrade handshake only; OkHttp lifts it once a
            // WebSocket is established. A booting TV can hang the handshake for 10 s+.
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .pingInterval(pingIntervalSecs, TimeUnit.SECONDS)
            .build()
    }

    // webOS 25/26 firmware silently drops registrations carrying the legacy
    // com.lge.test signed manifest (no prompt, no reply). A flat merged
    // permissions list without signatures pairs on both old and new firmware —
    // mirrors aiowebostv's webOS 26 handshake fix (home-assistant-libs #719).
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
                    put("APP_TO_APP"); put("CLOSE")
                    put("CONTROL_AUDIO"); put("CONTROL_DISPLAY")
                    put("CONTROL_INPUT_JOYSTICK"); put("CONTROL_INPUT_MEDIA_PLAYBACK")
                    put("CONTROL_INPUT_MEDIA_RECORDING"); put("CONTROL_INPUT_TEXT")
                    put("CONTROL_INPUT_TV"); put("CONTROL_MOUSE_AND_KEYBOARD")
                    put("CONTROL_POWER"); put("CONTROL_TV_SCREEN")
                    put("LAUNCH"); put("LAUNCH_WEBAPP")
                    put("READ_APP_STATUS"); put("READ_COUNTRY_INFO")
                    put("READ_CURRENT_CHANNEL"); put("READ_INPUT_DEVICE_LIST")
                    put("READ_INSTALLED_APPS"); put("READ_LGE_SDX")
                    put("READ_LGE_TV_INPUT_EVENTS"); put("READ_NETWORK_STATE")
                    put("READ_NOTIFICATIONS"); put("READ_POWER_STATE")
                    put("READ_RUNNING_APPS"); put("READ_SETTINGS")
                    put("READ_TV_CHANNEL_LIST"); put("READ_TV_CURRENT_TIME")
                    put("READ_UPDATE_INFO"); put("SEARCH")
                    put("TEST_OPEN"); put("TEST_PROTECTED"); put("TEST_SECURE")
                    put("UPDATE_FROM_REMOTE_APP")
                    put("WRITE_NOTIFICATION_ALERT"); put("WRITE_NOTIFICATION_TOAST")
                    put("WRITE_SETTINGS")
                })
            })
        })
    }.toString()

    // A hand-edited address that is not a valid host would make the builder throw
    private fun buildWsRequest(): Request? =
        if (tvIp.isBlank()) null else runCatching { Request.Builder().url("wss://$tvIp:$TV_PORT").build() }.getOrNull()

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

        @Volatile private var http: OkHttpClient? = null
        val ip = tvIp
        @Volatile private var ws: WebSocket? = null
        @Volatile private var socket: WebSocket? = null
        @Volatile var state = CmdState.CONNECTING
            private set
        @Volatile private var deadReason: String? = null
        val createdAt = System.currentTimeMillis()
        @Volatile private var lastActiveAt = createdAt
        // lastActiveAt also counts our own sends, which would hide a TV that went quiet
        @Volatile private var lastRxAt = 0L
        private val deathLogged = AtomicBoolean(false)
        // What the TV said when it closed its side; onClosed alone only knows our echo of it
        @Volatile private var peerClose: String? = null
        // The TV answered the registration with an error after showing its prompt (denied or timed out)
        @Volatile var pairingRejected = false
            private set
        // The socket completed its upgrade. A session that died without ever getting this far
        // stalled in connect/TLS, which says nothing about the TV being in standby
        @Volatile var opened = false
            private set
        private val readyLatch = CountDownLatch(1)
        private val deathLatch = CountDownLatch(1)
        private val pending = ConcurrentHashMap<String, Pair<AtomicReference<CmdReply>, CountDownLatch>>()
        private val subscriptions = ConcurrentHashMap<String, (JSONObject?) -> Unit>()
        // Callers that want whole frames, errors included, instead of the parsed payload
        private val rawListeners = ConcurrentHashMap<String, (JSONObject, String) -> Unit>()
        // The TV's answer to our registration, kept for the keyboard test's report
        @Volatile var registeredFrame: String? = null
            private set
        private val seq = AtomicInteger(0)
        val sid = "cmd${cmdSids.incrementAndGet()}"

        // Builds the client and opens the socket on the creating thread, outside the
        // shared-state lock, so a main-thread resetConnection() never waits on it
        fun connect() {
            try { open() } catch (e: Exception) {
                deadReason = e.message
                die(CmdReply.Err(e.message ?: "Connection failed"), "open threw ${e.javaClass.simpleName}: ${scrubAddresses(e.message)}")
            }
        }

        private fun open() {
            if (state == CmdState.DEAD) return
            val req = buildWsRequest()
            if (req == null) {
                state = CmdState.DEAD; readyLatch.countDown()
            } else {
                val savedKey = prefs.getString("client_key", null)
                // Pings surface a dead TV (unplugged, Wi-Fi dropped) within a couple of intervals
                // instead of leaving a silent socket that only fails once a command times out
                val client = buildClient(pingIntervalSecs = 15)
                http = client
                if (state == CmdState.DEAD) { client.dispatcher.executorService.shutdown(); return }
                TestLog.sock("cmd", sid, "connect")
                socket = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        opened = true
                        TestLog.sock("cmd", sid, "open")
                        if (state == CmdState.DEAD) { ws.close(1000, null); return }
                        this@CommandSession.ws = ws
                        ws.send(buildRegistration(savedKey))
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        lastActiveAt = System.currentTimeMillis()
                        lastRxAt = lastActiveAt
                        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                        val type = json.optString("type")
                        val id   = json.optString("id")
                        rawListeners[id]?.let { it(json, text); return }
                        when {
                            type == "registered" -> {
                                registeredFrame = text
                                // A reply that outlives a TV switch belongs to the old TV and must
                                // not overwrite the new one's key or MACs
                                val current = ip == tvIp
                                val key = json.optJSONObject("payload")?.optString("client-key")
                                if (current && !key.isNullOrEmpty()) prefs.edit().putString("client_key", key).apply()
                                // The TV can hand back the key the pointer already failed with
                                if (current) sharedPointerSession?.pairingDone()
                                // Fewer than two means a pairing from before both MACs were kept
                                if (current && macList(tvMac).size < 2) {
                                    ws.send(JSONObject().apply {
                                        put("id", "s_mac"); put("type", "request")
                                        put("uri", "ssap://com.webos.service.connectionmanager/getinfo")
                                    }.toString())
                                }
                                TestLog.sock("cmd", sid, "registered")
                                state = CmdState.READY
                                readyLatch.countDown()
                                if (current) Thread { rememberSoftwareVersion(this@CommandSession) }.start()
                            }
                            type == "response" && id == "reg_0" -> {
                                // TV challenged registration — user must accept pairing prompt
                                TestLog.sock("cmd", sid, "prompt")
                                state = CmdState.NEEDS_PAIRING
                                readyLatch.countDown()
                                failAll(CmdReply.NeedsPairing)
                            }
                            type == "error" && id == "reg_0" -> {
                                // The prompt was denied or timed out; the socket is no use any more
                                pairingRejected = state == CmdState.NEEDS_PAIRING
                                deadReason = json.optString("error").ifEmpty { null }
                                die(CmdReply.Err(deadReason ?: "Pairing failed"), "registration error: ${scrubAddresses(deadReason)}")
                                ws.close(1000, null)
                            }
                            type == "response" && id == "s_mac" -> {
                                if (ip == tvIp) mergeMacs(macsFrom(json.optJSONObject("payload")))
                            }
                            type == "response" && subscriptions.containsKey(id) ->
                                subscriptions[id]?.invoke(json.optJSONObject("payload"))
                            type == "error" && subscriptions.containsKey(id) ->
                                subscriptions[id]?.invoke(null)
                            type == "response" -> {
                                val (ref, latch) = pending.remove(id) ?: return
                                val payload = json.optJSONObject("payload")
                                val reply = if (responseFailed(json))
                                    CmdReply.Err(frameErrorText(json) ?: "Command rejected by TV")
                                else
                                    CmdReply.Ok(payload)
                                TestLog.res(sid, id, reply is CmdReply.Ok, (reply as? CmdReply.Err)?.msg)
                                ref.set(reply); latch.countDown()
                            }
                            type == "error" -> {
                                val (ref, latch) = pending.remove(id) ?: return
                                TestLog.res(sid, id, false, frameErrorText(json))
                                ref.set(CmdReply.Err(frameErrorText(json) ?: "Unknown error"))
                                latch.countDown()
                            }
                        }
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        TestLog.sock("cmd", sid, "fail", reason = t.message)
                        deadReason = t.message
                        die(CmdReply.Err(t.message ?: "Connection failed"), "failure ${t.javaClass.simpleName}: ${scrubAddresses(t.message)}")
                    }
                    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                        TestLog.sock("cmd", sid, "closing", code, reason.ifEmpty { null })
                        // A TV in standby accepts the upgrade and then closes with
                        // "Try Again Later (EWS)"; finish the handshake so onClosed fires promptly
                        deadReason = reason.ifEmpty { null }
                        peerClose = "peer closed code=$code reason=${reason.ifEmpty { "none" }}"
                        ws.close(1000, null)
                    }
                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        TestLog.sock("cmd", sid, "close", code, reason.ifEmpty { null })
                        die(CmdReply.Err("Connection closed"), peerClose ?: "closed code=$code reason=${reason.ifEmpty { "none" }}")
                    }
                })
                if (state == CmdState.DEAD) socket?.cancel()
            }
        }

        private fun die(reply: CmdReply, cause: String) {
            state = CmdState.DEAD; ws = null
            readyLatch.countDown(); deathLatch.countDown()
            failAll(reply)
            // The pointer socket has no ping of its own, so it can sit half-open after the
            // TV drops; the pinging command socket is the one that notices
            // A pointer still inside its connect deadline is left to finish: the power watcher
            // loses sessions every few seconds while the TV wakes.
            // A pointer that carried input moments ago is kept too: it is evidently still
            // delivering, and replacing it mid-drag stalls the cursor until a new command session
            // registers, then lands everything moved meanwhile as one jump
            val pointer = if (sharedCommandSession === this) {
                when {
                    sharedPointerSession == null -> "none"
                    dropPointerSession { !it.isDead && !it.isConnecting && !it.inUse() } -> "dropped"
                    else -> "kept"
                }
            } else "unshared"
            // close() runs die() again when the socket's own close callback arrives; only the first cause is the real one
            if (deathLogged.compareAndSet(false, true)) {
                val now = System.currentTimeMillis()
                val line = sessionDeathLine(cause, now - createdAt, if (lastRxAt == 0L) null else now - lastRxAt, pointer)
                android.util.Log.i(CONN_TAG, line)
                // The report closes the session itself to time a fresh one; that is not a death worth listing
                if (cause != PROBE_CLOSE) DeathLog.shared.add(line)
            }
        }

        private fun failAll(reply: CmdReply) {
            val snapshot = HashMap(pending); pending.clear()
            snapshot.values.forEach { (ref, latch) -> ref.set(reply); latch.countDown() }
        }

        fun send(uri: String, payload: JSONObject = JSONObject(), timeoutSecs: Long = 6): CmdReply {
            // Nobody reuses a session past its connect grace, so waiting longer only delays the caller's fallback
            readyLatch.await(minOf(8_000L, createdAt + CONNECT_GRACE_MS - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            if (state != CmdState.READY) return when (state) {
                CmdState.NEEDS_PAIRING -> CmdReply.NeedsPairing
                CmdState.DEAD -> CmdReply.Err("Can't connect to TV" + (deadReason?.let { " ($it)" } ?: ""))
                else -> CmdReply.Err("Timeout. Is the TV on and reachable?")
            }
            lastActiveAt = System.currentTimeMillis()
            val id = "c${seq.incrementAndGet()}"
            val resultRef = AtomicReference<CmdReply>(CmdReply.Err("Timeout. Is the TV on and reachable?"))
            val latch = CountDownLatch(1)
            pending[id] = Pair(resultRef, latch)
            val currentWs = ws
            val msg = JSONObject().apply {
                put("id", id); put("type", "request"); put("uri", uri); put("payload", payload)
            }.toString()
            if (currentWs == null || state != CmdState.READY) {
                pending.remove(id)
                return CmdReply.Err("Not connected")
            }
            // Logged before the send so the reply, which arrives on another thread, can never be logged first.
            TestLog.req(sid, id, uri)
            if (!currentWs.send(msg)) {
                pending.remove(id)
                TestLog.res(sid, id, false, "send failed")
                return CmdReply.Err("Not connected")
            }
            if (!latch.await(timeoutSecs, TimeUnit.SECONDS)) pending.remove(id)
            return resultRef.get()
        }

        fun awaitDeath() { deathLatch.await() }

        /** Closed by a resume check that found it silent, so the power watcher reconnects without its standby pause. */
        @Volatile var replacedAsStale = false

        /** True when the TV answers a request on this socket within [maxMs]. Any reply counts, an error included. */
        fun answersWithin(maxMs: Long): Boolean {
            val answered = CountDownLatch(1)
            val id = exchange("request", POWER_STATE_URI, JSONObject()) { _, _ -> answered.countDown() } ?: return false
            try {
                return awaitReply(answered, { state == CmdState.READY }, maxMs)
            } finally {
                endExchange(id, unsubscribe = false)
            }
        }

        fun subscribe(uri: String, listener: (JSONObject?) -> Unit): String? {
            val id = "s${seq.incrementAndGet()}"
            subscriptions[id] = listener
            val msg = JSONObject().apply {
                put("id", id); put("type", "subscribe"); put("uri", uri); put("payload", JSONObject())
            }.toString()
            if (state != CmdState.READY || ws?.send(msg) != true) { subscriptions.remove(id); return null }
            return id
        }

        fun unsubscribe(id: String) {
            if (subscriptions.remove(id) == null) return
            ws?.send(JSONObject().put("id", id).put("type", "unsubscribe").toString())
        }

        /** Sends a frame and passes every frame the TV tags with its id to [listener] untouched. */
        fun exchange(type: String, uri: String, payload: JSONObject, listener: (JSONObject, String) -> Unit): String? {
            val id = "k${seq.incrementAndGet()}"
            rawListeners[id] = listener
            val msg = JSONObject().apply {
                put("id", id); put("type", type); put("uri", uri); put("payload", payload)
            }.toString()
            if (state != CmdState.READY || ws?.send(msg) != true) { rawListeners.remove(id); return null }
            lastActiveAt = System.currentTimeMillis()
            return id
        }

        fun endExchange(id: String, unsubscribe: Boolean) {
            if (rawListeners.remove(id) == null || !unsubscribe) return
            ws?.send(JSONObject().put("id", id).put("type", "unsubscribe").toString())
        }

        // A session mid-handshake is shared rather than replaced: concurrent callers
        // (slider send loops, the power watcher, value refreshes) used to each open a new
        // socket and kill the previous one, so under load nothing ever finished connecting
        val isUsable get() = when (state) {
            CmdState.READY, CmdState.NEEDS_PAIRING -> ws != null
            CmdState.CONNECTING -> System.currentTimeMillis() - createdAt < CONNECT_GRACE_MS
            CmdState.DEAD -> false
        } && ip == tvIp

        /** The TV is showing its pairing prompt for this session. */
        val pairingPromptShowing get() = state == CmdState.NEEDS_PAIRING && ip == tvIp

        /** Connecting or registered: the TV is at least answering. */
        val maybeUp get() = (state == CmdState.READY || (state == CmdState.CONNECTING && isUsable)) && ip == tvIp

        /** Recent traffic or a request in flight: replacing it now would abort live work. */
        fun inUse() = state != CmdState.DEAD && ip == tvIp &&
            (pending.isNotEmpty() || System.currentTimeMillis() - lastActiveAt < RESET_KEEP_MS)

        /** Waits for the handshake to settle, for as long as the session may still count as connecting. */
        fun awaitSettled(maxMs: Long = CONNECT_GRACE_MS) {
            val left = minOf(maxMs, createdAt + CONNECT_GRACE_MS - System.currentTimeMillis())
            if (left > 0) readyLatch.await(left, TimeUnit.MILLISECONDS)
        }

        fun close(why: String = "own close") {
            val open = ws
            die(CmdReply.Err("Session closed"), why)
            if (open != null) open.close(1000, null) else socket?.cancel()
            http?.dispatcher?.executorService?.shutdown()
        }
    }

    // The sessions are process-wide: every WebOsClient (remote, settings, widgets,
    // trampolines) shares one command and one pointer connection instead of opening its own
    private fun commandSession(): CommandSession {
        val fresh: CommandSession
        val old: CommandSession?
        synchronized(stateLock) {
            val ex = sharedCommandSession
            if (ex != null && ex.isUsable) return ex
            old = ex
            fresh = CommandSession()
            sharedCommandSession = fresh
        }
        if (old != null) { old.close("replaced"); dropPointerSession { it.shouldRetire } }
        fresh.connect()
        return fresh
    }

    // A dead session stays put on purpose: it carries the retry pause and the pairing backoff
    private fun dropPointerSession(where: (PointerSession) -> Boolean): Boolean {
        val pointer = synchronized(stateLock) {
            sharedPointerSession?.takeIf(where)?.also { sharedPointerSession = null }
        }
        pointer?.close()
        return pointer != null
    }

    private fun execute(uri: String, payload: JSONObject = JSONObject(), timeoutSecs: Long = 6): Result {
        resumeGate.await(RESUME_GATE_MAX_MS)
        return when (val r = commandSession().send(uri, payload, timeoutSecs)) {
            is CmdReply.Ok         -> Result.Success
            is CmdReply.NeedsPairing -> Result.NeedsPairing
            is CmdReply.Err        -> Result.Error(r.msg)
        }
    }

    // ── Pointer socket session ────────────────────────────────────────────────
    // Separate persistent connection for d-pad keys and touchpad input — uses a different
    // WebSocket URL obtained at runtime, not the SSAP command socket. One instance is shared
    // by everything; it is created cheaply and connects on a background thread, so it is safe
    // to ask for it from the UI thread.

    private enum class PtrState { CONNECTING, READY, DEAD }

    inner class PointerSession {
        @Volatile private var httpClient: OkHttpClient? = null
        @Volatile private var regSocket: WebSocket? = null
        @Volatile private var regOpen = false
        private val attempts = HandshakeAttempts()
        @Volatile private var ptrSocket: WebSocket? = null
        @Volatile private var pointerWs: WebSocket? = null
        @Volatile var failedAt = 0L
            private set
        @Volatile private var state = PtrState.CONNECTING
        @Volatile private var handedOff = false
        val ip = tvIp
        private val createdAt = System.currentTimeMillis()
        @Volatile private var lastActiveAt = createdAt
        // The pointer's own handshake gets POINTER_CONNECT_MS from the moment it may register,
        // not from creation: it waits for the command session first, and two slow
        // registrations in a row would otherwise eat the whole budget. Nothing outlives
        // CONNECT_GRACE_MS from creation, which is also as long as a caller waits for it.
        @Volatile private var connectDeadline = createdAt + POINTER_CONNECT_MS
        private val waiters = AtomicInteger(0)
        val sid = "ptr${ptrSids.incrementAndGet()}"
        private val frameSeq = AtomicInteger(0)
        private val readyLatch = CountDownLatch(1)

        /** Closed on purpose (replaced or reset) rather than failed, so it carries no retry pause. */
        @Volatile var retired = false
            private set

        // Registering without a valid key makes the TV show a pairing prompt, so this session
        // never retries that: it stays dead until the command session has stored a new key
        @Volatile private var needsPairing = false
        @Volatile private var pairingAt = 0L
        @Volatile private var keyUsed: String? = null

        // Input sent before the socket is open waits here and goes out on open
        private val lock = Any()
        private val queue = PointerQueue(System::currentTimeMillis, MAX_QUEUED_FRAMES, STALE_FRAME_MS)

        // Same sharing rule as the command session: a session that is still connecting
        // is kept, not replaced, so a held key can't keep killing every attempt
        val isUsable get() = when (state) {
            PtrState.READY -> pointerWs != null
            PtrState.CONNECTING -> System.currentTimeMillis() < connectDeadline
            PtrState.DEAD -> false
        } && ip == tvIp

        val isDead get() = state == PtrState.DEAD
        val isConnecting get() = state == PtrState.CONNECTING &&
            System.currentTimeMillis() < connectDeadline && ip == tvIp
        val shouldRetire get() = ip != tvIp || (state != PtrState.DEAD && !isUsable)

        val idleMs get() = System.currentTimeMillis() - lastActiveAt

        /** Recent traffic or a key waiting on it: replacing it now would abort live work. */
        fun inUse() = state != PtrState.DEAD && ip == tvIp &&
            (waiters.get() > 0 || System.currentTimeMillis() - lastActiveAt < RESET_KEEP_MS)

        fun pairingDone() { needsPairing = false }

        private fun markPairingMissing() { needsPairing = true; pairingAt = System.currentTimeMillis() }

        /** True while a retry would only ask the TV for pairing again. The block lapses so a lost reply can't pin it. */
        fun pairingStillMissing() = needsPairing &&
            System.currentTimeMillis() - pairingAt < PAIRING_BLOCK_MS &&
            prefs.getString("client_key", null).orEmpty() == keyUsed.orEmpty()

        fun connect() {
            TestLog.sock("ptr", sid, "connect")
            try { open(attempts.number()) } catch (e: Exception) { fail() }
        }

        // The TV sometimes answers the command socket and leaves this registration socket
        // without any TLS or upgrade reply. The read timeout would end it after 3 s and fail the
        // press with most of its deadline unused, while a fresh socket opens in about 100 ms.
        private fun retryUnopenedRegistration(attempt: Int) {
            val stale: WebSocket?
            val staleClient: OkHttpClient?
            val next: Int
            synchronized(lock) {
                if (state != PtrState.CONNECTING || retired) return
                if (connectDeadline - System.currentTimeMillis() < MIN_RETRY_LEFT_MS) return
                next = attempts.retry(attempt) ?: return
                stale = regSocket
                staleClient = httpClient
            }
            stale?.cancel()
            staleClient?.dispatcher?.executorService?.shutdown()
            try { open(next) } catch (e: Exception) { fail() }
        }

        private fun open(attempt: Int) {
            if (state == PtrState.DEAD) return
            val req = buildWsRequest()
            if (req == null) { fail(); return }
            val savedKey = prefs.getString("client_key", null)
            keyUsed = savedKey
            if (savedKey.isNullOrEmpty()) { markPairingMissing(); fail(); return }
            val client = buildClient()
            httpClient = client
            if (state == PtrState.DEAD) { client.dispatcher.executorService.shutdown(); return }
            if (attempts.mayRetry(attempt)) {
                watchdog.schedule({ retryUnopenedRegistration(attempt) }, REGISTRATION_OPEN_MS, TimeUnit.MILLISECONDS)
            }
            TestLog.sock("reg", sid, "connect")
            regSocket = client.newWebSocket(req,
                object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        if (state == PtrState.DEAD || !attempts.markOpened(attempt)) { ws.close(1000, null); return }
                        regOpen = true
                        TestLog.sock("reg", sid, "open")
                        // Waits on its own thread: blocking this one would hold back the close a
                        // TV in standby sends straight after the upgrade
                        gateExecutor.execute { registerWhenCommandHas(ws) }
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        if (!attempts.isCurrent(attempt)) return
                        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                        when (json.optString("type")) {
                            "registered" -> {
                                TestLog.sock("reg", sid, "registered")
                                val key = json.optJSONObject("payload")?.optString("client-key")
                                if (ip == tvIp && !key.isNullOrEmpty()) prefs.edit().putString("client_key", key).apply()
                                ws.send(JSONObject().apply {
                                    put("id", "ptr_req")
                                    put("type", "request")
                                    put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                                    put("payload", JSONObject())
                                }.toString())
                            }
                            "response" -> when (json.optString("id")) {
                                "ptr_req" -> {
                                    val socketPath = json.optJSONObject("payload")?.optString("socketPath")
                                    handedOff = true
                                    ws.close(1000, null)
                                    if (!socketPath.isNullOrEmpty()) openPointerSocket(client, socketPath, attempt)
                                    else fail()
                                }
                                // The TV is asking the user to accept a pairing prompt
                                "reg_0" -> {
                                    TestLog.sock("reg", sid, "prompt")
                                    markPairingMissing()
                                    ws.close(1000, null)
                                    fail()
                                }
                            }
                            // Any other error is an ordinary failure: only the prompt itself blocks retries
                            "error" -> {
                                ws.close(1000, null)
                                fail()
                            }
                        }
                    }
                    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                        TestLog.sock("reg", sid, "closing", code, reason.ifEmpty { null })
                        ws.close(1000, null)
                        if (!handedOff && attempts.isCurrent(attempt)) fail()
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        TestLog.sock("reg", sid, "fail", reason = t.message)
                        if (handedOff || !attempts.isCurrent(attempt)) return
                        // No reply at all (refused, reset, TLS, timeout) is left to the pending
                        // retry instead of failing the press; an answer from the TV is final
                        if (!regOpen && response == null && attempts.mayRetry(attempt)) return
                        fail()
                    }
                }
            )
            if (state == PtrState.DEAD) regSocket?.cancel()
        }

        private fun registerWhenCommandHas(ws: WebSocket) {
            connectDeadline = createdAt + CONNECT_GRACE_MS
            val verdict = awaitCommandRegistered(this, connectDeadline)
            if (verdict == CmdState.READY) {
                connectDeadline = minOf(System.currentTimeMillis() + POINTER_CONNECT_MS, createdAt + CONNECT_GRACE_MS)
            }
            // Closed, past its deadline, or for a TV the user has since switched away from
            if (!isConnecting) { ws.close(1000, null); fail(); return }
            when (verdict) {
                CmdState.READY -> {
                    val key = prefs.getString("client_key", null)
                    // Registering without a key is a pairing request
                    if (key.isNullOrEmpty()) { ws.close(1000, null); fail(); return }
                    keyUsed = key
                    ws.send(buildRegistration(key))
                }
                CmdState.NEEDS_PAIRING -> { markPairingMissing(); ws.close(1000, null); fail() }
                else -> { ws.close(1000, null); fail() }
            }
        }

        private fun openPointerSocket(client: OkHttpClient, socketPath: String, attempt: Int) {
            TestLog.sock("ptr", sid, "connect")
            ptrSocket = client.newWebSocket(
                Request.Builder().url(socketPath).build(),
                object : WebSocketListener() {
                    override fun onOpen(pws: WebSocket, response: Response) {
                        synchronized(lock) {
                            if (state == PtrState.DEAD || !attempts.isCurrent(attempt)) { pws.close(1000, null); return }
                            pointerWs = pws
                            state = PtrState.READY
                            lastActiveAt = System.currentTimeMillis()
                            val queued = queue.size
                            val out = queue.drain()
                            out.forEach { pws.send(it) }
                            TestLog.sock("ptr", sid, "open")
                            if (queued > 0) TestLog.flush(sid, queued, out.size)
                        }
                        readyLatch.countDown()
                    }
                    override fun onClosing(pws: WebSocket, code: Int, reason: String) {
                        TestLog.sock("ptr", sid, "closing", code, reason.ifEmpty { null })
                        pws.close(1000, null)
                        fail()
                    }
                    override fun onClosed(pws: WebSocket, code: Int, reason: String) {
                        TestLog.sock("ptr", sid, "close", code, reason.ifEmpty { null })
                        fail()
                    }
                    override fun onFailure(pws: WebSocket, t: Throwable, response: Response?) {
                        TestLog.sock("ptr", sid, "fail", reason = t.message)
                        fail()
                    }
                }
            )
        }

        private fun fail(deliberate: Boolean = false) {
            synchronized(lock) {
                if (state != PtrState.DEAD) TestLog.sock("ptr", sid, "dead", deliberate = deliberate)
                if (!deliberate && state != PtrState.DEAD) failedAt = System.currentTimeMillis()
                if (deliberate) retired = true
                state = PtrState.DEAD
                pointerWs = null
                queue.clear()
            }
            readyLatch.countDown()
        }

        // Waits out the connect deadline in isUsable, so a caller that gave up with a
        // timeout can not have its key sent by a handshake that finishes later
        fun waitUntilReady(): Boolean {
            waiters.incrementAndGet()
            try {
                while (true) {
                    val left = connectDeadline - System.currentTimeMillis()
                    if (left <= 0) {
                        // Nothing else stops a handshake at its deadline once the registration
                        // went out, and a socket that opened later would send this key anyway
                        synchronized(lock) { if (state == PtrState.CONNECTING) fail() }
                        return state == PtrState.READY
                    }
                    if (readyLatch.await(left, TimeUnit.MILLISECONDS)) return state == PtrState.READY
                }
            } finally {
                waiters.decrementAndGet()
            }
        }

        // A socket that refuses a frame is gone, and with no ping of its own nothing else would say so
        private fun deliver(ok: Boolean): Boolean {
            if (ok) lastActiveAt = System.currentTimeMillis() else fail()
            return ok
        }

        /** True when a live socket took the frame or it is queued for the one still opening. */
        private fun post(frame: String, kind: PointerQueue.Kind, name: String? = null, dx: Int = 0, dy: Int = 0): Boolean = synchronized(lock) {
            val ws = pointerWs
            when {
                ws != null -> deliver(ws.send(frame)).also { logFrame(kind, if (it) "sent" else "dropped", name, dx, dy) }
                state == PtrState.DEAD -> false.also { logFrame(kind, "dropped", name, dx, dy) }
                else -> { queue.add(kind, frame); logFrame(kind, "queued", name, dx, dy); true }
            }
        }

        private fun logFrame(kind: PointerQueue.Kind, st: String, name: String?, dx: Int, dy: Int) {
            val n = frameSeq.incrementAndGet()
            when (kind) {
                PointerQueue.Kind.KEY -> TestLog.key(sid, n, st, name ?: "")
                PointerQueue.Kind.MOVE -> TestLog.move("move", sid, n, st, dx, dy)
                PointerQueue.Kind.SCROLL -> TestLog.move("scroll", sid, n, st, dx, dy)
                PointerQueue.Kind.CLICK -> TestLog.click(sid, n, st)
            }
        }

        fun sendKey(keyCode: String) = post("type:button\nname:$keyCode\n\n", PointerQueue.Kind.KEY, name = keyCode)
        fun scroll(dx: Int, dy: Int) = post("type:scroll\ndx:$dx\ndy:$dy\n\n", PointerQueue.Kind.SCROLL, dx = dx, dy = dy)
        fun click() = post("type:click\n\n", PointerQueue.Kind.CLICK)

        fun move(dx: Int, dy: Int) {
            if (dx == 0 && dy == 0) return
            synchronized(lock) {
                val ws = pointerWs
                when {
                    ws != null -> deliver(ws.send(PointerQueue.moveFrame(dx, dy))).also { logFrame(PointerQueue.Kind.MOVE, if (it) "sent" else "dropped", null, dx, dy) }
                    state == PtrState.DEAD -> logFrame(PointerQueue.Kind.MOVE, "dropped", null, dx, dy)
                    else -> { queue.addMove(dx, dy); logFrame(PointerQueue.Kind.MOVE, "queued", null, dx, dy) }
                }
            }
        }

        fun close() {
            val open = pointerWs
            fail(deliberate = true)
            if (open != null) open.close(1000, null) else ptrSocket?.cancel()
            if (!handedOff) { if (regOpen) regSocket?.close(1000, null) else regSocket?.cancel() }
            httpClient?.dispatcher?.executorService?.shutdown()
        }
    }

    // Null while the TV is showing a pairing prompt for the command session: registering a
    // second time with the same stale key could replace that prompt
    private fun pointerSession(): PointerSession? {
        val fresh: PointerSession
        val old: PointerSession?
        synchronized(stateLock) {
            val ex = sharedPointerSession
            if (ex != null && ex.isUsable) return ex
            if (sharedCommandSession?.pairingPromptShowing == true) return null
            // A TV in standby closes every socket it accepts; without this pause each
            // touchpad frame would open another connection
            if (ex != null && ex.ip == tvIp) {
                if (ex.pairingStillMissing()) return ex
                if (System.currentTimeMillis() - ex.failedAt < POINTER_RETRY_MS) return ex
            }
            old = ex
            fresh = PointerSession()
            sharedPointerSession = fresh
        }
        old?.close()
        connectExecutor.execute { fresh.connect() }
        return fresh
    }

    // The pointer registers with the same key as the command session. A key the TV doesn't know
    // turns every registration into a pairing prompt, and two at once can show two prompts or have
    // one replace the other, so the pointer only registers once the command session has and never
    // asks on its own. Its TLS handshake still runs alongside the command session's.
    private fun awaitCommandRegistered(pointer: PointerSession, deadline: Long): CmdState {
        var connected = false
        while (pointer.isConnecting) {
            val shared = sharedCommandSession
            val mine = shared != null && shared.ip == pointer.ip
            val session = when {
                shared != null && mine && shared.state == CmdState.NEEDS_PAIRING -> return CmdState.NEEDS_PAIRING
                // A session that opened and then died (a TV in standby accepts the upgrade and closes)
                // is the power watcher's to retry at its own pace: a connect from here would add one
                // per key press. One that never opened stalled in connect/TLS, so it falls through to
                // the single replacement below
                shared != null && mine && shared.state == CmdState.DEAD &&
                    (shared.opened || shared.pairingRejected) -> return CmdState.DEAD
                shared != null && mine && shared.isUsable -> shared
                // None yet, or one stuck past its connect grace
                !connected -> { connected = true; commandSession() }
                else -> return CmdState.DEAD
            }
            when (session.state) {
                CmdState.READY, CmdState.NEEDS_PAIRING -> return session.state
                CmdState.DEAD -> return CmdState.DEAD
                CmdState.CONNECTING -> {
                    val now = System.currentTimeMillis()
                    val left = minOf(deadline, session.createdAt + CONNECT_GRACE_MS) - now
                    if (left <= 0) return CmdState.DEAD
                    // Settles on any outcome, including the session being replaced; the next round
                    // looks at whichever one is shared by then
                    session.awaitSettled(left)
                }
            }
        }
        return CmdState.DEAD
    }

    /** Opens the shared pointer connection if there isn't a usable one. Never blocks. */
    fun warmPointerSession() {
        // Without a key the first registration is the pairing itself, and that belongs to the
        // command session
        if (tvIp.isNotBlank() && !prefs.getString("client_key", null).isNullOrEmpty()) pointerSession()
    }

    // A key press, unlike touchpad frames, is worth waiting out the retry pause for: the pause
    // only exists to stop a stream of frames from opening a connection each
    private fun keySession(): PointerSession? {
        var session = pointerSession() ?: return null
        if (session.isDead && !session.retired && session.ip == tvIp && !session.pairingStillMissing()) {
            val wait = POINTER_RETRY_MS - (System.currentTimeMillis() - session.failedAt)
            if (wait > 0) Thread.sleep(wait)
            session = pointerSession() ?: return null
        }
        return session
    }

    fun pressKey(keyCode: String): Result {
        if (buildWsRequest() == null) return Result.Error("No TV IP configured")
        resumeGate.await(RESUME_GATE_MAX_MS)
        var session = keySession() ?: return Result.NeedsPairing
        var replaced = false
        var resent = false
        while (true) {
            when {
                session.waitUntilReady() -> {
                    if (session.sendKey(keyCode)) return Result.Success
                    // A press meant for a TV the user has since switched away from must not reach the new one
                    if (resent || session.ip != tvIp) return Result.Error("Lost the connection to the TV. Try again")
                    resent = true
                    session = keySession() ?: return Result.NeedsPairing
                }
                session.pairingStillMissing() -> return Result.NeedsPairing
                // Another caller replaced the session at its deadline; the new one gets its own wait
                session.retired && !replaced && session.ip == tvIp -> {
                    replaced = true
                    session = keySession() ?: return Result.NeedsPairing
                }
                else -> return Result.Error("Timeout. Is the TV on and reachable?")
            }
        }
    }

    // Touchpad input: never blocks, safe from the UI thread
    fun pointerMove(dx: Int, dy: Int) { if (tvIp.isNotBlank()) pointerSession()?.move(dx, dy) }
    fun pointerScroll(dx: Int, dy: Int) { if (tvIp.isNotBlank()) pointerSession()?.scroll(dx, dy) }
    fun pointerClick() = afterResumeCheck { pointerSession()?.click() }
    fun pointerKey(keyCode: String) = afterResumeCheck { pointerSession()?.sendKey(keyCode) }

    // A tap on a half-open pointer is lost without any error, so it waits for the resume verdict
    // like a key does. Moves are streams of frames and stay unheld.
    private fun afterResumeCheck(send: () -> Unit) {
        if (tvIp.isBlank()) return
        resumeGate.runAfter(RESUME_GATE_MAX_MS, tapExecutor, send)
    }

    /**
     * Drops both connections so the next use opens fresh ones; recovers sockets gone stale while
     * the phone slept. A connection that carried traffic moments ago, or has work in flight, is
     * kept: a second widget tap or an app resume must not abort the first one's command.
     */
    fun resetConnection() {
        val pointer: PointerSession?
        val command: CommandSession?
        synchronized(stateLock) {
            pointer = sharedPointerSession?.takeUnless { it.inUse() }
            if (pointer != null) sharedPointerSession = null
            command = sharedCommandSession?.takeUnless { it.inUse() }
            if (command != null) sharedCommandSession = null
        }
        pointer?.close()
        command?.close("reset")
    }

    /**
     * Run when the remote comes back to the front, in place of [resetConnection]. Sockets the TV
     * still answers on are kept, so the first key costs one round trip instead of a new
     * handshake; ones it doesn't answer on are replaced. The pointer has no ping, so the check
     * runs on the command socket beside it.
     */
    fun resumeConnection() {
        val command = sharedCommandSession
        val step = resumeStep(commandReady = command != null && command.state == CmdState.READY && command.ip == tvIp)
        if (step == ResumeStep.RESET) { resetConnection(); return }
        val session = command!!
        val oldPointer = sharedPointerSession
        // Before anything can pick the old pointer up
        val check = resumeGate.begin()
        // The TV never writes on the pointer, so a close it sent while the phone dozed is never heard
        if (oldPointer != null && shouldReplaceIdlePointer(oldPointer.isDead, oldPointer.idleMs, RESUME_POINTER_IDLE_MS) &&
            dropPointerSession { sameSession(it, oldPointer) && !it.inUse() }) warmPointerSession()
        Thread({
            try {
                // A TV that resets the socket kills it before the verdict, and the power watcher
                // must not read that as standby
                if (probeCommand({ session.replacedAsStale = it }) { session.answersWithin(RESUME_PROBE_MS) }) {
                    TestLog.sock("cmd", session.sid, "resume-keep")
                } else {
                    TestLog.sock("cmd", session.sid, "resume-stale")
                    replaceStale(session, oldPointer)
                }
            } finally {
                resumeGate.end(check)
            }
        }, "resume-check").start()
    }

    // Unlike resetConnection this ignores recent traffic: anything sent since the resume went
    // into a socket that has just failed to answer
    private fun replaceStale(command: CommandSession, pointer: PointerSession?) {
        synchronized(stateLock) {
            if (sharedCommandSession === command) sharedCommandSession = null
        }
        command.replacedAsStale = true
        // A pointer opened since the resume is fresh and is left to finish its handshake
        dropPointerSession { sameSession(it, pointer) }
        command.close("silent on resume")
        warmPointerSession()
    }

    // ── Power state ───────────────────────────────────────────────────────────

    /** What the TV last told us, fed by [watchPower]. */
    sealed class Presence {
        object Unknown : Presence() { override fun toString() = "Unknown" }
        object Unreachable : Presence() { override fun toString() = "Unreachable" }
        object NeedsPairing : Presence() { override fun toString() = "NeedsPairing" }
        data class Reported(val state: String, val processing: String?) : Presence() {
            // "Screen Off" still counts as on: webOS runs and takes commands, only the panel is dark
            val isOn get() = state !in OFF_STATES && OFF_TRANSITIONS.none { processing?.contains(it) == true }
            val screenOff get() = state == "Screen Off"
        }
    }

    @Volatile var presence: Presence = Presence.Unknown
        private set
    private val presenceLock = ReentrantLock()
    private val presenceChanged = presenceLock.newCondition()

    private fun publish(p: Presence, listener: (Presence) -> Unit) {
        presenceLock.withLock { presence = p; presenceChanged.signalAll() }
        android.util.Log.d(TAG, "presence: $p")
        listener(p)
    }

    /**
     * Blocks until the watcher has reported something, or [timeoutMs] elapses. While the shared
     * command session is still mid-handshake, or registered with its power report still due, the
     * wait runs on to [whileConnectingMs], since on a slow Wi-Fi an answer is still on its way.
     */
    fun awaitPresence(timeoutMs: Long, whileConnectingMs: Long = timeoutMs): Presence {
        val start = System.currentTimeMillis()
        presenceLock.withLock {
            while (presence is Presence.Unknown) {
                // A session that registers late must still get time for its power report: cutting
                // the wait there would leave Unknown, which the power button reads as "on"
                val limit = if (commandMaybeUp()) whileConnectingMs else timeoutMs
                val left = start + limit - System.currentTimeMillis()
                if (left <= 0) break
                // The session leaving the connecting state does not signal the condition
                presenceChanged.await(minOf(left, 250L), TimeUnit.MILLISECONDS)
            }
        }
        return presence
    }

    /** The TV is registered or still registering on the shared command session. */
    fun commandMaybeUp() = sharedCommandSession?.maybeUp == true

    /**
     * Keeps the shared command session open and subscribed to the TV's power state,
     * reporting every change to [listener]. Reachability is judged by whether a session
     * registers, never by a TCP probe: a TV in standby keeps port 3001 open but closes
     * every new session with "Try Again Later (EWS)". A socket that drops after a while is
     * retried at once so a Wi-Fi blip doesn't blink the indicator; a refused or short-lived
     * one every [WATCH_RETRY_MS]. Returns a stop function.
     */
    fun watchPower(listener: (Presence) -> Unit): () -> Unit {
        presenceLock.withLock { presence = Presence.Unknown }
        val thread = Thread {
            var delayMs = 0L
            try {
                while (true) {
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val session = commandSession()
                    // A slow Wi-Fi can take longer than a few seconds to register; only a
                    // session that outlives its grace period counts as unreachable
                    session.awaitSettled()
                    when (session.state) {
                        CmdState.READY -> {
                            val sub = session.subscribe(POWER_STATE_URI) { p ->
                                val state = p?.optString("state").orEmpty()
                                when {
                                    p == null -> publish(Presence.Unknown, listener)
                                    // A payload without a state says nothing about the TV, so it must not read as "on"
                                    state.isEmpty() -> {}
                                    else -> publish(Presence.Reported(state, p.optString("processing").ifEmpty { null }), listener)
                                }
                            }
                            val since = System.currentTimeMillis()
                            try { session.awaitDeath() } finally { sub?.let(session::unsubscribe) }
                            delayMs = if (System.currentTimeMillis() - since < 2_000 && !session.replacedAsStale) WATCH_RETRY_MS else 0
                        }
                        CmdState.NEEDS_PAIRING -> {
                            publish(Presence.NeedsPairing, listener)
                            // The TV answers on this same socket once the prompt is accepted
                            while (session.state == CmdState.NEEDS_PAIRING) Thread.sleep(250)
                            // A refused or timed-out prompt would come straight back if retried at once
                            delayMs = when {
                                session.state == CmdState.READY -> 0
                                session.pairingRejected -> PAIRING_RETRY_MS
                                else -> WATCH_RETRY_MS
                            }
                        }
                        else -> {
                            publish(Presence.Unreachable, listener)
                            delayMs = WATCH_RETRY_MS
                        }
                    }
                }
            } catch (_: InterruptedException) { }
        }
        thread.start()
        return { thread.interrupt() }
    }

    // ── SSAP commands ─────────────────────────────────────────────────────────

    fun turnOffScreen() = execute("ssap://com.webos.service.tvpower/power/turnOffScreen")
    fun turnOnScreen()  = execute("ssap://com.webos.service.tvpower/power/turnOnScreen")
    fun turnOff()       = execute("ssap://system/turnOff")

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

    // An AV receiver or soundbar on ARC/optical often ignores setVolume (the TV only
    // relays volume keys to it over CEC), so callers step with the keys instead.
    @Volatile var volumeNeedsKeys = false
        private set

    private fun noteSoundOutput(status: JSONObject?) {
        status ?: return
        val output = status.optString("soundOutput")
        volumeNeedsKeys = !status.optBoolean("adjustVolume", true) ||
            (output.isNotEmpty() && output !in TV_OWN_OUTPUTS)
    }

    fun getVolume(): VolumeState? {
        if (buildWsRequest() == null) return null
        val reply = commandSession().send("ssap://audio/getVolume", JSONObject())
        val payload = (reply as? CmdReply.Ok)?.payload ?: return null
        val nested = payload.optJSONObject("volumeStatus")
        noteSoundOutput(nested)
        val src = nested ?: payload
        val vol = src.optInt("volume", -1).takeIf { it >= 0 } ?: return null
        val muted = if (nested != null) nested.optBoolean("muteStatus", false)
                    else payload.optBoolean("muted", false)
        return VolumeState(vol, muted)
    }

    fun brightnessUp()   = adjustBrightness(+5)
    fun brightnessDown() = adjustBrightness(-5)

    // Writing picture settings needs WRITE_SETTINGS, which the TV only grants to
    // the legacy signed manifest we no longer send (see buildRegistration). The
    // Luna alert route works with the plain registration on every firmware.
    fun setBrightness(level: Int) = lunaRequest(
        "com.webos.settingsservice/setSystemSettings",
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

    // Some sets ignore setVolume (external speakers over ARC/optical, or a burst of
    // requests during a drag) while the volume keys still work, so read the level back
    // and step the rest of the way.
    fun settleVolume(target: Int) {
        val actual = getVolume()?.volume ?: return
        val delta = target - actual
        if (delta == 0 || abs(delta) > 40) return
        repeat(abs(delta)) {
            if (delta > 0) volumeUp() else volumeDown()
            Thread.sleep(60)
        }
    }

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
        return setBrightness((current + delta).coerceIn(0, 100))
    }

    data class TvState(val brightness: Int?, val volume: Int?, val muted: Boolean)

    /** Fetch brightness and volume in parallel over the shared session.
     *  Returns null if the TV answered neither. */
    fun getTvState(): TvState? {
        if (buildWsRequest() == null) return null
        val session = commandSession()
        var brightness: Int? = null
        var volume: Int? = null
        var muted = false

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
                noteSoundOutput(nested)
                val src = nested ?: p
                volume = src?.optInt("volume", -1)?.takeIf { it >= 0 }
                muted = if (nested != null) nested.optBoolean("muteStatus", false)
                        else p?.optBoolean("muted", false) ?: false
            }
        }
        t1.start(); t2.start()
        t1.join(5000); t2.join(5000)

        return if (brightness == null && volume == null) null else TvState(brightness, volume, muted)
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
                if (list.isNotEmpty()) prefs.edit().putString(inputsCacheKey(), JSONArray().also { arr ->
                    list.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label)) }
                }.toString()).apply()
                Pair(list, if (list.isEmpty()) "No inputs found" else null)
            }
        }
    }

    private fun inputsCacheKey() = TvStore.activeId(prefs)?.let { "inputs_cache_$it" } ?: "inputs_cache"

    // Last list the TV gave us, so the wake picker still offers inputs while the TV is off
    fun cachedInputs(): List<InputSource> {
        val json = prefs.getString(inputsCacheKey(), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> InputSource(o.getString("id"), o.getString("label")) } }
        }.getOrDefault(emptyList())
    }

    fun switchInput(inputId: String) = execute(
        "ssap://tv/switchInput",
        JSONObject().put("inputId", inputId)
    )

    fun setPictureMode(mode: String) = lunaRequest(
        "com.webos.settingsservice/setSystemSettings",
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
        resumeGate.await(RESUME_GATE_MAX_MS)
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
    /**
     * The factory menus are the launcher's `com.webos.app.factorywin` app with an `irKey`, the same
     * call ColorControl and bscpylgtv make. inStop (factory reset) and tilt (locks the remote until a
     * reboot) have no entry here on purpose.
     */
    enum class ServiceMenu(val irKey: String) { IN_START("inStart"), EZ_ADJUST("ezAdjust") }

    fun launchServiceMenu(menu: ServiceMenu) = execute(
        "ssap://system.launcher/launch",
        JSONObject().put("id", FACTORY_APP_ID).put(
            "params", JSONObject().put("id", "executeFactory").put("irKey", menu.irKey)
        )
    )

    fun closeServiceMenu() = execute(
        "ssap://system.launcher/close",
        JSONObject().put("id", FACTORY_APP_ID)
    )

    /** Null when the TV doesn't say, which callers must not read as "not open". */
    fun foregroundAppId(): String? {
        if (buildWsRequest() == null) return null
        val reply = commandSession().send(
            "ssap://com.webos.applicationManager/getForegroundAppInfo", JSONObject(), 3
        )
        return (reply as? CmdReply.Ok)?.payload?.optString("appId")?.takeIf { it.isNotEmpty() }
    }

    fun goHome() = execute("ssap://system.launcher/launch", JSONObject().put("id", "com.webos.app.home"), timeoutSecs = 3)

    // Answers as soon as webOS is up without changing what is on screen, so a TV that
    // was woken over the network stays on whatever it boots into.
    fun probe() = execute("ssap://system/getSystemInfo", timeoutSecs = 3)

    fun afterWake(): Result {
        val a = WakeAction.get(prefs)
        return when (a.kind) {
            WakeAction.STAY  -> probe()
            WakeAction.INPUT -> execute("ssap://tv/switchInput", JSONObject().put("inputId", a.id), timeoutSecs = 3)
            WakeAction.APP   -> execute("ssap://system.launcher/launch", JSONObject().put("id", a.id), timeoutSecs = 3)
            else             -> goHome()
        }
    }
    fun launchYouTube() = launchApp("youtube.leanback.v4")
    fun launchStremio() = launchApp("io.strem.tv")

    // replace is a boolean in the IME service's schema; 0 is accepted by older firmware only by luck
    fun sendText(text: String) = execute(
        "ssap://com.webos.service.ime/insertText",
        JSONObject().put("text", text).put("replace", false)
    )

    sealed class TypeResult {
        object Typed : TypeResult()
        object NeedsPairing : TypeResult()
        /** The TV answered the typing call with a permission error and no fallback got text in. */
        object Refused : TypeResult()
        data class Failed(val message: String) : TypeResult()
    }

    /** Types into the TV's focused field. A permission refusal is retried through the notification route when its effect can be seen. */
    fun typeText(text: String): TypeResult {
        return when (val r = sendText(text)) {
            is Result.Success -> TypeResult.Typed
            is Result.NeedsPairing -> TypeResult.NeedsPairing
            is Result.Error ->
                if (!isPermissionError(r.message)) TypeResult.Failed(r.message)
                else if (typeThroughNotification(text)) TypeResult.Typed
                else TypeResult.Refused
        }
    }

    // The notification service runs the call as itself, which is how settings writes get past the same
    // 401 on webOS 26. Whether the IME honours it is unknown, so it only counts when the keyboard
    // subscription shows the text length grow; without that the answer is "no" rather than a guess.
    private fun typeThroughNotification(text: String): Boolean {
        val before = kbdTextLength
        if (kbdFocus != KeyboardFocus.FIELD || before < 0) return false
        if (lunaRequest("com.webos.service.ime/insertText",
                JSONObject().put("text", text).put("replace", false)) !is Result.Success) return false
        val until = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < until) {
            if (kbdTextLength > before) return true
            Thread.sleep(100)
        }
        return false
    }

    /** Opens typed text on the TV browser: a link as typed, anything else as a web search. */
    fun openInBrowser(text: String) = execute(
        "ssap://system.launcher/open",
        JSONObject().put("target", TypingSupport.browserTarget(text))
    )

    @Volatile private var kbdFocus = KeyboardFocus.UNKNOWN
    @Volatile private var kbdTextLength = -1

    private fun noteKeyboardFrame(frame: JSONObject) {
        val focus = keyboardFocusOf(frame)
        if (focus != KeyboardFocus.UNKNOWN) kbdFocus = focus
        if (focus == KeyboardFocus.NO_FIELD) kbdTextLength = -1
        keyboardTextLengthOf(frame)?.let { kbdTextLength = it }
    }

    /** Reads the TV's software version once per connection and stores it for the active TV. */
    private fun rememberSoftwareVersion(session: CommandSession) {
        runCatching {
            fun payloadOf(uri: String, payload: JSONObject) =
                (session.send(uri, payload, 4) as? CmdReply.Ok)?.payload
            // webOS 26 refuses the first with a permission error, so the system property service gets a turn
            val version = TypingSupport.versionOf(payloadOf("ssap://com.webos.service.update/getCurrentSWInformation", JSONObject()))
                ?: TypingSupport.versionOf(payloadOf("ssap://com.webos.service.tv.systemproperty/getSystemInfo",
                    JSONObject().put("keys", JSONArray().put("firmwareVersion").put("sdkVersion"))))
            if (version != null && session.ip == tvIp) TypingSupport.noteVersion(prefs, version)
        }
    }

    /**
     * Holds a registerRemoteKeyboard subscription on the shared command session and reports
     * whether the TV has a text field focused. Returns a stop function that unsubscribes.
     */
    fun watchKeyboardFocus(listener: (KeyboardFocus) -> Unit): () -> Unit {
        val thread = Thread {
            var idle = false
            try {
                while (true) {
                    // Rides the session the power watcher keeps up rather than dialling one: its retry
                    // and pairing backoff must stay the only thing that reconnects, or a refused
                    // pairing would put the TV's prompt back up every couple of seconds
                    val session = sharedCommandSession?.takeIf { it.state == CmdState.READY && it.ip == tvIp }
                    val id = session?.exchange("subscribe", KEYBOARD_URI, JSONObject().put("subscribe", true)) { frame, _ ->
                        noteKeyboardFrame(frame)
                        listener(keyboardFocusOf(frame))
                    }
                    if (session == null || id == null) {
                        if (!idle) { idle = true; listener(KeyboardFocus.UNKNOWN) }
                        Thread.sleep(1_000)
                        continue
                    }
                    idle = false
                    try { session.awaitDeath() } finally { session.endExchange(id, true) }
                    kbdFocus = KeyboardFocus.UNKNOWN; kbdTextLength = -1
                    listener(KeyboardFocus.UNKNOWN)
                }
            } catch (_: InterruptedException) { }
        }
        thread.start()
        return { thread.interrupt() }
    }

    /** One request's answer as the TV sent it, or why none arrived. */
    class RawCall(val frame: String?, val failure: String?)

    private fun readySession(): Pair<CommandSession?, String?> {
        if (buildWsRequest() == null) return null to "No TV IP configured"
        val session = commandSession()
        session.awaitSettled()
        return when (session.state) {
            CmdState.READY -> session to null
            CmdState.NEEDS_PAIRING -> null to "TV is waiting for pairing"
            else -> null to "Can't connect to TV"
        }
    }

    /** The notification route used for settings writes, aimed at insertText; both raw replies for the keyboard test. */
    fun rawNotificationInsert(text: String): Pair<RawCall, RawCall?> {
        val lunaUri = "luna://com.webos.service.ime/insertText"
        val params = JSONObject().put("text", text).put("replace", false)
        val alert = rawRequest("ssap://system.notifications/createAlert", JSONObject().apply {
            put("message", " ")
            put("buttons", JSONArray().put(JSONObject().put("label", "").put("onClick", lunaUri).put("params", params)))
            put("onclose", JSONObject().put("uri", lunaUri).put("params", params))
            put("onfail", JSONObject().put("uri", lunaUri).put("params", params))
        })
        val alertId = alert.frame?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("payload")?.optString("alertId")
        if (alertId.isNullOrEmpty()) return alert to null
        return alert to rawRequest("ssap://system.notifications/closeAlert", JSONObject().put("alertId", alertId))
    }

    /** Sends one request and returns the raw reply frame. Used by the keyboard test. */
    fun rawRequest(uri: String, payload: JSONObject = JSONObject(), timeoutSecs: Long = 6): RawCall {
        val (session, failure) = readySession()
        if (session == null) return RawCall(null, failure)
        val got = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        val id = session.exchange("request", uri, payload) { _, text -> got.set(text); latch.countDown() }
            ?: return RawCall(null, "Not connected")
        val answered = latch.await(timeoutSecs, TimeUnit.SECONDS)
        session.endExchange(id, false)
        return if (answered) RawCall(got.get(), null) else RawCall(null, "No reply within ${timeoutSecs}s")
    }

    /** Subscribes and passes every raw frame to [listener]; returns a stop function, or null with the reason in [onFail]. */
    fun rawSubscribe(uri: String, payload: JSONObject, onFail: (String) -> Unit, listener: (String) -> Unit): (() -> Unit)? {
        val (session, failure) = readySession()
        if (session == null) { onFail(failure ?: "Can't connect to TV"); return null }
        val id = session.exchange("subscribe", uri, payload) { _, text -> listener(text) }
        if (id == null) { onFail("Not connected"); return null }
        return { session.endExchange(id, true) }
    }

    /**
     * Timed probes for the connection report. Drops both shared sockets first so the open times
     * are those of a fresh connection; the next use of the remote reopens them on demand.
     */
    internal fun probeConnection(progress: (String) -> Unit): TvProbe {
        if (buildWsRequest() == null) return TvProbe(failure = "No TV is set up")
        // Registering without a key would raise a pairing prompt on the TV
        if (prefs.getString("client_key", null).isNullOrEmpty()) return TvProbe(failure = "The TV is not paired yet")
        fun msSince(t0: Long) = (System.nanoTime() - t0) / 1_000_000.0

        progress("Opening the command connection")
        val oldPointer: PointerSession?
        val oldCommand: CommandSession?
        synchronized(stateLock) {
            // Closing a session mid-exchange or under a pairing prompt would break the user's own action
            if (sharedCommandSession?.inUse() == true || sharedPointerSession?.inUse() == true ||
                sharedCommandSession?.pairingPromptShowing == true) {
                return TvProbe(failure = "The connection is busy, try again in a moment")
            }
            oldPointer = sharedPointerSession; sharedPointerSession = null
            oldCommand = sharedCommandSession; sharedCommandSession = null
        }
        oldPointer?.close()
        oldCommand?.close(PROBE_CLOSE)

        val openedAt = System.nanoTime()
        val (session, why) = readySession()
        if (session == null) return TvProbe(failure = why ?: "Can't connect to TV")
        val commandOpenMs = msSince(openedAt)

        progress("Reading TV info")
        val system = rawRequest("ssap://system/getSystemInfo", timeoutSecs = 4)
        val software = rawRequest("ssap://com.webos.service.update/getCurrentSWInformation", timeoutSecs = 4)
        val model = ConnReport.tvModel(system.frame, system.failure)
        val softwareText = ConnReport.tvSoftware(software.frame, software.failure)

        progress("Timing requests")
        val trips = ArrayList<Double>()
        var failed = 0
        repeat(PROBE_COUNT) {
            val t0 = System.nanoTime()
            val call = rawRequest("ssap://system/getSystemInfo", timeoutSecs = 4)
            if (call.frame != null) trips.add(msSince(t0)) else failed++
        }

        progress("Opening the pointer socket")
        val pointerAt = System.nanoTime()
        val pointer = pointerSession()
        if (pointer == null || !pointer.waitUntilReady()) {
            return TvProbe(null, model, softwareText, commandOpenMs, trips, failed,
                pointerFailure = "Pointer socket did not open")
        }
        val pointerOpenMs = msSince(pointerAt)

        return TvProbe(null, model, softwareText, commandOpenMs, trips, failed, pointerOpenMs)
    }

    /** The TV's answer to the shared session's registration, once the session is up. */
    fun registerReply(): RawCall {
        val (session, failure) = readySession()
        return RawCall(session?.registeredFrame, failure)
    }

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

    /**
     * Watches for pairing completion in a background thread loop.
     * - Calls [onPromptShown] the first time the TV displays its pairing prompt.
     * - Calls [onPaired] once the user accepts and the client key is saved.
     * - Retries silently on connection failure.
     * Returns a stop function; call it from onDestroy to cancel.
     */
    fun watchForPairing(
        ip: String,
        onPromptShown: () -> Unit,
        onPaired: () -> Unit
    ): () -> Unit {
        val flags = intArrayOf(1, 0) // [0] = running (1=true), [1] = promptShown (1=true)
        // Held while a key is stored and while stopping, so a pairing the user already backed
        // out of can't land its key afterwards
        val stopLock = Any()
        val current = AtomicReference<WebSocket?>()

        Thread {
            fun running() = flags[0] == 1
            fun promptShown() = flags[1] == 1
            while (running()) {
                // Pairs against the candidate IP directly — it's only persisted once
                // pairing succeeds, so abandoning setup doesn't lock in a TV
                val req = Request.Builder().url("wss://$ip:$TV_PORT").build()
                val savedKey = prefs.getString("client_key", null)
                val http = buildClient()
                val latch = CountDownLatch(1)

                current.set(http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        if (!running()) { ws.close(1000, null); latch.countDown(); return }
                        ws.send(buildRegistration(savedKey))
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        if (!running()) { ws.close(1000, null); latch.countDown(); return }
                        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                        when {
                            json.optString("type") == "registered" -> {
                                val key = json.optJSONObject("payload")?.optString("client-key")
                                val stillWanted = synchronized(stopLock) {
                                    running().also { wanted ->
                                        if (wanted) {
                                            if (!key.isNullOrEmpty()) prefs.edit().putString("client_key", key).apply()
                                            flags[0] = 0
                                        }
                                    }
                                }
                                ws.close(1000, null)
                                latch.countDown()
                                if (stillWanted) onPaired()
                            }
                            json.optString("type") == "response" && json.optString("id") == "reg_0" -> {
                                if (!promptShown()) { flags[1] = 1; onPromptShown() }
                                // Keep connection alive — WebOS sends "registered" on the same
                                // socket once the user taps Accept on the TV
                            }
                        }
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { latch.countDown() }
                    override fun onClosed(ws: WebSocket, code: Int, reason: String) { latch.countDown() }
                }))

                latch.await(30, TimeUnit.SECONDS)
                http.dispatcher.executorService.shutdown()
                if (running()) Thread.sleep(2000)
            }
        }.start()

        return {
            synchronized(stopLock) { flags[0] = 0 }
            // Closing the socket also takes the pairing prompt off the TV
            current.get()?.cancel()
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
        prefs.edit().putString(TvStore.shortcutsKey(prefs), arr.toString()).apply()
    }

    fun loadShortcuts(): List<TvApp> {
        val json = prefs.getString(TvStore.shortcutsKey(prefs), null) ?: return defaultShortcuts()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                TvApp(obj.getString("id"), obj.getString("title"), obj.optString("iconUrl").takeIf { s -> s.isNotEmpty() })
            }
        }.getOrElse { defaultShortcuts() }
    }

    private val iconHttp by lazy { buildClient() }

    fun fetchIcon(url: String): android.graphics.Bitmap? = runCatching {
        val response = iconHttp.newCall(Request.Builder().url(url).build()).execute()
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
        private const val TAG = "WebOsClient"
        private val cmdSids = AtomicInteger()
        private val ptrSids = AtomicInteger()
        private const val CONN_TAG = "LgpConn"
        private const val PROBE_COUNT = 10
        private const val PROBE_CLOSE = "connection report"
        private const val KEYBOARD_URI = "ssap://com.webos.service.ime/registerRemoteKeyboard"
        private const val POWER_STATE_URI = "ssap://com.webos.service.tvpower/power/getPowerState"
        private const val WATCH_RETRY_MS = 5_000L
        private const val CONNECT_GRACE_MS = 10_000L
        private const val RESET_KEEP_MS = 5_000L
        // Several round trips even on a jittery Wi-Fi. A false alarm only costs the handshake
        // every resume used to cost; a socket that really died costs this much on top of it
        private const val RESUME_PROBE_MS = 1_000L
        private const val RESUME_GATE_SLACK_MS = 500L
        private const val RESUME_GATE_MAX_MS = RESUME_PROBE_MS + RESUME_GATE_SLACK_MS
        private const val RESUME_POINTER_IDLE_MS = 30_000L
        private const val PAIRING_BLOCK_MS = 30_000L
        private const val PAIRING_RETRY_MS = 30_000L
        private const val MAX_QUEUED_FRAMES = 64
        private const val POINTER_CONNECT_MS = 6_000L
        private const val POINTER_RETRY_MS = 1_000L
        // A registration socket normally opens in 70-220 ms on the C4
        private const val REGISTRATION_OPEN_MS = 1_000L
        private const val MIN_RETRY_LEFT_MS = 1_500L
        private const val STALE_FRAME_MS = 1_500L

        // Guards only the swap of the two shared sessions, never any network work
        private val stateLock = Any()
        @Volatile private var sharedCommandSession: WebOsClient.CommandSession? = null
        @Volatile private var sharedPointerSession: WebOsClient.PointerSession? = null
        private val resumeGate = ResumeGate()
        const val FACTORY_APP_ID = "com.webos.app.factorywin"

        // Builds the OkHttp client and opens the pointer socket off the caller's thread
        private val connectExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "pointer-connect").apply { isDaemon = true }
        }
        private val watchdog = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "pointer-watchdog").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        // Holds a pointer's registration until the command session has registered
        private val gateExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "pointer-gate").apply { isDaemon = true }
        }
        // One thread so taps held on the resume gate leave in the order they were made; on the
        // cached pool a BACK and a click queued together could swap
        private val tapExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "pointer-tap").apply { isDaemon = true }
        }
        // Observed on a C4: turnOff pushes state "Active" with processing "Request Power Off"
        // within 50 ms, then "Request/Prepare Active Standby", and lands on "Active Standby" ~3 s later
        private val OFF_STATES = setOf("Active Standby", "Suspend", "Power Off")
        private val OFF_TRANSITIONS = listOf("Power Off", "Standby", "Suspend")
        private val TV_OWN_OUTPUTS = setOf("tv_speaker", "tv_external_speaker", "headphone", "tv_speaker_headphone")

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
        TvApp("netflix", "Netflix")
    )

    /**
     * First shortcuts for a freshly paired TV: the well-known streaming apps it actually
     * has, in a fixed order of popularity, topped up from the TV's own launcher list.
     * webOS exposes neither usage counts nor the on-screen ribbon order, so this is the
     * closest thing to "the apps you use".
     */
    fun pickDefaultShortcuts(apps: List<TvApp>, count: Int = 4): List<TvApp> {
        val wanted = listOf("youtube", "netflix", "amazon", "prime", "disney", "hbo", "max", "viaplay",
            "spotify", "apple", "plex", "twitch", "tv 2", "dr tv", "skyshowtime", "paramount")
        val picked = mutableListOf<TvApp>()
        for (w in wanted) {
            if (picked.size >= count) break
            apps.firstOrNull { a -> a !in picked && (a.id.lowercase().contains(w) || a.title.lowercase() == w || a.title.lowercase().startsWith("$w ")) }
                ?.let { picked.add(it) }
        }
        val system = listOf("com.webos.", "com.palm.", "com.lge.", "com.lgsmartplatform")
        apps.filter { a -> a !in picked && system.none { a.id.startsWith(it) } }
            .takeLast(count - picked.size).forEach { picked.add(it) }
        return picked.take(count)
    }
}
