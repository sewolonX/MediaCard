package module.sewolon.mediacard

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.service.notification.StatusBarNotification
import android.view.View
import io.github.libxposed.api.XposedInterface
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.HashMap
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Keeps the existing expanded music card in the notification center on the plugin's card page. */
internal object MusicPluginHooks {
    private const val PARSER = "com.oplus.systemui.plugins.shared.data.parse.r"
    private const val MODEL = "com.oplus.systemui.plugins.shared.data.model.f"
    private const val WRAPPER = "com.oplus.systemui.plugins.shared.template.wrapper.impl.h"
    private const val ALBUM_ART_RESOURCE_NAME = "com.oplus.systemui.plugins:id/img_album_art"
    private val parsedMusicModels = WeakIdentitySet()
    private val albumArtViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val albumArtResourceMatches = Collections.synchronizedMap(WeakHashMap<android.content.res.Resources, HashMap<Int, Boolean>>())
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
        installAlbumArtAttachTarget(loader)
        installAlbumArtPerformClickRouting(loader)
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
                        albumArtViews[view] = true
                        // In both supplied lock-screen hierarchies this ImageView is not clickable.
                        // Make the exact art view the touch target so its click cannot fall through
                        // to the outer card's app-launch listener.
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

    /** Intercept the click on that exact image View, rather than inferring it from a lambda index. */
    private fun installAlbumArtPerformClickRouting(loader: ClassLoader) {
        runCatching {
            val performClick = View::class.java.getDeclaredMethod("performClick")
            HookRuntime.deoptimize(performClick)
            HookRuntime.hook("plugin:route-notification-music-cover-click:${System.identityHashCode(loader)}", performClick,
                XposedInterface.Hooker { chain ->
                    val view = chain.thisObject as? View ?: return@Hooker chain.proceed()
                    // The plugin can create T0 before our constructor hook is installed (or reuse
                    // an existing section), so identity registration alone misses the real cover.
                    // Match the exact album-art ImageView as well; do not intercept the card body.
                    if (!isAlbumArtView(view) && albumArtViews[view] != true) {
                        return@Hooker chain.proceed()
                    }
                    if (routeAlbumArtClick(view)) true else chain.proceed()
                })
            HookRuntime.installInfo("Notification Center media cover click hook installed")
        }.onFailure { HookRuntime.installWarn("Media cover click hook unavailable", it) }
    }

    /** Catch an already-created or reused plugin image when it joins the view tree. */
    private fun installAlbumArtAttachTarget(loader: ClassLoader) {
        runCatching {
            val attached = View::class.java.getDeclaredMethod("onAttachedToWindow")
            HookRuntime.hook(
                "plugin:track-album-art-attach:${System.identityHashCode(loader)}",
                attached,
                XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View ?: return@Hooker result
                    if (isAlbumArtView(view)) {
                        albumArtViews[view] = true
                        if (!view.isClickable) view.isClickable = true
                        if (HookRuntime.isLogsEnabled()) {
                            HookRuntime.info(
                                "Attached OEM img_album_art made clickable: class=${view.javaClass.name}, " +
                                    "viewId=0x${view.id.toString(16)}"
                            )
                        }
                    }
                    result
                },
            )
            HookRuntime.installInfo("OEM img_album_art attach hook installed")
        }.onFailure { HookRuntime.installWarn("OEM img_album_art attach hook unavailable", it) }
    }

    /** Route clicks from the exact album-art image according to the active SystemUI surface. */
    private fun routeAlbumArtClick(view: View): Boolean {
        val keyguardLocked = runCatching {
            view.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(true)
        val notificationPanelExpanded = MusicMediaRowHook.isNotificationCenterForeground()
        val notificationCenterSurface = notificationPanelExpanded && !keyguardLocked
        // This cache tracks OPlus Seedling/Live Alert sources only. Standard Android media
        // sessions from other apps are owned by MediaPlayerData and must still reach the
        // native Control Center panel route.
        val seedlingMedia = MusicSeedlingPlayback.hasEligibleMediaSession()
        if (HookRuntime.isLogsEnabled()) {
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
                if (opened) HookRuntime.info("SystemUI album-art click routed directly to native PhoneStyle media dialog")
                else HookRuntime.warn("SystemUI album-art click matched; native PhoneStyle media dialog was unavailable")
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
        val resources = view.resources
        return synchronized(albumArtResourceMatches) {
            val matches = albumArtResourceMatches.getOrPut(resources) { HashMap() }
            matches[id] ?: runCatching {
                resources.getResourceName(id) == ALBUM_ART_RESOURCE_NAME
            }.getOrDefault(false).also { matches[id] = it }
        }
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
    private val pendingSelection = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val programmaticPageChange = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val lastPagerTouch = Collections.synchronizedMap(WeakHashMap<Any, Long>())

    fun install(loader: ClassLoader) {
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
                                "selected=${diagnosticKey(selectedKey)}, " +
                                "old=[${oldKeys.joinToString(",") { diagnosticKey(it) }}], " +
                                "new=[${newKeys.joinToString(",") { diagnosticKey(it) }}]"
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
                            "oldCount=${oldItems.size}, newCount=${newItems.size}, selectedRetained=${selectedKey in newKeys}"
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
                            "current=$currentIndex, target=$targetIndex, selected=${diagnosticKey(selectedKey)}"
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
            val pagerType = Reflect.findClass("androidx.viewpager2.widget.ViewPager2", loader)
            val dispatchTouch = pagerType.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent::class.java)
            HookRuntime.hook("plugin:track-media-carousel-touch", dispatchTouch, XposedInterface.Hooker { chain ->
                val pager = chain.thisObject
                val event = chain.args.getOrNull(0) as? android.view.MotionEvent
                if (pager != null && event != null && event.actionMasked != android.view.MotionEvent.ACTION_CANCEL) {
                    lastPagerTouch[pager] = android.os.SystemClock.uptimeMillis()
                }
                chain.proceed()
            })
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
                                        "pending=${diagnosticKey(abandoned)}; keeping user choice"
                                )
                            }
                        }
                    }
                    chain.proceed()
                })
            HookRuntime.installInfo("Multi-player selection preservation hook installed")
        }.onFailure { HookRuntime.installWarn("Multi-player selection preservation unavailable", it) }
    }

    private fun postSelectionRestore(
        section: Any,
        pager: Any,
        adapter: Any,
        selectedKey: String,
        stableKey: java.lang.reflect.Method,
        attempt: Int,
    ) {
        val view = pager as? View ?: return
        view.postDelayed({
            if (pendingSelection[section] != selectedKey) return@postDelayed
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
                                "selected=${diagnosticKey(selectedKey)}"
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
        }, 16L)
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
