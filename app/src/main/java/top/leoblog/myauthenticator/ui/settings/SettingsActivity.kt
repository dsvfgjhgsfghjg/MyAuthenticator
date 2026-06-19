package top.leoblog.myauthenticator.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.databinding.ActivitySettingsBinding
import top.leoblog.myauthenticator.model.AuthResultMessage
import top.leoblog.myauthenticator.model.ChallengeMessage
import top.leoblog.myauthenticator.model.UpdateCipherPrefRequest
import top.leoblog.myauthenticator.network.RetrofitClient
import top.leoblog.myauthenticator.service.ChallengeCallback
import top.leoblog.myauthenticator.service.ConnectionCallback
import top.leoblog.myauthenticator.service.WebSocketService
import top.leoblog.myauthenticator.storage.SecureStorage
import top.leoblog.myauthenticator.ui.bind.BindActivity
import top.leoblog.myauthenticator.ui.challenge.ChallengeDialogFragment

/**
 * 设置 Activity — 连接状态、设备信息、操作按钮，
 * 并支持切换设备首选加密算法（AES-256-GCM / SM4-GCM）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var secureStorage: SecureStorage
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secureStorage = SecureStorage(this)

        setupViews()

        // 设置服务回调
        WebSocketService.challengeCallback = object : ChallengeCallback {
            override fun onChallengeReceived(challenge: ChallengeMessage) {
                runOnUiThread { showChallengeDialog(challenge) }
            }

            override fun onAuthResultReceived(result: AuthResultMessage) {
                runOnUiThread { handleAuthResult(result) }
            }
        }

        WebSocketService.connectionCallback = object : ConnectionCallback {
            override fun onConnected() {
                runOnUiThread { updateConnectionStatus(true) }
            }

            override fun onDisconnected() {
                runOnUiThread { updateConnectionStatus(false) }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    binding.tvStatus.text = "错误: $message"
                    updateConnectionStatus(false)
                }
            }
        }

        // 同步当前连接状态
        isConnected = WebSocketService.isConnectedStatic()
        updateConnectionStatus(isConnected)
    }

    private fun setupViews() {
        // Toolbar 返回按钮
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        // 显示设备信息
        binding.tvDeviceId.text = "设备 ID: ${secureStorage.getDeviceId() ?: "未知"}"
        binding.tvUsername.text = "用户: ${secureStorage.getUsername() ?: "未知"}"
        binding.tvStatus.text = if (isConnected) "已连接" else "未连接"

        val currentCipher = secureStorage.getCipherPref()
        binding.tvCipher.text = "加密算法: ${currentCipher ?: "未知"}"

        // 设置加密算法下拉框
        setupCipherSpinner(currentCipher)

        // 切换连接按钮
        binding.btnToggleConnection.setOnClickListener {
            if (isConnected) {
                stopWebSocketService()
            } else {
                startWebSocketService()
            }
        }

        // 解绑按钮
        binding.btnUnbind.setOnClickListener {
            secureStorage.clearAll()
            Toast.makeText(this, "已解绑设备", Toast.LENGTH_SHORT).show()
            navigateToBind()
        }

        // 退出按钮
        binding.btnExit.setOnClickListener {
            stopWebSocketService()
            finishAffinity()
        }
    }

    /**
     * 设置加密算法下拉框
     */
    private fun setupCipherSpinner(currentCipher: String?) {
        val spinner = binding.spinnerCipher

        // 根据当前算法设置选中位置
        val position = when (currentCipher) {
            "AES-256-GCM" -> 0
            "SM4-GCM" -> 1
            else -> 0
        }
        spinner.setSelection(position)

        // 监听选择变更，调用 API 更新服务端
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val selected = resources.getStringArray(R.array.cipher_options)[pos]
                if (selected == secureStorage.getCipherPref()) return

                val deviceId = secureStorage.getDeviceId() ?: return
                val token = secureStorage.getToken() ?: return

                // 先乐观更新本地
                secureStorage.saveCipherPref(selected)
                binding.tvCipher.text = "加密算法: $selected"

                // 同步到服务端
                lifecycleScope.launch {
                    try {
                        val response = RetrofitClient.apiService.updateCipherPref(
                            authorization = "Bearer $token",
                            deviceId = deviceId,
                            request = UpdateCipherPrefRequest(cipherPref = selected)
                        )
                        if (response.isSuccessful && response.body()?.code == 200) {
                            Toast.makeText(this@SettingsActivity,
                                getString(R.string.cipher_switched, selected),
                                Toast.LENGTH_SHORT).show()
                        } else {
                            rollbackCipher(spinner, currentCipher, "服务端拒绝")
                        }
                    } catch (e: Exception) {
                        rollbackCipher(spinner, currentCipher, e.message ?: "网络错误")
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun rollbackCipher(spinner: Spinner, fallback: String?, reason: String) {
        secureStorage.saveCipherPref(fallback ?: "AES-256-GCM")
        binding.tvCipher.text = "加密算法: ${fallback ?: "未知"}"
        spinner.setSelection(if (fallback == "SM4-GCM") 1 else 0)
        Toast.makeText(this@SettingsActivity,
            getString(R.string.cipher_switch_failed, reason),
            Toast.LENGTH_SHORT).show()
    }

    private fun updateConnectionStatus(connected: Boolean) {
        isConnected = connected
        if (connected) {
            binding.tvStatus.text = getString(R.string.status_connected)
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.btnToggleConnection.text = getString(R.string.btn_disconnect)
            binding.ivConnectionIndicator.setImageResource(android.R.drawable.presence_online)
        } else {
            binding.tvStatus.text = getString(R.string.status_disconnected)
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            binding.btnToggleConnection.text = getString(R.string.btn_connect)
            binding.ivConnectionIndicator.setImageResource(android.R.drawable.presence_offline)
        }
    }

    private fun showChallengeDialog(challenge: ChallengeMessage) {
        val existingFragment = supportFragmentManager.findFragmentByTag("challenge_dialog")
        if (existingFragment is DialogFragment) {
            existingFragment.dismiss()
        }

        val dialog = ChallengeDialogFragment.newInstance(challenge)
        dialog.onNumberSelected = { challengeId, selectedNumber ->
            WebSocketService.sendChallengeResponseStatic(challengeId, selectedNumber)
            Toast.makeText(this@SettingsActivity, "已选择: $selectedNumber", Toast.LENGTH_SHORT).show()
        }
        dialog.show(supportFragmentManager, "challenge_dialog")
    }

    private fun handleAuthResult(result: AuthResultMessage) {
        when (result.status) {
            "approved" -> {
                Toast.makeText(this, "✅ 认证通过", Toast.LENGTH_LONG).show()
                binding.tvLastResult.text = "上次结果: 已批准"
            }
            "rejected" -> {
                Toast.makeText(this, "❌ 认证被拒绝: ${result.reason ?: ""}", Toast.LENGTH_LONG).show()
                binding.tvLastResult.text = "上次结果: 已拒绝 (${result.reason ?: ""})"
            }
            "expired" -> {
                Toast.makeText(this, "⏰ 挑战已过期", Toast.LENGTH_LONG).show()
                binding.tvLastResult.text = "上次结果: 已过期"
            }
        }
    }

    private fun startWebSocketService() {
        val intent = android.content.Intent(this, WebSocketService::class.java).apply {
            action = WebSocketService.ACTION_CONNECT
            putExtra(WebSocketService.EXTRA_TOKEN, secureStorage.getToken())
            putExtra(WebSocketService.EXTRA_DEVICE_ID, secureStorage.getDeviceId())
        }
        startService(intent)
    }

    private fun stopWebSocketService() {
        val intent = android.content.Intent(this, WebSocketService::class.java).apply {
            action = WebSocketService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun navigateToBind() {
        val intent = android.content.Intent(this, BindActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finishAffinity()
    }

    override fun onDestroy() {
        WebSocketService.challengeCallback = null
        WebSocketService.connectionCallback = null
        super.onDestroy()
    }
}