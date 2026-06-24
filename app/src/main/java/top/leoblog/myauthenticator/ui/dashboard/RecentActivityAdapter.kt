package top.leoblog.myauthenticator.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import top.leoblog.myauthenticator.databinding.ItemRecentActivityBinding
import top.leoblog.myauthenticator.model.RecentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentActivityAdapter(
    private val activities: List<RecentActivity>
) : RecyclerView.Adapter<RecentActivityAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentActivityBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(activities[position])
    }

    override fun getItemCount() = activities.size

    class ViewHolder(private val binding: ItemRecentActivityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(activity: RecentActivity) {
            binding.activityContent.text = activity.content
            binding.activityUsername.text = activity.username ?: "系统"
            binding.activityTime.text = formatTime(activity.time)
            binding.activityTypeIcon.text = getActivityIcon(activity.type)
        }

        /**
         * 格式化 ISO 时间显示
         */
        private fun formatTime(isoTime: String): String {
            return try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val outputFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                val date = inputFormat.parse(isoTime)
                date?.let { outputFormat.format(it) } ?: isoTime
            } catch (e: Exception) {
                isoTime
            }
        }

        companion object {
            /**
             * 根据动态类型返回对应的 emoji 图标
             */
            private fun getActivityIcon(type: String): String {
                return when (type) {
                    "post_publish" -> "📝"
                    "user_register" -> "👤"
                    "comment" -> "💬"
                    "user_login" -> "🔑"
                    "post_update" -> "✏️"
                    "post_delete" -> "🗑️"
                    "system" -> "⚙️"
                    else -> "📌"
                }
            }
        }
    }
}