package top.leoblog.myauthenticator.ui.debug

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.crypto.LogCompressor
import top.leoblog.myauthenticator.model.AuthResultMessage
import top.leoblog.myauthenticator.model.ChallengeMessage
import top.leoblog.myauthenticator.network.AppWebSocketClient
import top.leoblog.myauthenticator.network.BugReportManager
import top.leoblog.myauthenticator.network.NetworkConfig
import top.leoblog.myauthenticator.network.RetrofitClient
import top.leoblog.myauthenticator.service.ChallengeCallback
import top.leoblog.myauthenticator.service.HandshakeCallback
import top.leoblog.myauthenticator.service.WebSocketService
import top.leoblog.myauthenticator.storage.SecureStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试信息 Fragment
 *
 * 展示设备状态、API 测试、WebSocket 诊断、操作日志，支持一键复制和上传调试信息
 *
 * 修复：使用新的 HandshakeState 枚举进行精细化的 WebSocket 握手诊断
 */
class DebugInfoFragment : Fragment() {

    private lateinit var secureStorage: SecureStorage
    private var _bindingView: View? = null
    private val bindingView get() = _bindingView!!

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // 内存日志缓冲区
    private val logBuffer = mutableListOf<String>()

    // 记录最后一次握手状态（用于诊断结论）
    private var lastHandshakeState: AppWebSocketClient.HandshakeState =
        AppWebSocketClient.HandshakeState.DISCONNECTED
    private var lastReconnectCount = 0

    // 握手状态显示名称映射
    private val handshakeLabels = mapOf(
        AppWebSocketClient.HandshakeState.DISCONNECTED to "❌ 未连接",
        AppWebSocketClient.HandshakeState.CONNECTING to "⏳ 连接中...",
        AppWebSocketClient.HandshakeState.CONNECTED to "✅ 已连接（等待 bind_ack）",
        AppWebSocketClient.HandshakeState.BOUND to "✅ 已绑定（等待 DH 握手）",
        AppWebSocketClient.HandshakeState.DH_INIT_SENT to "🔄 DH 响应已发（等待 dh_ready）",
        AppWebSocketClient.HandshakeState.DH_READY to "🎉 加密通道就绪（设备在线）",
        AppWebSocketClient.HandshakeState.FAILED to "❌ 握手失败"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _bindingView = inflater.inflate(R.layout.fragment_debug_info, container, false)
        return bindingView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        secureStorage = SecureStorage(requireContext())

        setupButtons()
        refreshStatus()
        addLog("INFO", "调试页面已打开")
    }

    override fun onResume() {
        super.onResume()
        // 注册握手状态回调
        WebSocketService.handshakeCallback = object : HandshakeCallback {
            override fun onHandshakeStateChanged(state: AppWebSocketClient.HandshakeState) {
                lastHandshakeState = state
                val label = handshakeLabels[state] ?: state.name
                addLog("INFO", "握手状态: $label")
                refreshWebSocketDiagnosis()
            }

            override fun onReconnectCountChanged(count: Int) {
                lastReconnectCount = count
                addLog("INFO", "重连次数: $count")
                refreshWebSocketDiagnosis()
            }
        }

        // 注册挑战推送回调 — 记录推送事件的调试日志
        WebSocketService.challengeCallback = object : ChallengeCallback {
            override fun onChallengeReceived(challenge: ChallengeMessage) {
                addLog("PUSH", "📩 收到推送挑战: id=${challenge.challengeId}")
                addLog("PUSH", "    数字选项: ${challenge.numbers.joinToString(", ")}")
                val remaining = (challenge.expiresAt * 1000 - System.currentTimeMillis()) / 1000
                addLog("PUSH", "    过期时间戳: ${challenge.expiresAt} (剩余 ${remaining}s)")
            }

            override fun onAuthResultReceived(result: AuthResultMessage) {
                val statusIcon = when (result.status) {
                    "approved" -> "✅"
                    "rejected" -> "❌"
                    "expired" -> "⏰"
                    else -> "❓"
                }
                addLog("PUSH", "$statusIcon 认证结果: ${result.status}" +
                    if (result.reason != null) " (${result.reason})" else "")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        WebSocketService.handshakeCallback = null
        WebSocketService.challengeCallback = null
    }

    private fun setupButtons() {
        // 测试 Profile API
        bindingView.findViewById<Button>(R.id.btn_test_profile).setOnClickListener {
            testProfileApi()
        }

        // 测试 CipherInfo API
        bindingView.findViewById<Button>(R.id.btn_test_cipher).setOnClickListener {
            testCipherInfoApi()
        }

        // 测试 WebSocket 连接诊断
        bindingView.findViewById<Button>(R.id.btn_test_ws).setOnClickListener {
            testWebSocketConnection()
        }

        // 测试调试会话接口
        bindingView.findViewById<Button>(R.id.btn_test_session).setOnClickListener {
            testDebugSessions()
        }

        // 一键复制调试信息
        bindingView.findViewById<Button>(R.id.btn_copy_debug).setOnClickListener {
            copyDebugInfo()
        }

        // 上传调试信息
        bindingView.findViewById<Button>(R.id.btn_upload_debug).setOnClickListener {
            uploadDebugInfo()
        }

        // 清空日志
        bindingView.findViewById<Button>(R.id.btn_clear_logs).setOnClickListener {
            logBuffer.clear()
            bindingView.findViewById<TextView>(R.id.tv_debug_logs).text = "暂无日志"
            addLog("INFO", "日志已清空")
        }

        // 刷新状态
        bindingView.findViewById<Button>(R.id.btn_refresh_status).setOnClickListener {
            refreshStatus()
            addLog("INFO", "状态已刷新")
        }
    }

    /**
     * 刷新设备状态显示
     */
    private fun refreshStatus() {
        val token = secureStorage.getToken()
        val deviceId = secureStorage.getDeviceId()
        val userId = secureStorage.getUserId()
        val username = secureStorage.getUsername()
        val cipherPref = secureStorage.getCipherPref()

        // Token — 显示是否存在 + 前20位
        bindingView.findViewById<TextView>(R.id.tv_debug_token).text = if (token != null) {
            val masked = if (token.length > 20) "${token.take(20)}..." else token
            "✅ $masked"
        } else {
            "❌ 未登录 (Token 为空)"
        }

        // DeviceId
        bindingView.findViewById<TextView>(R.id.tv_debug_device_id).text = deviceId ?: "❌ 未设置"

        // UserId
        bindingView.findViewById<TextView>(R.id.tv_debug_user_id).text =
            if (userId > 0) "$userId" else "❌ 未设置"

        // Username
        bindingView.findViewById<TextView>(R.id.tv_debug_username).text = username ?: "❌ 未设置"

        // CipherPref
        bindingView.findViewById<TextView>(R.id.tv_debug_cipher_pref).text = cipherPref ?: "❌ 未设置（DH 握手未完成）"

        // 环境
        bindingView.findViewById<TextView>(R.id.tv_debug_env).text =
            NetworkConfig.environment.name

        // REST URL
        bindingView.findViewById<TextView>(R.id.tv_debug_rest_url).text =
            NetworkConfig.restBaseUrl

        // WS URL
        bindingView.findViewById<TextView>(R.id.tv_debug_ws_url).text =
            NetworkConfig.webSocketUrl

            // WS 连接状态（使用真实握手状态）
        refreshWebSocketDiagnosis()

        // 断连信息（使用 WebSocketService 静态变量）
        refreshDisconnectInfo()
    }

    /**
     * 刷新断连信息显示
     */
    private fun refreshDisconnectInfo() {
        val disconnTime = WebSocketService.lastDisconnectTime
        val disconnReason = WebSocketService.lastDisconnectReason

        bindingView.findViewById<TextView>(R.id.tv_debug_last_disconnect_time).text =
            disconnTime ?: "-"
        bindingView.findViewById<TextView>(R.id.tv_debug_last_disconnect_reason).text =
            disconnReason ?: "-"
    }

    /**
     * 刷新 WebSocket 诊断显示
     *
     * 修复：基于 HandshakeState 给出准确的诊断，不再依赖虚假的 isConnected()
     */
    private fun refreshWebSocketDiagnosis() {
        val state = WebSocketService.getCurrentHandshakeState()
        val reconnectCount = WebSocketService.getReconnectCount()
        val label = handshakeLabels[state] ?: state.name

        val wsStatusText = when (state) {
            AppWebSocketClient.HandshakeState.DH_READY -> "✅ 加密通道就绪（在线）"
            AppWebSocketClient.HandshakeState.CONNECTING -> "⏳ 连接中..."
            AppWebSocketClient.HandshakeState.CONNECTED -> "🟡 已连接，等待 bind_ack"
            AppWebSocketClient.HandshakeState.BOUND -> "🟡 已绑定，等待 DH 握手"
            AppWebSocketClient.HandshakeState.DH_INIT_SENT -> "🟡 DH 响应已发送，等待 dh_ready"
            AppWebSocketClient.HandshakeState.FAILED -> "❌ 握手失败"
            AppWebSocketClient.HandshakeState.DISCONNECTED -> "❌ 未连接"
        }

        bindingView.findViewById<TextView>(R.id.tv_debug_ws_status).text = wsStatusText
        bindingView.findViewById<TextView>(R.id.tv_debug_reconnect_count).text = "重连次数: $reconnectCount"
    }

    // ==================== API 测试方法 ====================

    /**
     * 测试 Profile API
     */
    private fun testProfileApi() {
        val token = secureStorage.getToken()
        if (token == null) {
            showApiResult("❌ Token 为空，请先登录")
            addLog("ERROR", "Profile API 测试失败: Token 为空")
            return
        }

        addLog("INFO", "正在测试 Profile API...")
        bindingView.findViewById<TextView>(R.id.tv_api_result).text = "请求中..."

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.getUserProfile(
                    authorization = "Bearer $token"
                )
                if (response.isSuccessful) {
                    val body = response.body()
                    val jsonStr = gson.toJson(body)
                    showApiResult(jsonStr)

                    val data = body?.data
                    if (data != null) {
                        addLog("INFO", "Profile API 成功: userId=${data.userId}, avatarUrl=${data.avatarUrl ?: "null"}")
                        if (data.avatarUrl.isNullOrBlank()) {
                            addLog("WARN", "avatarUrl 为空，头像将无法加载")
                        } else {
                            addLog("INFO", "avatarUrl = ${data.avatarUrl}")
                        }
                    } else {
                        addLog("WARN", "Profile API data 为 null")
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "未知错误"
                    showApiResult("HTTP ${response.code()}: $errorBody")
                    addLog("ERROR", "Profile API 失败: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                showApiResult("❌ 网络异常: ${e.message}")
                addLog("ERROR", "Profile API 异常: ${e.message}")
            }
        }
    }

    /**
     * 测试 CipherInfo API
     */
    private fun testCipherInfoApi() {
        val token = secureStorage.getToken()
        if (token == null) {
            showApiResult("❌ Token 为空，请先登录")
            addLog("ERROR", "CipherInfo API 测试失败: Token 为空")
            return
        }

        val deviceId = secureStorage.getDeviceId()
        addLog("INFO", "正在测试 CipherInfo API (deviceId=${deviceId ?: "null"})...")
        bindingView.findViewById<TextView>(R.id.tv_api_result).text = "请求中..."

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.getCipherInfo(
                    authorization = "Bearer $token",
                    deviceId = deviceId
                )
                if (response.isSuccessful) {
                    val body = response.body()
                    val jsonStr = gson.toJson(body)
                    showApiResult(jsonStr)

                    val data = body?.data
                    if (data != null) {
                        addLog("INFO", "CipherInfo API 成功")
                        addLog("INFO", "keyExchange=${data.keyExchange.algorithm}")
                        addLog("INFO", "dataEncryption=${data.dataEncryption.algorithm}")
                        addLog("INFO", "deviceCipherPref=${data.deviceCipherPref ?: "null"}")
                        addLog("INFO", "availableAlgorithms 数量=${data.availableAlgorithms.size}")

                        if (data.deviceCipherPref == null) {
                            addLog("WARN", "deviceCipherPref 为空，设备首选算法卡片不会显示")
                        }
                        if (data.availableAlgorithms.isEmpty()) {
                            addLog("WARN", "availableAlgorithms 为空，可用算法列表无内容")
                        }
                    } else {
                        addLog("WARN", "CipherInfo API data 为 null")
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "未知错误"
                    showApiResult("HTTP ${response.code()}: $errorBody")
                    addLog("ERROR", "CipherInfo API 失败: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                showApiResult("❌ 网络异常: ${e.message}")
                addLog("ERROR", "CipherInfo API 异常: ${e.message}")
            }
        }
    }

    // ==================== WebSocket 诊断 ====================

    /**
     * 测试 WebSocket 连接诊断
     *
     * 修复：基于 HandshakeState 给出准确的、分步的诊断结论
     */
    private fun testWebSocketConnection() {
        val token = secureStorage.getToken()
        val deviceId = secureStorage.getDeviceId()

        if (token == null || deviceId == null) {
            showApiResult("❌ Token 或 DeviceId 为空，请先登录绑定")
            addLog("ERROR", "WebSocket 诊断失败: Token 或 DeviceId 为空")
            return
        }

        addLog("INFO", "═══ WebSocket 连接诊断 ═══")
        addLog("INFO", "连接地址: ${NetworkConfig.webSocketUrl}")
        addLog("INFO", "端点检查: ${if (NetworkConfig.webSocketUrl.endsWith("/native")) "✅ /native 端点" else "⚠️ 非 /native 端点"}")
        addLog("INFO", "DeviceId: $deviceId")
        addLog("INFO", "Token: ${token.take(20)}...")

        // 使用真实握手状态
        val state = WebSocketService.getCurrentHandshakeState()
        val reconnectCount = WebSocketService.getReconnectCount()
        val label = handshakeLabels[state] ?: state.name
        addLog("INFO", "握手状态: $label")
        addLog("INFO", "重连次数: $reconnectCount")

        // 检查必要数据完整性
        val cipherPref = secureStorage.getCipherPref()
        val userId = secureStorage.getUserId()
        addLog("INFO", "CipherPref: ${cipherPref ?: "❌ 未设置（DH 握手未完成）"}")
        addLog("INFO", "UserId: ${if (userId > 0) "✅ $userId" else "❌ 未设置"}")

        // 分步诊断
        val issues = mutableListOf<String>()

        when (state) {
            AppWebSocketClient.HandshakeState.DISCONNECTED -> {
                issues.add("WebSocket 未连接 → 可能原因：网络不通或被服务端断开")
                issues.add("检查: 手机网络是否正常、服务器是否可达")
                if (reconnectCount > 0) {
                    issues.add("已尝试重连 $reconnectCount 次但仍未成功")
                }
            }
            AppWebSocketClient.HandshakeState.CONNECTING -> {
                issues.add("WebSocket 正在连接中，请等待...")
            }
            AppWebSocketClient.HandshakeState.CONNECTED -> {
                issues.add("TCP 已连接但未收到 bind_ack → 检查 Token 是否有效")
            }
            AppWebSocketClient.HandshakeState.BOUND -> {
                issues.add("已绑定但未收到 dh_init → 检查服务端 DH 配置")
            }
            AppWebSocketClient.HandshakeState.DH_INIT_SENT -> {
                issues.add("DH 响应已发送但未收到 dh_ready → 检查 DH 密钥交换")
            }
            AppWebSocketClient.HandshakeState.DH_READY -> {
                // 一切正常
            }
            AppWebSocketClient.HandshakeState.FAILED -> {
                issues.add("握手失败 → 检查服务端日志")
            }
        }

        if (userId <= 0) issues.add("UserId 未设置 → WebSocket bind_ack 未收到")
        if (cipherPref == null) issues.add("CipherPref 未设置 → DH 握手未完成")

        if (issues.isEmpty()) {
            addLog("INFO", "═══ 诊断结论: ✅ 一切正常 ═══")
            showApiResult("✅ WebSocket 诊断通过\n- 握手状态: $label\n- 重连次数: $reconnectCount")
        } else {
            addLog("WARN", "═══ 诊断结论: ⚠️ 发现问题 ═══")
            issues.forEach { addLog("WARN", "  - $it") }
            showApiResult("⚠️ 发现 ${issues.size} 个问题:\n${issues.joinToString("\n")}\n\n当前握手状态: $label\n重连次数: $reconnectCount")
        }
    }

    /**
     * 测试调试会话接口
     *
     * 调用 GET /api/auth/app/test-push/debug/sessions
     * 查看服务端 WebSocket 会话状态
     */
    private fun testDebugSessions() {
        val token = secureStorage.getToken()
        if (token == null) {
            showApiResult("❌ Token 为空，请先登录")
            addLog("ERROR", "调试会话 API 测试失败: Token 为空")
            return
        }

        addLog("INFO", "正在获取服务端调试会话信息...")
        bindingView.findViewById<TextView>(R.id.tv_api_result).text = "请求中..."

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.getDebugSessions(
                    authorization = "Bearer $token"
                )
                if (response.isSuccessful) {
                    val body = response.body()
                    val jsonStr = gson.toJson(body)
                    showApiResult(jsonStr)

                    val data = body?.data
                    if (data != null) {
                        addLog("INFO", "调试会话 API 成功")
                        addLog("INFO", "userId=${data.userId}, deviceCount=${data.deviceCount}")
                        addLog("INFO", "注册会话数=${data.totalRegisteredSessions}")
                        data.deviceDetails.forEach { detail ->
                            val status = when {
                                detail.online -> "✅ 在线"
                                detail.sessionExists -> "⚠️ 会话存在但未打开"
                                else -> "❌ 离线"
                            }
                            addLog("INFO", "  ${detail.deviceName}: $status (sessionExists=${detail.sessionExists})")
                            if (detail.sessionId != null) {
                                addLog("INFO", "    sessionId: ${detail.sessionId}")
                            }
                        }
                    } else {
                        addLog("WARN", "调试会话 API data 为 null")
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "未知错误"
                    showApiResult("HTTP ${response.code()}: $errorBody")
                    addLog("ERROR", "调试会话 API 失败: HTTP ${response.code()}")
                    if (response.code() == 404) {
                        addLog("WARN", "接口 404 → 后端可能未实现 /api/auth/app/test-push/debug/sessions")
                    }
                }
            } catch (e: Exception) {
                showApiResult("❌ 网络异常: ${e.message}")
                addLog("ERROR", "调试会话 API 异常: ${e.message}")
            }
        }
    }

    // ==================== 通用方法 ====================

    /**
     * 显示 API 返回结果
     */
    private fun showApiResult(text: String) {
        bindingView.findViewById<TextView>(R.id.tv_api_result).text = text
    }

    /**
     * 添加日志到缓冲区和 UI
     */
    private fun addLog(level: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$time] [$level] $message"
        logBuffer.add(logLine)
        if (logBuffer.size > 200) {
            logBuffer.removeAt(0)
        }

        val logView = bindingView.findViewById<TextView>(R.id.tv_debug_logs)
        val displayText = logBuffer.joinToString("\n")
        logView.text = displayText

        // 自动滚动到底部
        logView.post {
            try {
                val lineCount = logView.lineCount
                if (lineCount > 0) {
                    val scrollY = logView.layout?.getLineTop(lineCount - 1) ?: 0
                    logView.scrollTo(0, scrollY)
                }
            } catch (_: Exception) {
            }
        }
    }

    // ==================== 调试信息上传 ====================

    /**
     * 收集完整调试信息并复制到剪贴板
     */
    private fun copyDebugInfo() {
        val info = buildDebugInfoText()
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("debug_info", info)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "✅ 调试信息已复制到剪贴板", Toast.LENGTH_LONG).show()
        addLog("INFO", "调试信息已复制到剪贴板 (${info.length} 字符)")
    }

    /**
     * 上传调试信息到后端 — 带推送弹窗 + 详细调试日志
     */
    private fun uploadDebugInfo() {
        val token = secureStorage.getToken()
        if (token == null) {
            Toast.makeText(requireContext(), "❌ Token 为空，请先登录", Toast.LENGTH_SHORT).show()
            addLog("ERROR", "上传调试信息失败: Token 为空")
            return
        }

        addLog("INFO", "═══ 开始上传调试信息 ═══")

        // 构建日志文本
        val logText = buildDebugInfoText()
        val logBytes = logText.toByteArray(Charsets.UTF_8)
        addLog("INFO", "原始日志大小: ${formatFileSize(logBytes.size)}")

        // 预览压缩率（不上传，仅调试）
        val compressionInfo = LogCompressor.getCompressionInfo(logText)
        addLog("INFO", "Gzip 压缩后: ${formatFileSize(compressionInfo.compressedSize)} (${compressionInfo.ratio}%)")

        // 显示推送弹窗
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("📤 上传调试信息")
            .setMessage(buildString {
                appendLine("正在压缩并上传...")
                appendLine()
                appendLine("原始大小: ${formatFileSize(logBytes.size)}")
                appendLine("压缩后: ${formatFileSize(compressionInfo.compressedSize)} (${compressionInfo.ratio}%)")
                appendLine("日志长度: ${logText.length} 字符")
                appendLine("请求体: 含设备状态 + 操作日志 + 系统信息")
            })
            .setCancelable(false)
            .create()

        dialog.show()

        // 按钮状态
        bindingView.findViewById<Button>(R.id.btn_upload_debug).isEnabled = false

        lifecycleScope.launch {
            try {
                addLog("INFO", "请求体: summary=调试信息上报, appVersion=${getAppVersion()}")

                val bugReportManager = BugReportManager.getInstance(
                    requireContext(),
                    RetrofitClient.apiService
                )

                val startTime = System.currentTimeMillis()

                val result = withContext(Dispatchers.IO) {
                    bugReportManager.submitLog(
                        logText = logText,
                        summary = "调试信息上报"
                    )
                }

                val elapsed = System.currentTimeMillis() - startTime
                addLog("INFO", "请求耗时: ${elapsed}ms")

                result.onSuccess { response ->
                    addLog("INFO", "═══ 上传成功 ═══")
                    addLog("INFO", "响应: id=${response.id}, status=${response.status}, createdAt=${response.createdAt}")

                    // 更新弹窗为成功
                    dialog.setTitle("✅ 上传成功")
                    dialog.setMessage(buildString {
                        appendLine("调试信息已上传到服务器")
                        appendLine()
                        appendLine("📋 记录 ID: ${response.id}")
                        appendLine("📌 状态: ${response.status}")
                        appendLine("⏱ 上传耗时: ${elapsed}ms")
                        appendLine("📦 原始: ${formatFileSize(logBytes.size)} → 压缩: ${formatFileSize(compressionInfo.compressedSize)} (${compressionInfo.ratio}%)")
                    })
                    dialog.setCancelable(true)
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "确定") { _, _ -> dialog.dismiss() }

                    Toast.makeText(requireContext(), R.string.upload_success, Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    addLog("ERROR", "═══ 上传失败 ═══")
                    addLog("ERROR", "错误: ${error.message}")
                    addLog("ERROR", "耗时: ${elapsed}ms")

                    // 更新弹窗为失败
                    dialog.setTitle("❌ 上传失败")
                    dialog.setMessage(buildString {
                        appendLine("错误信息: ${error.message ?: "未知错误"}")
                        appendLine()
                        appendLine("⏱ 耗时: ${elapsed}ms")
                        appendLine("📦 原始: ${formatFileSize(logBytes.size)} → 压缩: ${formatFileSize(compressionInfo.compressedSize)} (${compressionInfo.ratio}%)")
                        appendLine()
                        appendLine("💡 提示: 检查网络连接或后端服务是否正常")
                    })
                    dialog.setCancelable(true)
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "确定") { _, _ -> dialog.dismiss() }

                    Toast.makeText(
                        requireContext(),
                        getString(R.string.upload_failed, error.message ?: "未知错误"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                addLog("ERROR", "上传异常: ${e.message}")

                dialog.setTitle("❌ 上传异常")
                dialog.setMessage("异常: ${e.message}\n\n💡 请检查网络连接")
                dialog.setCancelable(true)
                dialog.setButton(AlertDialog.BUTTON_POSITIVE, "确定") { _, _ -> dialog.dismiss() }

                Toast.makeText(
                    requireContext(),
                    getString(R.string.upload_failed, e.message ?: "未知错误"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                bindingView.findViewById<Button>(R.id.btn_upload_debug).isEnabled = true
            }
        }
    }

    /**
     * 构建完整的调试信息文本
     */
    private fun buildDebugInfoText(): String {
        val token = secureStorage.getToken()
        val deviceId = secureStorage.getDeviceId()
        val userId = secureStorage.getUserId()
        val username = secureStorage.getUsername()
        val cipherPref = secureStorage.getCipherPref()
        val state = WebSocketService.getCurrentHandshakeState()
        val reconnectCount = WebSocketService.getReconnectCount()
        val label = handshakeLabels[state] ?: state.name

        return buildString {
            appendLine("===== MyAuthenticator 调试信息 =====")
            appendLine("生成时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine()
            appendLine("--- 设备状态 ---")
            appendLine("Token: ${if (token != null) "已保存 (${token.take(20)}...)" else "未登录"}")
            appendLine("DeviceId: ${deviceId ?: "未设置"}")
            appendLine("UserId: ${if (userId > 0) userId else "未设置"}")
            appendLine("Username: ${username ?: "未设置"}")
            appendLine("CipherPref: ${cipherPref ?: "未设置"}")
            appendLine("握手状态: $label")
            appendLine("重连次数: $reconnectCount")
            appendLine()
            appendLine("--- 网络配置 ---")
            appendLine("环境: ${NetworkConfig.environment.name}")
            appendLine("REST URL: ${NetworkConfig.restBaseUrl}")
            appendLine("WS URL: ${NetworkConfig.webSocketUrl}")
            appendLine()
            appendLine("--- 操作日志 ---")
            if (logBuffer.isEmpty()) {
                appendLine("(无日志)")
            } else {
                logBuffer.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("--- 应用信息 ---")
            appendLine("包名: ${requireContext().packageName}")
            try {
                val pkgInfo = requireContext().packageManager.getPackageInfo(
                    requireContext().packageName, 0
                )
                appendLine("版本: ${pkgInfo.versionName} (${pkgInfo.longVersionCode})")
            } catch (e: Exception) {
                appendLine("版本: 获取失败")
            }
            appendLine("Android API: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pkgInfo = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
            pkgInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun formatFileSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    override fun onDestroyView() {
        _bindingView = null
        super.onDestroyView()
    }
}