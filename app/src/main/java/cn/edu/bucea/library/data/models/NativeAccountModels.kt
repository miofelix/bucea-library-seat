package cn.edu.bucea.library.data.models

enum class PasswordChangeOutcome { SUCCESS, REJECTED, UNKNOWN }

data class PasswordChangeResult(
    val outcome: PasswordChangeOutcome,
    val message: String,
    val credentialsSaved: Boolean = false,
)

data class NativeAccountUiState(
    val accountLabel: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val passwordChanged: Boolean = false,
    val credentialsSaved: Boolean = false,
    val outcomeUncertain: Boolean = false,
)
