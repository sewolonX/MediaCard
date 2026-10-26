package module.sewolon.mediacard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Pair
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.ConcurrentHashMap

/** API 102 entry for the music Live Alert card only. */
class MediaCardModule : XposedModule() {
    private val dispatched = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var targetPackageName: String? = null
    @Volatile private var targetClassLoader: ClassLoader? = null
    @Volatile private var pluginClassLoader: ClassLoader? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookRuntime.attach(this)
        runCatching { getRemotePreferences(HookFeatureConfig.GROUP) }
            .onSuccess(HookRuntime::attachPreferences)
            .onFailure { HookRuntime.installWarn("Remote hook settings unavailable; using enabled defaults", it) }
        HookRuntime.installInfo("API $apiVersion active in ${param.processName}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        installForTarget(param.packageName, param.classLoader)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        val packageName = targetPackageName
        val classLoader = targetClassLoader
        if (packageName != null && classLoader != null) {
            // Preserve the target app loader, which belongs to the host process, not this module generation.
            val target = Pair.create(packageName, classLoader)
            val targetAndPlugin = Pair.create(target, pluginClassLoader)
            val reloadTargets = Pair.create(
                MusicPluginHooks.prepareForHotReload(),
                MediaCoverAppearanceHook.snapshotBackgroundClickTargetsForHotReload(),
            )
            val managerAndTargets = Pair.create(NativeMusicPanelHook.managerForHotReload(), reloadTargets)
            param.setSavedInstanceState(
                Pair.create(targetAndPlugin, managerAndTargets),
            )
        }
        HookRuntime.hotReloadInfo("Approving API 102 hot reload")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        HookRuntime.attach(this)
        runCatching { getRemotePreferences(HookFeatureConfig.GROUP) }
            .onSuccess(HookRuntime::attachPreferences)
            .onFailure { HookRuntime.hotReloadWarn("Remote hook settings unavailable after hot reload", it) }
        var unhooked = 0
        var unhookFailures = 0
        param.oldHookHandles.forEach { handle ->
            runCatching { handle.unhook() }
                .onSuccess {
                    unhooked++
                    handle.id?.let(HookRuntime::forgetInstalledHook)
                }
                .onFailure {
                    unhookFailures++
                    HookRuntime.hotReloadWarn("Cannot unhook old hook ${handle.id}", it)
                }
        }
        // Preserve IDs for handles that failed to unhook so hook installation cannot stack a
        // second callback on top of an old one. Successful handles already forget their IDs.
        if (unhookFailures == 0) HookRuntime.clearInstalledHooks()
        dispatched.clear()
        HookRuntime.hotReloadInfo(
            "Hot reload received ${param.oldHookHandles.size} old hooks; " +
                "removed=$unhooked, unhookFailed=$unhookFailures",
        )
        val saved = param.savedInstanceState as? Pair<*, *> ?: return
        val first = saved.first as? Pair<*, *>
        // New state wraps target/plugin loaders and the existing OEM manager; accept the
        // previous target/plugin pair too so cross-version reloads remain compatible.
        val targetAndPlugin = if (first?.first is Pair<*, *>) first else null
        val target = (targetAndPlugin?.first ?: saved.first) as? Pair<*, *> ?: return
        val packageName = target.first as? String ?: return
        val classLoader = target.second as? ClassLoader ?: return
        val managerAndTargets = saved.second as? Pair<*, *>
        val reloadTargets = managerAndTargets?.second as? Pair<*, *>
        val savedManager = if (reloadTargets != null) managerAndTargets?.first else saved.second
        pluginClassLoader = (targetAndPlugin?.second ?: saved.second) as? ClassLoader
        if (targetAndPlugin != null) NativeMusicPanelHook.restoreManager(savedManager)
        installForTarget(packageName, classLoader)
        if (packageName == SYSTEM_UI) {
            pluginClassLoader?.let(MusicPluginHooks::install)
            val albumArtViews = (reloadTargets?.first as? List<*>)
                ?.mapNotNull { it as? android.view.View }
                .orEmpty()
            MusicPluginHooks.restoreAlbumArtClickRoutingAfterHotReload(albumArtViews)
            val backgroundTargets = (reloadTargets?.second as? List<*>)
                ?.mapNotNull { item ->
                    val pair = item as? Pair<*, *> ?: return@mapNotNull null
                    val background = pair.first as? android.view.View ?: return@mapNotNull null
                    val cover = pair.second as? android.view.View ?: return@mapNotNull null
                    background to cover
                }
                .orEmpty()
            MediaCoverAppearanceHook.restoreBackgroundClickTargetsAfterHotReload(backgroundTargets)
        }
    }

    private fun installForTarget(packageName: String, loader: ClassLoader) {
        if (packageName != SYSTEM_UI && packageName != NOTIFICATION_MANAGER) return
        targetPackageName = packageName
        targetClassLoader = loader
        val key = "$packageName@${System.identityHashCode(loader)}"
        if (!dispatched.add(key)) return

        when (packageName) {
            SYSTEM_UI -> SystemUiMediaHooks.install(loader) { pluginClassLoader = it }
            NOTIFICATION_MANAGER -> NotificationCenterMediaHooks.install(loader)
        }
    }

    companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val NOTIFICATION_MANAGER = "com.oplus.notificationmanager"
        const val MUSIC_SERVICE_ID = "268452006"
        const val NOTIFICATION_CENTER_HOST = 16
    }
}

internal object SystemUiMediaHooks {
    private const val PLUGIN_PACKAGE = "com.oplus.systemui.plugins"

    fun install(loader: ClassLoader, onPluginClassLoader: (ClassLoader) -> Unit) {
        MusicLiveAlertHostHook.install(loader)
        MusicLockScreenCoverHook.install(loader)
        MusicSeedlingPlayback.install(loader)
        MusicSeedlingServiceHook.install(loader)
        MusicMediaCreationHook.install(loader)
        MusicMediaDataRemovalHook.install(loader)
        MusicMediaRowHook.install(loader)
        NativeMusicPanelHook.install(loader)
        MusicSwipeDismissHook.install(loader)
        installPluginFactory(loader, onPluginClassLoader)
        HookRuntime.installInfo("Music-only SystemUI hooks installed")
    }

    private fun installPluginFactory(loader: ClassLoader, onPluginClassLoader: (ClassLoader) -> Unit) {
        runCatching {
            val factory = Reflect.findClass(
                "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory", loader,
            )
            listOf("createClassLoader", "createPluginContext").forEach { name ->
                val method = factory.getDeclaredMethod(name)
                HookRuntime.hook("plugin-factory:$name", method,
                    io.github.libxposed.api.XposedInterface.Hooker { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val info = Reflect.field(
                                requireNotNull(chain.thisObject),
                                "pluginAppInfo",
                            ) as? ApplicationInfo
                            if (info?.packageName != PLUGIN_PACKAGE) return@runCatching
                            val pluginLoader = when (result) {
                                is ClassLoader -> result
                                is Context -> result.classLoader
                                else -> null
                            }
                            pluginLoader?.let {
                                MusicPluginHooks.install(it)
                                onPluginClassLoader(it)
                            }
                        }.onFailure { HookRuntime.installWarn("Plugin hook setup failed", it) }
                        result
                    })
            }
        }.onFailure { HookRuntime.installWarn("SystemUI plugin factory unavailable", it) }
    }
}
