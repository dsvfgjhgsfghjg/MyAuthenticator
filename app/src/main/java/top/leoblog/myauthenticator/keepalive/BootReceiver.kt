package top.leoblog.myauthenticator.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 开机自启 BroadcastReceiver
 *
 * 监听 BOOT_COMPLETED 事件，延迟 10 秒后恢复保活策略。
 * 需要引导 MIUI 用户在自启动管理中允许本应用。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "🚀 收到开机完成广播")

        // 延迟启动，等待系统完成初始化
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                Log.d(TAG, "⏰ 开机延迟结束，恢复保活服务")
                KeepAliveManager.checkAndRestore(context)

                // 重新设置定时唤醒
                val keepAliveManager = KeepAliveManager(context)
                if (keepAliveManager.isPeriodicWakeupEnabled()) {
                    val receiver = PeriodicWakeupReceiver()
                    val intervalMinutes = keepAliveManager.getWakeupIntervalMinutes()
                    receiver.scheduleNextWakeup(context, intervalMinutes)
                    Log.d(TAG, "📅 开机后定时唤醒已重置: ${intervalMinutes}分钟")
                }

                // 启动保活策略
                keepAliveManager.startCurrentMode()
            } catch (e: Exception) {
                Log.e(TAG, "开机恢复保活失败", e)
            }
        }, KeepAliveConfig.BOOT_DELAY_MS)

        Log.i(TAG, "⏳ 将在 ${KeepAliveConfig.BOOT_DELAY_MS / 1000} 秒后恢复保活服务")
    }
}