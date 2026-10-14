package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.*
import cn.edu.bucea.library.ui.SeatPresentationPolicy
import cn.edu.bucea.library.ui.SeatVisualState
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** A single sequential, read-only pass over the school's real map endpoints. No polling or retries. */
class RegionOverviewRepository(
    private val maps: NativeMapRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(library: LibraryRepository, clock: Clock = Clock.systemUTC()) :
        this(NativeMapRepository(library), clock)

    suspend fun load(
        account: SavedAccount,
        onProgress: (RegionOverviewState) -> Unit = {},
    ): RegionOverviewState {
        val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).toString()
        var state = RegionOverviewState(date = today, loading = true)
        fun publish() { onProgress(state) }
        fun stop(error: String, verificationUserId: String? = null): RegionOverviewState {
            state = state.copy(
                loading = false,
                error = error,
                verificationRequired = verificationUserId != null,
                verificationUserId = verificationUserId.orEmpty(),
                regions = state.regions.map { region ->
                    if (region.loading) region.copy(loading = false, error = "本次查询未完成：$error") else region
                },
            )
            publish()
            return state
        }
        fun discoveryFailure(scope: String, error: Exception) {
            state = state.copy(discoveryErrors = state.discoveryErrors + "$scope：${errorMessage(error)}")
            publish()
        }
        publish()
        try {
            val options = maps.options(account)
            if (options.dates.none { it.value == today }) {
                return stop("学校当前未提供今日（$today）的区域布局，无法读取今日占用情况")
            }
            val buildings = concreteOptions(options.buildings)
            if (buildings.isEmpty()) return stop("学校暂未提供可读取的馆舍信息")

            // Discover every known room first, so a layout failure cannot hide later regions.
            val knownRooms = mutableSetOf<Pair<String, String>>()
            buildings.forEach { building ->
                val floors = try { concreteOptions(maps.floors(account, building.value)) }
                catch (error: Exception) {
                    if (mustStop(error)) throw error
                    discoveryFailure(building.label, error)
                    return@forEach
                }
                if (floors.isEmpty()) {
                    discoveryFailure(building.label, LoginFailure(FailureKind.PROTOCOL, "未提供楼层信息"))
                    return@forEach
                }
                floors.forEach floorLoop@{ floor ->
                    val response = try { maps.rooms(account, today, building.value, floor.value) }
                    catch (error: Exception) {
                        if (mustStop(error)) throw error
                        discoveryFailure("${building.label} ${floor.label}", error)
                        return@floorLoop
                    }
                    if (response.verificationRequired) throw NativeMapRepository.VerificationRequired(response.verificationUserId)
                    if (response.incomplete) discoveryFailure("${building.label} ${floor.label}",
                        LoginFailure(FailureKind.PROTOCOL, response.message))
                    val newRegions = response.rooms.filter { knownRooms.add(building.value to it.id) }.map { room ->
                        RegionSeatOverview(room.id, room.name, building.value, building.label,
                            floor.value, floor.label, today)
                    }
                    state = state.copy(regions = state.regions + newRegions,
                        totalRegions = state.totalRegions + newRegions.size)
                    publish()
                }
            }
            state = state.copy(discoveryComplete = state.discoveryErrors.isEmpty())
            publish()
            if (state.regions.isEmpty()) {
                return stop(if (state.discoveryErrors.isEmpty()) "学校今日暂未提供可读取的阅览区域"
                    else "部分馆舍或楼层未读取成功，暂无可显示的区域")
            }
            for (index in state.regions.indices) {
                val region = state.regions[index]
                val refreshed = try {
                    val layout = maps.layout(account, NativeMapRoom(region.id, region.name), today)
                    if (layout.verificationRequired) throw NativeMapRepository.VerificationRequired(layout.verificationUserId)
                    val counts = counts(layout)
                    if (counts == null) region.copy(loading = false,
                        error = layout.message.ifBlank { "该区域未提供可读取的座位布局" }.take(160))
                    else region.copy(counts = counts, loading = false, updatedAt = clock.millis())
                } catch (error: Exception) {
                    if (mustStop(error)) throw error
                    region.copy(loading = false, error = errorMessage(error))
                }
                state = state.copy(
                    regions = state.regions.toMutableList().apply { set(index, refreshed) },
                    completedRegions = state.completedRegions + 1,
                    updatedAt = refreshed.updatedAt ?: state.updatedAt,
                )
                publish()
            }
            state = state.copy(loading = false, error = when {
                state.discoveryErrors.isNotEmpty() -> "部分馆舍或楼层未读取成功，区域列表可能不完整"
                state.regions.any { it.counts == null } -> "部分区域未读取成功，请查看各区域提示"
                else -> null
            })
            publish()
            return state
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (verification: NativeMapRepository.VerificationRequired) {
            return stop(verification.message.orEmpty(), verification.userId)
        } catch (error: Exception) {
            return stop(errorMessage(error))
        }
    }

    companion object {
        private fun concreteOptions(options: List<LibraryOption>): List<LibraryOption> = options
            .filter { it.value.trim().lowercase() !in setOf("", "null", "all", "-1") &&
                it.label.trim() !in setOf("全部", "所有", "不限", "请选择", "请选择馆舍", "请选择楼层") }
            .distinctBy { it.value }

        private fun mustStop(error: Exception): Boolean = error is CancellationException ||
            error is NativeMapRepository.VerificationRequired ||
            (error is LoginFailure && error.kind in setOf(FailureKind.CREDENTIALS, FailureKind.NETWORK, FailureKind.CAPTCHA)) ||
            (error is IOException && error !is LoginFailure)

        private fun errorMessage(error: Exception): String = when (error) {
            is LoginFailure -> error.message
            is IOException -> "区域查询连接失败，请检查校园网络后重试"
            else -> "区域数据无法读取，请稍后重试"
        }

        /** null is missing data; it must never be displayed as an empty or completely free room. */
        internal fun counts(layout: NativeRoomLayout): RegionSeatCounts? {
            val cells = layout.cells.filter { it.seat != null }.distinctBy { it.seat!!.id }
            if (cells.isEmpty() || layout.verificationRequired) return null
            val categories = IntArray(SeatVisualState.entries.size)
            var localOnly = 0
            cells.forEach { cell ->
                if (cell.localOnly) localOnly++
                else {
                    // available is booking permission; do not convert a current free status to unavailable.
                    val current = SeatPresentationPolicy.visualState(cell.seat!!.status, available = true)
                    categories[current.ordinal]++
                }
            }
            return RegionSeatCounts(cells.size,
                free = categories[SeatVisualState.FREE.ordinal],
                inUse = categories[SeatVisualState.OCCUPIED.ordinal],
                reserved = categories[SeatVisualState.RESERVED.ordinal],
                away = categories[SeatVisualState.AWAY.ordinal],
                unavailable = categories[SeatVisualState.UNAVAILABLE.ordinal],
                unknown = categories[SeatVisualState.UNKNOWN.ordinal],
                localOnly = localOnly)
        }
    }
}
