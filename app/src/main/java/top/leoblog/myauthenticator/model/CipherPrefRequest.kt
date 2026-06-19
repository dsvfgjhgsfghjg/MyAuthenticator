package top.leoblog.myauthenticator.model

import com.google.gson.annotations.SerializedName

/**
 * 更新设备首选加密算法请求
 *
 * PUT /api/auth/app/devices/{deviceId}/cipher
 */
data class UpdateCipherPrefRequest(
    @SerializedName("cipherPref")
    val cipherPref: String
)