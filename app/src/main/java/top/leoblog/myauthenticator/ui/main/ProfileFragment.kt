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
import top.leoblog.myauthenticator.ui.main.AuthHistoryFragment
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

        // 检查是否已登录，未登录则显示登录提示
        if (secureStorage.getToken() == null) {
            showLoginPrompt()
        } else {
            loadProfile()
        }
    }

    /**
     * 未登录时显示登录提示，隐藏个人信息区域
     */
    private fun showLoginPrompt() {
        bindingView.findViewById<TextView>(R.id.tv_nickname)?.text = "未登录"
        // 隐藏邮箱、设备数、绑定时间、最后登录等字段
        bindingView.findViewById<View>(R.id.tv_email)?.visibility = View.GONE
        bindingView.findViewById<View>(R.id.tv_device_count)?.visibility = View.GONE
        bindingView.findViewById<View>(R.id.tv_bound_at)?.visibility = View.GONE
        bindingView.findViewById<View>(R.id.tv_last_login)?.visibility = View.GONE
        bindingView.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)?.isEnabled = false
        // 将"切换账号"改为"登录/绑定设备"
        val switchAccountView = bindingView.findViewById<android.widget.LinearLayout>(R.id.ll_menu_switch_account)
        switchAccountView?.setOnClickListener {
            val intent = Intent(requireContext(), LoginActivity::class.java)
            startActivity(intent)
        }
        // 找到切换账号行中的 TextView（第2个子View，index=1），修改文字
        if (switchAccountView != null && switchAccountView.childCount > 1) {
            val textView = switchAccountView.getChildAt(1) as? TextView
            textView?.text = "登录 / 绑定设备"
        }
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
                    val profile = response.body()!!.data!!
                    displayProfile(profile)
                    // 缓存 Profile 数据供调试页面使用
                    cacheProfile(profile)
                } else {
                    android.util.Log.w("Profile", "获取用户信息失败: HTTP ${response.code()}, 尝试使用缓存数据")
                    displayCachedProfile()
                }
            } catch (e: Exception) {
                android.util.Log.e("Profile", "获取用户信息异常, 尝试使用缓存数据", e)
                displayCachedProfile()
            } finally {
                bindingView.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh).isRefreshing = false
            }
        }
    }

    /**
     * 当 API 调用失败时，使用 SecureStorage 中的缓存数据显示 Profile
     */
    private fun displayCachedProfile() {
        // 邮箱
        secureStorage.getProfileEmail()?.let { email ->
            bindingView.findViewById<TextView>(R.id.tv_email).text = email
        }

        // 设备数
        val deviceCount = secureStorage.getProfileDeviceCount()
        if (deviceCount >= 0) {
            bindingView.findViewById<TextView>(R.id.tv_device_count).text = "$deviceCount"
        }

        // 头像 — 使用公开头像 API（userId 从本地存获取）
        val userId = secureStorage.getUserId()
        if (userId > 0) {
            val base = NetworkConfig.restBaseUrl.trimEnd('/')
            val avatarApiUrl = "$base/api/users/avatar/public/$userId"
            android.util.Log.d("Profile", "缓存头像 API URL: $avatarApiUrl")
            bindingView.findViewById<android.widget.ImageView>(R.id.iv_avatar).let { imageView ->
                imageView.load(avatarApiUrl) {
                    placeholder(R.drawable.ic_people)
                    error(R.drawable.ic_people)
                    crossfade(true)
                    listener(
                        onSuccess = { _, _ ->
                            android.util.Log.d("Profile", "Coil缓存头像加载成功 url=$avatarApiUrl")
                        },
                        onError = { _, error ->
                            android.util.Log.e("Profile", "Coil缓存头像加载失败 url=$avatarApiUrl, error=${error.throwable.message}")
                        }
                    )
                }
            }
        }

        // 绑定时间
        secureStorage.getProfileBoundAt()?.let { boundAt ->
            bindingView.findViewById<TextView>(R.id.tv_bound_at).text = formatDateTime(boundAt)
        }

        // 最后登录时间
        secureStorage.getProfileLastLoginAt()?.let { lastLogin ->
            bindingView.findViewById<TextView>(R.id.tv_last_login).text = "最后登录: ${formatDateTime(lastLogin)}"
        }
    }

    private fun displayProfile(profile: UserProfileResponse) {
        android.util.Log.d("Profile", "displayProfile: userId=${profile.userId}, avatarUrl=${profile.avatarUrl}, email=${profile.email}, deviceCount=${profile.deviceCount}")

        // 头像 — 使用公开头像 API，忽略 avatarUrl（它是 MinIO 原始路径，不能直接渲染）
        val base = NetworkConfig.restBaseUrl.trimEnd('/')
        val avatarApiUrl = "$base/api/users/avatar/public/${profile.userId}"
        android.util.Log.d("Profile", "头像 API URL: $avatarApiUrl")
        bindingView.findViewById<android.widget.ImageView>(R.id.iv_avatar).let { imageView ->
            imageView.load(avatarApiUrl) {
                placeholder(R.drawable.ic_people)
                error(R.drawable.ic_people)
                crossfade(true)
                listener(
                    onSuccess = { _, _ ->
                        android.util.Log.d("Profile", "Coil头像加载成功 url=$avatarApiUrl")
                    },
                    onError = { _, error ->
                        android.util.Log.e("Profile", "Coil头像加载失败 url=$avatarApiUrl, error=${error.throwable.message}")
                    }
                )
            }
        }

        // 邮箱
        bindingView.findViewById<TextView>(R.id.tv_email).text = profile.email ?: ""

        // 已绑定设备数
        bindingView.findViewById<TextView>(R.id.tv_device_count).text = "${profile.deviceCount}"
        android.util.Log.d("Profile", "deviceCount = ${profile.deviceCount}")

        // 绑定时间
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

    /**
     * 缓存 Profile 数据到 SecureStorage，供调试页面使用
     */
    private fun cacheProfile(profile: UserProfileResponse) {
        profile.email?.let { secureStorage.saveProfileEmail(it) }
        secureStorage.saveProfileDeviceCount(profile.deviceCount)
        profile.avatarUrl?.let { secureStorage.saveProfileAvatarUrl(it) }
        profile.boundAt?.let { secureStorage.saveProfileBoundAt(it) }
        profile.lastLoginAt?.let { secureStorage.saveProfileLastLoginAt(it) }
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