# MyAuthenticator — 移动端二次认证 App

> **版本**: beta · **包名**: `top.leoblog.myauthenticator`

MyAuthenticator 是一款 Android 原生二次认证（2FA）客户端，与后端认证系统配合，在登录等敏感操作时通过手机端进行"3 选 1"挑战确认。

## 特性

- **密码绑定 & 扫码绑定** — 支持两种设备绑定方式
- **实时认证挑战** — 通过 WebSocket 推送"3 选 1"数字挑战，用户选择正确数字完成认证
- **双算法加密** — 同时支持 AES-256-GCM 和 SM4-GCM（国密），可在设置中切换
- **认证历史** — 查看历史认证记录（已批准/已拒绝/已过期）
- **WebSocket 前台服务** — 保持长连接，确保认证通知及时送达
- **设备管理** — 查看/解绑已绑定的设备
- **反馈上报** — 内置 Bug 日志上报功能
- **多环境支持** — 生产环境 / 模拟器开发 / 真机无线调试一键切换

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin |
| 网络 | Retrofit + OkHttp + OkHttp WebSocket |
| 加密 | Bouncy Castle (SM4-GCM), JDK Security (AES-256-GCM) |
| 导航 | Navigation Component (底部导航 3 Tab) |
| UI | Fragment + XML Layout |
| 存储 | SharedPreferences (Encrypted) |
| 构建 | Gradle KTS + Version Catalog |

## 项目结构

```
app/src/main/java/top/leoblog/myauthenticator/
├── crypto/          # 加密模块（AES-GCM、SM4-GCM、DH 密钥交换、日志压缩）
├── model/           # 数据模型 & API 请求/响应
├── network/         # 网络层（RetrofitClient、WebSocket Client、API 接口）
├── service/         # WebSocket 前台服务
├── storage/         # 安全存储（Token、DeviceId）
└── ui/
    ├── bind/        # 设备绑定
    ├── challenge/   # 认证挑战对话框
    ├── debug/       # 调试信息页
    ├── login/       # 登录页
    ├── main/        # 主页面（Authenticator / Dashboard / Profile 三 Tab）
    ├── security/    # 安全 & 设备信息页
    └── settings/    # 设置页
```

## 快速开始

### 前置条件

- Android Studio (最新稳定版)
- JDK 21+
- Android SDK 34+

### 构建 & 安装

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 安装到连接的设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 无线调试

项目提供 `adb_wireless_debug.sh` 脚本和 VS Code Task 支持无线调试：

```bash
# 1. 首次配对（手机开启无线调试 → 配对码配对）
bash adb_wireless_debug.sh pair 192.168.43.1:39797 123456

# 2. 连接
bash adb_wireless_debug.sh connect 192.168.43.1:42819

# 3. 一键构建 + 安装 + 启动
bash adb_wireless_debug.sh all
```

### 环境切换

默认连接生产环境（`https://leo-blog.top`）。开发时可动态切换：

- **模拟器**：`NetworkConfig.useDevelopment()`
- **真机无线调试**：`NetworkConfig.useWirelessDebug("你的主机IP")`

详见 `NetworkConfig.kt`。

## 相关文档

| 文档 | 说明 |
|---|---|
| `BACKEND_API_GUIDE.md` | 后端 API 接口文档 |
| `BACKEND_DH_DEBUG_REPORT.md` | DH 密钥交换调试报告 |
| `APP_DEV_TODO.MD` | 开发待办 |
| `VSCODE_SHORTCUTS.md` | VS Code 快捷键参考 |
| `MYbugreport/` | Bug 报告与修复分析 |

## 许可证

内部项目 — 未经授权不得使用或分发。