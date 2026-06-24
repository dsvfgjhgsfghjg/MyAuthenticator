package top.leoblog.myauthenticator.keepalive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * MIUI 专有优化工具类
 *
 * 检测 MIUI 系统并提供引导用户进行保活优化的功能：
 * 1. 打开自启动管理
 * 2. 关闭省电限制（设置为无限制）
 * 3. 请求忽略电池优化
 *
 * 注意：由于 android.os.SystemProperties 是隐藏 API（@hide），
 * 无法在标准 Android SDK 编译中直接调用。
 * 这里使用 Build.DISPLAY / Build.MANUFACTURER 以及读取系统属性文件来检测 MIUI。
 */
object MiuiOptimizer {

    private const val TAG = "MiuiOptimizer"

    /**
     * 检测是否为 MIUI 系统
     *
     * 方法1：通过 Build.MANUFACTURER 检测（Xiaomi/Redmi/Poco）
     * 方法2：通过 Build.DISPLAY 检测 MIUI 版本标记
     * 方法3：读取 /system/build.prop 中的 ro.miui.ui.version.name
     */
    fun isMiui(): Boolean {
        // 方法1：制造商检测
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) {
            return true
        }

        // 方法2：Build.DISPLAY 检测
        val display = Build.DISPLAY.uppercase()
        if (display.contains("MIUI")) {
            return true
        }

        // 方法3：读取 build.prop
        return try {
            readSystemProperty("ro.miui.ui.version.name").isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取 MIUI 版本号（如 V12, V13, V14）
     */
    fun getMiuiVersion(): String {
        // 方法1：从 Build.DISPLAY 提取
        val display = Build.DISPLAY.uppercase()
        val miuiMatch = Regex("MIUI\\s*(V?\\d+)").find(display)
        if (miuiMatch != null) {
            return "V${miuiMatch.groupValues[1]}"
        }

        // 方法2：读取 build.prop
        return try {
            readSystemProperty("ro.miui.ui.version.name")
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 获取 MIUI 版本整数（用于新旧 API 判断）
     * V12=12, V13=13, V14=14 ...
     */
    fun getMiuiVersionInt(): Int {
        val versionStr = getMiuiVersion()
        return try {
            versionStr.filter { it.isDigit() }.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 读取系统属性（通过解析 /system/build.prop 文件）
     *
     * 避免使用隐藏 API android.os.SystemProperties.get()
     */
    private fun readSystemProperty(propName: String): String {
        try {
            BufferedReader(FileReader("/system/build.prop")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith(propName + "=")) {
                        return l.substringAfter("=").trim()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取系统属性失败: $propName", e)
        }
        return ""
    }

    /**
     * 跳转到 MIUI 自启动管理页面
     *
     * MIUI 自启动管理路径：
     * 安全中心 → 应用管理 → 权限 → 自启动管理
     */
    fun openAutoStartSetting(context: Context): Boolean {
        return try {
            val intent = Intent("miui.intent.action.OP_AUTO_START").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra("package_name", context.packageName)
                putExtra("uid", context.applicationInfo.uid)
            }
            context.startActivity(intent)
            Log.d(TAG, "跳转 MIUI 自启动管理成功")
            true
        } catch (e: Exception) {
            Log.w(TAG, "跳转 MIUI 自启动管理失败，尝试备用方案", e)
            tryFallbackAutoStart(context)
        }
    }

    /**
     * 备用方案：跳转到系统应用信息页
     */
    private fun tryFallbackAutoStart(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "跳转系统应用详情页成功（备用方案）")
            true
        } catch (e: Exception) {
            Log.e(TAG, "备用方案跳转也失败", e)
            false
        }
    }

    /**
     * 跳转到 MIUI 省电管理页面
     *
     * MIUI 12+ 路径：
     * 设置 → 省电与电池 → 场景配置 → 应用配置
     */
    fun openBatterySavingSetting(context: Context): Boolean {
        return try {
            val intent = Intent("miui.intent.action.POWER_SAVING_MANAGER").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "跳转 MIUI 省电管理成功")
            true
        } catch (e: Exception) {
            Log.w(TAG, "跳转 MIUI 省电管理失败，尝试备用方案", e)
            tryFallbackBatterySetting(context)
        }
    }

    /**
     * 备用方案：跳转到系统电池优化白名单
     */
    private fun tryFallbackBatterySetting(context: Context): Boolean {
        return try {
            val intent = Intent().apply {
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "跳转电池优化设置成功（备用方案）")
            true
        } catch (e: Exception) {
            Log.e(TAG, "备用电池方案跳转也失败", e)
            false
        }
    }

    /**
     * 检查是否已忽略电池优化
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    /**
     * 打开系统应用信息页（用户可手动设置自启动、通知权限等）
     */
    fun openAppInfoSetting(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "跳转系统应用详情页成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "跳转系统应用详情页失败", e)
            false
        }
    }

    /**
     * 获取 MIUI 优化建议列表（用于 UI 展示）
     */
    fun getOptimizationSuggestions(context: Context): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()

        // 1. 自启动
        suggestions.add(
            OptimizationSuggestion(
                id = "auto_start",
                title = "开启自启动管理",
                description = "允许应用在开机和后台自动启动",
                icon = "🔓",
                action = { openAutoStartSetting(context) }
            )
        )

        // 2. 省电策略
        if (!isIgnoringBatteryOptimizations(context)) {
            suggestions.add(
                OptimizationSuggestion(
                    id = "battery_optimization",
                    title = "关闭省电限制",
                    description = "将省电策略设为「无限制」，防止后台被杀",
                    icon = "🔋",
                    action = { openBatterySavingSetting(context) }
                )
            )
        }

        // 3. 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suggestions.add(
                OptimizationSuggestion(
                    id = "notification",
                    title = "开启通知权限",
                    description = "保证前台服务通知可见，不被系统静默移除",
                    icon = "🔔",
                    action = { openAppInfoSetting(context) }
                )
            )
        }

        return suggestions
    }

    /**
     * 优化建议数据类
     */
    data class OptimizationSuggestion(
        val id: String,
        val title: String,
        val description: String,
        val icon: String,
        val action: () -> Boolean
    )
}