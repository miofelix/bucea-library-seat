package cn.edu.bucea.library.data

import android.content.Context
import cn.edu.bucea.library.AppThemeColor

class AppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("library_appearance", Context.MODE_PRIVATE)

    var themeColor: AppThemeColor
        get() = AppThemeColor.fromPreference(preferences.getString("theme_color", null))
        set(value) { preferences.edit().putString("theme_color", value.preferenceValue).apply() }
}
