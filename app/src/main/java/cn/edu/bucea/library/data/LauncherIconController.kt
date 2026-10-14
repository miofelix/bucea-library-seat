package cn.edu.bucea.library.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import cn.edu.bucea.library.AppThemeColor

/** Launcher aliases share MainActivity; changing icons never disables the real activity. */
internal class LauncherIconController(private val context: Context) {
    fun apply(themeColor: AppThemeColor) {
        val packageManager = context.packageManager
        val changes = AppThemeColor.entries.mapNotNull { color ->
            val component = componentName(context, color)
            val enabled = when (packageManager.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                else -> color == AppThemeColor.BUCEA_PURPLE
            }
            val shouldEnable = color == themeColor
            if (enabled == shouldEnable) null else component to if (shouldEnable)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (changes.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(changes.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            })
        } else {
            // Enable the new launcher first so older Android releases always have an entry point.
            changes.sortedByDescending { (_, state) -> state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
                .forEach { (component, state) ->
                    packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
        }
    }

    companion object {
        fun componentName(context: Context, color: AppThemeColor): ComponentName {
            val alias = when (color) {
                AppThemeColor.BUCEA_PURPLE -> "BuceaPurple"
                AppThemeColor.BLUE -> "Blue"
                AppThemeColor.TEAL -> "Teal"
                AppThemeColor.ORANGE -> "Orange"
                AppThemeColor.ROSE -> "Rose"
                AppThemeColor.NAILONG_YELLOW -> "NailongYellow"
            }
            return ComponentName(context.packageName, "${context.packageName}.launcher.$alias")
        }
    }
}
