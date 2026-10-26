package module.sewolon.mediacard

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.jvm.javaObjectType

/** Keep the exact media key in local troubleshooting logs so events can be correlated. */
internal fun mediaKeyForLog(value: String): String = value

internal object HookRuntime {
    @Volatile private var module: XposedModule? = null
    private val installed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var settings = HookFeatureConfig.Settings()
    @Volatile private var preferences: SharedPreferences? = null
    @Volatile private var preferencesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val settingsListeners = CopyOnWriteArrayList<(HookFeatureConfig.Settings) -> Unit>()

    fun attach(module: XposedModule) {
        this.module = module
    }

    fun isLogsEnabled(): Boolean = settings.logsEnabled

    fun settingsSnapshot(): HookFeatureConfig.Settings = settings

    fun addSettingsListener(listener: (HookFeatureConfig.Settings) -> Unit) {
        settingsListeners.addIfAbsent(listener)
        runCatching { listener(settings) }
            .onFailure { installWarn("Hook settings listener failed", it) }
    }

    fun attachPreferences(preferences: SharedPreferences) {
        synchronized(this) {
            this.preferences?.let { old ->
                preferencesListener?.let { listener ->
                    runCatching { old.unregisterOnSharedPreferenceChangeListener(listener) }
                }
            }
            this.preferences = preferences
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
                if (key == null || key in setOf(
                        HookFeatureConfig.MASTER,
                        HookFeatureConfig.SWIPE,
                        HookFeatureConfig.LOGS,
                        HookFeatureConfig.MEDIA_COVER_MODE,
                        HookFeatureConfig.MEDIA_COVER_ADAPTIVE_WIDTH,
                    )
                ) {
                    val updated = HookFeatureConfig.read(changed)
                    publishSettings(updated)
                    // Keep setting refresh events visible when the log switch itself is off.
                    hotReloadInfo(
                        "Hook settings refreshed${key?.let { ": $it" } ?: ""}; " +
                            "logsEnabled=${updated.logsEnabled}",
                    )
                }
            }
            preferencesListener = listener
            runCatching { preferences.registerOnSharedPreferenceChangeListener(listener) }
                .onFailure { installWarn("Cannot observe remote hook settings", it) }
            publishSettings(HookFeatureConfig.read(preferences))
        }
    }

    private fun publishSettings(updated: HookFeatureConfig.Settings) {
        settings = updated
        settingsListeners.forEach { listener ->
            runCatching { listener(updated) }
                .onFailure { installWarn("Hook settings listener failed", it) }
        }
    }

    fun deoptimize(executable: Executable) {
        runCatching { requireNotNull(module).deoptimize(executable) }
            .onFailure { installWarn("Cannot deoptimize ${executable.name}", it) }
    }

    fun forgetInstalledHook(id: String) {
        installed.remove(id)
    }

    fun clearInstalledHooks() {
        installed.clear()
    }

    fun hook(key: String, executable: Executable, hooker: XposedInterface.Hooker) {
        if (!installed.add(key)) return
        runCatching {
            requireNotNull(module) { "Xposed module runtime is not attached" }
                .hook(executable)
                .setId(key)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain ->
                    if (HookFeatureConfig.isHookEnabled(key, settings)) hooker.intercept(chain)
                    else chain.proceed()
                })
        }.onFailure {
            installed.remove(key)
            installWarn("Cannot install $key", it)
        }
    }

    // Eager APIs are for already-gated diagnostics and hook-installation paths.
    fun info(message: String) = write(Log.INFO, message, null)
    fun warn(message: String, error: Throwable? = null) = write(Log.WARN, message, error)

    /** Build runtime diagnostics only while the user-facing log switch is enabled. */
    fun runtimeInfo(message: () -> String) {
        if (!settings.logsEnabled) return
        write(Log.INFO, message(), null)
    }

    fun runtimeWarn(error: Throwable? = null, message: () -> String) {
        if (!settings.logsEnabled) return
        write(Log.WARN, message(), error)
    }

    // Hook installation and runtime diagnostics follow the user-facing log switch too.
    fun installInfo(message: String) = info(message)
    fun installWarn(message: String, error: Throwable? = null) =
        warn(message, error)

    // Keep lifecycle and remotely-applied setting diagnostics visible when logs are off.
    fun hotReloadInfo(message: String) = write(Log.INFO, message, null, keepWhenDisabled = true)
    fun hotReloadWarn(message: String, error: Throwable? = null) =
        write(Log.WARN, message, error, keepWhenDisabled = true)

    private fun write(
        priority: Int,
        message: String,
        error: Throwable?,
        keepWhenDisabled: Boolean = false,
    ) {
        if (!keepWhenDisabled && !settings.logsEnabled) return
        val active = module
        if (active != null) {
            runCatching { active.log(priority, "MediaCard-LSP", message, error) }
                .onSuccess { return }
        }
        Log.println(priority, "MediaCard-LSP", message + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }
}

internal object Reflect {
    private val fields = object : ClassValue<ConcurrentHashMap<String, Field>>() {
        override fun computeValue(type: Class<*>): ConcurrentHashMap<String, Field> = ConcurrentHashMap()
    }
    private val methods = object : ClassValue<ConcurrentHashMap<MethodKey, Method>>() {
        override fun computeValue(type: Class<*>): ConcurrentHashMap<MethodKey, Method> = ConcurrentHashMap()
    }

    fun findClass(name: String, loader: ClassLoader): Class<*> = Class.forName(name, false, loader)

    fun field(target: Any, name: String): Any? = findField(target.javaClass, name).get(target)

    fun intField(target: Any, name: String): Int = findField(target.javaClass, name).getInt(target)

    fun staticField(type: Class<*>, name: String): Any? = findField(type, name).get(null)

    fun call(target: Any, name: String, vararg args: Any?): Any? =
        method(target.javaClass, name, args, static = false).invoke(target, *args)

    fun callStatic(type: Class<*>, name: String, vararg args: Any?): Any? =
        method(type, name, args, static = true).invoke(null, *args)

    private fun findField(type: Class<*>, name: String): Field =
        fields.get(type).computeIfAbsent(name) { findFieldUncached(type, it) }

    private fun findFieldUncached(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        error("Field $name not found on ${type.name}")
    }

    private fun method(type: Class<*>, name: String, args: Array<out Any?>, static: Boolean): Method {
        val key = MethodKey(name, args.map { it?.javaClass }, static)
        return methods.get(type).computeIfAbsent(key) { findMethodUncached(type, name, args, static) }
    }

    private fun findMethodUncached(type: Class<*>, name: String, args: Array<out Any?>, static: Boolean): Method {
        val methods = generateSequence(type as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { it.name == name && Modifier.isStatic(it.modifiers) == static }
            .filter { candidate ->
                candidate.parameterCount == args.size && candidate.parameterTypes.zip(args).all { (parameter, arg) ->
                    arg == null || boxed(parameter).isAssignableFrom(arg.javaClass)
                }
            }
            .toList()
        val selected = methods.minByOrNull { candidate ->
            candidate.parameterTypes.zip(args).sumOf { (parameter, arg) ->
                if (arg == null || boxed(parameter) == arg.javaClass) 0 else 1
            }
        } ?: error("Method ${type.name}#$name/${args.size} not found")
        selected.isAccessible = true
        return selected
    }

    private data class MethodKey(
        val name: String,
        val argumentTypes: List<Class<*>?>,
        val static: Boolean,
    )

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        java.lang.Byte.TYPE -> Byte::class.javaObjectType
        java.lang.Short.TYPE -> Short::class.javaObjectType
        java.lang.Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        java.lang.Float.TYPE -> Float::class.javaObjectType
        java.lang.Double.TYPE -> Double::class.javaObjectType
        java.lang.Character.TYPE -> Char::class.javaObjectType
        else -> type
    }
}
