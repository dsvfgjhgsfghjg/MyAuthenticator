package top.leoblog.myauthenticator.ui.security

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.model.AlgorithmInfo
import top.leoblog.myauthenticator.model.CipherInfoResponse
import top.leoblog.myauthenticator.network.RetrofitClient
import top.leoblog.myauthenticator.storage.SecureStorage

/**
 * 安全信息 Fragment — 展示加密算法信息
 *
 * 对应 v2 API: GET /api/auth/app/cipher-info
 */
class SecurityInfoFragment : Fragment() {

    private lateinit var secureStorage: SecureStorage
    private var _bindingView: View? = null
    private val bindingView get() = _bindingView!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _bindingView = inflater.inflate(R.layout.fragment_security_info, container, false)
        return bindingView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        secureStorage = SecureStorage(requireContext())
        loadCipherInfo()
    }

    private fun loadCipherInfo() {
        val token = secureStorage.getToken() ?: return

        lifecycleScope.launch {
            try {
                val deviceId = secureStorage.getDeviceId()
                val response = RetrofitClient.apiService.getCipherInfo(
                    authorization = "Bearer $token",
                    deviceId = deviceId
                )
                if (response.isSuccessful && response.body()?.data != null) {
                    displayCipherInfo(response.body()!!.data!!)
                }
            } catch (e: Exception) {
                android.util.Log.e("SecurityInfo", "获取加密算法信息失败", e)
            }
        }
    }

    private fun displayCipherInfo(info: CipherInfoResponse) {
        // 密钥交换算法
        bindingView.findViewById<TextView>(R.id.tv_key_exchange).text = """
            算法: ${info.keyExchange.algorithm}
            密钥长度: ${info.keyExchange.keySize} bit
            群组: ${info.keyExchange.group}
            哈希: ${info.keyExchange.hash}
        """.trimIndent()

        // 数据加密算法
        bindingView.findViewById<TextView>(R.id.tv_data_encryption).text = """
            算法: ${info.dataEncryption.algorithm}
            密钥长度: ${info.dataEncryption.keySize} bit
            IV 长度: ${info.dataEncryption.ivLength} 字节
            Tag 长度: ${info.dataEncryption.tagLength} 字节
            模式: ${info.dataEncryption.mode}
            填充: ${info.dataEncryption.padding}
        """.trimIndent()

        // 设备首选算法
        info.deviceCipherPref?.let { pref ->
            bindingView.findViewById<View>(R.id.card_device_pref).visibility = View.VISIBLE
            bindingView.findViewById<TextView>(R.id.tv_device_cipher_pref).text = pref
        }

        // 可用算法列表
        val container = bindingView.findViewById<LinearLayout>(R.id.layout_available_algorithms)
        container.removeAllViews()
        info.availableAlgorithms.forEach { algo ->
            container.addView(createAlgorithmItemView(algo))
        }
    }

    private fun createAlgorithmItemView(algo: AlgorithmInfo): View {
        val icon = when (algo.type) {
            "key_exchange" -> "🔑"
            "data_encryption" -> "🔒"
            else -> "📦"
        }
        val itemView = LayoutInflater.from(requireContext())
            .inflate(android.R.layout.simple_list_item_2, null, false)
        itemView.findViewById<android.widget.TextView>(android.R.id.text1).apply {
            text = "$icon ${algo.name} (${algo.keySize}-bit)"
            textSize = 15f
        }
        itemView.findViewById<android.widget.TextView>(android.R.id.text2).apply {
            text = algo.description
            textSize = 12f
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        return itemView
    }

    override fun onDestroyView() {
        _bindingView = null
        super.onDestroyView()
    }
}