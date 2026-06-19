package top.leoblog.myauthenticator.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SM4-GCM 加解密工具类
 *
 * 国密算法支持，需要 Bouncy Castle 库
 *
 * | 参数 | 值 | 说明 |
 * |------|-----|------|
 * | 密钥长度 | 128-bit（16 bytes） | SM4 固定密钥长度 |
 * | 加密模式 | GCM | 认证加密 |
 * | IV 长度 | 12 字节 | 推荐值 |
 * | Provider | Bouncy Castle | bcprov-jdk18on |
 */
object Sm4GcmCrypto {

    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val SM4_KEY_SIZE = 16 // 128-bit

    private var providerRegistered = false

    /**
     * 注册 Bouncy Castle Provider
     */
    fun initBouncyCastle() {
        if (!providerRegistered) {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            providerRegistered = true
        }
    }

    /**
     * 获取裁剪后的 SM4 密钥（取前 16 字节）
     */
    private fun toSm4Key(key: ByteArray): ByteArray {
        return if (key.size >= SM4_KEY_SIZE) {
            key.copyOfRange(0, SM4_KEY_SIZE)
        } else {
            error("SM4 密钥长度至少需要 16 字节")
        }
    }

    /**
     * SM4-GCM 加密 — 使用外部 IV
     *
     * @param key SM4 密钥
     * @param iv 12 字节 IV
     * @param plaintext 明文
     * @return 密文
     */
    fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        initBouncyCastle()
        val sm4Key = toSm4Key(key)
        val cipher = Cipher.getInstance("SM4/GCM/NoPadding", "BC")
        val keySpec = SecretKeySpec(sm4Key, "SM4")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(plaintext)
    }

    /**
     * SM4-GCM 加密 — 自动生成 IV
     *
     * @param plaintext 明文
     * @param key SM4 密钥
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
     * SM4-GCM 解密 — 使用字节数组 IV 和密文
     *
     * @param key SM4 密钥
     * @param iv 12 字节 IV
     * @param ciphertext 密文
     * @return 解密后的明文
     */
    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        initBouncyCastle()
        val sm4Key = toSm4Key(key)
        val cipher = Cipher.getInstance("SM4/GCM/NoPadding", "BC")
        val keySpec = SecretKeySpec(sm4Key, "SM4")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * SM4-GCM 解密 — 使用 Base64 编码的 IV 和密文
     *
     * @param ivBase64 IV（Base64 编码）
     * @param ciphertextBase64 密文（Base64 编码）
     * @param key SM4 密钥
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
