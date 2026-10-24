package module.sewolon.mediacard

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

internal object SettingsService : XposedServiceHelper.OnServiceListener {
    data class Connection(
        val preferences: SharedPreferences? = null,
        val scopeSufficient: Boolean? = null,
    )

    private val callbacks = CopyOnWriteArrayList<(Connection) -> Unit>()
    @Volatile private var connection = Connection()
    @Volatile private var registered = false

    fun connect(callback: (Connection) -> Unit) {
        callbacks += callback
        callback(connection)
        if (!registered) synchronized(this) {
            if (!registered) {
                registered = true
                XposedServiceHelper.registerListener(this)
            }
        }
    }

    fun disconnect(callback: (Connection) -> Unit) {
        callbacks -= callback
    }

    override fun onServiceBind(service: XposedService) {
        connection = Connection(
            preferences = runCatching { service.getRemotePreferences(HookFeatureConfig.GROUP) }.getOrNull(),
            scopeSufficient = runCatching {
                service.scope.containsAll(
                    listOf(MediaCardModule.SYSTEM_UI, MediaCardModule.NOTIFICATION_MANAGER),
                )
            }.getOrNull(),
        )
        callbacks.forEach { it(connection) }
    }

    override fun onServiceDied(service: XposedService) {
        connection = Connection()
        callbacks.forEach { it(connection) }
    }
}
