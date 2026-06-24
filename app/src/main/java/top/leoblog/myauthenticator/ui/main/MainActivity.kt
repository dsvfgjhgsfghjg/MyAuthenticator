package top.leoblog.myauthenticator.ui.main

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.model.AuthResultMessage
import top.leoblog.myauthenticator.model.ChallengeMessage
import top.leoblog.myauthenticator.service.ChallengeCallback
import top.leoblog.myauthenticator.service.WebSocketService
import top.leoblog.myauthenticator.storage.DeviceIdManager
import top.leoblog.myauthenticator.storage.SecureStorage
import top.leoblog.myauthenticator.ui.challenge.ChallengeDialogFragment
import top.leoblog.myauthenticator.MyAuthenticatorApp
import top.leoblog.myauthenticator.ui.lock.LockManager
import top.leoblog.myauthenticator.ui.lock.UnlockActivity
import top.leoblog.myauthenticator.ui.login.LoginActivity

/**
 * 主 Activity — 底部导航栏 + Navigation Component
 *
 * 启动时始终显示主视图，不再自动跳转登录页。
 * 如果有 JWT Token 和 deviceSecret，自动启动 WebSocket 前台服务保持长连接。
 * 未登录用户显示主视图，点击"登录"按钮才进入登录/绑定页面。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var secureStorage: SecureStorage

    /**
     * 当前显示的挑战弹窗引用，用于将认证结果传递回弹窗
     */
    private var currentChallengeDialog: ChallengeDialogFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        secureStorage = SecureStorage(this)

        // 确保设备码就绪（首次启动时获取 deviceId + deviceSecret）
        ensureDeviceSecret()

        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // 将底部导航与 NavController 绑定
        NavigationUI.setupWithNavController(bottomNavigation, navController)

        // 如果有 JWT Token + deviceId，自动启动 WebSocket 前台服务（重连）
        if (isLoggedIn()) {
            startWebSocketService()
        }
    }

    /**
     * 确保设备码（deviceId + deviceSecret）已就绪。
     * 如果本地没有，调用服务端 API 获取并存储。
     */
    private fun ensureDeviceSecret() {
        lifecycleScope.launch {
            val ready = DeviceIdManager.ensureDeviceSecret(this@MainActivity, secureStorage)
            if (ready) {
                Log.d(TAG, "设备码就绪")
            } else {
                Log.w(TAG, "设备码获取失败（首次启动时网络不可用？），将在登录时重试")
            }
        }
    }

    /**
     * 检查是否已登录（有 JWT Token 和 deviceId）
     */
    private fun isLoggedIn(): Boolean {
        val token = secureStorage.getToken()
        val deviceId = secureStorage.getDeviceId()
        return !token.isNullOrBlank() && !deviceId.isNullOrBlank()
    }

    override fun onResume() {
        super.onResume()

        // 仅在 app 从后台切回前台时检查锁，内部 Activity 间跳转不触发锁定
        if (MyAuthenticatorApp.checkAndClearForegroundFlag()) {
            val lockManager = LockManager(secureStorage)
            if (lockManager.shouldLock()) {
                Log.d(TAG, "后台超时，重新锁定")
                val intent = UnlockActivity.createIntent(this)
                startActivity(intent)
                finish()
                return
            }
        }

        // 注册挑战回调 — 接收 WebSocket 推送的 3 选 1 挑战
        WebSocketService.challengeCallback = object : ChallengeCallback {
            override fun onChallengeReceived(challenge: ChallengeMessage) {
                Log.d(TAG, "收到推送挑战: id=${challenge.challengeId}, numbers=${challenge.numbers}")
                runOnUiThread {
                    showChallengeDialog(challenge)
                }
            }

            override fun onAuthResultReceived(result: top.leoblog.myauthenticator.model.AuthResultMessage) {
                Log.d(TAG, "认证结果: status=${result.status}, reason=${result.reason}")
                runOnUiThread {
                    // 将结果传递给当前挑战弹窗（如果有），弹窗内部会显示结果并自动关闭
                    if (currentChallengeDialog?.isAdded == true) {
                        currentChallengeDialog?.showResult(result.status, result.reason)
                    } else {
                        // 没有弹窗时降级为 Toast 通知
                        Toast.makeText(this@MainActivity, getAuthResultMessage(result), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // 消费后台期间缓存的挑战（Activity 回到前台时处理）
        val pendingChallenge = WebSocketService.consumePendingChallenge()
        if (pendingChallenge != null) {
            Log.d(TAG, "消费缓存的挑战: id=${pendingChallenge.challengeId}")
            showChallengeDialog(pendingChallenge)
        }

        val pendingResult = WebSocketService.consumePendingAuthResult()
        if (pendingResult != null) {
            Log.d(TAG, "消费缓存的认证结果: status=${pendingResult.status}")
            Toast.makeText(this@MainActivity, getAuthResultMessage(pendingResult), Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开时取消回调，避免内存泄漏
        WebSocketService.challengeCallback = null
    }

    /**
     * 获取认证结果的显示消息
     */
    private fun getAuthResultMessage(result: AuthResultMessage): String {
        return when (result.status) {
            "approved" -> "✅ 认证通过"
            "rejected" -> "❌ 认证被拒绝: ${result.reason ?: "数字选择错误"}"
            "expired" -> "⏰ 挑战已过期"
            else -> "认证结果: ${result.status}"
        }
    }

    /**
     * 启动 WebSocket 前台服务
     *
     * 建立与服务器的长连接，完成 bind → DH 握手 → 加密通道，
     * 用于接收 3 选 1 认证挑战。
     * 携带 deviceSecret 以支持服务端密钥验证。
     */
    private fun startWebSocketService() {
        val intent = Intent(this, WebSocketService::class.java).apply {
            action = WebSocketService.ACTION_CONNECT
            putExtra(WebSocketService.EXTRA_TOKEN, secureStorage.getToken())
            putExtra(WebSocketService.EXTRA_DEVICE_ID, secureStorage.getDeviceId())
            putExtra(WebSocketService.EXTRA_DEVICE_SECRET, secureStorage.getDeviceSecret() ?: "")
        }
        startService(intent)
    }

    /**
     * 显示 3 选 1 挑战弹窗
     */
    private fun showChallengeDialog(challenge: ChallengeMessage) {
        // 检查是否已有挑战弹窗显示
        val existing = supportFragmentManager.findFragmentByTag("challenge_dialog")
        if (existing != null && existing.isAdded) {
            Log.w(TAG, "已有挑战弹窗，跳过新挑战")
            return
        }

        val dialog = ChallengeDialogFragment.newInstance(challenge)
        dialog.onNumberSelected = { challengeId, selectedNumber ->
            Log.d(TAG, "用户选择: challengeId=$challengeId, number=$selectedNumber")
            // 通过 WebSocketService 发送响应
            WebSocketService.sendChallengeResponseStatic(challengeId, selectedNumber)
        }
        // 保存引用，用于将后续 auth_result 传递给弹窗
        currentChallengeDialog = dialog
        // 弹窗关闭时清除引用
        dialog.onDismissListener = {
            currentChallengeDialog = null
        }
        dialog.show(supportFragmentManager, "challenge_dialog")
    }
}
