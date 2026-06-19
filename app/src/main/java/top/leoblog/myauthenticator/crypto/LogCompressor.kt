package top.leoblog.myauthenticator.crypto

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 日志压缩工具类
 *
 * 将日志文本先 Gzip 压缩，再 Base64 编码，用于上传到后端。
 * 对应 BUG_REPORT_ANDROID_GUIDE.md 3.2 节。
 */
object LogCompressor {

    private const val TAG = "LogCompressor"

    // 最大原始日志大小：5MB
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024

    // 最大压缩后大小：2MB
    private const val MAX_COMPRESSED_SIZE = 2 * 1024 * 1024

    /**
     * 压缩日志文本
     *
     * @param logText 原始日志文本
     * @return Gzip + Base64 编码后的字符串
     * @throws IllegalArgumentException 如果日志超过大小限制
     */
    fun compress(logText: String): String {
        // 检查原始日志大小
        val textBytes = logText.toByteArray(Charsets.UTF_8)
        if (textBytes.size > MAX_LOG_SIZE) {
            throw IllegalArgumentException("原始日志超过 5MB 限制: ${textBytes.size} bytes")
        }

        // Gzip 压缩
        val compressedBytes = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(textBytes)
            }
            baos.toByteArray()
        }

        // 检查压缩后大小
        if (compressedBytes.size > MAX_COMPRESSED_SIZE) {
            throw IllegalArgumentException("压缩后日志超过 2MB 限制: ${compressedBytes.size} bytes")
        }

        // Base64 编码（Android 自带 Base64 类，非标准 java.util.Base64）
        return Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
    }

    /**
     * 获取压缩率信息
     */
    fun getCompressionInfo(logText: String): CompressionInfo {
        val textBytes = logText.toByteArray(Charsets.UTF_8)
        val compressedBytes = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(textBytes)
            }
            baos.toByteArray()
        }

        val ratio = if (textBytes.size > 0) {
            (compressedBytes.size.toDouble() / textBytes.size * 100).toInt()
        } else 0

        return CompressionInfo(
            originalSize = textBytes.size,
            compressedSize = compressedBytes.size,
            ratio = ratio
        )
    }

    data class CompressionInfo(
        val originalSize: Int,
        val compressedSize: Int,
        val ratio: Int  // 压缩率百分比
    )
}