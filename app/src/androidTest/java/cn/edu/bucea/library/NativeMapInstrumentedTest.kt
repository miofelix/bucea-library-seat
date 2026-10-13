package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.bucea.library.data.NativeMapRepository
import cn.edu.bucea.library.data.SavedAccount
import cn.edu.bucea.library.data.models.NativeMapCellKind
import cn.edu.bucea.library.data.models.NativeMapRoom
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native geometry and request-contract checks using synthetic data, without live operations. */
@RunWith(AndroidJUnit4::class)
class NativeMapInstrumentedTest {
    @Test
    fun rowsPreserveEmptyAislesAndSeatStatusInsteadOfCollapsingSeatCoordinates() {
        val layout = NativeMapRepository.parseLayout("""
            <ul><li>&nbsp;</li><li id='seat_41'><a class='idle_both' local='false' title='座位空闲'>001A</a><div class='seatLayoutTip'>tip</div></li><li class='door'>入口</li></ul>
            <ul><li id='seat_42'><a class='idle' local='true'>002B</a></li><li>&nbsp;</li><li id='seat_43'><a class='agreement' title='已被预约'>003C</a></li></ul>
            <ul><li class='table'>桌</li><li class='window'>窗</li><li class='wall'></li></ul>
        """.trimIndent(), "room", "测试区域")
        assertEquals(3, layout.rows)
        assertEquals(3, layout.columns)
        assertEquals(9, layout.cells.size)
        assertEquals(NativeMapCellKind.EMPTY, layout.cells.first().kind)
        val free = layout.cells.first { it.seat?.id == "41" }
        assertEquals(0, free.row)
        assertEquals(1, free.column)
        assertEquals("001A", free.seat!!.label)
        assertEquals("测试区域", free.seat.room)
        assertTrue(free.seat.available)
        assertTrue(free.power)
        assertTrue(free.window)
        assertEquals("001A", free.label)
        val local = layout.cells.first { it.seat?.id == "42" }
        assertTrue(local.localOnly)
        assertFalse(local.seat!!.available)
        assertFalse(layout.cells.first { it.seat?.id == "43" }.seat!!.available)
        assertEquals(NativeMapCellKind.TABLE, layout.cells[6].kind)
        assertEquals(NativeMapCellKind.WINDOW, layout.cells[7].kind)
        assertEquals(NativeMapCellKind.WALL, layout.cells[8].kind)
    }

    @Test
    fun tableSpansOccupyTheirRealCellsAndShiftFollowingSeats() {
        val layout = NativeMapRepository.parseLayout("""
            <table><tr><td rowspan='2' colspan='2' class='table'>桌</td><td id='seat_7'><a class='idle'>007D</a></td></tr>
            <tr><td id='seat_8'><a class='noUsre'>008A</a></td></tr></table>
        """.trimIndent(), "room", "测试区域")
        assertEquals(2, layout.rows)
        assertEquals(3, layout.columns)
        assertEquals(2, layout.cells.first().rowSpan)
        assertEquals(2, layout.cells.first().columnSpan)
        assertEquals(2, layout.cells.first { it.seat?.id == "8" }.column)
        assertEquals(1, layout.cells.first { it.seat?.id == "8" }.row)
        assertFalse(layout.cells.first { it.seat?.id == "8" }.seat!!.available)
    }

    @Test
    fun schoolsVerificationResponseNeverBecomesAnEmptyAvailableMap() {
        for (response in listOf("""{"limit":true}""", "<script>clickVerifyCode('')</script>")) {
            val layout = NativeMapRepository.parseLayout(response, "room", "测试区域")
            assertTrue(layout.verificationRequired)
            assertTrue(layout.cells.isEmpty())
            assertTrue(layout.message.isNotBlank())
        }
        val rooms = NativeMapRepository.parseRooms("""{"limit":true,"userId":"synthetic-user","rooms":[{"id":1,"name":"区域"}]}""")
        assertTrue(rooms.verificationRequired)
        assertTrue(rooms.rooms.isEmpty())
        assertEquals("synthetic-user", rooms.verificationUserId)
        assertEquals("synthetic-user", NativeMapRepository.parseLayout("""{"limit":true,"userId":"synthetic-user"}""", "room", "测试区域").verificationUserId)
    }

    @Test
    fun floorRateLimitPreservesChallengeIdentityAndStopsTheReadSequence() = runBlocking {
        var calls = 0
        val repository = NativeMapRepository { _, _ -> calls++; """{"limit":true,"userId":"synthetic-user"}""" }
        try {
            repository.floors(SavedAccount("synthetic", "209999990001", "synthetic", "测试"), "1")
            fail("Rate-limited floor options must request verification")
        } catch (limited: NativeMapRepository.VerificationRequired) {
            assertEquals("synthetic-user", limited.userId)
        }
        assertEquals(1, calls)
    }

    @Test
    fun nativeRoomNavigationUsesSchoolGetContractsAndRealDate() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = NativeMapRepository { _, endpoint ->
            calls += endpoint
            when {
                endpoint == "/map" -> "<p id='options_onDate'><a value='2030-01-02'>2030-01-02</a></p><p id='options_building'><a value='1'>图书馆</a></p>"
                endpoint.startsWith("/mapBook/ajaxGetFloor") -> "<a value='2'>二层</a>"
                endpoint.startsWith("/mapBook/ajaxGetRooms") -> """{"rooms":[{"id":4,"name":"区域甲"}]}"""
                else -> "<ul><li></li><li id='seat_4'><a class='idle'>004D</a></li></ul>"
            }
        }
        val account = SavedAccount("synthetic", "209999990001", "synthetic", "测试")
        assertEquals("2030-01-02", repository.options(account).dates.single().value)
        assertEquals("2", repository.floors(account, "1").single().value)
        val rooms = repository.rooms(account, "2030-01-02", "1", "2")
        assertEquals(NativeMapRoom("4", "区域甲"), rooms.rooms.single())
        val roomRequest = ("http://10.1.20.7" + calls.last()).toHttpUrl()
        assertEquals("2030-01-02", roomRequest.queryParameter("onDate"))
        assertEquals("1", roomRequest.queryParameter("building"))
        assertEquals("2", roomRequest.queryParameter("floor"))
        val layout = repository.layout(account, rooms.rooms.single(), "2030-01-02")
        val layoutRequest = ("http://10.1.20.7" + calls.last()).toHttpUrl()
        assertEquals("/mapBook/getSeatsByRoom", layoutRequest.encodedPath)
        assertEquals("4", layoutRequest.queryParameter("room"))
        assertEquals("2030-01-02", layoutRequest.queryParameter("date"))
        assertNull(layoutRequest.queryParameter("onDate"))
        assertEquals(1, layout.cells.single { it.seat != null }.column)
    }
}
