package top.leoblog.myauthenticator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import top.leoblog.myauthenticator.crypto.Sm4GcmCrypto

/**
 * Application 类
 *
 * 负责全局初始化：
 * 1. 注册 Bouncy Castle Provider（国密支持）
 * 2. 创建通知渠道
 */
class MyAuthenticatorApp : Application() {

    companion object {
        const val CHANNEL_ID_WEBSOCKET = "channel_websocket"
        const val CHANNEL_ID_CHALLENGE = "channel_challenge"
        lateinit var instance: MyAuthenticatorApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化 Bouncy Castle（SM4 支持）
        Sm4GcmCrypto.initBouncyCastle()

        // 创建通知渠道
        createNotificationChannels()
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
