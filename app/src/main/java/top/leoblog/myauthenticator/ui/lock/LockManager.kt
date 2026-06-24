package top.leoblog.myauthenticator.ui.lock

import android.util.Log
import top.leoblog.myauthenticator.storage.SecureStorage
import java.security.MessageDigest

/**
 * 应用锁管理器
 *
 * 负责 PIN 哈希验证、解锁状态管理、后台超时检测。
 * 所有 PIN 相关存储和校验完全在客户端本地完成。
 *
 * 修复：解锁时间戳通过 SecureStorage 持久化，跨 Activity 实例共享，
 * 避免 MainActivity → LockManager → shouldLock() 死循环。
 */
class LockManager(private val secureStorage: SecureStorage) {

    companion object {
        private const val TAG = "LockManager"
    }

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
     *
     * 使用 SecureStorage 持久化的时间戳，确保跨 Activity 实例一致。
     *
     * 修复：当 timeout = 0（每次关闭后重新锁定）时，如果解锁时间戳在 2 秒内，
     * 视为刚解锁的宽限期，允许跳转到 MainActivity，避免无限循环。
     * 2 秒后 isUnlocked() 才会返回 false，触发重新锁定。
     */
    fun isUnlocked(): Boolean {
        val unlockTimestamp = secureStorage.getUnlockTimestamp()
        if (unlockTimestamp == 0L) return false
        val elapsed = (System.currentTimeMillis() - unlockTimestamp) / 1000
        val timeoutSeconds = secureStorage.getLockTimeoutSeconds()
        if (timeoutSeconds <= 0) {
            // 0 = 每次关闭都需要解锁，但给 2 秒宽限期避免刚解锁就重锁
            return elapsed < 2
        }
        return elapsed < timeoutSeconds
    }

    /**
     * 标记解锁成功（记录时间戳到持久化存储）
     */
    fun markUnlocked() {
        secureStorage.saveUnlockTimestamp(System.currentTimeMillis())
        Log.d(TAG, "App unlocked")
    }

    /**
     * 标记锁定（清除解锁状态）
     */
    fun markLocked() {
        secureStorage.clearUnlockTimestamp()
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