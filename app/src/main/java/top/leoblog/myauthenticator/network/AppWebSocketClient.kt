package top.leoblog.myauthenticator.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import top.leoblog.myauthenticator.crypto.AesGcmCrypto
import top.leoblog.myauthenticator.crypto.CryptoUtils
import top.leoblog.myauthenticator.crypto.KeyDerivation
import top.leoblog.myauthenticator.crypto.Sm4GcmCrypto
import top.leoblog.myauthenticator.model.*
import java.security.KeyPair
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket 客户端 - 实现完整通信协议
 *
 * 协议流程：
 * bind → bind_ack → dh_init → dh_response → dh_ready → (加密通道就绪)
 *
 * 加密通道就绪后：
 * - challenge 消息为加密格式: {"type":"encrypted","iv":"...","ciphertext":"..."}
 * - auth_result 消息为加密格式: {"type":"encrypted","iv":"...","ciphertext":"..."}
 * - challenge_response 需加密后发送
 */
class AppWebSocketClient(
    private val token: String,
    private val deviceId: String,
    private val deviceSecret: String,
    private val listener: WebSocketListenerCallback
) {
    companion object {
        private const val TAG = "AppWebSocketClient"
        private const val PING_INTERVAL_MS = 30_000L // 30 秒心跳
    }

    /**
     * WebSocket 握手状态枚举
     *
     * 用于精细化的调试诊断
     */
    enum class HandshakeState {
        DISCONNECTED,    // 未连接
        CONNECTING,      // 连接中
        CONNECTED,       // TCP 已连接，等待 bind_ack
        BOUND,           // bind_ack 收到，等待 dh_init
        DH_INIT_SENT,    // dh_response 已发，等待 dh_ready
        DH_READY,        // 加密通道就绪
        FAILED           // 连接失败
    }

    interface WebSocketListenerCallback {
        /** WebSocket 连接已建立 */
        fun onConnected()
        /** 绑定确认 */
        fun onBindAck(userId: Int)
        /** 加密通道已就绪 */
        fun onDhReady(cipher: String)
        /** 收到挑战 */
        fun onChallenge(challenge: ChallengeMessage)
        /** 收到认证结果 */
        fun onAuthResult(result: AuthResultMessage)
        /** 收到错误消息 */
        fun onError(message: String)
        /** 连接断开 */
        fun onDisconnected(reason: String)
        /** 连接失败 */
        fun onConnectionFailed(throwable: Throwable)

        /**
         * 握手状态变更回调（供调试诊断使用）
         */
        fun onHandshakeStateChanged(state: HandshakeState)
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var dhKeyPair: KeyPair? = null
    private var aesKey: ByteArray? = null
    var cipherAlgo: String = "AES-256-GCM"
        private set
    private var connectTime: Long = 0

    /**
     * 真实连接状态标志
     *
     * 修复关键问题：之前 isConnected() 只检查 webSocket != null，
     * 但 OkHttp 的 newWebSocket() 立即返回 WebSocket 对象，不表示已连接。
     * 现在用布尔标志跟踪真实状态。
     */
    private var connected = false

    /**
     * 当前握手状态（供外部调试查询）
     */
    @Volatile
    var handshakeState: HandshakeState = HandshakeState.DISCONNECTED
        private set

    // 统计信息
    private val sendSuccessCount = AtomicInteger(0)
    private val sendFailureCount = AtomicInteger(0)
    private var reconnectCount = 0

    /**
     * 获取重连次数
     */
    fun getReconnectCount(): Int = reconnectCount

    /**
     * 增加重连计数（由 WebSocketService 调用）
     */
    fun incrementReconnectCount() {
        reconnectCount++
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 不应超时
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    // ==================== 生命周期调试日志 ====================

    /**
     * 连接 WebSocket
     */
    fun connect() {
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "WebSocket 连接开始")
        Log.d(TAG, "连接地址: ${NetworkConfig.webSocketUrl}")
        Log.d(TAG, "设备 ID: $deviceId")
        Log.d(TAG, "Token 前缀: ${token.take(20)}...")
        Log.d(TAG, "═══════════════════════════════════════")

        handshakeState = HandshakeState.CONNECTING
        listener.onHandshakeStateChanged(handshakeState)

        val request = Request.Builder()
            .url(NetworkConfig.webSocketUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectTime = System.currentTimeMillis()
                connected = true
                handshakeState = HandshakeState.CONNECTED
                listener.onHandshakeStateChanged(handshakeState)
                Log.d(TAG, "✅ WebSocket 连接已建立 (真实状态标记为 connected=true)")
                listener.onConnected()
                // 发送 bind 消息
                sendBindMessage()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 正在关闭: $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val duration = if (connectTime > 0) {
                    (System.currentTimeMillis() - connectTime) / 1000
                } else 0
                Log.w(TAG, "🔌 WebSocket 连接已关闭: code=$code, reason=$reason, 持续了 ${duration}s")
                cleanupState("连接已关闭: $reason")
                listener.onDisconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket 连接失败: ${t.message}")
                Log.e(TAG, "   异常类型: ${t.javaClass.simpleName}")
                cleanupState("连接失败: ${t.message}")
                listener.onConnectionFailed(t)
            }
        })
    }

    /**
     * 断开时清理全部状态
     *
     * 修复：断开时不仅要把 webSocket 置 null，
     * 还要清理 DH 握手数据，防止残留密钥被误用。
     */
    private fun cleanupState(reason: String) {
        connected = false
        handshakeState = HandshakeState.DISCONNECTED
        listener.onHandshakeStateChanged(handshakeState)
        webSocket = null
        dhKeyPair = null
        aesKey = null
        Log.d(TAG, "🧹 状态已清理: $reason")
    }

    /**
     * 发送 bind 消息
     */
    private fun sendBindMessage() {
        Log.d(TAG, "📤 发送 bind 消息")
        Log.d(TAG, "   deviceId: $deviceId")
        Log.d(TAG, "   deviceSecret 前缀: ${deviceSecret.take(8)}...")
        val message = BindMessage(
            token = token,
            deviceId = deviceId,
            deviceSecret = deviceSecret
        )
        val json = gson.toJson(message)
        val sent = webSocket?.send(json) ?: false
        if (!sent) {
            Log.e(TAG, "❌ bind 消息发送失败（send 返回 false）")
            listener.onError("bind 消息发送失败")
        } else {
            sendSuccessCount.incrementAndGet()
        }
    }

    /**
     * 处理接收到的消息
     */
    private fun handleMessage(text: String) {
        try {
            val root = JsonParser.parseString(text).asJsonObject
            val type = root.get("type")?.asString ?: return

            when (type) {
                "bind_ack" -> handleBindAck(root)
                "dh_init" -> handleDhInit(root)
                "dh_ready" -> handleDhReady(root)
                "challenge" -> handleChallenge(root)
                "auth_result" -> handleAuthResult(root)
                "encrypted" -> handleEncryptedMessage(root)
                "pong" -> { /* 心跳回复，忽略 */ }
                "error" -> handleError(root)
                else -> Log.w(TAG, "未知消息类型: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "消息解析失败: ${e.message}")
            listener.onError("消息解析失败: ${e.message}")
        }
    }

    // ==================== 消息处理 ====================

    private fun handleBindAck(json: JsonObject) {
        val status = json.get("status")?.asString ?: "error"
        if (status == "ok") {
            val userId = json.get("userId")?.asInt ?: -1
            handshakeState = HandshakeState.BOUND
            listener.onHandshakeStateChanged(handshakeState)
            Log.d(TAG, "📥 收到 bind_ack, userId=$userId ✅ 绑定成功")
            listener.onBindAck(userId)
        } else {
            val message = json.get("message")?.asString ?: "绑定失败"
            handshakeState = HandshakeState.FAILED
            listener.onHandshakeStateChanged(handshakeState)
            listener.onError(message)
        }
    }

    private fun handleDhInit(json: JsonObject) {
        try {
            val serverPublicKey = json.get("serverPublicKey")?.asString
                ?: throw IllegalArgumentException("缺少 serverPublicKey")

            Log.d(TAG, "📥 收到 dh_init")
            Log.d(TAG, "   服务端公钥前缀: ${serverPublicKey.take(30)}...")

            // 1. 生成 DH 密钥对
            dhKeyPair = CryptoUtils.generateDhKeyPair()

            // 2. 计算共享密钥
            val sharedSecret = CryptoUtils.computeSharedSecret(
                dhKeyPair!!.private, serverPublicKey
            )

            // 3. 派生 AES-256 密钥
            aesKey = KeyDerivation.deriveAesKey(sharedSecret, 256)

            // 4. 发送客户端公钥
            val clientPublicKey = CryptoUtils.publicKeyToBase64(dhKeyPair!!.public)
            Log.d(TAG, "📤 发送 dh_response")
            Log.d(TAG, "   客户端公钥前缀: ${clientPublicKey.take(30)}...")

            val response = DhResponseMessage(clientPublicKey = clientPublicKey)
            val json = gson.toJson(response)
            val sent = webSocket?.send(json) ?: false
            if (!sent) {
                Log.e(TAG, "❌ dh_response 发送失败")
                handshakeState = HandshakeState.FAILED
                listener.onHandshakeStateChanged(handshakeState)
                listener.onError("DH 响应发送失败")
                return
            }
            sendSuccessCount.incrementAndGet()

            handshakeState = HandshakeState.DH_INIT_SENT
            listener.onHandshakeStateChanged(handshakeState)

            Log.d(TAG, "DH 密钥交换完成")
        } catch (e: Exception) {
            Log.e(TAG, "DH 密钥交换失败", e)
            handshakeState = HandshakeState.FAILED
            listener.onHandshakeStateChanged(handshakeState)
            listener.onError("DH 密钥交换失败: ${e.message}")
        }
    }

    private fun handleDhReady(json: JsonObject) {
        cipherAlgo = json.get("cipher")?.asString ?: "AES-256-GCM"
        handshakeState = HandshakeState.DH_READY
        listener.onHandshakeStateChanged(handshakeState)
        Log.d(TAG, "📥 收到 dh_ready, cipher=$cipherAlgo ✅ DH 密钥交换完成")
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "🎉 设备现在应该显示为【在线】状态！")
        Log.d(TAG, "═══════════════════════════════════════")
        listener.onDhReady(cipherAlgo)
    }

    /**
     * 处理加密消息（挑战/认证结果通过加密通道传输）
     *
     * 对应 APP_PUSH_TEST_ANDROID_GUIDE.md 3.1 节：
     * 接收格式: {"type":"encrypted","iv":"...","ciphertext":"..."}
     */
    private fun handleEncryptedMessage(json: JsonObject) {
        try {
            val ivB64 = json.get("iv")?.asString
                ?: throw IllegalArgumentException("加密消息缺少 iv")
            val ciphertextB64 = json.get("ciphertext")?.asString
                ?: throw IllegalArgumentException("加密消息缺少 ciphertext")

            Log.d(TAG, "📥 收到加密消息")
            Log.d(TAG, "   iv 前缀: ${ivB64.take(16)}...")
            Log.d(TAG, "   密文长度: ${ciphertextB64.length}")

            if (aesKey == null) {
                Log.e(TAG, "❌ 收到加密消息但 AES 密钥为空（DH 握手未完成）")
                listener.onError("加密通道未就绪，无法解密")
                return
            }

            val iv = Base64.getDecoder().decode(ivB64)
            val ciphertext = Base64.getDecoder().decode(ciphertextB64)

            val decryptedBytes = when (cipherAlgo) {
                "AES-256-GCM" -> AesGcmCrypto.decrypt(aesKey!!, iv, ciphertext)
                "SM4-GCM" -> Sm4GcmCrypto.decrypt(aesKey!!, iv, ciphertext)
                else -> throw IllegalStateException("不支持的加密算法: $cipherAlgo")
            }

            val decryptedJson = String(decryptedBytes, Charsets.UTF_8)
            Log.d(TAG, "   解密后消息: $decryptedJson")

            // 解析解密后的内部消息
            val inner = JsonParser.parseString(decryptedJson).asJsonObject
            when (inner.get("type")?.asString) {
                "challenge" -> handleChallenge(inner)
                "auth_result" -> handleAuthResult(inner)
                else -> Log.w(TAG, "加密消息中的未知类型: ${inner.get("type")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解密消息失败: ${e.message}")
            listener.onError("解密消息失败: ${e.message}")
        }
    }

    private fun handleChallenge(json: JsonObject) {
        try {
            val challenge = gson.fromJson(json, ChallengeMessage::class.java)
            Log.d(TAG, "📥 收到挑战: ${challenge.challengeId}, 数字: ${challenge.numbers}")
            listener.onChallenge(challenge)
        } catch (e: Exception) {
            Log.e(TAG, "挑战消息解析失败", e)
        }
    }

    private fun handleAuthResult(json: JsonObject) {
        try {
            val result = gson.fromJson(json, AuthResultMessage::class.java)
            Log.d(TAG, "📥 认证结果: ${result.status}")
            listener.onAuthResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "认证结果解析失败", e)
        }
    }

    private fun handleError(json: JsonObject) {
        val message = json.get("message")?.asString ?: "未知错误"
        Log.e(TAG, "❌ 服务端错误: $message")
        handshakeState = HandshakeState.FAILED
        listener.onHandshakeStateChanged(handshakeState)
        listener.onError(message)
    }

    // ==================== 发送消息 ====================

    /**
     * 发送挑战响应（用户选择数字后）
     *
     * 如果加密通道已就绪，消息需加密后发送。
     * 对应 APP_PUSH_TEST_ANDROID_GUIDE.md 3.2 节。
     */
    fun sendChallengeResponse(challengeId: String, selectedNumber: Int) {
        try {
            // 构建明文消息
            val plainMessage = ChallengeResponseMessage(
                challengeId = challengeId,
                selectedNumber = selectedNumber
            )
            val plainJson = gson.toJson(plainMessage)

            // 如果加密通道已就绪，加密发送
            if (aesKey != null) {
                sendEncryptedMessage(plainJson)
            } else {
                // 降级为明文发送（兼容旧协议）
                Log.w(TAG, "加密通道未就绪，使用明文发送挑战响应")
                sendMessage(plainJson)
            }

            Log.d(TAG, "发送挑战响应: challengeId=$challengeId, selected=$selectedNumber")
        } catch (e: Exception) {
            Log.e(TAG, "发送挑战响应失败", e)
        }
    }

    /**
     * 发送加密消息
     *
     * 格式: {"type":"encrypted","iv":"...","ciphertext":"..."}
     */
    private fun sendEncryptedMessage(plaintext: String) {
        val iv = when (cipherAlgo) {
            "AES-256-GCM" -> AesGcmCrypto.generateIv()
            "SM4-GCM" -> Sm4GcmCrypto.generateIv()
            else -> throw IllegalStateException("不支持的加密算法: $cipherAlgo")
        }

        val encryptedBytes = when (cipherAlgo) {
            "AES-256-GCM" -> AesGcmCrypto.encrypt(aesKey!!, iv, plaintext.toByteArray(Charsets.UTF_8))
            "SM4-GCM" -> Sm4GcmCrypto.encrypt(aesKey!!, iv, plaintext.toByteArray(Charsets.UTF_8))
            else -> throw IllegalStateException("不支持的加密算法: $cipherAlgo")
        }

        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ciphertextB64 = Base64.getEncoder().encodeToString(encryptedBytes)

        val encryptedMessage = """{"type":"encrypted","iv":"$ivB64","ciphertext":"$ciphertextB64"}"""
        sendMessage(encryptedMessage)
    }

    private fun sendMessage(json: String) {
        val sent = webSocket?.send(json) ?: false
        if (sent) {
            sendSuccessCount.incrementAndGet()
        } else {
            sendFailureCount.incrementAndGet()
            Log.w(TAG, "⚠️ 消息发送失败，webSocket.send() 返回 false")
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        webSocket?.close(1000, "用户退出")
        cleanupState("用户主动断开")
    }

    /**
     * 是否已连接
     *
     * 修复：使用真实的布尔标志，不再依赖 webSocket 是否为 null。
     * OkHttp 的 newWebSocket() 立即返回对象，不能代表连接状态。
     */
    fun isConnected(): Boolean {
        return connected && webSocket != null
    }
}