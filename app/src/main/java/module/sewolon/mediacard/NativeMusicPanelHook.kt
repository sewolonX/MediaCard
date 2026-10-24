package module.sewolon.mediacard

import android.content.Context
import android.view.View
import io.github.libxposed.api.XposedInterface

/** Opens the OEM PhoneStyle media dialog directly from a SystemUI media-card cover. */
internal object NativeMusicPanelHook {
    private const val SEPARATE_QS_MANAGER = "com.oplus.systemui.separate.OplusSeparateQSManager"
    private const val SEPARATE_QS_PLUGIN = "com.android.systemui.plugins.qs.OplusSeparateQSPlugin"

    @Volatile private var separateQsManager: Any? = null

    fun install(loader: ClassLoader) {
        runCatching {
            val managerType = Reflect.findClass(SEPARATE_QS_MANAGER, loader)
            managerType.declaredConstructors.forEachIndexed { index, constructor ->
                HookRuntime.hook("systemui:phone-style-manager:$index", constructor,
                    XposedInterface.Hooker { chain ->
                        val result = chain.proceed()
                        chain.thisObject?.let(::captureManager)
                        result
                    })
            }

            val pluginType = Reflect.findClass(SEPARATE_QS_PLUGIN, loader)
            val pluginConnected = managerType.getDeclaredMethod(
                "onPluginConnected", pluginType, Context::class.java,
            )
            HookRuntime.hook("systemui:phone-style-plugin-connected", pluginConnected,
                XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let(::captureManager)
                    result
                })
            HookRuntime.installInfo("Native PhoneStyle media dialog direct hook installed")
        }.onFailure {
            HookRuntime.installWarn("Native PhoneStyle media dialog hook unavailable", it)
        }
    }

    /** Resolves the OEM dialog controller from its Dagger Lazy factory without opening Quick Settings. */
    fun open(albumArtView: View): Boolean {
        val manager = separateQsManager
            ?: return unavailable("OplusSeparateQSManager was not captured")
        return runCatching {
            val factoryLazy = Reflect.field(manager, "qsMediaFactory")
                ?: error("OEM PhoneStyle media factory was unavailable")
            val factory = Reflect.call(factoryLazy, "get")
                ?: error("OEM PhoneStyle media factory returned null")
            val dialogController = Reflect.field(factory, "qsMediaDialogController")
                ?: error("OEM PhoneStyle dialog controller was unavailable")

            // showPrompt stores this view as the launch-animation anchor, then asks the native
            // dialog interactor to show the full PhoneStyle media player. It does not require a
            // media tile or an attached Quick Settings media panel view.
            Reflect.call(dialogController, "showPrompt", albumArtView)
            HookRuntime.info("Requested native PhoneStyle media dialog directly from album art")
            true
        }.onFailure {
            HookRuntime.warn("Cannot open native PhoneStyle media dialog directly", it)
        }.getOrDefault(false)
    }

    private fun captureManager(manager: Any) {
        separateQsManager = manager
        if (HookRuntime.isLogsEnabled()) {
            HookRuntime.info("Captured native PhoneStyle dialog factory host: ${manager.javaClass.name}")
        }
    }

    private fun unavailable(reason: String): Boolean {
        HookRuntime.warn("Native PhoneStyle media dialog unavailable: $reason")
        return false
    }
}
