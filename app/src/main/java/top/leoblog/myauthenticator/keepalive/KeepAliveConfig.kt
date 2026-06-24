package top.leoblog.myauthenticator.keepalive

/**
 * 保活模式枚举
 */
enum class KeepAliveMode(val value: String, val displayName: String) {
    FOREGROUND("foreground", "前台服务模式（推荐）"),
    DUAL_DAEMON("dual_daemon", "双守护进程模式（激进）");

    companion object {
        fun fromValue(value: String): KeepAliveMode {
            return entries.find { it.value == value } ?: FOREGROUND
        }
    }
}

/**
 * 保活配置常量
 */
object KeepAliveConfig {

    // ==================== 通用配置 ====================

    /** 默认保活模式 */
    const val DEFAULT_KEEPALIVE_MODE = "foreground"

    /** 默认启用定时唤醒 */
    const val DEFAULT_PERIODIC_WAKEUP_ENABLED = true

    /** 默认唤醒间隔（分钟） */
    const val DEFAULT_WAKEUP_INTERVAL_MINUTES = 15

    /** 最短唤醒间隔（分钟） */
    const val MIN_WAKEUP_INTERVAL_MINUTES = 5

    /** 最长唤醒间隔（分钟） */
    const val MAX_WAKEUP_INTERVAL_MINUTES = 30

    // ==================== 前台服务配置 ====================

    /** 前台服务通知 ID */
    const val FOREGROUND_NOTIFICATION_ID = 1001

    /** 前台服务通知渠道 ID */
    const val CHANNEL_ID_FOREGROUND = "channel_keepalive_foreground"

    // ==================== 双守护进程配置 ====================

    /** 守护进程进程名 */
    const val GUARD_PROCESS_NAME = ":guard"

    /** 守护进程心跳间隔（毫秒） */
    const val HEARTBEAT_INTERVAL_MS = 15_000L

    /** 守护进程心跳超时（毫秒）：超过此时间未收到心跳认为对方死亡 */
    const val HEARTBEAT_TIMEOUT_MS = 60_000L

    /** 守护进程重启延迟（毫秒） */
    const val GUARD_RESTART_DELAY_MS = 1_000L

    // ==================== 定时唤醒配置 ====================

    /** 定时唤醒 PendingIntent 请求码 */
    const val WAKEUP_REQUEST_CODE = 2001

    /** 定时唤醒 Action */
    const val ACTION_PERIODIC_WAKEUP = "top.leoblog.myauthenticator.ACTION_PERIODIC_WAKEUP"

    /** 唤醒后持有 WakeLock 时长（毫秒） */
    const val WAKELOCK_TIMEOUT_MS = 5_000L

    // ==================== 开机自启配置 ====================

    /** BOOT_COMPLETED 延迟启动（毫秒） */
    const val BOOT_DELAY_MS = 10_000L

    // ==================== SharedPreferences Key ====================

    const val KEY_KEEPALIVE_MODE = "keepalive_mode"
    const val KEY_PERIODIC_WAKEUP_ENABLED = "periodic_wakeup_enabled"
    const val KEY_WAKEUP_INTERVAL_MINUTES = "wakeup_interval_minutes"
}