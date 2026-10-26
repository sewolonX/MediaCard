package module.sewolon.mediacard

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import android.view.View
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal object NotificationCenterMediaHooks {
    private const val SERVICE_ENTRY = "com.oplus.fluidseeding.data.ServiceEntry"
    private val mediaEntrances = setOf(MediaCardModule.NOTIFICATION_CENTER_HOST)

    fun install(loader: ClassLoader) {
        runCatching {
            val entry = Reflect.findClass(SERVICE_ENTRY, loader)
            val serviceIdField = entry.getDeclaredField("mServiceId").apply { isAccessible = true }

            val entranceList = entry.getDeclaredMethod("getShouldShowEntrance", Context::class.java)
            HookRuntime.hook("nc:music-entrances", entranceList, XposedInterface.Hooker { chain ->
                val receiver = chain.thisObject ?: return@Hooker chain.proceed()
                if (!isMusicService(serviceIdField.get(receiver) as? String)) return@Hooker chain.proceed()
                val raw = chain.proceed()
                val result = raw as? ArrayList<*> ?: return@Hooker raw
                @Suppress("UNCHECKED_CAST")
                val mutable = result as ArrayList<Any?>
                mediaEntrances.forEach { entrance -> if (entrance !in mutable) mutable.add(entrance) }
                mutable
            })

            val support = entry.getDeclaredMethod("getSupportEnable", Int::class.javaPrimitiveType!!)
            HookRuntime.hook("nc:music-entrance-support", support, XposedInterface.Hooker { chain ->
                val supported = chain.proceed() as? Boolean ?: false
                val receiver = chain.thisObject ?: return@Hooker supported
                val entrance = chain.args.getOrNull(0) as? Int ?: return@Hooker supported
                if (isMusicService(serviceIdField.get(receiver) as? String) && entrance in mediaEntrances) {
                    true
                } else {
                    supported
                }
            })

            installMusicCloseEntry(entry, serviceIdField)
            HookRuntime.installInfo("Music-only Notification Center hooks installed")
        }.onFailure { HookRuntime.installWarn("Notification Center music hooks unavailable", it) }
    }

    private fun installMusicCloseEntry(entry: Class<*>, serviceIdField: java.lang.reflect.Field) {
        runCatching {
            val normalize = entry.getDeclaredMethod("enableMusicServiceNotification", Int::class.javaPrimitiveType!!)
            HookRuntime.hook("nc:music-close-entry", normalize, XposedInterface.Hooker { chain ->
                val rawResult = chain.proceed()
                val result = rawResult as? Int ?: return@Hooker rawResult
                val current = chain.args.getOrNull(0) as? Int ?: return@Hooker result
                (result and MediaCardModule.NOTIFICATION_CENTER_HOST.inv()) or
                    (current and MediaCardModule.NOTIFICATION_CENTER_HOST)
            })
        }.onFailure { HookRuntime.installWarn("Music close-entry normalization unavailable", it) }

        runCatching {
            val refresh = entry.getDeclaredMethod("refreshClosedEntry", Int::class.javaPrimitiveType!!)
            HookRuntime.hook("nc:music-refresh-close-entry", refresh, XposedInterface.Hooker { chain ->
                val requested = chain.args.getOrNull(0) as? Int ?: return@Hooker chain.proceed()
                val receiver = chain.thisObject ?: return@Hooker chain.proceed()
                if (!isMusicService(serviceIdField.get(receiver) as? String)) return@Hooker chain.proceed()
                val current = runCatching { Reflect.call(receiver, "getClosedEntry") as? Int }.getOrNull()
                    ?: return@Hooker chain.proceed()
                val restored = (requested and MediaCardModule.NOTIFICATION_CENTER_HOST.inv()) or
                    (current and MediaCardModule.NOTIFICATION_CENTER_HOST)
                chain.proceed(arrayOf(restored))
            })
        }.onFailure { HookRuntime.installWarn("Music close-entry refresh unavailable", it) }
    }

}

internal object MusicLiveAlertHostHook {
    private const val OPTIONS = "oplusLiveAlertOptions"
    private val lastState = AtomicReference<String>()

    fun install(loader: ClassLoader) {
        runCatching {
            val controller = Reflect.findClass(
                "com.oplus.systemui.media.seedling.livealert.MediaLiveAlertController", loader,
            )
            val send = controller.getDeclaredMethod(
                "sendDataToPlugin", String::class.java, StatusBarNotification::class.java,
            )
            HookRuntime.hook("systemui:music-live-alert-host", send, XposedInterface.Hooker { chain ->
                if (chain.args.getOrNull(0) != "sendLiveAlert") return@Hooker chain.proceed()
                val sbn = chain.args.getOrNull(1) as? StatusBarNotification ?: return@Hooker chain.proceed()
                if (
                    sbn.packageName != MediaCardModule.SYSTEM_UI ||
                    sbn.id.toString() != MediaCardModule.MUSIC_SERVICE_ID
                ) {
                    return@Hooker chain.proceed()
                }

                // The OEM already chose the Live Alert hosts. This cache only reflects media
                // sessions seen by the Seedling pipeline; it can be empty while the OEM payload
                // still targets Notification Center. Do not add or remove host 16 from that data.
                val result = chain.proceed()
                if (HookRuntime.isLogsEnabled()) {
                    val sourceOptions = runCatching {
                        sbn.notification.extras.getString(OPTIONS)?.let(::JSONObject)
                    }.getOrNull()
                    val seedlingEligible = MusicSeedlingPlayback.hasEligibleMediaSession()
                    val nativeHosts = sourceOptions?.optJSONArray("showHostMap")?.let { hosts ->
                        (0 until hosts.length()).mapNotNull { index ->
                            hosts.optInt(index, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }
                        }
                    }
                    // Log routing metadata only; never include notification extras or user content.
                    val state = "route=direct-live-alert, seedlingEligible=$seedlingEligible, " +
                        "hostMapChanged=false, nativeHosts=${nativeHosts ?: "unavailable"}, " +
                        "providerAccepted=${result == true}"
                    if (lastState.getAndSet(state) != state) HookRuntime.info(state)
                }
                result
            })
        }.onFailure { HookRuntime.installWarn("SystemUI music host hook unavailable", it) }
    }

}

/** The UMS path sends a Bundle instead of the direct Live Alert StatusBarNotification. */
internal object MusicSeedlingServiceHook {
    private const val SERVICE = "com.oplus.systemui.statusbar.seeding.StartSeedlingService"
    private const val MEDIA_EVENT = "MediaEvent"
    private const val MEDIA_LIST = "mediaList"
    private const val HIDDEN_FLAG = "shouldNotificationSeedlingCardHidden"
    private val lastState = AtomicReference<String>()

    fun install(loader: ClassLoader) {
        runCatching {
            val service = Reflect.findClass(SERVICE, loader)
            val update = service.getDeclaredMethod(
                "updateData", Context::class.java, String::class.java, Bundle::class.java,
            )
            HookRuntime.hook("systemui:music-seedling-service", update, XposedInterface.Hooker { chain ->
                if (chain.args.getOrNull(1) != MEDIA_EVENT) return@Hooker chain.proceed()
                val bundle = chain.args.getOrNull(2) as? Bundle ?: return@Hooker chain.proceed()
                val media = runCatching {
                    bundle.getParcelableArrayList<Parcelable>(MEDIA_LIST)
                }.getOrNull().orEmpty()
                // The payload is already available on this event path. Read its current states
                // directly so a stale power-saving cache cannot suppress a required card update.
                val eligibleInPayload = media.any(MusicSeedlingPlayback::isCardEligible)
                val eligible = eligibleInPayload
                if (!eligible) {
                    bundle.putBoolean(HIDDEN_FLAG, true)
                    if (HookRuntime.isLogsEnabled()) {
                        val state = "UMS media update: entries=${media.size}, noEligibleSession=true, cardHidden=true"
                        if (lastState.getAndSet(state) != state) HookRuntime.info(state)
                    }
                    return@Hooker chain.proceed()
                }

                val wasHidden = bundle.getBoolean(HIDDEN_FLAG, false)
                if (wasHidden) bundle.putBoolean(HIDDEN_FLAG, false)
                if (HookRuntime.isLogsEnabled()) {
                    val state = "UMS media update: entries=${media.size}, " +
                        "notificationCenterWasHidden=$wasHidden, eligibleSession=$eligibleInPayload"
                    if (lastState.getAndSet(state) != state) HookRuntime.info(state)
                }
                chain.proceed()
            })
            HookRuntime.installInfo("Music Notification Center UMS payload hook installed")
        }.onFailure { HookRuntime.installWarn("Music UMS payload hook unavailable", it) }
    }
}

/** Resolves the OEM media playback state predicate once for the SystemUI class loader. */
internal object MusicSeedlingPlayback {
    private const val MEDIA_DATA = "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData"
    @Volatile private var companion: Any? = null
    @Volatile private var isPlayState: java.lang.reflect.Method? = null
    @Volatile private var mediaSeedling: Any? = null
    @Volatile private var mediaCombineLatest: Any? = null
    private val eligibleSessionKeys = ConcurrentHashMap.newKeySet<String>()
    private val playbackStates = ConcurrentHashMap<String, Int>()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val settingsListener: (HookFeatureConfig.Settings) -> Unit = { settings ->
        if (!settings.master) cancelPendingRefresh()
    }
    private val refreshLock = Any()
    private var pendingRefresh: Runnable? = null
    private var pendingRefreshToken: Any? = null
    private var lastRefreshAt = 0L

    fun install(loader: ClassLoader) {
        HookRuntime.addSettingsListener(settingsListener)
        runCatching {
            val type = Reflect.findClass(MEDIA_DATA, loader)
            val instance = type.getField("Companion").get(null)
            companion = instance
            isPlayState = instance.javaClass.getDeclaredMethod("isPlayState", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.onFailure { HookRuntime.installWarn("Seedling media state helper unavailable", it) }
    }

    /** Keep playing, paused, and buffering sessions; exclude NONE, STOPPED, ERROR, and connecting. */
    fun mediaState(media: Any): Int? = runCatching {
        Reflect.call(media, "getState") as? Int
    }.getOrNull()

    fun isCardEligible(media: Any): Boolean = mediaState(media)?.let(::isCardEligibleState) == true

    fun isCardEligibleState(state: Int): Boolean = runCatching {
        state == 2 || state == 6 || isPlayState?.invoke(companion, state) as? Boolean == true
    }.getOrDefault(false)

    /** Returns true only when the cache transitions from no eligible media to at least one. */
    fun onMediaDataLoaded(key: String, state: Int?, eligible: Boolean): Boolean {
        if (state == null) {
            eligibleSessionKeys.remove(key)
            playbackStates.remove(key)
            MusicMediaRowHook.onMediaSessionStateChanged(key, eligible = false)
            return false
        }
        playbackStates[key] = state
        if (!eligible) {
            eligibleSessionKeys.remove(key)
            MusicMediaRowHook.onMediaSessionStateChanged(key, eligible = false)
            return false
        }
        MusicMediaRowHook.onMediaSessionStateChanged(key, eligible = true)
        val hadEligibleMedia = eligibleSessionKeys.isNotEmpty()
        val newlyEligible = eligibleSessionKeys.add(key)
        return newlyEligible && !hadEligibleMedia
    }

    fun captureMediaSeedlingInstance(seedling: Any) {
        mediaSeedling = seedling
    }

    fun captureMediaCombineInstance(combineLatest: Any) {
        mediaCombineLatest = combineLatest
        runCatching { Reflect.field(combineLatest, "mediaSeedling") }
            .getOrNull()?.let(::captureMediaSeedlingInstance)
    }

    fun onMediaDataRemoved(key: String) {
        eligibleSessionKeys.remove(key)
        playbackStates.remove(key)
        MusicMediaRowHook.onMediaSessionRemoved(key)
    }

    fun hasEligibleMediaSession(): Boolean = eligibleSessionKeys.isNotEmpty()
    fun eligibleMediaCount(): Int = eligibleSessionKeys.size
    fun hasSeedlingInstance(): Boolean = mediaSeedling != null

    fun shouldRetainCard(): Boolean = hasEligibleMediaSession()

    /** Reconcile the small OEM media-entry map only when the notification panel opens. */
    fun reconcileForNotificationCenter(): Boolean {
        val combineLatest = mediaCombineLatest
        val entries = combineLatest?.let { instance -> runCatching {
            Reflect.field(instance, "entries") as? Map<*, *>
        }.getOrNull() }
        if (entries != null) {
            val currentStates = HashMap<String, Int>(entries.size)
            val currentEligibleKeys = HashSet<String>()
            entries.forEach { (rawKey, media) ->
                val key = rawKey as? String ?: return@forEach
                val state = media?.let(::mediaState) ?: return@forEach
                currentStates[key] = state
                if (isCardEligibleState(state)) currentEligibleKeys.add(key)
            }
            (playbackStates.keys - currentStates.keys).forEach(MusicMediaRowHook::onMediaSessionRemoved)
            currentStates.forEach { (key, state) ->
                MusicMediaRowHook.onMediaSessionStateChanged(key, key in currentEligibleKeys)
            }
            playbackStates.clear()
            playbackStates.putAll(currentStates)
            eligibleSessionKeys.clear()
            eligibleSessionKeys.addAll(currentEligibleKeys)
            if (HookRuntime.isLogsEnabled()) {
                val stateCounts = currentStates.values.groupingBy { it }.eachCount().toSortedMap()
                HookRuntime.info(
                    "Shade media snapshot: entries=${currentStates.size}, eligible=${currentEligibleKeys.size}, " +
                        "states=$stateCounts, seedling=${mediaSeedling != null}"
                )
            }
        } else {
            playbackStates.clear()
            eligibleSessionKeys.clear()
            if (HookRuntime.isLogsEnabled()) {
                HookRuntime.info(
                    "Shade media snapshot unavailable: combine=${combineLatest != null}, " +
                        "seedling=${mediaSeedling != null}; cacheCleared=true"
                )
            }
        }
        return hasEligibleMediaSession()
    }

    /** Re-run the OEM media update so provider and Notification Center rebuild the live card. */
    fun scheduleActiveCardRefresh(reason: String, delayMillis: Long = 100L) {
        synchronized(refreshLock) {
            if (!HookRuntime.settingsSnapshot().master) {
                cancelPendingRefreshLocked()
                return
            }
            pendingRefresh?.let(refreshHandler::removeCallbacks)
            val token = Any()
            val refresh = Runnable {
                val isCurrent = synchronized(refreshLock) {
                    if (pendingRefreshToken !== token) {
                        false
                    } else {
                        pendingRefresh = null
                        pendingRefreshToken = null
                        lastRefreshAt = android.os.SystemClock.uptimeMillis()
                        true
                    }
                }
                if (!isCurrent || !HookRuntime.settingsSnapshot().master || !hasEligibleMediaSession()) {
                    return@Runnable
                }
                val seedling = mediaSeedling ?: run {
                    HookRuntime.runtimeWarn { "Cannot refresh active music card: MediaSeedling instance unavailable" }
                    return@Runnable
                }
                MusicMediaCreationHook.enableCardCreation(seedling)
                runCatching { Reflect.call(seedling, "postMediaDataUpdate", 1, reason) }
                    .onSuccess { HookRuntime.runtimeInfo { "Requested native active music card refresh: reason=$reason" } }
                    .onFailure { HookRuntime.runtimeWarn(it) { "Cannot request native active music card refresh" } }
            }
            val now = android.os.SystemClock.uptimeMillis()
            val cooldown = (500L - (now - lastRefreshAt)).coerceAtLeast(0L)
            pendingRefreshToken = token
            pendingRefresh = refresh
            refreshHandler.postDelayed(refresh, maxOf(delayMillis, cooldown))
        }
    }

    /** Immediate panel-open refresh; bypasses the background cooldown and session-cache gate. */
    fun refreshActiveCardImmediately(reason: String) {
        if (!HookRuntime.settingsSnapshot().master) return
        synchronized(refreshLock) {
            cancelPendingRefreshLocked()
            lastRefreshAt = android.os.SystemClock.uptimeMillis()
        }
        val seedling = mediaSeedling ?: run {
            HookRuntime.runtimeWarn { "Cannot refresh active music card: MediaSeedling instance unavailable" }
            return
        }
        MusicMediaCreationHook.enableCardCreation(seedling)
        runCatching { Reflect.call(seedling, "postMediaDataUpdate", 1, reason) }
            .onSuccess {
                HookRuntime.runtimeInfo {
                    "Immediately requested native music refresh: reason=$reason, " +
                        "eligible=${eligibleSessionKeys.size}, allowCreate=${hasEligibleMediaSession()}"
                }
            }
            .onFailure { HookRuntime.runtimeWarn(it) { "Cannot immediately request native active music card refresh" } }
    }

    private fun cancelPendingRefresh() {
        synchronized(refreshLock) { cancelPendingRefreshLocked() }
    }

    private fun cancelPendingRefreshLocked() {
        pendingRefresh?.let(refreshHandler::removeCallbacks)
        pendingRefresh = null
        pendingRefreshToken = null
    }
}

/** Re-enables card creation when playback resumes after the source notification was removed. */
internal object MusicMediaCreationHook {
    private const val MEDIA_SEEDLING = "com.oplus.systemui.media.seedling.MediaSeedling"
    private const val MEDIA_DATA = "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData"
    private val allowCreateFields = ConcurrentHashMap<Class<*>, java.lang.reflect.Field>()

    fun enableCardCreation(seedling: Any) {
        runCatching {
            val field = allowCreateFields.computeIfAbsent(seedling.javaClass) { type ->
                type.getDeclaredField("allowCreateCard").apply { isAccessible = true }
            }
            field.setBoolean(seedling, true)
        }.onFailure { HookRuntime.runtimeWarn(it) { "Cannot enable card creation for native media refresh" } }
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val seedling = Reflect.findClass(MEDIA_SEEDLING, loader)
            val mediaData = Reflect.findClass(MEDIA_DATA, loader)
            val loaded = seedling.getDeclaredMethod(
                "onMediaDataLoaded", String::class.java, mediaData, Int::class.javaPrimitiveType,
            )
            HookRuntime.hook("systemui:music-reenable-card-creation", loaded, XposedInterface.Hooker { chain ->
                val key = chain.args.getOrNull(0) as? String
                val data = chain.args.getOrNull(1) ?: return@Hooker chain.proceed()
                val currentState = MusicSeedlingPlayback.mediaState(data)
                val currentMediaEligible = currentState?.let(MusicSeedlingPlayback::isCardEligibleState) == true
                val receiver = chain.thisObject ?: return@Hooker chain.proceed()
                MusicSeedlingPlayback.captureMediaSeedlingInstance(receiver)
                val becameEligible = key?.let {
                    MusicSeedlingPlayback.onMediaDataLoaded(it, currentState, currentMediaEligible)
                } ?: false
                val allowCreate = runCatching {
                    allowCreateFields.computeIfAbsent(receiver.javaClass) { type ->
                        type.getDeclaredField("allowCreateCard").apply { isAccessible = true }
                    }
                }.onFailure { HookRuntime.runtimeWarn(it) { "Cannot access media card creation flag" } }.getOrNull()
                if (!currentMediaEligible && !MusicSeedlingPlayback.shouldRetainCard()) {
                    if (HookRuntime.isLogsEnabled()) {
                        HookRuntime.info(
                            "Seedling media state: key=${key?.let(::mediaKeyForLog)}, state=$currentState, " +
                            "eligible=false, remaining=${MusicSeedlingPlayback.eligibleMediaCount()}, " +
                                "action=allow-stop"
                        )
                    }
                    val result = chain.proceed()
                    runCatching {
                        allowCreate?.setBoolean(receiver, false)
                    }.onFailure { HookRuntime.runtimeWarn(it) { "Cannot disable stopped music card creation" } }
                    return@Hooker result
                }
                fun allowCardCreation() = runCatching {
                    val field = allowCreate ?: return@runCatching
                    if (!field.getBoolean(receiver)) {
                        field.setBoolean(receiver, true)
                        HookRuntime.runtimeInfo { "Re-enabled media card creation for active music playback" }
                    }
                }.onFailure { HookRuntime.runtimeWarn(it) { "Cannot re-enable Meizu Music card creation" } }
                allowCardCreation()
                if (HookRuntime.isLogsEnabled()) {
                    HookRuntime.info(
                        "Seedling media state: key=${key?.let(::mediaKeyForLog)}, state=$currentState, " +
                            "eligible=true, becameEligible=$becameEligible, " +
                            "active=${MusicSeedlingPlayback.eligibleMediaCount()}, action=allow-create"
                    )
                }
                val result = chain.proceed()
                allowCardCreation()
                if (becameEligible) {
                    MusicSeedlingPlayback.scheduleActiveCardRefresh("media-session-became-eligible", 0L)
                }
                result
            })
            HookRuntime.installInfo("Meizu Music media card creation hook installed")
        }.onFailure { HookRuntime.installWarn("Meizu Music media card creation hook unavailable", it) }
    }
}

/** Let SystemUI's source-removal callback retire stale or stopped media sessions. */
internal object MusicMediaDataRemovalHook {
    private const val COMBINE_LATEST = "com.oplus.systemui.media.seedling.OplusSeedlingMediaDataCombineLatest"

    fun install(loader: ClassLoader) {
        runCatching {
            val combine = Reflect.findClass(COMBINE_LATEST, loader)
            val mediaSeedling = Reflect.findClass(
                "com.oplus.systemui.media.seedling.MediaSeedling", loader,
            )
            val constructor = combine.getDeclaredConstructor(
                Context::class.java, mediaSeedling, Handler::class.java,
            )
            HookRuntime.hook("systemui:capture-media-combine-constructor", constructor,
                XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let(MusicSeedlingPlayback::captureMediaCombineInstance)
                    result
                })
            val mediaData = Reflect.findClass(
                "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData", loader,
            )
            val loaded = combine.getDeclaredMethod(
                "onMediaDataLoaded", String::class.java, mediaData, Int::class.javaPrimitiveType,
            )
            HookRuntime.hook("systemui:capture-media-combine", loaded, XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                chain.thisObject?.let(MusicSeedlingPlayback::captureMediaCombineInstance)
                result
            })
            val removed = combine.getDeclaredMethod("onMediaDataRemoved", String::class.java)

            HookRuntime.hook("systemui:keep-playing-media-entry", removed, XposedInterface.Hooker { chain ->
                val key = chain.args.getOrNull(0) as? String ?: return@Hooker chain.proceed()
                val receiver = chain.thisObject ?: return@Hooker chain.proceed()
                MusicSeedlingPlayback.captureMediaCombineInstance(receiver)
                runCatching { Reflect.field(receiver, "mediaSeedling") }
                    .getOrNull()?.let(MusicSeedlingPlayback::captureMediaSeedlingInstance)
                MusicSeedlingPlayback.onMediaDataRemoved(key)
                if (HookRuntime.isLogsEnabled()) {
                    val keyedMedia = runCatching {
                        (Reflect.field(receiver, "entries") as? Map<*, *>)?.get(key)
                    }.getOrNull()
                    HookRuntime.info(
                        "Forwarded native media-source removal: key=${mediaKeyForLog(key)}, " +
                            "entryFound=${keyedMedia != null}, remaining=${MusicSeedlingPlayback.eligibleMediaCount()}"
                    )
                }
                chain.proceed()
            })
            HookRuntime.installInfo("Active media removal guard installed")
        }.onFailure { HookRuntime.installWarn("Active media removal guard unavailable", it) }
    }
}

internal object MusicMediaRowHook {
    private const val MAX_RETIRED_MEDIA_KEYS = 64
    @Volatile private var customRowManager: Any? = null
    @Volatile private var cachedMusicEntry: Any? = null
    private val retiredMediaKeys = LinkedHashSet<String>()
    private val retiredMediaKeysLock = Any()
    private val lastRestoreState = AtomicReference<String>()
    private val revealLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var revealPending = false
    @Volatile private var notificationPanelForeground = false
    @Volatile private var panelRefreshRequested = false

    /** Retire only rows whose OEM entry key exactly matches an inactive Seedling media key. */
    fun onMediaSessionStateChanged(key: String, eligible: Boolean) {
        synchronized(retiredMediaKeysLock) {
            retiredMediaKeys.remove(key)
            if (!eligible) {
                retiredMediaKeys.add(key)
                while (retiredMediaKeys.size > MAX_RETIRED_MEDIA_KEYS) {
                    retiredMediaKeys.remove(retiredMediaKeys.first())
                }
            }
        }
        if (!eligible && musicEntryKey(cachedMusicEntry) == key) cachedMusicEntry = null
    }

    fun onMediaSessionRemoved(key: String) = onMediaSessionStateChanged(key, eligible = false)

    private fun isRetiredMusicEntry(entry: Any?): Boolean {
        val key = musicEntryKey(entry) ?: return false
        return synchronized(retiredMediaKeysLock) { key in retiredMediaKeys }
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val manager = Reflect.findClass(
                "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRowControllerManager", loader,
            )
            installControllerCapture(manager)
            val rowType = Reflect.findClass(MusicMediaRowClassifier.ROW_CLASS, loader)
            val companionType = Reflect.findClass(MusicMediaRowClassifier.COMPANION_CLASS, loader)
            val rowCompanion = requireNotNull(rowType.getField("Companion").get(null))
            val rowClassifier = companionType.getDeclaredMethod("isOplusMediaCustomRow", String::class.java)
            val setter = manager.getDeclaredMethod("setMediaControllerVisibility", Boolean::class.javaPrimitiveType)
            HookRuntime.hook("systemui:music-media-row-visibility", setter, XposedInterface.Hooker { chain ->
                val requested = chain.args.getOrNull(0) as? Boolean ?: return@Hooker chain.proceed()
                val receiver = chain.thisObject ?: return@Hooker chain.proceed()
                customRowManager = receiver
                if (!notificationPanelForeground) return@Hooker chain.proceed()
                val wasMusicRowVisible = hasVisibleMusicRow(receiver)
                val eligibleMusic = MusicSeedlingPlayback.hasEligibleMediaSession()
                val keepMusicVisible = eligibleMusic && hasMusicEntry(receiver)
                val visible = requested || keepMusicVisible
                if (keepMusicVisible && !requested) {
                    logRestoreState { "visibility:forced=true,eligible=true,controllerRow=${hasMusicRow(receiver)}" }
                }
                val result = if (visible == requested) chain.proceed() else chain.proceed(arrayOf(visible))
                // The OEM method can still set the row INVISIBLE while capsule mode is active,
                // even after its ViewState.gone flag is cleared. Re-apply VISIBLE after that
                // branch completes so the Notification Center host sees the music row.
                if (keepMusicVisible) revealActiveMusicRow(receiver, rowCompanion, rowClassifier)
                if (!wasMusicRowVisible && visible && hasVisibleMusicRow(receiver)) {
                    MediaCoverAppearanceHook.repairVisibleBackgroundMargins("music-row-became-visible")
                }
                result
            })
            installExposeActiveMusicController(manager, rowCompanion, rowClassifier)
            installRefreshOnNotificationShadeOpen(loader)
            installShadeWindowVisibilityReset(loader)
            installPreservePlayingEntry(loader, manager)
            installReAddAfterMediaLaunch(manager)
        }.onFailure { HookRuntime.installWarn("Music row visibility hook unavailable", it) }
    }

    private fun installControllerCapture(manager: Class<*>) {
        manager.declaredConstructors.forEachIndexed { index, constructor ->
            HookRuntime.hook("systemui:music-row-manager-instance:$index", constructor,
                XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { customRowManager = it }
                    result
                })
        }
        runCatching {
            val callback = manager.getDeclaredMethod(
                "setRequestImmersiveStateListener",
                Reflect.findClass(
                    "com.oplus.systemui.statusbar.notification.customcard." +
                        "OplusCustomRowControllerManager\$RequestImmersiveStateListener",
                    manager.classLoader,
                ),
            )
            HookRuntime.hook("systemui:music-row-manager-immersive-listener", callback,
                XposedInterface.Hooker { chain ->
                    chain.thisObject?.let { customRowManager = it }
                    chain.proceed()
                })
        }.onFailure { HookRuntime.installWarn("Music row manager callback capture unavailable", it) }
    }

    fun requestLockScreenImmersiveState(key: String): Boolean {
        val manager = customRowManager ?: run {
            HookRuntime.runtimeWarn { "Cannot request OEM lock-screen immersive expansion: row manager unavailable" }
            return false
        }
        val matchingEntry = runCatching {
            val controllers = Reflect.call(manager, "getOplusCustomRowControllerList") as? List<*>
                ?: return@runCatching false
            controllers.any { controller ->
                val entry = Reflect.call(requireNotNull(controller), "getEntry") ?: return@any false
                Reflect.call(entry, "getKey") == key &&
                    isMusicService(Reflect.call(entry, "getServiceId") as? String)
            }
        }.getOrDefault(false)
        if (!matchingEntry) {
            HookRuntime.runtimeWarn {
                "Cannot request OEM lock-screen immersive expansion: clicked music row key is not registered " +
                    "(key=${mediaKeyForLog(key)})"
            }
            return false
        }
        return runCatching {
            Reflect.call(manager, "requestImmersiveState", 1, 0x12f, key)
            MediaCoverAppearanceHook.repairVisibleBackgroundMargins("lock-screen-immersive-request")
            HookRuntime.runtimeInfo {
                "Dispatched OEM lock-screen immersive expansion for registered music row: " +
                    "key=${mediaKeyForLog(key)}"
            }
            true
        }.onFailure { HookRuntime.runtimeWarn(it) { "Cannot request OEM lock-screen immersive expansion" } }
            .getOrDefault(false)
    }

    /** Resolve the sole registered music row behind the lock-screen album-art card. */
    fun requestActiveLockScreenMusicRow(): Boolean {
        val manager = customRowManager ?: run {
            HookRuntime.runtimeWarn { "Cannot resolve lock-screen album-art target: row manager unavailable" }
            return false
        }
        val keys = LinkedHashSet<String>()
        runCatching {
            val controllers = Reflect.call(manager, "getOplusCustomRowControllerList") as? List<*>
            controllers.orEmpty().forEach { controller ->
                val entry = Reflect.call(requireNotNull(controller), "getEntry") ?: return@forEach
                val serviceId = Reflect.call(entry, "getServiceId") as? String
                val key = Reflect.call(entry, "getKey") as? String
                if (isMusicService(serviceId) && !key.isNullOrBlank()) keys.add(key)
            }
        }.onFailure { HookRuntime.runtimeWarn(it) { "Cannot inspect lock-screen music row controllers" } }
        runCatching {
            val entries = Reflect.field(manager, "customRowEntryList") as? List<*>
            entries.orEmpty().forEach { entry ->
                val serviceId = Reflect.call(requireNotNull(entry), "getServiceId") as? String
                val key = Reflect.call(entry, "getKey") as? String
                if (isMusicService(serviceId) && !key.isNullOrBlank()) keys.add(key)
            }
        }.onFailure { HookRuntime.runtimeWarn(it) { "Cannot inspect lock-screen music row entries" } }
        cachedMusicEntry?.let { entry ->
            runCatching {
                val serviceId = Reflect.call(entry, "getServiceId") as? String
                val key = Reflect.call(entry, "getKey") as? String
                if (isMusicService(serviceId) && !key.isNullOrBlank()) keys.add(key)
            }.onFailure { HookRuntime.runtimeWarn(it) { "Cannot inspect cached lock-screen music row" } }
        }
        if (keys.size != 1) {
            HookRuntime.runtimeWarn {
                "Cannot resolve lock-screen album-art target: registered music row count=${keys.size}"
            }
            return false
        }
        return requestLockScreenImmersiveState(keys.single())
    }

    /** StatusBarState.SHADE=0, KEYGUARD=1, SHADE_LOCKED=2. */
    fun currentStatusBarState(): Int? = runCatching {
        val manager = customRowManager ?: return null
        val stateController = Reflect.field(manager, "statusBarStateController") ?: return null
        Reflect.call(stateController, "getState") as? Int
    }.getOrNull()

    private fun installRefreshOnNotificationShadeOpen(loader: ClassLoader) {
        runCatching {
            val expansionManager = Reflect.findClass(
                "com.oplus.systemui.shade.OplusShadeExpansionStateManagerExImpl", loader,
            )
            val panelType = Reflect.findClass("com.android.systemui.shade.PanelType", loader)
            val notificationPanel = Reflect.staticField(panelType, "NOTIFICATION_PANEL")
            val callbackType = Reflect.findClass("kotlin.jvm.functions.Function1", loader)
            val expansionChanged = expansionManager.getDeclaredMethod(
                "onPanelExpansionChanged",
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                panelType,
                callbackType,
                callbackType,
                callbackType,
            )
            HookRuntime.deoptimize(expansionChanged)
            HookRuntime.hook("systemui:notification-panel-foreground", expansionChanged,
                XposedInterface.Hooker { chain ->
                    val isNotificationPanel = chain.args.getOrNull(5) === notificationPanel
                    val expansion = (chain.args.getOrNull(0) as? Float)?.takeIf { it.isFinite() }
                    // The OEM ignores NaN expansion updates. Preserve the last known panel state
                    // too; treating a transient NaN as zero can disable the music row until the
                    // shade is closed and opened again.
                    val isOpening = isNotificationPanel && expansion != null && expansion > 0f && !panelRefreshRequested
                    if (isNotificationPanel && expansion != null) {
                        notificationPanelForeground = expansion > 0f
                        if (expansion <= 0f) panelRefreshRequested = false
                        else if (isOpening) panelRefreshRequested = true
                    }
                    // Set this before the OEM callback: it can synchronously update the media row
                    // and controller list, whose hooks need the current panel state immediately.
                    val result = chain.proceed()
                    if (isOpening) {
                        refreshForNotificationCenter("notification-shade-opening")
                    }
                    result
                })
            val expanded = expansionManager.getDeclaredMethod(
                "onShadeExpansionFullyChanged", Boolean::class.javaPrimitiveType, panelType, callbackType,
            )
            HookRuntime.deoptimize(expanded)
            HookRuntime.hook(
                "systemui:refresh-music-on-notification-shade-open",
                expanded,
                XposedInterface.Hooker { chain ->
                    val isExpanded = chain.args.getOrNull(0) as? Boolean == true
                    val requestedPanel = chain.args.getOrNull(1)
                    val isNotificationPanel = requestedPanel === notificationPanel
                    val shouldRefresh = isNotificationPanel && isExpanded && !panelRefreshRequested
                    if (isNotificationPanel) {
                        notificationPanelForeground = isExpanded
                        if (isExpanded) panelRefreshRequested = true
                    }
                    val result = chain.proceed()
                    if (shouldRefresh) {
                        // Fallback for devices that deliver the fully-expanded callback without a
                        // preceding positive panel-fraction callback.
                        refreshForNotificationCenter("notification-shade-expanded")
                    } else if (!isExpanded && isNotificationPanel) {
                        panelRefreshRequested = false
                    }
                    result
                },
            )
        }.onFailure { HookRuntime.installWarn("Notification Center expansion refresh hook unavailable", it) }
    }

    /** Clear stale panel state when the shade window itself becomes hidden or detached. */
    private fun installShadeWindowVisibilityReset(loader: ClassLoader) {
        runCatching {
            val shadeWindow = Reflect.findClass(
                "com.oplus.systemui.shade.OplusNotificationShadeWindowViewExImpl",
                loader,
            )
            val shadeView = Reflect.findClass("com.android.systemui.shade.NotificationShadeWindowView", loader)
            val visibilityChanged = shadeWindow.getDeclaredMethod(
                "onWindowVisibilityChanged", Int::class.javaPrimitiveType, shadeView,
            )
            HookRuntime.hook("systemui:clear-hidden-notification-panel-state", visibilityChanged,
                XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    val visibility = chain.args.getOrNull(0) as? Int
                    if (visibility != null && visibility != View.VISIBLE) {
                        notificationPanelForeground = false
                        panelRefreshRequested = false
                    } else if (visibility == View.VISIBLE) {
                        MediaCoverAppearanceHook.repairVisibleBackgroundMargins("shade-window-visible")
                    }
                    result
                })
        }.onFailure { HookRuntime.installWarn("Notification shade window visibility hook unavailable", it) }
    }

    private fun installExposeActiveMusicController(
        manager: Class<*>,
        companion: Any,
        classifier: java.lang.reflect.Method,
    ) {
        runCatching {
            val getter = manager.getDeclaredMethod("getOplusCustomRowControllerList")
            HookRuntime.deoptimize(getter)
            HookRuntime.hook("systemui:include-active-music-controller", getter, XposedInterface.Hooker { chain ->
                chain.thisObject?.let { customRowManager = it }
                val raw = chain.proceed()
                val original = raw as? List<*> ?: return@Hooker raw
                val receiver = chain.thisObject ?: return@Hooker original
                if (!isNotificationCenterForeground() || !MusicSeedlingPlayback.hasEligibleMediaSession()) {
                    return@Hooker original
                }
                val controllers = Reflect.field(receiver, "customList") as? List<*> ?: return@Hooker original
                val musicController = controllers.firstOrNull { controller -> runCatching {
                    isMusicEntry(Reflect.call(requireNotNull(controller), "getEntry"), companion, classifier)
                }.getOrDefault(false) } ?: return@Hooker original
                if (original.any { it === musicController }) return@Hooker original
                ensureControllerVisible(musicController)
                HookRuntime.runtimeInfo { "Exposed active music controller to Notification Center consumer" }
                ArrayList<Any?>(original.size + 1).apply {
                    addAll(original)
                    add(musicController)
                }
            })

            val isShow = manager.getDeclaredMethod("isShowMediaController")
            HookRuntime.deoptimize(isShow)
            HookRuntime.hook("systemui:active-music-card-present", isShow, XposedInterface.Hooker { chain ->
                val original = chain.proceed() as? Boolean ?: false
                val receiver = chain.thisObject ?: return@Hooker original
                customRowManager = receiver
                if (!isNotificationCenterForeground() || !MusicSeedlingPlayback.hasEligibleMediaSession()) {
                    return@Hooker original
                }
                val controllers = Reflect.field(receiver, "customList") as? List<*> ?: return@Hooker original
                val musicController = controllers.firstOrNull { controller -> runCatching {
                    isMusicEntry(Reflect.call(requireNotNull(controller), "getEntry"), companion, classifier)
                }.getOrDefault(false) } ?: return@Hooker original
                ensureControllerVisible(musicController)
                true
            })
        }.onFailure { HookRuntime.installWarn("Active music consumer hooks unavailable", it) }
    }

    /** PanelType.NOTIFICATION_PANEL expansion is authoritative; status-bar state can lag it. */
    internal fun isNotificationCenterForeground(): Boolean = notificationPanelForeground

    private fun refreshForNotificationCenter(reason: String) {
        // Refresh the cache for accurate host filtering, but never let a stale/empty cache block
        // the native update request. The OEM payload hook checks actual media states.
        MusicSeedlingPlayback.reconcileForNotificationCenter()
        val manager = customRowManager
        val rowVisible = manager?.let(::hasVisibleMusicRow) == true
        HookRuntime.runtimeInfo {
            "Notification panel refresh: reason=$reason, eligible=${MusicSeedlingPlayback.eligibleMediaCount()}, " +
                "managerKnown=${manager != null}, rowVisible=$rowVisible, " +
                "seedling=${MusicSeedlingPlayback.hasSeedlingInstance()}"
        }
        // Rebuild only when no music row is actually visible. Re-running the native list update
        // while its carousel is visible can reset the selected player; an invisible cached row
        // must not suppress the panel-open refresh.
        if (rowVisible) return
        MusicSeedlingPlayback.refreshActiveCardImmediately(reason)
    }

    private fun ensureControllerVisible(controller: Any) {
        runCatching {
            val row = Reflect.call(controller, "getOplusCustomRow") as? View ?: return@runCatching
            if (row.visibility != View.VISIBLE) {
                row.visibility = View.VISIBLE
                row.alpha = 1f
                runCatching { Reflect.call(row, "resetTranslation") }
                row.requestLayout()
                row.invalidate()
            }
        }.onFailure { HookRuntime.runtimeWarn(it) { "Cannot expose active Notification Center music controller" } }
    }

    private fun installPreservePlayingEntry(loader: ClassLoader, manager: Class<*>) {
        runCatching {
            val row = Reflect.findClass(MusicMediaRowClassifier.ROW_CLASS, loader)
            val companionType = Reflect.findClass(MusicMediaRowClassifier.COMPANION_CLASS, loader)
            val companion = requireNotNull(row.getField("Companion").get(null))
            val classifier = companionType.getDeclaredMethod("isOplusMediaCustomRow", String::class.java)
            val setter = manager.getDeclaredMethod(
                "setCustomContainerControllerList", List::class.java, Boolean::class.javaPrimitiveType,
            )
            HookRuntime.deoptimize(setter)
            HookRuntime.hook(
                "systemui:keep-playing-notification-center-entry",
                setter,
                XposedInterface.Hooker { chain ->
                    chain.thisObject?.let { customRowManager = it }
                    if (!notificationPanelForeground) {
                        if (!MusicSeedlingPlayback.hasEligibleMediaSession()) cachedMusicEntry = null
                        return@Hooker chain.proceed()
                    }
                    val rawIncoming = chain.args.getOrNull(0) as? List<*> ?: return@Hooker chain.proceed()
                    val updateResources = chain.args.getOrNull(1) as? Boolean ?: false
                    val receiver = chain.thisObject ?: return@Hooker chain.proceed()
                    val existingEntries = runCatching {
                        Reflect.field(receiver, "customRowEntryList") as? List<*>
                    }.getOrNull().orEmpty()
                    cachedMusicEntry?.takeIf(::isRetiredMusicEntry)?.let { cachedMusicEntry = null }
                    val existingMusicEntries = existingEntries.filter {
                        isMusicEntry(it, companion, classifier) && !isRetiredMusicEntry(it)
                    }
                    val existingMusicOrder = existingEntries
                        .mapIndexedNotNull { index, entry ->
                            if (!isMusicEntry(entry, companion, classifier) || isRetiredMusicEntry(entry)) {
                                return@mapIndexedNotNull null
                            }
                            musicEntryKey(entry)?.let { key -> key to index }
                        }
                        .toMap()

                    // serviceId identifies the card type, not an individual media session. Keep
                    // separate players and remove only duplicate copies of the same row key.
                    val musicEntries = ArrayList<Any?>()
                    val seenMusicKeys = HashSet<String>()
                    rawIncoming.forEach { entry ->
                        if (isMusicEntry(entry, companion, classifier)) {
                            val key = musicEntryKey(entry)
                            if (!isRetiredMusicEntry(entry) && (key == null || seenMusicKeys.add(key))) {
                                musicEntries.add(entry)
                            }
                        }
                    }
                    val orderedMusicEntries = musicEntries.withIndex()
                        .sortedWith(compareBy<IndexedValue<Any?>>(
                            { musicEntryKey(it.value)?.let(existingMusicOrder::get) ?: Int.MAX_VALUE },
                            { it.index },
                        ))
                        .map { it.value }
                    val orderedMusicIterator = orderedMusicEntries.iterator()
                    val insertedExistingMusicKeys = HashSet<String>()
                    val incoming = ArrayList<Any?>(rawIncoming.size + orderedMusicEntries.size)
                    rawIncoming.forEach { entry ->
                        if (isMusicEntry(entry, companion, classifier)) {
                            if (isRetiredMusicEntry(entry)) return@forEach
                            val key = musicEntryKey(entry)
                            if (
                                key != null && key in existingMusicOrder &&
                                insertedExistingMusicKeys.add(key) && orderedMusicIterator.hasNext()
                            ) {
                                // Keep a surviving card in its old full-list slot. New cards are
                                // appended below so they cannot inherit another player's slot.
                                incoming.add(orderedMusicIterator.next())
                            }
                        } else {
                            incoming.add(entry)
                        }
                    }
                    while (orderedMusicIterator.hasNext()) incoming.add(orderedMusicIterator.next())
                    val incomingChanged = incoming.size != rawIncoming.size || incoming.indices.any { index ->
                        incoming[index] !== rawIncoming[index]
                    }
                    val incomingMusicEntry = incoming.firstOrNull { isMusicEntry(it, companion, classifier) }
                    if (HookRuntime.isLogsEnabled()) {
                        val existingMusicKeys = existingEntries.filter { isMusicEntry(it, companion, classifier) }
                            .mapNotNull(::musicEntryKey).joinToString(",") { mediaKeyForLog(it) }
                        val incomingMusicKeys = incoming.filter { isMusicEntry(it, companion, classifier) }
                            .mapNotNull(::musicEntryKey).joinToString(",") { mediaKeyForLog(it) }
                        HookRuntime.info(
                            "Notification Center row list: existing=[$existingMusicKeys], " +
                                "incoming=[$incomingMusicKeys], " +
                                "changed=$incomingChanged, updateResources=$updateResources, " +
                                "eligible=${MusicSeedlingPlayback.eligibleMediaCount()}",
                        )
                    }
                    fun proceedWithIncoming(): Any? = if (incomingChanged) {
                        chain.proceed(arrayOf(incoming, updateResources))
                    } else {
                        chain.proceed()
                    }
                    val existingMusicEntry = existingMusicEntries.firstOrNull() ?: incomingMusicEntry
                    if (existingMusicEntry != null) cachedMusicEntry = existingMusicEntry
                    val eligibleMedia = MusicSeedlingPlayback.hasEligibleMediaSession()
                    if (updateResources || !eligibleMedia) {
                        if (!eligibleMedia) cachedMusicEntry = null
                        val result = proceedWithIncoming()
                        if (eligibleMedia && !updateResources) revealActiveMusicRow(receiver, companion, classifier)
                        return@Hooker result
                    }
                    // This hook is entered only while the exact NOTIFICATION_PANEL is expanded.
                    // StatusBarStateController can lag the shade transition and must not veto the
                    // notification-row retention decision.
                    val retainedCandidates = ArrayList<Any?>()
                    val retainedKeys = HashSet<String>()
                    (existingMusicEntries + listOfNotNull(cachedMusicEntry)).forEach { entry ->
                        if (!isMusicEntry(entry, companion, classifier) || isRetiredMusicEntry(entry)) {
                            return@forEach
                        }
                        val key = musicEntryKey(entry)
                        if (key == null || retainedKeys.add(key)) retainedCandidates.add(entry)
                    }
                    val incomingMusicKeys = incoming.asSequence()
                        .filter { isMusicEntry(it, companion, classifier) }
                        .mapNotNull(::musicEntryKey)
                        .toHashSet()
                    val missingEntries = retainedCandidates.filter { entry ->
                        val key = musicEntryKey(entry)
                        key == null || key !in incomingMusicKeys
                    }
                    if (missingEntries.isEmpty()) {
                        logRestoreState {
                            "setter:incomingMusic=${incomingMusicEntry != null},alreadyPresent=true," +
                                "retained=${retainedCandidates.size},eligible=true,panel=notification"
                        }
                        val result = proceedWithIncoming()
                        revealActiveMusicRow(receiver, companion, classifier)
                        return@Hooker result
                    }
                    val merged = ArrayList<Any?>(incoming.size + missingEntries.size).apply {
                        addAll(incoming)
                        addAll(missingEntries)
                    }
                    HookRuntime.runtimeInfo {
                        "Restored ${missingEntries.size} eligible music entries through " +
                            "native Notification Center list setter"
                    }
                    logRestoreState {
                        "setter:incomingMusic=${incomingMusicEntry != null},restored=${missingEntries.size}," +
                            "eligible=true,panel=notification"
                    }
                    val result = chain.proceed(arrayOf(merged, updateResources))
                    revealActiveMusicRow(receiver, companion, classifier)
                    result
                },
            )
        }.onFailure { HookRuntime.installWarn("Playing media row retention hook unavailable", it) }
    }

    private fun installReAddAfterMediaLaunch(manager: Class<*>) {
        runCatching {
            val launch = manager.getDeclaredMethod(
                "callPluginStartActivity", String::class.java, Int::class.javaPrimitiveType,
            )
            HookRuntime.deoptimize(launch)
            HookRuntime.hook("systemui:restore-music-row-after-launch", launch, XposedInterface.Hooker { chain ->
                val receiver = chain.thisObject ?: return@Hooker chain.proceed()
                val key = chain.args.getOrNull(0) as? String
                val entries = runCatching { Reflect.field(receiver, "customRowEntryList") as? List<*> }.getOrNull()
                val listedEntry = entries?.firstOrNull { entry ->
                    runCatching {
                        val candidate = requireNotNull(entry)
                        Reflect.call(candidate, "getKey") == key &&
                            isMusicService(Reflect.call(candidate, "getServiceId") as? String)
                    }.getOrDefault(false)
                }
                // The lock-screen capsule can dispatch its click while the Notification Center
                // controller list is being rebuilt. Use the cached entry only when its key is an
                // exact match; never infer a music click merely because some player is active.
                val cachedEntryForKey = cachedMusicEntry?.takeIf { entry ->
                    musicEntryKey(entry) == key && runCatching {
                        isMusicService(Reflect.call(entry, "getServiceId") as? String)
                    }.getOrDefault(false)
                }
                val clickedEntry = listedEntry ?: cachedEntryForKey
                if (clickedEntry != null) cachedMusicEntry = clickedEntry
                val retainedEntry = clickedEntry ?: cachedMusicEntry
                val retainedIsMusic = retainedEntry?.let { entry -> runCatching {
                    isMusicService(Reflect.call(entry, "getServiceId") as? String)
                }.getOrDefault(false) } == true
                val isMusicClick = clickedEntry != null
                logRestoreState {
                    "launch:matchedEntry=${listedEntry != null},cachedKeyMatch=${cachedEntryForKey != null}," +
                        "retainedMusic=$retainedIsMusic,eligible=${MusicSeedlingPlayback.hasEligibleMediaSession()}"
                }

                val result = chain.proceed()
                if (!isMusicClick) return@Hooker result

                MusicSeedlingPlayback.scheduleActiveCardRefresh("music-card-app-launch", 150L)
                result
            })
        }.onFailure { HookRuntime.installWarn("Music post-launch row restoration hook unavailable", it) }
    }

    private inline fun logRestoreState(state: () -> String) {
        if (!HookRuntime.isLogsEnabled()) return
        val message = state()
        if (lastRestoreState.getAndSet(message) != message) HookRuntime.info("Music row restore state: $message")
    }

    private fun isMusicEntry(entry: Any?, companion: Any, classifier: java.lang.reflect.Method): Boolean = runCatching {
        val serviceId = Reflect.call(requireNotNull(entry), "getServiceId") as? String
        serviceId != null && classifier.invoke(companion, serviceId) as? Boolean == true
    }.getOrDefault(false)

    private fun musicEntryKey(entry: Any?): String? = runCatching {
        Reflect.call(requireNotNull(entry), "getKey") as? String
    }.getOrNull()

    private fun revealActiveMusicRow(manager: Any, companion: Any, classifier: java.lang.reflect.Method) {
        if (!isNotificationCenterForeground() || !MusicSeedlingPlayback.hasEligibleMediaSession()) return
        synchronized(revealLock) {
            if (revealPending) return
            revealPending = true
        }
        val posted = mainHandler.post {
            try {
                runCatching {
                    if (!isNotificationCenterForeground() || !MusicSeedlingPlayback.hasEligibleMediaSession()) {
                        return@runCatching
                    }
                    val controllers = Reflect.field(manager, "customList") as? List<*> ?: return@runCatching
                    val controller = controllers.firstOrNull { item ->
                        runCatching {
                            isMusicEntry(Reflect.call(requireNotNull(item), "getEntry"), companion, classifier)
                        }.getOrDefault(false)
                    } ?: return@runCatching
                    val row = Reflect.call(controller, "getOplusCustomRow") as? View ?: return@runCatching
                    val oldVisibility = row.visibility
                    if (oldVisibility != View.VISIBLE) {
                        row.visibility = View.VISIBLE
                        row.alpha = 1f
                        runCatching { Reflect.call(row, "resetTranslation") }
                        row.requestLayout()
                        row.invalidate()
                        HookRuntime.runtimeInfo {
                            "Restored active Notification Center music row visibility: " +
                                "oldVisibility=$oldVisibility"
                        }
                        MediaCoverAppearanceHook.repairVisibleBackgroundMargins("music-card-row-visible")
                    }
                }.onFailure {
                    HookRuntime.runtimeWarn(it) { "Cannot restore active Notification Center music row visibility" }
                }
            } finally {
                synchronized(revealLock) { revealPending = false }
            }
        }
        if (!posted) synchronized(revealLock) { revealPending = false }
    }

    private fun hasMusicRow(manager: Any): Boolean {
        val rows = Reflect.field(manager, "customList") as? List<*> ?: return false
        return rows.any { controller -> runCatching {
            val row = Reflect.call(controller!!, "getOplusCustomRow")!!
            val entry = Reflect.call(row, "getEntry")
            isMusicService(Reflect.call(entry!!, "getServiceId") as? String)
        }.getOrDefault(false) }
    }

    private fun hasVisibleMusicRow(manager: Any): Boolean {
        val rows = Reflect.field(manager, "customList") as? List<*> ?: return false
        return rows.any { controller -> runCatching {
            val row = Reflect.call(requireNotNull(controller), "getOplusCustomRow") as? View
                ?: return@runCatching false
            isMusicService(
                Reflect.call(Reflect.call(row, "getEntry")!!, "getServiceId") as? String,
            ) && row.isAttachedToWindow && row.isShown && row.alpha > 0f
        }.getOrDefault(false) }
    }

    private fun hasMusicEntry(manager: Any): Boolean {
        if (hasMusicRow(manager)) return true
        val cached = cachedMusicEntry
        if (cached != null && runCatching {
                isMusicService(Reflect.call(cached, "getServiceId") as? String)
            }.getOrDefault(false)
        ) return true
        val entries = runCatching { Reflect.field(manager, "customRowEntryList") as? List<*> }.getOrNull()
        return entries?.any { entry -> runCatching {
            isMusicService(Reflect.call(requireNotNull(entry), "getServiceId") as? String)
        }.getOrDefault(false) } == true
    }
}

/** Routes the OEM lock-screen album-art callback through SystemUI's immersive-state path. */
internal object MusicLockScreenCoverHook {
    private const val LISTENER =
        "com.oplus.systemui.notification.lockscreen.seedling.CapsuleSeedingData" +
            "\$Companion\$setupSeedlingClickListener\$1"
    private const val CAPSULE_ITEM_CLICK_CALLBACK =
        "com.oplus.systemui.notification.lockscreen.statemanager.LockScreenNotificationViewBinder\$initCapsule\$1"
    private const val CAPSULE_SEEDLING_DATA =
        "com.oplus.systemui.notification.lockscreen.seedling.CapsuleSeedingData"
    private const val CAPSULE_ITEM_DATA =
        "com.oplus.systemui.notification.lockscreen.capsule.modle.CapsuleItemData"

    fun install(loader: ClassLoader) {
        runCatching {
            val listener = Reflect.findClass(LISTENER, loader)
            val onClick = listener.getDeclaredMethod("onClick", View::class.java)
            HookRuntime.deoptimize(onClick)
            HookRuntime.hook("systemui:music-lock-screen-cover-click", onClick, XposedInterface.Hooker { chain ->
                val receiver = chain.thisObject ?: return@Hooker chain.proceed()
                val view = chain.args.getOrNull(0) as? View
                val key = findMusicServiceKey(receiver)
                if (HookRuntime.isLogsEnabled()) {
                    val viewId = view?.id?.let { "0x${it.toString(16)}" } ?: "unknown"
                    HookRuntime.info(
                        "Lock-screen capsule click observed: viewId=$viewId, " +
                            "listenerKeyPresent=${key != null}",
                    )
                }
                if (key == null) return@Hooker chain.proceed()
                val requested = MusicMediaRowHook.requestLockScreenImmersiveState(key)
                if (!requested) {
                    HookRuntime.runtimeWarn {
                        "Music lock-screen cover matched, but immersive request was unavailable"
                    }
                }
                chain.proceed()
            })
            installCapsuleItemClick(loader)
            HookRuntime.installInfo("OEM lock-screen music cover click hook installed")
        }.onFailure { HookRuntime.installWarn("OEM lock-screen music cover click hook unavailable", it) }
    }

    /** The lock-screen capsule item uses the binder callback; its mini icon has a separate OEM listener. */
    private fun installCapsuleItemClick(loader: ClassLoader) {
        runCatching {
            val callback = Reflect.findClass(CAPSULE_ITEM_CLICK_CALLBACK, loader)
            val itemType = Reflect.findClass(CAPSULE_ITEM_DATA, loader)
            val seedlingType = Reflect.findClass(CAPSULE_SEEDLING_DATA, loader)
            val onItemClick = callback.getDeclaredMethod("onItemClick", itemType)
            HookRuntime.hook("systemui:music-lock-screen-capsule-item-click", onItemClick,
                XposedInterface.Hooker { chain ->
                    val item = chain.args.getOrNull(0) ?: return@Hooker chain.proceed()
                    if (!seedlingType.isInstance(item)) return@Hooker chain.proceed()
                    val entry = runCatching { Reflect.call(item, "getCustomRowEntry") }.getOrNull()
                        ?: return@Hooker chain.proceed()
                    val serviceId = runCatching { Reflect.call(entry, "getServiceId") as? String }.getOrNull()
                    if (!isMusicService(serviceId)) return@Hooker chain.proceed()
                    val key = runCatching { Reflect.call(entry, "getKey") as? String }.getOrNull()
                        ?: return@Hooker chain.proceed()
                    HookRuntime.runtimeInfo {
                        "Lock-screen capsule item click observed: musicSeedling=true, key=${mediaKeyForLog(key)}"
                    }
                    if (MusicMediaRowHook.requestLockScreenImmersiveState(key)) {
                        // The OEM callback can start the app. The immersive request already
                        // dispatched the native lock-screen transition for this exact row.
                        null
                    } else {
                        HookRuntime.runtimeWarn {
                            "Music capsule item click could not dispatch immersive request; " +
                                "keeping OEM behavior"
                        }
                        chain.proceed()
                    }
                })
            HookRuntime.installInfo("Lock-screen capsule item click hook installed")
        }.onFailure { HookRuntime.installWarn("Lock-screen capsule item click hook unavailable", it) }
    }

    private fun findMusicServiceKey(receiver: Any): String? {
        // Device SystemUI source confirms CapsuleSeedingData creates this exact listener
        // with $key = CustomRowEntry.getKey(); the other String fields are not a safe match.
        val key = runCatching { Reflect.field(receiver, "\$key") as? String }.getOrNull()
        return key?.takeIf { it.isNotBlank() }
    }
}

internal object MusicMediaRowClassifier {
    internal const val ROW_CLASS = "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow"
    internal const val COMPANION_CLASS =
        "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow\$Companion"
    internal fun isNotificationCenterMediaRow(
        view: Any,
        companion: Any,
        classifier: java.lang.reflect.Method,
    ): Boolean = isMusicMediaRow(view, companion, classifier) && runCatching {
        val manager = Reflect.field(view, "customManager") ?: return false
        val stateController = Reflect.field(manager, "statusBarStateController") ?: return false
        Reflect.call(stateController, "getState") as? Int == 0
    }.getOrDefault(false)

    internal fun isMusicMediaRow(
        view: Any,
        companion: Any,
        classifier: java.lang.reflect.Method,
    ): Boolean = runCatching {
        if (!view.javaClass.name.endsWith("OplusCustomRow")) return false
        val entry = Reflect.call(view, "getEntry") ?: return false
        val serviceId = Reflect.call(entry, "getServiceId") as? String ?: return false
        isMediaService(serviceId, companion, classifier)
    }.getOrDefault(false)

    private fun isMediaService(
        serviceId: String?,
        companion: Any,
        classifier: java.lang.reflect.Method,
    ): Boolean = serviceId != null && runCatching {
        classifier.invoke(companion, serviceId) as? Boolean == true
    }.getOrDefault(false)
}

/** Keep the music row swipeable, but snap it back instead of dismissing it. */
internal object MusicSwipeDismissHook {
    private const val SWIPE_HELPER = "com.oplus.systemui.notification.row.swipe.OplusSwipeHelperExImpl"

    fun install(loader: ClassLoader) {
        runCatching {
            val swipe = Reflect.findClass(SWIPE_HELPER, loader)
            val rowType = Reflect.findClass(MusicMediaRowClassifier.ROW_CLASS, loader)
            val companionType = Reflect.findClass(MusicMediaRowClassifier.COMPANION_CLASS, loader)
            val companion = requireNotNull(rowType.getField("Companion").get(null))
            val classifier = companionType.getDeclaredMethod("isOplusMediaCustomRow", String::class.java)
            val callbackType = Reflect.findClass("com.android.systemui.SwipeHelper\$Callback", loader)
            val canDrag = swipe.getDeclaredMethod("canChildBeDragged", callbackType, View::class.java)
            HookRuntime.deoptimize(canDrag)
            HookRuntime.hook("systemui:music-row-gesture-enabled", canDrag, XposedInterface.Hooker { chain ->
                val row = chain.args.getOrNull(1) as? View ?: return@Hooker chain.proceed()
                if (MusicMediaRowClassifier.isMusicMediaRow(row, companion, classifier)) {
                    HookRuntime.runtimeInfo { "Enabled OEM gesture handling for media row" }
                    true
                } else chain.proceed()
            })

            val dismissibleRow = rowType.getDeclaredMethod("getCanViewBeDismissed")
            HookRuntime.deoptimize(dismissibleRow)
            HookRuntime.hook("systemui:music-row-can-dismiss", dismissibleRow, XposedInterface.Hooker { chain ->
                val row = chain.thisObject ?: return@Hooker chain.proceed()
                if (MusicMediaRowClassifier.isNotificationCenterMediaRow(row, companion, classifier)) false
                else chain.proceed()
            })

            val stackController = Reflect.findClass(
                "com.oplus.systemui.statusbar.notification.stack.NotificationStackScrollLayoutControllerExtImpl",
                loader,
            )
            val dismissible = stackController.getDeclaredMethod("canOplusCustomRowBeDismiss", View::class.java)
            HookRuntime.deoptimize(dismissible)
            HookRuntime.hook("systemui:music-row-dismissible", dismissible, XposedInterface.Hooker { chain ->
                val row = chain.args.getOrNull(0) as? View ?: return@Hooker chain.proceed()
                if (MusicMediaRowClassifier.isNotificationCenterMediaRow(row, companion, classifier)) {
                    HookRuntime.runtimeInfo {
                        "Kept Notification Center media row swipeable; OEM delete affordance disabled"
                    }
                    false
                }
                else chain.proceed()
            })

            val manager = Reflect.findClass(
                "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRowControllerManager", loader,
            )
            val dismiss = manager.getDeclaredMethod("dissmissCustomRow", rowType, String::class.java)
            HookRuntime.deoptimize(dismiss)
            HookRuntime.hook("systemui:music-swipe-dismiss-commit", dismiss, XposedInterface.Hooker { chain ->
                val row = chain.args.getOrNull(0) as? View ?: return@Hooker chain.proceed()
                if (MusicMediaRowClassifier.isNotificationCenterMediaRow(row, companion, classifier)) {
                    HookRuntime.runtimeInfo {
                        val reason = chain.args.getOrNull(1) as? String
                        "Blocked Notification Center media row dismissal; reason=$reason"
                    }
                    runCatching { Reflect.call(row, "resetTranslation") }
                        .onFailure { HookRuntime.runtimeWarn(it) { "Cannot restore swiped media row position" } }
                    row.alpha = 1f
                    row.visibility = View.VISIBLE
                    null
                } else {
                    chain.proceed()
                }
            })
            HookRuntime.installInfo("OEM media-row dismissal hooks installed")
        }.onFailure { HookRuntime.installWarn("Music swipe-dismiss hook unavailable", it) }
    }
}

private fun isMusicService(serviceId: String?): Boolean =
    serviceId == MediaCardModule.MUSIC_SERVICE_ID || serviceId?.endsWith("_${MediaCardModule.MUSIC_SERVICE_ID}") == true
