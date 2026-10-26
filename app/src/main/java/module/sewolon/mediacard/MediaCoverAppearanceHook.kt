package module.sewolon.mediacard

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Applies the selected cover presentation to each currently bound plugin media card. */
internal object MediaCoverAppearanceHook {
    private const val PLUGIN_PACKAGE = "com.oplus.systemui.plugins"
    private const val ROTATABLE_IMAGE_VIEW =
        "com.oplus.systemui.plugins.shared.template.component.media.view.RotatableImageView"
    private const val OEM_BITMAP_PROCESSOR = "com.heytap.log.strategy.f"
    private const val MEDIA_SECTION_BINDER =
        "com.oplus.systemui.plugins.shared.template.section.media.s1"
    private const val MEDIA_MODEL =
        "com.oplus.systemui.plugins.shared.data.media.model.y"
    private const val MEDIA_CARD_PAGE_ROOT =
        "com.oplus.systemui.plugins.shared.template.section.media.MediaPlayerCardPageRootView"
    private const val DEFAULT_ART_SIZE = "media_card_album_art_size"
    private const val BACKGROUND_TOUCH_LISTENER_CLASS =
        "module.sewolon.mediacard.MediaCoverAppearanceHook\$BackgroundTouchListener"
    private const val BACKGROUND_CLICK_LISTENER_CLASS =
        "module.sewolon.mediacard.MediaCoverAppearanceHook\$BackgroundClickListener"
    private val PRIMARY_ACTION_BUTTONS = listOf("btn_prev", "btn_play_pause", "btn_next")
    private const val LARGE_ART_HEIGHT_DP = 65f
    private const val BACKGROUND_BLUR_RADIUS_DP = 24f
    private const val MIN_ADAPTIVE_WIDTH_DP = 32f
    private const val MIN_TITLE_WIDTH_DP = 80f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bypassArtworkSourceHook = ThreadLocal<Boolean>()
    private val states = WeakHashMap<View, CoverState>()
    private val lastLoggedArtworkCandidates = WeakHashMap<View, String>()
    private val artworkOverridesByCover = WeakHashMap<View, WeakHashMap<Bitmap, PendingArtworkOverride>>()
    private val backgroundClickTargets = WeakHashMap<View, WeakReference<View>>()
    private val backgroundTapPoints = WeakHashMap<View, TouchPoints>()
    private val backgroundInteractionStates = WeakHashMap<View, BackgroundInteractionState>()
    @Volatile private var lastObservedLogsEnabled = false
    private val settingsListener: (HookFeatureConfig.Settings) -> Unit = ::applyToTrackedViews

    private data class CoverState(
        val nativeWidth: Int,
        val nativeHeight: Int,
        val nativeVisibility: Int,
        val nativeTextInfoLayoutParams: RelativeLayout.LayoutParams?,
        val nativeCoverOutlineProvider: ViewOutlineProvider?,
        val nativeCoverClipToOutline: Boolean,
        val nativeBackgroundDrawableState: Drawable.ConstantState?,
        var nativeBackgroundDrawableFallback: Drawable?,
        val nativeBackgroundScaleType: ImageView.ScaleType?,
        val nativeBackgroundVisibility: Int,
        val nativeBackgroundClickable: Boolean,
        val nativeBackgroundLayoutParamsHeight: Int,
        val nativeBackgroundAdjustViewBounds: Boolean,
        var nativeBackgroundOutlineProvider: ViewOutlineProvider?,
        val nativeBackgroundClipToOutline: Boolean,
        val nativeCardHost: WeakReference<ViewGroup>?,
        @Volatile var nativeCardHostHeight: Int,
        val nativeProgressContainer: ProgressContainerSnapshot?,
        @Volatile var bitmap: Bitmap?,
        @Volatile var coverDisplayBitmap: Bitmap? = null,
        @Volatile var nativeEffectBitmap: Bitmap? = null,
        @Volatile var highResolutionBitmap: Bitmap? = null,
        @Volatile var highResolutionCoverBitmap: Bitmap? = null,
        @Volatile var highResolutionCoverSource: Bitmap? = null,
        var highResolutionCoverWidth: Int = 0,
        var highResolutionCoverHeight: Int = 0,
        var highResolutionArtworkApplied: Boolean = false,
        var highResolutionCoverOutlineProvider: ViewOutlineProvider? = null,
        var artworkBackgroundApplied: Boolean = false,
        var artworkBackgroundBlurEffect: RenderEffect? = null,
        var artworkBackgroundBlurApplied: Boolean = false,
        @Volatile var lastLoggedBitmapSpec: String? = null,
        var lastLoggedApplySpec: String? = null,
        var lastLoggedResultSpec: String? = null,
        var lastLoggedOemShaderAspect: String? = null,
        var lastLoggedBackgroundBounds: String? = null,
        var backgroundClipMode: HookFeatureConfig.MediaCoverMode? = null,
        var backgroundClipView: WeakReference<View>? = null,
        var backgroundClipSnapshot: BackgroundClipSnapshot? = null,
        var backgroundArtworkOutlineProvider: ViewOutlineProvider? = null,
        var backgroundClipCaptureObserver: WeakReference<ViewTreeObserver>? = null,
        var backgroundClipCaptureListener: ViewTreeObserver.OnPreDrawListener? = null,
        val loggedBackgroundOutlineEvents: MutableSet<String> = HashSet(),
        val backgroundColorSnapshots: MutableList<MediaViewColorSnapshot> = ArrayList(),
        var backgroundPaletteBitmap: Bitmap? = null,
        var backgroundForegroundColor: Int? = null,
        var lastLoggedControlColorSpec: String? = null,
        var pendingResultLog: Runnable? = null,
        var pendingBackgroundMarginRepair: Runnable? = null,
        var adaptiveWidthParent: WeakReference<ViewGroup>? = null,
        var adaptiveWidthLayoutListener: View.OnLayoutChangeListener? = null,
    )

    private data class ProgressContainerSnapshot(
        val view: WeakReference<View>,
        val layoutParams: ViewGroup.LayoutParams,
        val nativeLayoutParamsClassName: String,
        val nativeParent: WeakReference<ViewGroup>,
        val nativeChildIndex: Int,
        var movedForLarge: Boolean = false,
    )

    private data class MediaViewColorSnapshot(
        val view: WeakReference<View>,
        val textColors: ColorStateList? = null,
        val imageTintList: ColorStateList? = null,
        val imageColorFilter: ColorFilter? = null,
        val imageLayerType: Int? = null,
        val imageLayerPaint: Paint? = null,
        val progressTintList: ColorStateList? = null,
        val secondaryProgressTintList: ColorStateList? = null,
        val progressBackgroundTintList: ColorStateList? = null,
        val indeterminateTintList: ColorStateList? = null,
    )

    private data class TouchPoints(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
    )

    private class BackgroundInteractionState {
        @Volatile var nativeTouchListener: View.OnTouchListener? = null
        @Volatile var touchListenerWrapper: BackgroundTouchListener? = null
        @Volatile var nativeClickListener: View.OnClickListener? = null
        @Volatile var clickListenerWrapper: BackgroundClickListener? = null
    }

    private class BackgroundTouchListener(
        @Volatile var nativeListener: View.OnTouchListener?,
        private val recordTouch: (View, MotionEvent) -> Unit,
    ) : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            recordTouch(view, event)
            return nativeListener?.onTouch(view, event) ?: false
        }
    }

    private class BackgroundClickListener(
        @Volatile var nativeListener: View.OnClickListener?,
    ) : View.OnClickListener {
        override fun onClick(view: View) {
            if (!HookRuntime.settingsSnapshot().master ||
                !MusicPluginHooks.routeBlankBackgroundClick(view)
            ) {
                nativeListener?.onClick(view)
            }
        }
    }

    private data class ArtworkBitmapCandidate(
        val bitmap: Bitmap,
        val source: String,
    )

    private data class CoverSize(
        val width: Int,
        val height: Int,
    )

    private data class BackgroundClipSnapshot(
        val mode: HookFeatureConfig.MediaCoverMode,
        val width: Int,
        val height: Int,
        val viewBoundsOnScreen: Rect,
        val outline: Outline,
    )

    private data class PendingArtworkOverride(
        val selected: ArtworkBitmapCandidate,
        val candidateCount: Int,
    )

    fun install(loader: ClassLoader) {
        HookRuntime.addSettingsListener(settingsListener)
        installArtworkSourceDiagnostics(loader)
        runCatching {
            val viewClass = Reflect.findClass(ROTATABLE_IMAGE_VIEW, loader)
            val method = viewClass.getDeclaredMethod("setEffectBitmap", Bitmap::class.java)
            HookRuntime.hook(
                "plugin:media-cover-appearance:${System.identityHashCode(loader)}",
                method,
                XposedInterface.Hooker { chain ->
                    val view = chain.thisObject as? View
                        ?: return@Hooker chain.proceed()
                    if (bypassArtworkSourceHook.get() == true || !isAlbumArtView(view)) {
                        return@Hooker chain.proceed()
                    }
                    val effectBitmap = chain.args.getOrNull(0) as? Bitmap
                    val settings = HookRuntime.settingsSnapshot()
                    val mode = if (settings.master) {
                        settings.mediaCoverMode
                    } else {
                        HookFeatureConfig.MediaCoverMode.SMALL
                    }
                    val adaptiveWidth = settings.master && settings.mediaCoverAdaptiveWidth
                    val candidate = effectBitmap?.let { findArtworkOverride(view, it) }
                    val state = synchronized(states) {
                        states[view] ?: createState(view).also { states[view] = it }
                    }
                    state.nativeEffectBitmap = effectBitmap
                    state.highResolutionBitmap = candidate?.bitmap
                    val useHighResolutionCover = adaptiveWidth && !mode.isBackground
                    val processedBitmap = if (useHighResolutionCover && candidate != null) {
                        createOemProcessedCoverBitmap(
                            cover = view,
                            state = state,
                            source = candidate.bitmap,
                            mode = mode,
                            adaptiveWidth = adaptiveWidth,
                        )
                    } else {
                        null
                    }
                    val bitmap = processedBitmap ?: effectBitmap
                    val result = if (processedBitmap != null) {
                        val args = chain.args.toTypedArray()
                        args[0] = processedBitmap
                        chain.proceed(args)
                    } else {
                        chain.proceed()
                    }
                    state.bitmap = bitmap
                    state.coverDisplayBitmap = bitmap
                    state.highResolutionArtworkApplied = processedBitmap != null
                    if (HookRuntime.isLogsEnabled()) {
                        val host = findCardHost(view)
                        val spec = "bitmap=${describeBitmap(bitmap)};sourceView=${viewLayoutSpec(view)};" +
                            "cardText=${textContentSpec(host)}"
                        if (state.lastLoggedBitmapSpec != spec) {
                            state.lastLoggedBitmapSpec = spec
                            HookRuntime.installInfo("Media cover source update: $spec")
                        }
                        if (processedBitmap != null && candidate != null) {
                            HookRuntime.installInfo(
                                "Media cover OEM-processed source applied: " +
                                    "processor=$OEM_BITMAP_PROCESSOR;source=${candidate.source};" +
                                    "effect=${describeBitmap(effectBitmap)};" +
                                    "raw=${describeBitmap(candidate.bitmap)};" +
                                    "display=${describeBitmap(processedBitmap)}",
                            )
                        }
                    }
                    mainHandler.post { applyAppearance(view, state, HookRuntime.settingsSnapshot()) }
                    result
                },
            )
            HookRuntime.installInfo("Media cover appearance hook installed")
        }.onFailure { HookRuntime.installWarn("Media cover appearance hook unavailable", it) }
    }

    private fun installArtworkSourceDiagnostics(loader: ClassLoader) {
        runCatching {
            val sectionClass = Reflect.findClass(MEDIA_SECTION_BINDER, loader)
            val modelClass = Reflect.findClass(MEDIA_MODEL, loader)
            val bind = sectionClass.declaredMethods.firstOrNull { method ->
                method.returnType == Void.TYPE && method.parameterTypes.contentEquals(arrayOf(modelClass))
            } ?: error("No media-section binder method accepts ${modelClass.name}")
            HookRuntime.hook(
                "plugin:media-cover-art-source-candidates:${System.identityHashCode(loader)}:${bind.name}",
                bind,
                XposedInterface.Hooker { chain ->
                    val section = chain.thisObject
                    val cover = section?.let(::findAlbumArtView)
                    val model = chain.args.getOrNull(0)
                    if (cover != null && model != null) {
                        val candidates = runCatching { findArtworkBitmapCandidates(model) }
                            .getOrElse {
                                HookRuntime.runtimeWarn(it) { "Media cover display source unavailable" }
                                emptyList()
                            }
                        val displayCandidate = candidates.maxByOrNull {
                            it.bitmap.width.toLong() * it.bitmap.height
                        }
                        val viewOverrides = synchronized(artworkOverridesByCover) {
                            artworkOverridesByCover.getOrPut(cover) { WeakHashMap() }.also { it.clear() }
                        }
                        if (displayCandidate != null) {
                            val displayPixels = displayCandidate.bitmap.width.toLong() *
                                displayCandidate.bitmap.height
                            synchronized(artworkOverridesByCover) {
                                candidates
                                    .asSequence()
                                    .filter { it.bitmap !== displayCandidate.bitmap }
                                    .filter {
                                        it.bitmap.width.toLong() * it.bitmap.height < displayPixels
                                    }
                                    .forEach { effectCandidate ->
                                        viewOverrides[effectCandidate.bitmap] =
                                            PendingArtworkOverride(
                                                selected = displayCandidate,
                                                candidateCount = candidates.size,
                                            )
                                    }
                            }
                        }
                        if (HookRuntime.isLogsEnabled()) {
                            val spec = "model=${model.javaClass.name};bitmapCandidates=" +
                                candidates.take(8).joinToString("|") {
                                    "${it.source}:bitmap@${System.identityHashCode(it.bitmap)};" +
                                        describeBitmap(it.bitmap)
                                }
                            val displaySource = displayCandidate?.let {
                                "${it.source}:bitmap@${System.identityHashCode(it.bitmap)};" +
                                    describeBitmap(it.bitmap)
                            } ?: "none"
                            val shouldLog = synchronized(lastLoggedArtworkCandidates) {
                                val loggedSpec = "$spec;displaySource=$displaySource"
                                if (lastLoggedArtworkCandidates[cover] == loggedSpec) {
                                    false
                                } else {
                                    lastLoggedArtworkCandidates[cover] = loggedSpec
                                    true
                                }
                            }
                            if (shouldLog) {
                                HookRuntime.installInfo(
                                    "Media cover source candidates: bindingView=" +
                                        "${System.identityHashCode(cover)};$spec;displaySource=$displaySource",
                                )
                            }
                        }
                    }
                    val result = chain.proceed()
                    cover?.let {
                        MusicPluginHooks.installAlbumArtClickRouting(it)
                        repairBackgroundMarginForCard(it, "media-card-bound")
                    }
                    result
                },
            )
            HookRuntime.installInfo("Media cover source candidate diagnostics installed")
        }.onFailure { HookRuntime.installWarn("Media cover source candidate diagnostics unavailable", it) }
    }

    private fun findArtworkBitmapCandidates(model: Any): List<ArtworkBitmapCandidate> {
        val modelPackage = model.javaClass.packageName
        val visitedObjects = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val visitedBitmaps = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
        val candidates = ArrayList<ArtworkBitmapCandidate>()

        fun visit(value: Any, path: String, depth: Int) {
            when (value) {
                is Bitmap -> {
                    if (!value.isRecycled && value.width > 0 && value.height > 0 &&
                        visitedBitmaps.add(value)
                    ) {
                        candidates += ArtworkBitmapCandidate(value, path)
                    }
                    return
                }
                is BitmapDrawable -> {
                    val bitmap = value.bitmap
                    if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0 &&
                        visitedBitmaps.add(bitmap)
                    ) {
                        candidates += ArtworkBitmapCandidate(bitmap, "$path.bitmap")
                    }
                    return
                }
            }
            if (depth >= 4 || !belongsToArtworkModel(value, modelPackage) ||
                !visitedObjects.add(value)
            ) return

            instanceFields(value.javaClass).forEach { field ->
                val child = readFieldValue(value, field) ?: return@forEach
                val childPath = "$path.${field.name}"
                if (child is Bitmap || child is BitmapDrawable ||
                    belongsToArtworkModel(child, modelPackage)
                ) {
                    visit(child, childPath, depth + 1)
                }
            }
        }

        visit(model, model.javaClass.simpleName, 0)
        return candidates
    }

    private fun findAlbumArtView(section: Any): View? {
        if (section is View && isAlbumArtView(section)) return section
        instanceFields(section.javaClass).forEach { field ->
            val value = readFieldValue(section, field) as? View ?: return@forEach
            if (isAlbumArtView(value)) return value
            if (value is ViewGroup) {
                val artId = resourceId(value.resources, "img_album_art")
                if (artId != 0) {
                    value.findViewById<View>(artId)?.takeIf(::isAlbumArtView)?.let { return it }
                }
            }
        }
        return null
    }

    private fun belongsToArtworkModel(value: Any, modelPackage: String): Boolean {
        val packageName = value.javaClass.packageName
        return packageName == modelPackage || packageName.startsWith("$modelPackage.")
    }

    private fun instanceFields(type: Class<*>): List<Field> = buildList {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .forEach { add(it) }
            current = current.superclass
        }
    }

    private fun readFieldValue(owner: Any, field: Field): Any? = runCatching {
        if (!field.isAccessible) field.isAccessible = true
        field.get(owner)
    }.getOrNull()

    private fun findArtworkOverride(view: View, effectBitmap: Bitmap): ArtworkBitmapCandidate? {
        val pending = synchronized(artworkOverridesByCover) {
            artworkOverridesByCover[view]?.get(effectBitmap)
        }
        if (pending == null) {
            if (HookRuntime.isLogsEnabled()) {
                HookRuntime.installInfo(
                    "Media cover source match: effectBitmap@${System.identityHashCode(effectBitmap)};" +
                        "view=${System.identityHashCode(view)};candidate=none;" +
                        "effect=${describeBitmap(effectBitmap)}",
                )
            }
            return null
        }
        val effectPixels = effectBitmap.width.toLong() * effectBitmap.height
        val selected = pending.selected.takeIf {
            !it.bitmap.isRecycled && it.bitmap !== effectBitmap &&
                it.bitmap.width.toLong() * it.bitmap.height > effectPixels
        }
        if (HookRuntime.isLogsEnabled()) {
            HookRuntime.installInfo(
                "Media cover source match: effectBitmap@${System.identityHashCode(effectBitmap)};" +
                    "view=${System.identityHashCode(view)};" +
                    "candidateCount=${pending.candidateCount};effect=${describeBitmap(effectBitmap)};selected=" +
                    (selected?.let { "${it.source}:${describeBitmap(it.bitmap)}" } ?: "none"),
            )
        }
        return selected
    }

    private fun createState(view: View): CoverState {
        val params = view.layoutParams
        val host = findCardHost(view)
        val background = host?.findViewById<ImageView>(resourceId(view.resources, "bg_1"))
        background?.let { restoreLegacyArtworkOutline(host, it) }
        val textInfo = host?.findViewById<View>(resourceId(view.resources, "text_info_container"))
        val textInfoParams = textInfo?.layoutParams as? RelativeLayout.LayoutParams
        val backgroundParams = background?.layoutParams
        return CoverState(
            nativeWidth = params?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            nativeHeight = params?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            nativeVisibility = view.visibility,
            nativeTextInfoLayoutParams = textInfoParams?.let { RelativeLayout.LayoutParams(it) },
            nativeCoverOutlineProvider = view.outlineProvider,
            nativeCoverClipToOutline = view.clipToOutline,
            nativeBackgroundDrawableState = background?.drawable?.constantState,
            nativeBackgroundDrawableFallback = null,
            nativeBackgroundScaleType = background?.scaleType,
            nativeBackgroundVisibility = background?.visibility ?: View.VISIBLE,
            nativeBackgroundClickable = background?.isClickable ?: false,
            nativeBackgroundLayoutParamsHeight = backgroundParams?.height
                ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            nativeBackgroundAdjustViewBounds = background?.adjustViewBounds ?: false,
            nativeBackgroundOutlineProvider = background?.outlineProvider,
            nativeBackgroundClipToOutline = background?.clipToOutline ?: false,
            nativeCardHost = host?.let(::WeakReference),
            nativeCardHostHeight = host?.let { it.height.takeIf { height -> height > 0 } ?: it.measuredHeight }
                ?: 0,
            nativeProgressContainer = host?.findViewById<View>(
                resourceId(view.resources, "seekbar_container"),
            )?.let { progressContainer ->
                val parent = progressContainer.parent as? ViewGroup
                val layoutParams = progressContainer.layoutParams
                if (parent != null && layoutParams != null) {
                    ProgressContainerSnapshot(
                        view = WeakReference(progressContainer),
                        layoutParams = copyLayoutParams(layoutParams),
                        nativeLayoutParamsClassName = layoutParams.javaClass.name,
                        nativeParent = WeakReference(parent),
                        nativeChildIndex = parent.indexOfChild(progressContainer),
                    )
                } else {
                    null
                }
            },
            bitmap = null,
        )
    }

    /** Remove a provider wrapper left on a live card by a previous module generation. */
    private fun restoreLegacyArtworkOutline(host: View?, background: View): Boolean {
        val current = background.outlineProvider ?: return false
        val providerClassName = current.javaClass.name
        if (!providerClassName.contains("MediaCoverAppearanceHook") ||
            !providerClassName.contains("createArtworkBackgroundOutlineProvider")
        ) return false

        val nativeProvider = current.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            if (Modifier.isStatic(field.modifiers) ||
                !ViewOutlineProvider::class.java.isAssignableFrom(field.type)
            ) return@firstNotNullOfOrNull null
            readFieldValue(current, field) as? ViewOutlineProvider
        }?.takeIf { it.javaClass.name.startsWith("com.oplus.systemui.plugins.") }
            ?: ViewOutlineProvider.BACKGROUND
        background.outlineProvider = nativeProvider
        background.invalidateOutline()

        // The old implementation forced this page root to clip an extra rectangular outline.
        if (host?.javaClass?.name == MEDIA_CARD_PAGE_ROOT) host.clipToOutline = false
        if (HookRuntime.isLogsEnabled()) {
            HookRuntime.installInfo(
                "Restored OEM media-card outline after hot reload: old=$providerClassName;" +
                    "new=${nativeProvider.javaClass.name};rootClip=${host?.clipToOutline}",
            )
        }
        return true
    }

    private fun isAlbumArtView(view: View): Boolean = runCatching {
        view.id != View.NO_ID && view.resources.getResourceName(view.id) == "$PLUGIN_PACKAGE:id/img_album_art"
    }.getOrDefault(false)

    private fun applyToTrackedViews(settings: HookFeatureConfig.Settings) {
        val justEnabledLogs = settings.logsEnabled && !lastObservedLogsEnabled
        lastObservedLogsEnabled = settings.logsEnabled
        val tracked = synchronized(states) { states.entries.map { it.key to it.value } }
        if (!settings.logsEnabled) {
            tracked.forEach { (_, state) ->
                state.pendingResultLog?.let(mainHandler::removeCallbacks)
                state.pendingResultLog = null
            }
        }
        if (!settings.master || !settings.mediaCoverMode.isBackground) {
            tracked.forEach { (_, state) ->
                state.pendingBackgroundMarginRepair?.let(mainHandler::removeCallbacks)
                state.pendingBackgroundMarginRepair = null
            }
        }
        if (tracked.isEmpty()) return
        mainHandler.post {
            if (justEnabledLogs) {
                tracked.forEach { (_, state) -> clearBackgroundOutlineLogDedup(state) }
            }
            tracked.forEach { (view, state) -> applyAppearance(view, state, settings) }
        }
    }

    /** Recheck only the outer text column after an event that can rebind or reveal a media card. */
    fun repairBackgroundMarginForCard(cover: View, trigger: String) {
        val state = synchronized(states) { states[cover] } ?: return
        scheduleBackgroundMarginRepair(cover, state, trigger)
    }

    /** Recheck tracked cards that have become visible without scanning hidden cached pages. */
    fun repairVisibleBackgroundMargins(trigger: String) {
        val settings = HookRuntime.settingsSnapshot()
        if (!settings.master || !settings.mediaCoverMode.isBackground) return
        val tracked = synchronized(states) { states.entries.map { it.key to it.value } }
        tracked.forEach { (cover, state) ->
            val host = findCardHost(cover)
            if (host?.isAttachedToWindow == true && host.isShown) {
                scheduleBackgroundMarginRepair(cover, state, trigger)
            }
        }
    }

    private fun scheduleBackgroundMarginRepair(cover: View, state: CoverState, trigger: String) {
        val settings = HookRuntime.settingsSnapshot()
        if (!settings.master || !settings.mediaCoverMode.isBackground) return
        state.pendingBackgroundMarginRepair?.let(mainHandler::removeCallbacks)
        lateinit var repair: Runnable
        repair = Runnable {
            if (state.pendingBackgroundMarginRepair !== repair) return@Runnable
            state.pendingBackgroundMarginRepair = null
            val currentSettings = HookRuntime.settingsSnapshot()
            if (!currentSettings.master || !currentSettings.mediaCoverMode.isBackground) return@Runnable
            if (synchronized(states) { states[cover] !== state }) return@Runnable

            val host = findCardHost(cover) ?: return@Runnable
            if (!host.isAttachedToWindow || !host.isShown) return@Runnable
            val textInfo = host.findViewById<View>(resourceId(cover.resources, "text_info_container"))
                ?: return@Runnable
            val before = textInfo.layoutParams as? RelativeLayout.LayoutParams ?: return@Runnable
            val beforeMarginStart = before.marginStart
            val changed = repairBackgroundTextInfoPosition(textInfo)
            if (!changed) return@Runnable

            requestLayoutFrom(host)
            if (HookRuntime.isLogsEnabled()) {
                val after = textInfo.layoutParams as? RelativeLayout.LayoutParams
                val expected = dimensionOrDp(textInfo.resources, "media_card_album_art_margin_start", 16f)
                HookRuntime.info(
                    "Media cover background margin repaired: trigger=$trigger;" +
                        "expectedStart=$expected;beforeStart=$beforeMarginStart;" +
                        "afterStart=${after?.marginStart};beforeRules=${before.getRules().contentToString()};" +
                        "afterRules=${after?.getRules()?.contentToString()};" +
                        "textInfo=${viewLayoutSpec(textInfo)};" +
                        "host=${viewLayoutSpec(host)}",
                )
            }
        }
        state.pendingBackgroundMarginRepair = repair
        mainHandler.post(repair)
    }

    private fun applyAppearance(
        cover: View,
        state: CoverState,
        settings: HookFeatureConfig.Settings,
    ) {
        val mode = if (settings.master) settings.mediaCoverMode else HookFeatureConfig.MediaCoverMode.SMALL
        val adaptiveWidth = settings.master && settings.mediaCoverAdaptiveWidth
        val useHighResolutionCover = adaptiveWidth && !mode.isBackground
        val rawHighResolutionBitmap = state.highResolutionBitmap?.takeUnless { it.isRecycled }
        val processedHighResolutionCover = if (useHighResolutionCover && rawHighResolutionBitmap != null) {
            createOemProcessedCoverBitmap(
                cover = cover,
                state = state,
                source = rawHighResolutionBitmap,
                mode = mode,
                adaptiveWidth = adaptiveWidth,
            )
        } else {
            null
        }
        setCoverArtworkSource(cover, state, processedHighResolutionCover)
        applyArtworkOutline(cover, state)
        val nativeBitmap = state.nativeEffectBitmap?.takeUnless { it.isRecycled }
        val bitmap = when {
            mode.isBackground -> rawHighResolutionBitmap ?: nativeBitmap ?: state.bitmap?.takeUnless { it.isRecycled }
            useHighResolutionCover -> processedHighResolutionCover
                ?: nativeBitmap
                ?: state.bitmap?.takeUnless { it.isRecycled }
            else -> nativeBitmap ?: state.bitmap?.takeUnless { it.isRecycled }
        }
        state.bitmap = bitmap
        val host = findCardHost(cover)
        val background = host?.findViewById<ImageView>(resourceId(cover.resources, "bg_1"))
        val title = host?.findViewById<View>(resourceId(cover.resources, "title_text_container"))
        val textInfo = host?.findViewById<View>(resourceId(cover.resources, "text_info_container"))
        val backgroundMode = mode.isBackground && bitmap != null && background != null && title != null
        if (mode.isBackground && !backgroundMode && HookRuntime.isLogsEnabled()) {
            val missing = buildList {
                if (bitmap == null) add("artwork-bitmap")
                if (background == null) add("bg_1")
                if (title == null) add("title_text_container")
            }.joinToString(",").ifEmpty { "unknown" }
            logBackgroundOutline(
                message = { "background mode unavailable;mode=$mode;missing=$missing;" +
                    "cover=${viewIdentity(cover)};host=${host?.let(::viewLayoutSpec) ?: "missing"};" +
                    "background=${background?.let(::viewLayoutSpec) ?: "missing"};" +
                    "title=${title?.let(::viewLayoutSpec) ?: "missing"};" +
                    "bitmap=${bitmap?.let(::describeBitmap) ?: "missing"}" },
                state = state,
                onceKey = { "background-unavailable:$mode:$missing" },
            )
        }

        if (backgroundMode) {
            val backgroundLayoutChanged = showArtworkBackground(cover, state, background!!, bitmap!!, mode)
            applyBackgroundColors(cover, state, host!!, bitmap!!)
            val coverVisibilityChanged = cover.visibility != View.GONE
            if (coverVisibilityChanged) cover.visibility = View.GONE
            val textInfoLayoutChanged = setTextInfoPosition(textInfo, state, background = true)
            val progressWasMoved = state.nativeProgressContainer?.movedForLarge
            val progressChange = applyProgressLayout(cover, state, large = false)
            val progressLayoutChanged = progressWasMoved != state.nativeProgressContainer?.movedForLarge
            if (backgroundLayoutChanged || coverVisibilityChanged || textInfoLayoutChanged || progressLayoutChanged) {
                requestLayoutFrom(host)
            }
            logAppearanceRequest(
                cover = cover,
                state = state,
                host = host,
                background = background,
                title = title,
                mode = mode,
                adaptiveWidth = adaptiveWidth,
                bitmap = bitmap,
                progressChange = progressChange,
            )
            return
        }

        restoreBackgroundColors(state)
        val backgroundRestored = restoreArtworkBackground(background, state)
        val coverVisibilityChanged = cover.visibility != state.nativeVisibility
        if (coverVisibilityChanged) cover.visibility = state.nativeVisibility
        val textInfoLayoutChanged = setTextInfoPosition(textInfo, state, background = false)
        val fallbackMode = if (mode.isBackground) HookFeatureConfig.MediaCoverMode.SMALL else mode
        val oldCoverParams = cover.layoutParams
        val oldCoverWidth = oldCoverParams?.width
        val oldCoverHeight = oldCoverParams?.height
        val coverSize = resizeCover(
            cover,
            state,
            bitmap,
            fallbackMode,
            adaptiveWidth && !mode.isBackground,
        )
        val coverSizeChanged = coverSize != null &&
            (cover.layoutParams?.width != oldCoverWidth || cover.layoutParams?.height != oldCoverHeight)
        coverSize?.let { normalizeOemShaderAspect(cover, state, it) }
        val progressWasMoved = state.nativeProgressContainer?.movedForLarge
        val progressChange = applyProgressLayout(
            cover,
            state,
            large = fallbackMode == HookFeatureConfig.MediaCoverMode.LARGE,
        )
        val progressLayoutChanged = progressWasMoved != state.nativeProgressContainer?.movedForLarge
        if (
            backgroundRestored || coverVisibilityChanged || textInfoLayoutChanged ||
            coverSizeChanged || progressLayoutChanged
        ) {
            requestLayoutFrom(host ?: cover)
        }
        if (adaptiveWidth && !mode.isBackground) {
            watchAdaptiveWidthAfterMeasure(cover, state)
        }
        logAppearanceRequest(
            cover = cover,
            state = state,
            host = host,
            background = background,
            title = title,
            mode = fallbackMode,
            adaptiveWidth = adaptiveWidth && !mode.isBackground,
            bitmap = bitmap,
            progressChange = progressChange,
        )
    }

    private fun showArtworkBackground(
        cover: View,
        state: CoverState,
        background: ImageView,
        bitmap: Bitmap,
        mode: HookFeatureConfig.MediaCoverMode,
    ): Boolean {
        if (!state.artworkBackgroundApplied && state.nativeBackgroundDrawableState == null &&
            state.nativeBackgroundDrawableFallback == null
        ) {
            state.nativeBackgroundDrawableFallback = background.drawable
        }
        val initialHeight = background.layoutParams?.height
        var needsLayout = false
        val constrainedHeight = constrainArtworkBackgroundHeight(cover, state, background)
        var viewChanged = false
        if (background.scaleType != ImageView.ScaleType.CENTER_CROP) {
            background.scaleType = ImageView.ScaleType.CENTER_CROP
            viewChanged = true
        }
        if (background.adjustViewBounds) {
            background.adjustViewBounds = false
            viewChanged = true
            needsLayout = true
        }
        if (background.visibility != View.VISIBLE) {
            background.visibility = View.VISIBLE
            viewChanged = true
            needsLayout = true
        }
        applyBackgroundOutline(cover, state, background, mode)
        if ((background.drawable as? BitmapDrawable)?.bitmap !== bitmap) {
            background.setImageDrawable(BitmapDrawable(background.resources, bitmap))
            viewChanged = true
        }
        installBackgroundTouchTracking(background)
        if (mode == HookFeatureConfig.MediaCoverMode.BLURRED_BACKGROUND) {
            val blur = state.artworkBackgroundBlurEffect ?: run {
                val radius = dp(cover.resources, BACKGROUND_BLUR_RADIUS_DP).toFloat()
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                    .also { state.artworkBackgroundBlurEffect = it }
            }
            if (!state.artworkBackgroundBlurApplied) {
                background.setRenderEffect(blur)
                state.artworkBackgroundBlurApplied = true
                viewChanged = true
            }
        } else if (state.artworkBackgroundBlurApplied) {
            background.setRenderEffect(null)
            state.artworkBackgroundBlurApplied = false
            viewChanged = true
        }
        if (!background.isClickable) {
            background.isClickable = true
            viewChanged = true
        }
        synchronized(backgroundClickTargets) {
            backgroundClickTargets[background] = WeakReference(cover)
        }
        installBackgroundClickTracking(background)
        state.artworkBackgroundApplied = true
        if (constrainedHeight > 0 && HookRuntime.isLogsEnabled()) {
            val boundsSpec = "nativeCardHeight=${state.nativeCardHostHeight};backgroundHeight=$constrainedHeight;" +
                "nativeLpHeight=${state.nativeBackgroundLayoutParamsHeight};scaleType=${background.scaleType}"
            if (state.lastLoggedBackgroundBounds != boundsSpec) {
                state.lastLoggedBackgroundBounds = boundsSpec
                HookRuntime.installInfo("Media cover background bounds constrained: $boundsSpec")
            }
        }
        if (viewChanged) background.invalidate()
        return needsLayout || initialHeight != background.layoutParams?.height
    }

    private fun constrainArtworkBackgroundHeight(
        cover: View,
        state: CoverState,
        background: ImageView,
    ): Int {
        val host = state.nativeCardHost?.get()
        if (state.nativeCardHostHeight <= 0) {
            state.nativeCardHostHeight = host?.let {
                it.height.takeIf { height -> height > 0 } ?: it.measuredHeight
            }?.takeIf { height -> height > 0 }
                ?: background.height.takeIf { height -> height > 0 }
                ?: 0
        }
        val targetHeight = state.nativeCardHostHeight.takeIf { it > 0 } ?: return 0
        val params = background.layoutParams ?: return 0
        // BitmapDrawable has intrinsic dimensions. Pin bg_1 to the card's pre-art
        // height in background modes so those dimensions cannot enlarge the card.
        if (params.height != targetHeight) {
            params.height = targetHeight
            background.layoutParams = params
        }
        return targetHeight
    }

    /** Cache outline diagnostics, but delegate clipping to OEM to retain OPlus smooth corners. */
    private fun applyBackgroundOutline(
        cover: View,
        state: CoverState,
        background: ImageView,
        mode: HookFeatureConfig.MediaCoverMode,
    ) {
        if (state.backgroundClipView?.get() !== background) {
            val previousBackground = state.backgroundClipView?.get()
            logBackgroundOutline(
                message = { "refresh trigger=background-view-changed;" +
                    "oldView=${previousBackground?.let(::viewIdentity) ?: "collected"};" +
                    "newView=${viewIdentity(background)};mode=$mode" },
            )
            cancelBackgroundClipCapture(state, "background-view-changed")
            state.backgroundClipMode = null
            state.backgroundClipSnapshot = null
            clearBackgroundOutlineLogDedup(state)
            state.backgroundClipView = WeakReference(background)
        }
        if (state.backgroundClipMode != mode) {
            val previousMode = state.backgroundClipMode
            cancelBackgroundClipCapture(state, "cover-style-changed:$previousMode->$mode")
            state.backgroundClipMode = mode
            state.backgroundClipSnapshot = null
            val currentProvider = background.outlineProvider
            val currentWasOurProvider = currentProvider === state.backgroundArtworkOutlineProvider
            if (currentProvider !== state.backgroundArtworkOutlineProvider) {
                // ColorOS can replace this provider while animating card transitions.
                state.nativeBackgroundOutlineProvider = currentProvider
            }
            if (currentWasOurProvider) {
                background.outlineProvider = state.nativeBackgroundOutlineProvider
                background.clipToOutline = state.nativeBackgroundClipToOutline
                background.invalidateOutline()
            }
            clearBackgroundOutlineLogDedup(state)
            logBackgroundOutline(
                message = { "refresh trigger=cover-style-changed;oldMode=$previousMode;newMode=$mode;" +
                    "view=${viewIdentity(background)};" +
                    "providerBefore=${describeNativeOutlineProvider(currentProvider)};" +
                    "providerWasCached=$currentWasOurProvider;providerForCapture=" +
                    "${describeNativeOutlineProvider(state.nativeBackgroundOutlineProvider)};" +
                    "clipBefore=${background.clipToOutline};nativeClip=${state.nativeBackgroundClipToOutline}" },
            )
        }

        val currentProvider = background.outlineProvider
        if (currentProvider !== state.backgroundArtworkOutlineProvider &&
            currentProvider !== state.nativeBackgroundOutlineProvider
        ) {
            val previousProvider = state.nativeBackgroundOutlineProvider
            cancelBackgroundClipCapture(state, "oem-outline-provider-changed")
            state.nativeBackgroundOutlineProvider = currentProvider
            state.backgroundClipSnapshot = null
            logBackgroundOutline(
                message = {
                    "refresh trigger=oem-outline-provider-changed;mode=$mode;" +
                        "view=${viewIdentity(background)};" +
                        "previous=${previousProvider?.javaClass?.name ?: "none"};" +
                        "current=${currentProvider?.javaClass?.name ?: "none"};" +
                        "clip=${background.clipToOutline}"
                },
            )
        }

        val cached = state.backgroundClipSnapshot
        if (cached != null) {
            logBackgroundOutline(
                message = {
                    "cached capture reused;${describeBackgroundOutline(background, cached, state.nativeBackgroundOutlineProvider)}"
                },
                onceKey = {
                    "cache-hit:${cached.mode}:${cached.width}x${cached.height}:${cached.viewBoundsOnScreen}"
                },
                state = state,
            )
            applyBackgroundOutlineDelegate(background, state)
            return
        }
        if (state.backgroundClipCaptureListener != null) return

        val observer = background.viewTreeObserver
        if (!observer.isAlive) {
            logBackgroundOutline(
                message = { "pre-draw observer unavailable;capturing immediately;mode=$mode;" +
                    "view=${viewIdentity(background)};layout=${viewLayoutSpec(background)}" },
            )
            captureBackgroundOutline(cover, state, background, mode, trigger = "immediate")
            return
        }
        val coverRef = WeakReference(cover)
        val backgroundRef = WeakReference(background)
        val stateRef = WeakReference(state)
        val observerRef = WeakReference(observer)
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        listener = ViewTreeObserver.OnPreDrawListener {
            observerRef.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            val currentState = stateRef.get()
            if (currentState?.backgroundClipCaptureListener === listener) {
                currentState.backgroundClipCaptureListener = null
                currentState.backgroundClipCaptureObserver = null
                val currentCover = coverRef.get()
                val currentBackground = backgroundRef.get()
                if (currentCover != null && currentBackground != null &&
                    currentState.backgroundClipMode == mode && currentState.artworkBackgroundApplied
                ) {
                    captureBackgroundOutline(
                        currentCover,
                        currentState,
                        currentBackground,
                        mode,
                        trigger = "one-shot-pre-draw",
                    )
                } else {
                    logBackgroundOutline(
                        message = { "capture skipped after pre-draw;mode=$mode;" +
                            "stateMode=${currentState?.backgroundClipMode};" +
                            "coverAlive=${currentCover != null};backgroundAlive=${currentBackground != null};" +
                            "artworkBackgroundApplied=${currentState?.artworkBackgroundApplied}" },
                    )
                }
            }
            true
        }
        state.backgroundClipCaptureObserver = observerRef
        state.backgroundClipCaptureListener = listener
        val scheduled = runCatching { observer.addOnPreDrawListener(listener) }
        if (scheduled.isSuccess) {
            logBackgroundOutline(
                message = { "capture scheduled;trigger=one-shot-pre-draw;mode=$mode;" +
                    "view=${viewIdentity(background)};layout=${viewLayoutSpec(background)};" +
                    "observerAlive=${observer.isAlive}" },
            )
        } else {
            state.backgroundClipCaptureListener = null
            state.backgroundClipCaptureObserver = null
            warnBackgroundOutline(
                message = { "capture scheduling failed;trigger=one-shot-pre-draw;mode=$mode;" +
                    "view=${viewIdentity(background)};layout=${viewLayoutSpec(background)}" },
                error = scheduled.exceptionOrNull(),
            )
        }
    }

    private fun captureBackgroundOutline(
        cover: View,
        state: CoverState,
        background: ImageView,
        mode: HookFeatureConfig.MediaCoverMode,
        trigger: String,
    ) {
        if (state.backgroundClipMode != mode) {
            logBackgroundOutline(
                message = { "capture skipped;reason=mode-changed;requestedMode=$mode;" +
                    "currentMode=${state.backgroundClipMode};view=${viewIdentity(background)}" },
            )
            return
        }
        val currentProvider = background.outlineProvider
        logBackgroundOutline(
            message = { "capture started;trigger=$trigger;mode=$mode;cover=${viewIdentity(cover)};" +
                "view=${viewIdentity(background)};" +
                "layout=${viewLayoutSpec(background)};provider=" +
                "${currentProvider?.javaClass?.name ?: "background"};" +
                "clip=${background.clipToOutline}" },
        )
        if (background.width <= 0 || background.height <= 0) {
            warnBackgroundOutline(
                message = { "capture failed;reason=bg_1-has-no-laid-out-range;" +
                    "size=${background.width}x${background.height};mode=$mode;trigger=$trigger;" +
                    "cover=${viewIdentity(cover)};layout=${viewLayoutSpec(background)}" },
            )
            return
        }

        if (currentProvider !== state.backgroundArtworkOutlineProvider) {
            state.nativeBackgroundOutlineProvider = currentProvider
        }
        val sourceProvider = if (currentProvider === state.backgroundArtworkOutlineProvider) {
            state.nativeBackgroundOutlineProvider
        } else {
            currentProvider
        }
        val outline = Outline()
        val captureResult = runCatching {
            if (sourceProvider != null) sourceProvider.getOutline(background, outline)
            else ViewOutlineProvider.BACKGROUND.getOutline(background, outline)
            !outline.isEmpty && outline.canClip()
        }
        val captured = captureResult.getOrDefault(false)
        if (!captured) {
            val reason = captureResult.exceptionOrNull()?.let {
                "provider-threw:${it.javaClass.name}:${it.message}"
            } ?: "outline-empty-or-not-clippable"
            warnBackgroundOutline(
                message = {
                    val failedBounds = Rect()
                    val failedRectBoundsAvailable = outline.getRect(failedBounds)
                    "capture failed;reason=$reason;trigger=$trigger;mode=$mode;" +
                        "cover=${viewIdentity(cover)};view=${viewIdentity(background)};" +
                        "layout=${viewLayoutSpec(background)};local=$failedBounds;" +
                        "rectBoundsAvailable=$failedRectBoundsAvailable;baseRadius=${outline.radius};" +
                        "provider=${sourceProvider?.javaClass?.name ?: "background"};" +
                        "empty=${outline.isEmpty};canClip=${outline.canClip()};clip=${background.clipToOutline}"
                },
                error = captureResult.exceptionOrNull(),
            )
            return
        }

        // Preserve the OEM Outline object for diagnostics and fallback. Outline.set() copies
        // generic geometry, but may omit OPlus smooth-corner extension data.
        val capturedOutline = outline
        val screenLocation = IntArray(2)
        background.getLocationOnScreen(screenLocation)
        val viewBoundsOnScreen = Rect(
            screenLocation[0], screenLocation[1],
            screenLocation[0] + background.width, screenLocation[1] + background.height,
        )
        state.backgroundClipSnapshot = BackgroundClipSnapshot(
            mode = mode,
            width = background.width,
            height = background.height,
            viewBoundsOnScreen = viewBoundsOnScreen,
            outline = capturedOutline,
        )
        logBackgroundOutline(message = {
            "capture succeeded;trigger=$trigger;" +
                describeBackgroundOutline(background, requireNotNull(state.backgroundClipSnapshot), sourceProvider)
        })
        applyBackgroundOutlineDelegate(background, state)
    }

    private fun applyBackgroundOutlineDelegate(background: ImageView, state: CoverState) {
        val snapshot = state.backgroundClipSnapshot ?: return
        val provider = state.backgroundArtworkOutlineProvider ?: run {
            val stateRef = WeakReference(state)
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val currentState = stateRef.get()
                    val nativeProvider = currentState?.nativeBackgroundOutlineProvider
                    val delegationResult = runCatching {
                        if (nativeProvider != null && nativeProvider !== this) {
                            nativeProvider.getOutline(view, outline)
                        } else {
                            ViewOutlineProvider.BACKGROUND.getOutline(view, outline)
                        }
                        !outline.isEmpty && outline.canClip()
                    }
                    val delegated = delegationResult.getOrDefault(false)
                    if (delegated && currentState != null && HookRuntime.isLogsEnabled()) {
                        val providerSpec = describeNativeOutlineProvider(nativeProvider)
                        val localBounds = Rect()
                        val rectBoundsAvailable = outline.getRect(localBounds)
                        logBackgroundOutline(
                            message = {
                                "outline provider delegated;source=$providerSpec;" +
                                    "view=${viewIdentity(view)};size=${view.width}x${view.height};" +
                                    "local=$localBounds;rectBoundsAvailable=$rectBoundsAvailable;" +
                                    "baseRadius=${outline.radius}"
                            },
                            state = currentState,
                            onceKey = {
                                "provider-delegated:$providerSpec:${view.width}x${view.height}:" +
                                    "$localBounds:${outline.radius}"
                            },
                        )
                    } else if (!delegated && nativeProvider != null && currentState != null &&
                        HookRuntime.isLogsEnabled()
                    ) {
                        warnBackgroundOutline(
                            message = {
                                "outline provider delegation failed;source=" +
                                    "${describeNativeOutlineProvider(nativeProvider)};" +
                                    "view=${viewIdentity(view)};size=${view.width}x${view.height};" +
                                    "fallback=last-captured-outline"
                            },
                            error = delegationResult.exceptionOrNull(),
                            state = currentState,
                            onceKey = {
                                "provider-delegate-failed:${nativeProvider.javaClass.name}:" +
                                    "${view.width}x${view.height}"
                            },
                        )
                    }
                    if (!delegated) {
                        val captured = currentState?.backgroundClipSnapshot?.outline
                        if (captured != null) runCatching { outline.set(captured) }
                        if (outline.isEmpty || !outline.canClip()) {
                            outline.setRect(0, 0, view.width, view.height)
                        }
                    }
                }
            }.also { state.backgroundArtworkOutlineProvider = it }
        }
        if (snapshot.width <= 0 || snapshot.height <= 0) return
        val result = runCatching {
            var changed = false
            if (background.outlineProvider !== provider) {
                background.outlineProvider = provider
                changed = true
            }
            if (!background.clipToOutline) {
                background.clipToOutline = true
                changed = true
            }
            if (changed) background.invalidateOutline()
            changed
        }
        result.onSuccess { changed ->
            logBackgroundOutline(
                message = {
                    "applied;mode=${state.backgroundClipMode};" +
                        "provider=${background.outlineProvider?.javaClass?.name ?: "none"};" +
                        "clip=${background.clipToOutline};providerChanged=$changed;" +
                        "viewBounds=${snapshot.viewBoundsOnScreen};cachedSize=${snapshot.width}x${snapshot.height};" +
                        "delegatesTo=${describeNativeOutlineProvider(state.nativeBackgroundOutlineProvider)};" +
                        "outline=${describeBackgroundOutline(background, snapshot, state.nativeBackgroundOutlineProvider)}"
                },
                state = state,
                onceKey = { "applied:${state.backgroundClipMode}:$changed:${snapshot.width}x${snapshot.height}" },
            )
        }.onFailure { error ->
            warnBackgroundOutline(
                message = { "apply failed;mode=${state.backgroundClipMode};" +
                    "view=${viewIdentity(background)};viewBounds=${snapshot.viewBoundsOnScreen};" +
                    "provider=${provider.javaClass.name};clip=${background.clipToOutline}" },
                error = error,
                state = state,
                onceKey = { "apply-failed:${state.backgroundClipMode}:${snapshot.width}x${snapshot.height}" },
            )
        }
    }

    private fun cancelBackgroundClipCapture(state: CoverState, reason: String = "unspecified") {
        val listener = state.backgroundClipCaptureListener ?: return
        logBackgroundOutline(
            message = { "pending capture cancelled;reason=$reason;mode=${state.backgroundClipMode};" +
                "observerAlive=${state.backgroundClipCaptureObserver?.get()?.isAlive}" },
        )
        state.backgroundClipCaptureObserver?.get()?.takeIf { it.isAlive }
            ?.removeOnPreDrawListener(listener)
        state.backgroundClipCaptureListener = null
        state.backgroundClipCaptureObserver = null
    }

    private fun logBackgroundOutline(
        message: () -> String,
        state: CoverState? = null,
        onceKey: (() -> String)? = null,
    ) {
        if (!HookRuntime.isLogsEnabled()) return
        if (state != null && onceKey != null) {
            val key = onceKey()
            val firstLog = synchronized(state.loggedBackgroundOutlineEvents) {
                state.loggedBackgroundOutlineEvents.add(key)
            }
            if (!firstLog) return
        }
        HookRuntime.installInfo("Media cover background outline: ${message()}")
    }

    private fun warnBackgroundOutline(
        message: () -> String,
        error: Throwable? = null,
        state: CoverState? = null,
        onceKey: (() -> String)? = null,
    ) {
        if (!HookRuntime.isLogsEnabled()) return
        if (state != null && onceKey != null) {
            val key = onceKey()
            val firstLog = synchronized(state.loggedBackgroundOutlineEvents) {
                state.loggedBackgroundOutlineEvents.add(key)
            }
            if (!firstLog) return
        }
        HookRuntime.installWarn("Media cover background outline: ${message()}", error)
    }

    private fun clearBackgroundOutlineLogDedup(state: CoverState) {
        synchronized(state.loggedBackgroundOutlineEvents) {
            state.loggedBackgroundOutlineEvents.clear()
        }
    }

    private fun viewIdentity(view: View): String =
        "${view.javaClass.name}@${System.identityHashCode(view)}#${view.id}"

    private fun describeBackgroundOutline(
        background: View,
        snapshot: BackgroundClipSnapshot,
        sourceProvider: ViewOutlineProvider?,
    ): String {
        val localBounds = Rect()
        val rectBoundsAvailable = snapshot.outline.getRect(localBounds)
        return "mode=${snapshot.mode};view=${background.javaClass.simpleName}@" +
            "${System.identityHashCode(background)};viewBounds=${snapshot.viewBoundsOnScreen};" +
            "size=${snapshot.width}x${snapshot.height};local=$localBounds;" +
            "rectBoundsAvailable=$rectBoundsAvailable;baseRadius=${snapshot.outline.radius};" +
            "provider=${describeNativeOutlineProvider(sourceProvider)}"
    }

    private fun describeNativeOutlineProvider(provider: ViewOutlineProvider?): String {
        if (provider == null) return "background"
        val providerName = provider.javaClass.name
        if (providerName != "${PLUGIN_PACKAGE}.common.util.j") return providerName
        val fields = provider.javaClass.declaredFields
        val rect = fields.firstOrNull { it.name == "a" }?.let { readFieldValue(provider, it) }
        val cornerConfig = fields.firstOrNull { it.name == "b" }?.let { readFieldValue(provider, it) }
        return "$providerName;rect=$rect;cornerConfig=$cornerConfig"
    }

    private fun applyBackgroundColors(
        cover: View,
        state: CoverState,
        host: ViewGroup,
        bitmap: Bitmap,
    ) {
        val foreground = if (state.backgroundPaletteBitmap === bitmap && state.backgroundForegroundColor != null) {
            state.backgroundForegroundColor!!
        } else {
            val sampled = sampleArtworkLuminance(bitmap)
            (if (sampled >= 145f) Color.rgb(24, 24, 24) else Color.WHITE).also {
                state.backgroundPaletteBitmap = bitmap
                state.backgroundForegroundColor = it
                if (HookRuntime.isLogsEnabled()) {
                    HookRuntime.installInfo(
                        "Media cover background foreground selected: luminance=${formatRatio(sampled / 255f)};" +
                            "color=#${Integer.toHexString(it)};source=${describeBitmap(bitmap)}",
                    )
                }
            }
        }
        val actionContainer = host.findViewById<View>(resourceId(cover.resources, "layout_operate"))
        val seekbarContainer = host.findViewById<View>(resourceId(cover.resources, "seekbar_container"))
        val actionImages = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        val actionButtons = PRIMARY_ACTION_BUTTONS.mapNotNull { name ->
            host.findViewById<View>(resourceId(cover.resources, name))?.let { name to it }
        }
        fun collectActionImages(view: View) {
            if (view is ImageView) actionImages += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) collectActionImages(view.getChildAt(index))
            }
        }
        actionButtons.forEach { (_, button) -> collectActionImages(button) }
        fun collect(view: View, includeText: Boolean) {
            val isTarget = (includeText && view is TextView) ||
                (view is ImageView && (
                    actionImages.contains(view) ||
                        (actionContainer != null && isDescendantOf(view, actionContainer))
                    )) ||
                (view is ProgressBar && seekbarContainer != null && isDescendantOf(view, seekbarContainer))
            if (isTarget) {
                val alreadySaved = state.backgroundColorSnapshots.any { it.view.get() === view }
                if (!alreadySaved) state.backgroundColorSnapshots += snapshotMediaViewColors(view)
                applyMediaForeground(view, foreground)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index), includeText)
            }
        }
        collect(host, includeText = true)
        if (HookRuntime.isLogsEnabled()) {
            val buttonSpec = PRIMARY_ACTION_BUTTONS.joinToString("|") { name ->
                val button = actionButtons.firstOrNull { it.first == name }?.second
                val icons = button?.let(::imageViewsUnder).orEmpty().joinToString(",") { image ->
                    val drawable = image.drawable
                    val tint = image.imageTintList?.defaultColor?.let { "#${Integer.toHexString(it)}" } ?: "none"
                    "${drawable?.javaClass?.simpleName ?: "no-drawable"};tint=$tint;" +
                        "filter=${image.colorFilter?.javaClass?.simpleName ?: "none"};" +
                        "viewLayer=${image.layerType};layerFilter=" +
                        "${nativeLayerPaint(image)?.colorFilter?.javaClass?.simpleName ?: "none"}"
                }.ifEmpty { "no-image-view" }
                "$name=${button?.javaClass?.simpleName ?: "missing"}[$icons]"
            }
            val colorSpec = "#${Integer.toHexString(foreground)};$buttonSpec"
            if (state.lastLoggedControlColorSpec != colorSpec) {
                state.lastLoggedControlColorSpec = colorSpec
                HookRuntime.installInfo("Media cover primary controls colored: $colorSpec")
            }
        }
    }

    private fun imageViewsUnder(root: View): List<ImageView> {
        val images = ArrayList<ImageView>()
        fun visit(view: View) {
            if (view is ImageView) images += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return images
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: ViewParent? = view.parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun snapshotMediaViewColors(view: View): MediaViewColorSnapshot = MediaViewColorSnapshot(
        view = WeakReference(view),
        textColors = (view as? TextView)?.textColors,
        imageTintList = (view as? ImageView)?.imageTintList,
        imageColorFilter = (view as? ImageView)?.colorFilter,
        imageLayerType = (view as? ImageView)?.layerType,
        imageLayerPaint = (view as? ImageView)?.let(::nativeLayerPaint),
        progressTintList = (view as? ProgressBar)?.progressTintList,
        secondaryProgressTintList = (view as? ProgressBar)?.secondaryProgressTintList,
        progressBackgroundTintList = (view as? ProgressBar)?.progressBackgroundTintList,
        indeterminateTintList = (view as? ProgressBar)?.indeterminateTintList,
    )

    private fun applyMediaForeground(view: View, foreground: Int) {
        when (view) {
            is TextView -> view.setTextColor(foreground)
            is ImageView -> {
                view.colorFilter = null
                view.imageTintList = null
                view.setColorFilter(foreground, PorterDuff.Mode.SRC_IN)
                if (view.javaClass.name.contains("lottie", ignoreCase = true)) {
                    val layerPaint = Paint().apply {
                        colorFilter = PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_IN)
                    }
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, layerPaint)
                }
            }
            is ProgressBar -> {
                val muted = ColorStateList.valueOf(Color.argb(
                    0x66, Color.red(foreground), Color.green(foreground), Color.blue(foreground),
                ))
                val secondary = ColorStateList.valueOf(Color.argb(
                    0xAA, Color.red(foreground), Color.green(foreground), Color.blue(foreground),
                ))
                view.progressTintList = ColorStateList.valueOf(foreground)
                view.secondaryProgressTintList = secondary
                view.progressBackgroundTintList = muted
                view.indeterminateTintList = ColorStateList.valueOf(foreground)
            }
        }
    }

    private fun sampleArtworkLuminance(bitmap: Bitmap): Float {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 128f
        return runCatching {
            val columns = 16
            val rows = 16
            var luminanceTotal = 0.0
            for (row in 0 until rows) {
                val y = ((row + 0.5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
                for (column in 0 until columns) {
                    val x = ((column + 0.5f) * bitmap.width / columns).toInt().coerceIn(0, bitmap.width - 1)
                    val pixel = bitmap.getPixel(x, y)
                    val alpha = Color.alpha(pixel) / 255f
                    val sourceLuminance = Color.red(pixel) * 0.299f +
                        Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f
                    luminanceTotal += sourceLuminance * alpha + 32f * (1f - alpha)
                }
            }
            (luminanceTotal / (columns * rows)).toFloat()
        }.getOrDefault(128f)
    }

    private fun restoreBackgroundColors(state: CoverState) {
        state.backgroundColorSnapshots.forEach { snapshot ->
            val view = snapshot.view.get() ?: return@forEach
            if (view is TextView && snapshot.textColors != null) view.setTextColor(snapshot.textColors)
            if (view is ImageView) {
                view.imageTintList = snapshot.imageTintList
                view.colorFilter = snapshot.imageColorFilter
                snapshot.imageLayerType?.let { layerType ->
                    view.setLayerType(layerType, snapshot.imageLayerPaint)
                }
            }
            if (view is ProgressBar) {
                view.progressTintList = snapshot.progressTintList
                view.secondaryProgressTintList = snapshot.secondaryProgressTintList
                view.progressBackgroundTintList = snapshot.progressBackgroundTintList
                view.indeterminateTintList = snapshot.indeterminateTintList
            }
        }
        state.backgroundColorSnapshots.clear()
        state.backgroundPaletteBitmap = null
        state.backgroundForegroundColor = null
        state.lastLoggedControlColorSpec = null
    }

    private fun nativeLayerPaint(view: View): Paint? = runCatching {
        var current: Class<*>? = view.javaClass
        while (current != null) {
            val type = current
            val field = runCatching { type.getDeclaredField("mLayerPaint") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return@runCatching field.get(view) as? Paint
            }
            current = type.superclass
        }
        null
    }.getOrNull()

    private fun restoreArtworkBackground(background: ImageView?, state: CoverState): Boolean {
        if (!state.artworkBackgroundApplied) return false
        val restoredMode = state.backgroundClipMode
        val capturedSnapshot = state.backgroundClipSnapshot
        val blurWasApplied = state.artworkBackgroundBlurApplied
        cancelBackgroundClipCapture(state, "leaving-background-mode")
        val beforeProvider = background?.outlineProvider?.javaClass?.name ?: "none"
        val beforeClip = background?.clipToOutline
        if (background != null) {
            synchronized(backgroundClickTargets) { backgroundClickTargets.remove(background) }
            synchronized(backgroundTapPoints) { backgroundTapPoints.remove(background) }
            restoreBackgroundTouchTracking(background)
            restoreBackgroundClickTracking(background)
            val nativeDrawable = state.nativeBackgroundDrawableState
                ?.newDrawable(background.resources)
                ?.mutate()
                ?: state.nativeBackgroundDrawableFallback
            background.setImageDrawable(nativeDrawable)
            if (state.artworkBackgroundBlurApplied) {
                background.setRenderEffect(null)
                state.artworkBackgroundBlurApplied = false
            }
            state.nativeBackgroundScaleType?.let { background.scaleType = it }
            background.adjustViewBounds = state.nativeBackgroundAdjustViewBounds
            background.outlineProvider = state.nativeBackgroundOutlineProvider
            background.clipToOutline = state.nativeBackgroundClipToOutline
            background.layoutParams?.let { params ->
                if (params.height != state.nativeBackgroundLayoutParamsHeight) {
                    params.height = state.nativeBackgroundLayoutParamsHeight
                    background.layoutParams = params
                }
            }
            background.visibility = state.nativeBackgroundVisibility
            background.isClickable = state.nativeBackgroundClickable
        }
        logBackgroundOutline(
            message = { "restored OEM background state;mode=$restoredMode;view=" +
                "${background?.let(::viewIdentity) ?: "missing"};capturedOutline=" +
                "${if (background != null && capturedSnapshot != null) describeBackgroundOutline(
                    background,
                    capturedSnapshot,
                    state.nativeBackgroundOutlineProvider,
                ) else "none"};" +
                "providerBefore=$beforeProvider;providerAfter=" +
                "${background?.outlineProvider?.javaClass?.name ?: "none"};" +
                "clipBefore=$beforeClip;clipAfter=${background?.clipToOutline};" +
                "nativeProvider=${state.nativeBackgroundOutlineProvider?.javaClass?.name ?: "background"};" +
                "nativeClip=${state.nativeBackgroundClipToOutline};" +
                "drawable=${background?.drawable?.javaClass?.name ?: "none"};" +
                "scaleType=${background?.scaleType};visibility=${background?.visibility};" +
                "layoutHeight=${background?.layoutParams?.height};" +
                "blurWasApplied=$blurWasApplied" },
        )
        state.artworkBackgroundApplied = false
        state.backgroundClipMode = null
        state.backgroundClipView = null
        state.backgroundClipSnapshot = null
        state.nativeBackgroundDrawableFallback = null
        clearBackgroundOutlineLogDedup(state)
        background?.invalidate()
        return true
    }

    /** Attach touch sampling only to this card's bg_1, while forwarding its OEM listener. */
    private fun installBackgroundTouchTracking(background: View) {
        val current = runCatching { readOnTouchListener(background) }.getOrElse {
            HookRuntime.runtimeWarn(it) { "Cannot inspect media background touch listener" }
            return
        }
        val state = backgroundInteractionState(background)
        val existingWrapper = state.touchListenerWrapper
        if (existingWrapper != null) {
            if (current !== existingWrapper) {
                state.nativeTouchListener = unwrapPreviousTouchListener(current)
                existingWrapper.nativeListener = state.nativeTouchListener
                background.setOnTouchListener(existingWrapper)
            }
            return
        }

        state.nativeTouchListener = unwrapPreviousTouchListener(current)
        val wrapper = BackgroundTouchListener(state.nativeTouchListener, ::recordBackgroundTouch)
        state.touchListenerWrapper = wrapper
        background.setOnTouchListener(wrapper)
    }

    private fun restoreBackgroundTouchTracking(background: View) {
        val state = synchronized(backgroundInteractionStates) { backgroundInteractionStates[background] } ?: return
        val wrapper = state.touchListenerWrapper ?: return
        val current = runCatching { readOnTouchListener(background) }.getOrNull()
        if (current === wrapper) {
            background.setOnTouchListener(state.nativeTouchListener)
        }
        state.touchListenerWrapper = null
        state.nativeTouchListener = null
        removeBackgroundInteractionStateIfUnused(background, state)
    }

    private fun installBackgroundClickTracking(background: View) {
        val current = runCatching { readOnClickListener(background) }.getOrElse {
            HookRuntime.runtimeWarn(it) { "Cannot inspect media background click listener" }
            return
        }
        val state = backgroundInteractionState(background)
        val existingWrapper = state.clickListenerWrapper
        if (existingWrapper != null) {
            if (current !== existingWrapper) {
                state.nativeClickListener = unwrapPreviousClickListener(current)
                existingWrapper.nativeListener = state.nativeClickListener
                background.setOnClickListener(existingWrapper)
            }
            return
        }

        state.nativeClickListener = unwrapPreviousClickListener(current)
        val wrapper = BackgroundClickListener(state.nativeClickListener)
        state.clickListenerWrapper = wrapper
        background.setOnClickListener(wrapper)
    }

    private fun restoreBackgroundClickTracking(background: View) {
        val state = synchronized(backgroundInteractionStates) { backgroundInteractionStates[background] } ?: return
        val wrapper = state.clickListenerWrapper ?: return
        val current = runCatching { readOnClickListener(background) }.getOrNull()
        if (current === wrapper) {
            background.setOnClickListener(state.nativeClickListener)
        }
        state.clickListenerWrapper = null
        state.nativeClickListener = null
        removeBackgroundInteractionStateIfUnused(background, state)
    }

    private fun backgroundInteractionState(background: View): BackgroundInteractionState =
        synchronized(backgroundInteractionStates) {
            backgroundInteractionStates.getOrPut(background) { BackgroundInteractionState() }
        }

    private fun removeBackgroundInteractionStateIfUnused(
        background: View,
        state: BackgroundInteractionState,
    ) {
        if (state.touchListenerWrapper == null && state.clickListenerWrapper == null) {
            synchronized(backgroundInteractionStates) {
                if (backgroundInteractionStates[background] === state) {
                    backgroundInteractionStates.remove(background)
                }
            }
        }
    }

    private fun readOnTouchListener(view: View): View.OnTouchListener? {
        val listenerInfo = Reflect.field(view, "mListenerInfo") ?: return null
        return Reflect.field(listenerInfo, "mOnTouchListener") as? View.OnTouchListener
    }

    private fun readOnClickListener(view: View): View.OnClickListener? {
        val listenerInfo = Reflect.field(view, "mListenerInfo") ?: return null
        return Reflect.field(listenerInfo, "mOnClickListener") as? View.OnClickListener
    }

    private fun unwrapPreviousClickListener(listener: View.OnClickListener?): View.OnClickListener? {
        if (listener?.javaClass?.name != BACKGROUND_CLICK_LISTENER_CLASS) return listener
        return runCatching { Reflect.field(listener, "nativeListener") as? View.OnClickListener }
            .getOrElse { listener }
    }

    private fun unwrapPreviousTouchListener(listener: View.OnTouchListener?): View.OnTouchListener? {
        if (listener == null) return null
        if (listener.javaClass.name == BACKGROUND_TOUCH_LISTENER_CLASS) {
            return runCatching { Reflect.field(listener, "nativeListener") as? View.OnTouchListener }
                .getOrElse { listener }
        }
        val className = listener.javaClass.name
        if (!className.startsWith("module.sewolon.mediacard.MediaCoverAppearanceHook\$") ||
            !className.contains("installBackgroundTouchTracking")
        ) return listener
        val previousState = listener.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            readFieldValue(listener, field)?.takeIf {
                it.javaClass.name.contains("MediaCoverAppearanceHook\$CoverState")
            }
        } ?: return listener
        return runCatching {
            Reflect.field(previousState, "nativeBackgroundTouchListener") as? View.OnTouchListener
        }.getOrElse { listener }
    }

    internal fun snapshotBackgroundClickTargetsForHotReload(): List<Pair<View, View>> =
        synchronized(backgroundClickTargets) {
            backgroundClickTargets.mapNotNull { (background, reference) ->
                reference.get()?.let { cover -> background to cover }
            }
        }

    internal fun restoreBackgroundClickTargetsAfterHotReload(targets: List<Pair<View, View>>) {
        targets.forEach { (background, cover) ->
            synchronized(backgroundClickTargets) {
                backgroundClickTargets[background] = WeakReference(cover)
            }
            background.post {
                installBackgroundTouchTracking(background)
                installBackgroundClickTracking(background)
            }
        }
    }

    fun takeBlankBackgroundClickTarget(background: View): View? {
        val cover = synchronized(backgroundClickTargets) {
            backgroundClickTargets[background]?.get()
        } ?: return null
        val touch = synchronized(backgroundTapPoints) { backgroundTapPoints.remove(background) } ?: return null
        val host = findCardHost(cover) ?: return null
        return if (touchHitsInteractiveContent(host, background, touch)) null else cover
    }

    private fun recordBackgroundTouch(view: View, event: MotionEvent) {
        val isTarget = synchronized(backgroundClickTargets) {
            backgroundClickTargets[view]?.get() != null
        }
        if (!isTarget) return
        synchronized(backgroundTapPoints) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> backgroundTapPoints[view] =
                    TouchPoints(event.x, event.y, event.x, event.y)
                MotionEvent.ACTION_MOVE -> backgroundTapPoints[view]?.let { start ->
                    backgroundTapPoints[view] = start.copy(endX = event.x, endY = event.y)
                }
                MotionEvent.ACTION_UP -> backgroundTapPoints[view]?.let { start ->
                    backgroundTapPoints[view] = start.copy(endX = event.x, endY = event.y)
                }
                MotionEvent.ACTION_CANCEL -> backgroundTapPoints.remove(view)
            }
        }
    }

    private fun touchHitsInteractiveContent(
        host: ViewGroup,
        background: View,
        touch: TouchPoints,
    ): Boolean {
        val backgroundLocation = IntArray(2)
        background.getLocationOnScreen(backgroundLocation)
        val points = arrayOf(
            backgroundLocation[0] + touch.startX.toInt() to backgroundLocation[1] + touch.startY.toInt(),
            backgroundLocation[0] + touch.endX.toInt() to backgroundLocation[1] + touch.endY.toInt(),
        )
        val hostArea = (host.width.toLong() * host.height.toLong()).coerceAtLeast(1L)

        fun containsTouch(bounds: Rect): Boolean = points.any { (x, y) -> bounds.contains(x, y) }

        fun inspect(view: View): Boolean {
            if (view !== host && view !== background && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0
            ) {
                val bounds = Rect()
                if (view.getGlobalVisibleRect(bounds)) {
                    if (view is ProgressBar || isProgressView(view)) {
                        bounds.inset(0, -dp(view.resources, 12f))
                        if (containsTouch(bounds)) return true
                    } else if ((view.isClickable || view.isLongClickable) &&
                        view.width.toLong() * view.height.toLong() < hostArea * 3L / 4L &&
                        containsTouch(bounds)
                    ) {
                        return true
                    }
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (inspect(view.getChildAt(index))) return true
                }
            }
            return false
        }

        return inspect(host)
    }

    private fun isProgressView(view: View): Boolean {
        val className = view.javaClass.name.lowercase()
        val resourceName = runCatching {
            if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id).lowercase()
        }.getOrDefault("")
        return listOf("progress", "seekbar", "seek_bar", "slider").any {
            it in className || it in resourceName
        }
    }

    private fun findProgressViews(host: ViewGroup): List<View> {
        val found = ArrayList<View>()
        fun visit(view: View) {
            if (view !== host && (view is ProgressBar || isProgressView(view))) found += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(host)
        return found
    }

    private fun applyProgressLayout(cover: View, state: CoverState, large: Boolean): String {
        val saved = state.nativeProgressContainer
            ?: return "large=$large;container=missing;changed=0;skipped=1"
        if (saved.movedForLarge == large) {
            return "unchanged;large=$large;containerMoved=${saved.movedForLarge}"
        }

        val container = saved.view.get()
            ?: return "large=$large;container=collected;changed=0;skipped=1"
        val originalParent = saved.nativeParent.get()
            ?: return "large=$large;container=native-parent-collected;changed=0;skipped=1"
        val coverRow = cover.parent as? RelativeLayout
        var changed = 0
        var skipped = 0

        if (large) {
            val originalParams = saved.layoutParams as? LinearLayout.LayoutParams
            val textInfoId = resourceId(cover.resources, "text_info_container")
            val textInfo = coverRow?.findViewById<View>(textInfoId)
            val textInfoParams = textInfo?.layoutParams as? RelativeLayout.LayoutParams
            val validHierarchy =
                coverRow != null &&
                    coverRow.javaClass == RelativeLayout::class.java &&
                    originalParent.javaClass == LinearLayout::class.java &&
                    coverRow.parent === originalParent &&
                    container.parent === originalParent &&
                    saved.nativeLayoutParamsClassName == LinearLayout.LayoutParams::class.java.name &&
                    container.layoutParams?.javaClass == LinearLayout.LayoutParams::class.java &&
                    cover.id != View.NO_ID &&
                    textInfo != null &&
                    textInfo.id != View.NO_ID &&
                    textInfo.parent === coverRow &&
                    textInfoParams != null &&
                    originalParams != null

            if (validHierarchy && coverRow != null && textInfo != null &&
                textInfoParams != null && originalParams != null
            ) {
                // Move the complete time/seek-bar/time row into the existing
                // artwork/text RelativeLayout. Its children keep their native
                // LinearLayout.LayoutParams and weight behavior.
                val params = RelativeLayout.LayoutParams(textInfoParams).apply {
                    width = originalParams.width
                    height = originalParams.height
                    topMargin = originalParams.topMargin
                    removeRule(RelativeLayout.ALIGN_PARENT_START)
                    removeRule(RelativeLayout.ALIGN_PARENT_LEFT)
                    removeRule(RelativeLayout.CENTER_HORIZONTAL)
                    removeRule(RelativeLayout.ALIGN_PARENT_TOP)
                    removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    removeRule(RelativeLayout.CENTER_VERTICAL)
                    removeRule(RelativeLayout.ALIGN_TOP)
                    removeRule(RelativeLayout.ALIGN_BOTTOM)
                    removeRule(RelativeLayout.ABOVE)
                    removeRule(RelativeLayout.BELOW)
                    addRule(RelativeLayout.END_OF, cover.id)
                    addRule(RelativeLayout.BELOW, textInfo.id)
                }
                if (reparentView(
                        child = container,
                        sourceParent = originalParent,
                        destinationParent = coverRow,
                        destinationIndex = -1,
                        destinationLayoutParams = params,
                        operation = "move into large-cover column",
                    )
                ) {
                    saved.movedForLarge = true
                    changed++
                } else {
                    skipped++
                }
            } else {
                skipped++
            }
        } else if (saved.movedForLarge) {
            val currentParent = container.parent as? ViewGroup
            if (
                currentParent != null &&
                currentParent !== originalParent &&
                saved.nativeLayoutParamsClassName == LinearLayout.LayoutParams::class.java.name &&
                saved.layoutParams is LinearLayout.LayoutParams
            ) {
                if (reparentView(
                        child = container,
                        sourceParent = currentParent,
                        destinationParent = originalParent,
                        destinationIndex = saved.nativeChildIndex.coerceIn(0, originalParent.childCount),
                        destinationLayoutParams = copyLayoutParams(saved.layoutParams),
                        operation = "restore native seek-bar row",
                    )
                ) {
                    saved.movedForLarge = false
                    changed++
                } else {
                    skipped++
                }
            } else {
                skipped++
            }
        }

        return "large=$large;containerParent=${container.parent?.javaClass?.name ?: "none"};" +
            "changed=$changed;skipped=$skipped;moved=${saved.movedForLarge}"
    }

    private fun reparentView(
        child: View,
        sourceParent: ViewGroup,
        destinationParent: ViewGroup,
        destinationIndex: Int,
        destinationLayoutParams: ViewGroup.LayoutParams,
        operation: String,
    ): Boolean {
        if (child.parent !== sourceParent) return false
        val sourceIndex = sourceParent.indexOfChild(child)
        val sourceLayoutParams = child.layoutParams?.let(::copyLayoutParams) ?: return false
        sourceParent.removeView(child)
        return try {
            destinationParent.addView(child, destinationIndex, destinationLayoutParams)
            true
        } catch (error: Throwable) {
            runCatching {
                (child.parent as? ViewGroup)?.removeView(child)
                sourceParent.addView(
                    child,
                    sourceIndex.coerceIn(0, sourceParent.childCount),
                    sourceLayoutParams,
                )
            }.onFailure(error::addSuppressed)
            HookRuntime.runtimeWarn(error) { "Cannot $operation" }
            false
        }
    }

    private fun logAppearanceRequest(
        cover: View,
        state: CoverState,
        host: ViewGroup?,
        background: ImageView?,
        title: View?,
        mode: HookFeatureConfig.MediaCoverMode,
        adaptiveWidth: Boolean,
        bitmap: Bitmap?,
        progressChange: String,
    ) {
        if (!HookRuntime.isLogsEnabled()) {
            state.pendingResultLog?.let(mainHandler::removeCallbacks)
            state.pendingResultLog = null
            return
        }
        val textInfo = host?.findViewById<View>(resourceId(cover.resources, "text_info_container"))
        val header = host?.findViewById<View>(resourceId(cover.resources, "header_container"))
        val progressRow = host?.findViewById<View>(resourceId(cover.resources, "seekbar_container"))
        val request = "mode=$mode;adaptiveWidth=$adaptiveWidth;source=${describeBitmap(bitmap)};" +
            "host=${viewLayoutSpec(host)};header=${viewLayoutSpec(header)};cover=${viewLayoutSpec(cover)};" +
            "background=${viewLayoutSpec(background)};textInfo=${viewLayoutSpec(textInfo)};" +
            "title=${viewLayoutSpec(title)};progressRow=${viewLayoutSpec(progressRow)};" +
            "nativeCardHeight=${state.nativeCardHostHeight};backgroundForeground=" +
            (state.backgroundForegroundColor?.let { "#${Integer.toHexString(it)}" } ?: "none") +
            ";backgroundOutline=${background?.outlineProvider?.javaClass?.name ?: "none"};" +
            "cardText=${textContentSpec(host)};progress={$progressChange}"
        if (state.lastLoggedApplySpec != request) {
            state.lastLoggedApplySpec = request
            HookRuntime.installInfo("Media cover apply request: $request")
        }

        state.pendingResultLog?.let(mainHandler::removeCallbacks)
        val resultLog = Runnable {
            state.pendingResultLog = null
            if (!HookRuntime.isLogsEnabled()) return@Runnable
            val currentHost = findCardHost(cover)
            val currentBackground = currentHost?.findViewById<ImageView>(
                resourceId(cover.resources, "bg_1"),
            )
            val currentTitle = currentHost?.findViewById<View>(
                resourceId(cover.resources, "title_text_container"),
            )
            val currentTextInfo = currentHost?.findViewById<View>(
                resourceId(cover.resources, "text_info_container"),
            )
            val currentHeader = currentHost?.findViewById<View>(
                resourceId(cover.resources, "header_container"),
            )
            val currentProgressRow = currentHost?.findViewById<View>(
                resourceId(cover.resources, "seekbar_container"),
            )
            val result = "mode=$mode;source=${describeBitmap(state.bitmap)};" +
                "host=${viewLayoutSpec(currentHost)};header=${viewLayoutSpec(currentHeader)};" +
                "cover=${viewLayoutSpec(cover)};background=${viewLayoutSpec(currentBackground)};" +
                "textInfo=${viewLayoutSpec(currentTextInfo)};title=${viewLayoutSpec(currentTitle)};" +
                "nativeCardHeight=${state.nativeCardHostHeight};backgroundForeground=" +
                (state.backgroundForegroundColor?.let { "#${Integer.toHexString(it)}" } ?: "none") +
                ";backgroundOutline=${currentBackground?.outlineProvider?.javaClass?.name ?: "none"};" +
                "progressRow=${viewLayoutSpec(currentProgressRow)};" +
                "cardText=${textContentSpec(currentHost)};progress=${progressLayoutSpecs(currentHost)}"
            if (state.lastLoggedResultSpec != result) {
                state.lastLoggedResultSpec = result
                HookRuntime.installInfo("Media cover measured result: $result")
            }
        }
        state.pendingResultLog = resultLog
        mainHandler.postDelayed(resultLog, 250L)
    }

    private fun describeBitmap(bitmap: Bitmap?): String {
        if (bitmap == null) return "null"
        if (bitmap.isRecycled) return "recycled"
        val width = bitmap.width
        val height = bitmap.height
        val ratio = if (height > 0) String.format(Locale.ROOT, "%.3f", width.toFloat() / height) else "n/a"
        val bytes = runCatching { bitmap.allocationByteCount }.getOrDefault(-1)
        return "${width}x${height};ratio=$ratio;config=${bitmap.config};density=${bitmap.density};" +
            "bytes=$bytes;mutable=${bitmap.isMutable};alpha=${bitmap.hasAlpha()}"
    }

    private fun formatRatio(value: Float): String = String.format(Locale.ROOT, "%.3f", value)

    private fun viewLayoutSpec(view: View?): String {
        if (view == null) return "none"
        val params = view.layoutParams
        val description = view.contentDescription?.toString() ?: "none"
        val parent = view.parent as? View
        val parentSpec = parent?.let { "${it.javaClass.name}#${viewIdSpec(it)}" } ?: "none"
        return "${view.javaClass.name};id=${viewIdSpec(view)};contentDescription=$description;" +
            "visibility=${view.visibility};clipToOutline=${view.clipToOutline};" +
            "outline=${view.outlineProvider?.javaClass?.name ?: "none"};parent=$parentSpec;" +
            "lp=${params?.width}x${params?.height}:${params?.javaClass?.name};" +
            "measured=${view.width}x${view.height};bounds=${view.left},${view.top}-${view.right},${view.bottom}"
    }

    private fun viewIdSpec(view: View): String = if (view.id == View.NO_ID) {
        "NO_ID"
    } else {
        runCatching { view.resources.getResourceName(view.id) }.getOrDefault(view.id.toString())
    }

    private fun textContentSpec(host: ViewGroup?): String {
        if (host == null) return "none"
        val texts = ArrayList<String>()
        fun visit(view: View) {
            if (view is TextView) {
                val text = view.text?.toString().orEmpty()
                if (text.isNotBlank()) texts += "${viewIdSpec(view)}=$text"
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(host)
        return texts.joinToString(" | ").ifEmpty { "none" }
    }

    private fun progressLayoutSpecs(host: ViewGroup?): String {
        if (host == null) return "none"
        val candidates = findProgressViews(host)
        val details = candidates.take(8).joinToString("|") { view ->
            val params = view.layoutParams
            val parent = view.parent as? View
            val parentSpec = parent?.let { "${it.javaClass.name}#${viewIdSpec(it)}" } ?: "none"
            "${viewIdSpec(view)}:${view.javaClass.name}<-${parentSpec};" +
                "lp=${params?.width}x${params?.height}:${params?.javaClass?.name};" +
                "measured=${view.width}x${view.height};bounds=${view.left},${view.top}-${view.right},${view.bottom}"
        }
        return "count=${candidates.size}[$details]"
    }

    private fun copyLayoutParams(params: ViewGroup.LayoutParams): ViewGroup.LayoutParams = when (params) {
        is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(params)
        is RelativeLayout.LayoutParams -> RelativeLayout.LayoutParams(params)
        is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params)
        else -> ViewGroup.LayoutParams(params)
    }

    private fun requestLayoutFrom(view: View?) {
        // View.requestLayout() propagates through its ViewParent chain; explicitly
        // requesting every ancestor only repeats the same traversal.
        view?.requestLayout()
    }

    private fun setTextInfoPosition(textInfo: View?, state: CoverState, background: Boolean): Boolean {
        val textInfoView = textInfo ?: return false
        val current = textInfoView.layoutParams as? RelativeLayout.LayoutParams ?: return false
        val original = state.nativeTextInfoLayoutParams ?: return false
        val params = RelativeLayout.LayoutParams(original)
        if (background) {
            params.removeRule(RelativeLayout.END_OF)
            params.removeRule(RelativeLayout.RIGHT_OF)
            params.addRule(RelativeLayout.ALIGN_PARENT_START)
            params.marginStart = dimensionOrDp(
                textInfoView.resources,
                "media_card_album_art_margin_start",
                16f,
            )
        }
        if (sameRelativeLayoutParams(current, params)) return false
        textInfoView.layoutParams = params
        return true
    }

    /** Repair only the background alignment and margin, preserving OEM-updated size and other rules. */
    private fun repairBackgroundTextInfoPosition(textInfo: View): Boolean {
        val current = textInfo.layoutParams as? RelativeLayout.LayoutParams ?: return false
        val params = RelativeLayout.LayoutParams(current).apply {
            removeRule(RelativeLayout.END_OF)
            removeRule(RelativeLayout.RIGHT_OF)
            addRule(RelativeLayout.ALIGN_PARENT_START)
            marginStart = dimensionOrDp(
                textInfo.resources,
                "media_card_album_art_margin_start",
                16f,
            )
        }
        if (sameRelativeLayoutParams(current, params)) return false
        textInfo.layoutParams = params
        return true
    }

    private fun sameRelativeLayoutParams(
        first: RelativeLayout.LayoutParams,
        second: RelativeLayout.LayoutParams,
    ): Boolean = first.width == second.width && first.height == second.height &&
        first.leftMargin == second.leftMargin && first.topMargin == second.topMargin &&
        first.rightMargin == second.rightMargin && first.bottomMargin == second.bottomMargin &&
        first.getMarginStart() == second.getMarginStart() &&
        first.getMarginEnd() == second.getMarginEnd() &&
        first.getRules().contentEquals(second.getRules())

    private fun setCoverArtworkSource(
        cover: View,
        state: CoverState,
        highResolutionCoverBitmap: Bitmap?,
    ) {
        val useHighResolution = highResolutionCoverBitmap != null
        val bitmap = (highResolutionCoverBitmap ?: state.nativeEffectBitmap)
            ?.takeUnless { it.isRecycled }
            ?: return
        if (
            state.highResolutionArtworkApplied == useHighResolution &&
            state.coverDisplayBitmap === bitmap
        ) {
            return
        }
        val setter = runCatching {
            cover.javaClass.getDeclaredMethod("setEffectBitmap", Bitmap::class.java)
        }.getOrElse {
            HookRuntime.runtimeWarn(it) { "Media cover source switch unavailable" }
            return
        }
        try {
            bypassArtworkSourceHook.set(true)
            setter.invoke(cover, bitmap)
            state.bitmap = bitmap
            state.coverDisplayBitmap = bitmap
            state.highResolutionArtworkApplied = useHighResolution
            if (HookRuntime.isLogsEnabled()) {
                HookRuntime.installInfo(
                    "Media cover display source switched: highResolution=$useHighResolution;" +
                        "bitmap=${describeBitmap(bitmap)}",
                )
            }
        } catch (error: Throwable) {
            HookRuntime.runtimeWarn(error) { "Media cover source switch failed" }
        } finally {
            bypassArtworkSourceHook.remove()
        }
    }

    private fun applyArtworkOutline(cover: View, state: CoverState) {
        if (!state.highResolutionArtworkApplied) {
            if (cover.outlineProvider !== state.nativeCoverOutlineProvider) {
                cover.outlineProvider = state.nativeCoverOutlineProvider
            }
            if (cover.clipToOutline != state.nativeCoverClipToOutline) {
                cover.clipToOutline = state.nativeCoverClipToOutline
            }
            return
        }

        val radiusId = resourceId(cover.resources, "media_card_album_art_corner_size")
        val radius = if (radiusId != 0) {
            cover.resources.getDimension(radiusId)
        } else {
            dp(cover.resources, 10f).toFloat()
        }
        val provider = state.highResolutionCoverOutlineProvider ?: object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }.also { state.highResolutionCoverOutlineProvider = it }
        var changed = false
        if (cover.outlineProvider !== provider) {
            cover.outlineProvider = provider
            changed = true
        }
        if (!cover.clipToOutline) {
            cover.clipToOutline = true
            changed = true
        }
        if (changed) cover.invalidateOutline()
    }

    private fun createOemProcessedCoverBitmap(
        cover: View,
        state: CoverState,
        source: Bitmap,
        mode: HookFeatureConfig.MediaCoverMode,
        adaptiveWidth: Boolean,
    ): Bitmap? {
        if (source.isRecycled) return null
        val size = coverSize(cover, state, source, mode, adaptiveWidth) ?: return null
        if (size.width <= 0 || size.height <= 0) return null

        val cached = state.highResolutionCoverBitmap
        if (
            state.highResolutionCoverSource === source &&
            state.highResolutionCoverWidth == size.width &&
            state.highResolutionCoverHeight == size.height &&
            cached != null && !cached.isRecycled
        ) {
            return cached
        }

        // Keep extra source detail for OEM's rotating/shader draw path while matching
        // the exact aspect ratio of the final cover view.
        val scale = minOf(
            2f,
            source.width.toFloat() / size.width,
            source.height.toFloat() / size.height,
        ).coerceAtLeast(1f)
        val outputWidth = (size.width * scale).roundToInt()
        val outputHeight = (size.height * scale).roundToInt()
        val radiusId = resourceId(cover.resources, "media_card_album_art_corner_size")
        val radius = if (radiusId != 0) {
            cover.resources.getDimension(radiusId)
        } else {
            dp(cover.resources, 10f).toFloat()
        } * scale

        val processor = runCatching {
            val processorClass = Class.forName(
                OEM_BITMAP_PROCESSOR,
                false,
                cover.javaClass.classLoader,
            )
            processorClass.declaredMethods.firstOrNull { method ->
                val parameters = method.parameterTypes
                Modifier.isStatic(method.modifiers) &&
                    method.returnType == Bitmap::class.java &&
                    parameters.size == 4 &&
                    parameters[0] == Bitmap::class.java &&
                    parameters[1] == Float::class.javaPrimitiveType &&
                    parameters[2] == Int::class.javaPrimitiveType &&
                    parameters[3] == Int::class.javaPrimitiveType
            } ?: error("OEM rounded center-crop Bitmap processor is unavailable")
        }.getOrElse {
            HookRuntime.runtimeWarn(it) { "OEM cover bitmap processor lookup failed" }
            return null
        }

        val processed = runCatching {
            processor.invoke(null, source, radius, outputWidth, outputHeight) as? Bitmap
        }.getOrElse {
            HookRuntime.runtimeWarn(it) { "OEM cover bitmap processing failed" }
            return null
        } ?: run {
            HookRuntime.runtimeWarn { "OEM cover bitmap processor returned null" }
            return null
        }

        state.highResolutionCoverSource = source
        state.highResolutionCoverBitmap = processed
        state.highResolutionCoverWidth = size.width
        state.highResolutionCoverHeight = size.height
        if (HookRuntime.isLogsEnabled()) {
            HookRuntime.installInfo(
                "Media cover OEM crop result: source=${describeBitmap(source)};" +
                    "target=${size.width}x${size.height};output=${describeBitmap(processed)};" +
                    "radius=${radius}px;resolutionScale=$scale",
            )
        }
        return processed
    }

    private fun coverSize(
        cover: View,
        state: CoverState,
        bitmap: Bitmap?,
        mode: HookFeatureConfig.MediaCoverMode,
        adaptiveWidth: Boolean,
    ): CoverSize? {
        val height: Int
        val width: Int
        when (mode) {
            HookFeatureConfig.MediaCoverMode.SMALL -> {
                height = state.nativeHeight.takeIf { it > 0 } ?: defaultArtSize(cover.resources)
                width = if (adaptiveWidth && bitmap != null) {
                    adaptiveWidth(cover, state, bitmap, height)
                } else {
                    state.nativeWidth
                }
            }
            HookFeatureConfig.MediaCoverMode.LARGE -> {
                height = dp(cover.resources, LARGE_ART_HEIGHT_DP)
                width = if (adaptiveWidth && bitmap != null) {
                    adaptiveWidth(cover, state, bitmap, height)
                } else {
                    height
                }
            }
            else -> return null
        }
        return CoverSize(width, height)
    }

    private fun resizeCover(
        cover: View,
        state: CoverState,
        bitmap: Bitmap?,
        mode: HookFeatureConfig.MediaCoverMode,
        adaptiveWidth: Boolean,
    ): CoverSize? {
        val size = coverSize(cover, state, bitmap, mode, adaptiveWidth) ?: return null
        val params = cover.layoutParams ?: return null
        if (params.width != size.width || params.height != size.height) {
            params.width = size.width
            params.height = size.height
            cover.layoutParams = params
        }
        return size
    }

    private fun normalizeOemShaderAspect(cover: View, state: CoverState, size: CoverSize) {
        if (!state.highResolutionArtworkApplied || size.width <= 0 || size.height <= 0) return

        val aspect = size.width.toFloat() / size.height
        val uniformWidth: Float
        val uniformHeight: Float
        if (aspect >= 1f) {
            uniformWidth = 1f
            uniformHeight = 1f / aspect
        } else {
            uniformWidth = aspect
            uniformHeight = 1f
        }
        val shaderHost = cover.javaClass.superclass ?: return
        val setAspect = shaderHost.declaredMethods.firstOrNull { method ->
            val parameters = method.parameterTypes
            method.returnType == Void.TYPE && parameters.size == 2 &&
                parameters[0] == Float::class.javaPrimitiveType &&
                parameters[1] == Float::class.javaPrimitiveType
        }
        if (setAspect == null) {
            if (HookRuntime.isLogsEnabled()) {
                val unavailable = "unavailable:${shaderHost.name}"
                if (state.lastLoggedOemShaderAspect != unavailable) {
                    state.lastLoggedOemShaderAspect = unavailable
                    HookRuntime.installWarn("OEM cover shader aspect method is unavailable: $unavailable")
                }
            }
            return
        }

        val update = runCatching {
            setAspect.invoke(cover, uniformWidth, uniformHeight)
        }
        if (update.isFailure) {
            HookRuntime.runtimeWarn(
                update.exceptionOrNull() ?: IllegalStateException("Unknown shader update error"),
            ) { "OEM cover shader aspect update failed" }
            return
        }

        if (HookRuntime.isLogsEnabled()) {
            val spec = "target=${size.width}x${size.height};viewRatio=${formatRatio(aspect)};" +
                "uniform=${formatRatio(uniformWidth)}x${formatRatio(uniformHeight)}"
            if (state.lastLoggedOemShaderAspect != spec) {
                state.lastLoggedOemShaderAspect = spec
                HookRuntime.installInfo("Media cover OEM shader aspect normalized: $spec")
            }
        }
    }

    private fun adaptiveWidth(cover: View, state: CoverState, bitmap: Bitmap, height: Int): Int {
        if (bitmap.width <= 0 || bitmap.height <= 0) return height
        val widthFromArtwork = (height * bitmap.width.toFloat() / bitmap.height.toFloat()).roundToInt()
        return widthFromArtwork.coerceIn(
            dp(cover.resources, MIN_ADAPTIVE_WIDTH_DP),
            maxAdaptiveWidth(cover, state, height),
        )
    }

    private fun watchAdaptiveWidthAfterMeasure(cover: View, state: CoverState) {
        val parent = cover.parent as? ViewGroup ?: return
        val previousParent = state.adaptiveWidthParent?.get()
        val previousListener = state.adaptiveWidthLayoutListener
        if (parent.width > 0) {
            if (previousParent != null && previousListener != null) {
                previousParent.removeOnLayoutChangeListener(previousListener)
            }
            state.adaptiveWidthParent = null
            state.adaptiveWidthLayoutListener = null
            return
        }
        if (previousParent === parent && previousListener != null) return
        if (previousParent != null && previousListener != null) {
            previousParent.removeOnLayoutChangeListener(previousListener)
        }

        val coverReference = WeakReference(cover)
        lateinit var listener: View.OnLayoutChangeListener
        listener = View.OnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
            val measuredParent = changedView as? ViewGroup
            if (measuredParent != null && measuredParent.width > 0) {
                measuredParent.removeOnLayoutChangeListener(listener)
                if (state.adaptiveWidthLayoutListener === listener) {
                    state.adaptiveWidthParent = null
                    state.adaptiveWidthLayoutListener = null
                }
                coverReference.get()?.let { measuredCover ->
                    mainHandler.post {
                        applyAppearance(measuredCover, state, HookRuntime.settingsSnapshot())
                    }
                }
            }
        }
        state.adaptiveWidthParent = WeakReference(parent)
        state.adaptiveWidthLayoutListener = listener
        parent.addOnLayoutChangeListener(listener)
    }

    private fun maxAdaptiveWidth(cover: View, state: CoverState, targetHeight: Int): Int {
        val row = cover.parent as? ViewGroup ?: return Int.MAX_VALUE
        if (row.width <= 0) return Int.MAX_VALUE

        val icon = row.findViewById<View>(resourceId(cover.resources, "img_top_right_icon"))
        val iconParams = icon?.layoutParams as? ViewGroup.MarginLayoutParams
        val artParams = cover.layoutParams as? ViewGroup.MarginLayoutParams
        val titleParams = state.nativeTextInfoLayoutParams
        val iconWidth = icon?.width?.takeIf { it > 0 } ?: dp(cover.resources, 24f)
        val iconEndMargin = iconParams?.marginEnd ?: dp(cover.resources, 16f)
        val artStartMargin = artParams?.marginStart ?: dp(cover.resources, 16f)
        val titleStartMargin = titleParams?.marginStart ?: dp(cover.resources, 6f)
        val minimumTitleWidth = dp(cover.resources, MIN_TITLE_WIDTH_DP)
        return (row.width - iconWidth - iconEndMargin - artStartMargin - titleStartMargin - minimumTitleWidth)
            .coerceAtLeast(dp(cover.resources, MIN_ADAPTIVE_WIDTH_DP))
    }

    private fun findCardHost(view: View): ViewGroup? {
        val backgroundId = resourceId(view.resources, "bg_1")
        val titleId = resourceId(view.resources, "title_text_container")
        var parent: ViewParent? = view.parent
        var fallback: ViewGroup? = null
        while (parent is ViewGroup) {
            val hasCardViews = parent.findViewById<View>(backgroundId) != null &&
                parent.findViewById<View>(titleId) != null
            if (hasCardViews) {
                if (parent.javaClass.name == MEDIA_CARD_PAGE_ROOT) return parent
                if (fallback == null) fallback = parent
            }
            parent = parent.parent
        }
        return fallback
    }

    private fun resourceId(resources: Resources, name: String): Int =
        resources.getIdentifier(name, "id", PLUGIN_PACKAGE)

    private fun defaultArtSize(resources: Resources): Int {
        val id = resources.getIdentifier(DEFAULT_ART_SIZE, "dimen", PLUGIN_PACKAGE)
        return if (id != 0) resources.getDimensionPixelSize(id) else dp(resources, 44f)
    }

    private fun dimensionOrDp(resources: Resources, name: String, fallbackDp: Float): Int {
        val id = resources.getIdentifier(name, "dimen", PLUGIN_PACKAGE)
        return if (id != 0) resources.getDimensionPixelSize(id) else dp(resources, fallbackDp)
    }

    private fun dp(resources: Resources, value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).roundToInt()
}
