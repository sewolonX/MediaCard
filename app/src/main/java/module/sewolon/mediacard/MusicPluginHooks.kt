package module.sewolon.mediacard

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import io.github.libxposed.api.XposedInterface
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Keeps the existing expanded music card in the notification center on the plugin's card page. */
private class AlbumArtClickListener(
    @Volatile var nativeListener: View.OnClickListener?,
) : View.OnClickListener {
    override fun onClick(view: View) {
        if (!HookRuntime.settingsSnapshot().master || !MusicPluginHooks.routeAlbumArtClick(view)) {
            nativeListener?.onClick(view)
        }
    }
}

internal object MusicPluginHooks {
    private const val PARSER = "com.oplus.systemui.plugins.shared.data.parse.r"
    private const val MODEL = "com.oplus.systemui.plugins.shared.data.model.f"
    private const val WRAPPER = "com.oplus.systemui.plugins.shared.template.wrapper.impl.h"
    private const val ALBUM_ART_RESOURCE_NAME = "com.oplus.systemui.plugins:id/img_album_art"
    private const val ALBUM_ART_CLICK_LISTENER_CLASS = "module.sewolon.mediacard.AlbumArtClickListener"
    private class AlbumArtClickState {
        @Volatile var nativeListener: View.OnClickListener? = null
        @Volatile var wrapper: AlbumArtClickListener? = null
    }

    private val parsedMusicModels = WeakIdentitySet()
    private val albumArtClickStates = Collections.synchronizedMap(WeakHashMap<View, AlbumArtClickState>())
    private val lastParserDiagnostic = AtomicReference<String>()
    private val lastConsumerDiagnostics = ConcurrentHashMap<String, String>()

    fun install(loader: ClassLoader) {
        runCatching {
            val parser = Reflect.findClass(PARSER, loader)
            val parse = parser.getDeclaredMethod("b", StatusBarNotification::class.java)
            HookRuntime.hook("plugin:music-model-parser:${System.identityHashCode(loader)}", parse,
                XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    val sbn = chain.args.getOrNull(0) as? StatusBarNotification
                    if (isMusicNotification(sbn)) {
                        val model = runCatching { Reflect.field(requireNotNull(result), "a") }.getOrNull()
                        if (model != null) parsedMusicModels.add(model)
                        if (HookRuntime.isLogsEnabled()) {
                            val cardTemplate = runCatching {
                                (Reflect.field(requireNotNull(model), "a") as? Map<*, *>)?.get("card") != null
                            }.getOrDefault(false)
                            val state = "Music plugin parsed: model=${model != null}, cardTemplate=$cardTemplate"
                            if (lastParserDiagnostic.getAndSet(state) != state) HookRuntime.info(state)
                        }
                    }
                    result
                })
        }.onFailure { HookRuntime.installWarn("Music plugin parser hook unavailable", it) }

        runCatching {
            val model = Reflect.findClass(MODEL, loader)
            val wrapper = Reflect.findClass(WRAPPER, loader)
            val constructor = wrapper.getDeclaredConstructor(
                Context::class.java, Context::class.java, Handler::class.java, Bundle::class.java, model,
            )
            HookRuntime.hook("plugin:music-expanded-card:${System.identityHashCode(loader)}", constructor,
                XposedInterface.Hooker { chain ->
                    val bundle = chain.args.getOrNull(3) as? Bundle ?: return@Hooker chain.proceed()
                    val cardModel = chain.args.getOrNull(4) ?: return@Hooker chain.proceed()
                    if (!parsedMusicModels.contains(cardModel)) return@Hooker chain.proceed()
                    val context = chain.args.getOrNull(0) as? Context ?: return@Hooker chain.proceed()
                    if (runCatching {
                            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
                        }.getOrDefault(true)
                    ) return@Hooker chain.proceed()
                    val hasCardTemplate = runCatching {
                        (Reflect.field(cardModel, "a") as? Map<*, *>)?.get("card") != null
                    }.getOrDefault(false)
                    if (!bundle.getBoolean("isNotificationCard", false) || !hasCardTemplate) {
                        return@Hooker chain.proceed()
                    }
                    val args = chain.args.toTypedArray()
                    args[3] = Bundle(bundle).apply { putBoolean("isNotificationCard", false) }
                    chain.proceed(args)
                })
            HookRuntime.installInfo("Music notification-center expanded template hook installed")
        }.onFailure { HookRuntime.installWarn("Music expanded-card hook unavailable", it) }

        installConsumerDiagnostics(loader)
        installAlbumArtViewTracking(loader)
        MediaCoverAppearanceHook.install(loader)
        MusicCarouselStabilityHook.install(loader)
    }

    /** Track T0.e, the RotatableImageView that the OEM binds to the album-art region. */
    private fun installAlbumArtViewTracking(loader: ClassLoader) {
        runCatching {
            val mediaSection = Reflect.findClass(
                "com.oplus.systemui.plugins.shared.template.section.media.T0", loader,
            )
            require(mediaSection.declaredConstructors.isNotEmpty()) { "No media section constructor found" }
            mediaSection.declaredConstructors.forEachIndexed { index, constructor ->
                HookRuntime.hook("plugin:track-album-art-view:${System.identityHashCode(loader)}:$index", constructor,
                    XposedInterface.Hooker { chain ->
                        val result = chain.proceed()
                        val section = chain.thisObject ?: return@Hooker result
                        val view = runCatching { Reflect.field(section, "e") as? View }.getOrNull()
                            ?: return@Hooker result
                        // In both supplied lock-screen hierarchies this ImageView is not clickable.
                        // The binder hook installs the click wrapper after the OEM has bound its
                        // listener, keeping click routing scoped to this exact media-card view.
                        view.isClickable = true
                        if (HookRuntime.isLogsEnabled()) {
                            HookRuntime.info(
                                "OEM media cover View bound: class=${view.javaClass.name}, " +
                                    "viewId=0x${view.id.toString(16)}, clickable=${view.isClickable}"
                            )
                        }
                        result
                    })
            }
            HookRuntime.installInfo("OEM media cover View tracking hook installed")
        }.onFailure { HookRuntime.installWarn("Media cover View tracking hook unavailable", it) }
    }

    /** Wrap only the bound media-card image's click listener, preserving the OEM callback. */
    internal fun installAlbumArtClickRouting(view: View) {
        if (!isAlbumArtView(view)) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            view.post { installAlbumArtClickRouting(view) }
            return
        }
        val nativeListener = runCatching { readOnClickListener(view) }.getOrElse {
            HookRuntime.runtimeWarn(it) { "Cannot inspect OEM media cover click listener" }
            return
        }
        val unwrappedListener = unwrapPreviousClickListener(nativeListener)
        val state = synchronized(albumArtClickStates) {
            albumArtClickStates.getOrPut(view) { AlbumArtClickState() }
        }
        val wrapper = state.wrapper ?: synchronized(state) {
            state.wrapper ?: AlbumArtClickListener(unwrappedListener).also { state.wrapper = it }
        }
        if (nativeListener !== wrapper) {
            state.nativeListener = unwrappedListener
            wrapper.nativeListener = unwrappedListener
            view.setOnClickListener(wrapper)
        }
    }

    internal fun trackedAlbumArtViewsForHotReload(): List<View> =
        synchronized(albumArtClickStates) { albumArtClickStates.keys.toList() }

    internal fun prepareForHotReload(): List<View> {
        MusicCarouselStabilityHook.clearPendingRestoresForHotReload()
        return trackedAlbumArtViewsForHotReload()
    }

    internal fun restoreAlbumArtClickRoutingAfterHotReload(views: List<View>) {
        views.forEach(::installAlbumArtClickRouting)
    }

    private fun unwrapPreviousClickListener(listener: View.OnClickListener?): View.OnClickListener? {
        if (listener?.javaClass?.name != ALBUM_ART_CLICK_LISTENER_CLASS) return listener
        return runCatching { Reflect.field(listener, "nativeListener") as? View.OnClickListener }
            .getOrElse { listener }
    }

    /** Route a blank background tap through the exact album-art view's normal click path. */
    internal fun routeBlankBackgroundClick(background: View): Boolean {
        val cover = MediaCoverAppearanceHook.takeBlankBackgroundClickTarget(background) ?: return false
        return cover.performClick()
    }

    private fun readOnClickListener(view: View): View.OnClickListener? {
        val listenerInfo = Reflect.field(view, "mListenerInfo") ?: return null
        return Reflect.field(listenerInfo, "mOnClickListener") as? View.OnClickListener
    }

    /** Route clicks from the exact album-art image according to the active SystemUI surface. */
    internal fun routeAlbumArtClick(view: View): Boolean {
        val keyguardLocked = runCatching {
            view.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(true)
        MediaCoverAppearanceHook.repairBackgroundMarginForCard(
            view,
            if (keyguardLocked) "lock-screen-cover-click" else "album-art-click",
        )
        val notificationPanelExpanded = MusicMediaRowHook.isNotificationCenterForeground()
        val notificationCenterSurface = notificationPanelExpanded && !keyguardLocked
        // This cache tracks OPlus Seedling/Live Alert sources only. Standard Android media
        // sessions from other apps are owned by MediaPlayerData and must still reach the
        // native Control Center panel route.
        if (HookRuntime.isLogsEnabled()) {
            val seedlingMedia = MusicSeedlingPlayback.hasEligibleMediaSession()
            HookRuntime.info(
                "OEM media cover tapped: viewId=0x${view.id.toString(16)}, keyguard=$keyguardLocked, " +
                    "notificationPanel=$notificationPanelExpanded, " +
                    "notificationCenterSurface=$notificationCenterSurface, seedlingMedia=$seedlingMedia"
            )
        }

        // Route to PhoneStyle only when the unlocked Notification Center is foreground.
        if (notificationCenterSurface) {
            val opened = NativeMusicPanelHook.open(view)
            if (HookRuntime.isLogsEnabled()) {
                if (opened) {
                    HookRuntime.info("SystemUI album-art click routed directly to native PhoneStyle media dialog")
                } else {
                    HookRuntime.warn("SystemUI album-art click matched; native PhoneStyle media dialog was unavailable")
                }
            }
            // Keep the cover tap from falling through to the card's app-launch listener if
            // the native panel controller is temporarily unavailable.
            return true
        }

        if (keyguardLocked) {
            val dispatched = MusicMediaRowHook.requestActiveLockScreenMusicRow()
            if (HookRuntime.isLogsEnabled()) {
                HookRuntime.info("Lock-screen album-art click consumed: native music expansion dispatched=$dispatched")
            }
            // Never let the enclosing card's app PendingIntent turn this cover tap into an app
            // launch (which would show the credential bouncer instead of the music lock screen).
            return true
        }

        return false
    }

    private fun isAlbumArtView(view: View): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        return runCatching { view.resources.getResourceName(id) == ALBUM_ART_RESOURCE_NAME }
            .getOrDefault(false)
    }

    private fun installConsumerDiagnostics(loader: ClassLoader) {
        runCatching {
            val origin = Reflect.findClass("com.oplus.systemui.livealert.classic.origin.e", loader)
            listOf("r", "s").forEach { name ->
                val method = origin.getDeclaredMethod(name)
                HookRuntime.hook("plugin:music-host-$name:${System.identityHashCode(loader)}", method,
                    XposedInterface.Hooker { chain ->
                        val result = chain.proceed()
                        if (!HookRuntime.isLogsEnabled()) return@Hooker result
                        val current = chain.thisObject
                        if (current != null && isMusicOrigin(current)) {
                            val host = runCatching {
                                Reflect.call(Reflect.call(current, "k")!!, "a") as? Int
                            }.getOrNull()
                            if (host == MediaCardModule.NOTIFICATION_CENTER_HOST) {
                                val key = "host-$name"
                                val state = "Music consumer $key allowed=${result == true}"
                                if (lastConsumerDiagnostics.put(key, state) != state) HookRuntime.info(state)
                            }
                        }
                        result
                    })
            }

            val controller = Reflect.findClass("com.oplus.systemui.plugins.seedling.notification.j", loader)
            val update = controller.getDeclaredMethod(
                "f", String::class.java, List::class.java, Boolean::class.javaPrimitiveType,
            )
            HookRuntime.hook("plugin:music-notification-input:${System.identityHashCode(loader)}", update,
                XposedInterface.Hooker { chain ->
                    if (!HookRuntime.isLogsEnabled()) return@Hooker chain.proceed()
                    val rows = chain.args.getOrNull(1) as? List<*>
                    val musicPresent = rows?.any { row -> runCatching {
                        isMusicOrigin(requireNotNull(Reflect.field(requireNotNull(row), "a")))
                    }.getOrDefault(false) } == true
                    val state = "Music notification input count=${rows?.size ?: -1}, music=$musicPresent"
                    if (lastConsumerDiagnostics.put("input", state) != state) HookRuntime.info(state)
                    chain.proceed()
                })
        }.onFailure { HookRuntime.installWarn("Music consumer diagnostics partly unavailable", it) }
    }

    private fun isMusicOrigin(origin: Any): Boolean = runCatching {
        val key = Reflect.call(origin, "q") as? String
        key?.contains(MediaCardModule.MUSIC_SERVICE_ID) == true
    }.getOrDefault(false)

    private fun isMusicNotification(sbn: StatusBarNotification?): Boolean =
        sbn?.packageName == MediaCardModule.SYSTEM_UI && sbn.id.toString() == MediaCardModule.MUSIC_SERVICE_ID
}

/** Preserve the selected media item when the OEM multi-player list inserts a newly active player. */
private object MusicCarouselStabilityHook {
    private const val PACKAGE = "com.oplus.systemui.plugins.shared.template.section.media."
    private class PagerTouchState(val view: WeakReference<View>) {
        @Volatile var nativeListener: View.OnTouchListener? = null
        @Volatile var wrapper: View.OnTouchListener? = null
    }
    private data class PendingRestore(val view: WeakReference<View>, val callback: Runnable)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingSelection = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val pendingRestoreCallbacks =
        Collections.synchronizedMap(IdentityHashMap<Any, PendingRestore>())
    private val programmaticPageChange = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val lastPagerTouch = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val pagerTouchStates = Collections.synchronizedMap(WeakHashMap<View, WeakReference<PagerTouchState>>())
    private val settingsListener: (HookFeatureConfig.Settings) -> Unit = { settings ->
        if (!settings.master) {
            mainHandler.post {
                if (!HookRuntime.settingsSnapshot().master) clearPendingRestores()
            }
        }
    }

    fun install(loader: ClassLoader) {
        HookRuntime.addSettingsListener(settingsListener)
        runCatching {
            val section = Reflect.findClass("${PACKAGE}e1", loader)
            val adapter = Reflect.findClass("${PACKAGE}b1", loader)
            val mediaModel = Reflect.findClass(
                "com.oplus.systemui.plugins.shared.data.media.model.k", loader,
            )
            val callback = Reflect.findClass(
                "com.oplus.systemui.plugins.seedling.notification.surface.viewholder.e", loader,
            )
            val keyProvider = Reflect.findClass("com.heytap.log.strategy.f", loader)
            val stableKey = keyProvider.getDeclaredMethod("B", mediaModel).apply { isAccessible = true }

            val submit = adapter.getDeclaredMethod("b", List::class.java, callback)
            HookRuntime.deoptimize(submit)
            HookRuntime.hook("plugin:preserve-selected-media-on-insert", submit, XposedInterface.Hooker { chain ->
                val receiver = chain.thisObject ?: return@Hooker chain.proceed()
                val sectionInstance = runCatching { Reflect.field(receiver, "a") }.getOrNull()
                    ?: return@Hooker chain.proceed()
                val oldItems = runCatching { Reflect.call(receiver, "getCurrentList") as? List<*> }
                    .getOrNull().orEmpty()
                val newItems = chain.args.getOrNull(0) as? List<*> ?: return@Hooker chain.proceed()
                val pager = runCatching { Reflect.field(sectionInstance, "s") }.getOrNull()
                    ?: return@Hooker chain.proceed()
                ensurePagerTouchTracking(pager)
                val selectedIndex = runCatching { Reflect.call(pager, "getCurrentItem") as? Int }
                    .getOrNull() ?: return@Hooker chain.proceed()
                val pendingKey = pendingSelection[sectionInstance]
                val selectedKey = pendingKey ?: oldItems.getOrNull(selectedIndex)?.let { mediaKey(stableKey, it) }
                    ?: return@Hooker chain.proceed()
                val oldKeys = oldItems.filterNotNull().mapNotNull { mediaKey(stableKey, it) }
                val newKeys = newItems.filterNotNull().mapNotNull { mediaKey(stableKey, it) }
                val keyOrderChanged = oldKeys != newKeys
                val selectionRetained = selectedKey in newKeys
                if (keyOrderChanged && selectionRetained) {
                    pendingSelection[sectionInstance] = selectedKey
                    if (HookRuntime.isLogsEnabled()) {
                        HookRuntime.info(
                            "Media carousel list changed: current=$selectedIndex/${oldItems.size}, " +
                                "selected=${mediaKeyForLog(selectedKey)}, " +
                                "old=[${oldKeys.joinToString(",") { mediaKeyForLog(it) }}], " +
                                "new=[${newKeys.joinToString(",") { mediaKeyForLog(it) }}]"
                        )
                    }
                } else if (pendingKey != null && !selectionRetained) {
                    pendingSelection.remove(sectionInstance)
                    if (HookRuntime.isLogsEnabled()) {
                        HookRuntime.info("Media carousel pending selection dropped: selected media was removed")
                    }
                } else if (HookRuntime.isLogsEnabled() && oldItems.size > 1) {
                    HookRuntime.info(
                        "Media carousel list updated without insertion: current=$selectedIndex, " +
                            "oldCount=${oldItems.size}, newCount=${newItems.size}, " +
                                "selectedRetained=${selectedKey in newKeys}"
                    )
                }
                val result = chain.proceed()
                if ((keyOrderChanged || pendingKey != null) && selectionRetained) {
                    // The adapter diff commits asynchronously, and some updates do not call the
                    // section reset method. Restore by media key after every retained reorder.
                    postSelectionRestore(sectionInstance, pager, receiver, selectedKey, stableKey, 0)
                }
                result
            })

            val reset = section.getDeclaredMethod("r", section, Int::class.javaPrimitiveType!!)
            HookRuntime.deoptimize(reset)
            HookRuntime.hook("plugin:preserve-selected-media-page", reset, XposedInterface.Hooker { chain ->
                val instance = chain.args.getOrNull(0) ?: return@Hooker chain.proceed()
                val requested = chain.args.getOrNull(1) as? Int ?: return@Hooker chain.proceed()
                val selectedKey = pendingSelection[instance] ?: return@Hooker chain.proceed()
                if (requested != 0) return@Hooker chain.proceed()
                val pager = runCatching { Reflect.field(instance, "s") }.getOrNull()
                    ?: return@Hooker chain.proceed()
                ensurePagerTouchTracking(pager)
                val adapter = runCatching { Reflect.field(instance, "t") }.getOrNull()
                    ?: return@Hooker chain.proceed()
                val currentIndex = runCatching { Reflect.call(pager, "getCurrentItem") as? Int }
                    .getOrNull() ?: 0
                val items = runCatching { Reflect.call(adapter, "getCurrentList") as? List<*> }
                    .getOrNull().orEmpty()
                val targetIndex = items.indexOfFirst { item ->
                    item != null && mediaKey(stableKey, item) == selectedKey
                }
                val effectiveIndex = when {
                    targetIndex >= 0 -> targetIndex
                    currentIndex > 0 -> currentIndex
                    else -> requested
                }
                val args = chain.args.toTypedArray()
                args[1] = effectiveIndex
                if (effectiveIndex != currentIndex) markProgrammaticPageChange(instance, pager)
                val result = chain.proceed(args)
                if (HookRuntime.isLogsEnabled()) {
                    HookRuntime.info(
                        "Media carousel reset intercepted: requested=$requested, effective=$effectiveIndex, " +
                            "current=$currentIndex, target=$targetIndex, selected=${mediaKeyForLog(selectedKey)}"
                    )
                }
                // The ListAdapter diff and OEM active-player sorting can take longer than one
                // frame; keep the stable key until the list settles or the user swipes.
                postSelectionRestore(instance, pager, adapter, selectedKey, stableKey, 0)
                result
            })

            val pageCallback = Reflect.findClass("com.oplus.systemui.plugins.r8.Y6", loader)
            val pageSelected = pageCallback.getDeclaredMethod("a", Int::class.javaPrimitiveType!!)
            HookRuntime.deoptimize(pageSelected)
            HookRuntime.hook("plugin:clear-pending-selection-on-user-swipe", pageSelected,
                XposedInterface.Hooker { chain ->
                    val callbackObject = chain.thisObject ?: return@Hooker chain.proceed()
                    val sectionInstance = runCatching { Reflect.field(callbackObject, "b") }.getOrNull()
                    val isMediaPager = runCatching { Reflect.intField(callbackObject, "a") == 1 }
                        .getOrDefault(false)
                    if (isMediaPager && sectionInstance != null) {
                        if (programmaticPageChange.remove(sectionInstance) == null) {
                            val pager = runCatching { Reflect.field(sectionInstance, "s") }.getOrNull()
                            val touchedAt = pager?.let { lastPagerTouch[it] } ?: 0L
                            val userGesture = android.os.SystemClock.uptimeMillis() - touchedAt < 1000L
                            if (!userGesture) return@Hooker chain.proceed()
                            val abandoned = pendingSelection.remove(sectionInstance)
                            if (abandoned != null && HookRuntime.isLogsEnabled()) {
                                val selectedPage = chain.args.getOrNull(0) as? Int
                                HookRuntime.info(
                                    "Media carousel selection changed before restore: page=$selectedPage, " +
                                        "pending=${mediaKeyForLog(abandoned)}; keeping user choice"
                                )
                            }
                        }
                    }
                    chain.proceed()
                })
            HookRuntime.installInfo("Multi-player selection preservation hook installed")
        }.onFailure { HookRuntime.installWarn("Multi-player selection preservation unavailable", it) }
    }

    /** Track touches only on the OEM media section's pager, preserving its current listener. */
    private fun ensurePagerTouchTracking(pager: Any) {
        val view = pager as? View ?: return
        val state = synchronized(pagerTouchStates) {
            pagerTouchStates[view]?.get() ?: PagerTouchState(WeakReference(view)).also {
                pagerTouchStates[view] = WeakReference(it)
            }
        }
        val current = runCatching {
            val listenerInfo = Reflect.field(view, "mListenerInfo") ?: return
            Reflect.field(listenerInfo, "mOnTouchListener") as? View.OnTouchListener
        }.getOrElse {
            HookRuntime.runtimeWarn(it) { "Cannot inspect media carousel touch listener" }
            return
        }
        val wrapper = state.wrapper ?: synchronized(state) {
            state.wrapper ?: View.OnTouchListener { touchedView, event ->
                if (event.actionMasked != android.view.MotionEvent.ACTION_CANCEL) {
                    state.view.get()?.let { lastPagerTouch[it] = android.os.SystemClock.uptimeMillis() }
                }
                state.nativeListener?.onTouch(touchedView, event) ?: false
            }.also { state.wrapper = it }
        }
        if (current !== wrapper) {
            state.nativeListener = current
            view.setOnTouchListener(wrapper)
        }
    }

    private fun postSelectionRestore(
        section: Any,
        pager: Any,
        adapter: Any,
        selectedKey: String,
        stableKey: java.lang.reflect.Method,
        attempt: Int,
    ) {
        if (!HookRuntime.settingsSnapshot().master) return
        val view = pager as? View ?: return
        lateinit var callback: Runnable
        callback = Runnable {
            val isCurrent = synchronized(pendingRestoreCallbacks) {
                if (pendingRestoreCallbacks[section]?.callback === callback) {
                    pendingRestoreCallbacks.remove(section)
                    true
                } else {
                    false
                }
            }
            if (!isCurrent || pendingSelection[section] != selectedKey) return@Runnable
            if (!HookRuntime.settingsSnapshot().master) {
                pendingSelection.remove(section)
                return@Runnable
            }
            val items = runCatching { Reflect.call(adapter, "getCurrentList") as? List<*> }
                .getOrNull().orEmpty()
            val index = items.indexOfFirst { item ->
                item != null && mediaKey(stableKey, item) == selectedKey
            }
            if (index >= 0) {
                val current = runCatching { Reflect.call(pager, "getCurrentItem") as? Int }.getOrNull()
                if (current != index) {
                    markProgrammaticPageChange(section, pager)
                    runCatching { Reflect.call(pager, "setCurrentItem", index, false) }
                    if (HookRuntime.isLogsEnabled()) {
                        HookRuntime.info(
                            "Media carousel selection restored after list diff: from=$current, to=$index, " +
                                "selected=${mediaKeyForLog(selectedKey)}"
                        )
                    }
                }
                if (attempt < 100) {
                    postSelectionRestore(section, pager, adapter, selectedKey, stableKey, attempt + 1)
                } else {
                    pendingSelection.remove(section)
                }
            } else if (attempt < 100) {
                postSelectionRestore(section, pager, adapter, selectedKey, stableKey, attempt + 1)
            } else {
                pendingSelection.remove(section)
                if (HookRuntime.isLogsEnabled()) {
                    HookRuntime.info("Media carousel selection not restored: selected item was removed from list")
                }
            }
        }
        synchronized(pendingRestoreCallbacks) {
            pendingRestoreCallbacks.remove(section)?.let { pending ->
                pending.view.get()?.removeCallbacks(pending.callback)
            }
            pendingRestoreCallbacks[section] = PendingRestore(WeakReference(view), callback)
        }
        view.postDelayed(callback, 16L)
    }

    fun clearPendingRestoresForHotReload() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearPendingRestores()
        } else {
            mainHandler.post(::clearPendingRestores)
        }
    }

    private fun clearPendingRestores() {
        synchronized(pendingRestoreCallbacks) {
            pendingRestoreCallbacks.values.forEach { pending ->
                pending.view.get()?.removeCallbacks(pending.callback)
            }
            pendingRestoreCallbacks.clear()
        }
        pendingSelection.clear()
        programmaticPageChange.clear()
        lastPagerTouch.clear()

        val trackedPagers = synchronized(pagerTouchStates) {
            pagerTouchStates.entries.mapNotNull { (view, reference) ->
                reference.get()?.let { state -> view to state }
            }
        }
        trackedPagers.forEach { (view, state) ->
            val wrapper = state.wrapper ?: return@forEach
            val current = runCatching {
                val listenerInfo = Reflect.field(view, "mListenerInfo") ?: return@runCatching null
                Reflect.field(listenerInfo, "mOnTouchListener") as? View.OnTouchListener
            }.getOrNull()
            if (current === wrapper) view.setOnTouchListener(state.nativeListener)
        }
    }

    private fun markProgrammaticPageChange(section: Any, pager: Any) {
        programmaticPageChange[section] = true
        (pager as? View)?.postDelayed({ programmaticPageChange.remove(section) }, 500L)
    }

    private fun mediaKey(stableKey: java.lang.reflect.Method, media: Any): String? =
        runCatching { stableKey.invoke(null, media) as? String }.getOrNull()
}

/** Weak identity set for models passed between the parser and expanded-card wrapper. */
private class WeakIdentitySet {
    private val collected = ReferenceQueue<Any>()
    private val values = HashMap<IdentityReference, Unit>()

    @Synchronized
    fun add(value: Any) {
        purgeCollected()
        values.putIfAbsent(IdentityReference(value, collected), Unit)
    }

    @Synchronized
    fun contains(value: Any): Boolean {
        purgeCollected()
        return values.containsKey(IdentityReference(value))
    }

    private fun purgeCollected() {
        while (true) {
            val reference = collected.poll() as? IdentityReference ?: return
            values.remove(reference)
        }
    }

    private class IdentityReference(
        value: Any,
        queue: ReferenceQueue<Any>? = null,
    ) : WeakReference<Any>(value, queue) {
        private val identityHash = System.identityHashCode(value)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityReference || identityHash != other.identityHash) return false
            val value = get()
            return value != null && value === other.get()
        }
    }
}
