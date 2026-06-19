# App 双因素认证系统 — Android 端开发指导文档

> **版本:** v1.0  
> **更新日期:** 2026-06-13  
> **后端开发者:** Leo  
> **协议:** WebSocket + DH 密钥交换 + AES-GCM/SM4-GCM 加密

---

## 📋 目录

1. [系统概述](#-系统概述)
2. [环境要求](#-环境要求)
3. [依赖库配置](#-依赖库配置)
4. [REST API 对接](#-rest-api-对接)
   - [4.1 密码绑定](#41-密码绑定)
   - [4.2 扫码绑定](#42-扫码绑定)
   - [4.3 生成配对码](#43-生成配对码)
   - [4.4 设备管理](#44-设备管理)
5. [密码学实现](#-密码学实现)
   - [5.1 DH 密钥交换（2048-bit）](#51-dh-密钥交换2048-bit)
   - [5.2 AES-GCM 加解密](#52-aes-gcm-加解密)
   - [5.3 SM4-GCM 加解密](#53-sm4-gcm-加解密)
   - [5.4 密钥派生（SHA-256）](#54-密钥派生sha-256)
6. [WebSocket 通信协议](#-websocket-通信协议)
   - [6.1 连接建立](#61-连接建立)
   - [6.2 完整协议流程](#62-完整协议流程)
   - [6.3 消息格式详解](#63-消息格式详解)
7. [3 选 1 挑战机制](#-3-选-1-挑战机制)
8. [完整对接流程](#-完整对接流程)
   - [场景 A：App 首次使用（密码绑定）](#场景-aapp-首次使用密码绑定)
   - [场景 B：App 首次使用（扫码绑定）](#场景-bapp-首次使用扫码绑定)
   - [场景 C：后续登录审批（WebSocket）](#场景-c后续登录审批websocket)
9. [错误处理指南](#-错误处理指南)
10. [安全注意事项](#-安全注意事项)
11. [附录：测试端点](#-附录测试端点)

---

## 📖 系统概述

这是一套 **App 审批式双因素认证系统**，作为 TOTP 的替代方案。当用户执行敏感操作时，后端会生成一个 **3 选 1 挑战**发送到已绑定 App，用户选择正确数字后操作才能继续。

### 核心架构

```
┌─────────────────┐         REST API          ┌──────────────┐
│   Android App   │ ◄──────────────────────►  │   Backend    │
│                 │   (绑定设备/获取 Token)    │   Server     │
│                 │                            │              │
│                 │   WebSocket (加密通道)      │              │
│                 │ ◄──────────────────────►  │              │
└─────────────────┘                            └──────────────┘
     │                                                 │
     │   DH Key Exchange                                │
     │   AES/SM4-GCM Encryption                         │
     │   3-Choice Challenge                             │
     └─────────────────────────────────────────────────┘
```

### 安全设计原则

| 原则 | 说明 |
|------|------|
| **端到端加密** | WebSocket 通信经过 DH 密钥交换后，所有挑战数据使用 AES-GCM/SM4-GCM 加密 |
| **2048-bit DH** | 使用 RFC 3526 的 2048-bit MODP Group，不使用 DH2/DH5 |
| **一次性挑战** | 挑战 ID 使用后即删除，不可重放 |
| **挑战超时** | 挑战有效期 60 秒，过期自动失效 |
| **配对码超时** | 配对码有效期 5 分钟，一次性使用 |

---

## 🛠 环境要求

| 项目 | 版本要求 |
|------|----------|
| Android SDK | API 26+ (Android 8.0) |
| Java | Java 8+ |
| WebSocket | okhttp3 或 Java-WebSocket |
| 密码学 | Java Cryptography Extension (JCE) 内置支持 DH/AES |

---

## 📦 依赖库配置

### build.gradle (Module: app)

```groovy
dependencies {
    // WebSocket (使用 OkHttp)
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    
    // Gson (JSON 解析)
    implementation 'com.google.code.gson:gson:2.10.1'
    
    // Retrofit (REST API 调用)
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
    
    // Bouncy Castle (如果需要 SM4 国密支持)
    implementation 'org.bouncycastle:bcprov-jdk18on:1.78.1'
}
```

---

## 🌐 REST API 对接

### 4.1 密码绑定

**App 端使用用户名密码绑定设备**，此接口不需要 JWT Token（公开接口）。

#### 请求

```
POST /api/auth/app/bind
Content-Type: application/json

{
    "username": "leoadmin",
    "password": "your_password",
    "deviceId": "生成的设备唯一ID",
    "deviceName": "Pixel 7",
    "deviceType": "ANDROID"
}
```

#### 响应

```json
{
    "code": 200,
    "message": "绑定成功",
    "data": {
        "token": "eyJhbGciOiJIUzI1NiJ9...",
        "deviceId": "生成的设备唯一ID"
    }
}
```

#### 🔑 deviceId 生成规则

`deviceId` 由客户端生成，需要确保唯一性和持久性：

```kotlin
import android.provider.Settings
import java.util.UUID

fun generateDeviceId(context: Context): String {
    // 使用 Android ID + UUID 后缀确保唯一
    val androidId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    )
    return "ANDROID_${androidId}_${UUID.randomUUID().toString().take(8)}"
}
```

#### 💾 持久化存储

绑定成功后，需要安全存储以下数据：

| 数据 | 存储方式 | 说明 |
|------|----------|------|
| `token` | EncryptedSharedPreferences | JWT Token，用于 WebSocket 认证 |
| `deviceId` | SharedPreferences | 设备标识 |
| `cipherPref` | SharedPreferences | 首选加密算法（从服务端响应推断） |

```kotlin
// 使用 EncryptedSharedPreferences 安全存储 Token
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val sharedPreferences = EncryptedSharedPreferences.create(
    context,
    "app_auth_prefs",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

sharedPreferences.edit()
    .putString("jwt_token", token)
    .putString("device_id", deviceId)
    .apply()
```

---

### 4.2 扫码绑定

**流程：** 网页端生成 6 位配对码 → 用户在 App 输入配对码 → 完成绑定

> ⚠️ 扫码绑定需要用户 **先通过网页登录** 获取配对码，App 端作为辅助设备绑定。

#### 请求

```
POST /api/auth/app/bind/qrcode
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>

{
    "pairCode": "583294",
    "deviceId": "生成的设备唯一ID",
    "deviceName": "Pixel 7"
}
```

#### 响应

```json
{
    "code": 200,
    "message": "绑定成功",
    "data": {
        "deviceId": "生成的设备唯一ID"
    }
}
```

> 扫码绑定接口需要 JWT Token。配对码由网页端生成（见 4.3），App 端扫描二维码或手动输入配对码。

---

### 4.3 生成配对码

网页端调用此接口生成 6 位配对码，供 App 扫码绑定使用。

#### 请求

```
POST /api/auth/app/generate-pair-code
Authorization: Bearer <JWT_TOKEN>
```

#### 响应

```json
{
    "code": 200,
    "data": {
        "pairCode": "583294",
        "expiresIn": 300
    }
}
```

| 字段 | 说明 |
|------|------|
| `pairCode` | 6 位随机数字配对码 |
| `expiresIn` | 有效期（秒），默认 300 秒 |

---

### 4.4 设备管理

#### 获取设备列表

```
GET /api/auth/app/devices
Authorization: Bearer <JWT_TOKEN>
```

**响应：**
```json
{
    "code": 200,
    "data": [
        {
            "id": 2054000000000001001,
            "userId": 1,
            "deviceId": "ANDROID_abc123_def456",
            "deviceName": "Pixel 7",
            "deviceType": "ANDROID",
            "publicKey": null,
            "cipherPref": "AES-256-GCM",
            "lastActiveAt": "2026-06-13T12:00:00",
            "createdAt": "2026-06-12T10:30:00"
        }
    ]
}
```

#### 检查绑定状态

```
GET /api/auth/app/status
Authorization: Bearer <JWT_TOKEN>
```

**响应：**
```json
{
    "code": 200,
    "data": {
        "hasBoundDevice": true
    }
}
```

#### 解绑设备

```
DELETE /api/auth/app/devices/{deviceId}
Authorization: Bearer <JWT_TOKEN>
```

**响应：**
```json
{
    "code": 200,
    "message": "设备已解绑",
    "data": null
}
```

---

## 🔐 密码学实现

### 5.1 DH 密钥交换（2048-bit）

使用 **RFC 3526 第 2048-bit MODP Group**，不使用 DH2/DH5。

#### DH 参数

```kotlin
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.spec.DHParameterSpec
import java.math.BigInteger

class DhParameters {
    companion object {
        // RFC 3526 2048-bit MODP Group (十六进制)
        val MODP_P = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08" +
            "8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B" +
            "302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9" +
            "A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6" +
            "49286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8" +
            "FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C" +
            "180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D" +
            "04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7D" +
            "B3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D226" +
            "1AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
            "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFC" +
            "E0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16
        )

        // 生成元
        val MODP_G = BigInteger.valueOf(2)
    }
}
```

#### 生成密钥对

```kotlin
import java.security.*
import javax.crypto.KeyAgreement
import java.util.Base64

fun generateDhKeyPair(): KeyPair {
    val dhParams = DHParameterSpec(DhParameters.MODP_P, DhParameters.MODP_G)
    val keyPairGenerator = KeyPairGenerator.getInstance("DH")
    keyPairGenerator.initialize(dhParams, SecureRandom())
    return keyPairGenerator.generateKeyPair()
}

// 使用示例
val keyPair = generateDhKeyPair()
val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
val privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)

// 保存私钥用于后续计算共享密钥
// 私钥不能泄露！
savePrivateKeyLocally(privateKeyBase64)
```

#### 计算共享密钥

```kotlin
import java.security.spec.X509EncodedKeySpec

fun computeSharedSecret(
    privateKey: PrivateKey,
    serverPublicKeyBase64: String
): ByteArray {
    // 解码服务端公钥
    val serverPublicKeyBytes = Base64.getDecoder().decode(serverPublicKeyBase64)
    val keyFactory = KeyFactory.getInstance("DH")
    val serverPublicKey = keyFactory.generatePublic(
        X509EncodedKeySpec(serverPublicKeyBytes)
    )

    // 计算共享密钥
    val keyAgreement = KeyAgreement.getInstance("DH")
    keyAgreement.init(privateKey)
    keyAgreement.doPhase(serverPublicKey, true)

    return keyAgreement.generateSecret()
}
```

> **重要：** DH 产生的共享密钥是 2048 位的原始字节。**不要直接使用**，需要使用 SHA-256 派生为加密密钥（见 5.4）。

---

### 5.2 AES-GCM 加解密

#### AES 密钥派生

```kotlin
import java.security.MessageDigest

fun deriveAesKey(sharedSecret: ByteArray, keySize: Int): ByteArray {
    // keySize: 128, 192, 256 (bits)
    val hash = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
    val keyBytes = keySize / 8
    return hash.copyOfRange(0, minOf(keyBytes, hash.size))
}

// 使用示例：
// AES-128: deriveAesKey(sharedSecret, 128)  → 16 bytes
// AES-192: deriveAesKey(sharedSecret, 192)  → 24 bytes
// AES-256: deriveAesKey(sharedSecret, 256)  → 32 bytes （推荐）
```

#### AES-GCM 加密

```kotlin
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesGcmResult(val iv: String, val ciphertext: String)

fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray): AesGcmResult {
    // 1. 生成随机 IV (12 字节)
    val iv = ByteArray(12)
    SecureRandom().nextBytes(iv)

    // 2. 初始化 Cipher
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(key, "AES")
    val gcmSpec = GCMParameterSpec(128, iv)  // 128-bit GCM Tag
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

    // 3. 加密
    val ciphertext = cipher.doFinal(plaintext)

    return AesGcmResult(
        iv = Base64.getEncoder().encodeToString(iv),
        ciphertext = Base64.getEncoder().encodeToString(ciphertext)
    )
}

fun aesGcmDecrypt(ivBase64: String, ciphertextBase64: String, key: ByteArray): ByteArray {
    val iv = Base64.getDecoder().decode(ivBase64)
    val ciphertext = Base64.getDecoder().decode(ciphertextBase64)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(key, "AES")
    val gcmSpec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

    return cipher.doFinal(ciphertext)
}
```

| 参数 | 值 | 说明 |
|------|-----|------|
| 加密模式 | GCM | 认证加密模式（同时保证机密性和完整性） |
| IV 长度 | 12 字节 (96-bit) | 推荐值 |
| Tag 长度 | 128-bit | GCM 认证标签 |
| 填充 | NoPadding | GCM 不需要填充 |

---

### 5.3 SM4-GCM 加解密

> 国密算法支持，需要 Bouncy Castle 库。

```kotlin
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

// 首次使用前注册 Provider
fun initBouncyCastle() {
    if (Security.getProvider("BC") == null) {
        Security.addProvider(BouncyCastleProvider())
    }
}

fun sm4GcmEncrypt(plaintext: ByteArray, key: ByteArray): AesGcmResult {
    initBouncyCastle()

    val iv = ByteArray(12)
    SecureRandom().nextBytes(iv)

    val cipher = Cipher.getInstance("SM4/GCM/NoPadding", "BC")
    val keySpec = SecretKeySpec(key, "SM4")
    val gcmSpec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

    val ciphertext = cipher.doFinal(plaintext)

    return AesGcmResult(
        iv = Base64.getEncoder().encodeToString(iv),
        ciphertext = Base64.getEncoder().encodeToString(ciphertext)
    )
}

fun sm4GcmDecrypt(ivBase64: String, ciphertextBase64: String, key: ByteArray): ByteArray {
    initBouncyCastle()

    val iv = Base64.getDecoder().decode(ivBase64)
    val ciphertext = Base64.getDecoder().decode(ciphertextBase64)

    val cipher = Cipher.getInstance("SM4/GCM/NoPadding", "BC")
    val keySpec = SecretKeySpec(key, "SM4")
    val gcmSpec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

    return cipher.doFinal(ciphertext)
}
```

| 参数 | 值 | 说明 |
|------|-----|------|
| 密钥长度 | 128-bit (16 bytes) | SM4 固定密钥长度 |
| 加密模式 | GCM | 认证加密模式 |
| IV 长度 | 12 字节 | 推荐值 |
| Provider | Bouncy Castle | `bcprov-jdk18on` |

---

### 5.4 密钥派生（SHA-256）

DH 交换产生的共享密钥（2048 位） → SHA-256 → 实际加密密钥

```
SharedSecret (2048-bit)
       │
       ▼
   SHA-256
       │
       ▼
   Hash (256-bit / 32 bytes)
       │
       ├── 取前 16 bytes → AES-128 密钥
       ├── 取前 24 bytes → AES-192 密钥
       └── 取前 32 bytes → AES-256 密钥 (推荐)
```

---

## 🔌 WebSocket 通信协议

### 6.1 连接建立

**WebSocket 端点：**

```
ws://<服务器地址>/ws/app/auth
```

> 生产环境使用 `wss://` 协议。

**连接后**，客户端立即发送 `bind` 消息进行身份认证。

### 6.2 完整协议流程

```
客户端 (App)                             服务端 (Backend)
    │                                         │
    │  ──[bind]──────────────────────────►    │  1. 发送 JWT Token + DeviceId
    │                                         │
    │  ◄──[bind_ack]─────────────────────     │  2. 身份认证通过
    │                                         │
    │  ◄──[dh_init]──────────────────────     │  3. 服务端发送 DH 公钥
    │                                         │
    │  ──[dh_response]───────────────────►    │  4. 客户端发送 DH 公钥
    │                                         │  5. 双方计算共享密钥
    │                                         │
    │  ◄──[dh_ready]─────────────────────     │  6. 加密通道建立完成
    │                                         │
    │  ◄──[challenge]────────────────────     │  7. 服务端发送 3 个数字挑战
    │                                         │
    │  ──[challenge_response]────────────►    │  8. 用户选择数字，回复结果
    │                                         │
    │  ◄──[auth_result]──────────────────     │  9. 返回审批结果
    │                                         │
    │  ◄──(加密通道保持，等待下一次挑战)          │
    │                                         │
    │  ──[ping]──────────────────────────►    │  10. 心跳（维持连接）
    │  ◄──[pong]─────────────────────────     │
```

### 6.3 消息格式详解

所有消息均为 JSON 格式。

#### ① bind — 身份认证

```json
{
    "type": "bind",
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "deviceId": "ANDROID_abc123_def456"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `type` | ✓ | 固定为 `"bind"` |
| `token` | ✓ | 密码绑定返回的 JWT Token |
| `deviceId` | ✓ | 绑定时的设备 ID |

---

#### ② bind_ack — 绑定确认

```json
{
    "type": "bind_ack",
    "status": "ok",
    "userId": 1
}
```

| 字段 | 说明 |
|------|------|
| `status` | `"ok"`=成功，其他值=失败 |
| `userId` | 用户 ID |

---

#### ③ dh_init — 服务端 DH 公钥

```json
{
    "type": "dh_init",
    "serverPublicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
}
```

| 字段 | 说明 |
|------|------|
| `serverPublicKey` | 服务端 DH 公钥（X.509 编码，Base64） |

**客户端处理逻辑：**

1. 读取 `serverPublicKey`，解码为 PublicKey 对象
2. 使用本地保存的 DH 私钥，计算共享密钥
3. 从共享密钥派生 AES 密钥
4. 生成自己的 DH 公钥
5. 发送 `dh_response` 给服务端

---

#### ④ dh_response — 客户端 DH 公钥

```json
{
    "type": "dh_response",
    "clientPublicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
}
```

| 字段 | 说明 |
|------|------|
| `clientPublicKey` | 客户端 DH 公钥（X.509 编码，Base64） |

---

#### ⑤ dh_ready — 加密通道就绪

```json
{
    "type": "dh_ready",
    "cipher": "AES-256-GCM"
}
```

| 字段 | 说明 |
|------|------|
| `cipher` | 服务端选择的加密算法 |

**可能的值：** `"AES-128-GCM"`、`"AES-192-GCM"`、`"AES-256-GCM"`、`"SM4-GCM"`

> 收到此消息后，WebSocket 加密通道建立完成。后续的 `challenge` 消息**不会加密**（因为挑战数据本身不需要保密，只需确认用户看到的是正确的数字）。但如果你希望对所有通信加密，可以在后续消息体中加密关键字段。

---

#### ⑥ challenge — 3 选 1 挑战

```json
{
    "type": "challenge",
    "challengeId": "a1b2c3d4e5f6...",
    "numbers": [23, 67, 42],
    "expiresAt": 1778820000
}
```

| 字段 | 说明 |
|------|------|
| `challengeId` | 挑战唯一标识（UUID，无连字符） |
| `numbers` | 3 个 0-99 的随机整数 |
| `expiresAt` | 过期时间（Unix 时间戳，秒） |

**App 端处理逻辑：**

① 收到此消息后，弹出通知/对话框，显示 3 个数字
② 等待用户选择一个数字
③ 用户选择后，发送 `challenge_response`

---

#### ⑦ challenge_response — 挑战结果回复

```json
{
    "type": "challenge_response",
    "challengeId": "a1b2c3d4e5f6...",
    "selectedNumber": 67
}
```

| 字段 | 说明 |
|------|------|
| `challengeId` | 收到的挑战 ID（原样返回） |
| `selectedNumber` | 用户选择的数字（必须为 3 个数字之一） |

---

#### ⑧ auth_result — 认证结果

```json
// 批准
{
    "type": "auth_result",
    "status": "approved"
}

// 拒绝
{
    "type": "auth_result",
    "status": "rejected",
    "reason": "数字选择错误"
}

// 过期
{
    "type": "auth_result",
    "status": "expired",
    "reason": "挑战已过期"
}
```

| 状态 | 说明 |
|------|------|
| `approved` | ✅ 用户选择正确，审批通过 |
| `rejected` | ❌ 用户选择错误，审批拒绝 |
| `expired` | ⏰ 挑战超时（60 秒），需要发起新的挑战 |

---

#### ⑨ ping/pong — 心跳

```json
// 客户端发送
{ "type": "ping" }

// 服务端回复
{ "type": "pong" }
```

> 建议每 30 秒发送一次心跳，保持连接活跃。

---

#### ⑩ error — 错误消息

```json
{
    "type": "error",
    "message": "Token 无效"
}
```

---

## 🎯 3 选 1 挑战机制

这是本系统的核心安全机制。

### 工作流程

```
1. 用户尝试执行敏感操作（如删除文章）
       │
2. 后端生成 challenge
   ┌─ numbers: [23, 67, 42]
   │  correct: 67  (答案，仅服务端知道)
   └─ challengeId: "a1b2c3..."
       │
3. WebSocket 发送 challenge 到 App
       │
4. App 弹出通知：
   ┌──────────────────┐
   │  请选择一个数字   │
   │                   │
   │   [23]  [67] [42] │
   │                   │
   │     5分钟内有效    │
   └──────────────────┘
       │
5. 用户点击 "67"
       │
6. App 发送 challenge_response
       │
7. 后端验证：
   - 挑战是否存在？→ 不存则返回 expired
   - 用户身份匹配？→ 不匹配则返回 rejected
   - 数字是否正确？→ 正确则 approved
       │
8. 用户操作被批准/拒绝
```

### Android 端实现要点

```kotlin
// 显示挑战通知
private fun showChallengeNotification(challenge: ChallengeMessage) {
    val numbers = challenge.numbers  // [23, 67, 42]
    
    // 方案一：使用系统通知 + PendingIntent
    // 创建包含 3 个按钮的通知
    for (i in numbers.indices) {
        val intent = Intent(this, ChallengeActivity::class.java).apply {
            putExtra("challengeId", challenge.challengeId)
            putExtra("selectedNumber", numbers[i])
        }
        // 每个按钮绑定不同的 PendingIntent
    }
    
    // 方案二：启动 Activity 或 Dialog
    val dialog = AlertDialog.Builder(this)
        .setTitle("请选择一个数字")
        // 添加 3 个按钮
        .setPositiveButton(numbers[0].toString()) { _, _ ->
            sendChallengeResponse(challenge.challengeId, numbers[0])
        }
        .setNeutralButton(numbers[1].toString()) { _, _ ->
            sendChallengeResponse(challenge.challengeId, numbers[1])
        }
        .setNegativeButton(numbers[2].toString()) { _, _ ->
            sendChallengeResponse(challenge.challengeId, numbers[2])
        }
        .create()
    dialog.show()
}
```

---

## 📱 完整对接流程

### 场景 A：App 首次使用（密码绑定）

```kotlin
// Step 1: 生成本地 deviceId
val deviceId = generateDeviceId(context)

// Step 2: 调用 REST API 绑定设备
val response = apiService.bindWithPassword(
    BindPasswordRequest(
        username = "leoadmin",
        password = "your_password",
        deviceId = deviceId,
        deviceName = "Pixel 7",
        deviceType = "ANDROID"
    )
)

// Step 3: 安全存储 Token 和 DeviceId
saveCredentials(response.token, response.deviceId)

// Step 4: 连接 WebSocket
connectWebSocket(response.token, response.deviceId)
```

### 场景 B：App 首次使用（扫码绑定）

```kotlin
// Step 1: 在网页端登录，生成配对码
// (用户操作网页，获取 6 位配对码如 "583294")

// Step 2: 在 App 中输入配对码
val pairCode = "583294"  // 用户输入

// Step 3: 用户登录获取 JWT Token（使用已有登录接口）
val loginResponse = loginWithUsernamePassword(username, password)
val jwtToken = loginResponse.token

// Step 4: 调用扫码绑定 API
val response = apiService.bindWithQrCode(
    "Bearer $jwtToken",
    BindQrCodeRequest(
        pairCode = pairCode,
        deviceId = deviceId,
        deviceName = "Pixel 7"
    )
)

// Step 5: 连接 WebSocket
connectWebSocket(jwtToken, response.deviceId)
```

### 场景 C：后续登录/操作审批（WebSocket）

```kotlin
// WebSocket 连接成功后，等待挑战
class AppWebSocketClient(
    private val token: String,
    private val deviceId: String,
    private val onChallenge: (ChallengeMessage) -> Unit,
    private val onResult: (AuthResultMessage) -> Unit
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var dhKeyPair: KeyPair? = null
    private var aesKey: ByteArray? = null
    private var cipherAlgo: String = "AES-256-GCM"

    fun connect() {
        val request = Request.Builder()
            .url("ws://10.0.2.2:8080/ws/app/auth")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 发送 bind 消息
                sendMessage(mapOf(
                    "type" to "bind",
                    "token" to token,
                    "deviceId" to deviceId
                ))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
        })
    }

    private fun handleMessage(text: String) {
        val json = Gson().fromJson(text, JsonObject::class.java)
        when (json.get("type").asString) {
            "bind_ack" -> onBindAck()
            "dh_init" -> handleDhInit(json)
            "dh_ready" -> handleDhReady(json)
            "challenge" -> onChallenge(parseChallenge(json))
            "auth_result" -> onResult(parseResult(json))
            "pong" -> { /* 心跳回复，忽略 */ }
            "error" -> handleError(json)
        }
    }

    private fun handleDhInit(json: JsonObject) {
        // 1. 生成 DH 密钥对
        dhKeyPair = CryptoUtils.generateDhKeyPair()

        // 2. 计算共享密钥
        val serverPublicKey = json.get("serverPublicKey").asString
        val sharedSecret = CryptoUtils.computeSharedSecret(
            dhKeyPair!!.private, serverPublicKey
        )

        // 3. 派生 AES 密钥
        aesKey = CryptoUtils.deriveAesKey(sharedSecret, 256)  // AES-256

        // 4. 发送客户端公钥
        val clientPublicKey = Base64.getEncoder()
            .encodeToString(dhKeyPair!!.public.encoded)
        sendMessage(mapOf(
            "type" to "dh_response",
            "clientPublicKey" to clientPublicKey
        ))
    }

    private fun onBindAck() {
        // WebSocket 绑定成功
    }

    private fun handleDhReady(json: JsonObject) {
        cipherAlgo = json.get("cipher").asString
        // 加密通道已就绪
    }

    private fun sendMessage(data: Map<String, Any>) {
        val json = Gson().toJson(data)
        webSocket?.send(json)
    }

    fun sendChallengeResponse(challengeId: String, selectedNumber: Int) {
        sendMessage(mapOf(
            "type" to "challenge_response",
            "challengeId" to challengeId,
            "selectedNumber" to selectedNumber
        ))
    }

    fun disconnect() {
        webSocket?.close(1000, "用户退出")
    }
}
```

---

## ⚠️ 错误处理指南

### REST API 错误

| HTTP 状态码 | 错误消息 | 处理方式 |
|------------|----------|----------|
| 400 | "用户名不能为空" | 提示用户填写必要信息 |
| 400 | "设备 ID 不能为空" | 检查 deviceId 生成逻辑 |
| 400 | "该设备已被绑定" | 提示用户该设备已绑定，可在网页端解绑 |
| 400 | "用户名或密码错误" | 提示用户重新输入 |
| 400 | "配对码已过期" | 提示用户重新获取配对码 |
| 400 | "配对码无效" | 提示用户检查配对码 |
| 401 | Token 无效或过期 | 重新登录获取新 Token |

### WebSocket 错误

| type | message | 处理方式 |
|------|---------|----------|
| error | "缺少 token 或 deviceId" | 检查 bind 消息格式 |
| error | "Token 无效" | Token 过期，重新登录 |
| error | "Token 验证失败" | Token 格式错误，检查生成逻辑 |
| error | "设备未绑定" | 设备未绑定，先调用 REST API 绑定 |
| error | "DH 会话未初始化" | 连接顺序错误，重新建立连接 |
| error | "挑战参数无效" | 检查 challengeId 和 selectedNumber |
| error | "会话未绑定" | 重新发送 bind 消息 |

### 网络重连策略

```kotlin
class ReconnectStrategy {
    private var retryCount = 0
    private val maxRetries = 5
    private val baseDelay = 1000L  // 1 秒

    fun getNextDelay(): Long {
        // 指数退避：1s, 2s, 4s, 8s, 16s
        val delay = baseDelay * (1 shl retryCount)
        retryCount++
        return minOf(delay, 30000L)  // 最大 30 秒
    }

    fun reset() {
        retryCount = 0
    }

    fun shouldRetry(): Boolean = retryCount < maxRetries
}
```

---

## 🔒 安全注意事项

### ⭐ 必须遵守的安全规则

1. **DH 私钥禁止上传或发送**
   - DH 私钥仅存储在本地
   - 每次 WebSocket 连接可重新生成密钥对

2. **Token 安全存储**
   - 使用 Android 的 `EncryptedSharedPreferences`
   - 不要存储在 SharedPreferences 明文或外部存储

3. **验证挑战数字**
   - 用户选择的数字必须是挑战中包含的 3 个之一
   - 后端会验证数字正确性

4. **一次性挑战**
   - 每个 challengeId 只能使用一次
   - 如果挑战过期（60 秒），需要发起新的挑战

5. **HTTPS/WSS**
   - 生产环境必须使用 HTTPS 和 WSS
   - 校验 SSL 证书

### ⭐ 建议的安全措施

6. **WebSocket 心跳**
   - 每 30 秒发送 ping
   - 检测连接断开后自动重连

7. **设备标识持久化**
   - deviceId 在 App 生命周期内保持不变
   - App 卸载重装后应重新生成

8. **对话框安全**
   - 挑战通知显示时，应展示具体操作的描述
   - 防止用户误点

9. **本地日志清理**
   - 不在 logcat 中打印敏感信息（Token、密钥）
   - 发布版应禁用调试日志

---

## 📋 附录：测试端点

### 开发环境配置

| 项目 | 地址 |
|------|------|
| REST API 基准 URL | `http://10.0.2.2:8080` |
| WebSocket 端点 | `ws://10.0.2.2:8080/ws/app/auth` |

> `10.0.2.2` 是 Android 模拟器中访问主机的地址。

### 测试流程

```
1. 启动后端服务 (Spring Boot)
2. Android 模拟器连接
3. 调用 POST /api/auth/app/bind 绑定设备
4. 连接 WebSocket: ws://10.0.2.2:8080/ws/app/auth
5. 发送 bind 消息
6. 验证 dh_init → dh_response → dh_ready 流程
7. 等待 challenge 消息
8. 在 App 中选择数字并回复
9. 验证 auth_result
```

### Websocket 调试工具

推荐使用以下工具测试 WebSocket 通信：

- **Postman** — 支持 WebSocket 请求
- **wscat** — 命令行 WebSocket 客户端
- **Android Studio Network Inspector** — 检查网络流量

---

## 📝 附录：消息序列图（详细）

```
App                                Backend
 │                                    │
 ├─ WebSocket Connect ──────────────► │
 │                                    │
 ├─ {"type":"bind",                  ►│
 │    "token":"eyJ...",              │
 │    "deviceId":"ANDROID_..."}      │
 │                                    │
 │  ◄─ {"type":"bind_ack",          ─┤
 │       "status":"ok",              │
 │       "userId":1}                 │
 │                                    │
 │  ◄─ {"type":"dh_init",          ─┤
 │       "serverPublicKey":"MIIB..."}│
 │                                    │
 │  (generate DH key pair)           │
 │  (compute shared secret)          │
 │  (derive AES key)                 │
 │                                    │
 ├─ {"type":"dh_response",          ►│
 │    "clientPublicKey":"MIIB..."}   │
 │                                    │
 │  ◄─ {"type":"dh_ready",         ─┤
 │       "cipher":"AES-256-GCM"}     │
 │                                    │
 │  ◄─ {"type":"challenge",        ─┤
 │       "challengeId":"abc...",     │
 │       "numbers":[23,67,42],       │
 │       "expiresAt":1778820000}     │
 │                                    │
 │  (显示对话框，用户选择 67)          │
 │                                    │
 ├─ {"type":"challenge_response",   ►│
 │    "challengeId":"abc...",        │
 │    "selectedNumber":67}           │
 │                                    │
 │  ◄─ {"type":"auth_result",      ─┤
 │       "status":"approved"}        │
 │                                    │
 │  (等待下一次挑战...)                │
 │                                    │
 ├─ {"type":"ping"} ───────────────► │
 │  ◄─ {"type":"pong"} ──────────── ┤ │
 │                                    │
```

---
> **本文档对应后端版本:** Commit `7b89d08`  
> **如有疑问请联系后端开发者**
