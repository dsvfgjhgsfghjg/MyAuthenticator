package top.leoblog.myauthenticator.model

import com.google.gson.annotations.SerializedName

/**
 * WebSocket bind 消息
 *
 * 对应 APP_DEVICE_SECRET_GUIDE.md 增强验证流程
 */
data class BindMessage(
    @SerializedName("type")
    val type: String = "bind",
    @SerializedName("token")
    val token: String,
    @SerializedName("deviceId")
    val deviceId: String,
    @SerializedName("deviceSecret")
    val deviceSecret: String = ""
)

/**
 * WebSocket bind_ack 消息
 */
data class BindAckMessage(
    @SerializedName("type")
    val type: String,
    @SerializedName("status")
    val status: String,
    @SerializedName("userId")
    val userId: Int? = null
)

/**
 * WebSocket dh_init 消息（服务端发送 DH 公钥）
 */
data class DhInitMessage(
    @SerializedName("type")
    val type: String,
    @SerializedName("serverPublicKey")
    val serverPublicKey: String
)

/**
 * WebSocket dh_response 消息（客户端发送 DH 公钥）
 */
data class DhResponseMessage(
    @SerializedName("type")
    val type: String = "dh_response",
    @SerializedName("clientPublicKey")
    val clientPublicKey: String
)

/**
 * WebSocket dh_ready 消息
 */
data class DhReadyMessage(
    @SerializedName("type")
    val type: String,
    @SerializedName("cipher")
    val cipher: String
)

/**
 * WebSocket challenge 消息 — 3 选 1 挑战
 */
data class ChallengeMessage(
    @SerializedName("type")
    val type: String,
    @SerializedName("challengeId")
    val challengeId: String,
    @SerializedName("numbers")
    val numbers: List<Int>,
    @SerializedName("expiresAt")
    val expiresAt: Long
)

/**
 * WebSocket challenge_response 消息
 */
data class ChallengeResponseMessage(
    @SerializedName("type")
    val type: String = "challenge_response",
    @SerializedName("challengeId")
    val challengeId: String,
    @SerializedName("selectedNumber")
    val selectedNumber: Int
)

/**
 * WebSocket auth_result 消息
 */
data class AuthResultMessage(
    @SerializedName("type")
    val type: String,
    @SerializedName("status")
    val status: String,
    @SerializedName("reason")
    val reason: String? = null
)

/**
 * WebSocket 错误消息
 */
data class WsErrorMessage(
    @SerializedName("type")
    val type: String,
    @SerializedName("message")
    val message: String
)

/**
 * WebSocket ping 消息
 */
data class PingMessage(
    @SerializedName("type")
    val type: String = "ping"
)
