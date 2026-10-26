package module.sewolon.mediacard

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

internal object SettingsService : XposedServiceHelper.OnServiceListener {
    data class Connection(
        val preferences: SharedPreferences? = null,
        val activeScopes: Set<String>? = null,
    ) {
        val scopeSufficient: Boolean?
            get() = activeScopes?.containsAll(SettingsService.requiredScopes)
    }

    private val callbacks = CopyOnWriteArrayList<(Connection) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scopeRefreshGeneration = AtomicLong()
    private val requiredScopes = setOf(MediaCardModule.SYSTEM_UI, MediaCardModule.NOTIFICATION_MANAGER)
    @Volatile private var connection = Connection()
    @Volatile private var boundService: XposedService? = null
    @Volatile private var registered = false

    fun currentConnection(): Connection = connection

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

    /** Refresh the active LSPosed scope when settings becomes visible; the cached value can be stale. */
    fun refreshScope() {
        val service = boundService ?: return
        val generation = scopeRefreshGeneration.incrementAndGet()
        val previousScopes = connection.activeScopes
        val pending = connection.copy(activeScopes = null)
        connection = pending
        callbacks.forEach { it(pending) }
        logScopeState("refresh-start", previousScopes)

        Thread({
            val activeScopes = readActiveScopes(service)
            val refreshed = synchronized(this) {
                if (boundService !== service || scopeRefreshGeneration.get() != generation) {
                    null
                } else {
                    connection.copy(activeScopes = activeScopes).also { connection = it }
                }
            } ?: return@Thread
            logScopeState("refresh-complete", activeScopes)
            mainHandler.post {
                if (boundService !== service || scopeRefreshGeneration.get() != generation) return@post
                callbacks.forEach { it(refreshed) }
            }
        }, "MediaCardScopeRefresh").apply { isDaemon = true }.start()
    }

    override fun onServiceBind(service: XposedService) {
        boundService = service
        scopeRefreshGeneration.incrementAndGet()
        val preferences = runCatching { service.getRemotePreferences(HookFeatureConfig.GROUP) }.getOrNull()
        val activeScopes = readActiveScopes(service)
        connection = Connection(
            preferences = preferences,
            activeScopes = activeScopes,
        )
        logScopeState("service-bound", activeScopes)
        callbacks.forEach { it(connection) }
    }

    override fun onServiceDied(service: XposedService) {
        if (boundService !== service) return
        boundService = null
        scopeRefreshGeneration.incrementAndGet()
        connection = Connection()
        callbacks.forEach { it(connection) }
    }

    private fun readActiveScopes(service: XposedService): Set<String>? =
        runCatching { service.scope.toSet() }.getOrNull()

    private fun logScopeState(event: String, activeScopes: Set<String>?) {
        val logsEnabled = runCatching {
            connection.preferences?.getBoolean(HookFeatureConfig.LOGS, false) == true
        }.getOrDefault(false)
        if (!logsEnabled) return

        val systemUiEnabled = activeScopes?.contains(MediaCardModule.SYSTEM_UI)
        val notificationManagerEnabled = activeScopes?.contains(MediaCardModule.NOTIFICATION_MANAGER)
        val scopes = activeScopes?.sorted()?.joinToString(prefix = "[", postfix = "]") ?: "unknown"
        Log.i(
            "MediaCard-LSP",
            "Settings scope $event;systemui=$systemUiEnabled;" +
                "notificationmanager=$notificationManagerEnabled;" +
                "sufficient=${activeScopes?.containsAll(requiredScopes)};activeScopes=$scopes",
        )
    }
}
