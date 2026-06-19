# MyAuthenticator — 后端 API 开发指导文档

> **版本:** v1.0  
> **更新日期:** 2026-06-14  
> **对应客户端:** MyAuthenticator Android App  
> **目的:** 指导后端开发者实现与 Android 客户端对接的所有 API 接口

---

## 📋 目录

1. [通用约定](#1-通用约定)
2. [REST API 接口](#2-rest-api-接口)
   - [2.1 密码绑定](#21-密码绑定)
   - [2.2 扫码绑定](#22-扫码绑定)
   - [2.3 生成配对码](#23-生成配对码)
   - [2.4 检查绑定状态](#24-检查绑定状态)
   - [2.5 获取设备列表](#25-获取设备列表)
   - [2.6 解绑设备](#26-解绑设备)
   - [2.7 获取用户个人信息](#27-获取用户个人信息-计划新增)
   - [2.8 获取认证历史](#28-获取认证历史-计划新增)
3. [WebSocket 通信协议](#3-websocket-通信协议)
   - [3.1 协议流程概览](#31-协议流程概览)
   - [3.2 消息格式详解](#32-消息格式详解)
   - [3.3 心跳机制](#33-心跳机制)
   - [3.4 断线重连建议](#34-断线重连建议)
4. [密码学协议详解](#4-密码学协议详解)
   - [4.1 DH 密钥交换](#41-dh-密钥交换)
   - [4.2 密钥派生 (KDF)](#42-密钥派生-kdf)
   - [4.3 AES-256-GCM 加解密](#43-aes-256-gcm-加解密)
   - [4.4 SM4-GCM 加解密](#44-sm4-gcm-加解密)
   - [4.5 加密算法协商](#45-加密算法协商)
5. [安全增强措施（TODO 第 5 项）](#5-安全增强措施)
   - [5.1 客户端时间戳签名](#51-客户端时间戳签名)
   - [5.2 登录失败防护](#52-登录失败防护)
   - [5.3 Challenge 防暴力](#53-challenge-防暴力)
   - [5.4 密钥轮换](#54-密钥轮换)
   - [5.5 通信安全](#55-通信安全)
6. [开发环境与测试](#6-开发环境与测试)
7. [附录：数据库模型建议](#7-附录数据库模型建议)

---

## 1. 通用约定

### 1.1 基准 URL

| 环境 | REST API URL | WebSocket URL |
|------|-------------|---------------|
| **生产环境** | `https://leo-blog.top` | `wss://leo-blog.top/ws/app/auth` |
| **开发环境** | `http://10.0.2.2:8080` | `ws://10.0.2.2:8080/ws/app/auth` |

> 客户端代码位置：`NetworkConfig.kt`

### 1.2 认证方式

需要认证的接口使用 HTTP 请求头携带 JWT Token：

```
Authorization: Bearer <jwt_token>
```

#### JWT Token 建议载荷

```json
{
  "sub": "user_id",
  "deviceId": "ANDROID_xxx_yyy",
  "iat": 1718380000,
  "exp": 1718383600
}
```

> JWT 过期时间建议 7-30 天，客户端不会主动刷新 Token，过期后需重新登录。

### 1.3 统一响应格式

所有 REST API 响应必须使用以下统一包装：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `code` | int | 是 | 业务状态码，**200 表示成功**，非 200 表示失败 |
| `message` | string | 否 | 提示信息，失败时描述错误原因 |
| `data` | T | 否 | 业务数据载荷 |

> 客户端代码位置：`ApiResponse.kt`
>
> ```kotlin
> data class ApiResponse<T>(
>     val code: Int,
>     val message: String? = null,
>     val data: T? = null
> )
> ```

### 1.4 HTTP 状态码约定

| HTTP 状态码 | 含义 | 客户端处理方式 |
|-------------|------|---------------|
| 200 | 成功 | 检查 `body.code` 是否为 200 |
| 400 | 请求参数错误 | 显示 `body.message` 给用户 |
| 401 | 未授权（Token 无效/过期） | 清除 Token，跳转登录页 |
| 500 | 服务器内部错误 | 显示"服务器错误" |

### 1.5 接口概览

| # | 方法 | 路径 | 需 Token | 实现状态 |
|---|------|------|----------|----------|
| 2.1 | POST | `/api/auth/app/bind` | ❌ | ✅ 已实现 |
| 2.2 | POST | `/api/auth/app/bind/qrcode` | ✅ | ✅ 已实现 |
| 2.3 | POST | `/api/auth/app/generate-pair-code` | ✅ | ✅ 已实现 |
| 2.4 | GET | `/api/auth/app/status` | ✅ | ✅ 已实现 |
| 2.5 | GET | `/api/auth/app/devices` | ✅ | ✅ 已实现 |
| 2.6 | DELETE | `/api/auth/app/devices/{deviceId}` | ✅ | ✅ 已实现 |
| 2.7 | GET | `/api/user/profile` | ✅ | ✅ 已实现 |
| 2.8 | GET | `/api/auth/history` | ✅ | ✅ 已实现 |

---

## 2. REST API 接口

### 2.1 密码绑定

> **用途：** 用户通过用户名+密码绑定设备。此接口**不需要 JWT Token**，是公开接口。

```
POST /api/auth/app/bind
Content-Type: application/json
```

#### 请求体

```json
{
  "username": "leoadmin",
  "password": "plain_password",
  "deviceId": "ANDROID_abc12345_def67890",
  "deviceName": "Xiaomi 14",
  "deviceType": "ANDROID"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `username` | string | 是 | 用户名 |
| `password` | string | 是 | 密码明文（HTTPS 传输，建议服务端做 bcrypt 比對） |
| `deviceId` | string | 是 | 客户端生成的设备唯一标识（格式见下方） |
| `deviceName` | string | 是 | 设备显示名称，客户端默认使用 `Build.MODEL` |
| `deviceType` | string | 否 | 设备类型，默认 `"ANDROID"` |

#### deviceId 生成规则（客户端）

```kotlin
"ANDROID_${Settings.Secure.ANDROID_ID}_${UUID.randomUUID().toString().take(8)}"
```

示例：`ANDROID_abc12345_def67890`

#### 响应

```json
{
  "code": 200,
  "message": "绑定成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "deviceId": "ANDROID_abc12345_def67890"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `token` | string | 是 | JWT Token，后续所有请求使用此 Token |
| `deviceId` | string | 是 | 服务端确认或生成的设备 ID（建议原样返回客户端传入的 deviceId） |

#### 客户端处理流程

```kotlin
val response = RetrofitClient.apiService.bindWithPassword(request)
if (response.isSuccessful && body?.code == 200) {
    secureStorage.saveToken(body.data.token)
    secureStorage.saveDeviceId(body.data.deviceId)
    secureStorage.saveUsername(username)
    // 跳转到 MainActivity
}
```

> 客户端代码位置：`ApiService.bindWithPassword()` → `LoginActivity.login()`

#### 后端处理要点

1. 验证用户名密码（建议 bcrypt 哈希比对）
2. 检查 `deviceId` 是否已被其他用户绑定：
   - 如果已绑定且属于当前用户 → 更新绑定信息，返回新 Token
   - 如果已绑定但属于不同用户 → 返回错误 `400 + "该设备已被绑定"`
3. 将设备信息存入数据库
4. 生成 JWT Token（建议包含 `userId`、`deviceId` 声明）
5. 返回 Token 和设备 ID

---

### 2.2 扫码绑定

> **用途：** 用户通过扫描二维码或输入配对码完成设备绑定。

```
POST /api/auth/app/bind/qrcode
Content-Type: application/json
Authorization: Bearer <jwt_token>
```

#### 请求体

```json
{
  "pairCode": "583294",
  "deviceId": "ANDROID_abc12345_def67890",
  "deviceName": "Xiaomi 14"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `pairCode` | string | 是 | 6 位配对码（通过 2.3 生成） |
| `deviceId` | string | 是 | 设备 ID |
| `deviceName` | string | 是 | 设备名称 |

#### 响应

与密码绑定相同：

```json
{
  "code": 200,
  "message": "绑定成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "deviceId": "ANDROID_abc12345_def67890"
  }
}
```

> ⚠️ 注意：此接口返回的 Token 可能与请求 Header 中的 Token 不同（扫码绑定的网页用户 Token vs App 用户 Token）。建议：
> - 配对码由已登录的网页用户生成，关联该网页用户身份
> - App 绑定成功后，应返回 App 用户对应的 Token（即绑定设备所归属的用户身份）
> - 具体业务视实现而定

#### 后端处理要点

1. 验证 JWT Token 有效性
2. 验证 `pairCode` 是否存在、是否过期、是否已被使用
3. 通过配对码关联的用户信息，创建/更新设备绑定
4. 标记配对码为已使用（一次性）
5. 返回 Token 和 DeviceId

---

### 2.3 生成配对码

> **用途：** 网页端调用此接口生成 6 位配对码，供 App 扫码绑定使用。此接口通常由**网页前端**调用，而非 Android 客户端。

```
POST /api/auth/app/generate-pair-code
Authorization: Bearer <jwt_token>
```

#### 请求体

无需请求体。

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

| 字段 | 类型 | 说明 |
|------|------|------|
| `pairCode` | string | 6 位随机数字配对码 |
| `expiresIn` | int | 有效期（秒），默认 300 秒（5 分钟） |

#### 后端处理要点

1. 验证调用者身份（JWT Token 中的用户）
2. 生成 6 位随机数字配对码
3. 关联到当前用户，设置过期时间
4. 存储到 Redis 或数据库（建议 Redis，可设置 TTL 自动过期）
5. 同一用户同一时间只允许一个有效配对码（可覆盖旧码）

---

### 2.4 检查绑定状态

> **用途：** 检查当前 Token 所代表的用户+设备组合是否已完成绑定。

```
GET /api/auth/app/status
Authorization: Bearer <jwt_token>
```

#### 响应

```json
{
  "code": 200,
  "data": {
    "hasBoundDevice": true
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `hasBoundDevice` | boolean | 当前用户是否已绑定设备 |

#### 客户端使用场景

主要用于 `MainActivity` 启动时判断是否已绑定，如果未绑定则跳转绑定页面。

---

### 2.5 获取设备列表

> **用途：** 获取当前账号下所有已绑定的设备列表。

```
GET /api/auth/app/devices
Authorization: Bearer <jwt_token>
```

#### 响应

```json
{
  "code": 200,
  "data": [
    {
      "id": 2054000000000001001,
      "userId": 1,
      "deviceId": "ANDROID_abc12345_def67890",
      "deviceName": "Xiaomi 14",
      "deviceType": "ANDROID",
      "publicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...",
      "cipherPref": "AES-256-GCM",
      "lastActiveAt": "2026-06-14T12:00:00",
      "createdAt": "2026-06-12T10:30:00"
    }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | long | 是 | 设备记录 ID |
| `userId` | int | 是 | 用户 ID |
| `deviceId` | string | 是 | 设备标识 |
| `deviceName` | string | 是 | 设备显示名称 |
| `deviceType` | string | 是 | 设备类型: `"ANDROID"` / `"IOS"` / `"WEB"` |
| `publicKey` | string | 否 | DH 公钥（如已握手过） |
| `cipherPref` | string | 否 | 偏好加密算法: `"AES-256-GCM"` / `"SM4-GCM"` |
| `lastActiveAt` | string (ISO8601) | 否 | 最后活跃时间 |
| `createdAt` | string (ISO8601) | 否 | 绑定创建时间 |

> 客户端代码位置：`DeviceInfo.kt`

---

### 2.6 解绑设备

> **用途：** 解绑指定设备。

```
DELETE /api/auth/app/devices/{deviceId}
Authorization: Bearer <jwt_token>
```

#### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `deviceId` | string | 要解绑的设备 ID |

#### 响应

```json
{
  "code": 200,
  "message": "设备已解绑",
  "data": null
}
```

#### 后端处理要点

1. 验证 JWT Token
2. 检查要解绑的设备是否属于当前用户
3. 删除设备绑定记录
4. （可选）关闭该设备的 WebSocket 连接

---

### 2.7 获取用户个人信息（计划新增）

> **用途：** 获取当前用户的详细信息，用于 Profile 页面展示。此接口按 TODO 计划在客户端 `ApiService.kt` 中新增。

```
GET /api/user/profile
Authorization: Bearer <jwt_token>
```

#### 响应建议

```json
{
  "code": 200,
  "data": {
    "userId": 10001,
    "username": "leoadmin",
    "email": "user@example.com",
    "avatarUrl": "https://example.com/avatars/10001.jpg",
    "boundAt": "2026-06-12T10:30:00",
    "lastLoginAt": "2026-06-14T12:00:00",
    "deviceCount": 2
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `userId` | int | 是 | 用户 ID |
| `username` | string | 是 | 用户名 |
| `email` | string | 否 | 邮箱地址 |
| `avatarUrl` | string | 否 | 头像 URL（客户端使用 Glide/Coil 加载） |
| `boundAt` | string (ISO8601) | 否 | 首次绑定时间 |
| `lastLoginAt` | string (ISO8601) | 否 | 最后登录时间 |
| `deviceCount` | int | 否 | 已绑定设备数量 |

#### 客户端新增接口定义（建议）

```kotlin
// 在 ApiService.kt 中新增
@GET("/api/user/profile")
suspend fun getUserProfile(
    @Header("Authorization") authorization: String
): Response<ApiResponse<UserProfile>>
```

```kotlin
// 新增数据模型
data class UserProfile(
    @SerializedName("userId") val userId: Int,
    @SerializedName("username") val username: String,
    @SerializedName("email") val email: String?,
    @SerializedName("avatarUrl") val avatarUrl: String?,
    @SerializedName("boundAt") val boundAt: String?,
    @SerializedName("lastLoginAt") val lastLoginAt: String?,
    @SerializedName("deviceCount") val deviceCount: Int?
)
```

---

### 2.8 获取认证历史（计划新增）

> **用途：** 获取当前用户的最近认证请求记录，用于 Dashboard 展示。

```
GET /api/auth/history
Authorization: Bearer <jwt_token>
```

#### 请求参数（建议）

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `page` | int | 否 | 页码，默认 1 |
| `size` | int | 否 | 每页条数，默认 20 |
| `status` | string | 否 | 筛选状态: `approved` / `rejected` / `expired` |

#### 响应建议

```json
{
  "code": 200,
  "data": {
    "total": 50,
    "page": 1,
    "size": 20,
    "records": [
      {
        "id": 1,
        "challengeId": "a1b2c3d4e5f6",
        "deviceId": "ANDROID_abc12345_def67890",
        "deviceName": "Xiaomi 14",
        "status": "approved",
        "requestedAt": "2026-06-14T20:00:00",
        "respondedAt": "2026-06-14T20:00:05"
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | long | 记录 ID |
| `challengeId` | string | 挑战 ID |
| `deviceId` | string | 响应设备 ID |
| `deviceName` | string | 设备名称 |
| `status` | string | 状态: `approved` / `rejected` / `expired` |
| `requestedAt` | string (ISO8601) | 挑战发起时间 |
| `respondedAt` | string (ISO8601) | 用户响应时间 |

---

## 3. WebSocket 通信协议

### 3.1 协议流程概览

```
客户端 (Android App)                服务端 (Backend)
        │                                     │
        │  ① 建立 WebSocket 连接              │
        ├──────────────────────────────────►   │
        │                                     │
        │  ② bind (JWT + DeviceId)            │
        ├──────────────────────────────────►   │
        │                                     │  ③ 验证 Token
        │  ④ bind_ack (ok + userId)           │
        │◄──────────────────────────────────  │
        │                                     │
        │  ⑤ dh_init (serverPublicKey)        │
        │◄──────────────────────────────────  │  ⑥ 发送服务端 DH 公钥
        │                                     │
        │  ⑦ 生成密钥对, 计算共享密钥          │
        │                                     │
        │  ⑧ dh_response (clientPublicKey)    │
        ├──────────────────────────────────►   │  ⑨ 计算共享密钥
        │                                     │
        │  ⑩ dh_ready (cipher)                │
        │◄──────────────────────────────────  │  ⑪ 加密通道就绪
        │                                     │
        │  ⑫ challenge (3 选 1)               │
        │◄──────────────────────────────────  │  ⑬ 有登录审批请求时推送
        │                                     │
        │  ⑭ 用户选择数字                      │
        │                                     │
        │  ⑮ challenge_response               │
        ├──────────────────────────────────►   │  ⑯ 验证数字
        │                                     │
        │  ⑰ auth_result                      │
        │◄──────────────────────────────────  │  ⑱ 返回结果
        │                                     │
        │  ... (等待下一次 challenge)          │
        │                                     │
        │  ping ◄────────────► pong            │  ⑲ 心跳维持
```

### 3.2 消息格式详解

所有消息均为 **JSON 格式** 的文本消息。

---

#### 3.2.1 bind — 身份认证（客户端 → 服务端）

**连接建立后客户端立即发送。**

```json
{
  "type": "bind",
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "deviceId": "ANDROID_abc12345_def67890"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定为 `"bind"` |
| `token` | string | 是 | 密码绑定获取的 JWT Token |
| `deviceId` | string | 是 | 设备 ID |

**服务端处理：**
1. 验证 JWT Token 是否有效
2. 验证 Token 中的设备 ID 与消息中的设备 ID 是否匹配
3. 如果验证通过，记录 WebSocket Session 与用户的映射关系
4. 回复 `bind_ack`

---

#### 3.2.2 bind_ack — 绑定确认（服务端 → 客户端）

**成功：**

```json
{
  "type": "bind_ack",
  "status": "ok",
  "userId": 10001
}
```

**失败：**

```json
{
  "type": "bind_ack",
  "status": "error",
  "message": "Token 无效或已过期"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定为 `"bind_ack"` |
| `status` | string | 是 | `"ok"` 成功, `"error"` 失败 |
| `userId` | int | 否 | 用户 ID（仅成功时返回） |
| `message` | string | 否 | 错误描述（仅失败时返回） |

**客户端行为：** 收到 `bind_ack` 后，调用 `secureStorage.saveUserId(userId)` 持久化 userId。

---

#### 3.2.3 dh_init — DH 密钥交换初始化（服务端 → 客户端）

**bind_ack 成功后，服务端立即发送。**

```json
{
  "type": "dh_init",
  "serverPublicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定为 `"dh_init"` |
| `serverPublicKey` | string | 是 | 服务端 DH 公钥（X.509 编码后 Base64） |

**服务端准备：**
- 服务端预生成 DH 密钥对（使用 **2048-bit MODP Group**, RFC 3526）
- 私钥保存在服务端内存或缓存中，用于后续计算共享密钥
- 公钥发送给客户端

**DH 参数（必须与客户端一致）：**

| 参数 | 值 |
|------|-----|
| 算法 | DH (Diffie-Hellman) |
| 素数 p | RFC 3526 2048-bit MODP Group (十六进制值见附录) |
| 生成元 g | 2 |
| 私钥长度 | 2048-bit |

> 客户端代码位置：`DhParameters.kt`、`CryptoUtils.kt`

---

#### 3.2.4 dh_response — DH 密钥交换响应（客户端 → 服务端）

```json
{
  "type": "dh_response",
  "clientPublicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定为 `"dh_response"` |
| `clientPublicKey` | string | 是 | 客户端 DH 公钥（X.509 编码后 Base64） |

**服务端处理：**
1. 解码客户端公钥为 `PublicKey` 对象
2. 使用服务端 DH 私钥计算共享密钥（ECDH）
3. 使用 SHA-256 对共享密钥做哈希，取前 32 字节作为 AES-256 密钥
4. 保存此密钥与当前 WebSocket Session 关联（用于后续可能的加密通信）
5. 回复 `dh_ready`

**共享密钥计算（服务端伪代码）：**

```java
KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
keyAgreement.init(serverPrivateKey);
keyAgreement.doPhase(clientPublicKey, true);
byte[] sharedSecret = keyAgreement.generateSecret();

// 派生 AES-256 密钥
MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
byte[] aesKey = Arrays.copyOf(sha256.digest(sharedSecret), 32);
```

> ⚠️ **重要：** 客户端使用 `KeyDerivation.deriveAesKey()` 方法派生密钥，请确保服务端 KDF 实现与客户端一致。

---

#### 3.2.5 dh_ready — 加密通道就绪（服务端 → 客户端）

```json
{
  "type": "dh_ready",
  "cipher": "AES-256-GCM"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定为 `"dh_ready"` |
| `cipher` | string | 是 | 选定的加密算法 |

**允许的 cipher 值：**
- `"AES-256-GCM"`（默认，推荐）
- `"SM4-GCM"`（国密）

**客户端行为：** 将 `cipher` 值保存到 `SecureStorage.saveCipherPref()`，并在 UI 上显示。

> 当前客户端实现中，challenge 消息**不加密**传输，因为挑战数字本身无需保密。加密通道主要用于未来的扩展或敏感数据保护。

---

#### 3.2.6 challenge — 认证挑战推送（服务端 → 客户端）

**当用户在其他设备发起登录等敏感操作时，服务端推送此消息到已绑定设备。**

```json
{
  "type": "challenge",
  "challengeId": "a1b2c3d4e5f6",
  "numbers": [231, 845, 672],
  "expiresAt": 1718387200000
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定为 `"challenge"` |
| `challengeId` | string | 是 | 挑战唯一 ID（UUID） |
| `numbers` | array[int] | 是 | 3 个候选数字（客户端显示供用户选择） |
| `expiresAt` | long | 是 | 过期时间戳（毫秒） |

#### 3 选 1 挑战核心机制

```
1. 用户尝试登录 (Web 或其他设备)
        │
2. 后端生成 3 个随机数字，记录正确答案
   ┌─ numbers: [231, 845, 672]
   │  correctAnswer: 845  ← 仅服务端知道
   │  challengeId: UUID
   └─ expiresAt: now + 60s
        │
3. 后端向已绑定设备推送 challenge
        │
4. Android App 弹出对话框显示 3 个数字
        │
5. 用户选择其中 1 个数字
        │
6. 客户端发送 challenge_response
        │
7. 后端验证数字是否正确
        │
8. 返回 auth_result (approved / rejected / expired)
```

**后端实现要点：**

1. 生成 `challengeId`（UUID）
2. 生成 3 个随机数字（建议范围 0-999），记录哪个是正确的
3. 存储挑战信息到缓存（Redis/内存）：
   ```json
   {
     "challengeId": "UUID",
     "userId": 10001,
     "deviceId": "target_device",
     "numbers": [231, 845, 672],
     "correctAnswer": 845,
     "expiresAt": 1718387200000,
     "used": false
   }
   ```
4. 推送到用户对应设备的 WebSocket 连接
5. 设置 TTL 与 `expiresAt` 一致

---

#### 3.2.7 challenge_response — 挑战响应（客户端 → 服务端）

```json
{
  "type": "challenge_response",
  "challengeId": "a1b2c3d4e5f6",
  "selectedNumber": 845
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定为 `"challenge_response"` |
| `challengeId` | string | 是 | 挑战 ID（原样返回） |
| `selectedNumber` | int | 是 | 用户选择的数字 |

**服务端验证逻辑：**

```
1. 检查 challengeId 是否存在 → 不存在则无效
2. 检查是否已使用 (used=true) → 已使用则拒绝（防重放）
3. 检查是否过期 (expiresAt < now) → 过期则返回 expired
4. 检查 selectedNumber 是否等于 correctAnswer
   ├── 是 → 标记挑战为已使用，返回 approved
   └── 否 → 标记挑战为已使用，返回 rejected
```

---

#### 3.2.8 auth_result — 认证结果（服务端 → 客户端）

```json
// 批准
{
  "type": "auth_result",
  "status": "approved",
  "reason": null
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

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定为 `"auth_result"` |
| `status` | string | 是 | `"approved"` / `"rejected"` / `"expired"` |
| `reason` | string | 否 | 拒绝或过期原因 |

**客户端处理：**

| status | Toast 显示 |
|--------|-----------|
| `approved` | ✅ 认证通过 |
| `rejected` | ❌ 认证被拒绝: {reason} |
| `expired` | ⏰ 挑战已过期 |

> 客户端代码位置：`ChallengeCallback.onAuthResultReceived()`

---

#### 3.2.9 error — 错误消息（服务端 → 客户端）

```json
{
  "type": "error",
  "message": "Token 无效"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定为 `"error"` |
| `message` | string | 是 | 错误描述 |

#### 服务端可能发送的错误消息

| message | 触发条件 |
|---------|----------|
| `"缺少 token 或 deviceId"` | bind 消息缺少必填字段 |
| `"Token 无效"` | JWT 解析失败或过期 |
| `"Token 验证失败"` | JWT 签名验证失败 |
| `"设备未绑定"` | deviceId 未在数据库中找到 |
| `"DH 会话未初始化"` | 收到 dh_response 但未发送 dh_init |
| `"挑战参数无效"` | challenge_response 中的 challengeId 不存在 |
| `"会话未绑定"` | 收到非 bind 消息但 session 未绑定 |

---

### 3.3 心跳机制

客户端使用 OkHttp 内置的 WebSocket Ping/Pong 机制，每 30 秒发送一次 Ping 帧。

**服务端行为：**
- 按 RFC 6455 标准回复 Pong 帧即可，无需特殊处理
- 如果超过 60 秒未收到任何消息（包括 Ping），可认为连接已断开

**客户端代码：**
```kotlin
val client = OkHttpClient.Builder()
    .pingInterval(30_000L, TimeUnit.MILLISECONDS) // 30 秒心跳
    .build()
```

---

### 3.4 断线重连建议

当前客户端已实现基本的 WebSocket 连接管理，但**自动重连机制尚未完善**。建议服务端配合以下策略：

#### 服务端侧

1. **检测到 WebSocket 断开时**：清理该 session 的映射关系
2. **重连后**：客户端会重新走 bind → dh_init → dh_ready 完整流程
3. **同一用户多设备**：支持一个用户绑定多个设备，每个设备独立 WebSocket 连接

#### 客户端侧（计划实现）

- 指数退避重连策略：1s → 2s → 4s → 8s → 16s → 30s（封顶）
- 重连时保留 Token 和 DeviceId
- 最大重试次数：10 次后停止

---

## 4. 密码学协议详解

### 4.1 DH 密钥交换

#### 参数规格

| 参数 | 值 |
|------|-----|
| 算法 | Diffie-Hellman (DH) |
| 素数模数 p | RFC 3526 2048-bit MODP Group |
| 生成元 g | 2 |
| 私钥长度 | 2048-bit |
| 公钥编码 | X.509 SubjectPublicKeyInfo → Base64 |
| 共享密钥长度 | 2048-bit (256 字节) |

#### RFC 3526 2048-bit MODP Group (p 值)

```
FFFFFFFF FFFFFFFF C90FDAA2 2168C234 C4C6628B 80DC1CD1
29024E08 8A67CC74 020BBEA6 3B139B22 514A0879 8E3404DD
EF9519B3 CD3A431B 302B0A6D F25F1437 4FE1356D 6D51C245
E485B576 625E7EC6 F44C42E9 A637ED6B 0BFF5CB6 F406B7ED
EE386BFB 5A899FA5 AE9F2411 7C4B1FE6 49286651 ECE45B3D
C2007CB8 A163BF05 98DA4836 1C55D39A 69163FA8 FD24CF5F
83655D23 DCA3AD96 1C62F356 208552BB 9ED52907 7096966D
670C354E 4ABC9804 F1746C08 CA18217C 32905E46 2E36CE3B
E39E772C 180E8603 9B2783A2 EC07A28F B5C55DF0 6F4C52C9
DE2BCBF6 95581718 3995497C EA956AE5 15D22618 98FA0510
15728E5A 8AAAC42D AD33170D 04507A33 A85521AB DF1CBA64
ECFB8504 58DBEF0A 8AEA7157 5D060C7D B3970F85 A6E1E4C7
ABF5AE8C DB0933D7 1E8C94E0 4A25619D CEE3D226 1AD2EE6B
F12FFA06 D98A0864 D8760273 3EC86A64 521F2B18 177B200C
BBE11757 7A615D6C 770988C0 BAD946E2 08E24FA0 74E5AB31
43DB5BFC E0FD108E 4B82D120 A93AD2CA FFFFFFFF FFFFFFFF
```

> 十进制表示：`2^2048 - 2^1984 - 1 + 2^64 * (floor(2^1918 * π) + 124476)`

#### 服务端 DH 密钥对管理

```java
// Java 示例: 生成 DH 密钥对
DHParameterSpec dhParams = new DHParameterSpec(MODP_P, MODP_G);
KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DH");
keyGen.initialize(dhParams, new SecureRandom());
KeyPair serverKeyPair = keyGen.generateKeyPair();

// 公钥编码为 Base64
String publicKeyBase64 = Base64.getEncoder()
    .encodeToString(serverKeyPair.getPublic().getEncoded());

// 计算共享密钥（收到客户端公钥后）
KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
keyAgreement.init(serverKeyPair.getPrivate());
keyAgreement.doPhase(clientPublicKey, true);
byte[] sharedSecret = keyAgreement.generateSecret();
```

> **建议：** 每次 WebSocket 连接重新生成 DH 密钥对，不与其它连接复用。

---

### 4.2 密钥派生 (KDF)

DH 交换产生的 2048 位共享密钥 → SHA-256 → 32 字节 AES-256 密钥

```
SharedSecret (2048-bit / 256 bytes)
        │
        ▼
    SHA-256 哈希
        │
        ▼
    32 bytes → AES-256-GCM 密钥
```

**客户端实现（`KeyDerivation.deriveAesKey()`）：**

```kotlin
fun deriveAesKey(sharedSecret: ByteArray, keySize: Int): ByteArray {
    val hash = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
    val keyBytes = keySize / 8  // 256 / 8 = 32
    return hash.copyOfRange(0, minOf(keyBytes, hash.size))
}
```

**服务端实现（Java 示例）：**

```java
public byte[] deriveAesKey(byte[] sharedSecret, int keySizeBits) {
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    byte[] hash = sha256.digest(sharedSecret);
    int keyBytes = keySizeBits / 8;
    return Arrays.copyOfRange(hash, 0, Math.min(keyBytes, hash.length));
}

// 使用: deriveAesKey(sharedSecret, 256) → 32 bytes
```

> ⚠️ **注意：** 请确保服务端 KDF 实现与客户端完全一致（SHA-256、取前 N 字节）。

---

### 4.3 AES-256-GCM 加解密

当前客户端代码中 `AesGcmCrypto.kt` 已实现，但目前 WebSocket challenge 消息**未加密传输**。此加密实现主要用于未来扩展或 API 级别的数据加密。

#### 参数规格

| 参数 | 值 |
|------|-----|
| 算法 | AES/GCM/NoPadding |
| 密钥长度 | 256 位 (32 字节) |
| IV (Nonce) 长度 | 12 字节 (96 位) |
| GCM Tag 长度 | 128 位 (16 字节) |

#### AES-GCM 加密流程

```
1. 生成 12 字节随机 IV
2. 初始化 Cipher: AES/GCM/NoPadding
3. 设置 SecretKeySpec (32 bytes)
4. 设置 GCMParameterSpec (128-bit tag, 12-byte IV)
5. 执行加密 → ciphertext (含 GCM tag)
6. 返回: IV (Base64) + ciphertext (Base64)
```

#### Java 服务端实现

```java
public class AesGcmUtil {
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12; // bytes

    public static String[] encrypt(byte[] plaintext, byte[] key) throws Exception {
        // 1. 生成随机 IV
        byte[] iv = new byte[IV_LENGTH];
        SecureRandom.getInstanceStrong().nextBytes(iv);

        // 2. 初始化 Cipher
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

        // 3. 加密
        byte[] ciphertext = cipher.doFinal(plaintext);

        // 4. Base64 编码
        return new String[]{
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(ciphertext)
        };
    }

    public static byte[] decrypt(String ivBase64, String ciphertextBase64, byte[] key) throws Exception {
        byte[] iv = Base64.getDecoder().decode(ivBase64);
        byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        return cipher.doFinal(ciphertext);
    }
}
```

> ⚠️ **至关重要：** 同一密钥下，IV 绝不能重复使用！建议使用随机 IV 或递增计数器。

---

### 4.4 SM4-GCM 加解密

国密算法支持，当 `dh_ready` 消息中的 `cipher` 为 `"SM4-GCM"` 时使用。

#### 参数规格

| 参数 | 值 |
|------|-----|
| 算法 | SM4/GCM/NoPadding |
| 密钥长度 | 128 位 (16 字节) |
| IV 长度 | 12 字节 |
| Tag 长度 | 128 位 |

> 服务端如需 SM4 支持，建议使用 Bouncy Castle 库。

---

### 4.5 加密算法协商

通过 `dh_ready.cipher` 字段协商。服务端可根据策略选择合适的算法：

| 策略 | 说明 |
|------|------|
| 固定 AES-256-GCM | 服务端始终返回 `"AES-256-GCM"` |
| 根据客户端偏好 | 查询数据库 `device.cipherPref` 字段 |
| 根据安全等级 | 可选实现不同安全级别的算法切换 |

---

## 5. 安全增强措施

以下措施对应 TODO 第 5 项"升级后端安全性设计"。

### 5.1 客户端时间戳签名

**目标：** 防止重放攻击。

**建议方案：**

```
每次登录请求（bind）携带:
- timestamp: 客户端 Unix 时间戳（毫秒）
- nonce:  一次性随机数
- signature: HMAC-SHA256(timestamp + nonce, deviceSecret)

服务端验证:
1. 检查 timestamp 偏差 < ±5 分钟
2. 检查 nonce 是否已使用（Redis 去重）
3. 验证 signature
```

> 此功能需客户端配合实现，当前尚未开发。

### 5.2 登录失败防护

**服务端建议：**

1. **登录失败计数**：记录每个用户名/IP 的连续失败次数
2. **临时锁定**：连续失败 5 次后，锁定该账号 15 分钟
3. **验证码**：连续失败 3 次后，要求输入验证码

**客户端侧已实现：**
- 登录失败后显示错误提示
- 待实现：客户端侧指数退避延迟（连续失败 3 次后增加等待）

### 5.3 Challenge 防暴力

1. **单次有效**：每个 `challengeId` 只能响应一次
2. **过期失效**：设置合理的过期时间（建议 60-120 秒）
3. **频率限制**：同一用户每分钟最多发起 5 次 challenge
4. **连续错误冷却**：连续 3 次 challenge 失败后，冷却 30 秒

### 5.4 密钥轮换

- WebSocket 每次重连时重新执行 DH 密钥交换
- 建议服务端保留最近 2-3 个连接的 DH 公钥，允许短时间内重用

### 5.5 通信安全

| 要求 | 说明 |
|------|------|
| **WSS** | 生产环境必须使用 `wss://`，禁用明文 `ws://` |
| **HTTPS** | REST API 必须使用 HTTPS |
| **TLS 版本** | 仅允许 TLS 1.2+，禁用 TLS 1.0/1.1 和 SSL |
| **证书锁定** | 可选，建议在关键环境配置 Certificate Pinning |
| **日志安全** | 服务端不要记录 Token、密钥等敏感信息到日志 |

---

## 6. 开发环境与测试

### 6.1 环境配置

| 项目 | 值 |
|------|-----|
| REST API 基准 URL (开发) | `http://10.0.2.2:8080` |
| WebSocket 端点 (开发) | `ws://10.0.2.2:8080/ws/app/auth` |
| Android 模拟器访问宿主机 | `10.0.2.2` 映射到 `localhost` |

### 6.2 推荐后端技术栈

| 组件 | 建议 |
|------|------|
| 框架 | Spring Boot / Kotlin Ktor |
| 数据库 | MySQL / PostgreSQL |
| 缓存 | Redis（WebSocket session 管理、挑战信息存储） |
| JWT | io.jsonwebtoken (jjwt) |
| WebSocket | Spring WebSocket / OkHttp WebSocket Server |

### 6.3 测试建议

#### 使用 Postman / wscat 测试 WebSocket

```bash
# 安装 wscat
npm install -g wscat

# 连接 WebSocket
wscat -c ws://localhost:8080/ws/app/auth

# 发送 bind 消息
{"type":"bind","token":"your_jwt_token","deviceId":"ANDROID_test_device_123"}
```

#### 测试流程

```
1. 启动后端服务
2. POST /api/auth/app/bind 创建设备绑定（获取 Token 和 DeviceId）
3. WebSocket 连接测试：
   a. 使用 wscat 连接 ws://localhost:8080/ws/app/auth
   b. 发送 bind 消息
   c. 验证收到 bind_ack
   d. 验证收到 dh_init
   e. 回复 dh_response（使用已知的客户端公钥）
   f. 验证收到 dh_ready
   g. 触发 challenge 推送（模拟登录请求）
   h. 回复 challenge_response
   i. 验证收到 auth_result
```

### 6.4 错误场景测试

| 场景 | 期望行为 |
|------|----------|
| 无效 Token 连接 WebSocket | 收到 `bind_ack.status=error` |
| 已过期 challenge 回复 | 收到 `auth_result.status=expired` |
| 错误数字回复 | 收到 `auth_result.status=rejected` |
| 重复回复同一 challenge | 第二次收到 `error` 或无效响应 |

---

## 7. 附录：数据库模型建议

### 7.1 用户表 (users)

```sql
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,      -- bcrypt 哈希
    email VARCHAR(128),
    avatar_url VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### 7.2 设备绑定表 (user_devices)

```sql
CREATE TABLE user_devices (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(128) NOT NULL UNIQUE,
    device_name VARCHAR(128),
    device_type VARCHAR(32) DEFAULT 'ANDROID',
    public_key TEXT,                             -- DH 公钥
    cipher_pref VARCHAR(32) DEFAULT 'AES-256-GCM',
    last_active_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_user_id (user_id),
    INDEX idx_device_id (device_id)
);
```

### 7.3 挑战记录表 (auth_challenges)

```sql
CREATE TABLE auth_challenges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    challenge_id VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    numbers JSON NOT NULL,                        -- [231, 845, 672]
    correct_answer INT NOT NULL,
    status VARCHAR(16) DEFAULT 'pending',         -- pending/approved/rejected/expired
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    responded_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_challenge_id (challenge_id),
    INDEX idx_user_id_status (user_id, status)
);
```

### 7.4 配对码表 (pair_codes)

```sql
CREATE TABLE pair_codes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pair_code VARCHAR(8) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    status VARCHAR(16) DEFAULT 'active',           -- active/used/expired
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    used_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_pair_code (pair_code),
    INDEX idx_user_id (user_id)
);
```

### 7.5 认证历史表 (auth_history)

```sql
CREATE TABLE auth_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(128),
    challenge_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,                   -- approved/rejected/expired
    requested_at TIMESTAMP NOT NULL,
    responded_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_user_id_created (user_id, requested_at DESC)
);
```

---

> **文档版本:** v1.0  
> **最后更新:** 2026-06-14  
> **对应客户端:** MyAuthenticator Android App  
> **如有疑问请联系 Android 开发者**