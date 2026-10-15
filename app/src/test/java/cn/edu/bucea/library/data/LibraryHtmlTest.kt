package cn.edu.bucea.library.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryHtmlTest {
    @Test fun occupancySummaryInsideNoticeSectionDoesNotBecomeAnAnnouncement() {
        val overview = LibraryHtml.overview(LibraryHtml.parse("""
            <div class="left notice">
                <div class="imgdiv"><p>179</p><p>当前可用</p></div>
                <div class="imgdiv"><p>1471</p><p>已使用</p></div>
                <div class="imgdiv"><p>4</p><p>未签到</p></div>
                <p>欢迎使用图书馆选座系统。</p><p>请保持安静。</p>
            </div>
        """))

        assertEquals(179, overview.available)
        assertEquals(1471, overview.inUse)
        assertEquals(4, overview.awaitingSignIn)
        assertEquals(listOf("欢迎使用图书馆选座系统。", "请保持安静。"), overview.notices)
    }

    @Test fun noticeParagraphsAndShortItemsKeepTheirSourceOrder() {
        val notices = LibraryHtml.overview(LibraryHtml.parse("""
            <div class="left notice"><h2>公告</h2>
                <p>请保持安静。</p><p>请按时签到，暂离后及时返回。</p><p>&nbsp;</p>
            </div>
        """)).notices

        assertEquals(listOf("请保持安静。", "请按时签到，暂离后及时返回。"), notices)
    }

    @Test fun noticeHtmlLineBreaksAndListItemsBecomeSeparateEntries() {
        val notices = LibraryHtml.overview(LibraryHtml.parse("""
            <div class="left notice">
                <p>预约时段请以系统显示为准。<br>离馆时请结束使用。</p>
                <ul><li>请勿占座。</li><li><p>随身带走贵重物品。</p></li></ul>
            </div>
        """)).notices

        assertEquals(listOf("预约时段请以系统显示为准。", "离馆时请结束使用。", "请勿占座。", "随身带走贵重物品。"), notices)
    }

    @Test fun inlineMarkupPunctuationAndSourceWhitespaceDoNotSplitNoticeItems() {
        val notices = LibraryHtml.overview(LibraryHtml.parse("""
            <div class="left notice"><p>开放时间：08:00—22:00，
                请查看<strong>当日安排</strong>；具体要求以通知为准。<script>ignored()</script></p></div>
        """)).notices

        assertEquals(listOf("开放时间：08:00—22:00， 请查看当日安排；具体要求以通知为准。"), notices)
    }
}
