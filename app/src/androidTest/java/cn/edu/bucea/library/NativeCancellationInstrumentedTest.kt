package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.bucea.library.data.*
import kotlinx.coroutines.runBlocking
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
}
