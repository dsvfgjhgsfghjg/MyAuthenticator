package top.leoblog.myauthenticator.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.model.DashboardHistoryRecord
import top.leoblog.myauthenticator.model.DashboardResponse
import top.leoblog.myauthenticator.network.RetrofitClient
import top.leoblog.myauthenticator.storage.SecureStorage
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Authenticator 主页 Fragment — 显示概览仪表盘
 *
 * 调用 GET /api/auth/app/dashboard 获取聚合数据，展示：
 * - 连接状态 & 当前用户
 * - 核心指标卡片（上次结果、设备数、加密算法）
 * - 最近认证记录列表
 */
class AuthenticatorFragment : Fragment() {

    private lateinit var secureStorage: SecureStorage
    private var _bindingView: View? = null
    private val bindingView get() = _bindingView
      ?: error("AuthenticatorFragment: _bindingView is null — view destroyed")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _bindingView = inflater.inflate(R.layout.fragment_authenticator, container, false)
        return bindingView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        secureStorage = SecureStorage(requireContext())

        setupViewAllHistory()
        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        // 从后台切回时刷新数据
        loadDashboard()
    }

    private fun setupViewAllHistory() {
        bindingView.findViewById<View>(R.id.ll_view_all_history).setOnClickListener {
            // 跳转到 AuthHistoryFragment
            val fragment = AuthHistoryFragment()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, fragment)
                .addToBackStack("auth_history")
                .commit()
        }
    }

    /**
     * 加载 Dashboard 聚合数据
     */
    private fun loadDashboard() {
        val token = secureStorage.getToken() ?: run {
            showLoggedOutState()
            return
        }

        showLoading(true)

        lifecycleScope.launch {
            try {
                val deviceId = secureStorage.getDeviceId()
                val response = RetrofitClient.apiService.getDashboard(
                    authorization = "Bearer $token",
                    deviceId = deviceId
                )
                if (response.isSuccessful && response.body()?.data != null) {
                    val dashboard = response.body()!!.data!!
                    displayDashboard(dashboard)
                } else {
                    android.util.Log.w("Authenticator", "Dashboard API 失败: HTTP ${response.code()}")
                    displayCachedState()
                }
            } catch (e: Exception) {
                android.util.Log.e("Authenticator", "Dashboard API 异常", e)
                displayCachedState()
            } finally {
                showLoading(false)
            }
        }
    }

    /**
     * 显示 Dashboard 数据
     */
    private fun displayDashboard(dashboard: DashboardResponse) {
        if (!isAdded || _bindingView == null) return
        // 1. 状态栏 — 连接状态 + 当前用户
        updateConnectionStatus(true)
        val displayName = dashboard.user.nickname ?: dashboard.user.username
        bindingView.findViewById<TextView>(R.id.tv_current_user).text = "用户: $displayName"

        // 2. 核心指标卡片
        // 上次结果
        val lastResult = dashboard.lastAuthResult
        val tvLastResult = bindingView.findViewById<TextView>(R.id.tv_last_result)
        if (lastResult != null && lastResult.status != null) {
            tvLastResult.text = formatLastResult(lastResult.status)
            tvLastResult.setTextColor(getStatusColor(lastResult.status))
        } else {
            tvLastResult.text = getString(R.string.last_result_empty)
            tvLastResult.setTextColor(resources.getColor(R.color.profile_text_secondary, null))
        }

        // 设备数
        bindingView.findViewById<TextView>(R.id.tv_device_count).text =
            "${dashboard.user.deviceCount}"

        // 加密算法
        val cipherPref = dashboard.device?.cipherPref
            ?: secureStorage.getCipherPref()
            ?: "AES-256-GCM"
        bindingView.findViewById<TextView>(R.id.tv_cipher_pref).text = cipherPref

        // 3. 最近认证记录
        val history = dashboard.recentHistory
        val totalText = "共 ${history.total} 条"
        bindingView.findViewById<TextView>(R.id.tv_history_total).text = totalText

        if (history.records.isNotEmpty()) {
            showHistoryRecords(history.records)
            // 显示"查看全部"
            bindingView.findViewById<View>(R.id.ll_view_all_history).visibility = View.VISIBLE
        } else {
            bindingView.findViewById<TextView>(R.id.tv_history_empty).visibility = View.VISIBLE
            bindingView.findViewById<View>(R.id.ll_view_all_history).visibility = View.GONE
        }

        // 4. 数据缓存到 SecureStorage 供其他页面使用
        cacheDashboardData(dashboard)
    }

    /**
     * 显示最近认证记录列表
     */
    private fun showHistoryRecords(records: List<DashboardHistoryRecord>) {
        val llHistoryList = bindingView.findViewById<LinearLayout>(R.id.ll_history_list)
        // 清空占位文本
        llHistoryList.removeAllViews()

        val inflater = LayoutInflater.from(requireContext())
        val maxRecords = minOf(records.size, 5)

        for (i in 0 until maxRecords) {
            val record = records[i]
            val itemView = inflater.inflate(R.layout.item_auth_history, llHistoryList, false)

            // 状态图标 + 文本
            val tvStatus = itemView.findViewById<TextView>(R.id.tv_status)
            val statusText = when (record.status) {
                "approved" -> "✅ 已批准"
                "rejected" -> "❌ 已拒绝"
                "expired" -> "⏰ 已过期"
                else -> record.status
            }
            tvStatus.text = statusText
            tvStatus.setTextColor(getStatusColor(record.status))

            // 设备名 + 响应耗时
            val tvDeviceName = itemView.findViewById<TextView>(R.id.tv_device_name)
            val deviceName = record.deviceName ?: "未知设备"
            val responseTime = record.responseTimeMs?.let { ms ->
                " · ${ms / 1000}秒内"
            } ?: ""
            tvDeviceName.text = "$deviceName$responseTime"

            // 时间
            val tvTime = itemView.findViewById<TextView>(R.id.tv_time)
            tvTime.text = formatDateTime(record.requestedAt)

            llHistoryList.addView(itemView)
        }
    }

    /**
     * 使用本地缓存的 SecureStorage 数据展示（API 失败时）
     */
    private fun displayCachedState() {
        if (!isAdded || _bindingView == null) return
        val nickname = secureStorage.getNickname() ?: secureStorage.getUsername()
        if (nickname != null) {
            bindingView.findViewById<TextView>(R.id.tv_current_user).text = "用户: $nickname"
        }

        val deviceCount = secureStorage.getProfileDeviceCount()
        if (deviceCount >= 0) {
            bindingView.findViewById<TextView>(R.id.tv_device_count).text = "$deviceCount"
        }

        val cipherPref = secureStorage.getCipherPref()
        if (cipherPref != null) {
            bindingView.findViewById<TextView>(R.id.tv_cipher_pref).text = cipherPref
        }
    }

    /**
     * 未登录状态
     */
    private fun showLoggedOutState() {
        if (!isAdded || _bindingView == null) return
        updateConnectionStatus(false)
        bindingView.findViewById<TextView>(R.id.tv_current_user).text = "用户: 未登录"
        bindingView.findViewById<TextView>(R.id.tv_last_result).text = getString(R.string.last_result_empty)
        bindingView.findViewById<TextView>(R.id.tv_device_count).text = "-"
        bindingView.findViewById<TextView>(R.id.tv_cipher_pref).text = "-"
    }

    /**
     * 更新连接状态指示器
     */
    private fun updateConnectionStatus(connected: Boolean) {
        if (!isAdded || _bindingView == null) return
        val tvStatus = bindingView.findViewById<TextView>(R.id.tv_connection_status)
        if (connected) {
            tvStatus.text = "● 已连接"
            tvStatus.setTextColor(resources.getColor(R.color.status_approved, null))
        } else {
            tvStatus.text = "● 未连接"
            tvStatus.setTextColor(resources.getColor(R.color.status_rejected, null))
        }
    }

    private fun showLoading(show: Boolean) {
        if (!isAdded || _bindingView == null) return
        bindingView.findViewById<ProgressBar>(R.id.progress_bar).visibility =
            if (show) View.VISIBLE else View.GONE
    }

    /**
     * 缓存 Dashboard 数据到 SecureStorage
     */
    private fun cacheDashboardData(dashboard: DashboardResponse) {
        dashboard.user.nickname?.let { secureStorage.saveNickname(it) }
        secureStorage.saveProfileDeviceCount(dashboard.user.deviceCount)
        dashboard.device?.cipherPref?.let { secureStorage.saveCipherPref(it) }
    }

    private fun formatLastResult(status: String): String {
        return when (status) {
            "approved" -> "已批准"
            "rejected" -> "已拒绝"
            "expired" -> "已过期"
            else -> status
        }
    }

    private fun getStatusColor(status: String): Int {
        return when (status) {
            "approved" -> resources.getColor(R.color.status_approved, null)
            "rejected" -> resources.getColor(R.color.status_rejected, null)
            "expired" -> resources.getColor(R.color.status_expired, null)
            else -> resources.getColor(R.color.profile_text_secondary, null)
        }
    }

    private fun formatDateTime(isoDateTime: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val outputFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val date = inputFormat.parse(isoDateTime)
            date?.let { outputFormat.format(it) } ?: isoDateTime
        } catch (e: Exception) {
            isoDateTime
        }
    }

    override fun onDestroyView() {
        _bindingView = null
        super.onDestroyView()
    }
}