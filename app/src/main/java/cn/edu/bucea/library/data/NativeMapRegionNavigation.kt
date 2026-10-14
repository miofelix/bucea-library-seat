package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.NativeMapOptions
import cn.edu.bucea.library.data.models.NativeMapState
import cn.edu.bucea.library.data.models.RegionSeatOverview

/** A homepage region is only a target; fresh server options and geometry determine the map. */
internal suspend fun NativeMapRepository.loadRegion(account: SavedAccount, region: RegionSeatOverview): NativeMapState {
    val options = options(account)
    val dates = options.dates.filterNot { it.value.isBlank() || it.value == "null" }
    val buildings = options.buildings.filterNot { it.value.isBlank() || it.value == "null" }
    check(dates.any { it.value == region.date }) { "学校当前未提供 ${region.date} 的区域布局，请刷新首页后重试" }
    check(buildings.any { it.value == region.buildingId }) { "学校当前未提供该场馆，请刷新首页后重试" }
    val floors = floors(account, region.buildingId).filterNot { it.value.isBlank() || it.value == "null" }
    check(floors.any { it.value == region.floorId }) { "学校当前未提供该楼层，请刷新首页后重试" }
    val state = NativeMapState(
        directRegionView = true,
        options = NativeMapOptions(dates, buildings, floors),
        filters = mapOf("onDate" to region.date, "building" to region.buildingId, "floor" to region.floorId),
    )
    val rooms = rooms(account, region.date, region.buildingId, region.floorId)
    if (rooms.verificationRequired) return state.copy(
        rooms = rooms.rooms, notice = rooms.message.takeIf(String::isNotBlank),
        verificationRequired = true, verificationUserId = rooms.verificationUserId,
    )
    val room = rooms.rooms.find { it.id == region.id }
    check(room != null) { "学校当前未提供该阅览区域，请刷新首页后重试" }
    val layout = layout(account, room, region.date)
    return state.copy(
        rooms = rooms.rooms, layout = layout, notice = layout.message.takeIf(String::isNotBlank),
        verificationRequired = layout.verificationRequired, verificationUserId = layout.verificationUserId,
    )
}
