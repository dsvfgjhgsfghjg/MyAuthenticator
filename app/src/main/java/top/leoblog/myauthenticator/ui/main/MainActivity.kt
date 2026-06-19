package top.leoblog.myauthenticator.ui.main

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.google.android.material.bottomnavigation.BottomNavigationView
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.model.AuthResultMessage
import top.leoblog.myauthenticator.model.ChallengeMessage
import top.leoblog.myauthenticator.network.AppWebSocketClient
import top.leoblog.myauthenticator.service.ChallengeCallback
import top.leoblog.myauthenticator.service.WebSocketService
import top.leoblog.myauthenticator.storage.SecureStorage
import top.leoblog.myauthenticator.ui.challenge.ChallengeDialogFragment
import top.leoblog.myauthenticator.ui.login.LoginActivity

/**
 * 主 Activity — 底部导航栏 + Navigation Component
 *
 * 启动时会检查用户是否已绑定（是否有 JWT Token），
 * 未绑定的用户将被重定向到登录页面。
 *
 * 注册 challengeCallback 以接收 WebSocket 推送的 3 选 1 挑战。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var secureStorage: SecureStorage
    private var currentChallengeDialog: ChallengeDialogFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        secureStorage = SecureStorage(this)

        // 检查是否已绑定，未绑定则跳转到登录页面
        if (!secureStorage.isBound()) {
            navigateToLogin()
            return
        }

        // 自动启动 WebSocket 前台服务（保持长连接，接收认证挑战）
        startWebSocketService()

        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // 将底部导航与 NavController 绑定
        NavigationUI.setupWithNavController(bottomNavigation, navController)
    }

    override fun onResume() {
        super.onResume()
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
                    // 如果弹窗还在，更新弹窗状态
                    currentChallengeDialog?.showResult(result.status, result.reason)
                    currentChallengeDialog = null

                    // 同时 Toast 提示
                    val msg = when (result.status) {
                        "approved" -> "✅ 认证通过"
                        "rejected" -> "❌ 认证被拒绝: ${result.reason ?: "数字选择错误"}"
                        "expired" -> "⏰ 挑战已过期"
                        else -> "认证结果: ${result.status}"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开时取消回调，避免内存泄漏
        WebSocketService.challengeCallback = null
    }

    /**
     * 启动 WebSocket 前台服务
     *
     * 建立与服务器的长连接，完成 bind → DH 握手 → 加密通道，
     * 用于接收 3 选 1 认证挑战。
     */
    private fun startWebSocketService() {
        val intent = Intent(this, WebSocketService::class.java).apply {
            action = WebSocketService.ACTION_CONNECT
            putExtra(WebSocketService.EXTRA_TOKEN, secureStorage.getToken())
            putExtra(WebSocketService.EXTRA_DEVICE_ID, secureStorage.getDeviceId())
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
            // 可以通过队列管理多个挑战，当前简化处理
            return
        }

        val dialog = ChallengeDialogFragment.newInstance(challenge)
        currentChallengeDialog = dialog
        dialog.onNumberSelected = { challengeId, selectedNumber ->
            Log.d(TAG, "用户选择: challengeId=$challengeId, number=$selectedNumber")
            // 通过 WebSocketService 发送响应
            WebSocketService.sendChallengeResponseStatic(challengeId, selectedNumber)
        }
        dialog.show(supportFragmentManager, "challenge_dialog")
    }

    private fun navigateToLogin() {
        val intent = android.content.Intent(this, LoginActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
