package top.leoblog.myauthenticator.network

import retrofit2.Response
import retrofit2.http.*
import top.leoblog.myauthenticator.model.*

/**
 * Retrofit REST API 接口
 */
interface ApiService {

    /**
     * 0.1 生成设备码（不需要认证）
     *
     * 对应 APP_DEVICE_SECRET_GUIDE.md
     */
    @POST("/api/auth/app/device-secret")
    suspend fun getDeviceSecret(
    ): Response<ApiResponse<DeviceSecretResponse>>

    /**
     * 4.1 密码绑定
     */
    @POST("/api/auth/app/bind")
    suspend fun bindWithPassword(
        @Body request: BindPasswordRequest
    ): Response<ApiResponse<BindResponse>>

    /**
     * 4.2 扫码绑定
     */
    @POST("/api/auth/app/bind/qrcode")
    suspend fun bindWithQrCode(
        @Header("Authorization") authorization: String,
        @Body request: BindQrCodeRequest
    ): Response<ApiResponse<BindResponse>>

    /**
     * 4.4 获取设备列表
     */
    @GET("/api/auth/app/devices")
    suspend fun getDevices(
        @Header("Authorization") authorization: String
    ): Response<ApiResponse<List<DeviceInfo>>>

    /**
     * 4.4 检查绑定状态
     */
    @GET("/api/auth/app/status")
    suspend fun getBindStatus(
        @Header("Authorization") authorization: String
    ): Response<ApiResponse<BindStatus>>

    /**
     * 1.3 生成配对码（网页端调用）
     */
    @POST("/api/auth/app/generate-pair-code")
    suspend fun generatePairCode(
        @Header("Authorization") authorization: String
    ): Response<ApiResponse<PairCodeResponse>>

    /**
     * 1.6 解绑设备
     */
    @DELETE("/api/auth/app/devices/{deviceId}")
    suspend fun unbindDevice(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<Unit>>

    // ===================== 设备加密算法设置 =====================

    /**
     * 更新设备首选加密算法
     */
    @PUT("/api/auth/app/devices/{deviceId}/cipher")
    suspend fun updateCipherPref(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String,
        @Body request: UpdateCipherPrefRequest
    ): Response<ApiResponse<Unit>>

    // ===================== v2 新增接口 =====================

    /**
     * 2.1 获取加密算法信息
     */
    @GET("/api/auth/app/cipher-info")
    suspend fun getCipherInfo(
        @Header("Authorization") authorization: String,
        @Query("deviceId") deviceId: String? = null
    ): Response<ApiResponse<CipherInfoResponse>>

    /**
     * 2.2 获取用户个人信息
     * 注意路径是 /api/user/profile（单数），不是 /api/users/profile
     */
    @GET("/api/user/profile")
    suspend fun getUserProfile(
        @Header("Authorization") authorization: String
    ): Response<ApiResponse<UserProfileResponse>>

    /**
     * 2.3 获取认证历史（分页）
     */
    @GET("/api/auth/app/history")
    suspend fun getAuthHistory(
        @Header("Authorization") authorization: String,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("status") status: String? = null
    ): Response<ApiResponse<AuthHistoryPageData>>

    // ===================== Dashboard（Authenticator 首页聚合） =====================

    /**
     * 获取 Authenticator 首页聚合数据
     * 对应 APP_DASHBOARD_API_GUIDE.md
     */
    @GET("/api/auth/app/dashboard")
    suspend fun getDashboard(
        @Header("Authorization") authorization: String,
        @Query("deviceId") deviceId: String? = null
    ): Response<ApiResponse<DashboardResponse>>

    // ===================== 调试接口 =====================

    /**
     * 调试 WebSocket 会话状态
     * 对应 APP_PUSH_TEST_ANDROID_GUIDE.md 调试指南
     */
    @GET("/api/auth/app/test-push/debug/sessions")
    suspend fun getDebugSessions(
        @Header("Authorization") authorization: String
    ): Response<ApiResponse<DebugSessionsResponse>>

    // ===================== Bug 日志上报 =====================

    /**
     * 上传 Bug 日志
     * 对应 BUG_REPORT_ANDROID_GUIDE.md 3.3 节
     */
    @POST("/api/auth/app/bug-reports")
    suspend fun submitBugReport(
        @Header("Authorization") authorization: String,
        @Body request: BugReportRequest
    ): Response<ApiResponse<BugReportResponse>>
}
