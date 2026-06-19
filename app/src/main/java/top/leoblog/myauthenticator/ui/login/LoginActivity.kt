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
import top.leoblog.myauthenticator.storage.SecureStorage
import top.leoblog.myauthenticator.ui.main.MainActivity

/**
 * 登录 Activity — 首次使用的用户输入用户名密码绑定设备
 *
 * 调用 POST /api/auth/app/bind 实现密码绑定，成功后将 JWT Token
 * 存入 EncryptedSharedPreferences，然后跳转到主界面。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var secureStorage: SecureStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secureStorage = SecureStorage(this)
        setupViews()
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
     * 调用密码绑定 API 完成登录 + 设备绑定
     */
    private fun login(username: String, password: String, deviceName: String) {
        binding.btnLogin.isEnabled = false
        binding.tvStatus.text = "正在登录并绑定设备..."
        binding.tvErrorDetail.text = ""

        lifecycleScope.launch {
            try {
                val deviceId = SecureStorage.generateDeviceId(this@LoginActivity)
                val request = BindPasswordRequest(
                    username = username,
                    password = password,
                    deviceId = deviceId,
                    deviceName = deviceName
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