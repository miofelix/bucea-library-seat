package cn.edu.bucea.library.data.models

data class NativeMapOptions(
    val dates: List<LibraryOption> = emptyList(),
    val buildings: List<LibraryOption> = emptyList(),
    val floors: List<LibraryOption> = emptyList(),
)

data class NativeMapRoom(val id: String, val name: String)
data class NativeMapRooms(val rooms: List<NativeMapRoom>, val verificationRequired: Boolean = false,
    val message: String = "", val verificationUserId: String = "", val incomplete: Boolean = false)

/** Reservation identity stays separate from the server's seat and room IDs. */
data class ReservationMapTarget(val date: String, val seatLabel: String, val location: String)

enum class NativeMapCellKind { EMPTY, SEAT, TABLE, WALL, DOOR, WINDOW, LABEL }

/** Coordinates refer to the server's actual row/column grid, including its empty aisles. */
data class NativeMapCell(
    val row: Int,
    val column: Int,
    val rowSpan: Int = 1,
    val columnSpan: Int = 1,
    val seat: LibrarySeat? = null,
    val localOnly: Boolean = false,
    val kind: NativeMapCellKind = NativeMapCellKind.EMPTY,
    val label: String = "",
    val power: Boolean = false,
    val window: Boolean = false,
)

data class NativeRoomLayout(
    val roomId: String,
    val roomName: String,
    val rows: Int,
    val columns: Int,
    val cells: List<NativeMapCell>,
    val verificationRequired: Boolean = false,
    val message: String = "",
    val verificationUserId: String = "",
)

data class NativeMapState(
    val loading: Boolean = false,
    val error: String? = null,
    val options: NativeMapOptions = NativeMapOptions(),
    val filters: Map<String, String> = emptyMap(),
    val rooms: List<NativeMapRoom> = emptyList(),
    val layout: NativeRoomLayout? = null,
    val listMode: Boolean = false,
    val notice: String? = null,
    val verificationRequired: Boolean = false,
    val verificationUserId: String = "",
    val directRegionView: Boolean = false,
    val focusSeatId: String? = null,
    val focusSeatLabel: String? = null,
    /** Original booking date; filters.onDate always identifies the map actually displayed. */
    val reservationDate: String? = null,
)
