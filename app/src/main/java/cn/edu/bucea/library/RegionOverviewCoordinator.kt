package cn.edu.bucea.library

import cn.edu.bucea.library.data.models.RegionOverviewState
import cn.edu.bucea.library.data.models.RegionSeatOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Account-scoped refresh/cache policy; a serialized loader can also be exercised without Android. */
internal class RegionOverviewCoordinator(
    private val scope: CoroutineScope,
    private val load: suspend (String, (RegionOverviewState) -> Unit) -> RegionOverviewState,
    private val clock: Clock = Clock.system(SHANGHAI),
    private val userMessage: (Throwable) -> String = { it.message ?: "阅览区域查询失败，请稍后刷新" },
) {
    private val mutable = MutableStateFlow(RegionOverviewState())
    val state = mutable.asStateFlow()
    private val requestGate = Mutex()
    @Volatile private var generation = 0L
    @Volatile private var accountId: String? = null
    @Volatile private var authenticated = false
    private var operation: Job? = null
    private var requestedDay: LocalDate? = null
    private var lastAttemptAt: Long? = null
    private var lastAttemptDay: LocalDate? = null

    fun bindAccount(id: String?, loggedIn: Boolean) {
        if (accountId == id && authenticated == loggedIn) return
        invalidateRequest()
        accountId = id
        authenticated = loggedIn
        lastAttemptAt = null
        lastAttemptDay = null
        mutable.value = RegionOverviewState()
    }

    fun ensureFresh() = request(force = false)

    fun refresh() = request(force = true)

    fun onVerified() {
        mutable.update { it.copy(verificationRequired = false, verificationUserId = "", error = null) }
        refresh()
    }

    private fun request(force: Boolean) {
        val id = accountId?.takeIf { authenticated } ?: return
        val today = LocalDate.now(clock.withZone(SHANGHAI))
        val now = clock.millis()
        if (operation?.isActive == true) {
            if (requestedDay == today) return
            invalidateRequest()
        }
        val previous = mutable.value
        val sameDay = previous.date == today.toString()
        if (!force && sameDay && previous.verificationRequired) return
        if (!force && sameDay && !previous.stale && previous.updatedAt?.let { elapsed(now, it) < REFRESH_MILLIS } == true) return
        if (!force && lastAttemptDay == today && lastAttemptAt?.let { elapsed(now, it) < REFRESH_MILLIS } == true) return

        val token = ++generation
        val cached = previous.regions.associateBy { it.buildingId to it.id }
        val lastSuccess = previous.updatedAt
        lastAttemptAt = now
        lastAttemptDay = today
        requestedDay = today
        mutable.value = previous.copy(
            date = today.toString(),
            loading = true,
            regions = previous.regions.map { it.copy(loading = true, error = null, stale = it.counts != null) },
            completedRegions = 0,
            discoveryComplete = false,
            discoveryErrors = emptyList(),
            error = null,
            verificationRequired = false,
            verificationUserId = "",
            stale = previous.regions.any { it.counts != null },
        )
        operation = scope.launch {
            val requestJob = currentCoroutineContext()[Job]!!
            fun current() = generation == token && accountId == id && authenticated && requestJob.isActive
            fun publish(value: RegionOverviewState, loading: Boolean, success: Boolean = false) {
                mutable.update { existing ->
                    if (!current()) existing else merge(value, cached, lastSuccess, loading, success)
                }
            }
            try {
                val result = requestGate.withLock {
                    currentCoroutineContext().ensureActive()
                    load(id) { progress -> publish(progress, loading = true) }
                }
                currentCoroutineContext().ensureActive()
                val complete = result.discoveryComplete && result.discoveryErrors.isEmpty() &&
                    result.error == null && !result.verificationRequired &&
                    result.completedRegions == result.totalRegions && result.regions.size == result.totalRegions &&
                    result.regions.all { it.counts != null && it.error == null && !it.loading }
                publish(result, loading = false, success = complete)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update { existing ->
                    if (!current()) existing else existing.copy(
                        loading = false,
                        regions = existing.regions.map { it.copy(loading = false, stale = it.counts != null) },
                        error = userMessage(error),
                        stale = true,
                        updatedAt = lastSuccess,
                    )
                }
            } finally {
                if (generation == token) {
                    operation = null
                    mutable.update { it.copy(loading = false) }
                }
            }
        }
    }

    private fun merge(
        incoming: RegionOverviewState,
        cached: Map<Pair<String, String>, RegionSeatOverview>,
        lastSuccess: Long?,
        loading: Boolean,
        success: Boolean,
    ): RegionOverviewState {
        val refreshed = incoming.regions.map { region ->
            val previous = cached[region.buildingId to region.id]
            if (region.counts == null && previous?.counts != null) region.copy(
                counts = previous.counts,
                date = previous.date,
                updatedAt = previous.updatedAt,
                stale = true,
            ) else region
        }
        val retained = if (incoming.discoveryComplete) emptyList() else cached.values
            .filter { old -> refreshed.none { it.buildingId == old.buildingId && it.id == old.id } }
            .map { it.copy(loading = loading, stale = it.counts != null) }
        return incoming.copy(
            regions = refreshed + retained,
            loading = loading,
            updatedAt = if (success) clock.millis() else lastSuccess,
            stale = !success,
            error = incoming.error ?: if (!loading && !success && !incoming.verificationRequired &&
                (incoming.discoveryErrors.isNotEmpty() || incoming.regions.any { it.error != null }))
                "部分阅览区域未能更新，请稍后刷新" else null,
        )
    }

    private fun invalidateRequest() {
        generation++
        operation?.cancel()
        operation = null
        requestedDay = null
    }

    fun close() {
        invalidateRequest()
    }

    companion object {
        const val REFRESH_MILLIS = 2 * 60 * 1000L
        private val SHANGHAI = ZoneId.of("Asia/Shanghai")
        private fun elapsed(now: Long, then: Long): Long = if (now < then) Long.MAX_VALUE else now - then
    }
}
