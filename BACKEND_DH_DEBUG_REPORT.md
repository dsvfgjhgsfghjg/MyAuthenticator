# 服务端 DH 握手问题报告

## 问题描述

Android 客户端（`MyAuthenticator` 版本 1.0.0-beta.1）WebSocket 连接流程在 `bind_ack` 后卡住，服务端未按协议发送 `dh_init` 消息。

## 客户端日志（验证结果）

```
[23:25:28] WebSocketService 连接状态: ✅ 已连接
[23:25:28] UserId: ✅ 1              ← bind_ack 已收到
[23:25:28] CipherPref: ❌ 未设置     ← dh_ready 未收到
[23:25:31] 注册会话数=1              ← 服务端有该设备的 WebSocket 会话
[23:25:31]   22011211C: ✅ 在线       ← 会话确实存在
```

## 协议流程图

```
客户端 (Android App)                服务端 (Backend)
        │                                     │
        │  WebSocket 连接 ✅                   │
        ├──────────────────────────────────►   │
        │                                     │
        │  bind {token, deviceId}             │
        ├──────────────────────────────────►   │
        │                          ┌──────────┤
        │                          │ 验证 Token
        │                          │ 验证设备
        │                          └──────────┤
        │  bind_ack {status:"ok", userId:1}   │
        │◄──────────────────────────────────  │
        │                                     │
        │  ★★★ 以下步骤缺失 ★★★               │
        │                                     │
        │  dh_init {serverPublicKey: "..."}   │  ← 服务端应在此处发送 DH 公钥
        │◄──────────────────────────────────  │
        │                                     │
        │  客户端生成 DH 密钥对                  │
        │  计算共享密钥                          │
        │                                     │
        │  dh_response {clientPublicKey: "..."}│
        ├──────────────────────────────────►   │
        │                          ┌──────────┤
        │                          │ 计算共享密钥
        │                          └──────────┤
        │  dh_ready {cipher:"AES-256-GCM"}    │
        │◄──────────────────────────────────  │
        │                                     │
        │  加密通道就绪 ✅                      │
```

## 需要后端做的修改

在 WebSocket 服务端 `onMessage` 处理 `bind` 消息的代码中，验证成功后**立即发送 `dh_init`**：

```java
// 修改前：仅发送 bind_ack
void onBind(String token, String deviceId) {
    if (validateToken(token, deviceId)) {
        send(new BindAckMessage("ok", userId));
        // ← 没有后续动作，DH 握手无法进行
    }
}

// 修改后：bind_ack 后立即发送 dh_init
void onBind(String token, String deviceId) {
    if (validateToken(token, deviceId)) {
        send(new BindAckMessage("ok", userId));
        
        // 1. 为该连接生成 DH 密钥对（每次新连接重新生成，不复用）
        KeyPair serverKeyPair = generateDhKeyPair();
        storeSessionKeyPair(session, serverKeyPair); // 暂存私钥，等 dh_response 时用
        
        // 2. 发送 DH 公钥给客户端
        String publicKeyBase64 = encodePublicKeyToBase64(serverKeyPair.getPublic());
        send(new DhInitMessage(publicKeyBase64));
    }
}
```

## 服务端 DH 密钥交换完整流程

收到 `dh_response` 后的处理：

```java
void onDhResponse(String clientPublicKeyBase64) {
    // 1. 取出之前暂存的该会话的 DH 私钥
    PrivateKey serverPrivateKey = getSessionKeyPair(session).getPrivate();
    
    // 2. 解码客户端公钥
    PublicKey clientPublicKey = decodePublicKeyFromBase64(clientPublicKeyBase64);
    
    // 3. 计算共享密钥
    KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
    keyAgreement.init(serverPrivateKey);
    keyAgreement.doPhase(clientPublicKey, true);
    byte[] sharedSecret = keyAgreement.generateSecret();
    
    // 4. 派生 AES-256 密钥（注意：必须使用 SHA-256 取前 32 字节）
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    byte[] aesKey = Arrays.copyOf(sha256.digest(sharedSecret), 32);
    
    // 5. 保存密钥与该会话关联（后续加密通信用）
    storeSessionAesKey(session, aesKey);
    
    // 6. 回复 dh_ready
    send(new DhReadyMessage("AES-256-GCM")); // 默认选择 AES-256-GCM
}
```

## DH 参数规格

| 参数 | 值 |
|------|-----|
| 算法 | DH (Diffie-Hellman) |
| 素数 p | RFC 3526 2048-bit MODP Group |
| 生成元 g | 2 |
| 公钥编码 | X.509 SubjectPublicKeyInfo → Base64 |
| 密钥派生 | SHA-256(sharedSecret) → 前 32 字节 |

> 详细密码学参数请参考 `BACKEND_API_GUIDE.md` 第 4.1-4.2 节

## 验证方式

修复后，App 调试页面的诊断应变为：
- CipherPref: ✅ AES-256-GCM（DH 握手完成）
- 所有设备状态从 `sessionExists=true` 变为 `online=true`

---

**请确认后端的 WebSocket 服务端是否已实现了上述流程？如果遇到问题请回复具体现象。**