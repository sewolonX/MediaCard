package module.sewolon.mediacard

import android.util.Log
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Compact identifier for correlating log events without printing media keys. */
internal fun diagnosticKey(value: String): String = Integer.toHexString(value.hashCode())

internal object HookRuntime {
    @Volatile private var module: XposedModule? = null
    private val installed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var settings = HookFeatureConfig.Settings()
    @Volatile private var preferences: SharedPreferences? = null
    @Volatile private var preferencesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun attach(module: XposedModule) {
        this.module = module
    }

    fun isLogsEnabled(): Boolean = settings.logsEnabled

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
                    )
                ) {
                    settings = HookFeatureConfig.read(changed)
                    info("Hook settings refreshed${key?.let { ": $it" } ?: ""}")
                }
            }
            preferencesListener = listener
            runCatching { preferences.registerOnSharedPreferenceChangeListener(listener) }
                .onFailure { installWarn("Cannot observe remote hook settings", it) }
            settings = HookFeatureConfig.read(preferences)
        }
    }

    fun deoptimize(executable: Executable) {
        runCatching { requireNotNull(module).deoptimize(executable) }
            .onFailure { installWarn("Cannot deoptimize ${executable.name}", it) }
    }

    fun forgetInstalledHook(id: String) {
        installed.remove(id)
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

    fun info(message: String) = write(Log.INFO, message, null)
    fun warn(message: String, error: Throwable? = null) = write(Log.WARN, message, error)

    fun installInfo(message: String) = write(Log.INFO, message, null, always = true)
    fun installWarn(message: String, error: Throwable? = null) =
        write(Log.WARN, message, error, always = true)

    private fun write(priority: Int, message: String, error: Throwable?, always: Boolean = false) {
        if (!always && !settings.logsEnabled) return
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
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }
}
