package top.leoblog.myauthenticator.model

/**
 * 管理后台仪表盘数据模型
 *
 * 对应 APP_ADMIN_DASHBOARD_WS_GUIDE.md 成功响应格式
 */
data class AdminDashboardData(
    val loginInfo: LoginInfo,
    val statsCards: StatsCards,
    val lastUpdateTime: String?,
    val quickActions: List<QuickAction>,
    val recentActivities: List<RecentActivity>
)

data class LoginInfo(
    val currentIp: String?,
    val lastLoginIp: String?,
    val lastLoginTime: String?,
    val loginCount: Long
)

data class StatsCards(
    val totalUsers: TotalCount,
    val totalPosts: TotalCount,
    val todayVisits: TotalCount
)

data class TotalCount(
    val current: Long,
    val percentageChange: Double,
    val trend: String  // "up", "down", "flat"
)

data class QuickAction(
    val action: String,
    val label: String,
    val icon: String,
    val route: String
)

data class RecentActivity(
    val id: Long,
    val type: String,
    val content: String,
    val time: String,
    val username: String?
)