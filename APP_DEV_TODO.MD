# MyAuthenticator — 开发待办清单

> 基于当前代码库分析，按优先级排序。每个任务附带具体文件路径和技术要点。

---

## 1. 登录成功后用户信息显示在主页（Profile 页面增强）

**涉及文件：**
- `app/src/main/java/top/leoblog/myauthenticator/ui/main/ProfileFragment.kt`
- `app/src/main/res/layout/fragment_profile.xml`
- `app/src/main/java/top/leoblog/myauthenticator/storage/SecureStorage.kt`

**当前状态：** 只显示用户名。头像固定图标、无邮箱/绑定时间等额外信息。

**需要完成：**
- [ ] 从后端获取用户详细信息（头像URL、邮箱、绑定时间等）
- [ ] 新增 `ApiService` 接口方法，如 `GET /api/user/profile`（需携带 JWT Token）
- [ ] 在 `SecureStorage` 中增加邮箱、头像URL等字段的存取(注意脱敏)
- [ ] 修改 `fragment_profile.xml` 布局，添加邮箱、绑定状态、绑定时间等展示控件
- [ ] 修改 `ProfileFragment`，登录后从 API 加载用户完整信息并渲染
- [ ] 支持头像加载（可使用 Glide 或 Coil 加载 URL 图片）
- [ ] 在 `ProfileFragment` 中添加下拉刷新功能

---

## 2. 完善设置页面逻辑

**涉及文件：**
- `app/src/main/java/top/leoblog/myauthenticator/ui/settings/SettingsActivity.kt`
- `app/src/main/res/layout/activity_settings.xml`
- `app/src/main/java/top/leoblog/myauthenticator/network/AppWebSocketClient.kt`
- `app/src/main/java/top/leoblog/myauthenticator/service/WebSocketService.kt`
- `app/src/main/java/top/leoblog/myauthenticator/crypto/AesGcmCrypto.kt`
- `app/src/main/java/top/leoblog/myauthenticator/crypto/Sm4GcmCrypto.kt`
- `app/src/main/java/top/leoblog/myauthenticator/crypto/KeyDerivation.kt`

### 2.1 修复加密算法显示与选择
- [ ] 当前 `tvCipher` 显示 `getCipherPref()`，但登录时未保存加密算法偏好 → 需在 DH 握手完成后持久化到 `SecureStorage`
- [ ] 检查 `AesGcmCrypto` 和 `Sm4GcmCrypto` 是否完整实现（加密/解密方法、与协议集成）
- [ ] 检查 `KeyDerivation.deriveAesKey()` 是否与后端使用的 KDF 一致（确认 salt/迭代次数等参数）
- [ ] 可在设置页添加加密算法切换开关（AES-256-GCM ↔ SM4-GCM），切换后重连 WebSocket

### 2.2 修复连接状态显示
- [ ] `AppWebSocketClient.isConnected()` 仅返回 `webSocket != null`，即使实际已断开也会显示"已连接"
- [ ] 改用 `OkHttp WebSocket` 真实状态标志（在 `onOpen`/`onClosed`/`onFailure` 中维护 `AtomicBoolean`）
- [ ] 设置页进入时应主动调用 API 或发送 ping 检测真实连接状态
- [ ] 连接断开后自动重连机制（当前未实现断线重连）

### 2.3 其他设置页增强
- [ ] 添加设备信息编辑（修改设备名称）
- [ ] 添加日志/调试信息展示区域
- [ ] 添加关于版本号展示

---

## 3. Dashboard 的简单管理能力菜单项开发

**涉及文件：**
- `app/src/main/java/top/leoblog/myauthenticator/ui/main/DashboardFragment.kt`
- `app/src/main/res/layout/fragment_dashboard.xml`

**当前状态：** 仅 inflate 布局，无任何功能。

**需要完成：**
- [ ] 设计 Dashboard 布局，包含以下菜单项/卡片：
  - [ ] **已绑定设备列表** — 展示当前账号下所有已绑定的设备（名称、最后活跃时间、在线状态）
  - [ ] **认证历史** — 展示最近的认证请求记录（时间、设备、结果）
  - [ ] **安全概览** — 展示加密算法、连接状态等摘要信息
- [ ] 新增 API 接口（需要后端配合）：
  - `GET /api/auth/devices` — 获取绑定的设备列表
  - `GET /api/auth/history` — 获取认证历史
  - `DELETE /api/auth/devices/{id}` — 解绑指定设备
- [ ] 在 `ApiService.kt` 中添加上述接口定义
- [ ] 在 `DashboardFragment` 中调用接口、展示数据
- [ ] 添加空状态和错误状态处理

---

## 4. 核心推送接收功能和确认弹窗

**涉及文件：**
- `app/src/main/java/top/leoblog/myauthenticator/service/WebSocketService.kt`
- `app/src/main/java/top/leoblog/myauthenticator/ui/challenge/ChallengeDialogFragment.kt`
- `app/src/main/java/top/leoblog/myauthenticator/network/AppWebSocketClient.kt`
- `app/src/main/AndroidManifest.xml`

**当前状态：** WebSocket 前台服务已在运行，Challenge 弹窗已实现基本功能，但缺少很多关键流程。

### 4.1 推送接收机制完善
- [ ] 确保 App 在后台/锁屏时仍能收到推送通知（前台 Service 已在运行，但需验证 `Android 12+` 的 foreground service 限制）
- [ ] 添加 `Android 13+` 通知权限运行时请求（`POST_NOTIFICATIONS`）
- [ ] 验证 WebSocket 心跳机制是否正常工作（当前 30 秒间隔）
- [ ] 添加断线自动重连（指数退避策略：1s → 2s → 4s → 8s → max 30s）
- [ ] 在 `WebSocketService` 中添加重连逻辑，保留 token/deviceId 以便重建连接

### 4.2 挑战确认弹窗完善
- [ ] 检查 `ChallengeDialogFragment` 的 UI/UX：
  - [ ] 弹窗显示 3 个数字供选择
  - [ ] 添加超时倒计时（后端给的 challenge 通常有有效期）
  - [ ] 添加弹窗关闭/忽略逻辑
  - [ ] 选择数字后显示加载状态等待结果
- [ ] 确认弹窗在 `SettingsActivity` 中显示，但考虑在 `MainActivity` 级别也注册回调，确保在任何页面都能收到挑战弹窗
- [ ] 认证结果展示优化：通知栏通知 + 页面内 Toast 双重提示

### 4.3 前台 Service 通知优化
- [ ] 将通知的 `priority` 从 `LOW` 改为 `DEFAULT` 或 `HIGH` 以确保及时显示
- [ ] 通知渠道（`CHANNEL_ID_WEBSOCKET`）设置合适的描述和重要性
- [ ] 支持多语言（英文/中文）

---

## 5. 升级后端安全性设计，减少爆破可能

> ⚠️ **注意：** 此部分需要 Android 客户端与后端配合修改，以下仅列出客户端侧可做的改动，后端侧改动需要后端开发者配合。

### 5.1 客户端防护措施
- [ ] 登录请求添加客户端时间戳 + 设备指纹签名
  - 每次登录请求携带 `timestamp` + `deviceFingerprint`（由设备ID + Android ID + 时间戳 HMAC 签名）
  - 后端验证时间戳偏差（±5分钟）
- [ ] 登录失败后客户端侧增加指数退避延迟
  - 连续失败 3 次后，客户端自动增加 3 秒等待
  - 连续失败 5 次后，增加到 10 秒等待
- [ ] 登录请求添加一次性 nonce（需要后端配合生成和验证）

### 5.2 认证流程增强
- [ ] Challenge 数字选择逻辑检查：防止随机暴力选择（连续错误应有冷却）
- [ ] 加密通道（DH + AES-GCM）密钥定期轮换
  - 可以每 24 小时或每次重连时重新执行 DH 密钥交换
- [ ] 验证 AES-GCM 的 nonce 管理是否正确（每次加密使用唯一 nonce）

### 5.3 通信安全
- [ ] 确保 WebSocket 使用 `wss://`（当前 `NetworkConfig.webSocketUrl` 是否为 wss？）
- [ ] 检查 Retrofit 客户端是否配置了 SSL 证书锁定（Certificate Pinning）
- [ ] 检查 `OkHttpClient` 的 SSL 配置是否安全（禁用所有不安全的 TLS 版本和密码套件）

### 5.4 本地存储安全
- [ ] 确认 `EncryptedSharedPreferences` 的 MasterKey 使用了 `KeyScheme.AES256_GCM`（当前已使用 ✅）
- [ ] 检查是否存在敏感信息被意外记录到日志的风险（当前有多处 `Log.d(TAG, ...)` 可能泄漏 token/密钥）
- [ ] 屏幕截图防护（在 `MainActivity`/`SettingsActivity` 的 `onCreate` 中设置 `FLAG_SECURE` 防止后台截图）

---

## 附：已知潜在 Bug 和代码质量问题

- [ ] `AppWebSocketClient.handleBindAck()` 中 `userId` 保存到 `SecureStorage` 的调用在 `WebSocketService` 回调中完成，但 `SettingsActivity` 独立使用 WebSocket 时没有保存 userId
- [ ] `WebSocketService.connectionCallback` 和 `challengeCallback` 是静态可空变量，存在多 Activity 场景下回调被覆盖或空指针的风险
- [ ] `EncryptedSharedPreferences` 的 `clearAll()` 会清除所有键值对，包括 `deviceId`，但 `generateDeviceId()` 在登录时重新生成，可能导致设备 ID 变化
- [ ] Dashboard Fragment 和 Authenticator Fragment 目前完全空白，导航图中已配置但无实际内容
- [ ] 导航图（`nav_graph.xml`）中定义的 Fragment 间跳转关系需要确认