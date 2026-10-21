package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.LibraryHistoryItem
import cn.edu.bucea.library.data.models.LibraryUserState
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** A refresh uses fresh history, never the previous account's or previous refresh's summary. */
internal object CurrentReservationDetails {
    private enum class Status { RESERVED, IN_USE, AWAY }

    private fun status(value: String): Status? = when (
        value.trim().replace(Regex("^状态\\s*[:：]\\s*"), "").uppercase(Locale.ROOT)
    ) {
        "已预约", "预约", "待签到", "RESERVED", "RESERVE", "BOOKED" -> Status.RESERVED
        "使用中", "已签到", "IN_USE", "USING", "CHECKED_IN", "CHECK_IN" -> Status.IN_USE
        "暂离", "暂离中", "AWAY", "LEAVE" -> Status.AWAY
        else -> null
    }

    private fun matchesStatus(userStatus: String, item: LibraryHistoryItem): Boolean {
        val actual = status(item.status) ?: return false
        if (ReservationStatuses.isInactive(item.status)) return false
        return userStatus == "LOC_RESTRICT" || actual == status(userStatus) ||
            // Temporary leave changes /self, while /history can retain its
            // checked-in label. Both still describe the same occupied booking.
            (status(userStatus) == Status.AWAY && actual == Status.IN_USE) ||
            (userStatus == "COMPLETE" && actual in setOf(Status.IN_USE, Status.AWAY))
    }

    fun isAwaitingCheckIn(item: LibraryHistoryItem): Boolean = status(item.status) == Status.RESERVED

    fun select(
        userStatus: String,
        items: List<LibraryHistoryItem>,
        now: LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
    ): LibraryHistoryItem? {
        val awaitingEnd = userStatus == "COMPLETE"
        val restricted = userStatus == "LOC_RESTRICT"
        if (status(userStatus) == null && !awaitingEnd && !restricted) return null
        val nowMinute = now.hour * 60 + now.minute
        return items.distinctBy { it.id }.filter { item ->
            val actual = status(item.status)
            val date = item.schedule.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val end = minute(item.schedule.end)
            val begin = minute(item.schedule.begin)
            // An occupied seat can still require "结束使用" after its booked end.
            // Only the server's terminal label ends it. Reserved seats expire by time.
            val eligibleDate = date == now.toLocalDate() ||
                (restricted && actual == Status.RESERVED && date != null && date > now.toLocalDate())
            actual != null && matchesStatus(userStatus, item) && eligibleDate &&
                (actual != Status.RESERVED || date != now.toLocalDate() || end == null || end > nowMinute) &&
                (actual == Status.RESERVED || begin == null || begin <= nowMinute)
        }.singleOrNull()
    }

    suspend fun read(
        user: LibraryUserState,
        availableHistory: List<LibraryHistoryItem>?,
        readHistory: suspend () -> List<LibraryHistoryItem>,
        readDetails: suspend (LibraryHistoryItem) -> LibraryHistoryItem,
        now: LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
    ): List<String> = readReservation(user, availableHistory, readHistory, readDetails, now)
        ?.let(::canonicalDetails).orEmpty()

    /** Keep the exact current record so its server-provided actions can be reused. */
    suspend fun readReservation(
        user: LibraryUserState,
        availableHistory: List<LibraryHistoryItem>?,
        readHistory: suspend () -> List<LibraryHistoryItem>,
        readDetails: suspend (LibraryHistoryItem) -> LibraryHistoryItem,
        now: LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
    ): LibraryHistoryItem? {
        if (status(user.status) == null && user.status !in setOf("LOC_RESTRICT", "COMPLETE")) return null
        val items = availableHistory ?: try {
            readHistory()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        // An undated active record can only become current after its receipt supplies
        // an authoritative date. Count it when checking uniqueness, so a second
        // possible record never causes us to guess which reservation is current.
        val candidates = items.distinctBy { it.id }.filter { item ->
            select(user.status, listOf(item), now) != null ||
                (item.schedule.date == null && matchesStatus(user.status, item))
        }
        val selected = candidates.singleOrNull() ?: return null
        if (selected.schedule.date == null && selected.detailPath == null) return null
        val resolved = if (selected.detailPath != null &&
            (selected.schedule.date == null || selected.schedule.begin == null || selected.schedule.end == null)
        ) {
            try {
                readDetails(selected).let { receipt -> receipt.copy(
                    details = ReservationHistoryDetails.merge(selected.details, receipt.details),
                ) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                selected
            }
        } else selected
        // A receipt can reveal that the list item was already completed or canceled.
        if (select(user.status, listOf(resolved), now) == null) return null
        return resolved
    }

    private val dateField = Regex("^\\s*(?:预约|使用)?日期\\s*[:：]")
    private val timeField = Regex("^\\s*(?:预约|使用)?(?:时间|时段|开始时间|结束时间|起始时间|截止时间|开始|结束|起始|截止)\\s*[:：]")

    fun canonicalDetails(item: LibraryHistoryItem): List<String> {
        val schedule = item.schedule
        val completeTime = schedule.begin != null && schedule.end != null
        return buildList {
            ReservationHistoryDetails.canonical(item.details).forEach { detail ->
                when {
                    schedule.date != null && dateField.containsMatchIn(detail) -> Unit
                    completeTime && timeField.containsMatchIn(detail) -> Unit
                    else -> add(detail)
                }
            }
            schedule.date?.let { add("日期：$it") }
            if (completeTime) add("时间：${schedule.begin} — ${schedule.end}")
        }.distinct()
    }

    private fun minute(value: String?): Int? {
        val parts = value?.split(':') ?: return null
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour == 24 && minute == 0) return 1440
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
}
