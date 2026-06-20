package top.leoblog.myauthenticator.ui.lock

import android.util.Log
import top.leoblog.myauthenticator.storage.SecureStorage
import java.security.MessageDigest

/**
 * 应用锁管理器
 *
 * 负责 PIN 哈希验证、解锁状态管理、后台超时检测。
 * 所有 PIN 相关存储和校验完全在客户端本地完成。
 */
class LockManager(private val secureStorage: SecureStorage) {

    companion object {
        private const val TAG = "LockManager"
    }

    /** 解锁时记录的时间戳 */
    private var unlockTimestamp: Long = 0L

    /**
     * 应用锁是否已启用
     */
    fun isLockEnabled(): Boolean = secureStorage.isLockEnabled()

    /**
     * 是否已设置 PIN
     */
    fun hasPin(): Boolean = secureStorage.hasPin()

    /**
     * 设置 PIN（存储 SHA-256 哈希）
     */
    fun setPin(pin: String) {
        secureStorage.savePinHash(sha256(pin))
    }

    /**
     * 校验 PIN 是否正确
     */
    fun verifyPin(pin: String): Boolean {
        val storedHash = secureStorage.getPinHash() ?: return false
        return sha256(pin) == storedHash
    }

    /**
     * 当前是否处于解锁状态（未超时）
     */
    fun isUnlocked(): Boolean {
        if (unlockTimestamp == 0L) return false
        val timeoutSeconds = secureStorage.getLockTimeoutSeconds()
        if (timeoutSeconds <= 0) {
            // 0 = 每次关闭都需要解锁，不保留解锁状态
            return false
        }
        val elapsed = (System.currentTimeMillis() - unlockTimestamp) / 1000
        return elapsed < timeoutSeconds
    }

    /**
     * 标记解锁成功（记录时间戳）
     */
    fun markUnlocked() {
        unlockTimestamp = System.currentTimeMillis()
        Log.d(TAG, "App unlocked")
    }

    /**
     * 标记锁定（清除解锁状态）
     */
    fun markLocked() {
        unlockTimestamp = 0L
        Log.d(TAG, "App locked")
    }

    /**
     * 检查是否需要显示解锁界面
     * 应用锁已开启 且 (未设置PIN 或 当前未解锁)
     */
    fun shouldLock(): Boolean {
        return isLockEnabled() && !isUnlocked()
    }

    /**
     * SHA-256 哈希
     */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}