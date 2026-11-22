package com.standin.gamer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var gamerOptions: JSONObject = JSONObject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebHost.activity = this
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF0C0E0F.toInt())
        // shown only while the character is floating over a game (the WebView covers it otherwise)
        root.addView(TextView(this).apply {
            text = "Gamer mode is on.\nYour character is floating over your game.\n\nTap ■ Stop on the character, or Stop & save in the notification, to get your MP4."
            setTextColor(0xFFEEF1EC.toInt()); textSize = 17f; gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        attachWeb()

        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { !has(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
        handleIntent(intent)
    }

    /** Put the shared WebView back into this screen (unless it is floating over a game). */
    private fun attachWeb() {
        if (GamerService.running) return
        val w = WebHost.obtain(this)
        WebHost.useContext(this)
        val parent = w.parent as? ViewGroup
        if (parent === root) return
        parent?.removeView(w)
        root.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onResume() {
        super.onResume()
        WebHost.activity = this
        attachWeb()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        attachWeb()
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i != null && i.getBooleanExtra(EXTRA_DONE, false)) {
            WebHost.js("window.standInGamer && window.standInGamer(false)")
            i.removeExtra(EXTRA_DONE)
        }
    }

    override fun onDestroy() {
        if (WebHost.activity === this) {
            WebHost.activity = null
            WebHost.useContext(applicationContext)
            val w = WebHost.web
            if (w != null && w.parent === root) root.removeView(w)
        }
        super.onDestroy()
    }

    @Deprecated("Keeps the camera, model and rig alive instead of closing the app")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------- camera / mic for the web page ----------
    fun handleWebPermission(req: PermissionRequest) {
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
        }
    }

    // ---------- file picker for models and videos ----------
    fun openChooser(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = cb
        val accept = params.acceptTypes.joinToString(",")
        val i = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (accept.contains("video")) "video/*" else "*/*")
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        return try {
            startActivityForResult(Intent.createChooser(i, "Choose a file"), REQ_FILE)
            true
        } catch (e: Exception) {
            fileCallback = null
            cb.onReceiveValue(null)
            true
        }
    }

    // ---------- gamer mode ----------
    fun startGamerFlow(opts: JSONObject) {
        if (GamerService.running) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” for Stand-In Gamer, then press Start again.", Toast.LENGTH_LONG).show()
            WebHost.notify("needOverlay")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (opts.optBoolean("mic", true) && !has(Manifest.permission.RECORD_AUDIO)) opts.put("mic", false)
        gamerOptions = opts
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE -> {
                val cb = fileCallback ?: return
                fileCallback = null
                var uris: Array<Uri>? = null
                if (resultCode == RESULT_OK && data != null) {
                    val clip = data.clipData
                    uris = if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
                }
                cb.onReceiveValue(uris)
            }
            REQ_PROJECTION -> {
                if (resultCode != RESULT_OK || data == null) {
                    WebHost.notify("error", "Screen recording was not allowed.")
                    return
                }
                val o = gamerOptions
                val svc = Intent(this, GamerService::class.java)
                    .putExtra(GamerService.EXTRA_CODE, resultCode)
                    .putExtra(GamerService.EXTRA_DATA, data)
                    .putExtra(GamerService.EXTRA_LANDSCAPE, o.optBoolean("landscape", true))
                    .putExtra(GamerService.EXTRA_MIC, o.optBoolean("mic", true))
                    .putExtra(GamerService.EXTRA_BITRATE, o.optInt("bitrate", 10_000_000))
                    .putExtra(GamerService.EXTRA_SIZE, o.optInt("size", 1))
                startForegroundService(svc)
                Toast.makeText(this, "Recording! Open your game now.", Toast.LENGTH_LONG).show()
                root.postDelayed({ moveTaskToBack(true) }, 700)
            }
        }
    }

    // ---------- share / play files ----------
    fun shareUri(uri: Uri?, mime: String) {
        if (uri == null) {
            Toast.makeText(this, "Nothing recorded yet.", Toast.LENGTH_SHORT).show(); return
        }
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share"))
    }

    fun viewUri(uri: Uri?, mime: String) {
        if (uri == null) {
            Toast.makeText(this, "Nothing recorded yet.", Toast.LENGTH_SHORT).show(); return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            Toast.makeText(this, "No video player found.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_DONE = "gamerDone"
        private const val REQ_PERMS = 1
        private const val REQ_WEB_PERMS = 2
        private const val REQ_FILE = 3
        private const val REQ_PROJECTION = 4
    }
}
