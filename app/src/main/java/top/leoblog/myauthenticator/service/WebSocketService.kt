package top.leoblog.myauthenticator.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.leoblog.myauthenticator.MyAuthenticatorApp
import top.leoblog.myauthenticator.model.AuthResultMessage
import top.leoblog.myauthenticator.model.ChallengeMessage
import top.leoblog.myauthenticator.network.AppWebSocketClient
import top.leoblog.myauthenticator.network.BugReportManager
import top.leoblog.myauthenticator.network.RetrofitClient
import top.leoblog.myauthenticator.storage.SecureStorage
import top.leoblog.myauthenticator.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WebSocket 挑战回调接口
 */
interface ChallengeCallback {
    fun onChallengeReceived(challenge: ChallengeMessage)
    fun onAuthResultReceived(result: AuthResultMessage)
}

/**
 * WebSocket 连接状态回调接口
 */
interface ConnectionCallback {
    fun onConnected()
    fun onDisconnected()
    fun onError(message: String)
}

/**
 * 握手状态回调接口（供调试用）
 */
interface HandshakeCallback {
    fun onHandshakeStateChanged(state: AppWebSocketClient.HandshakeState)
    fun onReconnectCountChanged(count: Int)
}

/**
 * WebSocket 前台服务
 *
 * 保持 WebSocket 长连接，接收认证挑战
 *
 * 修复：增加指数退避自动重连 + 快速断连检测 + 异常重连自动上报
 */
class WebSocketService : Service() {

    companion object {
        private const val TAG = "WebSocketService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_CONNECT = "top.leoblog.myauthenticator.CONNECT"
        const val ACTION_DISCONNECT = "top.leoblog.myauthenticator.DISCONNECT"
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_DEVICE_SECRET = "extra_device_secret"

        // 重连参数
        private const val RECONNECT_MIN_DELAY_MS = 1_000L     // 1 秒
        private const val RECONNECT_MAX_DELAY_MS = 30_000L    // 30 秒
        private const val RECONNECT_MULTIPLIER = 2            // 指数退避倍数

        // 连接稳定性常量
        private const val STABLE_CONNECTION_THRESHOLD_MS = 15_000L // 15 秒
        private const val QUICK_DISCONNECT_WINDOW_MS = 10_000L
        private const val QUICK_DISCONNECT_BACKOFF_MULTIPLIER = 4

        // 异常重连自动上报阈值
        private const val ABNORMAL_RECONNECT_THRESHOLD = 3

        // WebSocket 客户端实例（供 Activity 直接调用）
        private var webSocketClient: AppWebSocketClient? = null

        // 挑战回调接口
        var challengeCallback: ChallengeCallback? = null
        var connectionCallback: ConnectionCallback? = null

        // 握手状态回调（调试用）
        var handshakeCallback: HandshakeCallback? = null

        // 上次断连追踪（供调试页面使用）
        @Volatile
        var lastDisconnectTime: String? = null
            private set
        @Volatile
        var lastDisconnectReason: String? = null
            private set
        @Volatile
        var lastDisconnectTimestamp: Long = 0L
            private set

        /**
         * 获取当前握手状态
         */
        fun getCurrentHandshakeState(): AppWebSocketClient.HandshakeState {
            return webSocketClient?.handshakeState ?: AppWebSocketClient.HandshakeState.DISCONNECTED
        }

        /**
         * 获取重连次数
         */
        fun getReconnectCount(): Int {
            return webSocketClient?.getReconnectCount() ?: 0
        }

        /**
         * 静态方法：发送挑战响应
         * 供 Activity/Fragment 直接调用
         */
        fun sendChallengeResponseStatic(challengeId: String, selectedNumber: Int) {
            webSocketClient?.sendChallengeResponse(challengeId, selectedNumber)
        }

        /**
         * 静态方法：获取当前连接状态
         */
        fun isConnectedStatic(): Boolean {
            return webSocketClient?.isConnected() ?: false
        }
    }

    private lateinit var secureStorage: SecureStorage
    private var savedToken: String? = null
    private var savedDeviceId: String? = null
    private var savedDeviceSecret: String? = null

    // 重连控制
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectDelayMs = RECONNECT_MIN_DELAY_MS
    private var shouldReconnect = false
    private val reconnectRunnable = Runnable { performReconnect() }

    // 连接稳定性追踪：记录连接建立的时间戳，用于判断是否应该重置退避
    private var connectionEstablishedTime: Long = 0L
    private var lastDhReadyTime: Long = 0L
    private var quickDisconnectCount = 0

    // 协程作用域（用于 Bug 上报等异步操作）
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 标志：是否已经上报过本次异常重连（避免重复上报）
    private var reportedAbnormalReconnect = false

    override fun onCreate() {
        super.onCreate()
        secureStorage = SecureStorage(this)
        Log.d(TAG, "WebSocket 服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: secureStorage.getToken()
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: secureStorage.getDeviceId()
                val deviceSecret = intent.getStringExtra(EXTRA_DEVICE_SECRET) ?: secureStorage.getDeviceSecret() ?: ""

                if (token != null && deviceId != null) {
                    savedToken = token
                    savedDeviceId = deviceId
                    savedDeviceSecret = deviceSecret
                    startForeground(NOTIFICATION_ID, createNotification("正在连接..."))
                    connectWebSocket(token, deviceId, deviceSecret)
                } else {
                    Log.e(TAG, "缺少 Token 或 DeviceId")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                shouldReconnect = false
                cancelReconnect()
                disconnectWebSocket()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * 连接 WebSocket
     *
     * @param token JWT Token
     * @param deviceId 设备 ID（svr_<UUID> 或客户端生成 ID）
     * @param deviceSecret 设备密钥（服务端下发，可为空字符串兼容旧版）
     */
    private fun connectWebSocket(token: String, deviceId: String, deviceSecret: String = "") {
        webSocketClient?.disconnect()
        cancelReconnect()
        shouldReconnect = true

        val client = AppWebSocketClient(
            token = token,
            deviceId = deviceId,
            deviceSecret = deviceSecret,
            listener = object : AppWebSocketClient.WebSocketListenerCallback {
                override fun onConnected() {
                    connectionEstablishedTime = System.currentTimeMillis()
                    updateNotification("已连接")
                    connectionCallback?.onConnected()
                    // 不再无条件重置退避计数器，避免重连风暴。
                    // 退避重置推迟到连接稳定持续一段时间后（在 onDhReady 中判断）
                }

                override fun onBindAck(userId: Int) {
                    Log.d(TAG, "绑定确认, userId=$userId")
                    secureStorage.saveUserId(userId)
                }

                override fun onDhReady(cipher: String) {
                    lastDhReadyTime = System.currentTimeMillis()
                    Log.d(TAG, "加密通道已就绪: $cipher")
                    secureStorage.saveCipherPref(cipher)

                    // DH_READY 表示加密通道就绪，此时检查连接持续时间
                    // 如果连接已经稳定持续超过阈值，则重置退避计数器
                    // 如果连接经常快速断连，则保持当前退避值避免重连风暴
                    val connectionDuration = lastDhReadyTime - connectionEstablishedTime
                    if (connectionDuration >= STABLE_CONNECTION_THRESHOLD_MS) {
                        // 连接稳定，重置退避和快速断连计数
                        reconnectDelayMs = RECONNECT_MIN_DELAY_MS
                        quickDisconnectCount = 0
                        reportedAbnormalReconnect = false
                        Log.d(TAG, "连接已稳定 ${connectionDuration}ms，重置退避计数器")
                    } else {
                        // 快速完成握手，可能即将断连，维持当前退避值
                        Log.d(TAG, "连接持续时间较短 (${connectionDuration}ms)，保持退避延迟=${reconnectDelayMs}ms")
                    }

                    // 检查是否需要自动上报异常重连
                    val reconnectCount = webSocketClient?.getReconnectCount() ?: 0
                    if (reconnectCount > ABNORMAL_RECONNECT_THRESHOLD && !reportedAbnormalReconnect) {
                        reportedAbnormalReconnect = true
                        Log.w(TAG, "检测到异常重连（已重连 $reconnectCount 次），自动上报日志")
                        autoSubmitBugReport(reconnectCount)
                    }
                }

                override fun onChallenge(challenge: ChallengeMessage) {
                    Log.d(TAG, "收到挑战: ${challenge.challengeId}")
                    updateNotification("新的认证请求")
                    challengeCallback?.onChallengeReceived(challenge)
                }

                override fun onAuthResult(result: AuthResultMessage) {
                    Log.d(TAG, "认证结果: ${result.status}")
                    updateNotification("已连接")
                    challengeCallback?.onAuthResultReceived(result)
                }

                override fun onError(message: String) {
                    Log.e(TAG, "错误: $message")
                    updateNotification("连接异常: $message")
                    connectionCallback?.onError(message)
                }

                override fun onDisconnected(reason: String) {
                    Log.d(TAG, "断开连接: $reason")
                    updateNotification("已断开")
                    connectionCallback?.onDisconnected()

                    // 记录断连信息（供调试页面使用）
                    val now = System.currentTimeMillis()
                    lastDisconnectTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                    lastDisconnectReason = reason
                    lastDisconnectTimestamp = now

                    // 自动重连
                    scheduleReconnect()
                }

                override fun onConnectionFailed(throwable: Throwable) {
                    Log.e(TAG, "连接失败", throwable)
                    updateNotification("连接失败")
                    connectionCallback?.onError(throwable.message ?: "连接失败")

                    // 记录断连信息
                    val now = System.currentTimeMillis()
                    lastDisconnectTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                    lastDisconnectReason = "连接失败: ${throwable.message}"
                    lastDisconnectTimestamp = now

                    // 自动重连
                    scheduleReconnect()
                }

                override fun onHandshakeStateChanged(state: AppWebSocketClient.HandshakeState) {
                    handshakeCallback?.onHandshakeStateChanged(state)
                }
            }
        )

        webSocketClient = client
        client.connect()
    }

    /**
     * 安排自动重连
     *
     * 指数退避：1s → 2s → 4s → 8s → ... → 30s 上限
     *
     * 修复：当检测到快速断连模式（连接建立后很短时间内断开），
     * 应用额外退避倍数，防止重连风暴。
     */
    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        cancelReconnect()

        // 检测快速断连：如果从连接建立到断开小于阈值，计数+1
        val now = System.currentTimeMillis()
        val timeSinceConnected = now - connectionEstablishedTime
        val timeSinceDhReady = now - lastDhReadyTime

        if (connectionEstablishedTime > 0 && timeSinceConnected < QUICK_DISCONNECT_WINDOW_MS) {
            quickDisconnectCount++
            Log.w(TAG, "检测到快速断连 #$quickDisconnectCount（持续 ${timeSinceConnected}ms）")
        } else if (lastDhReadyTime > 0 && timeSinceDhReady < QUICK_DISCONNECT_WINDOW_MS) {
            quickDisconnectCount++
            Log.w(TAG, "检测到快速断连 #$quickDisconnectCount（DH_READY 后 ${timeSinceDhReady}ms）")
        } else {
            // 正常断连，重置计数
            quickDisconnectCount = 0
        }

        // 快速断连时应用额外退避
        val effectiveDelay = if (quickDisconnectCount >= 3) {
            // 连续 3 次以上快速断连，使用更激进的退避
            val boostedDelay = (reconnectDelayMs * QUICK_DISCONNECT_BACKOFF_MULTIPLIER)
                .coerceAtMost(RECONNECT_MAX_DELAY_MS)
            Log.w(TAG, "连续 $quickDisconnectCount 次快速断连，退避倍增至 ${boostedDelay}ms")
            boostedDelay
        } else {
            reconnectDelayMs
        }

        Log.d(TAG, "⏰ ${effectiveDelay}ms 后尝试重连...")
        mainHandler.postDelayed(reconnectRunnable, effectiveDelay)

        // 指数退避，但不超过上限（使用原始退避值）
        reconnectDelayMs = (reconnectDelayMs * RECONNECT_MULTIPLIER)
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)

        // 重置连接时间戳，避免重复检测
        connectionEstablishedTime = 0L
    }

    /**
     * 取消待执行的重连
     */
    private fun cancelReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
    }

    /**
     * 执行重连
     */
    private fun performReconnect() {
        if (!shouldReconnect) return
        Log.d(TAG, "🔄 正在重连... (已重连 ${webSocketClient?.getReconnectCount() ?: 0} 次)")

        val token = savedToken ?: secureStorage.getToken()
        val deviceId = savedDeviceId ?: secureStorage.getDeviceId()
        val deviceSecret = savedDeviceSecret ?: secureStorage.getDeviceSecret() ?: ""

        if (token != null && deviceId != null) {
            webSocketClient?.incrementReconnectCount()
            handshakeCallback?.onReconnectCountChanged(webSocketClient?.getReconnectCount() ?: 0)
            connectWebSocket(token, deviceId, deviceSecret)
        } else {
            Log.e(TAG, "重连失败: Token 或 DeviceId 为空")
        }
    }

    /**
     * 异常重连自动上报
     *
     * 当检测到短时间内重连次数超过阈值时，自动收集日志并上报给后端。
     * 对应 WebSocket重连风暴_前端反馈文档.md 2.3 节。
     */
    private fun autoSubmitBugReport(reconnectCount: Int) {
        val token = secureStorage.getToken() ?: return
        if (token.isEmpty()) return

        // 构建日志内容：当前连接状态 + 重连历史
        val logText = buildString {
            appendLine("=== WebSocket 异常重连自动上报 ===")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("已重连次数: $reconnectCount")
            appendLine("快速断连计数: $quickDisconnectCount")
            appendLine("当前退避延迟: ${reconnectDelayMs}ms")
            appendLine("上次断开时间: ${lastDisconnectTime ?: "N/A"}")
            appendLine("上次断开原因: ${lastDisconnectReason ?: "N/A"}")
            appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        }

        serviceScope.launch {
            try {
                val bugReportManager = BugReportManager.getInstance(
                    this@WebSocketService,
                    RetrofitClient.apiService
                )
                val result = bugReportManager.submitLog(
                    logText = logText,
                    summary = "WebSocket 异常重连（已重连 ${reconnectCount} 次）"
                )
                result.onSuccess {
                    Log.i(TAG, "异常重连日志自动上报成功")
                }.onFailure { error ->
                    Log.w(TAG, "异常重连日志自动上报失败: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "异常重连日志自动上报异常", e)
            }
        }
    }

    /**
     * 断开 WebSocket
     */
    private fun disconnectWebSocket() {
        webSocketClient?.disconnect()
        webSocketClient = null
    }

    // ==================== 通知管理 ====================

    /**
     * 创建通知
     */
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, MyAuthenticatorApp.CHANNEL_ID_WEBSOCKET)
            .setContentTitle("双因素认证")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 更新通知状态
     */
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shouldReconnect = false
        cancelReconnect()
        disconnectWebSocket()
        Log.d(TAG, "WebSocket 服务已销毁")
        super.onDestroy()
    }
}