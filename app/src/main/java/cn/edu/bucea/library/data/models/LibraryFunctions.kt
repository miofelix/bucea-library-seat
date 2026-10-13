package cn.edu.bucea.library.data.models

data class LibraryOption(val value: String, val label: String)

data class SeatSearchOptions(
    val dates: List<LibraryOption> = emptyList(),
    val buildings: List<LibraryOption> = emptyList(),
    val rooms: List<LibraryOption> = emptyList(),
    val durations: List<LibraryOption> = emptyList(),
    val startTimes: List<LibraryOption> = emptyList(),
    val endTimes: List<LibraryOption> = emptyList(),
    val powers: List<LibraryOption> = emptyList(),
    val windows: List<LibraryOption> = emptyList(),
)

data class SeatQuery(
    val onDate: String,
    val building: String = "null",
    val room: String = "null",
    val hour: String = "null",
    val power: String = "null",
    val window: String = "null",
    val startMin: String = "null",
    val endMin: String = "null",
    val name: String = "",
)

data class LibrarySeat(
    val id: String,
    val label: String,
    val room: String,
    val status: String,
    val available: Boolean,
)

data class SeatPage(
    val seats: List<LibrarySeat>,
    val seatNum: Int,
    val nextOffset: Int?,
    val verificationRequired: Boolean = false,
    val message: String = "",
    val verificationUserId: String = "",
)

data class SeatTimes(
    val options: List<LibraryOption>,
    val building: String = "",
    val room: String = "",
    val message: String = "",
    val verificationRequired: Boolean = false,
    val verificationUserId: String = "",
)

data class ReservationSelection(
    val onDate: String,
    val seat: LibrarySeat,
    val start: LibraryOption,
    val end: LibraryOption,
)

data class PreparedReservation(
    val selection: ReservationSelection,
    val fields: Map<String, String>,
    val verificationRequired: Boolean,
    val accountId: String = "",
    val verificationAuthId: String? = null,
)

data class ReservationReceipt(
    val number: String,
    val onDate: String,
    val begin: String,
    val end: String,
    val location: String,
    val status: String,
)

data class ReservationResult(val success: Boolean, val message: String, val receipt: ReservationReceipt? = null)

data class LibraryHistoryItem(
    val id: String,
    val title: String,
    val details: List<String>,
    val status: String,
    val cancelPath: String? = null,
    val cancelMethod: String? = null,
    val reservationId: String? = null,
    val detailPath: String? = null,
)

data class ReservationHistory(
    val items: List<LibraryHistoryItem>,
    val nextOffset: Int?,
    val userStatus: String,
    val message: String,
)

data class LibraryUserState(
    val status: String,
    val softMode: Boolean,
    val details: List<String>,
    val actions: List<String>,
    val actionMessage: String = "",
    /** A school requirement, not a measurement of the phone's current Wi-Fi connection. */
    val requiresLibraryWifi: Boolean = false,
)
