package cn.edu.bucea.library

import cn.edu.bucea.library.data.models.ReservationSchedule
import cn.edu.bucea.library.ui.currentReservationSummary
import cn.edu.bucea.library.ui.reservationLocationDetailText
import cn.edu.bucea.library.ui.reservationLocationText
import cn.edu.bucea.library.ui.reservationMapTarget
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReservationLocationPresentationTest {
    @Test fun realSchoolLocationVariantsUseOneFloorAndACompactSeatSuffix() {
        for (location in listOf(
            "位置：图书馆2层二层内环区，座位号009C",
            "图书馆2层二层内环区009C号",
            "位置：图书馆2层内环区，座位号009C",
            "地点：图书馆二层二层内环区，座位：009C",
            "位置：图书馆2层2层内环区009C号座位",
        )) {
            assertEquals(location, "图书馆2层内环区009C号", reservationLocationText(listOf(location)))
            assertEquals(location, "图书馆2层内环区", reservationLocationText(listOf(location), includeSeat = false))
        }
        assertEquals("图书馆2层内环区040B号", reservationLocationText(listOf("图书馆2层内环区040B号")))
    }

    @Test fun separateSeatFieldIsAddedWithoutLosingRoomNamesOrUnknownMeaningfulText() {
        val examples = listOf(
            "图书馆三层三层外环区" to "图书馆3层外环区",
            "图书馆4层四层期刊阅" to "图书馆4层期刊阅",
            "图书馆二层内环区东侧" to "图书馆2层内环区东侧",
            "新馆特色阅览室（临窗）" to "新馆特色阅览室（临窗）",
        )
        for ((original, formatted) in examples) {
            val details = listOf("座位：040B", "位置：$original")
            assertEquals(original, "${formatted}040B号", reservationLocationText(details))
            assertEquals(original, formatted, reservationLocationText(details, includeSeat = false))
        }
        assertEquals("图书馆2层内环区009C号（靠窗）",
            reservationLocationText(listOf("位置：图书馆2层二层内环区，座位号009C（靠窗）")))
        assertEquals("图书馆2层内环区（靠窗）",
            reservationLocationText(listOf("位置：图书馆2层二层内环区，座位号009C（靠窗）"), includeSeat = false))
        assertEquals("图书馆2层内环区005F号",
            reservationLocationText(listOf("位置：图书馆2层二层内环区，座位号005F")))
    }

    @Test fun differentFloorsAndUnknownNamesAreNeverCollapsedIntoAChosenRoom() {
        assertEquals("图书馆2层3层内环区009C号",
            reservationLocationText(listOf("位置：图书馆2层三层内环区，座位号009C")))
        assertEquals("图书馆12层期刊阅040B号",
            reservationLocationText(listOf("位置：图书馆十二层期刊阅", "座位：040B")))
        assertEquals("图书馆12层2层期刊阅040B号",
            reservationLocationText(listOf("位置：图书馆12层2层期刊阅", "座位：040B")))
        assertEquals("图书馆2层内环区，东侧", reservationLocationText(listOf("区域：图书馆二层内环区，东侧")))
    }

    @Test fun presentationDoesNotRewriteOtherDetailRowsOrInventMissingLocation() {
        val details = listOf("座位：009C", "位置：图书馆2层二层内环区，座位号009C",
            "日期：2026-10-20", "时间：17:00 — 18:00", "状态：使用中", "凭证号：fixture-2层-009C", "请连接图书馆 Wi-Fi")
        val original = details.toList()
        for (detail in details.filterNot { it.startsWith("位置：") }) {
            assertNull(detail, reservationLocationDetailText(detail, details))
        }
        assertEquals("图书馆2层内环区009C号", reservationLocationText(details))
        assertEquals(original, details)
        assertNull(reservationLocationText(listOf("座位：009C", "时间：17:00 — 18:00")))
        assertNull(reservationLocationText(emptyList()))
        assertNull(reservationLocationText(listOf("位置：")))
    }

    @Test fun currentSummarySeparatesProminentSeatAndLocationWhileMapTargetKeepsRawLocation() {
        val rawLocation = "图书馆2层二层内环区，座位号009C"
        val details = listOf("座位：009C", "位置：$rawLocation", "日期：2026-10-20", "时间：17:00 — 18:00")
        val summary = currentReservationSummary(details, LocalDate.of(2026, 10, 20))
        assertEquals("009C", summary.seat)
        assertEquals("图书馆2层内环区", summary.location)
        val target = requireNotNull(reservationMapTarget(details, ReservationSchedule("2026-10-20")))
        assertEquals(rawLocation, target.location)
        assertEquals("009C", target.seatLabel)
    }
}
