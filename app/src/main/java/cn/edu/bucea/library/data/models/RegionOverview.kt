package cn.edu.bucea.library.data.models

/** Mutually exclusive current-status counts, independent of whether online booking is allowed. */
data class RegionSeatCounts(
    val total: Int,
    val free: Int = 0,
    val inUse: Int = 0,
    val reserved: Int = 0,
    val away: Int = 0,
    val unavailable: Int = 0,
    val unknown: Int = 0,
    val localOnly: Int = 0,
) {
    /** Known occupied seats include active use, reservations and temporary absence. */
    val occupied: Int get() = inUse + reserved + away
}

data class RegionSeatOverview(
    val id: String,
    val name: String,
    val buildingId: String,
    val buildingName: String,
    val floorId: String,
    val floorName: String,
    val date: String,
    val counts: RegionSeatCounts? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val updatedAt: Long? = null,
    val stale: Boolean = false,
)

data class RegionOverviewState(
    val date: String = "",
    val regions: List<RegionSeatOverview> = emptyList(),
    val loading: Boolean = false,
    val completedRegions: Int = 0,
    val totalRegions: Int = 0,
    val discoveryComplete: Boolean = false,
    val discoveryErrors: List<String> = emptyList(),
    val error: String? = null,
    val verificationRequired: Boolean = false,
    val verificationUserId: String = "",
    val updatedAt: Long? = null,
    val stale: Boolean = false,
)
