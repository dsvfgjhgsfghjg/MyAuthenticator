package top.leoblog.myauthenticator.ui.challenge

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.model.ChallengeMessage

/**
 * 3 选 1 挑战对话框（增强版）
 *
 * 对应 APP_PUSH_CHALLENGE_POPUP_GUIDE.md 规范：
 * - 不可关闭：无返回键、无外部点击关闭
 * - 选择后切换加载状态，不立即关闭
 * - 显示认证结果后自动关闭
 * - 倒计时基于服务端 expiresAt（Unix 秒）
 */
class ChallengeDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_CHALLENGE_ID = "challenge_id"
        private const val ARG_OPTION_1 = "option_1"
        private const val ARG_OPTION_2 = "option_2"
        private const val ARG_OPTION_3 = "option_3"
        private const val ARG_EXPIRES_AT = "expires_at"

        fun newInstance(challenge: ChallengeMessage): ChallengeDialogFragment {
            val fragment = ChallengeDialogFragment()
            val args = Bundle().apply {
                putString(ARG_CHALLENGE_ID, challenge.challengeId)
                if (challenge.numbers.size >= 3) {
                    putInt(ARG_OPTION_1, challenge.numbers[0])
                    putInt(ARG_OPTION_2, challenge.numbers[1])
                    putInt(ARG_OPTION_3, challenge.numbers[2])
                }
                putLong(ARG_EXPIRES_AT, challenge.expiresAt)
            }
            fragment.arguments = args
            return fragment
        }
    }

    var onNumberSelected: ((challengeId: String, selectedNumber: Int) -> Unit)? = null

    private var challengeId: String = ""
    private var options = listOf(0, 0, 0)
    private var expiresAt: Long = 0L
    private var countDownTimer: CountDownTimer? = null
    private var optionButtons: List<Button> = emptyList()
    private lateinit var optionsContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvCountdown: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            challengeId = it.getString(ARG_CHALLENGE_ID, "")
            options = listOf(
                it.getInt(ARG_OPTION_1, 0),
                it.getInt(ARG_OPTION_2, 0),
                it.getInt(ARG_OPTION_3, 0)
            )
            expiresAt = it.getLong(ARG_EXPIRES_AT, 0)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = object : Dialog(requireActivity()) {
            override fun onBackPressed() {
                // 拦截返回键 — 弹窗不可关闭
            }
        }
        dialog.setContentView(R.layout.dialog_challenge)

        // 禁止外部点击关闭
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)

        // 设置弹窗宽度
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // 初始化视图
        optionsContainer = dialog.findViewById(R.id.options_container)
        progressBar = dialog.findViewById(R.id.progress_bar)
        tvCountdown = dialog.findViewById(R.id.tv_countdown)
        tvStatus = dialog.findViewById(R.id.tv_status)

        optionButtons = listOf(
            dialog.findViewById(R.id.btn_option_1),
            dialog.findViewById(R.id.btn_option_2),
            dialog.findViewById(R.id.btn_option_3)
        )

        // 设置 3 个选项数字
        options.forEachIndexed { index, value ->
            optionButtons[index].text = value.toString()
            optionButtons[index].setOnClickListener {
                onOptionSelected(value)
            }
        }

        // 启动倒计时
        startCountdown()

        return dialog
    }

    /**
     * 用户选择某个数字
     */
    private fun onOptionSelected(selectedNumber: Int) {
        // 禁用所有按钮，防止重复点击
        optionButtons.forEach { it.isEnabled = false }
        countDownTimer?.cancel()

        // 切换到加载状态（不关闭弹窗）
        showLoadingState()

        // 回调 — 发送 challenge_response
        onNumberSelected?.invoke(challengeId, selectedNumber)
    }

    /**
     * 切换到加载状态 — 用户选择后、等待结果前
     */
    private fun showLoadingState() {
        optionsContainer.visibility = View.GONE
        tvCountdown.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "⏳ 验证中..."
        tvStatus.setTextColor(Color.parseColor("#666666"))
    }

    /**
     * 切换到过期状态
     */
    private fun showExpiredState() {
        optionsContainer.visibility = View.GONE
        tvCountdown.visibility = View.GONE
        progressBar.visibility = View.GONE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "⏰ 挑战已过期"
        tvStatus.setTextColor(Color.parseColor("#E6A23C"))

        // 3 秒后自动关闭
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAdded) dismiss()
        }, 3000)
    }

    /**
     * 显示认证结果（由外部回调调用）
     */
    fun showResult(status: String, reason: String?) {
        if (!isAdded) return

        progressBar.visibility = View.GONE
        tvStatus.visibility = View.VISIBLE

        when (status) {
            "approved" -> {
                tvStatus.text = "✅ 验证通过"
                tvStatus.setTextColor(Color.parseColor("#67C23A"))
                // 1.5 秒后自动关闭
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isAdded) dismiss()
                }, 1500)
            }
            "rejected" -> {
                tvStatus.text = "❌ 验证失败：${reason ?: "数字选择错误"}"
                tvStatus.setTextColor(Color.parseColor("#F56C6C"))
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isAdded) dismiss()
                }, 3000)
            }
            "expired" -> {
                tvStatus.text = "⏰ ${reason ?: "挑战已过期"}"
                tvStatus.setTextColor(Color.parseColor("#E6A23C"))
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isAdded) dismiss()
                }, 3000)
            }
        }
    }

    /**
     * 倒计时 — 基于服务端 expiresAt（Unix 秒）
     */
    private fun startCountdown() {
        val remainingMs = (expiresAt * 1000) - System.currentTimeMillis()

        if (remainingMs <= 0) {
            showExpiredState()
            return
        }

        countDownTimer = object : CountDownTimer(remainingMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                tvCountdown.text = "⏱ 剩余 $seconds 秒"
            }

            override fun onFinish() {
                showExpiredState()
            }
        }.start()
    }

    override fun onDestroyView() {
        countDownTimer?.cancel()
        super.onDestroyView()
    }
}