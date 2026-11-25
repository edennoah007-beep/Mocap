package com.vstage.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/** Lets the recorder service talk to the page. */
object Web {
    @Volatile var view: WebView? = null
    fun js(code: String) {
        val w = view ?: return
        w.post { w.evaluateJavascript(code, null) }
    }
    fun notify(type: String, msg: String = "") {
        js("window.vstageNative && window.vstageNative(${JSONObject.quote(type)}, ${JSONObject.quote(msg)})")
    }
}

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var wantMic = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
        }
        web.setBackgroundColor(Color.BLACK)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermission(request) }
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                openChooser(callback, params)
        }
        web.addJavascriptInterface(Bridge(), "VStage")
        setContentView(web)
        Web.view = web
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { !has(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Full screen so the status bar / nav bar aren't in your video. */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onResume() { super.onResume(); web.onResume(); Web.js("window.vstageResume && window.vstageResume()") }
    override fun onPause() { super.onPause() }

    override fun onDestroy() {
        RecorderService.instance?.stop()
        if (Web.view === web) Web.view = null
        web.destroy()
        super.onDestroy()
    }

    // Volume Down stops a recording (handy when the screen controls are hidden)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && RecorderService.instance != null) {
            if (event.action == KeyEvent.ACTION_UP) RecorderService.instance?.stop()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Back closes open panels in the page first")
    override fun onBackPressed() {
        web.evaluateJavascript("window.vstageBack ? window.vstageBack() : false") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------- camera / mic for the page ----------
    private fun handleWebPermission(req: PermissionRequest) {
        val missing = mutableListOf<String>()
        if (req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !has(Manifest.permission.CAMERA)) missing += Manifest.permission.CAMERA
        if (req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && !has(Manifest.permission.RECORD_AUDIO)) missing += Manifest.permission.RECORD_AUDIO
        if (missing.isEmpty()) {
            req.grant(req.resources)
        } else {
            pendingWebPermission?.deny()
            pendingWebPermission = req
            requestPermissions(missing.toTypedArray(), REQ_WEB_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WEB_PERMS) {
            val req = pendingWebPermission ?: return
            pendingWebPermission = null
            val ok = req.resources.filter {
                when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> has(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> has(Manifest.permission.RECORD_AUDIO)
                    else -> false
                }
            }
            if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
        } else if (requestCode == REQ_PERMS) {
            Web.js("window.vstageResume && window.vstageResume()")
        }
    }

    // ---------- file picker for 3D models / background images ----------
    private fun openChooser(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = cb
        val accept = params.acceptTypes.joinToString(",")
        val i = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (accept.contains("image")) "image/*" else "*/*")
        return try {
            startActivityForResult(Intent.createChooser(i, "Choose a file"), REQ_FILE)
            true
        } catch (e: Exception) {
            fileCallback = null
            cb.onReceiveValue(null)
            true
        }
    }

    // ---------- recording ----------
    fun requestRecording(mic: Boolean) {
        if (RecorderService.instance != null) return
        wantMic = mic && has(Manifest.permission.RECORD_AUDIO)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE -> {
                val cb = fileCallback ?: return
                fileCallback = null
                val uri = if (resultCode == RESULT_OK) data?.data else null
                cb.onReceiveValue(uri?.let { arrayOf(it) })
            }
            REQ_PROJECTION -> {
                if (resultCode != RESULT_OK || data == null) {
                    Web.notify("cancelled", "Screen recording was not allowed.")
                    return
                }
                startForegroundService(
                    Intent(this, RecorderService::class.java)
                        .putExtra(RecorderService.EXTRA_CODE, resultCode)
                        .putExtra(RecorderService.EXTRA_DATA, data)
                        .putExtra(RecorderService.EXTRA_MIC, wantMic)
                )
            }
        }
    }

    private fun shareUri(uri: Uri?) {
        if (uri == null) { Toast.makeText(this, "Nothing recorded yet.", Toast.LENGTH_SHORT).show(); return }
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share video"))
    }

    private fun viewUri(uri: Uri?) {
        if (uri == null) { Toast.makeText(this, "Nothing recorded yet.", Toast.LENGTH_SHORT).show(); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            Toast.makeText(this, "No video player found.", Toast.LENGTH_SHORT).show()
        }
    }

    /** `window.VStage` in the page. Calls arrive on a WebView thread, so hop to the UI thread. */
    inner class Bridge {
        @JavascriptInterface fun isApp(): Boolean = true
        @JavascriptInterface fun startRecording(mic: Boolean) { runOnUiThread { requestRecording(mic) } }
        @JavascriptInterface fun beginRecording(landscape: Boolean, bitrate: Int, fps: Int) {
            runOnUiThread { RecorderService.instance?.begin(landscape, bitrate, fps) }
        }
        @JavascriptInterface fun stopRecording() { runOnUiThread { RecorderService.instance?.stop() } }
        @JavascriptInterface fun shareLast() { runOnUiThread { shareUri(RecorderService.lastUri) } }
        @JavascriptInterface fun openLast() { runOnUiThread { viewUri(RecorderService.lastUri) } }
        @JavascriptInterface fun toast(msg: String) { runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() } }
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_WEB_PERMS = 2
        private const val REQ_FILE = 3
        private const val REQ_PROJECTION = 4
    }
}
