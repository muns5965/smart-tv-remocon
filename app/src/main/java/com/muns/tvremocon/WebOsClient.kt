package com.muns.tvremocon

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * LG webOS TV 접속 (순수 JVM — 안드로이드 의존 없음, 단위 테스트 가능).
 * 프로토콜: WEBOS_API.md 1~4절
 *
 * - wss://IP:3001, TV 자체 서명 인증서 → 처음 본 인증서 지문을 저장해 두고 이후 비교(TOFU)
 * - register(PROMPT) → client-key 수신
 * - ssap 요청/응답은 호출자가 준 reqId로 매칭
 * - 방향키는 포인터 소켓(두 번째 WebSocket)에 텍스트로 전송
 */
class WebOsClient(
    private val listener: Listener,
    private val port: Int = 3001,
    private val scheme: String = "wss"
) {

    interface Listener {
        /** state: connecting | prompt | pin | connected | closed | failed */
        fun onState(state: String, message: String)
        fun onRegistered(clientKey: String, certSha256: String?)
        fun onResponse(reqId: String, ok: Boolean, payload: JSONObject?, error: String?)
        /** 저장된 client-key를 TV가 거부함 → 호출자는 저장된 키를 지운다 (클라이언트는 키 없이 재등록) */
        fun onKeyRejected()
        /** 저장된 지문과 TV 인증서가 다름 → 접속 중단 */
        fun onCertMismatch(actualSha256: String?)
    }

    private val lock = Any()
    private var gen = 0                      // 이전 소켓의 늦은 콜백 무시용
    private var ws: WebSocket? = null
    private var pointer: WebSocket? = null
    private var pointerOpen = false
    private var pointerRequested = false
    private val pendingButtons = ArrayList<String>()
    private var connected = false
    private var sentKey = false
    private var host = ""
    @Volatile private var seenCert: String? = null
    @Volatile private var certMismatch = false

    private var http: OkHttpClient? = null

    fun isConnected(): Boolean = synchronized(lock) { connected }

    fun connect(host: String, clientKey: String?, pinnedCertSha256: String?) {
        val myGen: Int
        synchronized(lock) {
            closeSockets()
            gen++
            myGen = gen
            this.host = host
            connected = false
            sentKey = !clientKey.isNullOrEmpty()
            seenCert = null
            certMismatch = false
            http = buildClient(pinnedCertSha256)
        }
        listener.onState("connecting", "$host:$port")
        val req = Request.Builder().url("$scheme://$host:$port/").build()
        http!!.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stale(myGen)) { webSocket.cancel(); return }
                synchronized(lock) { ws = webSocket }
                webSocket.send(registerMessage(clientKey))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stale(myGen)) return
                handleMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stale(myGen)) return
                markClosed()
                listener.onState("closed", reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stale(myGen)) return
                markClosed()
                if (certMismatch) listener.onCertMismatch(seenCert)
                else listener.onState("failed", t.message ?: t.javaClass.simpleName)
            }
        })
    }

    fun disconnect() {
        synchronized(lock) {
            gen++
            closeSockets()
            connected = false
        }
        listener.onState("closed", "")
    }

    /** ssap 요청. 응답은 listener.onResponse(reqId, ...) */
    fun request(reqId: String, uri: String, payload: JSONObject?) {
        val sock = synchronized(lock) { if (connected) ws else null }
        if (sock == null) {
            listener.onResponse(reqId, false, null, "not_connected")
            return
        }
        val m = JSONObject().put("id", reqId).put("type", "request").put("uri", uri)
        if (payload != null) m.put("payload", payload)
        if (!sock.send(m.toString())) listener.onResponse(reqId, false, null, "send_failed")
    }

    /** 방향키 등 (UP, DOWN, LEFT, RIGHT, ENTER, BACK, HOME ...) */
    fun button(name: String): Boolean {
        val text = "type:button\nname:$name\n\n"
        synchronized(lock) {
            if (!connected) return false
            val p = pointer
            if (p != null && pointerOpen) return p.send(text)
            if (pendingButtons.size < 5) pendingButtons.add(text)
            if (!pointerRequested) requestPointerLocked()
            return true
        }
    }

    fun sendPin(pin: String) {
        val sock = synchronized(lock) { ws } ?: return
        sock.send(
            JSONObject().put("id", PIN_ID).put("type", "request")
                .put("uri", "ssap://pairing/setPin")
                .put("payload", JSONObject().put("pin", pin)).toString()
        )
    }

    // ---------------------------------------------------------------- 내부

    private fun stale(myGen: Int) = synchronized(lock) { myGen != gen }

    private fun markClosed() {
        synchronized(lock) {
            connected = false
            pointer?.cancel()
            pointer = null
            pointerOpen = false
            pointerRequested = false
            ws = null
        }
    }

    private fun closeSockets() {
        ws?.close(1000, null)
        pointer?.close(1000, null)
        ws = null
        pointer = null
        pointerOpen = false
        pointerRequested = false
        pendingButtons.clear()
    }

    private fun handleMessage(sock: WebSocket, text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        val id = m.optString("id")
        val type = m.optString("type")
        val payload = m.optJSONObject("payload")

        when (id) {
            REGISTER_ID -> handleRegister(sock, type, payload, m.optString("error"))
            POINTER_ID -> {
                val path = payload?.optString("socketPath").orEmpty()
                if (type != "error" && path.isNotEmpty()) openPointer(path)
                else synchronized(lock) { pointerRequested = false }
            }
            PIN_ID -> {
                if (type == "error") listener.onState("pin", "PIN이 맞지 않습니다")
            }
            else -> {
                val ok = type != "error" && (payload?.optBoolean("returnValue", true) ?: true)
                val err = if (ok) null else (m.optString("error").ifEmpty { payload?.optString("errorText") ?: "error" })
                listener.onResponse(id, ok, payload, err)
            }
        }
    }

    private fun handleRegister(sock: WebSocket, type: String, payload: JSONObject?, error: String) {
        when (type) {
            "registered" -> {
                val key = payload?.optString("client-key").orEmpty()
                synchronized(lock) {
                    connected = true
                    requestPointerLocked()
                }
                listener.onRegistered(key, seenCert)
                listener.onState("connected", host)
            }
            "response" -> when (payload?.optString("pairingType")) {
                "PIN" -> listener.onState("pin", "TV 화면의 PIN을 입력하세요")
                else -> listener.onState("prompt", "TV 화면에서 '허용'을 눌러 주세요")
            }
            "error" -> {
                val retry = synchronized(lock) { if (sentKey) { sentKey = false; true } else false }
                if (retry) {
                    listener.onKeyRejected()
                    sock.send(registerMessage(null))      // 같은 소켓으로 키 없이 다시 등록
                } else {
                    listener.onState("failed", "TV가 거부했습니다: $error")
                }
            }
        }
    }

    /** lock 안에서 호출 */
    private fun requestPointerLocked() {
        val sock = ws ?: return
        pointerRequested = true
        sock.send(
            JSONObject().put("id", POINTER_ID).put("type", "request")
                .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket").toString()
        )
    }

    private fun openPointer(socketPath: String) {
        val client = http ?: return
        val myGen = synchronized(lock) { gen }
        client.newWebSocket(Request.Builder().url(socketPath).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val queued: List<String>
                synchronized(lock) {
                    if (myGen != gen) { webSocket.cancel(); return }
                    pointer = webSocket
                    pointerOpen = true
                    queued = ArrayList(pendingButtons)
                    pendingButtons.clear()
                }
                queued.forEach { webSocket.send(it) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropPointer(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropPointer(webSocket)
            }
        })
    }

    private fun dropPointer(sock: WebSocket) {
        synchronized(lock) {
            if (pointer === sock || pointer == null) {
                pointer = null
                pointerOpen = false
                pointerRequested = false          // 다음 버튼 때 다시 요청
            }
        }
    }

    private fun buildClient(pinned: String?): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                val fp = sha256(leaf.encoded)
                seenCert = fp
                if (!pinned.isNullOrEmpty() && !pinned.equals(fp, ignoreCase = true)) {
                    certMismatch = true
                    throw CertificateException("TV certificate changed")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }     // TV 인증서는 IP와 이름이 맞지 않음 → 지문으로 대신 확인
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val REGISTER_ID = "register_0"
        const val POINTER_ID = "ptr_0"
        const val PIN_ID = "pin_0"

        val PERMISSIONS = listOf(
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "UPDATE_FROM_REMOTE_APP", "CONTROL_POWER",
            "READ_INSTALLED_APPS", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TV", "READ_INPUT_DEVICE_LIST",
            "READ_NETWORK_STATE", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST",
            "READ_SETTINGS", "CONTROL_TV_SCREEN", "CONTROL_INPUT_TEXT",
            "CONTROL_MOUSE_AND_KEYBOARD", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS",
            "READ_POWER_STATE"
        )

        fun registerMessage(clientKey: String?): String {
            val manifest = JSONObject()
                .put("manifestVersion", 1)
                .put("appVersion", "1.0")
                .put("permissions", JSONArray(PERMISSIONS))
            val payload = JSONObject()
                .put("forcePairing", false)
                .put("pairingType", "PROMPT")
                .put("manifest", manifest)
            if (!clientKey.isNullOrEmpty()) payload.put("client-key", clientKey)
            return JSONObject().put("id", REGISTER_ID).put("type", "register")
                .put("payload", payload).toString()
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
    }
}
