package top.leoblog.myauthenticator.crypto

import java.security.MessageDigest

/**
 * 密钥派生工具类（SHA-256）
 *
 * DH 交换产生的共享密钥（2048位）→ SHA-256 → 实际加密密钥
 */
object KeyDerivation {

    /**
     * 从共享密钥派生 AES 密钥
     *
     * @param sharedSecret DH 共享密钥（2048 位原始字节）
     * @param keySize 密钥长度（位）：128, 192, 256
     * @return 派生后的 AES 密钥字节数组
     */
    fun deriveAesKey(sharedSecret: ByteArray, keySize: Int): ByteArray {
        // SHA-256 哈希
        val hash = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        // 取前 keySize/8 字节
        val keyBytes = keySize / 8
        return hash.copyOfRange(0, minOf(keyBytes, hash.size))
    }
}
