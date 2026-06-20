# MyAuthenticator — Authenticator 首页需求文档

> **版本:** v1.0  
> **更新日期:** 2026-06-20  
> **对应客户端:** MyAuthenticator Android App  
> **目的:**  
> 1. 为后端定义 Authenticator 首页聚合 API（`GET /api/auth/app/dashboard`）  
> 2. 为客户端定义敏感数据加密存储迁移方案

---

## 📋 目录

1. [Authenticator 首页概述](#1-authenticator-首页概述)
2. [聚合 API — GET /api/auth/app/dashboard](#2-聚合-api--get-apiauthappdashboard)
3. [客户端加密存储迁移](#3-客户端加密存储迁移)
4. [附录：客户端实现计划](#4-附录客户端实现计划)

---

## 1. Authenticator 首页概述

### 1.1 页面定位

Authenticator 页面是底部导航栏的**第一项（首页）**，定位为**概览仪表盘**，用户打开 App 后第一时间看到核心状态信息与快捷操作入口。

### 1.2 页面布局（示意）

```
┌─────────────────────────────────┐
│  🔗 WebSocket 已连接              │  ← 状态栏: 连接状态 + 当前用户
│  📱 用户: leoadmin                │
├─────────────────────────────────┤
│  ┌────────┐ ┌────────┐ ┌──────┐ │
│  │ 上次结果 │ │设备数量 │ │ 加密 │ │  ← 三列核心指标卡片
│  │ 已批准   │ │   2   │ │AES256│ │
│  └────────┘ └────────┘ └──────┘ │
├─────────────────────────────────┤
│  📋 最近认证记录                   │
│  ┌───────────────────────────┐  │
│  │ ✅ 2026-06-20 10:30       │  │  ← 最近 5 条记录列表
│  │   来自 Xiaomi 14 · 2秒内   │  │
│  ├───────────────────────────┤  │
│  │ ❌ 2026-06-19 22:15       │  │
│  │   来自 Web 浏览器 · 数字错误 │  │
│  └───────────────────────────┘  │
│  [查看全部认证历史 >]             │  ← 跳转 AuthHistoryFragment
├─────────────────────────────────┤
│  [新建设备配对] [切换加密算法]    │  ← 快捷操作按钮
└─────────────────────────────────┘
```

### 1.3 需要展示的数据项

| # | 数据项 | 说明 | 来源 |
|---|--------|------|------|
| 1 | 用户昵称/用户名 | 当前绑定用户 | `SecureStorage` + Profile API |
| 2 | WebSocket 连接状态 | 已连接/已断开/正在连接 | 客户端 `AppWebSocketClient` 状态 |
| 3 | 上次认证结果 | 最近一条认证记录的 status | 聚合 API 返回 |
| 4 | 已绑定设备数 | 当前账号下设备总数 | 聚合 API 或 Profile API |
| 5 | 加密算法 | 当前设备首选加密算法 | `SecureStorage.getCipherPref()` |
| 6 | 最近认证记录 | 最近 5 条记录（含时间、设备名、状态、响应耗时） | 聚合 API |
| 7 | 是否有待处理挑战 | 当前是否有未响应的挑战 | 客户端状态（WebSocket 推送） |

---

## 2. 聚合 API — GET /api/auth/app/dashboard

### 2.1 接口定义

> **用途：** 聚合 Authenticator 首页所需的所有数据，减少客户端多次请求。

```
GET /api/auth/app/dashboard
Authorization: Bearer <jwt_token>
```

#### 请求参数

无（使用 JWT Token 中的 userId 和 deviceId 鉴权）。

#### 响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "user": {
      "userId": 10001,
      "username": "leoadmin",
      "nickname": "Leo",
      "email": "user@example.com",
      "avatarUrl": "https://example.com/avatars/10001.jpg?t=123456",
      "deviceCount": 2
    },
    "device": {
      "deviceId": "ANDROID_abc12345_def67890",
      "deviceName": "Xiaomi 14",
      "cipherPref": "AES-256-GCM",
      "boundAt": "2026-06-12T10:30:00"
    },
    "recentHistory": {
      "total": 50,
      "records": [
        {
          "id": 100,
          "challengeId": "a1b2c3d4e5f6",
          "deviceName": "Xiaomi 14",
          "status": "approved",
          "requestedAt": "2026-06-20T10:30:00",
          "respondedAt": "2026-06-20T10:30:02",
          "responseTimeMs": 2000
        }
      ]
    },
    "lastAuthResult": {
      "status": "approved",
      "deviceName": "Xiaomi 14",
      "requestedAt": "2026-06-20T10:30:00"
    },
    "lastLoginAt": "2026-06-20T10:00:00"
  }
}
```

### 2.2 字段说明

#### data.user — 用户信息

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `userId` | int | 是 | 用户 ID |
| `username` | string | 是 | 用户名 |
| `nickname` | string | 否 | 昵称（可为 null） |
| `email` | string | 否 | 邮箱地址（可为 null） |
| `avatarUrl` | string | 否 | **头像可公开访问的 URL（带时间戳防止 CDN 缓存）**，客户端直接用此 URL 渲染 |
| `deviceCount` | int | 是 | 当前用户已绑定的设备总数 |

> **关于 avatarUrl 的重要说明：**  
> 客户端使用 Coil 图片加载库直接渲染此 URL。建议服务端返回一个**可公开访问的代理 URL**（例如 `/api/users/avatar/public/{userId}`），而不是 MinIO 原始路径。URL 应附加查询参数 `?t=timestamp` 以绕过 CDN/浏览器缓存。

#### data.device — 当前设备信息

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceId` | string | 是 | 当前设备 ID |
| `deviceName` | string | 是 | 设备显示名称（如 "Xiaomi 14"） |
| `cipherPref` | string | 是 | 设备首选加密算法: `"AES-256-GCM"` / `"SM4-GCM"` |
| `boundAt` | string (ISO8601) | 否 | 本设备绑定时间 |

#### data.recentHistory — 最近认证记录

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `total` | long | 是 | 该用户的总认证记录数 |
| `records` | array | 是 | 最近 5 条记录（按 requestedAt 降序） |

每条 record 字段：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | long | 是 | 记录 ID |
| `challengeId` | string | 是 | 挑战 ID |
| `deviceName` | string | 否 | 发起挑战的设备名称（客户端显示"来自 XX 设备"） |
| `status` | string | 是 | `"approved"` / `"rejected"` / `"expired"` |
| `requestedAt` | string (ISO8601) | 是 | 挑战发起时间 |
| `respondedAt` | string (ISO8601) | 否 | 用户响应时间（可为 null，pending 状态不返回此字段） |
| `responseTimeMs` | long | 否 | 响应耗时（毫秒），`respondedAt - requestedAt` |

#### data.lastAuthResult — 上次认证结果快捷字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `status` | string | 是 | `"approved"` / `"rejected"` / `"expired"` / `null`（无记录） |
| `deviceName` | string | 否 | 发起挑战的设备名称 |
| `requestedAt` | string (ISO8601) | 否 | 挑战发起时间 |

> 此字段本质上是 `recentHistory.records[0]` 的摘要，方便客户端直接读取无需再次解析列表。

#### data.lastLoginAt — 最后登录时间

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `lastLoginAt` | string (ISO8601) | 否 | 当前账号最后登录时间 |

### 2.3 后端处理要点

1. **验证 JWT Token** 有效性，提取 userId 和 deviceId
2. **查询用户信息**（从 user 表/缓存）
3. **查询当前设备信息**（从 device 表/缓存）
4. **查询最近 5 条认证历史**（从 auth_history 表，按 `requestedAt DESC`，limit 5）
5. **汇总返回**，缓存策略建议：
   - 用户信息和设备信息可以缓存 5 分钟（变化不频繁）
   - 认证历史实时查询（建议不需要缓存）
   - 如果启用 Redis，可以将整个 dashboard 响应缓存 30 秒，减少数据库压力

### 2.4 客户端新增接口定义

```kotlin
// 在 ApiService.kt 中新增
@GET("/api/auth/app/dashboard")
suspend fun getDashboard(
    @Header("Authorization") authorization: String
): Response<ApiResponse<DashboardResponse>>

// 新增数据模型
data class DashboardResponse(
    @SerializedName("user") val user: DashboardUserInfo,
    @SerializedName("device") val device: DashboardDeviceInfo,
    @SerializedName("recentHistory") val recentHistory: DashboardRecentHistory,
    @SerializedName("lastAuthResult") val lastAuthResult: DashboardLastAuthResult?,
    @SerializedName("lastLoginAt") val lastLoginAt: String?
)

data class DashboardUserInfo(
    @SerializedName("userId") val userId: Long,
    @SerializedName("username") val username: String,
    @SerializedName("nickname") val nickname: String?,
    @SerializedName("email") val email: String?,
    @SerializedName("avatarUrl") val avatarUrl: String?,
    @SerializedName("deviceCount") val deviceCount: Int
)

data class DashboardDeviceInfo(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String,
    @SerializedName("cipherPref") val cipherPref: String,
    @SerializedName("boundAt") val boundAt: String?
)

data class DashboardRecentHistory(
    @SerializedName("total") val total: Long,
    @SerializedName("records") val records: List<DashboardHistoryRecord>
)

data class DashboardHistoryRecord(
    @SerializedName("id") val id: Long,
    @SerializedName("challengeId") val challengeId: String,
    @SerializedName("deviceName") val deviceName: String?,
    @SerializedName("status") val status: String,
    @SerializedName("requestedAt") val requestedAt: String,
    @SerializedName("respondedAt") val respondedAt: String?,
    @SerializedName("responseTimeMs") val responseTimeMs: Long?
)

data class DashboardLastAuthResult(
    @SerializedName("status") val status: String?,
    @SerializedName("deviceName") val deviceName: String?,
    @SerializedName("requestedAt") val requestedAt: String?
)
```

### 2.5 错误场景

| HTTP 状态码 | body.code | body.message | 客户端处理 |
|-------------|-----------|-------------|-----------|
| 401 | 401 | Token 无效或过期 | 清除 Token，跳转登录页 |
| 404 | 404 | 用户或设备不存在 | 提示"用户信息异常"，跳转登录页 |
| 500 | 500 | 服务器内部错误 | 使用本地缓存数据展示 |

---

## 3. 客户端加密存储迁移

### 3.1 现状

当前 `SecureStorage.kt` 使用 **普通 `SharedPreferences`** 存储敏感数据：

```
文件名: app_auth_prefs_v2.xml
存储位置: /data/data/top.leoblog.myauthenticator/shared_prefs/
```

存储的敏感数据包括：

| 密钥 | 数据 | 敏感等级 |
|------|------|---------|
| `jwt_token` | JWT Token（会话凭证） | 🔴 高危 |
| `device_secret` | 设备密钥（64 位 hex） | 🔴 高危 |
| `device_id` | 设备唯一标识 | 🟡 中危 |
| `user_id` | 用户 ID | 🟡 中危 |
| `username` / `nickname` | 用户名 | 🟢 低危 |
| `cipher_pref` | 加密算法偏好 | 🟢 低危 |
| profile 缓存数据 | 邮箱、头像 URL 等 | 🟢 低危 |

> **风险：** 在已 Root 设备或通过 ADB 备份，普通 SharedPreferences 文件可被直接读取，JWT Token 和 deviceSecret 存在泄露风险。

### 3.2 目标

使用 **`EncryptedSharedPreferences`**（AndroidX Security Crypto 库）加密存储所有敏感数据。

### 3.3 依赖检查

`app/build.gradle.kts` 中已有：

```kotlin
// EncryptedSharedPreferences
implementation(libs.androidx.security.crypto)
```

**确认此项依赖存在**，如果缺失需要添加。

### 3.4 迁移方案

#### 方案概述

1. 创建一个新文件 `SecureStorageEncrypted.kt`，专门负责**加密存储层**
2. `SecureStorage.kt` 保留对外接口，内部委托给加密存储层
3. 数据库名加上 `_encrypted` 后缀，与旧的明文文件区分
4. 迁移过程：启动时检测旧的明文数据是否存在，如果存在则迁移到加密存储并清除旧文件

#### EncryptedSharedPreferences 初始化

```kotlin
// 替换原有的普通 SharedPreferences 初始化
private val encryptedPrefs: SharedPreferences by lazy {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    EncryptedSharedPreferences.create(
        context,
        PREFS_NAME_ENCRYPTED,           // "app_auth_prefs_encrypted_v3"
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
```

#### 需要加密存储的键

所有涉及凭证和设备标识的键使用加密存储：

| 键 | 理由 |
|----|------|
| `jwt_token` | 会话凭证，最高风险 |
| `device_secret` | 服务端下发的设备密钥，用于 WebSocket 握手验证 |
| `device_id` | 设备唯一标识 |

其他低敏感数据（username, nickname, cipher_pref, profile 缓存等）可以继续使用普通 SharedPreferences 或也迁移到加密存储（不强制）。

#### 数据迁移逻辑

```kotlin
/**
 * 从旧 SharedPreferences 迁移数据到 EncryptedSharedPreferences
 * 在 App 启动时（Application.onCreate 或 MainActivity.onCreate）调用
 */
fun migrateIfNeeded(context: Context) {
    val oldPrefs = context.getSharedPreferences("app_auth_prefs_v2", Context.MODE_PRIVATE)
    
    // 检查旧文件中是否有数据需要迁移
    val oldToken = oldPrefs.getString("jwt_token", null)
    if (oldToken != null && getToken() == null) {
        // 写入加密存储
        saveToken(oldToken)
        saveDeviceId(oldPrefs.getString("device_id", "") ?: "")
        saveDeviceSecret(oldPrefs.getString("device_secret", "") ?: "")
        
        // 清除旧文件中的数据（保留低敏感数据在原文件中）
        oldPrefs.edit()
            .remove("jwt_token")
            .remove("device_secret")
            .remove("device_id")
            .apply()
    }
}
```

### 3.5 保留旧版本兼容

旧版 `SecureStorage` 使用普通 `SharedPreferences`（文件名 `app_auth_prefs_v2`），新版使用 `EncryptedSharedPreferences`（文件名 `app_auth_prefs_encrypted_v3`）。

**兼容策略：**
1. `SecureStorage.getToken()` 先尝试读加密存储，如果为 null 则尝试读旧明文存储（兼容旧版升级）
2. `SecureStorage.saveToken()` 只写入加密存储，读取时自动 fallback
3. 迁移完成后，旧明文数据应被清除

### 3.6 涉及的文件修改

| 文件 | 修改内容 |
|------|---------|
| `SecureStorage.kt` | 引入 `EncryptedSharedPreferences` + `MasterKey`，改造存储层 |
| `MyAuthenticatorApp.kt` | 在 `onCreate()` 中触发一次数据迁移（如果需要） |
| `app/build.gradle.kts` | 确认 `libs.androidx.security.crypto` 依赖存在 |
| `gradle/libs.versions.toml` | 确认版本号，建议使用 `1.1.0-alpha06` 及以上 |

---

## 4. 附录：客户端实现计划

### 4.1 Authenticator 首页开发步骤

1. ✅ 后端实现 `GET /api/auth/app/dashboard` 接口（本文档第 2 节）
2. ✅ 前端新增数据模型 `DashboardResponse` 和 API 接口
3. 实现 `AuthenticatorFragment`：
   - 调用 Dashboard API 获取数据
   - 显示连接状态、用户信息、核心指标卡片
   - 显示最近认证记录列表
   - 提供快捷操作入口
4. 集成 WebSocket 状态监听（实时更新连接状态条）

### 4.2 加密存储迁移步骤

1. ✅ 确认 `EncryptedSharedPreferences` 依赖已添加
2. 实现 `SecureStorage` 改造（使用 `EncryptedSharedPreferences`）
3. 实现数据迁移逻辑（启动时自动迁移）
4. 测试：安装新版 App，确认旧数据已迁移、新数据加密存储
5. 测试：卸载重装，确认 EncryptedSharedPreferences 正常工作

---

> **本文档对应后端 API 开发参考：** `BACKEND_API_GUIDE.md`  
> **本文档对应客户端数据模型：** `V2ApiModels.kt`（新增 Dashboard 相关模型）  
> **本文档对应客户端存储：** `SecureStorage.kt`（加密改造）