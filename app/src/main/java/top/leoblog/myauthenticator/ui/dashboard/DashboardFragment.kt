package top.leoblog.myauthenticator.ui.dashboard

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.JsonObject
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.model.AdminDashboardData
import top.leoblog.myauthenticator.model.TotalCount
import top.leoblog.myauthenticator.service.DashboardCallback
import top.leoblog.myauthenticator.service.WebSocketService

/**
 * 管理后台 Dashboard Fragment
 *
 * 通过加密 WebSocket 通道获取管理后台仪表盘数据并展示。
 * 数据流：WebSocketService → webSocketClient → onAdminDashboardResponse → DashboardFragment → ViewModel → UI
 */
class DashboardFragment : Fragment() {

    companion object {
        private const val TAG = "DashboardFragment"
    }

    private lateinit var viewModel: DashboardViewModel
    private val gson = Gson()

    // 缓存 WebSocket 客户端的回调引用，用于在 onDestroyView 时反注册
    private val dashboardCallback = object : DashboardCallback {
        override fun onDashboardResponse(json: JsonObject) {
            handleDashboardResponse(json)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[DashboardViewModel::class.java]

        // 注册 Dashboard 回调
        WebSocketService.dashboardCallback = dashboardCallback

        // 获取 WebSocket 客户端
        val wsClient = WebSocketService.getWebSocketClient()
        if (wsClient == null) {
            // WebSocket 未连接，显示错误提示，用户可稍后重试
            showError(view, "WebSocket 未连接，请等待连接完成")
            return
        }

        setupViews(view)
        observeViewModel(view)

        // 加载数据
        viewModel.loadDashboard(wsClient)
    }

    override fun onResume() {
        super.onResume()
        // 确保回调已注册（可能在 onPause 时被覆盖）
        WebSocketService.dashboardCallback = dashboardCallback
    }

    override fun onPause() {
        super.onPause()
        // 离开页面时取消回调，避免内存泄漏（与其他 Fragment 的 challengeCallback 模式一致）
        WebSocketService.dashboardCallback = null
    }

    private fun setupViews(view: View) {
        // 重试按钮
        view.findViewById<MaterialButton>(R.id.btn_retry).setOnClickListener {
            val wsClient = WebSocketService.getWebSocketClient()
            if (wsClient == null) {
                showError(view, "WebSocket 未连接，请等待连接完成")
                return@setOnClickListener
            }
            viewModel.reset()
            viewModel.loadDashboard(wsClient)
        }
    }

    private fun observeViewModel(view: View) {
        viewModel.dashboardData.observe(viewLifecycleOwner) { data ->
            if (data == null) return@observe
            val v = viewNullable() ?: return@observe
            bindDashboardData(v, data)
        }

        viewModel.noAbilityMessage.observe(viewLifecycleOwner) { message ->
            if (message == null) return@observe
            val v = viewNullable() ?: return@observe
            showNoAbility(v, message)
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            if (message == null) return@observe
            val v = viewNullable() ?: return@observe
            showError(v, message)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (loading == null) return@observe
            val v = viewNullable() ?: return@observe
            v.findViewById<ProgressBar>(R.id.loading_indicator).visibility =
                if (loading) View.VISIBLE else View.GONE
        }
    }

    /**
     * 安全获取当前 Fragment 的 View，避免生命周期竞争导致 NPE。
     * 在 LiveData observer 中使用，确保 View 可用时才操作 UI。
     */
    private fun viewNullable(): View? {
        if (!isAdded) return null
        return view  // Fragment.getView() — 生命周期安全
    }

    /**
     * 处理 WebSocket 返回的 Dashboard 响应
     */
    private fun handleDashboardResponse(json: JsonObject) {
        val status = json.get("status")?.asString

        when (status) {
            "ok" -> {
                val dataObj = json.getAsJsonObject("data")
                if (dataObj != null) {
                    try {
                        val dashboard = gson.fromJson(dataObj, AdminDashboardData::class.java)
                        requireActivity().runOnUiThread {
                            viewModel.onDashboardLoaded(dashboard)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析 Dashboard 数据失败", e)
                        requireActivity().runOnUiThread {
                            viewModel.onError("数据解析失败: ${e.message}")
                        }
                    }
                } else {
                    requireActivity().runOnUiThread {
                        viewModel.onError("响应数据为空")
                    }
                }
            }
            "no_ability" -> {
                val message = json.get("message")?.asString ?: "请联系超级管理员分配权限"
                requireActivity().runOnUiThread {
                    viewModel.onNoAbility(message)
                }
            }
            else -> {
                val msg = json.get("message")?.asString ?: "未知状态: $status"
                requireActivity().runOnUiThread {
                    viewModel.onError(msg)
                }
            }
        }
    }

    /**
     * 绑定 Dashboard 数据到 UI
     */
    private fun bindDashboardData(view: View, data: AdminDashboardData) {
        // 显示内容，隐藏其他状态
        view.findViewById<View>(R.id.dashboard_content).visibility = View.VISIBLE
        view.findViewById<View>(R.id.no_ability_layout).visibility = View.GONE
        view.findViewById<View>(R.id.error_layout).visibility = View.GONE

        // 登录信息
        view.findViewById<TextView>(R.id.tv_current_ip).text = "当前 IP: ${data.loginInfo.currentIp ?: "unknown"}"
        view.findViewById<TextView>(R.id.tv_last_login_ip).text = "上次登录 IP: ${data.loginInfo.lastLoginIp ?: "未知"}"
        view.findViewById<TextView>(R.id.tv_last_login_time).text = "上次登录: ${data.loginInfo.lastLoginTime ?: "未知"}"
        view.findViewById<TextView>(R.id.tv_login_count).text = "登录次数: ${data.loginInfo.loginCount}"

        // 统计卡片
        view.findViewById<TextView>(R.id.tv_total_users).text = "${data.statsCards.totalUsers.current}"
        view.findViewById<TextView>(R.id.tv_total_users_change).text = formatChange(data.statsCards.totalUsers)
        view.findViewById<TextView>(R.id.tv_total_posts).text = "${data.statsCards.totalPosts.current}"
        view.findViewById<TextView>(R.id.tv_total_posts_change).text = formatChange(data.statsCards.totalPosts)
        view.findViewById<TextView>(R.id.tv_today_visits).text = "${data.statsCards.todayVisits.current}"
        view.findViewById<TextView>(R.id.tv_today_visits_change).text = formatChange(data.statsCards.todayVisits)

        // 快捷操作
        view.findViewById<RecyclerView>(R.id.quick_actions_recycler_view).adapter =
            QuickActionAdapter(data.quickActions)

        // 最近动态
        view.findViewById<RecyclerView>(R.id.recent_activities_recycler_view).adapter =
            RecentActivityAdapter(data.recentActivities)
    }

    /**
     * 格式化百分比变化显示
     */
    private fun formatChange(count: TotalCount): String {
        val arrow = when (count.trend) {
            "up" -> "↑"
            "down" -> "↓"
            else -> "→"
        }
        return "$arrow ${count.percentageChange}%"
    }

    /**
     * 显示无能力提示
     */
    private fun showNoAbility(view: View, message: String) {
        view.findViewById<View>(R.id.dashboard_content).visibility = View.GONE
        view.findViewById<View>(R.id.no_ability_layout).visibility = View.VISIBLE
        view.findViewById<View>(R.id.error_layout).visibility = View.GONE
        view.findViewById<TextView>(R.id.no_ability_message).text = message
    }

    /**
     * 显示错误提示
     */
    private fun showError(view: View, message: String) {
        view.findViewById<View>(R.id.dashboard_content).visibility = View.GONE
        view.findViewById<View>(R.id.no_ability_layout).visibility = View.GONE
        view.findViewById<View>(R.id.error_layout).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.error_text).text = message
    }
}