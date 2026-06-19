package top.leoblog.myauthenticator.storage

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * 设备 ID 管理器 — 生成稳定唯一的设备特征码
 *
 * 对应 APP_DEVICE_FINGERPRINT_GUIDE.md 规范：
 * - 首次安装时生成，永久存储（SharedPreferences）
 * - 格式: ANDROID_MD5(ANDROID_ID+包名)前12位_随机8位
 * - 同一设备、同一用户不变，防止重复设备记录
 */
object DeviceIdManager {

    private const val PREF_NAME = "device_id_prefs"
    private const val KEY_DEVICE_ID = "stable_device_id"

    /**
     * 获取稳定的设备 ID
     *
     * 优先从 SharedPreferences 读取已保存的 ID。
     * 如果不存在（首次安装），则生成并持久化。
     *
     * 返回格式示例: "ANDROID_a3f8b2c1d4e5_5237ba0e"
     */
    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 1. 优先读取已存储的 deviceId
        val existingId = prefs.getString(KEY_DEVICE_ID, null)
        if (!existingId.isNullOrBlank()) {
            return existingId
        }

        // 2. 首次安装：生成新的 deviceId
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

        // 3. 持久化存储（关键！）
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()

        return deviceId
    }

    /**
     * 获取设备名称（用于显示）
     *
     * 返回格式示例: "Pixel 7 Pro" 或 "Samsung Galaxy S24"
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