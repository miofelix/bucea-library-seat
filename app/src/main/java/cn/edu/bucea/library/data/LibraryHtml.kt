package cn.edu.bucea.library.data

import cn.edu.bucea.library.LibraryOverview
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object LibraryHtml {
    fun parse(html: String): Document = Jsoup.parse(html, LibraryRepository.BASE_URL)

    fun authenticated(document: Document, username: String): Boolean =
        document.selectFirst("#sysUsername")?.attr("value") == username &&
            document.selectFirst("a[href='/logout']") != null &&
            document.selectFirst("form[action='/auth/signIn']") == null

    fun overview(document: Document): LibraryOverview {
        fun count(label: String): Int? = document.select(".imgdiv").firstOrNull { it.text().contains(label) }
            ?.selectFirst("p")?.text()?.toIntOrNull()
        return LibraryOverview(
            available = count("当前可用"), inUse = count("已使用"), awaitingSignIn = count("未签到"),
            displayName = document.selectFirst(".userInfor a[href='/user/password']")?.text(),
            notices = document.select(".left.notice").select("p").map { it.text() }
                .filter { it.length > 20 },
        )
    }

    fun hiddenFields(document: Document): Map<String, String> {
        val form = document.selectFirst("form[action='/auth/signIn']")
            ?: throw LoginFailure(FailureKind.PROTOCOL, "登录接口已变更，请稍后更新应用后重试")
        return form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
            .also { require(it["SYNCHRONIZER_TOKEN"].orEmpty().isNotBlank()) { "登录页面缺少校验令牌" } }
    }

    fun error(document: Document): String {
        document.selectFirst(".errors, .error, .alert-danger, #msgBoxDIV .msg")?.text()
            ?.takeIf { it.isNotBlank() }?.let { return it.take(200) }
        val scripts = document.select("script").joinToString("\n") { it.data() }
        val match = Regex("flashMsg\\s*=\\s*['\"]([^'\"]+)['\"]").find(scripts)
        return match?.groupValues?.get(1)?.take(200) ?: "登录未成功，请检查账号密码或验证码"
    }
}
