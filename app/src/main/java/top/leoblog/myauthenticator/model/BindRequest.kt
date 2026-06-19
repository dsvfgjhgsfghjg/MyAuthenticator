package top.leoblog.myauthenticator.model

import com.google.gson.annotations.SerializedName

/**
 * 密码绑定请求
 */
data class BindPasswordRequest(
    @SerializedName("username")
    val username: String,
    @SerializedName("password")
    val password: String,
    @SerializedName("deviceId")
    val deviceId: String,
    @SerializedName("deviceName")
    val deviceName: String,
    @SerializedName("deviceType")
    val deviceType: String = "ANDROID"
)

/**
 * 扫码绑定请求
 */
data class BindQrCodeRequest(
    @SerializedName("pairCode")
    val pairCode: String,
    @SerializedName("deviceId")
    val deviceId: String,
    @SerializedName("deviceName")
    val deviceName: String
)

/**
 * 密码绑定响应
 */
data class BindResponse(
    @SerializedName("token")
    val token: String,
    @SerializedName("deviceId")
    val deviceId: String
)

/**
 * 设备信息
 */
data class DeviceInfo(
    @SerializedName("id")
    val id: Long,
    @SerializedName("userId")
    val userId: Int,
    @SerializedName("deviceId")
    val deviceId: String,
    @SerializedName("deviceName")
    val deviceName: String,
    @SerializedName("deviceType")
    val deviceType: String,
    @SerializedName("publicKey")
    val publicKey: String? = null,
    @SerializedName("cipherPref")
    val cipherPref: String? = null,
    @SerializedName("lastActiveAt")
    val lastActiveAt: String? = null,
    @SerializedName("createdAt")
    val createdAt: String? = null
)

/**
 * 绑定状态
 */
data class BindStatus(
    @SerializedName("hasBoundDevice")
    val hasBoundDevice: Boolean
)

/**
 * 配对码响应
 */
data class PairCodeResponse(
    @SerializedName("pairCode")
    val pairCode: String,
    @SerializedName("expiresIn")
    val expiresIn: Int
)
