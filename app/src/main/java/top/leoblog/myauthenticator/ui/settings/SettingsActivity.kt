package top.leoblog.myauthenticator.ui.settings

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
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
import top.leoblog.myauthenticator.ui.lock.LockManager
import top.leoblog.myauthenticator.ui.lock.UnlockActivity

/**
 * 设置 Activity — 连接状态、设备信息、操作按钮、应用锁配置。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var secureStorage: SecureStorage
    private lateinit var lockManager: LockManager
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secureStorage = SecureStorage(this)
        lockManager = LockManager(secureStorage)

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

        // ---- App Lock 配置 ----
        setupAppLockViews()
    }

    private fun setupAppLockViews() {
        val isLockEnabled = secureStorage.isLockEnabled()
        binding.switchLock.isChecked = isLockEnabled
        binding.tvLockStatus.text = if (isLockEnabled) "已开启" else "已关闭"

        // 更新 PIN / 指纹等选项可见性
        updateLockOptionVisibility(isLockEnabled)

        // 应用锁开关
        binding.switchLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 开启应用锁：需先设置 PIN
                if (!secureStorage.hasPin()) {
                    showSetupPinDialog {
                        secureStorage.setLockEnabled(true)
                        updateLockOptionVisibility(true)
                        binding.switchLock.isChecked = true
                        binding.tvLockStatus.text = "已开启"
                        Toast.makeText(this, R.string.pin_set_success, Toast.LENGTH_SHORT).show()
                    }
                    // 如果取消设置 PIN，开关会被回调恢复
                    binding.switchLock.isChecked = false
                } else {
                    secureStorage.setLockEnabled(true)
                    binding.tvLockStatus.text = "已开启"
                    updateLockOptionVisibility(true)
                }
            } else {
                // 关闭应用锁：需验证当前 PIN
                showVerifyPinDialog {
                    secureStorage.setLockEnabled(false)
                    secureStorage.setBiometricEnabled(false)
                    binding.tvLockStatus.text = "已关闭"
                    updateLockOptionVisibility(false)
                    Toast.makeText(this, "应用锁已关闭", Toast.LENGTH_SHORT).show()
                }
                // 如果取消验证，恢复开关
                binding.switchLock.isChecked = true
            }
        }

        // 修改 PIN
        binding.btnChangePin.setOnClickListener {
            showChangePinDialog()
        }

        // 指纹解锁开关
        binding.switchBiometric.isChecked = secureStorage.isBiometricEnabled()
        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 检查设备是否支持指纹
                val biometricManager = BiometricManager.from(this)
                when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                    BiometricManager.BIOMETRIC_SUCCESS -> {
                        secureStorage.setBiometricEnabled(true)
                    }
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                        binding.switchBiometric.isChecked = false
                        Toast.makeText(this, R.string.lock_biometric_not_available, Toast.LENGTH_SHORT).show()
                    }
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                        binding.switchBiometric.isChecked = false
                        Toast.makeText(this, R.string.lock_biometric_not_enrolled, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        binding.switchBiometric.isChecked = false
                        Toast.makeText(this, R.string.lock_biometric_not_available, Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                secureStorage.setBiometricEnabled(false)
            }
        }

        // 超时设置
        val timeoutSeconds = secureStorage.getLockTimeoutSeconds()
        binding.spinnerTimeout.setSelection(getTimeoutPosition(timeoutSeconds))
        binding.spinnerTimeout.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val seconds = when (pos) {
                    1 -> 30
                    2 -> 60
                    3 -> 300
                    else -> 0
                }
                secureStorage.setLockTimeoutSeconds(seconds)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun getTimeoutPosition(seconds: Int): Int {
        return when (seconds) {
            30 -> 1
            60 -> 2
            300 -> 3
            else -> 0
        }
    }

    private fun updateLockOptionVisibility(lockEnabled: Boolean) {
        val visibility = if (lockEnabled) View.VISIBLE else View.GONE
        binding.lockOptionsGroup.visibility = visibility
        binding.tvLockStatus.visibility = if (lockEnabled) View.VISIBLE else View.GONE

        if (lockEnabled) {
            binding.btnChangePin.text = if (secureStorage.hasPin())
                getString(R.string.lock_change_pin)
            else
                getString(R.string.lock_set_pin)
        }
    }

    /**
     * 设置 PIN 对话框（初次设置）
     */
    private fun showSetupPinDialog(onSuccess: () -> Unit) {
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_pin_setup, null)
        val etNewPin = dialogView.findViewById<TextInputEditText>(R.id.et_new_pin)
        val etConfirmPin = dialogView.findViewById<TextInputEditText>(R.id.et_confirm_pin)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_confirm, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .show().apply {
                // Override positive button to prevent auto-dismiss on validation failure
                getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val newPin = etNewPin.text.toString().trim()
                    val confirmPin = etConfirmPin.text.toString().trim()

                    if (newPin.length < 4) {
                        etNewPin.error = getString(R.string.pin_error_too_short)
                        return@setOnClickListener
                    }

                    if (newPin != confirmPin) {
                        etConfirmPin.error = getString(R.string.pin_error_mismatch)
                        return@setOnClickListener
                    }

                    lockManager.setPin(newPin)
                    onSuccess()
                    dismiss()
                }
            }
    }

    /**
     * 验证 PIN 对话框
     */
    private fun showVerifyPinDialog(onSuccess: () -> Unit) {
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_pin_verify, null)
        val etOldPin = dialogView.findViewById<TextInputEditText>(R.id.et_old_pin)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_dialog_old)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_confirm, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .show().apply {
                getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val oldPin = etOldPin.text.toString().trim()

                    if (!lockManager.verifyPin(oldPin)) {
                        etOldPin.error = getString(R.string.pin_error_invalid)
                        return@setOnClickListener
                    }

                    onSuccess()
                    dismiss()
                }
            }
    }

    /**
     * 修改 PIN 对话框
     */
    private fun showChangePinDialog() {
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_pin_change, null)
        val etOldPin = dialogView.findViewById<TextInputEditText>(R.id.et_old_pin)
        val etNewPin = dialogView.findViewById<TextInputEditText>(R.id.et_new_pin)
        val etConfirmPin = dialogView.findViewById<TextInputEditText>(R.id.et_confirm_pin)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lock_change_pin)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_confirm, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .show().apply {
                getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val oldPin = etOldPin.text.toString().trim()
                    val newPin = etNewPin.text.toString().trim()
                    val confirmPin = etConfirmPin.text.toString().trim()

                    if (!lockManager.verifyPin(oldPin)) {
                        etOldPin.error = getString(R.string.pin_error_invalid)
                        return@setOnClickListener
                    }

                    if (newPin.length < 4) {
                        etNewPin.error = getString(R.string.pin_error_too_short)
                        return@setOnClickListener
                    }

                    if (newPin != confirmPin) {
                        etConfirmPin.error = getString(R.string.pin_error_mismatch)
                        return@setOnClickListener
                    }

                    lockManager.setPin(newPin)
                    Toast.makeText(this@SettingsActivity, R.string.pin_changed_success, Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            }
    }

    // ---- 以下为原有代码，未改动 ---- //

    private fun setupCipherSpinner(currentCipher: String?) {
        val spinner = binding.spinnerCipher

        val position = getCipherPosition(currentCipher)
        spinner.setSelection(position)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val selected = resources.getStringArray(R.array.cipher_options)[pos]
                if (selected == secureStorage.getCipherPref()) return

                val deviceId = secureStorage.getDeviceId() ?: return
                val token = secureStorage.getToken() ?: return

                secureStorage.saveCipherPref(selected)
                binding.tvCipher.text = "加密算法: $selected"

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

    private fun getCipherPosition(cipher: String?): Int {
        return when (cipher) {
            "AES-256-GCM" -> 0
            "AES-192-GCM" -> 1
            "AES-128-GCM" -> 2
            "SM4-GCM" -> 3
            else -> 0
        }
    }

    private fun rollbackCipher(spinner: Spinner, fallback: String?, reason: String) {
        secureStorage.saveCipherPref(fallback ?: "AES-256-GCM")
        binding.tvCipher.text = "加密算法: ${fallback ?: "未知"}"
        spinner.setSelection(getCipherPosition(fallback))
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