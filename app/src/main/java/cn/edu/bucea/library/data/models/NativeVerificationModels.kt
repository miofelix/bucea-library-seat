package cn.edu.bucea.library.data.models

/** Sensitive server values are scoped to one account and never displayed or logged. */
data class VerificationContext(
    val accountId: String,
    val userId: String = "",
    val username: String = "",
    val sysToken: String = "",
)

data class VerificationPoint(val x: Double, val y: Double)

data class VerificationChallenge(
    val accountId: String,
    val token: String,
    val requiredPoints: Int,
    val image: ByteArray,
    val wordImage: ByteArray,
    val issuedAt: Long,
    val width: Int = 330,
    val height: Int = 160,
)

data class VerificationProof(val accountId: String, val authId: String, val validatedAt: Long)

data class NativeVerificationState(
    val context: VerificationContext? = null,
    val challenge: VerificationChallenge? = null,
    val points: List<VerificationPoint> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val proof: VerificationProof? = null,
)
