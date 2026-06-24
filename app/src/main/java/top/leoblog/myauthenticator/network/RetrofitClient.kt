package top.leoblog.myauthenticator.network

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import top.leoblog.myauthenticator.storage.SecureStorage
import java.util.concurrent.TimeUnit

/**
 * Retrofit 客户端单例
 *
 * 使用 NetworkConfig 统一管理 URL，默认生产环境。
 * 支持通过 NetworkConfig.useDevelopment() 切换至开发环境。
 */
object RetrofitClient {

    private const val TAG = "RetrofitClient"

    /**
     * 日志拦截器（仅在开发环境启用日志输出）
     */
    private val loggingInterceptor: HttpLoggingInterceptor by lazy {
        HttpLoggingInterceptor { message ->
            Log.d(TAG, message)
        }.apply {
            level = if (NetworkConfig.environment == NetworkConfig.Environment.DEVELOPMENT) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    /**
     * Auth 拦截器 — 自动注入 JWT Token
     */
    private fun createAuthInterceptor(storage: SecureStorage): okhttp3.Interceptor {
        return okhttp3.Interceptor { chain ->
            val originalRequest = chain.request()

            // 从安全存储中获取 Token
            val token = storage.getToken()

            if (token != null) {
                val authenticatedRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
                chain.proceed(authenticatedRequest)
            } else {
                chain.proceed(originalRequest)
            }
        }
    }

    /**
     * 认证错误拦截器 — 401/403（Token 过期/认证过期）时触发 AuthExpiredHandler
     *
     * 不再直接跳转到登录页，而是通过 AuthExpiredHandler 通知当前 Activity
     * 弹出提示窗口，让用户确认后跳转登录页，跳转时保留 deviceId/deviceSecret。
     */
    private fun createAuthErrorInterceptor(context: Context): okhttp3.Interceptor {
        return okhttp3.Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 401 || response.code == 403) {
                Log.w(TAG, "认证过期 (HTTP ${response.code})，触发 AuthExpiredHandler")
                SecureStorage(context).clearToken()
                AuthExpiredHandler.notifyAuthExpired("HTTP ${response.code}")
            }
            response
        }
    }

    /**
     * 创建 OkHttpClient（不含 Auth 拦截器）
     * 适用于首次绑定时（密码绑定 / 扫码绑定）不需要 JWT 的场景
     */
    private fun createBaseClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * 创建带 Auth 拦截器和认证过期处理的 OkHttpClient
     * 适用于需要 JWT Token 的请求（获取设备列表、检查绑定状态、解绑等）
     * 401/403 时会触发 AuthExpiredHandler 弹窗提示重新登录
     */
    private fun createAuthenticatedClient(context: Context, storage: SecureStorage): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .addInterceptor(createAuthInterceptor(storage))
            .addInterceptor(createAuthErrorInterceptor(context))
            .build()
    }

    private var retrofit: Retrofit? = null

    /**
     * 获取 ApiService 实例（无 Auth，用于公开接口如密码绑定）
     */
    val apiService: ApiService by lazy {
        createRetrofit().create(ApiService::class.java)
    }

    /**
     * 获取带 Auth 拦截器和认证过期处理的 ApiService 实例
     *
     * @param context Context（用于 401/403 过期处理中的 SharedPreferences）
     * @param storage SecureStorage 实例，用于读取 JWT Token
     */
    fun createAuthenticatedApiService(context: Context, storage: SecureStorage): ApiService {
        return Retrofit.Builder()
            .baseUrl("${NetworkConfig.restBaseUrl}/")
            .client(createAuthenticatedClient(context, storage))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    /**
     * 创建 Retrofit 实例
     */
    private fun createRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl("${NetworkConfig.restBaseUrl}/")
            .client(createBaseClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * 重置 Retrofit 实例（环境切换时调用）
     */
    fun reset() {
        retrofit = null
    }
}
