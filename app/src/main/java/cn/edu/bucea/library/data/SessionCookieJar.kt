package cn.edu.bucea.library.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

class SessionCookieJar(private val store: AccountStore, private val accountId: String) : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    private var active = true

    init {
        val array = JSONArray(store.cookies(accountId))
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val builder = Cookie.Builder().name(item.getString("name")).value(item.getString("value"))
                .path(item.getString("path")).expiresAt(item.getLong("expires"))
            if (item.getBoolean("hostOnly")) builder.hostOnlyDomain(item.getString("domain"))
            else builder.domain(item.getString("domain"))
            if (item.getBoolean("secure")) builder.secure()
            if (item.getBoolean("httpOnly")) builder.httpOnly()
            cookies += builder.build()
        }
    }

    @Synchronized override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) {
        if (!active) return
        incoming.forEach { next ->
            cookies.removeAll { it.name == next.name && it.domain == next.domain && it.path == next.path }
            if (next.expiresAt > System.currentTimeMillis()) cookies += next
        }
        persist()
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!active) return emptyList()
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized fun all(): List<Cookie> = cookies.filter { it.expiresAt > System.currentTimeMillis() }.toList()
    /** Replace the WebView's campus-root snapshot, including cookies deleted by the server. */
    @Synchronized fun replaceBrowserCookies(header: String?) {
        if (!active) return
        if (header.isNullOrBlank()) { clear(); return }
        val origin = LibraryRepository.BASE_URL.toHttpUrl()
        val incoming = header.split(';').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else part.substring(0, separator).trim() to part.substring(separator + 1).trim()
        }.toMutableList()
        val snapshot = mutableListOf<Cookie>()
        val now = System.currentTimeMillis()
        for (cookie in cookies.filter { it.expiresAt > now }) {
            // A root Cookie header cannot describe path-specific cookies; retain their scope.
            if (!cookie.matches(origin)) { snapshot += cookie; continue }
            val index = incoming.indexOfFirst { it.first == cookie.name }
            if (index < 0) continue
            val (_, value) = incoming.removeAt(index)
            cookieWithValue(cookie, value)?.let(snapshot::add)
        }
        incoming.forEach { (name, value) ->
            try { snapshot += Cookie.Builder().name(name).value(value).hostOnlyDomain(origin.host).path("/").build() }
            catch (_: IllegalArgumentException) { /* Ignore malformed browser cookie pairs. */ }
        }
        cookies.clear()
        cookies += snapshot
        persist()
    }

    private fun cookieWithValue(cookie: Cookie, value: String): Cookie? = try {
        Cookie.Builder().name(cookie.name).value(value).path(cookie.path).expiresAt(cookie.expiresAt).apply {
            if (cookie.hostOnly) hostOnlyDomain(cookie.domain) else domain(cookie.domain)
            if (cookie.secure) secure()
            if (cookie.httpOnly) httpOnly()
        }.build()
    } catch (_: IllegalArgumentException) { null }
    @Synchronized fun clear() { cookies.clear(); store.clearCookies(accountId) }
    @Synchronized fun invalidate(clearSaved: Boolean = true) {
        active = false
        cookies.clear()
        if (clearSaved) store.clearCookies(accountId)
    }

    private fun persist() = store.saveCookies(accountId, JSONArray().apply {
        cookies.forEach { put(JSONObject().put("name", it.name).put("value", it.value)
            .put("domain", it.domain).put("path", it.path).put("expires", it.expiresAt)
            .put("hostOnly", it.hostOnly).put("secure", it.secure).put("httpOnly", it.httpOnly)) }
    }.toString())
}
