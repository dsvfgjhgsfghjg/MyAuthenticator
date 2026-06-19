package top.leoblog.myauthenticator.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.spec.DHParameterSpec

/**
 * DH 密钥交换工具类
 */
object CryptoUtils {

    /**
     * 生成 DH 密钥对（2048-bit）
     */
    fun generateDhKeyPair(): KeyPair {
        val dhParams = DHParameterSpec(DhParameters.MODP_P, DhParameters.MODP_G)
        val keyPairGenerator = KeyPairGenerator.getInstance("DH")
        keyPairGenerator.initialize(dhParams, SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * 获取公钥的 Base64 编码
     */
    fun publicKeyToBase64(publicKey: PublicKey): String {
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    /**
     * 计算 DH 共享密钥
     *
     * @param privateKey 本地 DH 私钥
     * @param serverPublicKeyBase64 服务端 DH 公钥（Base64 编码）
     * @return 共享密钥（2048 位原始字节）
     */
    fun computeSharedSecret(
        privateKey: PrivateKey,
        serverPublicKeyBase64: String
    ): ByteArray {
        // 解码服务端公钥
        val serverPublicKeyBytes = Base64.getDecoder().decode(serverPublicKeyBase64)
        val keyFactory = KeyFactory.getInstance("DH")
        val serverPublicKey = keyFactory.generatePublic(
            X509EncodedKeySpec(serverPublicKeyBytes)
        )

        // 计算共享密钥
        val keyAgreement = KeyAgreement.getInstance("DH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(serverPublicKey, true)

        return keyAgreement.generateSecret()
    }
}
