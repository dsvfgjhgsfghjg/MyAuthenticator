package top.leoblog.myauthenticator.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.leoblog.myauthenticator.crypto.LogCompressor
import top.leoblog.myauthenticator.model.BugReportRequest
import top.leoblog.myauthenticator.model.BugReportResponse
import top.leoblog.myauthenticator.storage.SecureStorage
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Bug 日志上报管理器
 *
 * 对应 BUG_REPORT_ANDROID_GUIDE.md 3.4 节
 * 负责收集设备信息、压缩日志、调用 API 上报。
 */
class BugReportManager(
    private val context: Context,
    private val apiService: ApiService
) {
    companion object {
        private const val TAG = "BugReportManager"

        @Volatile
        private var instance: BugReportManager? = null

        fun getInstance(context: Context, apiService: ApiService): BugReportManager {
            return instance ?: synchronized(this) {
                instance ?: BugReportManager(context.applicationContext, apiService).also {
                    instance = it
                }
            }
        }
    }

    private val secureStorage by lazy { SecureStorage(context) }

    /**
     * 上报日志
     *
     * @param logText 日志文本
     * @param summary 日志摘要（可选）
     * @param appVersion App 版本号（可选，自动获取）
     * @param deviceModel 设备型号（可选，自动获取）
     * @param osVersion 操作系统版本（可选，自动获取）
     * @return 上报结果
     */
    suspend fun submitLog(
        logText: String,
        summary: String? = null,
        appVersion: String? = null,
        deviceModel: String? = null,
        osVersion: String? = null
    ): Result<BugReportResponse> = withContext(Dispatchers.IO) {
        try {
            // 1. 获取 Token
            val token = secureStorage.getToken()
                ?: return@withContext Result.failure(Exception("未登录，无法上报日志"))

            // 2. 压缩日志
            val compressedData = LogCompressor.compress(logText)

            // 3. 获取设备信息（如果未提供）
            val finalAppVersion = appVersion ?: getAppVersion()
            val finalDeviceModel = deviceModel ?: getDeviceModel()
            val finalOsVersion = osVersion ?: getOsVersion()

            // 4. 构建请求
            val request = BugReportRequest(
                logData = compressedData,
                appVersion = finalAppVersion,
                deviceModel = finalDeviceModel,
                osVersion = finalOsVersion,
                summary = summary
            )

            // 5. 发送请求（使用 RetrofitClient 的统一 Auth 拦截器，但仍显式传 token 以保持兼容）
            val response = apiService.submitBugReport("Bearer $token", request)

            if (response.isSuccessful && response.body()?.data != null) {
                val data = response.body()!!.data!!
                Log.i(TAG, "Bug 日志上报成功: id=${data.id}")
                Result.success(data)
            } else {
                val errorBody = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                Log.e(TAG, "Bug 日志上报失败: $errorBody")
                Result.failure(Exception("上报失败: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bug 日志上报异常", e)
            Result.failure(e)
        }
    }

    /**
     * 上报异常堆栈（便捷方法）
     */
    suspend fun submitException(
        throwable: Throwable,
        summary: String? = null,
        extraInfo: String? = null
    ): Result<BugReportResponse> {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()

        val logText = buildString {
            if (extraInfo != null) {
                appendLine("=== 额外信息 ===")
                appendLine(extraInfo)
                appendLine()
            }
            appendLine("=== 异常堆栈 ===")
            append(sw.toString())
        }

        return submitLog(
            logText = logText,
            summary = summary ?: throwable.javaClass.simpleName
        )
    }

    // ===== 辅助方法 =====

    private fun getAppVersion(): String {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getDeviceModel(): String {
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    }

    private fun getOsVersion(): String {
        return "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
    }
}