package com.muns.tvremocon

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

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
        applyTheme()
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

    // 시스템 다크 모드 변경 (configChanges에 uiMode가 있어 액티비티를 다시 만들지 않음)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
        bridge.emit("theme", JSONObject().put("dark", isDark(this)))
    }

    /** 상태·내비게이션 바와 WebView 배경을 화면 테마(index.html --bg)와 맞춘다 */
    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val dark = isDark(this)
        val bg = if (dark) 0xFF000000.toInt() else 0xFFF2F2F4.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        webView.setBackgroundColor(bg)
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        } else {
            var flags = window.decorView.systemUiVisibility
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            flags = if (dark) flags and light.inv() else flags or light
            window.decorView.systemUiVisibility = flags
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

        fun isDark(ctx: Context): Boolean =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
