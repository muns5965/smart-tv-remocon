package com.muns.tvremocon

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * JS ↔ Kotlin 브리지. 메서드 이름과 이벤트 형식은 ARCHITECTURE.md 3절이 기준.
 * 1단계: appInfo()만 구현. 나머지는 2단계(WebOsClient)에서 채운다.
 */
class AndroidBridge(
    private val activity: Activity,
    private val webView: WebView
) {

    @JavascriptInterface
    fun appInfo(): String = JSONObject()
        .put("version", BuildConfig.VERSION_NAME)
        .put("build", BuildConfig.VERSION_CODE)
        .put("stage", 1)
        .toString()

    /** Kotlin → JS 이벤트: window.onBridge(type, dataJson) */
    fun emit(type: String, data: JSONObject) {
        val js = "window.onBridge && window.onBridge(${JSONObject.quote(type)}, ${JSONObject.quote(data.toString())})"
        activity.runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    fun release() {
        // 2단계: 소켓 정리
    }
}
