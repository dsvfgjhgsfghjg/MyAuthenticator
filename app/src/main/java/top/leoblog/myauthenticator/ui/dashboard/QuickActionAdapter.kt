package top.leoblog.myauthenticator.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import top.leoblog.myauthenticator.databinding.ItemQuickActionBinding
import top.leoblog.myauthenticator.model.QuickAction

class QuickActionAdapter(
    private val actions: List<QuickAction>
) : RecyclerView.Adapter<QuickActionAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQuickActionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(actions[position])
    }

    override fun getItemCount() = actions.size

    class ViewHolder(private val binding: ItemQuickActionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(action: QuickAction) {
            binding.actionLabel.text = action.label
            binding.actionIcon.text = getActionIcon(action.icon)
        }
    }

    companion object {
        /**
         * 根据 icon 名称返回对应的 emoji 图标
         */
        private fun getActionIcon(icon: String): String {
            return when (icon) {
                "edit" -> "📝"
                "list" -> "📋"
                "message-square" -> "💬"
                "users" -> "👥"
                "settings" -> "⚙️"
                "image" -> "🖼️"
                else -> "📌"
            }
        }
    }
}