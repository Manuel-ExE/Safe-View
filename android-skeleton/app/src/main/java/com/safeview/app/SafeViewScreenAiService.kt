package com.safeview.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.app.AppOpsManager
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Optional, parent-consented screen analysis. Frames stay in memory and are
 * passed to the on-device classifier; no screenshots are persisted or uploaded.
 */
class SafeViewScreenAiService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var overlay: View? = null
    private var windowManager: WindowManager? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var lastSampleMs = 0L
    private var consecutiveBlocked = 0
    private var cachedForegroundPackage: String? = null
    private var foregroundCheckedAt = 0L
    private var lossAlertSent = false

    private val statePrefs by lazy {
        getSharedPreferences("safeview_screen_ai", Context.MODE_PRIVATE)
    }

    private val imageListener = ImageReader.OnImageAvailableListener { source ->
        val now = System.currentTimeMillis()
        val image = source.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return@OnImageAvailableListener
            if (!shouldAnalyzeCurrentApp()) {
                consecutiveBlocked = 0
                handler?.post { hideOverlay() }
                return@OnImageAvailableListener
            }
            lastSampleMs = now
            val bitmap = imageToBitmap(image) ?: return@OnImageAvailableListener
            handler?.post { classifyFrame(bitmap) }
        } finally {
            image.close()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        worker = HandlerThread("SafeViewScreenAI").also { it.start() }
        handler = Handler(worker!!.looper)
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            lossAlertSent = true
            setCaptureState(STATE_STOPPED)
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = getIntentExtra(intent) ?: run {
            notifyCaptureLoss(getString(R.string.screen_ai_alert_missing_consent))
            stopSelf()
            return START_NOT_STICKY
        }
        if (resultCode != android.app.Activity.RESULT_OK) {
            notifyCaptureLoss(getString(R.string.screen_ai_alert_permission_denied))
            stopSelf()
            return START_NOT_STICKY
        }
        lossAlertSent = false
        setCaptureState(STATE_STARTING)
        handler?.post { startProjection(resultCode, resultData) }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (projection != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                setCaptureState(STATE_PAUSED)
                notifyCaptureLoss(getString(R.string.screen_ai_alert_capture_stopped))
                hideOverlay()
                stopSelf()
            }
        }, handler)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        // Cap capture size early for memory and classification speed
        val width = min(metrics.widthPixels, MAX_CAPTURE_DIMENSION)
        val height = min(metrics.heightPixels, MAX_CAPTURE_DIMENSION)
        reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener(imageListener, handler)
        display = projection?.createVirtualDisplay(
            "SafeView Screen AI",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            handler
        )
        if (display == null) {
            setCaptureState(STATE_PAUSED)
            notifyCaptureLoss(getString(R.string.screen_ai_alert_setup_failed))
            stopSelf()
        } else {
            setCaptureState(STATE_ACTIVE)
        }
    }

    /**
     * Decide whether the current foreground app should be analyzed.
     * Empty protected set = protect everything (safer default).
     * Missing Usage Access = also protect everything rather than silently weakening.
     */
    private fun shouldAnalyzeCurrentApp(): Boolean {
        val protectedApps = SettingsPrefs(this).protectedApps
        if (protectedApps.isEmpty()) return true

        if (!hasUsageAccess()) return true

        val now = System.currentTimeMillis()
        if (now - foregroundCheckedAt >= FOREGROUND_CACHE_MS) {
            cachedForegroundPackage = getForegroundPackage()
            foregroundCheckedAt = now
        }
        val foreground = cachedForegroundPackage
        // If we cannot determine the foreground package, keep analyzing (safer).
        if (foreground == null) return true
        // Always analyze our own UI so overlays work correctly.
        if (foreground == packageName) return true
        return foreground in protectedApps
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * More reliable than queryUsageStats: walk recent UsageEvents and take the
     * latest MOVE_TO_FOREGROUND / ACTIVITY_RESUMED package.
     */
    private fun getForegroundPackage(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - FOREGROUND_LOOKBACK_MS, now)
            var lastPackage: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        lastPackage = event.packageName
                    }
                }
            }
            lastPackage
        } catch (_: Exception) {
            null
        }
    }

    private fun classifyFrame(bitmap: Bitmap) {
        val app = application as? SafeViewApp ?: run {
            bitmap.recycle()
            return
        }
        val prefs = SettingsPrefs(this)
        // Use live parent-configured thresholds instead of hard-coded defaults.
        val result = app.classifier.classify(
            bitmap,
            explicitThreshold = prefs.explicitThreshold,
            revealingThreshold = prefs.revealingThreshold
        )
        bitmap.recycle()
        if (result == null) {
            // Strict mode must fail closed. A classifier error or unavailable model
            // must never be treated as a safe frame.
            if (prefs.strict) {
                consecutiveBlocked = BLOCK_CONFIRMATION_FRAMES
                showOverlay()
            } else {
                consecutiveBlocked = 0
                hideOverlay()
            }
            return
        }
        if (result.blocked) consecutiveBlocked++ else consecutiveBlocked = 0
        if (consecutiveBlocked >= BLOCK_CONFIRMATION_FRAMES) showOverlay()
        else if (consecutiveBlocked == 0) hideOverlay()
    }

    /**
     * Convert Image → Bitmap, downsampling to CLASSIFY_SIZE to reduce memory
     * and classification cost. Original full-resolution buffer is never kept.
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || pixelStride < 4 || rowStride < pixelStride * width) return null

        // Sample directly into the classifier-sized bitmap; never allocate a full-resolution bitmap.
        val outW = CLASSIFY_SIZE
        val outH = CLASSIFY_SIZE
        val result = try {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        val pixels = IntArray(outW * outH)
        try {
            for (y in 0 until outH) {
                val sourceY = (y * height / outH).coerceIn(0, height - 1)
                val rowStart = sourceY * rowStride
                for (x in 0 until outW) {
                    val sourceX = (x * width / outW).coerceIn(0, width - 1)
                    val offset = rowStart + sourceX * pixelStride
                    if (offset + 3 >= buffer.limit()) throw IllegalArgumentException("truncated screen buffer")
                    val r = buffer.get(offset).toInt() and 0xff
                    val g = buffer.get(offset + 1).toInt() and 0xff
                    val b = buffer.get(offset + 2).toInt() and 0xff
                    val a = buffer.get(offset + 3).toInt() and 0xff
                    pixels[y * outW + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            result.setPixels(pixels, 0, outW, 0, 0, outW, outH)
            return result
        } catch (_: Exception) {
            result.recycle()
            return null
        }
    }

    private fun showOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        val warningMode = SettingsPrefs(this).screenAiWarningMode
        val bg = getColor(R.color.sv_bg)
        val cardColor = getColor(R.color.sv_card)
        val lineColor = getColor(R.color.sv_line)
        val textColor = getColor(R.color.sv_text)
        val mutedColor = getColor(R.color.sv_muted)
        val accentColor = getColor(R.color.sv_accent)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(20)
                setStroke(dp(1).toInt(), lineColor)
            }
            setPadding(dp(28).toInt(), dp(32).toInt(), dp(28).toInt(), dp(28).toInt())
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_shield)
            layoutParams = LinearLayout.LayoutParams(dp(48).toInt(), dp(48).toInt()).apply {
                bottomMargin = dp(16).toInt()
            }
        }

        val title = TextView(this).apply {
            text = getString(if (warningMode) R.string.screen_ai_intervention_title else R.string.screen_ai_block_title)
            setTextColor(textColor)
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val message = TextView(this).apply {
            text = getString(if (warningMode) R.string.screen_ai_intervention_message else R.string.screen_ai_block_message)
            setTextColor(mutedColor)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8).toInt(), 0, dp(20).toInt())
        }
        card.addView(icon)
        card.addView(title, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        card.addView(message, LinearLayout.LayoutParams(dp(240).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        if (warningMode) {
            val home = Button(this).apply {
                text = getString(R.string.screen_ai_go_home)
                setTextColor(bg)
                textSize = 14f
                isAllCaps = false
                background = GradientDrawable().apply {
                    setColor(accentColor)
                    cornerRadius = dp(999)
                }
                setPadding(dp(24).toInt(), dp(10).toInt(), dp(24).toInt(), dp(10).toInt())
                setOnClickListener { goHome() }
            }
            card.addView(
                home,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
        root.addView(
            card,
            LinearLayout.LayoutParams(dp(300).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Always consume touches so the underlying app cannot be used while blocked.
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager?.addView(root, params)
            overlay = root
        } catch (_: Exception) {
            overlay = null
        }
    }

    /** Converts a dp value to pixels using this service's display metrics. */
    private fun dp(value: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(home)
        hideOverlay()
    }

    private fun setCaptureState(state: String) {
        statePrefs.edit().putString(KEY_CAPTURE_STATE, state).apply()
    }

    private fun notifyCaptureLoss(message: String) {
        if (lossAlertSent) return
        lossAlertSent = true
        val settingsIntent = Intent(this, SettingsActivity::class.java)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 7302, settingsIntent, pendingFlags)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.safeview.app.R.drawable.ic_shield)
            .setContentTitle(getString(R.string.screen_ai_alert_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        try { windowManager?.removeView(view) } catch (_: Exception) { }
        overlay = null
    }

    override fun onDestroy() {
        if (statePrefs.getString(KEY_CAPTURE_STATE, STATE_STOPPED) == STATE_ACTIVE) {
            setCaptureState(STATE_STOPPED)
            notifyCaptureLoss(getString(R.string.screen_ai_alert_service_stopped))
        }
        hideOverlay()
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        worker?.quitSafely()
        worker = null
        handler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.safeview.app.R.drawable.ic_shield)
            .setContentTitle(getString(R.string.screen_ai_notification_title))
            .setContentText(getString(R.string.screen_ai_notification_text))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(SERVICE_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.screen_ai_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun getIntentExtra(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_RESULT_DATA)
    }

    companion object {
        const val ACTION_STOP = "com.safeview.app.action.STOP_SCREEN_AI"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "safeview_screen_ai"
        private const val SERVICE_ID = 7301
        private const val ALERT_NOTIFICATION_ID = 7302
        const val CAPTURE_STATE_KEY = "capture_state"
        private const val KEY_CAPTURE_STATE = CAPTURE_STATE_KEY
        const val STATE_STARTING = "starting"
        const val STATE_ACTIVE = "active"
        const val STATE_PAUSED = "paused"
        const val STATE_STOPPED = "stopped"
        private const val SAMPLE_INTERVAL_MS = 500L
        private const val BLOCK_CONFIRMATION_FRAMES = 2
        private const val MAX_CAPTURE_DIMENSION = 720   // reduced from 1280 for memory
        private const val CLASSIFY_SIZE = 224
        private const val FOREGROUND_LOOKBACK_MS = 8_000L
        private const val FOREGROUND_CACHE_MS = 1_500L
    }
}
