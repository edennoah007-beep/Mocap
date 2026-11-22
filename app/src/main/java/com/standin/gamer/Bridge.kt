package com.standin.gamer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID

/** `window.StandIn` inside the web page. Calls arrive on a WebView thread, so UI work hops to main. */
class Bridge(private val app: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val files = HashMap<String, Pair<Uri, OutputStream>>()

    @JavascriptInterface
    fun isApp(): Boolean = true

    @JavascriptInterface
    fun startGamer(options: String) {
        val opts = try { JSONObject(options) } catch (e: Exception) { JSONObject() }
        main.post { WebHost.activity?.startGamerFlow(opts) }
    }

    @JavascriptInterface
    fun stopGamer() {
        main.post { GamerService.instance?.stopRecording() }
    }

    @JavascriptInterface
    fun shareLast() {
        main.post { WebHost.activity?.shareUri(GamerService.lastUri, "video/mp4") }
    }

    @JavascriptInterface
    fun openLast() {
        main.post { WebHost.activity?.viewUri(GamerService.lastUri, "video/mp4") }
    }

    @JavascriptInterface
    fun shareFile(uri: String, mime: String) {
        main.post { WebHost.activity?.shareUri(Uri.parse(uri), mime) }
    }

    // --- saving takes recorded in the page (videos -> Movies/Stand-In, motion .glb -> Downloads/Stand-In) ---
    @JavascriptInterface
    fun beginFile(name: String, mime: String): String {
        return try {
            val video = mime.startsWith("video/")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DOWNLOADS) + "/Stand-In")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = app.contentResolver.insert(collection, values) ?: return ""
            val out = app.contentResolver.openOutputStream(uri) ?: return ""
            val id = UUID.randomUUID().toString()
            synchronized(files) { files[id] = uri to out }
            id
        } catch (e: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun appendFile(id: String, base64: String): Boolean {
        val f = synchronized(files) { files[id] } ?: return false
        return try {
            f.second.write(Base64.decode(base64, Base64.DEFAULT)); true
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun endFile(id: String): String {
        val f = synchronized(files) { files.remove(id) } ?: return ""
        return try {
            f.second.close()
            app.contentResolver.update(f.first, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            f.first.toString()
        } catch (e: Exception) {
            ""
        }
    }
}
