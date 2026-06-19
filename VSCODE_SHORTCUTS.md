# ⌨️ VS Code 开发快捷键速查

> **适用于:** MyAuthenticator Android 项目  
> **快捷键基于:** `.vscode/tasks.json` + 全局 keybindings.json

---

## 🚀 编译与安装

| 快捷键                   | 功能                             | 说明                                                            |
| ------------------------ | -------------------------------- | --------------------------------------------------------------- |
| `Ctrl + Shift + B`       | 🔨 **编译 Debug APK（带时间戳）** | 编译后自动在文件名追加 `_yyyyMMdd_HHmm`                         |
| `Ctrl + Shift + R`       | 📱 **完整重新编译 + 安装到手机**  | **最常用！** 先清理缓存 → 完全重新构建 → 自动安装带时间戳的 APK |
| `Ctrl + Shift + Alt + B` | 🧹 **清理 + 重新编译**            | 删除 configuration-cache 和 build 目录后重新构建                |
| `Ctrl + Alt + B`         | 🔍 **仅检查编译错误**             | 快速检查 Kotlin 编译（最快）                                    |

> ⚠️ **注意:** `Ctrl + Shift + R` 会**完全重新构建**（不依赖缓存），确保安装到手机的是最新的代码。产品 APK 带有时间戳后缀方便识别。

## 📋 日志与调试

| 快捷键             | 功能                | 说明                   |
| ------------------ | ------------------- | ---------------------- |
| `Ctrl + Shift + L` | 📋 **查看 App 日志** | 按 `Ctrl + C` 停止查看 |

---

## 📖 如何运行任务

### 方式一：快捷键（推荐）

直接按上面的快捷键即可运行对应的任务。

### 方式二：通过菜单

```
终端 → 运行任务... → 从列表中选择
```

---

## 💻 常用终端命令

在 VS Code 终端中手动执行：

```bash
# 编译（带时间戳 APK）
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/app-debug_$(date +%Y%m%d_%H%M).apk

# 完全重新编译
rm -rf .gradle/configuration-cache app/build && ./gradlew clean assembleDebug

# 编译 Release APK
./gradlew assembleRelease

# 仅检查 Kotlin 编译
./gradlew compileDebugKotlin

# 清理
rm -rf .gradle/configuration-cache app/build && ./gradlew clean

# 安装到手机（安装最新带时间戳的 APK）
adb install -r app/build/outputs/apk/debug/app-debug_$(date +%Y%m%d_%H%M).apk

# 查看日志
adb logcat -v time | grep -i myauthenticator
```

---

## ⚙️ 项目配置说明

| 文件                          | 说明                                         |
| ----------------------------- | -------------------------------------------- |
| `.vscode/tasks.json`          | 定义所有可运行的任务（编译、安装、日志）     |
| `.vscode/settings.json`       | 项目级 VS Code 设置（JDK、Android SDK 路径） |
| `.vscode/extensions.json`     | 推荐安装的扩展                               |
| `~/.gradle/gradle.properties` | Gradle 全局配置（代理等）                    |

---

## ❓ 常见问题

### Q: 按快捷键没反应？
确保已安装推荐的扩展（打开项目时会提示安装），或在扩展面板手动安装。

### Q: 编译时提示 JAVA_HOME 未设置？
任务配置中已内置环境变量，如果仍报错，检查 `.vscode/tasks.json` 中 `options.env.JAVA_HOME` 路径是否正确。

### Q: adb: 未找到命令？
确保 `$ANDROID_HOME/platform-tools` 在系统 PATH 中（已被 tasks.json 环境变量覆盖），确认该目录下有 `adb` 可执行文件。

### Q: 之前编译的旧版本还在手机上？
先卸载再安装：
```bash
adb uninstall top.leoblog.myauthenticator
adb install app/build/outputs/apk/debug/app-debug_$(date +%Y%m%d_%H%M).apk