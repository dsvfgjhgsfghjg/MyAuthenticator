package top.leoblog.myauthenticator.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.network.RetrofitClient
import top.leoblog.myauthenticator.storage.SecureStorage

/**
 * 认证历史 Fragment — 展示历史认证记录
 *
 * 对应 v2 API: GET /api/auth/app/history
 */
class AuthHistoryFragment : Fragment() {

    private lateinit var secureStorage: SecureStorage
    private var _bindingView: View? = null
    private val bindingView get() = _bindingView!!

    private lateinit var historyAdapter: AuthHistoryAdapter
    private var currentPage = 1
    private var totalPages = 1
    private var currentStatus: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _bindingView = inflater.inflate(R.layout.fragment_auth_history, container, false)
        return bindingView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        secureStorage = SecureStorage(requireContext())

        setupRecyclerView()
        setupFilterChips()
        loadHistory()
    }

    private fun setupRecyclerView() {
        historyAdapter = AuthHistoryAdapter()
        bindingView.findViewById<RecyclerView>(R.id.rv_history).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter

            // 加载更多（分页）
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                    if (lastVisibleItem >= historyAdapter.itemCount - 3
                        && currentPage < totalPages) {
                        currentPage++
                        loadHistory()
                    }
                }
            })
        }
    }

    private fun setupFilterChips() {
        val chipGroup = bindingView.findViewById<ChipGroup>(R.id.chip_group)
        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            when {
                checkedIds.contains(R.id.chip_all) -> filterBy(null)
                checkedIds.contains(R.id.chip_approved) -> filterBy("approved")
                checkedIds.contains(R.id.chip_rejected) -> filterBy("rejected")
                checkedIds.contains(R.id.chip_expired) -> filterBy("expired")
            }
        }
    }

    private fun filterBy(status: String?) {
        currentStatus = status
        currentPage = 1
        historyAdapter.clear()
        bindingView.findViewById<TextView>(R.id.tv_empty).visibility = View.GONE
        loadHistory()
    }

    private fun loadHistory() {
        val token = secureStorage.getToken() ?: return

        lifecycleScope.launch {
            bindingView.findViewById<View>(R.id.progress_bar).visibility = View.VISIBLE
            try {
                val response = RetrofitClient.apiService.getAuthHistory(
                    authorization = "Bearer $token",
                    page = currentPage,
                    size = 20,
                    status = currentStatus
                )
                if (response.isSuccessful && response.body()?.data != null) {
                    val pageData = response.body()!!.data!!
                    totalPages = pageData.pages.toInt()
                    historyAdapter.addAll(pageData.records)
                    bindingView.findViewById<TextView>(R.id.tv_empty).visibility =
                        if (historyAdapter.itemCount == 0) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthHistory", "获取认证历史失败", e)
            } finally {
                bindingView.findViewById<View>(R.id.progress_bar).visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        _bindingView = null
        super.onDestroyView()
    }
}