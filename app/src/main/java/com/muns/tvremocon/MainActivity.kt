package com.muns.tvremocon

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * 화면은 assets/index.html, 통신은 AndroidBridge(Kotlin) 담당.
 * 구조: ARCHITECTURE.md 1절
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // 화면 설정(localStorage)
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.setBackgroundColor(0xFF111317.toInt())
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            // 앱 화면 밖으로 나가는 이동은 막는다
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != APP_HOST
        }

        bridge = AndroidBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.loadUrl("https://$APP_HOST/assets/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 화면 쪽에서 열린 패널을 먼저 닫게 하고, 처리 안 했으면 종료
        webView.evaluateJavascript("(window.onBackKey && window.onBackKey()) ? 1 : 0") { r ->
            if (r != "1") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // 화면의 visibilitychange(돌아오면 재연결)가 동작하도록 WebView에 전달
    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        bridge.release()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val APP_HOST = "appassets.androidplatform.net"
    }
}
