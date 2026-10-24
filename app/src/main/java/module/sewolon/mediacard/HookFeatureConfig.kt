package module.sewolon.mediacard

import android.content.SharedPreferences

/** Shared setting names used by the settings app and API 102 hook processes. */
internal object HookFeatureConfig {
    const val GROUP = "mediacard_hooks"
    const val MASTER = "enabled"
    const val SWIPE = "hook_swipe_protection"
    const val LOGS = "logs_enabled"

    data class Settings(
        val master: Boolean = true,
        val swipe: Boolean = true,
        val logsEnabled: Boolean = false,
    )

    fun read(preferences: SharedPreferences?): Settings {
        val prefs = preferences ?: return Settings()
        return Settings(
            master = prefs.getBoolean(MASTER, true),
            swipe = prefs.getBoolean(SWIPE, true),
            logsEnabled = prefs.getBoolean(LOGS, false),
        )
    }

    fun withValue(settings: Settings, key: String, value: Boolean): Settings = when (key) {
        MASTER -> settings.copy(master = value)
        SWIPE -> settings.copy(swipe = value)
        LOGS -> settings.copy(logsEnabled = value)
        else -> settings
    }

    fun isHookEnabled(id: String, settings: Settings): Boolean {
        if (!settings.master) return false
        val isSwipeHook = id.contains("swipe") || id.contains("dismiss") ||
            id.contains("can-drag") || id.contains("row-gesture")
        if (isSwipeHook && !settings.swipe) return false
        return true
    }
}
