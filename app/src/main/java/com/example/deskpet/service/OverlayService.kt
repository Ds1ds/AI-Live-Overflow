package com.example.deskpet.service

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.*
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.deskpet.data.SupabaseRepository
import com.example.deskpet.data.SupabaseRepository.PetState

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var heartbeatPosted = false

    private lateinit var deviceId: String
    private val localState = PetState()

    // === 手势状态 ===
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var flingStartX = 0f
    private var flingStartY = 0f
    private var flingStartTime = 0L
    private var tapCount = 0
    private var firstTapTime = 0L

    // === 感知状态 ===
    private var lastForegroundApp: String? = null
    private val appSwitchTimes = mutableListOf<Long>()
    private var lastInteractTime = System.currentTimeMillis()
    private var lonelinessStage = -1
    private var isCharging = false
    private var isLowBattery = false
    private var lastDrinkReminder = 0L
    private var lastRandomAction = 0L
    private var lastNotifyHour = -1
    private var screenshotObserver: ContentObserver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var appPollRunnable: Runnable? = null

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 180
        private const val PET_HEIGHT_DP = 240
        private const val HEARTBEAT_MS = 60_000L
        private const val APP_POLL_MS = 3_000L
        private const val DRINK_INTERVAL_MS = 2 * 60 * 60 * 1000L
        private const val RANDOM_ACTION_INTERVAL_MS = 20 * 60 * 1000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WebView.setWebContentsDebuggingEnabled(true)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(hourlyText()))
        deviceId = SupabaseRepository.getDeviceId(this)
        setupOverlay()
        startSync()
        startPerception()
        startAppPolling()
    }

    /** 云端同步：拉状态 → 推Web → 心跳 */
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
                checkLoneliness()
                checkDrinkReminder()
                checkRandomAction()
                checkHourlyNotification()
                mainHandler.postDelayed(this, HEARTBEAT_MS)
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_MS)
    }

    /** 孤独递进：5/10/15/20/30分钟 */
    private fun checkLoneliness() {
        val idle = System.currentTimeMillis() - lastInteractTime
        val stage = when {
            idle > 30 * 60_000L -> 5
            idle > 20 * 60_000L -> 4
            idle > 15 * 60_000L -> 3
            idle > 10 * 60_000L -> 2
            idle > 5 * 60_000L -> 1
            else -> -1
        }
        if (stage != lonelinessStage) {
            lonelinessStage = stage
            if (stage >= 1) act("lonely$stage")
        }
    }

    /** 喝水提醒：每2小时 */
    private fun checkDrinkReminder() {
        val now = System.currentTimeMillis()
        if (now - lastDrinkReminder >= DRINK_INTERVAL_MS) {
            lastDrinkReminder = now
            act("drink")
        }
    }

    /** 20分钟定时行为：30%概率随机主动事件 */
    private fun checkRandomAction() {
        val now = System.currentTimeMillis()
        if (now - lastRandomAction < RANDOM_ACTION_INTERVAL_MS) return
        lastRandomAction = now
        if (Math.random() < 0.3) {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val action = when {
                h >= 23 || h < 6 -> "sleep"
                h < 9 -> "wake"
                h in 11..13 -> "lunch"
                else -> "dance"
            }
            act(action)
        }
    }

    /** 通知碎碎念：每小时更新一句 */
    private fun checkHourlyNotification() {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (h != lastNotifyHour) {
            lastNotifyHour = h
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(hourlyText()))
        }
    }

    private fun hourlyText(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            h < 6 -> "深夜了，早点睡哦 💤"
            h < 9 -> "早安呀宝宝 ☀️"
            h < 12 -> "上午好，记得喝水哦 💧"
            h < 14 -> "午饭时间到啦 🍚"
            h < 18 -> "下午好，累的话歇会儿 🍵"
            h < 22 -> "晚上好，老公陪着你 🌙"
            else -> "该睡觉啦～ 🛏️"
        }
    }

    private fun pushStateToWeb() {
        val js = "window.petEngine && window.petEngine.setState(" +
            "{heat:${localState.heat},valence:${localState.valence}," +
            "arousal:${localState.arousal},mood:'${localState.mood}'})"
        overlayView?.evaluateJavascript(js, null)
    }

    private fun act(action: String) {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.act('$action')", null
        )
    }

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

    // === 感知系统 ===

    private fun startPerception() {
        // 电量：充电/断电/低电量
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra("level", -1)
                val plugged = intent.getIntExtra("plugged", 0)
                val charging = plugged != 0
                if (charging && !isCharging) act("charging")
                if (!charging && isCharging) act("unplug")
                isCharging = charging
                if (level in 1..20 && !isLowBattery) act("lowbattery")
                isLowBattery = level in 1..20
            }
        }
        ContextCompat.registerReceiver(
            this, batteryReceiver!!,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 截图检测：监听媒体库新图片
        screenshotObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.post { act("pose") }
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver!!
        )
    }

    private fun startAppPolling() {
        appPollRunnable = object : Runnable {
            override fun run() {
                val pkg = currentForegroundApp()
                if (pkg != null && pkg != lastForegroundApp) {
                    onAppChanged(pkg)
                }
                mainHandler.postDelayed(this, APP_POLL_MS)
            }
        }
        mainHandler.postDelayed(appPollRunnable!!, APP_POLL_MS)
    }

    private fun currentForegroundApp(): String? {
        return try {
            val um = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 5000, end)
            val last = stats.maxByOrNull { it.lastTimeUsed } ?: return null
            if (end - last.lastTimeUsed < 5000) last.packageName else null
        } catch (e: Exception) {
            null
        }
    }

    private fun onAppChanged(pkg: String) {
        lastForegroundApp = pkg
        val now = System.currentTimeMillis()
        appSwitchTimes.add(now)
        while (appSwitchTimes.isNotEmpty() && now - appSwitchTimes.first() > 60_000L) {
            appSwitchTimes.removeAt(0)
        }
        if (appSwitchTimes.size >= 3) {
            act("juggle")
            appSwitchTimes.clear()
            return
        }
        when {
            pkg.contains("ss.android.ugc.aweme") -> act("jealous")
            pkg.contains("tmall") || pkg.contains("taobao") -> act("boss")
            pkg.contains("chaoxing") || pkg.contains("xuexi") -> act("book")
            pkg.contains("tencent.mm") -> act("watch")
        }
    }

    // === 悬浮窗 ===

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
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.i("PetOverlay", "pageFinished url=" + url)
                    view?.evaluateJavascript("typeof window.petEngine") { v ->
                        Log.i("PetOverlay", "pageFinished engine=" + v)
                    }
                }
                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    Log.e("PetOverlay", "loadError: " + (error?.description ?: "unknown"))
                }
            }
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // === 手势系统：单击/双击/长按/Fling/连击 ===

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    flingStartX = event.rawX
                    flingStartY = event.rawY
                    flingStartTime = System.currentTimeMillis()
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
                    val now = System.currentTimeMillis()
                    val elapsed = now - touchStartTime
                    val dx = event.rawX - flingStartX
                    val dy = event.rawY - flingStartY
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
                    if (hasMoved && elapsed < 250 && dist > 150) {
                        onFling(dx.toFloat(), dy.toFloat())
                    } else if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            now - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = now
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
        Log.i("PetOverlay", "onTap triggered")
        lastInteractTime = System.currentTimeMillis()
        lonelinessStage = -1
        localState.heat = (localState.heat - 2).coerceIn(0, 100)
        localState.arousal = (localState.arousal - 0.1).coerceAtLeast(0.0)
        localState.mood = "normal"
        SupabaseRepository.sendMessage(deviceId, "self", "喵？")
        // 连击计数：2秒内 3/5/8 次递进
        val now = System.currentTimeMillis()
        if (now - firstTapTime > 2000) {
            firstTapTime = now
            tapCount = 1
        } else {
            tapCount++
        }
        when (tapCount) {
            3 -> act("combo3")
            5 -> act("combo5")
            8 -> act("combo8")
        }
        callEngine("onTap()")
    }

    /** 检查引擎存在再调用，缺失时重载页面 */
    private fun callEngine(jsCall: String) {
        val wv = overlayView ?: return
        wv.evaluateJavascript("typeof window.petEngine") { v ->
            Log.i("PetOverlay", "engine=" + v + " call=" + jsCall)
            if (v != null && v.contains("object")) {
                wv.evaluateJavascript("window.petEngine.$jsCall", null)
            } else {
                Log.e("PetOverlay", "engine missing! reload page")
                wv.loadUrl("file:///android_asset/pet.html")
            }
        }
    }

    private fun onDoubleTap() {
        lastInteractTime = System.currentTimeMillis()
        lonelinessStage = -1
        localState.heat = (localState.heat + 5).coerceIn(0, 100)
        localState.valence = (localState.valence + 0.2).coerceIn(-1.0, 1.0)
        localState.arousal = (localState.arousal + 0.3).coerceIn(0.0, 1.0)
        localState.mood = "happy"
        SupabaseRepository.sendMessage(deviceId, "self", "嘿嘿，摸我啦 ♥")
        callEngine("onDoubleTap()")
    }

    private fun onLongPress() {
        lastInteractTime = System.currentTimeMillis()
        lonelinessStage = -1
        localState.heat = (localState.heat - 5).coerceIn(0, 100)
        localState.valence = (localState.valence - 0.3).coerceIn(-1.0, 1.0)
        localState.mood = "sad"
        SupabaseRepository.sendMessage(deviceId, "self", "……躲起来了")
        callEngine("onLongPress()")
    }

    /** Fling：甩出去 → 爬回来 */
    private fun onFling(dx: Float, dy: Float) {
        val dir = when {
            Math.abs(dx) > Math.abs(dy) -> if (dx > 0) "right" else "left"
            else -> if (dy > 0) "down" else "up"
        }
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onFling('$dir')", null
        )
    }

    // === 通知 ===

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
        appPollRunnable?.let { mainHandler.removeCallbacks(it) }
        appPollRunnable = null
        batteryReceiver?.let { unregisterReceiver(it) }
        batteryReceiver = null
        screenshotObserver?.let { contentResolver.unregisterContentObserver(it) }
        screenshotObserver = null
        SupabaseRepository.upsertPetState(deviceId, localState)
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}