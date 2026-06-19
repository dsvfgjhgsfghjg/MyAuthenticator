package top.leoblog.myauthenticator.storage

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * 安全存储工具类
 *
 * 使用 EncryptedSharedPreferences 安全存储敏感数据
 *
 * deviceId 已迁移到 DeviceIdManager（MD5 哈希方案），
 * 此处保留方法用于读取旧数据兼容。
 */
class SecureStorage(context: Context) {

    companion object {
        private const val PREFS_NAME = "app_auth_prefs"
        private const val KEY_JWT_TOKEN = "jwt_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CIPHER_PREF = "cipher_pref"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"

        /**
         * 生成设备唯一 ID（旧方案，保留兼容）
         * 新代码应使用 DeviceIdManager.getOrCreateDeviceId()
         */
        fun generateDeviceId(context: Context): String {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            return "ANDROID_${androidId}_${UUID.randomUUID().toString().take(8)}"
        }
    }

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ---- JWT Token ----

    fun saveToken(token: String) {
        sharedPreferences.edit().putString(KEY_JWT_TOKEN, token).apply()
    }

    fun getToken(): String? {
        return sharedPreferences.getString(KEY_JWT_TOKEN, null)
    }

    fun clearToken() {
        sharedPreferences.edit().remove(KEY_JWT_TOKEN).apply()
    }

    // ---- Device ID ----

    /**
     * 获取已有 DeviceId（从新 DeviceIdManager 或旧 SecureStorage 兼容读取）
     */
    fun getOrCreateDeviceId(context: Context): String {
        // 优先使用新方案
        return DeviceIdManager.getOrCreateDeviceId(context)
    }

    fun saveDeviceId(deviceId: String) {
        sharedPreferences.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    fun getDeviceId(): String? {
        // 读取旧 SharedPreferences 中的 deviceId（兼容旧数据）
        return sharedPreferences.getString(KEY_DEVICE_ID, null)
    }

    // ---- Cipher Preference ----

    fun saveCipherPref(cipher: String) {
        sharedPreferences.edit().putString(KEY_CIPHER_PREF, cipher).apply()
    }

    fun getCipherPref(): String? {
        return sharedPreferences.getString(KEY_CIPHER_PREF, null)
    }

    // ---- User ID ----

    fun saveUserId(userId: Int) {
        sharedPreferences.edit().putInt(KEY_USER_ID, userId).apply()
    }

    fun getUserId(): Int {
        return sharedPreferences.getInt(KEY_USER_ID, -1)
    }

    // ---- Username ----

    fun saveUsername(username: String) {
        sharedPreferences.edit().putString(KEY_USERNAME, username).apply()
    }

    fun getUsername(): String? {
        return sharedPreferences.getString(KEY_USERNAME, null)
    }

    // ---- 检查是否已绑定 ----

    fun isBound(): Boolean {
        if (getToken() == null) return false
        // 检查新 DeviceIdManager 或旧 SharedPreferences
        return getDeviceId() != null
    }

    // ---- 清除所有数据 ----

    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }
}