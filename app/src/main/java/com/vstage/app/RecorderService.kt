package com.vstage.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Records the screen (the avatar stage) to an MP4 (H.264 video + AAC mic audio, voice-changed)
 * saved in the phone gallery under Movies/VStage.
 *
 * Two steps so the countdown isn't in the video:
 *  1. start  -> foreground service + screen-capture permission are held ("ready")
 *  2. begin  -> the encoder actually starts writing the MP4
 */
class RecorderService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIC = "mic"
        private const val ACTION_STOP = "com.vstage.app.STOP"
        private const val CHANNEL = "recording"
        private const val TAG = "VStage"

        @Volatile var instance: RecorderService? = null
            private set
        @Volatile var recording = false
            private set
        @Volatile var lastUri: Uri? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: Mp4Recorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outUri: Uri? = null
    private var mic = true
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(); return START_NOT_STICKY
        }
        if (intent == null || instance != null) return START_NOT_STICKY
        instance = this
        finished = false
        try {
            mic = intent.getBooleanExtra(EXTRA_MIC, true) && has(Manifest.permission.RECORD_AUDIO)
            goForeground()
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_DATA)
            requireNotNull(data) { "Screen recording permission is missing." }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("Screen recording was not allowed.")
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stop() } }
            }, main)
            projection = proj
            Web.notify("ready")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            Web.notify("error", "Could not start recording: ${e.message}")
            finish(keepFile = false)
        }
        return START_NOT_STICKY
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("VStage is recording")
            .setContentText("Double-tap the screen, press Volume Down, or tap Stop & save.")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Stop & save", stopPi).build())
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (mic && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(1, n, type)
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    /** Start writing the MP4. Called after the on-screen countdown. */
    fun begin(landscape: Boolean, bitrate: Int, fps: Int, voice: String) {
        val proj = projection ?: return
        if (recorder != null) return
        try {
            val (sw, sh) = screenSize()
            val longSide = max(sw, sh); val shortSide = min(sw, sh)
            val density = resources.displayMetrics.densityDpi

            val name = "VStage_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VStage")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the video file.")
            outUri = uri

            // Full HD first, then 720p for phones whose encoder refuses the bigger size
            var lastError: Exception? = null
            for (maxLong in intArrayOf(1920, 1280)) {
                val scale = min(1f, maxLong.toFloat() / longSide)
                val l = even16((longSide * scale).roundToInt()); val s = even16((shortSide * scale).roundToInt())
                val w = if (landscape) l else s
                val h = if (landscape) s else l
                var rec: Mp4Recorder? = null
                try {
                    pfd?.close()
                    pfd = contentResolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Could not open the video file.")
                    rec = Mp4Recorder(w, h, fps, bitrate, mic, voice, pfd!!.fileDescriptor) { msg ->
                        main.post { Web.notify("error", msg); stop() }
                    }
                    val d = display
                    if (d == null) {
                        display = proj.createVirtualDisplay(
                            "VStageRecording", w, h, density,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.inputSurface, null, main
                        )
                    } else {
                        d.resize(w, h, density)
                        d.surface = rec.inputSurface
                    }
                    rec.start()
                    recorder = rec
                    recording = true
                    Log.i(TAG, "recording ${w}x$h @ ${bitrate / 1000} kbps, mic=$mic, voice=$voice")
                    Web.notify("recording")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "recorder setup failed at $maxLong", e)
                    lastError = e
                    try { rec?.stop() } catch (_: Exception) { }
                    recorder = null
                }
            }
            throw lastError ?: IllegalStateException("This phone's video encoder refused to start.")
        } catch (e: Exception) {
            Log.e(TAG, "begin failed", e)
            Web.notify("error", "Could not start recording: ${e.message}")
            finish(keepFile = false)
        }
    }

    /** Stop and keep the MP4 (or cancel if recording never began). */
    fun stop() {
        if (finished) return
        val wasRecording = recorder != null
        val ok = finish(keepFile = true)
        when {
            ok -> Web.notify("saved", lastUri?.toString() ?: "")
            wasRecording -> Web.notify("error", "The recording was too short or failed to save.")
            else -> Web.notify("cancelled")
        }
    }

    /** Releases everything. Returns true if a playable MP4 was kept. */
    private fun finish(keepFile: Boolean): Boolean {
        if (finished) return false
        finished = true
        recording = false
        var ok = keepFile && recorder != null
        var kept = false
        display?.release(); display = null
        recorder?.let {
            val good = try { it.stop() } catch (e: Exception) { Log.w(TAG, "stop failed", e); false }
            if (!good) ok = false
        }
        recorder = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        try { pfd?.close() } catch (_: Exception) { }
        pfd = null
        outUri?.let { uri ->
            if (ok) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                lastUri = uri
                kept = true
            } else {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            }
        }
        outUri = null
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return kept
    }

    private fun even16(x: Int) = max(16, x - (x % 16))

    override fun onDestroy() {
        if (!finished) finish(keepFile = true)
        super.onDestroy()
    }
}
