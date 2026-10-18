package com.muns.tvremocon

import android.app.Activity
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

    @Volatile private var currentId = ""
    @Volatile private var currentIp = ""
    @Volatile private var released = false

    // ------------------------------------------------------------ JS → Kotlin

    @JavascriptInterface
    fun appInfo(): String = JSONObject()
        .put("version", BuildConfig.VERSION_NAME)
        .put("build", BuildConfig.VERSION_CODE)
        .put("stage", 2)
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

    /** 저장된 TV id로 연결 */
    @JavascriptInterface
    fun connect(id: String) {
        val tv = store.get(id)
        if (tv == null) {
            emit("error", JSONObject().put("message", "저장된 TV가 없습니다: $id"))
            return
        }
        startConnect(id, tv)
    }

    /** IP 직접 입력으로 연결 */
    @JavascriptInterface
    fun connectIp(ip: String) {
        val clean = ip.trim()
        if (!Regex("^[0-9A-Za-z.:\\-]+$").matches(clean)) {
            emit("error", JSONObject().put("message", "IP 형식이 아닙니다: $ip"))
            return
        }
        val id = store.ensureManual(clean)
        startConnect(id, store.get(id) ?: JSONObject().put("ip", clean))
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

    @JavascriptInterface
    fun sendPin(pin: String) {
        client.sendPin(pin.trim())
    }

    /** 인증서가 바뀐 TV를 같은 TV로 인정 (저장된 지문 삭제 후 다음 연결에서 새로 저장) */
    @JavascriptInterface
    fun acceptCert(id: String) {
        store.setCert(id, null)
    }

    @JavascriptInterface
    fun forget(id: String) {
        if (id == currentId) client.disconnect()
        store.forget(id)
    }

    // ------------------------------------------------------------ WebOsClient.Listener

    override fun onState(state: String, message: String) {
        val tv = store.get(currentId)
        emit(
            "state", JSONObject().put("state", state).put("message", message)
                .put("id", currentId).put("ip", currentIp)
                .put("name", tv?.optString("name").orEmpty())
        )
    }

    override fun onRegistered(clientKey: String, certSha256: String?) {
        val id = currentId
        if (clientKey.isNotEmpty()) store.setKey(id, clientKey)
        val saved = store.get(id)?.optString("cert").orEmpty()
        if (saved.isEmpty() && !certSha256.isNullOrEmpty()) store.setCert(id, certSha256)
        store.setLast(id)
    }

    override fun onResponse(reqId: String, ok: Boolean, payload: JSONObject?, error: String?) {
        val d = JSONObject().put("reqId", reqId).put("ok", ok)
        if (payload != null) d.put("payload", payload)
        if (error != null) d.put("error", error)
        emit("response", d)
    }

    override fun onKeyRejected() {
        store.setKey(currentId, null)
    }

    override fun onCertMismatch(actualSha256: String?) {
        emit(
            "state", JSONObject().put("state", "failed").put("code", "cert_changed")
                .put("message", "TV 인증서가 바뀌었습니다")
                .put("id", currentId).put("ip", currentIp)
        )
    }

    // ------------------------------------------------------------ 내부

    private fun startConnect(id: String, tv: JSONObject) {
        currentId = id
        currentIp = tv.optString("ip")
        client.connect(
            currentIp,
            tv.optString("key").ifEmpty { null },
            tv.optString("cert").ifEmpty { null }
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
