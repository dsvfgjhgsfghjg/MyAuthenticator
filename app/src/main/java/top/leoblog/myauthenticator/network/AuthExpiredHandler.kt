package top.leoblog.myauthenticator.network

import android.util.Log

/**
 * 认证过期全局处理器
 *
 * 当 HTTP 401/403 或 WebSocket 认证过期时触发，
 * 通知当前 Activity 弹出提示窗口并跳转到登录页。
 * 跳转时保留现有的 deviceId/deviceSecret，实现复用。
 */
object AuthExpiredHandler {

    private const val TAG = "AuthExpiredHandler"

    /**
     * 认证过期监听器
     */
    interface OnAuthExpiredListener {
        /**
         * 认证过期回调
         *
         * @param source 来源描述，如 "HTTP 401"、"HTTP 403"、"WebSocket bind_ack error"
         */
        fun onAuthExpired(source: String)
    }

    @Volatile
    private var listener: OnAuthExpiredListener? = null

    /**
     * 注册认证过期监听器（通常在 Activity.onResume 中注册）
     */
    fun registerListener(listener: OnAuthExpiredListener) {
        this.listener = listener
        Log.d(TAG, "认证过期监听器已注册: $listener")
    }

    /**
     * 注销认证过期监听器（通常在 Activity.onPause 中注销）
     */
    fun unregisterListener() {
        this.listener = null
        Log.d(TAG, "认证过期监听器已注销")
    }

    /**
     * 触发认证过期事件
     *
     * 通知当前注册的监听器弹出提醒。
     * 如果没有监听器（Activity 不在前台），则记录日志。
     *
     * @param source 来源描述
     */
    fun notifyAuthExpired(source: String) {
        Log.w(TAG, "认证过期: source=$source")
        val currentListener = listener
        if (currentListener != null) {
            currentListener.onAuthExpired(source)
        } else {
            Log.w(TAG, "没有已注册的监听器（Activity 可能不在前台），忽略过期事件")
        }
    }
}