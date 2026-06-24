package top.leoblog.myauthenticator.keepalive

import android.content.Context
import android.content.Intent
import android.util.Log
import top.leoblog.myauthenticator.service.WebSocketService
import top.leoblog.myauthenticator.storage.SecureStorage

/**
 * 保活策略管理中枢
 *
 * 职责：
 * 1. 管理两种保活模式的启动、停止和切换
 * 2. 管理定时唤醒（隐藏保活机制）
 * 3. 提供服务状态检查与恢复
 * 4. 与 SecureStorage 交互，持久化保活配置
 *
 * 使用方法：
 * ```kotlin
 * val manager = KeepAliveManager(context)
 * manager.switchMode(KeepAliveMode.DUAL_DAEMON)
 * ```
 */
class KeepAliveManager(private val context: Context) {

    companion object {
        private const val TAG = "KeepAliveManager"

        /**
         * 检查并恢复保活服务（供 PeriodicWakeupReceiver 和 BootReceiver 调用）
         *
         * 这种方法不依赖 KeepAliveManager 实例，可以从静态上下文调用。
         */
        fun checkAndRestore(context: Context) {
            val manager = KeepAliveManager(context)

            // 获取当前模式
            val mode = manager.getCurrentMode()

            // 检查 WebSocketService 是否存活
            if (!WebSocketService.isConnectedStatic()) {
                Log.w(TAG, "⚠️ WebSocketService 未运行，尝试重启...")
                manager.restartWebSocketService()
            }

            // 双守护进程模式：检查守护进程是否存活
            if (mode == KeepAliveMode.DUAL_DAEMON) {
                if (!DaemonGuardService.isRunning) {
                    Log.w(TAG, "⚠️ 守护进程未运行，尝试重启...")
                    manager.startDaemonGuard()
                }
            }
        }
    }

    private val secureStorage = SecureStorage(context)
    private var daemonMonitor: DaemonMonitor? = null

    // ==================== 保活模式管理 ====================

    /**
     * 获取当前保活模式
     */
    fun getCurrentMode(): KeepAliveMode {
        val modeValue = secureStorage.getKeepAliveMode()
        return KeepAliveMode.fromValue(modeValue)
    }

    /**
     * 切换保活模式
     *
     * 1. 停止当前模式
     * 2. 保存新模式
     * 3. 启动新模式
     */
    fun switchMode(newMode: KeepAliveMode) {
        Log.i(TAG, "🔄 切换保活模式: ${getCurrentMode().displayName} → ${newMode.displayName}")

        // 停止当前模式
        stopCurrentMode()

        // 保存新模式
        secureStorage.saveKeepAliveMode(newMode.value)

        // 启动新模式
        startMode(newMode)
    }

    /**
     * 启动当前配置的模式
     */
    fun startCurrentMode() {
        val mode = getCurrentMode()
        Log.i(TAG, "🚀 启动保活模式: ${mode.displayName}")
        startMode(mode)
    }

    /**
     * 停止当前模式
     */
    fun stopCurrentMode() {
        val mode = getCurrentMode()
        Log.i(TAG, "🛑 停止保活模式: ${mode.displayName}")

        when (mode) {
            KeepAliveMode.FOREGROUND -> {
                // 前台服务模式：WebSocketService 由 MainActivity 控制生命周期
                // 切换模式时不做 stopService 操作，避免断开 WebSocket
                // 只是停止定时唤醒（如有）
            }
            KeepAliveMode.DUAL_DAEMON -> {
                // 停止守护进程监控
                stopDaemonGuard()
            }
        }

        // 停止定时唤醒（切换后将根据新模式配置重新启动）
        if (isPeriodicWakeupEnabled()) {
            val receiver = PeriodicWakeupReceiver()
            receiver.cancelWakeup(context)
        }
    }

    /**
     * 启动指定模式
     */
    private fun startMode(mode: KeepAliveMode) {
        when (mode) {
            KeepAliveMode.FOREGROUND -> {
                // 前台服务模式：只需确保 WebSocketService 运行（定时唤醒会兜底）
                Log.d(TAG, "前台服务模式已激活")
            }
            KeepAliveMode.DUAL_DAEMON -> {
                // 双守护进程模式：启动守护进程
                startDaemonGuard()
            }
        }

        // 如果启用定时唤醒，设置 Alarm
        if (isPeriodicWakeupEnabled()) {
            val receiver = PeriodicWakeupReceiver()
            val intervalMinutes = getWakeupIntervalMinutes()
            receiver.scheduleNextWakeup(context, intervalMinutes)
            Log.d(TAG, "📅 定时唤醒已启动: ${intervalMinutes}分钟")
        }
    }

    // ==================== 前台服务模式 ====================

    /**
     * 重启 WebSocketService
     */
    private fun restartWebSocketService() {
        try {
            val intent = Intent(context, WebSocketService::class.java).apply {
                action = WebSocketService.ACTION_CONNECT
                putExtra(WebSocketService.EXTRA_TOKEN, secureStorage.getToken())
                putExtra(WebSocketService.EXTRA_DEVICE_ID, secureStorage.getDeviceId())
                putExtra(WebSocketService.EXTRA_DEVICE_SECRET, secureStorage.getDeviceSecret() ?: "")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startService(intent)
            Log.i(TAG, "✅ WebSocketService 重启成功")
        } catch (e: Exception) {
            Log.e(TAG, "重启 WebSocketService 失败", e)
        }
    }

    // ==================== 双守护进程模式 ====================

    /**
     * 启动守护进程
     */
    private fun startDaemonGuard() {
        try {
            // 启动守护进程服务（:guard 进程）
            val intent = Intent(context, DaemonGuardService::class.java)
            context.startService(intent)
            Log.d(TAG, "🛡️ 守护进程启动意图已发送")

            // 创建并启动守护进程监控（绑定 :guard 进程）
            if (daemonMonitor == null) {
                daemonMonitor = DaemonMonitor(context).apply {
                    onGuardDied = {
                        Log.e(TAG, "💀 守护进程死亡回调，尝试重启...")
                        restartDaemonGuard()
                    }
                    onGuardConnected = {
                        Log.i(TAG, "✅ 守护进程连接成功")
                    }
                    onGuardDisconnected = {
                        Log.w(TAG, "⚠️ 守护进程断开连接")
                    }
                }
            }

            // 延迟绑定，等待守护进程启动
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                daemonMonitor?.startMonitoring()
            }, 500)

            Log.i(TAG, "🛡️ 双守护进程模式启动完成")
        } catch (e: Exception) {
            Log.e(TAG, "启动守护进程失败", e)
        }
    }

    /**
     * 停止守护进程
     */
    private fun stopDaemonGuard() {
        try {
            // 停止监控
            daemonMonitor?.stopMonitoring()
            daemonMonitor = null

            // 停止守护进程服务
            val intent = Intent(context, DaemonGuardService::class.java)
            context.stopService(intent)
            Log.d(TAG, "🛡️ 守护进程已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止守护进程失败", e)
        }
    }

    /**
     * 重启守护进程
     */
    private fun restartDaemonGuard() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stopDaemonGuard()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startDaemonGuard()
            }, 500)
        }, KeepAliveConfig.GUARD_RESTART_DELAY_MS)
    }

    // ==================== 定时唤醒管理 ====================

    /**
     * 定时唤醒是否启用
     */
    fun isPeriodicWakeupEnabled(): Boolean {
        return secureStorage.isPeriodicWakeupEnabled()
    }

    /**
     * 设置定时唤醒启用状态
     */
    fun setPeriodicWakeupEnabled(enabled: Boolean) {
        secureStorage.savePeriodicWakeupEnabled(enabled)
        if (enabled) {
            val receiver = PeriodicWakeupReceiver()
            receiver.scheduleNextWakeup(context, getWakeupIntervalMinutes())
            Log.d(TAG, "📅 定时唤醒已启用")
        } else {
            val receiver = PeriodicWakeupReceiver()
            receiver.cancelWakeup(context)
            Log.d(TAG, "📅 定时唤醒已禁用")
        }
    }

    /**
     * 获取唤醒间隔（分钟）
     */
    fun getWakeupIntervalMinutes(): Int {
        return secureStorage.getWakeupIntervalMinutes()
    }

    /**
     * 设置唤醒间隔
     */
    fun setWakeupIntervalMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(
            KeepAliveConfig.MIN_WAKEUP_INTERVAL_MINUTES,
            KeepAliveConfig.MAX_WAKEUP_INTERVAL_MINUTES
        )
        secureStorage.saveWakeupIntervalMinutes(clamped)
        Log.d(TAG, "📅 唤醒间隔已设置为 ${clamped} 分钟")

        // 如果定时唤醒已启用，重新设置 Alarm
        if (isPeriodicWakeupEnabled()) {
            val receiver = PeriodicWakeupReceiver()
            receiver.cancelWakeup(context)
            receiver.scheduleNextWakeup(context, clamped)
        }
    }

    // ==================== MIUI 优化引导 ====================

    /**
     * 检测是否为 MIUI 系统
     */
    fun isMiui(): Boolean {
        return MiuiOptimizer.isMiui()
    }

    /**
     * 获取 MIUI 优化建议
     */
    fun getMiuiOptimizationSuggestions(): List<MiuiOptimizer.OptimizationSuggestion> {
        return MiuiOptimizer.getOptimizationSuggestions(context)
    }

    /**
     * 打开 MIUI 自启动管理
     */
    fun openMiuiAutoStartSetting(): Boolean {
        return MiuiOptimizer.openAutoStartSetting(context)
    }

    /**
     * 打开 MIUI 省电管理
     */
    fun openMiuiBatterySavingSetting(): Boolean {
        return MiuiOptimizer.openBatterySavingSetting(context)
    }
}