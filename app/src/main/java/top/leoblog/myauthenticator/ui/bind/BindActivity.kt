package top.leoblog.myauthenticator.ui.bind

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.databinding.ActivityBindBinding
import top.leoblog.myauthenticator.model.BindPasswordRequest
import top.leoblog.myauthenticator.model.BindQrCodeRequest
import top.leoblog.myauthenticator.network.RetrofitClient
import top.leoblog.myauthenticator.storage.DeviceIdManager
import top.leoblog.myauthenticator.storage.SecureStorage
import top.leoblog.myauthenticator.ui.main.MainActivity

/**
 * 绑定 Activity — 用于首次设备绑定
 *
 * 提供两种绑定方式：
 * 1. 密码绑定（输入用户名 + 密码）
 * 2. 扫码绑定（输入配对码）
 *
 * 修复：使用 getOrCreateDeviceId() 复用已有 deviceId，避免重复注册设备
 */
class BindActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBindBinding
    private lateinit var secureStorage: SecureStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBindBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secureStorage = SecureStorage(this)
        setupViews()
    }

    private fun setupViews() {
        // 密码绑定
        binding.btnBindPassword.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val deviceName = binding.etDeviceName.text.toString().trim()

            if (username.isEmpty()) {
                binding.etUsername.error = "请输入用户名"
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                binding.etPassword.error = "请输入密码"
                return@setOnClickListener
            }

            val name = deviceName.ifEmpty { android.os.Build.MODEL }
            bindWithPassword(username, password, name)
        }

        // 扫码绑定
        binding.btnBindQrCode.setOnClickListener {
            val pairCode = binding.etPairCode.text.toString().trim()
            val password = binding.etPasswordQr.text.toString().trim()
            val deviceName = binding.etDeviceNameQr.text.toString().trim()

            if (pairCode.isEmpty()) {
                binding.etPairCode.error = "请输入配对码"
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                binding.etPasswordQr.error = "请输入密码"
                return@setOnClickListener
            }

            // 需要先登录获取 Token，再扫码绑定
            val name = deviceName.ifEmpty { android.os.Build.MODEL }
            loginAndBindWithQrCode(pairCode, password, name)
        }
    }

    /**
     * 密码绑定
     *
     * 修复：使用 getOrCreateDeviceId() 确保每次绑定使用相同 deviceId
     */
    private fun bindWithPassword(username: String, password: String, deviceName: String) {
        binding.btnBindPassword.isEnabled = false
        binding.tvStatus.text = "正在绑定..."

        lifecycleScope.launch {
            try {
                // 复用已有 deviceId（若有），否则生成新 ID
                val deviceId = secureStorage.getOrCreateDeviceId(this@BindActivity)
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
                        // 保存凭据
                        secureStorage.saveToken(body.data.token)
                        secureStorage.saveDeviceId(body.data.deviceId)
                        secureStorage.saveUsername(username)

                        binding.tvStatus.text = "绑定成功！"

                        Toast.makeText(
                            this@BindActivity,
                            "绑定成功",
                            Toast.LENGTH_SHORT
                        ).show()

                        // 跳转到主界面
                        navigateToMain()
                    } else {
                        binding.tvStatus.text = "绑定失败: ${body?.message ?: "未知错误"}"
                        binding.btnBindPassword.isEnabled = true
                    }
                } else {
                    val errorMsg = when (response.code()) {
                        400 -> "请求参数错误"
                        401 -> "用户名或密码错误"
                        else -> "服务器错误: ${response.code()}"
                    }
                    binding.tvStatus.text = "绑定失败: $errorMsg"
                    binding.btnBindPassword.isEnabled = true
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "网络错误: ${e.message}"
                binding.btnBindPassword.isEnabled = true
            }
        }
    }

    /**
     * 登录后扫码绑定
     *
     * 修复：使用 getOrCreateDeviceId() 确保每次绑定使用相同 deviceId
     */
    private fun loginAndBindWithQrCode(pairCode: String, password: String, deviceName: String) {
        binding.btnBindQrCode.isEnabled = false
        binding.tvStatus.text = "正在绑定..."

        lifecycleScope.launch {
            try {
                // 先获取用户名（假设用户也在输入框中输入了）
                val username = binding.etUsername.text.toString().trim()
                if (username.isEmpty()) {
                    binding.tvStatus.text = "请先在密码绑定区域输入用户名"
                    binding.btnBindQrCode.isEnabled = true
                    return@launch
                }

                // 复用已有 deviceId（若有），否则生成新 ID
                val deviceId = secureStorage.getOrCreateDeviceId(this@BindActivity)
                val bindRequest = BindPasswordRequest(
                    username = username,
                    password = password,
                    deviceId = deviceId,
                    deviceName = deviceName
                )

                val loginResponse = RetrofitClient.apiService.bindWithPassword(bindRequest)
                if (!loginResponse.isSuccessful || loginResponse.body()?.data == null) {
                    binding.tvStatus.text = "登录失败，请检查用户名和密码"
                    binding.btnBindQrCode.isEnabled = true
                    return@launch
                }

                val jwtToken = loginResponse.body()!!.data!!.token

                // 调用扫码绑定 API
                val qrRequest = BindQrCodeRequest(
                    pairCode = pairCode,
                    deviceId = deviceId,
                    deviceName = deviceName
                )

                val qrResponse = RetrofitClient.apiService.bindWithQrCode(
                    "Bearer $jwtToken",
                    qrRequest
                )

                if (qrResponse.isSuccessful) {
                    val body = qrResponse.body()
                    if (body?.code == 200) {
                        secureStorage.saveToken(jwtToken)
                        secureStorage.saveDeviceId(deviceId)
                        secureStorage.saveUsername(username)

                        binding.tvStatus.text = "扫码绑定成功！"
                        Toast.makeText(this@BindActivity, "扫码绑定成功", Toast.LENGTH_SHORT).show()
                        navigateToMain()
                    } else {
                        binding.tvStatus.text = "绑定失败: ${body?.message ?: "配对码无效或已过期"}"
                        binding.btnBindQrCode.isEnabled = true
                    }
                } else {
                    val errorMsg = when (qrResponse.code()) {
                        400 -> "配对码无效或已过期"
                        401 -> "Token 无效"
                        else -> "服务器错误: ${qrResponse.code()}"
                    }
                    binding.tvStatus.text = "绑定失败: $errorMsg"
                    binding.btnBindQrCode.isEnabled = true
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "网络错误: ${e.message}"
                binding.btnBindQrCode.isEnabled = true
            }
        }
    }

    /**
     * 跳转到主界面
     */
    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}