package com.standin.gamer

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class App : Application()

/**
 * Owns the single WebView that runs the Stand-In web app.
 * It lives outside any Activity so it can move between the app screen and the
 * floating overlay window without losing the loaded model, rig or camera.
 */
object WebHost {
    private const val START_URL = "https://appassets.androidplatform.net/assets/index.html"

    var web: WebView? = null
        private set
    var wrapper: MutableContextWrapper? = null
        private set
    var activity: MainActivity? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(ctx: Context): WebView {
        web?.let { return it }
        val app = ctx.applicationContext
        val wrap = MutableContextWrapper(ctx)
        wrapper = wrap
        WebView.setWebContentsDebuggingEnabled(true)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app))
            .build()

        val w = WebView(wrap)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        w.setBackgroundColor(Color.TRANSPARENT)

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val a = activity
                if (a == null) request.grant(request.resources) else a.handleWebPermission(request)
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                val a = activity
                if (a == null) {
                    callback.onReceiveValue(null)
                    return true
                }
                return a.openChooser(callback, params)
            }
        }

        w.addJavascriptInterface(Bridge(app), "StandIn")
        w.loadUrl(START_URL)
        web = w
        return w
    }

    /** Point the WebView's context at an Activity (for pickers/dropdowns) or the app (overlay). */
    fun useContext(ctx: Context) {
        wrapper?.baseContext = ctx
    }

    fun js(code: String) {
        val w = web ?: return
        w.post { w.evaluateJavascript(code, null) }
    }

    fun notify(type: String, msg: String = "") {
        js("window.standInNative && window.standInNative(${JSONObject.quote(type)}, ${JSONObject.quote(msg)})")
    }
}
