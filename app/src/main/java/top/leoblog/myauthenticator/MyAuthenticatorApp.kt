package top.leoblog.myauthenticator

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.util.Log
import top.leoblog.myauthenticator.keepalive.KeepAliveManager
import top.leoblog.myauthenticator.crypto.Sm4GcmCrypto
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application 类
 *
 * 负责全局初始化：
 * 1. 注册 Bouncy Castle Provider（国密支持）
 * 2. 创建通知渠道
 * 3. 注册全局未捕获异常处理器（输出到文件 + Logcat）
 * 4. 追踪 Activity 生命周期，判断 app 前后台切换
 */
class MyAuthenticatorApp : Application() {

    companion object {
        private const val TAG = "MyAuthenticatorApp"
        const val CHANNEL_ID_WEBSOCKET = "channel_websocket"
        const val CHANNEL_ID_CHALLENGE = "channel_challenge"
        private const val CRASH_LOG_FILENAME = "app_crash_log.txt"
        lateinit var instance: MyAuthenticatorApp
            private set

        // ======== Activity 前后台追踪 ========
        private var activityCounter = 0
        @Volatile
        private var justCameFromBackground = false

        /**
         * 检查 app 是否刚从后台切换回前台（仅消费一次，读取后自动重置）
         */
        fun checkAndClearForegroundFlag(): Boolean {
            val result = justCameFromBackground
            if (result) {
                justCameFromBackground = false
            }
            return result
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 注册全局崩溃捕获器
        setupCrashHandler()

        // 初始化 Bouncy Castle（SM4 支持）
        Sm4GcmCrypto.initBouncyCastle()

        // 创建通知渠道
        createNotificationChannels()

        // 注册 Activity 生命周期回调，追踪前后台切换
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activityCounter == 0) {
                    // 上一个可见 Activity 为 0 → app 刚从后台回到前台
                    justCameFromBackground = true
                    Log.d(TAG, "App came to foreground")
                }
                activityCounter++
                Log.v(TAG, "Activity started: ${activity.javaClass.simpleName}, counter=$activityCounter")
            }

            override fun onActivityStopped(activity: Activity) {
                activityCounter--
                if (activityCounter == 0) {
                    // 所有 Activity 都停止 → app 进入后台
                    Log.d(TAG, "App went to background")
                }
                Log.v(TAG, "Activity stopped: ${activity.javaClass.simpleName}, counter=$activityCounter")
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // 初始化保活管理（延迟执行，等待 UI 完全初始化）
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val keepAliveManager = KeepAliveManager(this)
                keepAliveManager.startCurrentMode()
                Log.d(TAG, "保活管理已初始化，当前模式: ${keepAliveManager.getCurrentMode().displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "保活管理初始化失败", e)
            }
        }, 3000) // 延迟 3 秒，给系统足够时间完成初始化
    }

    /**
     * 注册全局未捕获异常处理器
     *
     * 将崩溃信息同时输出到 Logcat（tag=CRASH_MONITOR）和内部存储文件，
     * 方便通过 adb logcat 或 Android Studio Device Explorer 查看。
     *
     * 使用方式：
     *   adb logcat -s CRASH_MONITOR
     * 或在 Android Studio 中加载崩溃日志文件：
     *   /data/data/top.leoblog.myauthenticator/files/crash_logs/app_crash_log.txt
     */
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable)
            } catch (_: Exception) {
                // 写日志本身异常不影响崩溃处理
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 将崩溃信息写入 Logcat 和文件
     */
    private fun writeCrashLog(thread: Thread?, throwable: Throwable) {
        // 1. Logcat — 用 tag=CRASH_MONITOR 方便过滤
        Log.e("CRASH_MONITOR", "═══════════════════════════════")
        Log.e("CRASH_MONITOR", "💥 APP CRASHED! ${throwable.javaClass.simpleName}: ${throwable.message}")
        Log.e("CRASH_MONITOR", "Thread: ${thread?.name ?: "unknown"}")
        for (element in throwable.stackTrace.take(20)) {
            Log.e("CRASH_MONITOR", "  at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }
        throwable.cause?.let { cause ->
            Log.e("CRASH_MONITOR", "Caused by: ${cause.javaClass.name}: ${cause.message}")
            cause.stackTrace.take(10).forEach {
                Log.e("CRASH_MONITOR", "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
            }
        }
        Log.e("CRASH_MONITOR", "═══════════════════════════════")

        // 2. 写入 external files dir → 手机文件管理器可直接访问
        //    路径: /storage/emulated/0/Android/data/top.leoblog.myauthenticator/files/crash_logs/app_crash_log.txt
        //    不用 root，MIUI 自带文件管理器可以查看
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logContent = buildString {
            appendLine()
            appendLine("=== CRASH at $timestamp ===")
            appendLine("Thread: ${thread?.name ?: "unknown"}")
            appendLine("${throwable.javaClass.name}: ${throwable.message}")
            throwable.stackTrace.forEach { appendLine("  $it") }
            throwable.cause?.let {
                appendLine("Caused by: ${it.javaClass.name}: ${it.message}")
                it.stackTrace.forEach { appendLine("  $it") }
            }
            appendLine("==============================")
        }
        try {
            // 优先写入外部存储（MIUI 文件管理器可读）
            val extDir = getExternalFilesDir("crash_logs")
            if (extDir != null) {
                extDir.mkdirs()
                val extFile = File(extDir, CRASH_LOG_FILENAME)
                FileWriter(extFile, true).use { writer -> writer.write(logContent) }
                Log.i(TAG, "Crash log saved (external): ${extFile.absolutePath}")
            }
        } catch (_: Exception) {
            // 外部存储不可用时降级到内部存储
            try {
                val intDir = File(filesDir, "crash_logs")
                intDir.mkdirs()
                val intFile = File(intDir, CRASH_LOG_FILENAME)
                FileWriter(intFile, true).use { writer -> writer.write(logContent) }
                Log.i(TAG, "Crash log saved (internal): ${intFile.absolutePath}")
            } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // WebSocket 前台服务通知渠道
        val wsChannel = NotificationChannel(
            CHANNEL_ID_WEBSOCKET,
            "WebSocket 服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 WebSocket 长连接"
            setShowBadge(false)
        }

        // 挑战通知渠道
        val challengeChannel = NotificationChannel(
            CHANNEL_ID_CHALLENGE,
            "认证挑战",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "接收 3 选 1 认证挑战"
        }

        notificationManager.createNotificationChannel(wsChannel)
        notificationManager.createNotificationChannel(challengeChannel)
    }
}