package cn.edu.bucea.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.SeatFavoritesStore
import cn.edu.bucea.library.data.models.LibrarySeat
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeatFavoritesInstrumentedTest {
    @Test
    fun favoritesPersistAndEditingOneAccountCannotChangeAnother() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = "instrumentation_fake_${UUID.randomUUID()}_seat_favorites"
        val preferences = instrumentation.targetContext.getSharedPreferences(file, Context.MODE_PRIVATE)
        val isolatedContext = object : ContextWrapper(instrumentation.context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                require(name == "seat_favorites")
                return preferences
            }
        }
        try {
            val first = LibrarySeat("fake-seat-1", "001D", "二层内环区", "空闲", true)
            val second = LibrarySeat("fake-seat-2", "002A", "三层外环区", "空闲", true)
            val store = SeatFavoritesStore(isolatedContext)
            store.toggle("fake-account-A", first)
            store.toggle("fake-account-B", first)
            store.toggle("fake-account-B", second)
            assertEquals(listOf(first.id), SeatFavoritesStore(isolatedContext).seats("fake-account-A").map { it.id })
            assertEquals(listOf(first.id, second.id), SeatFavoritesStore(isolatedContext).seats("fake-account-B").map { it.id })

            store.remove("fake-account-A", first.id)
            assertTrue(SeatFavoritesStore(isolatedContext).seats("fake-account-A").isEmpty())
            assertEquals(2, SeatFavoritesStore(isolatedContext).seats("fake-account-B").size)
            store.toggle("fake-account-B", first)
            assertEquals(listOf(second.id), SeatFavoritesStore(isolatedContext).seats("fake-account-B").map { it.id })
            assertFalse(SeatFavoritesStore(isolatedContext).seats("fake-account-B").any { it.id == first.id })
            store.clear("fake-account-B")
            assertTrue(SeatFavoritesStore(isolatedContext).seats("fake-account-B").isEmpty())
        } finally {
            preferences.edit().clear().commit()
            instrumentation.targetContext.deleteSharedPreferences(file)
        }
    }
}
