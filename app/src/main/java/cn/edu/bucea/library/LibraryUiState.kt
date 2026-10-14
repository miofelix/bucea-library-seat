package cn.edu.bucea.library

data class AccountSummary(val id: String, val username: String, val label: String)

enum class CampusAccess { UNKNOWN, CHECKING, REACHABLE, UNREACHABLE }

data class LibraryOverview(
    val available: Int? = null,
    val inUse: Int? = null,
    val awaitingSignIn: Int? = null,
    val displayName: String? = null,
    val notices: List<String> = emptyList(),
    val reservationText: String? = null,
)

data class LibraryUiState(
    val accounts: List<AccountSummary> = emptyList(),
    val activeAccountId: String? = null,
    val autoLogin: Boolean = true,
    val autoSwitch: Boolean = false,
    val themeColor: AppThemeColor = AppThemeColor.BUCEA_PURPLE,
    val busy: Boolean = false,
    val status: String = "连接校园网后，添加账号即可开始",
    val authenticated: Boolean = false,
    val overview: LibraryOverview? = null,
    val overviewStale: Boolean = true,
    val campusAccess: CampusAccess = CampusAccess.UNKNOWN,
)
