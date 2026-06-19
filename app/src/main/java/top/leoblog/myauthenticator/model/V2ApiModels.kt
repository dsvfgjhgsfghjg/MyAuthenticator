package top.leoblog.myauthenticator.model

import com.google.gson.annotations.SerializedName

/**
 * v2 API 数据模型
 *
 * 对应 APP_AUTH_ANDROID_GUIDE_V2.md 新增的三个 API
 */

// ===================== 2.1 加密算法信息 =====================

data class CipherInfoResponse(
    @SerializedName("keyExchange")
    val keyExchange: KeyExchangeInfo,
    @SerializedName("dataEncryption")
    val dataEncryption: DataEncryptionInfo,
    @SerializedName("availableAlgorithms")
    val availableAlgorithms: List<AlgorithmInfo>,
    @SerializedName("deviceCipherPref")
    val deviceCipherPref: String? = null
)

data class KeyExchangeInfo(
    @SerializedName("algorithm")
    val algorithm: String,       // "DH"
    @SerializedName("keySize")
    val keySize: Int,            // 2048
    @SerializedName("group")
    val group: String,           // "MODP Group (RFC 3526)"
    @SerializedName("hash")
    val hash: String             // "SHA-256"
)

data class DataEncryptionInfo(
    @SerializedName("algorithm")
    val algorithm: String,       // "AES-256-GCM"
    @SerializedName("keySize")
    val keySize: Int,            // 256
    @SerializedName("ivLength")
    val ivLength: Int,           // 12
    @SerializedName("tagLength")
    val tagLength: Int,          // 16
    @SerializedName("mode")
    val mode: String,            // "GCM"
    @SerializedName("padding")
    val padding: String          // "NoPadding"
)

data class AlgorithmInfo(
    @SerializedName("name")
    val name: String,
    @SerializedName("type")
    val type: String,            // "key_exchange" / "data_encryption"
    @SerializedName("keySize")
    val keySize: Int,
    @SerializedName("description")
    val description: String
)

// ===================== 2.2 用户个人信息 =====================

data class UserProfileResponse(
    @SerializedName("userId")
    val userId: Long,
    @SerializedName("username")
    val username: String,
    @SerializedName("email")
    val email: String?,
    @SerializedName("avatarUrl")
    val avatarUrl: String?,
    @SerializedName("boundAt")
    val boundAt: String?,        // ISO datetime
    @SerializedName("lastLoginAt")
    val lastLoginAt: String?,    // ISO datetime
    @SerializedName("deviceCount")
    val deviceCount: Int
)

// ===================== 2.3 认证历史 =====================

data class AuthHistoryPageData(
    @SerializedName("records")
    val records: List<AuthHistoryRecord>,
    @SerializedName("total")
    val total: Long,
    @SerializedName("page")
    val page: Long,
    @SerializedName("size")
    val size: Long,
    @SerializedName("pages")
    val pages: Long
)

data class AuthHistoryRecord(
    @SerializedName("id")
    val id: Long,
    @SerializedName("challengeId")
    val challengeId: String,
    @SerializedName("deviceId")
    val deviceId: String?,
    @SerializedName("deviceName")
    val deviceName: String?,
    @SerializedName("status")
    val status: String,          // "pending" / "approved" / "rejected" / "expired"
    @SerializedName("requestedAt")
    val requestedAt: String,     // ISO datetime
    @SerializedName("respondedAt")
    val respondedAt: String?     // ISO datetime
)

// ===================== 2.4 调试会话信息 =====================

data class DebugSessionsResponse(
    @SerializedName("userId")
    val userId: Long,
    @SerializedName("deviceCount")
    val deviceCount: Int,
    @SerializedName("totalRegisteredSessions")
    val totalRegisteredSessions: Int,
    @SerializedName("deviceDetails")
    val deviceDetails: List<DeviceSessionDetail>
)

data class DeviceSessionDetail(
    @SerializedName("deviceId")
    val deviceId: String,
    @SerializedName("deviceName")
    val deviceName: String,
    @SerializedName("online")
    val online: Boolean,
    @SerializedName("sessionExists")
    val sessionExists: Boolean,
    @SerializedName("sessionId")
    val sessionId: String?,
    @SerializedName("sessionOpen")
    val sessionOpen: Boolean?
)
