package top.leoblog.myauthenticator.storage

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.leoblog.myauthenticator.model.DeviceSecretResponse
import top.leoblog.myauthenticator.network.RetrofitClient
import java.security.MessageDigest
import java.util.UUID

/**
 * 设备码（Device Secret）管理器
 *
 * 对应 APP_DEVICE_SECRET_GUIDE.md 规范：
 * 1. 设备码由服务端生成（deviceId 格式为 svr_<UUID>）
 * 2. deviceSecret 为服务端下发的 32 字节随机密钥（64 位 hex 字符串）
 * 3. 本地持久化存储，App 重启后复用
 * 4. 如果本地存储丢失（首次安装 / 清除数据），调用 API 获取新设备码
 */
object DeviceIdManager {

    private const val TAG = "DeviceIdManager"
    private const val PREF_NAME = "device_id_prefs"
    private const val KEY_DEVICE_ID = "stable_device_id"

    /**
     * 确保本地有设备码（deviceId + deviceSecret）。
     * 如果没有，调用 POST /api/auth/app/device-secret 获取并存储。
     *
     * @param context Context
     * @param secureStorage SecureStorage 实例（用于存储 deviceSecret）
     * @return true 表示设备码已就绪，false 表示获取失败
     */
    suspend fun ensureDeviceSecret(context: Context, secureStorage: SecureStorage): Boolean {
        // 1. 检查本地是否已有 deviceSecret
        val existingSecret = secureStorage.getDeviceSecret()
        if (!existingSecret.isNullOrBlank()) {
            Log.d(TAG, "本地已有 deviceSecret，跳过获取")
            // 如果 deviceId 在 SecureStorage 中不存在但 DeviceSecretManager 中有，同步一下
            if (secureStorage.getDeviceId() == null) {
                secureStorage.saveDeviceId(getOrCreateDeviceId(context))
            }
            return true
        }

        // 2. 本地没有，调用服务端 API 获取
        return withContext(Dispatchers.IO) {
            try {
                val response = RetrofitClient.apiService.getDeviceSecret()
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.code == 200 && body.data != null) {
                        val data: DeviceSecretResponse = body.data
                        // 存储服务端下发的 deviceId（svr_<UUID> 格式）
                        secureStorage.saveDeviceId(data.deviceId)
                        // 存储 deviceSecret
                        secureStorage.saveDeviceSecret(data.deviceSecret)
                        // 存储 hint（用于 UI 展示）
                        secureStorage.saveDeviceSecretHint(data.hint)
                        // 同步到本地持久化（旧兼容存储）
                        saveDeviceIdCompat(context, data.deviceId)
                        Log.d(TAG, "✅ 设备码获取成功: deviceId=${data.deviceId}, hint=${data.hint}")
                        true
                    } else {
                        Log.e(TAG, "设备码获取失败: code=${body?.code}, message=${body?.message}")
                        false
                    }
                } else {
                    Log.e(TAG, "设备码获取 HTTP 失败: ${response.code()}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "设备码获取异常: ${e.message}")
                false
            }
        }
    }

    /**
     * 获取设备码是否已就绪
     */
    fun hasDeviceSecret(secureStorage: SecureStorage): Boolean {
        return !secureStorage.getDeviceSecret().isNullOrBlank()
            && !secureStorage.getDeviceId().isNullOrBlank()
    }

    /**
     * 获取稳定的设备 ID（客户端生成，保留兼容）
     *
     * 如果服务端 deviceId 已存在（svr_ 前缀），优先返回服务端的。
     * 否则使用旧方案生成客户端 ID。
     *
     * 返回格式示例: "svr_a1b2c3d4e5f6..." 或 "ANDROID_a3f8b2c1d4e5_5237ba0e"
     */
    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 1. 优先读取已存储的 deviceId
        val existingId = prefs.getString(KEY_DEVICE_ID, null)
        if (!existingId.isNullOrBlank()) {
            return existingId
        }

        // 2. 首次安装：生成新的 deviceId（客户端生成方案，兼容旧版）
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"

        val packageName = context.packageName

        // MD5(ANDROID_ID + 包名) 取前 12 位
        val hash = md5(androidId + packageName).substring(0, 12)

        // 随机后缀 8 位
        val randomSuffix = UUID.randomUUID().toString()
            .replace("-", "")
            .substring(0, 8)

        val deviceId = "ANDROID_${hash}_${randomSuffix}"

        // 3. 持久化存储
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()

        return deviceId
    }

    /**
     * 保存服务端下发的 deviceId 到兼容存储
     */
    private fun saveDeviceIdCompat(context: Context, deviceId: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    /**
     * 获取设备名称（用于显示）
     */
    fun getDeviceName(): String {
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    /**
     * 获取设备型号（简写）
     */
    fun getDeviceModel(): String {
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    }

    /**
     * 获取操作系统版本信息
     */
    fun getOsVersion(): String {
        return "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}