package top.leoblog.myauthenticator.keepalive

import android.app.ActivityManager
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
 * 进程间守护监控器
 *
 * 负责主进程与 :guard 守护进程之间的双向心跳检测。
 * 如果检测到对方进程死亡，立即触发恢复。
 *
 * 通信方式：
 * - 使用 Messenger 实现 IPC（进程安全、简单可靠）
 * - 每 15 秒互发心跳消息
 * - 超过 60 秒未收到心跳判定对方死亡
 */
class DaemonMonitor(private val context: Context) {

    companion object {
        private const val TAG = "DaemonMonitor"

        /** 心跳消息类型：主进程 → 守护进程 */
        const val MSG_HEARTBEAT_FROM_MAIN = 1

        /** 心跳消息类型：守护进程 → 主进程 */
        const val MSG_HEARTBEAT_FROM_GUARD = 2

        /** 心跳消息类型：守护进程通知主进程它还活着 */
        const val MSG_PING = 3

        /** 心跳消息类型：主进程回复守护进程它还活着 */
        const val MSG_PONG = 4

        /** 心跳消息类型：查询对方状态 */
        const val MSG_QUERY_STATUS = 5

        /** 心跳消息类型：状态回复 */
        const val MSG_STATUS_REPLY = 6

        /** 心跳消息类型：守护进程请求检查主进程是否存活 */
        const val MSG_CHECK_MAIN_ALIVE = 7
    }

    /** 守护进程死亡回调 */
    var onGuardDied: (() -> Unit)? = null

    /** 守护进程连接回调 */
    var onGuardConnected: (() -> Unit)? = null

    /** 守护进程断开回调 */
    var onGuardDisconnected: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var guardMessenger: Messenger? = null
    private var isGuardBound = false
    private var lastHeartbeatFromGuard: Long = 0L
    private var heartbeatCheckRunning = false

    /** 主进程的 Messenger（供守护进程发送消息） */
    private val mainMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        handleGuardMessage(msg)
        true
    })

    /** ServiceConnection 用于绑定守护进程 */
    private val guardConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            guardMessenger = Messenger(service)
            isGuardBound = true
            lastHeartbeatFromGuard = System.currentTimeMillis()
            Log.d(TAG, "✅ 守护进程已连接")
            onGuardConnected?.invoke()
            // 发送初始心跳
            sendHeartbeat()
            // 启动心跳检查循环
            startHeartbeatCheck()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "⚠️ 守护进程断开连接")
            guardMessenger = null
            isGuardBound = false
            stopHeartbeatCheck()
            onGuardDisconnected?.invoke()
            // 守护进程意外死亡，触发恢复
            onGuardDied?.invoke()
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.e(TAG, "💀 守护进程绑定死亡")
            guardMessenger = null
            isGuardBound = false
            stopHeartbeatCheck()
            onGuardDied?.invoke()
        }
    }

    /**
     * 启动守护进程监控
     * 通过 bindService 绑定 :guard 进程的 DaemonGuardService
     */
    fun startMonitoring() {
        if (isGuardBound) {
            Log.d(TAG, "守护进程监控已运行，跳过")
            return
        }

        Log.d(TAG, "🚀 启动守护进程监控")
        try {
            val intent = Intent(context, DaemonGuardService::class.java)
            context.bindService(intent, guardConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "绑定守护进程失败", e)
        }
    }

    /**
     * 停止守护进程监控
     */
    fun stopMonitoring() {
        Log.d(TAG, "🛑 停止守护进程监控")
        stopHeartbeatCheck()
        if (isGuardBound) {
            try {
                context.unbindService(guardConnection)
            } catch (e: Exception) {
                Log.e(TAG, "解绑守护进程失败", e)
            }
        }
        guardMessenger = null
        isGuardBound = false
    }

    /**
     * 发送心跳到守护进程
     */
    private fun sendHeartbeat() {
        val messenger = guardMessenger ?: return
        try {
            val msg = Message.obtain(null, MSG_HEARTBEAT_FROM_MAIN)
            msg.replyTo = mainMessenger
            messenger.send(msg)
        } catch (e: RemoteException) {
            Log.w(TAG, "发送心跳到守护进程失败", e)
        }
    }

    /**
     * 处理来自守护进程的消息
     */
    private fun handleGuardMessage(msg: Message) {
        when (msg.what) {
            MSG_HEARTBEAT_FROM_GUARD -> {
                lastHeartbeatFromGuard = System.currentTimeMillis()
                Log.v(TAG, "收到守护进程心跳 ❤️")
            }
            MSG_PING -> {
                // 守护进程 ping 我们，回复 pong
                try {
                    val reply = Message.obtain(null, MSG_PONG)
                    msg.replyTo?.send(reply)
                } catch (e: Exception) {
                    Log.w(TAG, "回复 PONG 失败", e)
                }
            }
            MSG_QUERY_STATUS -> {
                // 守护进程查询状态，回复
                try {
                    val reply = Message.obtain(null, MSG_STATUS_REPLY)
                    reply.obj = "alive"
                    msg.replyTo?.send(reply)
                } catch (e: Exception) {
                    Log.w(TAG, "回复状态失败", e)
                }
            }
        }
    }

    /**
     * 启动心跳检查循环
     * 每 15 秒检查一次是否收到守护进程心跳
     */
    private fun startHeartbeatCheck() {
        if (heartbeatCheckRunning) return
        heartbeatCheckRunning = true
        heartbeatCheckRunnable.run()
    }

    private fun stopHeartbeatCheck() {
        heartbeatCheckRunning = false
        mainHandler.removeCallbacks(heartbeatCheckRunnable)
    }

    private val heartbeatCheckRunnable = object : Runnable {
        override fun run() {
            if (!heartbeatCheckRunning) return

            // 检查上次心跳时间
            val timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatFromGuard
            if (timeSinceLastHeartbeat > KeepAliveConfig.HEARTBEAT_TIMEOUT_MS) {
                Log.w(TAG, "⚠️ 守护进程心跳超时 (${timeSinceLastHeartbeat}ms)，判定死亡")
                onGuardDied?.invoke()
                stopHeartbeatCheck()
                return
            }

            // 发送心跳
            sendHeartbeat()

            // 继续循环
            mainHandler.postDelayed(this, KeepAliveConfig.HEARTBEAT_INTERVAL_MS)
        }
    }

    /**
     * 检查主进程是否存活（供守护进程调用）
     * 实际上是检查 ActivityManager 中是否有自己的进程
     */
    fun isMainProcessAlive(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true // 无法检查时默认存活

        val processes = am.runningAppProcesses ?: return true
        val myPid = android.os.Process.myPid()

        // 检查是否是 :guard 进程
        val isGuard = context.applicationInfo.processName.endsWith(":guard")

        return processes.any { process ->
            if (isGuard) {
                // 守护进程检查主进程：主进程名不包含 :guard
                !process.processName.contains(":guard") &&
                    process.processName.contains(context.packageName) &&
                    process.pid != myPid
            } else {
                // 主进程检查守护进程
                process.processName.endsWith(":guard") &&
                    process.pid != myPid
            }
        }
    }
}