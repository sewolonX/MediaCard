package module.sewolon.mediacard

import android.content.SharedPreferences

/** Shared setting names used by the settings app and API 102 hook processes. */
internal object HookFeatureConfig {
    const val GROUP = "mediacard_hooks"
    const val MASTER = "enabled"
    const val SWIPE = "hook_swipe_protection"
    const val LOGS = "logs_enabled"
    const val MEDIA_COVER_MODE = "media_cover_mode"
    const val MEDIA_COVER_ADAPTIVE_WIDTH = "media_cover_adaptive_width"

    private val swipeProtectionHookIds = setOf(
        "systemui:music-row-gesture-enabled",
        "systemui:music-row-can-dismiss",
        "systemui:music-row-dismissible",
        "systemui:music-swipe-dismiss-commit",
    )

    enum class MediaCoverMode(val storedValue: String) {
        SMALL("small"),
        LARGE("large"),
        FILL_BACKGROUND("fill_background"),
        BLURRED_BACKGROUND("blurred_background");

        val isBackground: Boolean
            get() = this == FILL_BACKGROUND || this == BLURRED_BACKGROUND

        val supportsAdaptiveWidth: Boolean
            get() = this == SMALL || this == LARGE

        companion object {
            fun fromStoredValue(value: String?): MediaCoverMode =
                values().firstOrNull { it.storedValue == value } ?: SMALL
        }
    }

    data class Settings(
        val master: Boolean = true,
        val swipe: Boolean = true,
        val logsEnabled: Boolean = false,
        val mediaCoverMode: MediaCoverMode = MediaCoverMode.SMALL,
        val mediaCoverAdaptiveWidth: Boolean = false,
    )

    fun read(preferences: SharedPreferences?): Settings {
        val prefs = preferences ?: return Settings()
        return Settings(
            master = prefs.getBoolean(MASTER, true),
            swipe = prefs.getBoolean(SWIPE, true),
            logsEnabled = prefs.getBoolean(LOGS, false),
            mediaCoverMode = MediaCoverMode.fromStoredValue(prefs.getString(MEDIA_COVER_MODE, null)),
            mediaCoverAdaptiveWidth = prefs.getBoolean(MEDIA_COVER_ADAPTIVE_WIDTH, false),
        )
    }

    fun withValue(settings: Settings, key: String, value: Boolean): Settings = when (key) {
        MASTER -> settings.copy(master = value)
        SWIPE -> settings.copy(swipe = value)
        LOGS -> settings.copy(logsEnabled = value)
        MEDIA_COVER_ADAPTIVE_WIDTH -> settings.copy(mediaCoverAdaptiveWidth = value)
        else -> settings
    }

    fun withMediaCoverMode(settings: Settings, value: MediaCoverMode): Settings =
        settings.copy(mediaCoverMode = value)

    fun isHookEnabled(id: String, settings: Settings): Boolean {
        if (!settings.master) return false
        if (id in swipeProtectionHookIds && !settings.swipe) return false
        return true
    }
}
