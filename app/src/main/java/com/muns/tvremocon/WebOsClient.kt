package com.muns.tvremocon

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ScheduledThreadPoolExecutor
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
 * - 방향키·커서는 포인터 소켓(두 번째 WebSocket)에 텍스트로 전송.
 *   포인터 상태: IDLE → REQUESTING(주소 요청) → CONNECTING(소켓 여는 중) → OPEN.
 *   포인터 연결마다 번호(ptrGen)를 붙여, 이전 연결의 늦은 콜백·중복 주소 응답이 현재 상태를 바꾸지 못하게 한다.
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

    private enum class Ptr { IDLE, REQUESTING, CONNECTING, OPEN }

    /** 연결 한 번 = 세션 하나. 늦게 도착한 이전 세션의 콜백은 gen으로 걸러낸다 */
    private class Session(val tag: String, val gen: Int, val host: String, val pairingType: String) {
        var ws: WebSocket? = null
        var connected = false
        var sentKey = false
        @Volatile var seenCert: String? = null
        @Volatile var certMismatch = false
        var http: OkHttpClient? = null
        // 포인터
        var ptr = Ptr.IDLE
        var ptrGen = 0                      // 포인터 연결 번호 (늦은 콜백 무시용)
        var ptrReqSeq = 0                   // 포인터 주소 요청 번호 → 요청 ID "ptr_<n>"
        var ptrReqId: String? = null        // 지금 기다리는 주소 요청 ID (이것과 같은 응답만 처리)
        var pointer: WebSocket? = null
        var pointerBlocked = false          // TV가 준 포인터 주소가 이상하면 이 세션에서는 다시 요청하지 않음
        val pendingButtons = ArrayList<String>()   // 순서가 중요한 버튼만 FIFO로 대기
    }

    private val lock = Any()
    private var gen = 0
    private var cur: Session? = null

    fun isConnected(): Boolean = synchronized(lock) { cur?.connected == true }

    /** 포인터 소켓이 열려 있는지 (터치패드 이동은 열려 있을 때만 전송됨) */
    fun isPointerReady(): Boolean = synchronized(lock) { cur?.ptr == Ptr.OPEN }

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
        val m = JSONObject().put("id", reqId).put("type", "request").put("uri", uri)
        if (payload != null) m.put("payload", payload)
        // 세션 확인과 전송을 한 번의 잠금 안에서 → 그 사이 TV가 바뀌어 다른 세션으로 가는 일이 없음
        var tag = ""
        val sent = synchronized(lock) {
            val s = cur
            tag = s?.tag ?: ""
            val sock = if (s != null && s.connected) s.ws else null
            when {
                sock == null -> "not_connected"
                !sock.send(m.toString()) -> "send_failed"
                else -> null
            }
        }
        if (sent != null) listener.onResponse(tag, reqId, false, null, sent)
    }

    /** 방향키 등 (UP, DOWN, LEFT, RIGHT, ENTER, BACK, HOME ...). 포인터가 열리기 전이면 FIFO 대기 (최대 5) */
    fun button(name: String): Boolean = sendPointer("type:button\nname:$name\n\n", queue = true)

    /**
     * 커서 이동 (실TV: 화면 가로 ≈ 600~650 단위, WEBOS_API.md 4절).
     * 연속 동작이라 대기하지 않는다 — 포인터가 열리기 전이면 false (소켓 요청은 시작).
     */
    fun pointerMove(dx: Int, dy: Int): Boolean =
        sendPointer("type:move\ndx:$dx\ndy:$dy\ndown:0\n\n", queue = false)

    /** 단독 클릭은 TV가 무시하므로 보통은 [pointerTap]을 쓴다 */
    fun pointerClick(): Boolean = sendPointer("type:click\n\n", queue = false)

    /** 스크롤 (실TV: 10부터 반응, 양수 = 위). 대기하지 않음 */
    fun pointerScroll(dy: Int): Boolean = sendPointer("type:scroll\ndx:0\ndy:$dy\n\n", queue = false)

    /**
     * 탭 = 클릭 (실TV 확인: 이동 직후에만 클릭이 먹힘).
     * 같은 세션·같은 포인터 연결에서 이동 → [TAP_DELAY_MS] 뒤 클릭. 그 사이 TV가 바뀌거나 포인터가 다시 열리면 클릭하지 않는다.
     */
    fun pointerTap(): Boolean {
        val s: Session
        val myPtr: Int
        synchronized(lock) {
            s = cur ?: return false
            if (!s.connected || s.pointerBlocked) return false
            if (s.ptr != Ptr.OPEN) { if (s.ptr == Ptr.IDLE) requestPointerLocked(s); return false }
            if (s.pointer?.send("type:move\ndx:1\ndy:0\ndown:0\n\n") != true) return false
            myPtr = s.ptrGen
        }
        val click = Runnable {                     // Runnable 명시: Callable 오버로드와 겹침 방지
            synchronized(lock) {
                if (cur === s && s.ptr == Ptr.OPEN && s.ptrGen == myPtr) s.pointer?.send("type:click\n\n")
            }
        }
        SCHED.schedule(click, TAP_DELAY_MS, TimeUnit.MILLISECONDS)
        return true
    }

    /**
     * 포인터로 전송. 열려 있으면 바로 보낸다.
     * 아니면 queue=true(버튼)만 대기열에 넣고(가득 차면 false), 소켓 요청을 시작한다.
     */
    private fun sendPointer(text: String, queue: Boolean): Boolean {
        synchronized(lock) {
            val s = cur ?: return false
            if (!s.connected || s.pointerBlocked) return false
            if (s.ptr == Ptr.OPEN) return s.pointer?.send(text) == true
            if (s.ptr == Ptr.IDLE) requestPointerLocked(s)
            if (!queue) return false
            if (s.pendingButtons.size >= MAX_PENDING) return false
            s.pendingButtons.add(text)
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

    /** lock 안에서 호출: 포인터를 IDLE로 되돌리고 이전 포인터 연결의 콜백은 무효화 */
    private fun resetPointerLocked(s: Session) {
        s.ptrGen++
        s.ptrReqId = null
        s.pointer?.cancel()
        s.pointer = null
        s.ptr = Ptr.IDLE
        s.pendingButtons.clear()
    }

    private fun markClosed(s: Session) {
        synchronized(lock) {
            s.connected = false
            resetPointerLocked(s)
            s.ws = null
        }
    }

    /** lock 안에서 호출 */
    private fun closeLocked() {
        val s = cur ?: return
        s.ws?.close(1000, null)
        s.pointer?.close(1000, null)
        s.pointer = null
        resetPointerLocked(s)
        s.ws = null
        s.connected = false
    }

    private fun handleMessage(s: Session, sock: WebSocket, text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        val id = m.optString("id")
        val type = m.optString("type")
        val payload = m.optJSONObject("payload")

        when {
            id == REGISTER_ID -> handleRegister(s, sock, type, payload, m.optString("error"))
            id.startsWith(POINTER_PREFIX) -> handlePointerAddress(s, id, type, payload?.optString("socketPath").orEmpty())
            id == PIN_ID -> {
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

    /**
     * 포인터 주소 응답. REQUESTING이고 **지금 기다리는 요청 ID와 같을 때만** 처리
     * → 중복 응답·이전 요청의 늦은 응답은 무시 (소켓 1개만)
     */
    private fun handlePointerAddress(s: Session, respId: String, type: String, path: String) {
        var error: String? = null
        var openGen: Int? = null
        synchronized(lock) {
            if (s.ptr != Ptr.REQUESTING || respId != s.ptrReqId) return
            s.ptrReqId = null
            when {
                type != "error" && path.isNotEmpty() && isSamePointerTarget(path, s.host) -> {
                    s.ptrGen++
                    s.ptr = Ptr.CONNECTING
                    openGen = s.ptrGen
                }
                type != "error" && path.isNotEmpty() -> {
                    // 세션과 다른 스킴·호스트·포트 → 접속하지 않고 이 세션에서는 포인터 차단 (오류는 한 번만)
                    s.pointerBlocked = true
                    s.ptr = Ptr.IDLE
                    s.pendingButtons.clear()
                    error = "TV가 알려 준 방향키 주소가 올바르지 않아 연결하지 않았습니다"
                }
                else -> {
                    s.ptr = Ptr.IDLE
                    if (s.pendingButtons.isNotEmpty()) error = "방향키 연결을 받지 못했습니다"
                    s.pendingButtons.clear()
                }
            }
        }
        val g = openGen
        if (g != null) openPointer(s, path, g)
        val e = error
        if (e != null) listener.onPointerError(s.tag, e)
    }

    private fun handleRegister(s: Session, sock: WebSocket, type: String, payload: JSONObject?, error: String) {
        when (type) {
            "registered" -> {
                val key = payload?.optString("client-key").orEmpty()
                synchronized(lock) {
                    s.connected = true
                    if (s.ptr == Ptr.IDLE) requestPointerLocked(s)
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

    /** lock 안에서 호출. IDLE일 때만 주소를 요청한다 */
    private fun requestPointerLocked(s: Session) {
        if (s.pointerBlocked || s.ptr != Ptr.IDLE) return
        val sock = s.ws ?: return
        s.ptr = Ptr.REQUESTING
        s.ptrReqSeq++
        val reqId = POINTER_PREFIX + s.ptrReqSeq
        s.ptrReqId = reqId
        sock.send(
            JSONObject().put("id", reqId).put("type", "request")
                .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket").toString()
        )
    }

    private fun openPointer(s: Session, socketPath: String, myPtr: Int) {
        val client = s.http ?: return
        client.newWebSocket(Request.Builder().url(socketPath).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val queued: List<String>
                synchronized(lock) {
                    if (cur !== s || s.ptrGen != myPtr || s.ptr != Ptr.CONNECTING) { webSocket.cancel(); return }
                    s.pointer = webSocket
                    // 대기한 버튼을 OPEN 전환과 같은 잠금 안에서 먼저 전송 큐에 넣는다
                    // → 다른 스레드의 새 버튼이 대기 버튼을 앞지르지 못함 (OkHttp send는 비차단 큐잉)
                    queued = ArrayList(s.pendingButtons)
                    s.pendingButtons.clear()
                    s.ptr = Ptr.OPEN
                }
                Thread.sleep(5)   // [실험] 수정 전 코드: 잠금 밖에서 전송 (경쟁 창을 조금 넓힘)
                queued.forEach { webSocket.send(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropPointer(s, myPtr, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropPointer(s, myPtr, t.message ?: t.javaClass.simpleName)
            }
        })
    }

    /** 이 포인터 연결(myPtr)이 아직 현재 것일 때만 상태를 되돌린다 → 이전 연결의 늦은 종료는 무시 */
    private fun dropPointer(s: Session, myPtr: Int, error: String?) {
        val hadPending: Boolean
        synchronized(lock) {
            if (cur !== s || s.ptrGen != myPtr) return      // 현재 세션의 현재 포인터 연결일 때만
            hadPending = s.pendingButtons.isNotEmpty()
            s.pointer = null
            s.ptr = Ptr.IDLE                         // 다음 버튼 때 다시 요청
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
        // 공유 기본 클라이언트에서 파생 → 디스패처·스레드·연결 풀을 공유 (연결마다 새로 만들지 않음)
        return BASE_HTTP.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }     // TV 인증서는 IP와 이름이 맞지 않음 → 지문으로 대신 확인
            .build()
    }

    /** 포인터 주소가 지금 세션과 같은 스킴·호스트·포트인지 (URL 파서로 분리 비교) */
    private fun isSamePointerTarget(socketPath: String, sessionHost: String): Boolean =
        isSameTarget(socketPath, scheme, sessionHost, port)

    companion object {
        /** 모든 세션이 공유하는 기본 클라이언트 (세션별로 인증서 검사만 바꿔 파생) */
        private val BASE_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        /** 탭 클릭 지연용 공유 스케줄러 (데몬 1개) */
        private val SCHED = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "webos-tap").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        const val TAP_DELAY_MS = 30L
        const val MAX_PENDING = 5

        /**
         * 실TV 주소 형식: wss://<TV_IP>:3001/resources/<고유값>/netinput.pointer.sock (WEBOS_API.md 4절)
         * 스킴·호스트·포트가 모두 같아야 true. 경로는 TV가 정하는 값이라 검사하지 않는다.
         */
        fun isSameTarget(url: String, scheme: String, host: String, port: Int): Boolean {
            val u = try { URI(url.trim()) } catch (e: Exception) { return false }
            if (!scheme.equals(u.scheme, ignoreCase = true)) return false
            val h = (u.host ?: return false).removePrefix("[").removeSuffix("]")
            if (!h.equals(host.removePrefix("[").removeSuffix("]"), ignoreCase = true)) return false
            val p = if (u.port > 0) u.port else if (u.scheme.equals("wss", true)) 443 else 80
            return p == port
        }

        const val REGISTER_ID = "register_0"
        const val POINTER_PREFIX = "ptr_"           // 포인터 주소 요청 ID: ptr_1, ptr_2, …
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
