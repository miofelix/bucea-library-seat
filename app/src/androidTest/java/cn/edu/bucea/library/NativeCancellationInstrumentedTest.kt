package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.bucea.library.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCancellationInstrumentedTest {
    private val account = SavedAccount("fake-local-account", "209999990001", "synthetic-password", "测试")
    private val history = """<input id="sysUsername" value="209999990001"><div class="myReserveList"><dl id="res_1"><dt>测试座位预约</dt><dd>日期：2026-10-14</dd><dd>状态：已预约</dd><dd><a href="/user/cancel?id=1">取消预约</a></dd></dl><dl id="moreBlock"><dd class="more" style="display: none"></dd></dl></div>"""

    @Test fun cancellationRequiresFreshServerActionAndSendsOnce() = runBlocking {
        val requests = mutableListOf<String>()
        val repository = LibraryFunctionsRepository(request = { _, path -> requests += path
            if (path.startsWith("/history")) history else """{"status":"success","message":"预约已取消"}"""
        }, post = { _, _, _ -> error("Cancellation must not invent a POST contract") })
        val item = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(history)).items.single()
        val result = repository.cancelHistory(account, item)
        assertTrue(result.success)
        assertEquals(listOf("/history?type=SEAT", "/user/cancel?id=1"), requests)
    }

    @Test fun missingFreshActionStopsBeforeMutation() = runBlocking {
        val paths = mutableListOf<String>()
        val item = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(history)).items.single()
        val repository = LibraryFunctionsRepository(request = { _, path -> paths += path; history.replace("/user/cancel?id=1", "/user/alreadyCancelled?id=1") },
            post = { _, _, _ -> error("No mutation is permitted") })
        try { repository.cancelHistory(account, item); fail("Stale cancellation must be blocked") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf("/history?type=SEAT"), paths)
    }

    @Test fun actualSchoolCancellationFormUsesFreshPostTokenAndStableReservationIdentity() = runBlocking {
        fun page(token: String, status: String = "已预约") = """<div class="myReserveList"><dl>
            <dt>明天 07:30 -- 08:00</dt><dd><a href="/view?id=123456&amp;type=SEAT" title="图书馆二层001D号">图书馆二层001D号</a></dd>
            <form action="/reservation/cancel" method="post" id="form_123456">
            <input type="hidden" name="SYNCHRONIZER_TOKEN" value="$token">
            <input type="hidden" name="SYNCHRONIZER_URI" value="/history"><input type="hidden" name="id" value="123456">
            <a onclick="MyDoSubmit('123456')" class="normal" title="点击取消预约">取消预约</a></form><a class="normal">$status</a></dl></div>"""
        val original = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(page("stale-token"))).items.single()
        val changed = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(page("changed-token", "已取消"))).items.single()
        assertEquals("reservation_123456", original.id)
        assertEquals(original.id, changed.id)
        assertEquals("已预约", original.status)
        assertEquals("POST", original.cancelMethod)
        assertEquals("/view?id=123456&type=SEAT", original.detailPath)
        val gets = mutableListOf<String>()
        var submitted: Map<String, String>? = null
        val repository = LibraryFunctionsRepository(request = { _, path -> gets += path; page("fresh-token") },
            post = { _, path, fields ->
                assertEquals("/reservation/cancel", path)
                submitted = fields
                """{"status":"success","message":"预约已取消"}"""
            })
        assertTrue(repository.cancelHistory(account, original).success)
        assertEquals(listOf("/history?type=SEAT"), gets)
        assertEquals("fresh-token", submitted?.get("SYNCHRONIZER_TOKEN"))
        assertEquals("/history", submitted?.get("SYNCHRONIZER_URI"))
        assertEquals("123456", submitted?.get("id"))
    }

    @Test fun actualCanceledHistoryResponseConfirmsPostAndKeepsRecordStatus() = runBlocking {
        val pending = """<div class="myReserveList"><dl><dt>明天 07:30 -- 08:00</dt><dd><a href="/view?id=123456&amp;type=SEAT">001D</a></dd>
            <form action="/reservation/cancel" method="post"><input type="hidden" name="SYNCHRONIZER_TOKEN" value="fresh">
            <input type="hidden" name="SYNCHRONIZER_URI" value="/history"><input type="hidden" name="id" value="123456">
            <a onclick="MyDoSubmit('123456')">取消预约</a></form><a class="normal">已预约</a></dl></div>"""
        val canceled = """<div class="myReserveList"><dl><dt>明天 07:30 -- 08:00</dt><dd><a href="/view?id=123456&amp;type=SEAT">001D</a></dd>
            <a class="Missed">已取消</a></dl></div>"""
        val parsed = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(canceled)).items.single()
        assertEquals("已取消", parsed.status)
        assertNull(parsed.cancelPath)
        val repository = LibraryFunctionsRepository(request = { _, _ -> pending }, post = { _, _, _ -> canceled })
        val result = repository.cancelHistory(account, LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(pending)).items.single())
        assertTrue(result.success)
        assertEquals("取消预约成功", result.message)
    }

    @Test fun genericPostResponseNeedsFreshCanceledHistoryReadback() = runBlocking {
        val pending = """<div class="myReserveList"><dl><dt>明天 07:30 -- 08:00</dt><dd><a href="/view?id=123456&amp;type=SEAT">001D</a></dd>
            <form action="/reservation/cancel" method="post"><input type="hidden" name="SYNCHRONIZER_TOKEN" value="fresh">
            <input type="hidden" name="SYNCHRONIZER_URI" value="/history"><input type="hidden" name="id" value="123456"></form><a class="normal">已预约</a></dl></div>"""
        val canceled = """<div class="myReserveList"><dl><dt>明天 07:30 -- 08:00</dt><dd><a href="/view?id=123456&amp;type=SEAT">001D</a></dd>
            <a class="Missed">已取消</a></dl></div>"""
        var gets = 0
        var posts = 0
        val repository = LibraryFunctionsRepository(request = { _, path ->
            assertEquals("/history?type=SEAT", path)
            gets++; if (gets == 1) pending else canceled
        }, post = { _, _, _ -> posts++; "<p>请求已处理</p>" })
        val result = repository.cancelHistory(account, LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(pending)).items.single())
        assertTrue(result.success)
        assertEquals("取消预约成功", result.message)
        assertEquals(2, gets)
        assertEquals(1, posts)
    }

    @Test fun concurrentAndAmbiguousCancellationCannotResubmitSameFreshToken() = runBlocking {
        val page = """<div class="myReserveList"><dl><dt>预约</dt><dd><a href="/view?id=123456&amp;type=SEAT">001D</a></dd>
            <form action="/reservation/cancel" method="post"><input type="hidden" name="SYNCHRONIZER_TOKEN" value="same-token">
            <input type="hidden" name="SYNCHRONIZER_URI" value="/history"><input type="hidden" name="id" value="123456"></form><a>已预约</a></dl></div>"""
        val item = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(page)).items.single()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var posts = 0
        val repository = LibraryFunctionsRepository(request = { _, _ -> page }, post = { _, _, _ ->
            posts++; entered.complete(Unit); release.await(); throw IOException("synthetic ambiguous result")
        })
        val first = async { try { repository.cancelHistory(account, item); fail("Timeout must remain unknown") } catch (_: IOException) { } }
        entered.await()
        try { repository.cancelHistory(account, item); fail("Concurrent duplicate must be blocked") }
        catch (_: IllegalArgumentException) { }
        release.complete(Unit)
        first.await()
        try { repository.cancelHistory(account, item); fail("Used token must not be submitted after timeout") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, posts)
    }

    @Test fun cancellationFormWithoutDetailLinkUsesServerIdAndNormalizesSchoolAction() = runBlocking {
        val page = """<div class="myReserveList"><dl><dt>明天 07:30 -- 08:00</dt><dd>001D</dd>
            <form action="http://10.1.20.7/reservation/cancel" method="POST">
            <input type="hidden" name="SYNCHRONIZER_TOKEN" value="fresh-form-token">
            <input type="hidden" name="SYNCHRONIZER_URI" value="/history"><input type="hidden" name="id" value="123456">
            <a onclick="MyDoSubmit('123456')">取消预约</a></form><a>已预约</a></dl></div>"""
        val item = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(page)).items.single()
        assertEquals("reservation_123456", item.id)
        assertEquals("123456", item.reservationId)
        assertNull(item.detailPath)
        assertEquals("/reservation/cancel", item.cancelPath)
        assertEquals("POST", item.cancelMethod)
        var posts = 0
        val repository = LibraryFunctionsRepository(request = { _, _ -> page }, post = { _, endpoint, fields ->
            posts++
            assertEquals("/reservation/cancel", endpoint)
            assertEquals("123456", fields["id"])
            """{"status":"success","message":"预约已取消"}"""
        })
        assertTrue(repository.cancelHistory(account, item).success)
        assertEquals(1, posts)
    }

    @Test fun inactiveMismatchedAndExternalFormsNeverExposeCancellation() {
        fun page(detail: String, action: String, id: String, status: String) = """<div class="myReserveList"><dl>
            <dt>预约</dt><dd><a href="$detail">001D</a></dd><form action="$action" method="POST">
            <input type="hidden" name="id" value="$id"></form><a>$status</a></dl></div>"""
        for (html in listOf(
            page("/view?type=SEAT&amp;id=123456", "/reservation/cancel", "999999", "已预约"),
            page("/view?type=SEAT&amp;id=123456", "http://example.com/reservation/cancel", "123456", "已预约"),
            page("/view?type=SEAT&amp;id=123456", "/reservation/cancel", "123456", "已取消"),
        )) {
            val item = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(html)).items.single()
            assertEquals("123456", item.reservationId)
            assertNull(item.cancelPath)
            assertNull(item.cancelMethod)
        }
        val reordered = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(
            page("http://10.1.20.7/view?type=SEAT&amp;id=123456", "reservation/cancel", "123456", "已预约"))).items.single()
        assertEquals("reservation_123456", reordered.id)
        assertEquals("/view?type=SEAT&id=123456", reordered.detailPath)
        assertEquals("/reservation/cancel", reordered.cancelPath)
    }

    @Test fun genericCancellationResponseReadsBackRecordOnOlderHistoryPage() = runBlocking {
        val firstPage = """<div class="myReserveList"><dl id="moreBlock"><dd class="more"><input id="offset" value="1"></dd></dl></div>"""
        fun record(canceled: Boolean) = """<div class="myReserveList"><dl><dt>预约</dt><dd><a href="/view?id=123456&amp;type=SEAT">001D</a></dd>
            ${if (canceled) "" else """<form action="/reservation/cancel" method="post">
            <input type="hidden" name="SYNCHRONIZER_TOKEN" value="fresh"><input type="hidden" name="SYNCHRONIZER_URI" value="/history">
            <input type="hidden" name="id" value="123456"></form>"""}
            <a>${if (canceled) "已取消" else "已预约"}</a></dl></div>"""
        val item = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(record(false))).items.single()
        var posts = 0
        val paths = mutableListOf<String>()
        val repository = LibraryFunctionsRepository(request = { _, endpoint ->
            paths += endpoint
            if (endpoint.startsWith("/history")) firstPage else org.json.JSONObject()
                .put("resStr", record(posts > 0)).put("resNum", 1).put("offset", -1).toString()
        }, post = { _, _, _ -> posts++; "<p>请求已处理</p>" })
        assertTrue(repository.cancelHistory(account, item).success)
        assertEquals(1, posts)
        assertEquals(2, paths.count { it == "/history?type=SEAT" })
        assertEquals(2, paths.count { it.startsWith("/user/viewMoreHistory?") })
    }
}
