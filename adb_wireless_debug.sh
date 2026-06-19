#!/usr/bin/env bash
# ============================================================
# ADB 无线调试助手
# 电脑连手机热点后，用此脚本通过 Wi-Fi 安装/调试 App
#
# 使用方法:
#   1. 手机开启「开发者选项」→「无线调试」
#   2. 选「配对码配对」，执行: bash adb_wireless_debug.sh pair <IP:端口> <配对码>
#      例: bash adb_wireless_debug.sh pair 192.168.43.1:39797 123456
#   3. 配对成功后，执行: bash adb_wireless_debug.sh connect <IP:端口>
#      例: bash adb_wireless_debug.sh connect 192.168.43.1:42819
#   4. 安装 APK: bash adb_wireless_debug.sh install
#   5. 查看日志: bash adb_wireless_debug.sh logs
# ============================================================

ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="top.leoblog.myauthenticator"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info() { echo -e "${GREEN}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

case "${1:-help}" in
    pair)
        if [ -z "$2" ] || [ -z "$3" ]; then
            error "用法: $0 pair <IP:端口> <配对码>"
            error "例: $0 pair 192.168.43.1:39797 123456"
            exit 1
        fi
        info "正在配对 $2 ..."
        $ADB pair "$2" "$3"
        ;;
    connect)
        if [ -z "$2" ]; then
            error "用法: $0 connect <IP:端口>"
            error "例: $0 connect 192.168.43.1:42819"
            exit 1
        fi
        info "正在连接 $2 ..."
        $ADB connect "$2"
        ;;
    disconnect)
        info "断开所有无线连接..."
        $ADB disconnect
        ;;
    devices)
        info "已连接的设备:"
        $ADB devices -l
        ;;
    build)
        info "正在构建 Debug APK ..."
        cd "$(dirname "$0")"
        # 先停旧 daemon，再用正确 JDK 以 no-daemon 模式构建（避 VS Code 的 JDK 问题）
        ./gradlew --stop 2>/dev/null
        rm -rf .gradle/configuration-cache app/build
        ./gradlew assembleDebug --no-daemon --no-configuration-cache
        if [ $? -eq 0 ]; then
            info "构建成功!"
        else
            error "构建失败"
            exit 1
        fi
        ;;
    install)
        if [ ! -f "$APK_PATH" ]; then
            warn "APK 不存在，先执行构建..."
            bash "$0" build
        fi
        info "正在安装 APK 到设备..."
        $ADB install -r "$APK_PATH"
        if [ $? -eq 0 ]; then
            info "安装成功!"
        else
            error "安装失败，尝试卸载重装..."
            $ADB uninstall "$PACKAGE"
            $ADB install "$APK_PATH"
        fi
        ;;
    run)
        info "正在启动 App..."
        $ADB shell am start -n "$PACKAGE/.ui.main.MainActivity"
        ;;
    logs)
        info "实时日志（按 Ctrl+C 退出）..."
        $ADB logcat --pid=$($ADB shell pidof -s $PACKAGE) 2>/dev/null || \
        $ADB logcat | grep --line-buffered "$PACKAGE"
        ;;
    clear)
        info "清除 App 数据..."
        $ADB shell pm clear "$PACKAGE"
        ;;
    all)
        # 一键构建+安装+启动
        bash "$0" build && bash "$0" install && bash "$0" run
        ;;
    help|*)
        echo "ADB 无线调试助手"
        echo ""
        echo "配对阶段（首次使用，手机无线调试页面操作）:"
        echo "  $0 pair <IP:端口> <配对码>  配对设备"
        echo ""
        echo "连接阶段（每次调试前执行）:"
        echo "  $0 connect <IP:端口>        连接已配对的设备"
        echo "  $0 devices                  查看已连接设备"
        echo "  $0 disconnect               断开所有连接"
        echo ""
        echo "构建与安装:"
        echo "  $0 build                    构建 Debug APK"
        echo "  $0 install                  安装 APK 到设备"
        echo "  $0 run                      启动 App"
        echo "  $0 all                      一键 构建 + 安装 + 启动"
        echo ""
        echo "调试:"
        echo "  $0 logs                     实时查看 App 日志"
        echo "  $0 clear                    清除 App 数据"
        echo ""
        echo "快速开始（先配对一次，之后每次只需）:"
        echo "  bash $0 connect 192.168.43.1:42819"
        echo "  bash $0 all"
        ;;
esac