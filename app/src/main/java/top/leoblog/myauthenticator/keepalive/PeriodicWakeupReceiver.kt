package top.leoblog.myauthenticator.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * 定时唤醒 BroadcastReceiver
 *
 * 由 AlarmManager 定期触发，检查并恢复保活服务。
 * 这是隐藏保活机制的核心——即使所有 Service 都被系统杀死，
 * AlarmManager 的 PendingIntent 仍会触发此 Receiver，然后重启服务。
 *
 * 工作流程：
 * 1. AlarmManager.setExactAndAllowWhileIdle() 定时触发
 * 2. 持有 WakeLock 5 秒
 * 3. 检查 WebSocketService 和守护进程是否存活
 * 4. 如已死则重启
 * 5. 重新设置下一次 Alarm
 */
class PeriodicWakeupReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PeriodicWakeupReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "⏰ 定时唤醒触发")

        // 持有 WakeLock 防止 CPU 休眠
        val wakeLock = acquireWakeLock(context)

        try {
            // 检查并恢复服务
            KeepAliveManager.checkAndRestore(context)

            // 重新设置下一次唤醒
            val keepAliveManager = KeepAliveManager(context)
            val intervalMinutes = keepAliveManager.getWakeupIntervalMinutes()
            val wakeupEnabled = keepAliveManager.isPeriodicWakeupEnabled()

            if (wakeupEnabled) {
                scheduleNextWakeup(context, intervalMinutes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "定时唤醒处理异常", e)
        } finally {
            // 释放 WakeLock
            releaseWakeLock(wakeLock)
        }
    }

    /**
     * 获取唤醒锁
     */
    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MyAuthenticator:PeriodicWakeup"
            )
            wakeLock.acquire(KeepAliveConfig.WAKELOCK_TIMEOUT_MS)
            Log.d(TAG, "WakeLock 已获取 (${KeepAliveConfig.WAKELOCK_TIMEOUT_MS}ms)")
            wakeLock
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 失败", e)
            null
        }
    }

    /**
     * 释放唤醒锁
     */
    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
                Log.d(TAG, "WakeLock 已释放")
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放 WakeLock 失败", e)
        }
    }

    /**
     * 设置定时唤醒 Alarm
     *
     * 使用 setExactAndAllowWhileIdle 确保在 MIUI 中也能准时触发。
     * Android 12+ 限制前台服务启动，但通过 BroadcastReceiver 是可以的。
     */
    fun scheduleNextWakeup(context: Context, intervalMinutes: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return

        val pendingIntent = createWakeupPendingIntent(context)

        // 计算下一次触发时间
        val triggerAtMillis = System.currentTimeMillis() + (intervalMinutes * 60_000L)

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.d(TAG, "📅 下一次唤醒已设置: ${intervalMinutes}分钟后 ($triggerAtMillis)")
        } catch (e: SecurityException) {
            // Android 12+ 可能抛出 SecurityException
            Log.w(TAG, "setExactAndAllowWhileIdle 失败，使用 setAndAllowWhileIdle", e)
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } catch (e2: Exception) {
                Log.e(TAG, "设置 Alarm 失败", e2)
            }
        }
    }

    /**
     * 取消定时唤醒
     */
    fun cancelWakeup(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return

        val pendingIntent = createWakeupPendingIntent(context)
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "⏹ 定时唤醒已取消")
    }

    /**
     * 创建唤醒 PendingIntent
     */
    private fun createWakeupPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PeriodicWakeupReceiver::class.java).apply {
            action = KeepAliveConfig.ACTION_PERIODIC_WAKEUP
        }
        return PendingIntent.getBroadcast(
            context,
            KeepAliveConfig.WAKEUP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}