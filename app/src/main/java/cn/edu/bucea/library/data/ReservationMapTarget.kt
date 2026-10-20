package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.LibraryOption
import cn.edu.bucea.library.data.models.NativeMapOptions
import cn.edu.bucea.library.data.models.NativeMapRoom
import cn.edu.bucea.library.data.models.NativeMapState
import cn.edu.bucea.library.data.models.ReservationMapTarget
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Resolve human-readable reservation data against the school's fresh, date-specific map. */
internal suspend fun NativeMapRepository.loadReservation(
    account: SavedAccount,
    target: ReservationMapTarget,
    today: LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai")),
): NativeMapState {
    require(target.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
        runCatching { LocalDate.parse(target.date) }.isSuccess) { "预约日期不明确，无法定位座位" }
    val seatLabel = normalizedMapText(target.seatLabel)
    require(seatLabel.isNotEmpty()) { "预约座位号不明确，无法定位座位" }
    val location = reservationRoomLocation(target.location, seatLabel)
    require(location.isNotEmpty()) { "预约区域不明确，无法定位座位" }
    val choices = options(account)
    val dates = concreteMapOptions(choices.dates)
    val buildings = concreteMapOptions(choices.buildings)
    val reservationDay = LocalDate.parse(target.date)
    val layoutDate = when {
        dates.any { it.value == target.date } -> target.date
        reservationDay < today && dates.any { it.value == today.toString() } -> today.toString()
        reservationDay < today -> error("学校未提供 ${target.date} 的历史布局，且暂无 $today 的当前地图，请稍后重试")
        else -> error("学校当前未提供 ${target.date} 的座位地图，请稍后重试")
    }
    val layoutDateNotice = if (layoutDate != target.date)
        "预约日期 ${target.date}；当前布局日期 $layoutDate，座位状态以当前布局为准" else null
    check(buildings.isNotEmpty()) { "学校未提供场馆信息，暂时无法定位预约座位" }

    data class Match(val building: LibraryOption, val floor: LibraryOption, val room: NativeMapRoom,
        val floors: List<LibraryOption>, val rooms: List<NativeMapRoom>)
    val matches = mutableListOf<Match>()
    for (building in buildings) {
        val floors = concreteMapOptions(floors(account, building.value))
        check(floors.isNotEmpty()) { "${building.label} 未提供楼层信息，暂时无法准确定位预约座位" }
        for (floor in floors) {
            val rooms = rooms(account, layoutDate, building.value, floor.value)
            if (rooms.verificationRequired) throw NativeMapRepository.VerificationRequired(rooms.verificationUserId)
            check(!rooms.incomplete) { "部分区域信息未能读取，暂时无法准确定位预约座位，请重试" }
            rooms.rooms.filter { matchesReservationRoom(location, building.label, floor.label, it.name) }
                .forEach { room -> matches += Match(building, floor, room, floors, rooms.rooms) }
        }
    }
    val distinctMatches = matches.distinctBy { Triple(it.building.value, it.floor.value, it.room.id) }
    check(distinctMatches.isNotEmpty()) { "学校地图中未找到预约区域“${target.location}”，请刷新预约后重试" }
    check(distinctMatches.size == 1) { "预约区域对应多个地图位置，暂时无法准确定位，请查看完整预约信息" }
    val selected = distinctMatches.single()
    val layout = layout(account, selected.room, layoutDate)
    val state = NativeMapState(
        directRegionView = true,
        options = NativeMapOptions(dates, buildings, selected.floors),
        filters = mapOf("onDate" to layoutDate, "building" to selected.building.value, "floor" to selected.floor.value),
        rooms = selected.rooms, layout = layout,
        notice = listOfNotNull(layoutDateNotice, layout.message.takeIf(String::isNotBlank)).joinToString("\n").ifBlank { null },
        verificationRequired = layout.verificationRequired, verificationUserId = layout.verificationUserId,
        reservationDate = target.date,
    )
    if (layout.verificationRequired) return state
    // Availability affects booking, not the identity or position of a reserved seat.
    val seats = layout.cells.mapNotNull { it.seat }.filter { normalizedMapText(it.label) == seatLabel }
    check(seats.isNotEmpty()) { "$layoutDate 的区域地图中未找到 ${target.seatLabel} 号座位，请刷新预约后重试" }
    check(seats.size == 1) { "地图中有多个 ${target.seatLabel} 号座位，暂时无法准确定位" }
    val seat = seats.single()
    return state.copy(focusSeatId = seat.id, focusSeatLabel = seat.label)
}

private fun concreteMapOptions(options: List<LibraryOption>): List<LibraryOption> = options
    .filterNot { it.value.isBlank() || it.value == "null" }.distinctBy { it.value }

private fun reservationRoomLocation(location: String, seatLabel: String): String {
    val text = normalizedMapText(location.replace(Regex("^\\s*(?:位置|地点|区域|房间)\\s*[:：]\\s*"), ""))
    return text.replace(Regex("(?:座位(?:号)?)?${Regex.escape(seatLabel)}(?:号(?:座位)?)?$"), "")
}

/** Only remove known building/floor prefixes; a substring is insufficient evidence of a room. */
private fun matchesReservationRoom(location: String, building: String, floor: String, room: String): Boolean {
    val buildingName = normalizedMapText(building)
    val floorName = normalizedMapText(floor).let { if (buildingName.isNotEmpty()) it.removePrefix(buildingName) else it }.let { value ->
        if (value.matches(Regex("[零〇一二三四五六七八九十百0-9]+"))) "${floorNumber(value)}层" else value
    }
    fun withoutFloor(value: String): String {
        if (floorName.isEmpty()) return value
        var remainder = value
        while (remainder.startsWith(floorName)) remainder = remainder.removePrefix(floorName)
        return remainder
    }
    val locationRoom = if (buildingName.isNotEmpty()) location.removePrefix(buildingName) else location
    val roomName = normalizedMapText(room).let { if (buildingName.isNotEmpty()) it.removePrefix(buildingName) else it }
    return roomName.isNotEmpty() && withoutFloor(locationRoom) == withoutFloor(roomName)
}

private fun normalizedMapText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .uppercase(Locale.ROOT).replace(Regex("[\\s\\u00a0，,、·:：]"), "")
    .replace(Regex("第?([零〇一二三四五六七八九十百0-9]+)(?:层|楼|F)")) { "${floorNumber(it.groupValues[1])}层" }
    .replace(Regex("^F([0-9]+)$")) { "${it.groupValues[1].toIntOrNull() ?: it.groupValues[1]}层" }

private fun floorNumber(value: String): String {
    value.toIntOrNull()?.let { return it.toString() }
    val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    if (value.length == 1) digits[value.single()]?.let { return it.toString() }
    if (value.count { it == '十' } == 1 && value.length <= 3) {
        val parts = value.split('十')
        val tens = if (parts[0].isEmpty()) 1 else parts[0].singleOrNull()?.let(digits::get)
        val ones = if (parts[1].isEmpty()) 0 else parts[1].singleOrNull()?.let(digits::get)
        if (tens != null && ones != null) return (tens * 10 + ones).toString()
    }
    return value
}
