package top.leoblog.myauthenticator.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM 加解密结果
 */
data class AesGcmResult(
    val iv: String,
    val ciphertext: String
)

/**
 * AES-GCM 加解密工具类
 *
 * | 参数 | 值 | 说明 |
 * |------|-----|------|
 * | 加密模式 | GCM | 认证加密 |
 * | IV 长度 | 12 字节（96-bit） | 推荐值 |
 * | Tag 长度 | 128-bit | GCM 认证标签 |
 * | 填充 | NoPadding | GCM 不需要填充 |
 */
object AesGcmCrypto {

    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    /**
     * AES-GCM 加密 — 使用外部 IV
     *
     * @param key AES 密钥
     * @param iv 12 字节 IV
     * @param plaintext 明文
     * @return 密文（不包含 IV）
     */
    fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(plaintext)
    }

    /**
     * AES-GCM 加密 — 自动生成 IV
     *
     * @param plaintext 明文
     * @param key AES 密钥
     * @return 加密结果（IV + 密文，均为 Base64 编码）
     */
    fun encrypt(plaintext: ByteArray, key: ByteArray): AesGcmResult {
        val iv = generateIv()
        val ciphertext = encrypt(key, iv, plaintext)
        return AesGcmResult(
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext)
        )
    }

    /**
     * AES-GCM 解密 — 使用字节数组 IV 和密文
     *
     * @param key AES 密钥
     * @param iv 12 字节 IV
     * @param ciphertext 密文
     * @return 解密后的明文
     */
    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * AES-GCM 解密 — 使用 Base64 编码的 IV 和密文
     *
     * @param ivBase64 IV（Base64 编码）
     * @param ciphertextBase64 密文（Base64 编码）
     * @param key AES 密钥
     * @return 解密后的明文
     */
    fun decrypt(ivBase64: String, ciphertextBase64: String, key: ByteArray): ByteArray {
        val iv = Base64.getDecoder().decode(ivBase64)
        val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
        return decrypt(key, iv, ciphertext)
    }

    /**
     * 生成 12 字节随机 IV
     */
    fun generateIv(): ByteArray {
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        return iv
    }
}
