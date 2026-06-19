package top.leoblog.myauthenticator.ui.main

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.model.UserProfileResponse
import top.leoblog.myauthenticator.network.BugReportManager
import top.leoblog.myauthenticator.network.NetworkConfig
import top.leoblog.myauthenticator.network.RetrofitClient
import top.leoblog.myauthenticator.storage.SecureStorage
import top.leoblog.myauthenticator.ui.login.LoginActivity
import top.leoblog.myauthenticator.ui.debug.DebugInfoFragment
import top.leoblog.myauthenticator.ui.security.SecurityInfoFragment
import top.leoblog.myauthenticator.ui.settings.SettingsActivity
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 我的 Fragment — 展示用户个人信息、安全信息和菜单列表
 *
 * 增强版：调用 v2 API GET /api/user/profile 获取完整信息
 * 使用 Coil 加载头像
 */
class ProfileFragment : Fragment() {

    private lateinit var secureStorage: SecureStorage
    private var _bindingView: View? = null
    private val bindingView get() = _bindingView!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _bindingView = inflater.inflate(R.layout.fragment_profile, container, false)
        return bindingView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        secureStorage = SecureStorage(requireContext())

        setupViews()
        setupSwipeRefresh()
        loadProfile()
    }

    private fun setupViews() {
        // 设置菜单点击事件
        bindingView.findViewById<View>(R.id.ll_menu_settings).setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        // 设备信息菜单项
        bindingView.findViewById<View>(R.id.ll_menu_security).setOnClickListener {
            val fragment = SecurityInfoFragment()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, fragment)
                .addToBackStack("security_info")
                .commit()
        }

        // 认证历史菜单项
        bindingView.findViewById<View>(R.id.ll_menu_history).setOnClickListener {
            val fragment = AuthHistoryFragment()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, fragment)
                .addToBackStack("auth_history")
                .commit()
        }

        // 调试信息菜单项
        bindingView.findViewById<View>(R.id.ll_menu_debug).setOnClickListener {
            val fragment = DebugInfoFragment()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, fragment)
                .addToBackStack("debug_info")
                .commit()
        }

        // 提交反馈菜单项
        bindingView.findViewById<View>(R.id.ll_menu_feedback).setOnClickListener {
            if (secureStorage.getToken() == null) {
                Toast.makeText(requireContext(), "请先登录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBugReportDialog()
        }

        // 切换账号
        bindingView.findViewById<View>(R.id.ll_menu_switch_account).setOnClickListener {
            secureStorage.clearAll()
            Toast.makeText(requireContext(), "已退出，请重新登录", Toast.LENGTH_SHORT).show()
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            requireActivity().finish()
        }
    }

    private fun setupSwipeRefresh() {
        bindingView.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh).setOnRefreshListener {
            loadProfile()
        }
    }

    private fun loadProfile() {
        // 先从本地加载基础信息
        val username = secureStorage.getUsername() ?: "用户"
        bindingView.findViewById<TextView>(R.id.tv_nickname).text = username

        val token = secureStorage.getToken() ?: return

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.getUserProfile(
                    authorization = "Bearer $token"
                )
                if (response.isSuccessful && response.body()?.data != null) {
                    displayProfile(response.body()!!.data!!)
                }
            } catch (e: Exception) {
                android.util.Log.e("Profile", "获取用户信息失败", e)
                // 静默失败，本地已有基础信息
            } finally {
                bindingView.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh).isRefreshing = false
            }
        }
    }

    private fun displayProfile(profile: UserProfileResponse) {
        // 头像 — 处理后端返回的相对路径
        profile.avatarUrl?.let { avatarUrl ->
            if (avatarUrl.isNotBlank()) {
                val fullUrl = if (avatarUrl.startsWith("http")) {
                    avatarUrl
                } else {
                    // 相对路径，拼接 REST API 基础 URL
                    val base = NetworkConfig.restBaseUrl.trimEnd('/')
                    val path = avatarUrl.trimStart('/')
                    "$base/$path"
                }
                bindingView.findViewById<android.widget.ImageView>(R.id.iv_avatar).let { imageView ->
                    imageView.load(fullUrl) {
                        placeholder(R.drawable.ic_people)
                        error(R.drawable.ic_people)
                        crossfade(true)
                    }
                }
            }
        }

        //
        profile.boundAt?.let { boundAt ->
            bindingView.findViewById<TextView>(R.id.tv_bound_at).text = formatDateTime(boundAt)
        } ?: run {
            bindingView.findViewById<TextView>(R.id.tv_bound_at).text = "尚未绑定"
        }

        // 最后登录时间
        profile.lastLoginAt?.let { lastLogin ->
            bindingView.findViewById<TextView>(R.id.tv_last_login).text = "最后登录: ${formatDateTime(lastLogin)}"
        }
    }

    private fun formatDateTime(isoDateTime: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val date = inputFormat.parse(isoDateTime)
            date?.let { outputFormat.format(it) } ?: isoDateTime
        } catch (e: Exception) {
            isoDateTime
        }
    }

    // ===== Bug 日志上报 =====

    /**
     * 显示 Bug 上报对话框
     */
    private fun showBugReportDialog() {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_bug_report)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etDescription = dialog.findViewById<TextInputEditText>(R.id.et_description)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btn_cancel)
        val btnSubmit = dialog.findViewById<MaterialButton>(R.id.btn_submit)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.progress_bar)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val description = etDescription.text?.toString()?.trim() ?: ""
            if (description.isEmpty()) {
                Toast.makeText(requireContext(), R.string.bug_report_empty_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 禁用按钮，显示进度
            btnSubmit.isEnabled = false
            btnCancel.isEnabled = false
            progressBar.visibility = View.VISIBLE

            submitBugReport(description, dialog)
        }

        dialog.show()
    }

    /**
     * 提交 Bug 报告
     */
    private fun submitBugReport(description: String, dialog: Dialog) {
        lifecycleScope.launch {
            val bugReportManager = BugReportManager.getInstance(
                requireContext(),
                RetrofitClient.apiService
            )

            // 构建日志内容：用户描述 + 系统信息
            val logText = buildString {
                appendLine("=== 用户反馈 ===")
                appendLine(description)
                appendLine()
                appendLine("=== 系统信息 ===")
                appendLine("App 版本: ${getAppVersion()}")
                appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("系统: Android ${android.os.Build.VERSION.RELEASE}")
            }

            val result = bugReportManager.submitLog(
                logText = logText,
                summary = "用户反馈: ${description.take(50)}"
            )

            result.onSuccess {
                dialog.dismiss()
                Toast.makeText(requireContext(), R.string.bug_report_success, Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                val btnCancel = dialog.findViewById<MaterialButton>(R.id.btn_cancel)
                val btnSubmit = dialog.findViewById<MaterialButton>(R.id.btn_submit)
                val progressBar = dialog.findViewById<ProgressBar>(R.id.progress_bar)
                btnSubmit.isEnabled = true
                btnCancel.isEnabled = true
                progressBar.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    getString(R.string.bug_report_failed, error.message ?: "未知错误"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pkgInfo = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
            pkgInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    override fun onDestroyView() {
        _bindingView = null
        super.onDestroyView()
    }
}
