package top.leoblog.myauthenticator.model

import com.google.gson.annotations.SerializedName

/**
 * 通用 API 响应包装
 */
data class ApiResponse<T>(
    @SerializedName("code")
    val code: Int,
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("data")
    val data: T? = null
)
