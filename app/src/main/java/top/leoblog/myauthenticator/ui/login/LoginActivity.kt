package top.leoblog.myauthenticator.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.databinding.ActivityLoginBinding
import top.leoblog.myauthenticator.model.BindPasswordRequest
import top.leoblog.myauthenticator.network.RetrofitClient
import top.leoblog.myauthenticator.storage.DeviceIdManager
import top.leoblog.myauthenticator.storage.SecureStorage
import top.leoblog.myauthenticator.ui.main.MainActivity

/**
 * 登录 Activity — 首次使用的用户输入用户名密码绑定设备
 *
 * 调用 POST /api/auth/app/bind 实现密码绑定，成功后将 JWT Token
 * 存入 EncryptedSharedPreferences，然后跳转到主界面。
 *
 * 也支持认证过期后重新登录的场景：通过 intent extra "extra_device_id"
 * 传入已有的 deviceId，复用现有设备信息。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"

        /**
         * Intent extra 常量 — 传入已有的 deviceId（认证过期后重新登录时使用）
         */
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var secureStorage: SecureStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secureStorage = SecureStorage(this)
        setupViews()
        checkExistingDevice()
    }

    /**
     * 检查是否有已存在的设备信息（认证过期后重新登录的场景）
     * 如果已有 deviceId 和 deviceSecret，隐藏设备码状态提示，
     * 因为这些信息是复用的，不需要重新获取。
     */
    private fun checkExistingDevice() {
        val existingDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (existingDeviceId != null) {
            binding.tvStatus.text = "检测到已有设备，直接登录即可复用当前设备"
            binding.tvStatus.setTextColor(
                resources.getColor(android.R.color.holo_green_dark, theme)
            )
        }
    }

    private fun setupViews() {
        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val deviceName = binding.etDeviceName.text.toString().trim()

            // 表单校验
            if (username.isEmpty()) {
                binding.tilUsername.error = "请输入用户名"
                return@setOnClickListener
            } else {
                binding.tilUsername.error = null
            }
            if (password.isEmpty()) {
                binding.tilPassword.error = "请输入密码"
                return@setOnClickListener
            } else {
                binding.tilPassword.error = null
            }

            val name = deviceName.ifEmpty { android.os.Build.MODEL }
            login(username, password, name)
        }
    }

    /**
     * 确保设备码就绪，若本地没有则调用服务端 API 获取
     *
     * 如果是认证过期后重新登录（intent 携带了 EXTRA_DEVICE_ID），
     * 跳过设备码获取，直接复用现有的 deviceId 和 deviceSecret。
     *
     * @return true 表示设备码已就绪，false 表示获取失败
     */
    private suspend fun ensureDeviceSecretReady(): Boolean {
        // 如果是认证过期后重新登录，检查 intent 中是否携带了已有的 deviceId
        val existingDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (existingDeviceId != null) {
            val existingSecret = secureStorage.getDeviceSecret()
            if (!existingSecret.isNullOrBlank()) {
                // 复用已有的 deviceId 和 deviceSecret，不需要重新获取
                return true
            }
        }

        // 先检查本地是否已有 deviceSecret
        val existingSecret = secureStorage.getDeviceSecret()
        if (!existingSecret.isNullOrBlank()) {
            return true
        }
        // 本地没有，调用 API 获取
        return DeviceIdManager.ensureDeviceSecret(this@LoginActivity, secureStorage)
    }

    private fun login(username: String, password: String, deviceName: String) {
        binding.btnLogin.isEnabled = false
        binding.tvStatus.text = "正在登录并绑定设备..."
        binding.tvErrorDetail.text = ""

        lifecycleScope.launch {
            try {
                // 先确保设备码就绪
                val secretReady = ensureDeviceSecretReady()
                if (!secretReady) {
                    binding.tvStatus.text = "获取设备码失败"
                    binding.tvErrorDetail.text = "无法获取设备码，请检查网络连接"
                    binding.btnLogin.isEnabled = true
                    return@launch
                }

                // 优先使用 intent 传入的已有 deviceId（认证过期后重新登录）
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                    ?: secureStorage.getDeviceId()
                    ?: SecureStorage.generateDeviceId(this@LoginActivity)

                val deviceSecret = secureStorage.getDeviceSecret() ?: ""
                val request = BindPasswordRequest(
                    username = username,
                    password = password,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    deviceSecret = deviceSecret
                )

                val response = RetrofitClient.apiService.bindWithPassword(request)

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.code == 200 && body.data != null) {
                        // 保存凭据到安全存储
                        secureStorage.saveToken(body.data.token)
                        secureStorage.saveDeviceId(body.data.deviceId)
                        secureStorage.saveUsername(username)

                        binding.tvStatus.text = "登录成功！"
                        Toast.makeText(
                            this@LoginActivity,
                            "登录成功",
                            Toast.LENGTH_SHORT
                        ).show()

                        // 跳转到主界面
                        navigateToMain()
                    } else {
                        // API 返回业务错误
                        binding.tvStatus.text = "登录失败"
                        binding.tvErrorDetail.text = body?.message ?: "未知错误"
                        binding.btnLogin.isEnabled = true
                    }
                } else {
                    // HTTP 错误
                    val errorDetail = when (response.code()) {
                        400 -> "用户名或密码错误"
                        401 -> "用户名或密码错误"
                        500 -> "服务器内部错误"
                        else -> "服务器错误: ${response.code()}"
                    }
                    binding.tvStatus.text = "登录失败"
                    binding.tvErrorDetail.text = errorDetail
                    binding.btnLogin.isEnabled = true
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "网络错误"
                binding.tvErrorDetail.text = e.message ?: "请检查网络连接"
                binding.btnLogin.isEnabled = true
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}