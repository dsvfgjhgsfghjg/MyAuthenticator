package top.leoblog.myauthenticator.network

/**
 * 网络配置 — 统一管理 REST API 和 WebSocket 连接地址
 *
 * 支持三种环境：
 * - PRODUCTION: 生产环境 https://leo-blog.top
 * - DEVELOPMENT: 模拟器开发环境 http://10.0.2.2:8080
 * - WIRELESS_DEBUG: 真机无线调试，需设置 hostIp
 */
object NetworkConfig {

    /**
     * 环境枚举
     */
    enum class Environment {
        /** 生产环境 */
        PRODUCTION,

        /** 开发环境（Android 模拟器访问主机） */
        DEVELOPMENT,

        /** 无线调试环境（真机通过 Wi-Fi 连接电脑） */
        WIRELESS_DEBUG
    }

    /**
     * 当前环境，默认生产
     */
    var environment: Environment = Environment.PRODUCTION

    /**
     * 无线调试时主机的 IP 地址
     * 电脑连接手机热点后，通过 `ip addr` 或 `ifconfig` 查看本机 IP
     */
    var wirelessHostIp: String = "192.168.0.100"

    /**
     * 后端服务端口（开发环境）
     */
    const val DEV_PORT = 8080

    /**
     * REST API 基准 URL（末尾不含 /）
     */
    val restBaseUrl: String
        get() = when (environment) {
            Environment.PRODUCTION -> "https://leo-blog.top"
            Environment.DEVELOPMENT -> "http://10.0.2.2:$DEV_PORT"
            Environment.WIRELESS_DEBUG -> "http://$wirelessHostIp:$DEV_PORT"
        }

    /**
     * WebSocket 端点 URL
     *
     * 必须使用 /native 端点（非 SockJS 端点），否则原生 WebSocket 客户端连接会被拒绝。
     * 详见 APP_PUSH_TEST_ANDROID_GUIDE.md
     */
    val webSocketUrl: String
        get() = when (environment) {
            Environment.PRODUCTION -> "wss://leo-blog.top/ws/app/auth/native"
            Environment.DEVELOPMENT -> "ws://10.0.2.2:$DEV_PORT/ws/app/auth/native"
            Environment.WIRELESS_DEBUG -> "ws://$wirelessHostIp:$DEV_PORT/ws/app/auth/native"
        }

    /**
     * 切换到生产环境
     */
    fun useProduction() {
        environment = Environment.PRODUCTION
    }

    /**
     * 切换到开发环境（模拟器）
     */
    fun useDevelopment() {
        environment = Environment.DEVELOPMENT
    }

    /**
     * 切换到无线调试环境
     *
     * @param hostIp 电脑在 Wi-Fi 局域网中的 IP 地址
     */
    fun useWirelessDebug(hostIp: String) {
        wirelessHostIp = hostIp
        environment = Environment.WIRELESS_DEBUG
    }
}
