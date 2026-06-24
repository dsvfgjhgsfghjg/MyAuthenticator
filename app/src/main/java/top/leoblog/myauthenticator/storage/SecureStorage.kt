package top.leoblog.myauthenticator.storage

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import top.leoblog.myauthenticator.keepalive.KeepAliveConfig
import java.util.UUID

/**
 * 安全存储工具类
 *
 * 使用普通 SharedPreferences 存储（JWT Token 有服务端签名，篡改即失效）。
 * Android 系统会保留 app 的 SharedPreferences 文件，关掉 App 再打开数据仍在。
 * 配合 backup_rules.xml 实现卸载重装后的自动恢复。
 *
 * deviceId 已迁移到 DeviceIdManager（MD5 哈希方案），
 * 此处保留方法用于读取旧数据兼容。
 *
 * deviceSecret（服务端下发的设备密钥）也存储在此，
 * 对应 APP_DEVICE_SECRET_GUIDE.md 规范。
 */
class SecureStorage(context: Context) {

    companion object {
        // 使用新文件名！旧版用 EncryptedSharedPreferences 创建了同名的加密文件，
        // 普通 SharedPreferences 去读加密文件会返回 null
        private const val PREFS_NAME = "app_auth_prefs_v2"
        private const val KEY_JWT_TOKEN = "jwt_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_SECRET = "device_secret"
        private const val KEY_DEVICE_SECRET_HINT = "device_secret_hint"
        private const val KEY_CIPHER_PREF = "cipher_pref"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_PROFILE_EMAIL = "profile_email"
        private const val KEY_PROFILE_DEVICE_COUNT = "profile_device_count"
        private const val KEY_PROFILE_AVATAR_URL = "profile_avatar_url"
        private const val KEY_PROFILE_BOUND_AT = "profile_bound_at"
        private const val KEY_PROFILE_LAST_LOGIN_AT = "profile_last_login_at"

        // App Lock keys
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_LOCK_TIMEOUT_SECONDS = "lock_timeout_seconds"
        private const val KEY_UNLOCK_TIMESTAMP = "unlock_timestamp"

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

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        return DeviceIdManager.getOrCreateDeviceId(context)
    }

    fun saveDeviceId(deviceId: String) {
        sharedPreferences.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    fun getDeviceId(): String? {
        return sharedPreferences.getString(KEY_DEVICE_ID, null)
    }

    // ---- Device Secret（服务端下发） ----

    /**
     * 保存设备密钥（由服务端下发的 64 位 hex 字符串）
     */
    fun saveDeviceSecret(secret: String) {
        sharedPreferences.edit().putString(KEY_DEVICE_SECRET, secret).apply()
    }

    /**
     * 获取设备密钥
     */
    fun getDeviceSecret(): String? {
        return sharedPreferences.getString(KEY_DEVICE_SECRET, null)
    }

    /**
     * 保存设备密钥提示（前4位+...+后4位，用于 UI 展示）
     */
    fun saveDeviceSecretHint(hint: String) {
        sharedPreferences.edit().putString(KEY_DEVICE_SECRET_HINT, hint).apply()
    }

    /**
     * 获取设备密钥提示
     */
    fun getDeviceSecretHint(): String? {
        return sharedPreferences.getString(KEY_DEVICE_SECRET_HINT, null)
    }

    /**
     * 清除设备密钥（用户切换账号或清除数据时）
     */
    fun clearDeviceSecret() {
        sharedPreferences.edit()
            .remove(KEY_DEVICE_SECRET)
            .remove(KEY_DEVICE_SECRET_HINT)
            .apply()
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

    // ---- Nickname ----

    fun saveNickname(nickname: String) {
        sharedPreferences.edit().putString(KEY_NICKNAME, nickname).apply()
    }

    fun getNickname(): String? {
        return sharedPreferences.getString(KEY_NICKNAME, null)
    }

    // ---- Profile 数据缓存（用于调试页面展示） ----

    fun saveProfileEmail(email: String) {
        sharedPreferences.edit().putString(KEY_PROFILE_EMAIL, email).apply()
    }

    fun getProfileEmail(): String? {
        return sharedPreferences.getString(KEY_PROFILE_EMAIL, null)
    }

    fun saveProfileDeviceCount(count: Int) {
        sharedPreferences.edit().putInt(KEY_PROFILE_DEVICE_COUNT, count).apply()
    }

    fun getProfileDeviceCount(): Int {
        return sharedPreferences.getInt(KEY_PROFILE_DEVICE_COUNT, -1)
    }

    fun saveProfileAvatarUrl(url: String) {
        sharedPreferences.edit().putString(KEY_PROFILE_AVATAR_URL, url).apply()
    }

    fun getProfileAvatarUrl(): String? {
        return sharedPreferences.getString(KEY_PROFILE_AVATAR_URL, null)
    }

    fun saveProfileBoundAt(boundAt: String) {
        sharedPreferences.edit().putString(KEY_PROFILE_BOUND_AT, boundAt).apply()
    }

    fun getProfileBoundAt(): String? {
        return sharedPreferences.getString(KEY_PROFILE_BOUND_AT, null)
    }

    fun saveProfileLastLoginAt(lastLoginAt: String) {
        sharedPreferences.edit().putString(KEY_PROFILE_LAST_LOGIN_AT, lastLoginAt).apply()
    }

    fun getProfileLastLoginAt(): String? {
        return sharedPreferences.getString(KEY_PROFILE_LAST_LOGIN_AT, null)
    }

    fun clearProfileCache() {
        sharedPreferences.edit()
            .remove(KEY_PROFILE_EMAIL)
            .remove(KEY_PROFILE_DEVICE_COUNT)
            .remove(KEY_PROFILE_AVATAR_URL)
            .remove(KEY_PROFILE_BOUND_AT)
            .remove(KEY_PROFILE_LAST_LOGIN_AT)
            .apply()
    }

    // ==================== 保活配置 ====================

    fun getKeepAliveMode(): String {
        return sharedPreferences.getString(KeepAliveConfig.KEY_KEEPALIVE_MODE, KeepAliveConfig.DEFAULT_KEEPALIVE_MODE) ?: KeepAliveConfig.DEFAULT_KEEPALIVE_MODE
    }

    fun saveKeepAliveMode(mode: String) {
        sharedPreferences.edit().putString(KeepAliveConfig.KEY_KEEPALIVE_MODE, mode).apply()
    }

    fun isPeriodicWakeupEnabled(): Boolean {
        return sharedPreferences.getBoolean(KeepAliveConfig.KEY_PERIODIC_WAKEUP_ENABLED, KeepAliveConfig.DEFAULT_PERIODIC_WAKEUP_ENABLED)
    }

    fun savePeriodicWakeupEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KeepAliveConfig.KEY_PERIODIC_WAKEUP_ENABLED, enabled).apply()
    }

    fun getWakeupIntervalMinutes(): Int {
        return sharedPreferences.getInt(KeepAliveConfig.KEY_WAKEUP_INTERVAL_MINUTES, KeepAliveConfig.DEFAULT_WAKEUP_INTERVAL_MINUTES)
    }

    fun saveWakeupIntervalMinutes(minutes: Int) {
        sharedPreferences.edit().putInt(KeepAliveConfig.KEY_WAKEUP_INTERVAL_MINUTES, minutes).apply()
    }

    // ---- 检查是否已绑定 ----

    fun isBound(): Boolean {
        if (getToken() == null) return false
        return getDeviceId() != null
    }

    // ---- 清除所有数据 ----

    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }

    // ---- App Lock (应用锁) ----

    fun isLockEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_LOCK_ENABLED, false)
    }

    fun setLockEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
    }

    fun savePinHash(hash: String) {
        sharedPreferences.edit().putString(KEY_PIN_HASH, hash).apply()
    }

    fun getPinHash(): String? {
        return sharedPreferences.getString(KEY_PIN_HASH, null)
    }

    fun hasPin(): Boolean {
        return !getPinHash().isNullOrBlank()
    }

    fun isBiometricEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun getLockTimeoutSeconds(): Int {
        return sharedPreferences.getInt(KEY_LOCK_TIMEOUT_SECONDS, 0) // 0 = immediately
    }

    fun setLockTimeoutSeconds(seconds: Int) {
        sharedPreferences.edit().putInt(KEY_LOCK_TIMEOUT_SECONDS, seconds).apply()
    }

    // ---- 解锁时间戳（SharedPreferences 持久化，跨 Activity 实例共享） ----

    fun saveUnlockTimestamp(timestamp: Long) {
        sharedPreferences.edit().putLong(KEY_UNLOCK_TIMESTAMP, timestamp).apply()
    }

    fun getUnlockTimestamp(): Long {
        return sharedPreferences.getLong(KEY_UNLOCK_TIMESTAMP, 0L)
    }

    fun clearUnlockTimestamp() {
        sharedPreferences.edit().remove(KEY_UNLOCK_TIMESTAMP).apply()
    }
}
