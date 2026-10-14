package cn.edu.bucea.library

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AppearanceStore
import cn.edu.bucea.library.data.LauncherIconController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeLauncherInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test fun themeChangesPersistAndKeepExactlyOneLauncherWithTheNativeActivityEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val preferences = context.getSharedPreferences("library_appearance", 0)
        val hadPreference = preferences.contains("theme_color")
        val originalPreference = preferences.getString("theme_color", null)
        val originalTheme = AppThemeColor.fromPreference(originalPreference)
        val components = AppThemeColor.entries.associateWith { LauncherIconController.componentName(context, it) }
        val originalStates = components.values.associateWith(packageManager::getComponentEnabledSetting)
        val icons = mutableSetOf<Int>()
        try {
            val appearance = AppearanceStore(context)
            AppThemeColor.entries.forEach { color ->
                appearance.themeColor = color
                assertEquals(color, AppearanceStore(context).themeColor)
                val entries = packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
                assertEquals("Exactly one launcher for ${color.label}", 1, entries.size)
                assertEquals(components.getValue(color).className, entries.single().activityInfo.name)
                icons += entries.single().activityInfo.iconResource
                assertEquals(MainActivity::class.java.name, entries.single().activityInfo.targetActivity)
                assertEquals(components.getValue(color), packageManager.getLaunchIntentForPackage(context.packageName)?.component)
                val mainActivity = packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
                assertTrue("The original activity remains launchable", mainActivity.enabled && mainActivity.exported)
            }
            assertEquals("Each theme uses a distinct icon resource", AppThemeColor.entries.size, icons.size)
        } finally {
            AppearanceStore(context).themeColor = originalTheme
            val editor = preferences.edit()
            if (hadPreference) editor.putString("theme_color", originalPreference) else editor.remove("theme_color")
            assertTrue(editor.commit())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(originalStates.map { (component, state) ->
                    PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                })
            } else {
                originalStates.entries.sortedByDescending { (component, state) ->
                    state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                        (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && component == components.getValue(AppThemeColor.BUCEA_PURPLE))
                }.forEach { (component, state) ->
                    packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
            }
        }
    }
}
