package cn.edu.bucea.library.data

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReservationStatusesTest {
    @Test fun historyPreservesEachSchoolStatusAndOnlyActiveRecordsExposeCancellation() {
        val active = listOf("已预约", "待签到", "已签到", "使用中", "暂离")
        val inactive = listOf("已履约", "失约", "已失约", "已结束", "结束使用", "已取消", "已完成", "已违约")
        val labels = active + inactive
        val page = "<div class='myReserveList'>" + labels.mapIndexed { index, label ->
            historyRecord(index + 1, label)
        }.joinToString("") + "</div><script>var userInfo='{\"currentReservationStatus\":\"NO_RESERVATION\"}';</script>"

        val history = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(page))

        assertEquals(labels, history.items.map { it.status })
        assertEquals("NO_RESERVATION", history.userStatus)
        history.items.forEachIndexed { index, record ->
            assertEquals("reservation_${index + 1}", record.id)
            assertEquals((index + 1).toString(), record.reservationId)
            if (record.status in active) {
                assertEquals("/reservation/cancel", record.cancelPath)
                assertEquals("POST", record.cancelMethod)
            } else {
                assertNull(record.status, record.cancelPath)
                assertNull(record.status, record.cancelMethod)
            }
        }
    }

    @Test fun staticSchoolStatusDoesNotDependOnAnExhaustiveLabelList() {
        val unknown = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(historyRecord(1, "学校新增状态"))).items.single()
        assertEquals("学校新增状态", unknown.status)

        for (label in listOf("已履约", "失约", "结束使用")) {
            val unstyled = historyRecord(2, label).replace("<a class='normal'>$label</a>", "<a>$label</a>")
            assertEquals(label, LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(unstyled)).items.single().status)
        }

        val labeled = "<dl><dt>预约</dt><dd>状态：失约</dd><dd><a href='/user/cancel?id=3'>取消预约</a></dd></dl>"
        val record = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(labeled)).items.single()
        assertEquals("状态：失约", record.status)
        assertNull(record.cancelPath)

        val actionOnly = historyRecord(4, "已预约").replace("<a class='normal'>已预约</a>", "")
        assertEquals("", LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(actionOnly)).items.single().status)
    }

    @Test fun linkedOrNonAnchorTerminalStatusRemainsVisibleOnTheHistoryRecord() {
        val linked = historyRecord(5, "结束使用")
            .replace("<a class='normal'>结束使用</a>", "<a class='normal' href='/history/status?id=5'>结束使用</a>")
        val nonAnchor = historyRecord(6, "结束使用")
            .replace("<a class='normal'>结束使用</a>", "<span class='normal'>结束使用</span>")

        val history = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(linked + nonAnchor))

        assertEquals(listOf("结束使用", "结束使用"), history.items.map { it.status })
        assertTrue(history.items.all { it.cancelPath == null })

        for (label in listOf("STOPPED", "FINISHED")) {
            val machineLabel = historyRecord(7, label)
                .replace("<a class='normal'>$label</a>", "<a class='normal' href='/history/status?id=7'>$label</a>")
            assertEquals(label, LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(machineLabel)).items.single().status)
        }
    }

    @Test fun paginatedHistoryKeepsFulfilledAndMissedLabelsFromServerFragments() = runTest {
        val labels = listOf("已履约", "失约", "已结束", "结束使用", "已取消", "已预约")
        val repository = LibraryFunctionsRepository(
            request = { _, path ->
                assertTrue(path.startsWith("/user/viewMoreHistory?"))
                JSONObject().put("resStr", labels.mapIndexed { index, label -> historyRecord(index + 1, label) }.joinToString(""))
                    .put("resNum", labels.size).put("offset", 2).toString()
            },
            post = { _, _, _ -> error("Reading history must not submit a mutation") },
        )

        val history = repository.history(SavedAccount("fake", "209999990001", "fake", "测试"), offset = 1)

        assertEquals(labels, history.items.map { it.status })
        assertEquals(2, history.nextOffset)
    }

    @Test fun fulfilledOrMissedReceiptCannotConfirmANewReservation() {
        for (label in listOf("已履约", "失约", "已结束", "结束使用", "已取消")) {
            val result = LibraryFunctionsRepository.parseReservationResult(LibraryHtml.parse("""
                <p>预约成功</p><div class='receipt'><dl>
                <dd>凭证号：synthetic-receipt</dd><dd>日期：2030-01-15</dd><dd>时间：08:00 - 10:00</dd>
                <dd>状态：$label</dd></dl></div>
            """))
            assertEquals(label, result.receipt?.status)
            assertFalse(label, result.success)
        }
    }

    private fun historyRecord(id: Int, label: String) = """
        <dl><dt>2030-01-16 08:00 -- 10:00</dt><dd>座位：001D</dd>
        <form action='/reservation/cancel' method='post'><input type='hidden' name='id' value='$id'>
        <a class='normal' onclick="MyDoSubmit('$id')">取消预约</a></form><a class='normal'>$label</a></dl>
    """.trimIndent()
}
