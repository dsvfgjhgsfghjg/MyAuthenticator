package top.leoblog.myauthenticator.model

import com.google.gson.annotations.SerializedName

/**
 * Bug 日志上传数据模型
 *
 * 对应 BUG_REPORT_ANDROID_GUIDE.md 3.3 节
 */

/**
 * Bug 日志上报请求体
 */
data class BugReportRequest(
    /** Gzip + Base64 编码的日志内容 */
    @SerializedName("logData")
    val logData: String,

    /** App 版本号 */
    @SerializedName("appVersion")
    val appVersion: String? = null,

    /** 设备型号 */
    @SerializedName("deviceModel")
    val deviceModel: String? = null,

    /** 操作系统版本 */
    @SerializedName("osVersion")
    val osVersion: String? = null,

    /** 日志摘要（可选） */
    @SerializedName("summary")
    val summary: String? = null
)

/**
 * Bug 日志上报响应
 */
data class BugReportResponse(
    @SerializedName("id")
    val id: Long,

    @SerializedName("status")
    val status: String,        // "unread"

    @SerializedName("createdAt")
    val createdAt: String      // ISO datetime
)