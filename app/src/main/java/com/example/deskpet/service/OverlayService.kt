package com.example.deskpet.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import com.example.deskpet.data.SupabaseRepository
import com.example.deskpet.data.SupabaseRepository.PetState

/**
 * Overlay service with Supabase sync.
 * 悬浮窗桌宠 + 云端状态同步（pet_state）+ 消息收发（messages）。
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var heartbeatPosted = false

    private lateinit var deviceId: String
    private val localState = PetState()

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 180
        private const val PET_HEIGHT_DP = 240
        private const val HEARTBEAT_MS = 60_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("..."))
        deviceId = SupabaseRepository.getDeviceId(this)
        setupOverlay()
        startSync()
    }

    /** 启动云端同步：拉取状态 → 推送 Web 端 → 启动心跳 */
    private fun startSync() {
        SupabaseRepository.fetchPetState(deviceId) { state ->
            if (state != null) {
                localState.heat = state.heat
                localState.valence = state.valence
                localState.arousal = state.arousal
                localState.mood = state.mood
            }
            mainHandler.post {
                pushStateToWeb()
                SupabaseRepository.upsertPetState(deviceId, localState)
                startHeartbeat()
            }
        }
    }

    private fun startHeartbeat() {
        if (heartbeatPosted) return
        heartbeatPosted = true
        heartbeatRunnable = object : Runnable {
            override fun run() {
                SupabaseRepository.upsertPetState(deviceId, localState)
                fetchAndShowCloudMessages()
                mainHandler.postDelayed(this, HEARTBEAT_MS)
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_MS)
    }

    /** 把本地状态推给 pet.html 渲染 */
    private fun pushStateToWeb() {
        val js = "window.petEngine && window.petEngine.setState(" +
            "{heat:${localState.heat},valence:${localState.valence}," +
            "arousal:${localState.arousal},mood:'${localState.mood}'})"
        overlayView?.evaluateJavascript(js, null)
    }

    /** 拉取云端消息，把别人发给本机的消息显示成气泡 */
    private fun fetchAndShowCloudMessages() {
        SupabaseRepository.fetchMessages(deviceId, limit = 10) { list ->
            val incoming = list.filter { it.first == "in" }.map { it.second }
            if (incoming.isNotEmpty()) {
                mainHandler.post {
                    val text = incoming.last()
                    overlayView?.evaluateJavascript(
                        "window.petEngine && window.petEngine.showCloud('${escapeJs(text)}')", null
                    )
                }
            }
        }
    }

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // === GESTURE HANDLING ===

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        // 轻触：小猫回应，略降情绪值
        localState.heat = (localState.heat - 2).coerceIn(0, 100)
        localState.arousal = (localState.arousal - 0.1f).coerceAtLeast(0f)
        localState.mood = "normal"
        SupabaseRepository.sendMessage(deviceId, "self", "喵？")
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onTap()", null
        )
    }

    private fun onDoubleTap() {
        // 双击：开心，情绪值上升
        localState.heat = (localState.heat + 5).coerceIn(0, 100)
        localState.valence = (localState.valence + 0.2f).coerceIn(-1f, 1f)
        localState.arousal = (localState.arousal + 0.3f).coerceIn(0f, 1f)
        localState.mood = "happy"
        SupabaseRepository.sendMessage(deviceId, "self", "嘿嘿，摸我啦 ♥")
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onDoubleTap()", null
        )
    }

    private fun onLongPress() {
        // 长按：委屈，情绪值下降
        localState.heat = (localState.heat - 5).coerceIn(0, 100)
        localState.valence = (localState.valence - 0.3f).coerceIn(-1f, 1f)
        localState.mood = "sad"
        SupabaseRepository.sendMessage(deviceId, "self", "……躲起来了")
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onLongPress()", null
        )
    }

    // === NOTIFICATION ===

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("\uD83D\uDC3E")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // === UTILS ===

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        heartbeatPosted = false
        SupabaseRepository.upsertPetState(deviceId, localState)
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
