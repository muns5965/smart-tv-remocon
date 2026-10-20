package com.muns.tvremocon

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * JS ↔ Kotlin 브리지. 메서드 이름과 이벤트 형식은 ARCHITECTURE.md 3절이 기준.
 * 모든 메서드는 즉시 반환하고 결과는 window.onBridge(type, dataJson) 이벤트로 보낸다.
 */
class AndroidBridge(
    private val activity: Activity,
    private val webView: WebView
) : WebOsClient.Listener {

    private val store = TvStore(activity)
    private val discovery = TvDiscovery(activity)
    private val client = WebOsClient(this)

    @Volatile private var currentId = ""      // forget() 판단용. 저장에는 쓰지 않음
    @Volatile private var released = false

    // ------------------------------------------------------------ JS → Kotlin

    @JavascriptInterface
    fun appInfo(): String = JSONObject()
        .put("version", BuildConfig.VERSION_NAME)
        .put("build", BuildConfig.VERSION_CODE)
        .put("stage", 3)
        .put("pairing", "PIN")
        .toString()

    /** 저장된 TV 목록: [{id, ip, name, model, known}] */
    @JavascriptInterface
    fun savedTvs(): String = store.listForUi().toString()

    /** 마지막으로 연결한 TV id ("" = 없음) */
    @JavascriptInterface
    fun lastTv(): String = store.last()

    @JavascriptInterface
    fun discover() {
        discovery.start(
            onFound = { tv ->
                val known = store.upsertFound(tv)
                emit(
                    "tvFound", JSONObject().put("id", tv.id).put("ip", tv.ip)
                        .put("name", tv.name).put("model", tv.model).put("uuid", tv.uuid)
                        .put("known", known)
                )
            },
            onDone = { count, error ->
                val d = JSONObject().put("count", count)
                if (error != null) d.put("error", error)
                emit("discoverDone", d)
            }
        )
    }

    /** 저장된 TV id로 연결. 새 등록이면 PIN 방식 (리모컨 불필요, 실TV 확인) */
    @JavascriptInterface
    fun connect(id: String) {
        startConnect(id, WebOsClient.PAIRING_PIN)
    }

    /** PIN이 안 뜨는 TV용: 'TV에서 허용' 방식으로 다시 연결 */
    @JavascriptInterface
    fun connectPrompt(id: String) {
        startConnect(id, WebOsClient.PAIRING_PROMPT)
    }

    /** IP 직접 입력으로 연결 (IPv4·IPv6 표기만) */
    @JavascriptInterface
    fun connectIp(ip: String) {
        val clean = ip.trim()
        if (!SsdpSearch.isIpLiteral(clean)) {
            emit("error", JSONObject().put("message", "IP 주소 형식이 아닙니다: $ip"))
            return
        }
        startConnect(store.ensureManual(clean), WebOsClient.PAIRING_PIN)
    }

    @JavascriptInterface
    fun disconnect() {
        client.disconnect()
    }

    @JavascriptInterface
    fun request(reqId: String, uri: String, payloadJson: String?) {
        val payload = if (payloadJson.isNullOrBlank()) null else try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            null
        }
        client.request(reqId, uri, payload)
    }

    @JavascriptInterface
    fun button(name: String): Boolean = client.button(name)

    // 터치패드 (ARCHITECTURE.md 3절). 클릭 타이밍(move → 30ms → click)은 화면 쪽에서 맞춘다
    @JavascriptInterface
    fun pointerMove(dx: Int, dy: Int): Boolean = client.pointerMove(dx, dy)

    @JavascriptInterface
    fun pointerClick(): Boolean = client.pointerClick()

    /** 탭 = 이동 → 30ms → 클릭을 같은 세션에서 (TV가 바뀌면 클릭 안 함) */
    @JavascriptInterface
    fun pointerTap(): Boolean = client.pointerTap()

    @JavascriptInterface
    fun pointerScroll(dy: Int): Boolean = client.pointerScroll(dy)

    @JavascriptInterface
    fun sendPin(pin: String) {
        client.sendPin(pin.trim())
    }

    /**
     * 인증서가 바뀐 TV를 다시 등록: 지문과 함께 client-key도 지운다.
     * → 바뀐 상대에게 기존 키를 보내지 않고, TV 화면의 PIN으로 새로 등록 (PIN은 진짜 TV 화면에만 뜸)
     */
    @JavascriptInterface
    fun acceptCert(id: String) {
        store.setCert(id, null)
        store.setKey(id, null)
    }

    /**
     * 화면 미러링 / DeX: 일반 앱은 Miracast·DeX를 직접 시작할 수 없으므로 시스템 화면 전송 화면을 연다.
     * 순서: 안드로이드 화면 전송 설정 → 삼성 Smart View 앱 → 디스플레이 설정.
     * 결과는 이벤트 cast {via}: cast_settings | smart_view | display_settings | none (실기 확인용으로 화면에 표시)
     */
    @JavascriptInterface
    fun openCastSettings() {
        activity.runOnUiThread {
            val via = tryStart(Intent(Settings.ACTION_CAST_SETTINGS), "cast_settings")
                ?: activity.packageManager.getLaunchIntentForPackage(SMART_VIEW_PKG)
                    ?.let { tryStart(it, "smart_view") }
                ?: tryStart(Intent(Settings.ACTION_DISPLAY_SETTINGS), "display_settings")
                ?: "none"
            emit("cast", JSONObject().put("via", via))
        }
    }

    private fun tryStart(intent: Intent, name: String): String? = try {
        activity.startActivity(intent)
        name
    } catch (e: ActivityNotFoundException) {
        null
    } catch (e: SecurityException) {
        null
    }

    @JavascriptInterface
    fun forget(id: String) {
        if (id == currentId) client.disconnect()
        store.forget(id)
    }

    companion object {
        const val SMART_VIEW_PKG = "com.samsung.android.smartmirroring"
    }

    // ------------------------------------------------------------ WebOsClient.Listener
    // 저장은 항상 콜백의 tag(=연결을 시작한 TV id) 기준. currentId를 쓰지 않는다 (연결이 겹칠 때 섞임 방지)

    override fun onState(tag: String, state: String, message: String) {
        val tv = store.get(tag)
        emit(
            "state", JSONObject().put("state", state).put("message", message)
                .put("id", tag).put("ip", tv?.optString("ip").orEmpty())
                .put("name", tv?.optString("name").orEmpty())
        )
    }

    override fun onRegistered(tag: String, clientKey: String, certSha256: String?) {
        if (clientKey.isNotEmpty()) store.setKey(tag, clientKey)
        val saved = store.get(tag)?.optString("cert").orEmpty()
        if (saved.isEmpty() && !certSha256.isNullOrEmpty()) store.setCert(tag, certSha256)
        store.setLast(tag)
    }

    override fun onResponse(tag: String, reqId: String, ok: Boolean, payload: JSONObject?, error: String?) {
        val d = JSONObject().put("reqId", reqId).put("ok", ok).put("id", tag)   // 어느 TV의 응답인지
        if (payload != null) d.put("payload", payload)
        if (error != null) d.put("error", error)
        emit("response", d)
    }

    override fun onKeyRejected(tag: String) {
        store.setKey(tag, null)
    }

    override fun onCertMismatch(tag: String, actualSha256: String?) {
        val tv = store.get(tag)
        emit(
            "state", JSONObject().put("state", "failed").put("code", "cert_changed")
                .put("message", "TV 인증서가 바뀌었습니다")
                .put("id", tag).put("ip", tv?.optString("ip").orEmpty())
                .put("name", tv?.optString("name").orEmpty())
                .put("model", tv?.optString("model").orEmpty())
        )
    }

    override fun onPointerError(tag: String, message: String) {
        emit("error", JSONObject().put("message", message))
    }

    // ------------------------------------------------------------ 내부

    private fun startConnect(id: String, pairingType: String) {
        val tv = store.get(id)
        if (tv == null) {
            emit("error", JSONObject().put("message", "저장된 TV가 없습니다: $id"))
            return
        }
        currentId = id
        client.connect(
            id,
            tv.optString("ip"),
            tv.optString("key").ifEmpty { null },
            tv.optString("cert").ifEmpty { null },
            pairingType
        )
    }

    /** Kotlin → JS 이벤트: window.onBridge(type, dataJson) */
    fun emit(type: String, data: JSONObject) {
        if (released) return
        val js = "window.onBridge && window.onBridge(${JSONObject.quote(type)}, ${JSONObject.quote(data.toString())})"
        activity.runOnUiThread { if (!released) webView.evaluateJavascript(js, null) }
    }

    fun release() {
        released = true
        client.disconnect()
        discovery.shutdown()
    }
}
