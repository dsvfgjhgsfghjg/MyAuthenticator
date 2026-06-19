package top.leoblog.myauthenticator.ui.main

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.model.AuthHistoryRecord
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 认证历史列表适配器
 */
class AuthHistoryAdapter : RecyclerView.Adapter<AuthHistoryAdapter.ViewHolder>() {

    private val records = mutableListOf<AuthHistoryRecord>()

    fun clear() {
        records.clear()
        notifyDataSetChanged()
    }

    fun addAll(newRecords: List<AuthHistoryRecord>) {
        val startPos = records.size
        records.addAll(newRecords)
        notifyItemRangeInserted(startPos, newRecords.size)
    }

    fun getItem(position: Int): AuthHistoryRecord = records[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_auth_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(records[position])
    }

    override fun getItemCount() = records.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStatus = itemView.findViewById<TextView>(R.id.tv_status)
        private val tvDeviceName = itemView.findViewById<TextView>(R.id.tv_device_name)
        private val tvTime = itemView.findViewById<TextView>(R.id.tv_time)
        private val tvChallengeId = itemView.findViewById<TextView>(R.id.tv_challenge_id)

        fun bind(record: AuthHistoryRecord) {
            // 状态图标和颜色
            val (icon, color) = when (record.status) {
                "approved" -> "✅" to Color.parseColor("#4CAF50")
                "rejected" -> "❌" to Color.parseColor("#F44336")
                "expired" -> "⏰" to Color.GRAY
                "pending" -> "⏳" to Color.parseColor("#FF9800")
                else -> "❓" to Color.GRAY
            }
            tvStatus.text = "$icon ${record.status.uppercase()}"
            tvStatus.setTextColor(color)

            tvDeviceName.text = record.deviceName ?: record.deviceId ?: "未知设备"
            tvTime.text = formatTime(record.requestedAt)
            tvChallengeId.text = "ID: ${record.challengeId.take(12)}..."
        }

        private fun formatTime(isoDateTime: String): String {
            return try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val outputFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                val date = inputFormat.parse(isoDateTime)
                date?.let { outputFormat.format(it) } ?: isoDateTime
            } catch (e: Exception) {
                isoDateTime
            }
        }
    }
}