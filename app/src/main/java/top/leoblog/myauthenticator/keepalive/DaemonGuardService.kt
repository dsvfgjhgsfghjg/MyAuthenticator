package top.leoblog.myauthenticator.keepalive

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log

/**
 * 守护进程服务（运行在 :guard 独立进程）
 *
 * 职责：
 * 1. 作为低优先级的后台进程，与主进程互相监视
 * 2. 如果主进程死亡，尝试重启主进程的 WebSocketService
 * 3. 通过 Messenger 实现 IPC 心跳通信
 *
 * 注意：
 * - 运行在独立进程 `:guard`，不显示前台通知（减少被杀概率）
 * - 使用空进程优先级（极低资源消耗）
 * - Android 系统在杀进程时会优先杀死空进程，所以 :guard 的存活
 *   策略是：保持空进程状态，让系统认为它"无害"从而延迟杀死
 */
class DaemonGuardService : Service() {

    companion object {
        private const val TAG = "DaemonGuardService"

        /** 心跳消息类型：守护进程 → 主进程 */
        const val MSG_HEARTBEAT = 1

        /** 心跳消息类型：主进程 → 守护进程 */
        const val MSG_HEARTBEAT_ACK = 2

        /** 心跳检查间隔（毫秒） */
        private const val HEARTBEAT_INTERVAL_MS = 15_000L

        /** 心跳超时时间（毫秒） */
        private const val HEARTBEAT_TIMEOUT_MS = 60_000L

        // 是否正在运行（供 KeepAliveManager 检查）
        @Volatile
        var isRunning = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mainMessenger: Messenger? = null
    private var lastHeartbeatFromMain: Long = 0L
    private var heartbeatCheckRunning = false

    /** 守护进程的 Messenger */
    private val guardMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        handleMainMessage(msg)
        true
    })

    /** 心跳检查 runnable */
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!heartbeatCheckRunning) return
            checkMainProcessAlive()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🛡️ 守护进程服务已创建 (PID=${android.os.Process.myPid()})")
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🛡️ 守护进程服务启动")

        // 启动心跳检查
        if (!heartbeatCheckRunning) {
            heartbeatCheckRunning = true
            lastHeartbeatFromMain = System.currentTimeMillis()
            handler.post(heartbeatRunnable)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "🛡️ 守护进程被绑定")
        return guardMessenger.binder
    }

    /**
     * 处理来自主进程的消息
     */
    private fun handleMainMessage(msg: Message) {
        when (msg.what) {
            DaemonMonitor.MSG_HEARTBEAT_FROM_MAIN -> {
                lastHeartbeatFromMain = System.currentTimeMillis()
                // 回复心跳确认
                try {
                    val reply = Message.obtain(null, DaemonMonitor.MSG_HEARTBEAT_FROM_GUARD)
                    msg.replyTo?.send(reply)
                } catch (e: Exception) {
                    Log.w(TAG, "回复心跳确认失败", e)
                }
            }
            DaemonMonitor.MSG_PONG -> {
                // 主进程回复了 PONG，记录心跳
                lastHeartbeatFromMain = System.currentTimeMillis()
                Log.v(TAG, "主进程 PONG ❤️")
            }
            DaemonMonitor.MSG_STATUS_REPLY -> {
                // 主进程状态回复
                Log.d(TAG, "主进程状态: ${msg.obj}")
            }
        }
    }

    /**
     * 检查主进程是否存活
     * 如果主进程死亡，尝试重启 WebSocketService
     */
    private fun checkMainProcessAlive() {
        val timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatFromMain
        if (timeSinceLastHeartbeat < HEARTBEAT_TIMEOUT_MS) {
            return // 心跳正常
        }

        Log.w(TAG, "⚠️ 主进程心跳超时 (${timeSinceLastHeartbeat}ms)，检查进程状态")

        // 通过 ActivityManager 确认主进程是否真的死了
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isMainAlive = am?.runningAppProcesses?.any { process ->
            !process.processName.contains(":guard") &&
                process.processName.contains(packageName) &&
                process.pid != android.os.Process.myPid()
        } ?: true // 无法检查时默认存活，避免误杀

        if (!isMainAlive) {
            Log.e(TAG, "💀 主进程已死亡！尝试重启...")
            restartMainProcess()
        } else {
            Log.d(TAG, "主进程仍在运行，继续等待心跳")
            // 可能心跳暂时丢失，重置心跳计时器
            lastHeartbeatFromMain = System.currentTimeMillis()
        }
    }

    /**
     * 重启主进程的 WebSocketService
     */
    private fun restartMainProcess() {
        try {
            // 使用 startService 跨进程启动主进程的 WebSocketService
            val intent = Intent().apply {
                component = ComponentName(
                    packageName,
                    "top.leoblog.myauthenticator.service.WebSocketService"
                )
                action = "top.leoblog.myauthenticator.CONNECT"
            }
            startService(intent)
            Log.i(TAG, "✅ 已发送重启主进程服务意图")
        } catch (e: Exception) {
            Log.e(TAG, "重启主进程失败", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "🛡️ 守护进程服务销毁")
        isRunning = false
        heartbeatCheckRunning = false
        handler.removeCallbacks(heartbeatRunnable)
        super.onDestroy()
    }
}