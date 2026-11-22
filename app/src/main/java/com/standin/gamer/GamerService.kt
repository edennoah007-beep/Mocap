package com.standin.gamer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Gamer mode:
 *  - records the WHOLE screen to an MP4 (H.264 + AAC) in Movies/Stand-In with MediaProjection,
 *  - floats the Stand-In WebView (your live motion-captured character) over the game,
 *    so the recording contains the game with your character on top.
 * It is a camera + microphone + media-projection foreground service, which is what lets
 * the front camera keep tracking you while another app (the game) is in front.
 */
class GamerService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_LANDSCAPE = "landscape"
        const val EXTRA_MIC = "mic"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_SIZE = "size"
        private const val ACTION_STOP = "com.standin.gamer.STOP"
        private const val CHANNEL = "gamer"
        private const val TAG = "StandInGamer"
        private val SIZES = floatArrayOf(0.3f, 0.45f, 0.62f)

        @Volatile var running = false
            private set
        @Volatile var instance: GamerService? = null
            private set
        @Volatile var lastUri: Uri? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outUri: Uri? = null
    private var stopping = false

    private lateinit var wm: WindowManager
    private var overlay: FrameLayout? = null
    private var bar: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var sizeIndex = 1
    private val hideBar = Runnable { bar?.animate()?.alpha(0f)?.setDuration(250)?.start() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording(); return START_NOT_STICKY
        }
        if (intent == null || running) return START_NOT_STICKY
        instance = this
        stopping = false
        try {
            goForeground()
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_DATA)
            requireNotNull(data) { "Screen recording permission is missing." }
            sizeIndex = intent.getIntExtra(EXTRA_SIZE, 1).coerceIn(0, SIZES.size - 1)
            startRecording(
                code, data,
                landscape = intent.getBooleanExtra(EXTRA_LANDSCAPE, true),
                mic = intent.getBooleanExtra(EXTRA_MIC, true) && has(Manifest.permission.RECORD_AUDIO),
                bitrate = intent.getIntExtra(EXTRA_BITRATE, 10_000_000)
            )
            running = true
            showOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            WebHost.notify("error", "Could not start recording: ${e.message}")
            stopping = true
            cleanup(keepFile = false)
            stopSelfNow()
        }
        return START_NOT_STICKY
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------------- foreground notification ----------------
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gamer recording", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, GamerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Stand-In is recording")
            .setContentText("Your character is on screen. Tap Stop & save to get the MP4.")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Stop & save", stopPi).build())
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30) {
            if (has(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (has(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(1, n, type)
    }

    // ---------------- screen recording ----------------
    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun startRecording(code: Int, data: Intent, landscape: Boolean, mic: Boolean, bitrate: Int) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("Screen capture was not allowed.")
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopRecording() } }
        }, main)
        projection = proj

        val (sw, sh) = screenSize()
        val longSide = max(sw, sh); val shortSide = min(sw, sh)
        val density = resources.displayMetrics.densityDpi

        // MP4 file in the gallery: Movies/Stand-In
        val name = "StandIn_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Stand-In")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the video file.")
        outUri = uri

        // try full HD first, then 720p for phones whose encoder refuses the bigger size
        var lastError: Exception? = null
        for (maxLong in intArrayOf(1920, 1280)) {
            val scale = min(1f, maxLong.toFloat() / longSide)
            val l = even16((longSide * scale).roundToInt()); val s = even16((shortSide * scale).roundToInt())
            val w = if (landscape) l else s
            val h = if (landscape) s else l
            try {
                pfd?.close()
                pfd = contentResolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Could not open the video file.")
                val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
                if (mic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (mic) {
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    r.setAudioEncodingBitRate(160_000)
                    r.setAudioSamplingRate(48_000)
                    r.setAudioChannels(1)
                }
                r.setVideoSize(w, h)
                r.setVideoFrameRate(30)
                r.setVideoEncodingBitRate(bitrate)
                r.setOutputFile(pfd!!.fileDescriptor)
                r.setOnErrorListener { _, what, extra -> Log.e(TAG, "recorder error $what/$extra"); main.post { stopRecording() } }
                r.prepare()
                display = proj.createVirtualDisplay(
                    "StandInRecording", w, h, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main
                )
                r.start()
                recorder = r
                Log.i(TAG, "recording ${w}x$h @ ${bitrate / 1000} kbps, mic=$mic")
                return
            } catch (e: Exception) {
                Log.w(TAG, "recorder setup failed at $maxLong", e)
                lastError = e
                display?.release(); display = null
                recorder?.release(); recorder = null
            }
        }
        throw lastError ?: IllegalStateException("This phone's video encoder refused to start.")
    }

    private fun even16(x: Int) = max(16, x - (x % 16))

    fun stopRecording() {
        if (stopping) return
        stopping = true
        val ok = cleanup(keepFile = true)
        if (ok) WebHost.notify("saved", lastUri?.toString() ?: "")
        else WebHost.notify("error", "The recording was too short or failed to save.")
        // bring the app back (allowed: we hold the overlay permission)
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_DONE, true)
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not reopen app", e)
            WebHost.js("window.standInGamer && window.standInGamer(false)")
        }
        stopSelfNow()
    }

    /** Releases everything. Returns true if a playable MP4 was kept. */
    private fun cleanup(keepFile: Boolean): Boolean {
        running = false
        var ok = keepFile && recorder != null
        var kept = false
        recorder?.let {
            try { it.stop() } catch (e: Exception) { Log.w(TAG, "stop failed", e); ok = false }
            it.release()
        }
        recorder = null
        display?.release(); display = null
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
        removeOverlay()
        return kept
    }

    private fun stopSelfNow() {
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) cleanup(keepFile = true)
        instance = null
        super.onDestroy()
    }

    // ---------------- floating character window ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun boxSize(): Pair<Int, Int> {
        val (sw, sh) = screenSize()
        val w = (min(sw, sh) * SIZES[sizeIndex]).roundToInt()
        return w to (w * 4 / 3)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        wm = getSystemService(WindowManager::class.java)
        val web = WebHost.web ?: throw IllegalStateException("The character view is not loaded.")
        (web.parent as? ViewGroup)?.removeView(web)
        WebHost.useContext(applicationContext)

        val box = FrameLayout(this)
        box.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // small control bar: move · resize · stop. It fades out so it isn't in your video.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xCC0C0E0F.toInt()) }
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        fun chip(label: String, color: Int = 0xFFEEF1EC.toInt()) = TextView(this).apply {
            text = label; setTextColor(color); textSize = 15f
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val move = chip("✥ Move")
        val size = chip("⤢")
        val stop = chip("■ Stop", 0xFFFF5247.toInt())
        controls.addView(move); controls.addView(size); controls.addView(stop)
        box.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        bar = controls

        val (w, h) = boxSize()
        val (sw, sh) = screenSize()
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = max(0, sw - w - dp(12))
            y = max(0, sh - h - dp(12))
        }
        params = lp

        // touching the character shows the controls for a few seconds (the game underneath keeps its touches)
        web.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) showBar(); true }
        stop.setOnClickListener { stopRecording() }
        size.setOnClickListener {
            sizeIndex = (sizeIndex + 1) % SIZES.size
            val (nw, nh) = boxSize(); lp.width = nw; lp.height = nh
            wm.updateViewLayout(box, lp); showBar()
        }
        var startX = 0f; var startY = 0f; var baseX = 0; var baseY = 0
        move.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; baseX = lp.x; baseY = lp.y; main.removeCallbacks(hideBar); controls.alpha = 1f }
                MotionEvent.ACTION_MOVE -> {
                    val (cw, ch) = screenSize()
                    lp.x = (baseX + (e.rawX - startX)).roundToInt().coerceIn(-lp.width / 2, cw - lp.width / 2)
                    lp.y = (baseY + (e.rawY - startY)).roundToInt().coerceIn(0, ch - dp(40))
                    wm.updateViewLayout(box, lp)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> showBar()
            }
            true
        }

        wm.addView(box, lp)
        overlay = box
        WebHost.js("window.standInGamer && window.standInGamer(true)")
        showBar()
    }

    private fun showBar() {
        val b = bar ?: return
        main.removeCallbacks(hideBar)
        b.animate().cancel(); b.alpha = 1f
        main.postDelayed(hideBar, 3000)
    }

    private fun removeOverlay() {
        main.removeCallbacks(hideBar)
        val box = overlay ?: return
        overlay = null; bar = null
        WebHost.web?.let { web ->
            web.setOnTouchListener(null)
            if (web.parent === box) box.removeView(web)
        }
        try { wm.removeView(box) } catch (_: Exception) { }
        WebHost.js("window.standInGamer && window.standInGamer(false)")
    }
}
