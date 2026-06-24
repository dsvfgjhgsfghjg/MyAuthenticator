package top.leoblog.myauthenticator.crypto

import org.bouncycastle.crypto.engines.SM4Engine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.SecureRandom
import java.security.Security
import java.util.Base64

/**
 * SM4-GCM 加解密工具类
 *
 * 使用 Bouncy Castle 低级 API（GCMBlockCipher + SM4Engine）
 * 而非 JCE Cipher.getInstance()，因为 BC Provider 未注册 SM4/GCM/NoPadding 转换。
 *
 * | 参数 | 值 | 说明 |
 * |------|-----|------|
 * | 密钥长度 | 128-bit（16 bytes） | SM4 固定密钥长度 |
 * | 加密模式 | GCM | 认证加密 |
 * | IV 长度 | 12 字节 | 推荐值 |
 * | Tag 长度 | 128-bit | GCM 认证标签 |
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
     * 使用 BC 低级 API 执行 SM4-GCM 加密
     */
    @Suppress("DEPRECATION")
    private fun encryptInternal(sm4Key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = GCMBlockCipher(SM4Engine())
        val keyParam = KeyParameter(sm4Key)
        val aeadParams = AEADParameters(keyParam, GCM_TAG_LENGTH, iv)
        cipher.init(true, aeadParams)

        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val len1 = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        val len2 = cipher.doFinal(output, len1)
        val totalLen = len1 + len2

        // 精确裁剪到实际输出大小
        return output.copyOfRange(0, totalLen)
    }

    /**
     * 使用 BC 低级 API 执行 SM4-GCM 解密
     */
    @Suppress("DEPRECATION")
    private fun decryptInternal(sm4Key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = GCMBlockCipher(SM4Engine())
        val keyParam = KeyParameter(sm4Key)
        val aeadParams = AEADParameters(keyParam, GCM_TAG_LENGTH, iv)
        cipher.init(false, aeadParams)

        val output = ByteArray(cipher.getOutputSize(ciphertext.size))
        val len1 = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
        val len2 = cipher.doFinal(output, len1)
        val totalLen = len1 + len2

        return output.copyOfRange(0, totalLen)
    }

    /**
     * SM4-GCM 加密 — 使用外部 IV
     *
     * @param key SM4 密钥
     * @param iv 12 字节 IV
     * @param plaintext 明文
     * @return 密文（含 GCM 认证标签）
     * @throws Exception 加密失败时抛出
     */
    @Throws(Exception::class)
    fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        initBouncyCastle()
        val sm4Key = toSm4Key(key)
        return encryptInternal(sm4Key, iv, plaintext)
    }

    /**
     * SM4-GCM 加密 — 自动生成 IV
     *
     * @param plaintext 明文
     * @param key SM4 密钥
     * @return 加密结果（IV + 密文，均为 Base64 编码）
     */
    @Throws(Exception::class)
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
     * @param ciphertext 密文（含 GCM 认证标签）
     * @return 解密后的明文
     * @throws Exception 认证失败或解密失败时抛出
     */
    @Throws(Exception::class)
    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        initBouncyCastle()
        val sm4Key = toSm4Key(key)
        return decryptInternal(sm4Key, iv, ciphertext)
    }

    /**
     * SM4-GCM 解密 — 使用 Base64 编码的 IV 和密文
     *
     * @param ivBase64 IV（Base64 编码）
     * @param ciphertextBase64 密文（Base64 编码）
     * @param key SM4 密钥
     * @return 解密后的明文
     */
    @Throws(Exception::class)
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