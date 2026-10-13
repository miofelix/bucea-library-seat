package cn.edu.bucea.library.data

import android.content.Context
import cn.edu.bucea.library.data.models.LibrarySeat
import org.json.JSONArray
import org.json.JSONObject

/** Personal quick access, scoped by the local account id; independent from site usage history. */
class SeatFavoritesStore(context: Context) {
    private val preferences = context.getSharedPreferences("seat_favorites", Context.MODE_PRIVATE)

    fun seats(accountId: String): List<LibrarySeat> {
        val values = JSONArray(preferences.getString(accountId, "[]"))
        return (0 until values.length()).map { values.getJSONObject(it) }.map {
            LibrarySeat(it.getString("id"), it.getString("label"), it.getString("room"), "收藏座位", true)
        }
    }

    fun toggle(accountId: String, seat: LibrarySeat) {
        val current = seats(accountId).toMutableList()
        if (current.any { it.id == seat.id }) current.removeAll { it.id == seat.id } else current.add(seat)
        preferences.edit().putString(accountId, JSONArray().apply {
            current.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("room", it.room)) }
        }.toString()).apply()
    }

    fun remove(accountId: String, id: String) {
        seats(accountId).find { it.id == id }?.let { toggle(accountId, it) }
    }

    fun clear(accountId: String) { preferences.edit().remove(accountId).apply() }
}
