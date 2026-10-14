package cn.edu.bucea.library

import cn.edu.bucea.library.data.LoginFailure
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Campus access means the configured library host responds; Wi-Fi names do not decide it. */
internal object CampusAccessPolicy {
    fun shouldRetryOnForeground(access: CampusAccess, busy: Boolean): Boolean =
        access == CampusAccess.UNREACHABLE && !busy

    fun afterFailure(previous: CampusAccess, error: Throwable): CampusAccess = when (error) {
        // All LoginFailure variants come from a server response/protocol decision, including HTTP 5xx.
        is LoginFailure -> CampusAccess.REACHABLE
        is IOException -> CampusAccess.UNREACHABLE
        else -> previous
    }

    suspend fun check(probe: suspend () -> LibraryOverview, update: (CampusAccess) -> Unit): LibraryOverview {
        update(CampusAccess.CHECKING)
        return try {
            probe().also { update(CampusAccess.REACHABLE) }
        } catch (error: CancellationException) {
            update(CampusAccess.UNKNOWN)
            throw error
        } catch (error: Exception) {
            update(afterFailure(CampusAccess.UNKNOWN, error))
            throw error
        }
    }
}
