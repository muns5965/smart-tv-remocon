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
 *   지문이 다르면 TLS 단계에서 끊으므로 저장된 client-key는 보내지 않는다.
 * - 새 등록은 기본 PIN 방식(리모컨 없이, 실TV 확인), 필요하면 PROMPT('허용') 방식
 * - 모든 콜백은 connect()에 준 tag(TV id)를 그대로 돌려준다 → 연결이 겹쳐도 다른 TV에 저장되지 않음
 * - 방향키는 포인터 소켓(두 번째 WebSocket)에 텍스트로 전송
 */
class WebOsClient(
    private val listener: Listener,
    private val port: Int = 3001,
    private val scheme: String = "wss"
) {

    interface Listener {
        /** state: connecting | prompt | pin | connected | closed | failed */
        fun onState(tag: String, state: String, message: String)
        fun onRegistered(tag: String, clientKey: String, certSha256: String?)
        fun onResponse(tag: String, reqId: String, ok: Boolean, payload: JSONObject?, error: String?)
        /** 저장된 client-key를 TV가 거부함 → 호출자는 저장된 키를 지운다 (클라이언트는 키 없이 재등록) */
        fun onKeyRejected(tag: String)
        /** 저장된 지문과 TV 인증서가 다름 → 접속 중단 (키는 보내지 않음) */
        fun onCertMismatch(tag: String, actualSha256: String?)
        /** 방향키 소켓 실패 → 대기 중이던 버튼은 버려짐. 다음 버튼 때 다시 연결 */
        fun onPointerError(tag: String, message: String)
    }

    /** 연결 한 번 = 세션 하나. 늦게 도착한 이전 세션의 콜백은 gen으로 걸러낸다 */
    private class Session(val tag: String, val gen: Int, val host: String, val pairingType: String) {
        var ws: WebSocket? = null
        var pointer: WebSocket? = null
        var pointerOpen = false
        var pointerRequested = false
        val pendingButtons = ArrayList<String>()
        var connected = false
        var sentKey = false
        @Volatile var seenCert: String? = null
        @Volatile var certMismatch = false
        var http: OkHttpClient? = null
    }

    private val lock = Any()
    private var gen = 0
    private var cur: Session? = null

    fun isConnected(): Boolean = synchronized(lock) { cur?.connected == true }

    /**
     * @param tag 호출자가 정한 식별자(TV id). 모든 콜백에 그대로 돌아온다.
     * @param pairingType 새 등록 방식 "PIN"(기본) 또는 "PROMPT". 저장된 키가 있으면 키로 먼저 시도.
     */
    fun connect(
        tag: String,
        host: String,
        clientKey: String?,
        pinnedCertSha256: String?,
        pairingType: String = PAIRING_PIN
    ) {
        val s: Session
        synchronized(lock) {
            closeLocked()
            gen++
            s = Session(tag, gen, host, pairingType)
            s.sentKey = !clientKey.isNullOrEmpty()
            s.http = buildClient(s, pinnedCertSha256)
            cur = s
        }
        listener.onState(tag, "connecting", "$host:$port")
        val req = Request.Builder().url("$scheme://$host:$port/").build()
        s.http!!.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!live(s)) { webSocket.cancel(); return }
                synchronized(lock) { s.ws = webSocket }
                webSocket.send(registerMessage(clientKey, s.pairingType))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!live(s)) return
                handleMessage(s, webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!live(s)) return
                markClosed(s)
                listener.onState(s.tag, "closed", reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!live(s)) return
                markClosed(s)
                if (s.certMismatch) listener.onCertMismatch(s.tag, s.seenCert)
                else listener.onState(s.tag, "failed", t.message ?: t.javaClass.simpleName)
            }
        })
    }

    fun disconnect() {
        val tag: String
        synchronized(lock) {
            tag = cur?.tag ?: ""
            closeLocked()
            gen++
            cur = null
        }
        listener.onState(tag, "closed", "")
    }

    /** ssap 요청. 응답은 listener.onResponse(tag, reqId, ...) */
    fun request(reqId: String, uri: String, payload: JSONObject?) {
        val s = synchronized(lock) { cur }
        val sock = synchronized(lock) { if (s != null && s.connected) s.ws else null }
        if (s == null || sock == null) {
            listener.onResponse(s?.tag ?: "", reqId, false, null, "not_connected")
            return
        }
        val m = JSONObject().put("id", reqId).put("type", "request").put("uri", uri)
        if (payload != null) m.put("payload", payload)
        if (!sock.send(m.toString())) listener.onResponse(s.tag, reqId, false, null, "send_failed")
    }

    /** 방향키 등 (UP, DOWN, LEFT, RIGHT, ENTER, BACK, HOME ...) */
    fun button(name: String): Boolean {
        val text = "type:button\nname:$name\n\n"
        synchronized(lock) {
            val s = cur ?: return false
            if (!s.connected) return false
            val p = s.pointer
            if (p != null && s.pointerOpen) return p.send(text)
            if (s.pendingButtons.size < 5) s.pendingButtons.add(text)
            if (!s.pointerRequested) requestPointerLocked(s)
            return true
        }
    }

    fun sendPin(pin: String) {
        val sock = synchronized(lock) { cur?.ws } ?: return
        sock.send(
            JSONObject().put("id", PIN_ID).put("type", "request")
                .put("uri", "ssap://pairing/setPin")
                .put("payload", JSONObject().put("pin", pin)).toString()
        )
    }

    // ---------------------------------------------------------------- 내부

    private fun live(s: Session) = synchronized(lock) { cur === s && s.gen == gen }

    private fun markClosed(s: Session) {
        synchronized(lock) {
            s.connected = false
            s.pointer?.cancel()
            s.pointer = null
            s.pointerOpen = false
            s.pointerRequested = false
            s.pendingButtons.clear()
            s.ws = null
        }
    }

    /** lock 안에서 호출 */
    private fun closeLocked() {
        val s = cur ?: return
        s.ws?.close(1000, null)
        s.pointer?.close(1000, null)
        s.ws = null
        s.pointer = null
        s.pointerOpen = false
        s.pointerRequested = false
        s.pendingButtons.clear()
        s.connected = false
    }

    private fun handleMessage(s: Session, sock: WebSocket, text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        val id = m.optString("id")
        val type = m.optString("type")
        val payload = m.optJSONObject("payload")

        when (id) {
            REGISTER_ID -> handleRegister(s, sock, type, payload, m.optString("error"))
            POINTER_ID -> {
                val path = payload?.optString("socketPath").orEmpty()
                if (type != "error" && path.isNotEmpty()) {
                    openPointer(s, path)
                } else {
                    val hadPending = synchronized(lock) {
                        s.pointerRequested = false
                        val had = s.pendingButtons.isNotEmpty()
                        s.pendingButtons.clear()
                        had
                    }
                    if (hadPending) listener.onPointerError(s.tag, "방향키 연결을 받지 못했습니다")
                }
            }
            PIN_ID -> {
                if (type == "error" || payload?.optBoolean("returnValue", true) == false) {
                    listener.onState(s.tag, "pin", "PIN이 맞지 않습니다")
                }
            }
            else -> {
                val ok = type != "error" && (payload?.optBoolean("returnValue", true) ?: true)
                val err = if (ok) null else (m.optString("error").ifEmpty { payload?.optString("errorText") ?: "error" })
                listener.onResponse(s.tag, id, ok, payload, err)
            }
        }
    }

    private fun handleRegister(s: Session, sock: WebSocket, type: String, payload: JSONObject?, error: String) {
        when (type) {
            "registered" -> {
                val key = payload?.optString("client-key").orEmpty()
                synchronized(lock) {
                    s.connected = true
                    requestPointerLocked(s)
                }
                listener.onRegistered(s.tag, key, s.seenCert)
                listener.onState(s.tag, "connected", s.host)
            }
            "response" -> when (payload?.optString("pairingType")) {
                PAIRING_PIN -> listener.onState(s.tag, "pin", "TV 화면에 표시된 숫자를 입력하세요")
                else -> listener.onState(s.tag, "prompt", "TV 화면에서 '허용'을 눌러 주세요")
            }
            "error" -> {
                val retry = synchronized(lock) { if (s.sentKey) { s.sentKey = false; true } else false }
                if (retry) {
                    listener.onKeyRejected(s.tag)
                    sock.send(registerMessage(null, s.pairingType))   // 같은 소켓으로 키 없이 다시 등록
                } else {
                    listener.onState(s.tag, "failed", "TV가 거부했습니다: $error")
                }
            }
        }
    }

    /** lock 안에서 호출 */
    private fun requestPointerLocked(s: Session) {
        val sock = s.ws ?: return
        s.pointerRequested = true
        sock.send(
            JSONObject().put("id", POINTER_ID).put("type", "request")
                .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket").toString()
        )
    }

    private fun openPointer(s: Session, socketPath: String) {
        val client = s.http ?: return
        client.newWebSocket(Request.Builder().url(socketPath).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val queued: List<String>
                synchronized(lock) {
                    if (cur !== s) { webSocket.cancel(); return }
                    s.pointer = webSocket
                    s.pointerOpen = true
                    queued = ArrayList(s.pendingButtons)
                    s.pendingButtons.clear()
                }
                queued.forEach { webSocket.send(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropPointer(s, webSocket, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropPointer(s, webSocket, t.message ?: t.javaClass.simpleName)
            }
        })
    }

    private fun dropPointer(s: Session, sock: WebSocket, error: String?) {
        val hadPending: Boolean
        synchronized(lock) {
            if (s.pointer !== sock && s.pointer != null) return
            s.pointer = null
            s.pointerOpen = false
            s.pointerRequested = false              // 다음 버튼 때 다시 요청
            hadPending = s.pendingButtons.isNotEmpty()
            s.pendingButtons.clear()
        }
        if (error != null && hadPending && live(s)) listener.onPointerError(s.tag, "방향키 연결 실패: $error")
    }

    private fun buildClient(s: Session, pinned: String?): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                val fp = sha256(leaf.encoded)
                s.seenCert = fp
                if (!pinned.isNullOrEmpty() && !pinned.equals(fp, ignoreCase = true)) {
                    s.certMismatch = true
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
        const val PAIRING_PIN = "PIN"
        const val PAIRING_PROMPT = "PROMPT"

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

        fun registerMessage(clientKey: String?, pairingType: String = PAIRING_PIN): String {
            val manifest = JSONObject()
                .put("manifestVersion", 1)
                .put("appVersion", "1.0")
                .put("permissions", JSONArray(PERMISSIONS))
            val payload = JSONObject()
                .put("forcePairing", false)
                .put("pairingType", pairingType)
                .put("manifest", manifest)
            if (!clientKey.isNullOrEmpty()) payload.put("client-key", clientKey)
            return JSONObject().put("id", REGISTER_ID).put("type", "register")
                .put("payload", payload).toString()
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
    }
}
