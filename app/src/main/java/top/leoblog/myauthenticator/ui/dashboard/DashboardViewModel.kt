package top.leoblog.myauthenticator.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import top.leoblog.myauthenticator.model.AdminDashboardData
import top.leoblog.myauthenticator.network.AppWebSocketClient

/**
 * 管理后台仪表盘 ViewModel
 *
 * 管理 Dashboard 数据加载状态、成功数据、错误消息、无权限提示。
 */
class DashboardViewModel : ViewModel() {

    private val _dashboardData = MutableLiveData<AdminDashboardData>()
    val dashboardData: LiveData<AdminDashboardData> = _dashboardData

    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _noAbilityMessage = MutableLiveData<String>()
    val noAbilityMessage: LiveData<String> = _noAbilityMessage

    fun onDashboardLoaded(data: AdminDashboardData) {
        _isLoading.value = false
        _errorMessage.value = null
        _noAbilityMessage.value = null
        _dashboardData.value = data
    }

    fun onNoAbility(message: String) {
        _isLoading.value = false
        _errorMessage.value = null
        _noAbilityMessage.value = message
    }

    fun onError(message: String) {
        _isLoading.value = false
        _noAbilityMessage.value = null
        _errorMessage.value = message
    }

    fun loadDashboard(webSocketClient: AppWebSocketClient) {
        _isLoading.value = true
        _errorMessage.value = null
        _noAbilityMessage.value = null
        webSocketClient.requestAdminDashboard()
    }

    /**
     * 重置所有状态（用于重试）
     */
    fun reset() {
        _dashboardData.value = null
        _errorMessage.value = null
        _isLoading.value = false
        _noAbilityMessage.value = null
    }
}