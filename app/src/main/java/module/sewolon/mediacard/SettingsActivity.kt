package module.sewolon.mediacard

import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import java.util.concurrent.TimeUnit
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class SettingsActivity : ComponentActivity() {
    private val nativeMediaControls = mutableStateOf(NativeMediaControlsUiState())
    private val nativeMediaLock = Any()
    private var settingsPageVisible = false
    private var nativeCommandGeneration = 0L
    private var nativeCommandProcess: Process? = null
    private var nativeCommandThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeController = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
            MiuixTheme(controller = themeController) {
                val darkTheme = isSystemInDarkTheme()
                val statusBarColor = MiuixTheme.colorScheme.background
                val view = LocalView.current
                SideEffect {
                    window.statusBarColor = statusBarColor.toArgb()
                    window.navigationBarColor = statusBarColor.toArgb()
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !darkTheme
                    insetsController.isAppearanceLightNavigationBars = !darkTheme
                }
                MediaCardApp(
                    nativeMediaControls = nativeMediaControls.value,
                    onNativeMediaControlsChange = ::setNativeMediaControlsEnabled,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        synchronized(nativeMediaLock) { settingsPageVisible = true }
        refreshNativeMediaControls()
    }

    override fun onStop() {
        stopNativeMediaCommands()
        super.onStop()
    }

    private fun refreshNativeMediaControls() {
        nativeMediaControls.value = NativeMediaControlsUiState(loading = true)
        startNativeMediaCommand { generation ->
            val root = runRootCommand(generation, "id -u") ?: return@startNativeMediaCommand
            val hasRoot = root.exitCode == 0 && root.output.trim() == "0"
            if (!hasRoot) {
                publishNativeMediaControls(
                    generation,
                    NativeMediaControlsUiState(rootAvailable = false),
                )
                return@startNativeMediaCommand
            }

            val setting = runRootCommand(generation, "settings get global qs_media_controls")
                ?: return@startNativeMediaCommand
            publishNativeMediaControls(
                generation,
                NativeMediaControlsUiState(
                    rootAvailable = true,
                    enabled = parseNativeMediaControlsValue(setting).let { value ->
                        when (value) {
                            0 -> true
                            1 -> false
                            else -> null
                        }
                    },
                ),
            )
        }
    }

    private fun setNativeMediaControlsEnabled(enabled: Boolean) {
        val current = nativeMediaControls.value
        if (!settingsPageVisible) return
        if (current.rootAvailable != true) {
            if (current.rootAvailable == false) {
                Toast.makeText(this, R.string.settings_native_media_controls_no_root, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (current.loading || current.enabled == null) return

        val value = if (enabled) 0 else 1
        nativeMediaControls.value = current.copy(loading = true)
        startNativeMediaCommand { generation ->
            val put = runRootCommand(generation, "settings put global qs_media_controls $value")
                ?: return@startNativeMediaCommand
            if (put.exitCode != 0) {
                val root = runRootCommand(generation, "id -u") ?: return@startNativeMediaCommand
                val hasRoot = root.exitCode == 0 && root.output.trim() == "0"
                val readBack = if (hasRoot) {
                    runRootCommand(generation, "settings get global qs_media_controls")
                } else {
                    null
                }
                val actual = readBack?.let(::parseNativeMediaControlsValue)
                publishNativeMediaControls(
                    generation,
                    NativeMediaControlsUiState(
                        rootAvailable = hasRoot,
                        enabled = when (actual) {
                            0 -> true
                            1 -> false
                            else -> null
                        },
                    ),
                    toastMessage = getString(
                        if (hasRoot) R.string.settings_native_media_controls_command_failed
                        else R.string.settings_native_media_controls_no_root,
                    ),
                )
                return@startNativeMediaCommand
            }
            val readBack = runRootCommand(generation, "settings get global qs_media_controls")
                ?: return@startNativeMediaCommand
            val actual = parseNativeMediaControlsValue(readBack)
            val applied = actual == value
            publishNativeMediaControls(
                generation,
                NativeMediaControlsUiState(
                    rootAvailable = true,
                    enabled = when (actual) {
                        0 -> true
                        1 -> false
                        else -> null
                    },
                ),
                toastMessage = if (applied) {
                    getString(R.string.settings_native_media_controls_applied, value)
                } else {
                    getString(R.string.settings_native_media_controls_command_failed)
                },
            )
        }
    }

    private fun startNativeMediaCommand(task: (Long) -> Unit) {
        val generation = synchronized(nativeMediaLock) {
            if (!settingsPageVisible) return
            nativeCommandGeneration += 1L
            nativeCommandProcess?.destroyForcibly()
            nativeCommandProcess = null
            nativeCommandThread?.interrupt()
            nativeCommandGeneration
        }
        val thread = Thread({
            try {
                task(generation)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                publishNativeMediaControls(
                    generation,
                    NativeMediaControlsUiState(rootAvailable = true),
                    toastMessage = getString(R.string.settings_native_media_controls_command_failed),
                )
            } finally {
                synchronized(nativeMediaLock) {
                    if (nativeCommandThread === Thread.currentThread()) nativeCommandThread = null
                }
            }
        }, "NativeMediaControlsSettings").apply { isDaemon = true }
        synchronized(nativeMediaLock) {
            if (!settingsPageVisible || nativeCommandGeneration != generation) return
            nativeCommandThread = thread
        }
        thread.start()
    }

    private fun runRootCommand(generation: Long, command: String): RootCommandResult? {
        if (!isNativeCommandCurrent(generation)) return null
        val process = runCatching {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrElse { error ->
            return RootCommandResult(exitCode = -1, output = error.message.orEmpty())
        }
        synchronized(nativeMediaLock) {
            if (!settingsPageVisible || nativeCommandGeneration != generation) {
                process.destroyForcibly()
                return null
            }
            nativeCommandProcess = process
        }
        return try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                RootCommandResult(exitCode = -1, output = "timeout")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                RootCommandResult(exitCode = process.exitValue(), output = output)
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            null
        } finally {
            synchronized(nativeMediaLock) {
                if (nativeCommandProcess === process) nativeCommandProcess = null
            }
        }
    }

    private fun publishNativeMediaControls(
        generation: Long,
        state: NativeMediaControlsUiState,
        toastMessage: String? = null,
    ) {
        runOnUiThread {
            if (!isNativeCommandCurrent(generation)) return@runOnUiThread
            nativeMediaControls.value = state
            toastMessage?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun isNativeCommandCurrent(generation: Long): Boolean = synchronized(nativeMediaLock) {
        settingsPageVisible && nativeCommandGeneration == generation
    }

    private fun stopNativeMediaCommands() {
        synchronized(nativeMediaLock) {
            settingsPageVisible = false
            nativeCommandGeneration += 1L
            nativeCommandProcess?.destroyForcibly()
            nativeCommandProcess = null
            nativeCommandThread?.interrupt()
            nativeCommandThread = null
        }
    }

    private fun parseNativeMediaControlsValue(result: RootCommandResult): Int? =
        if (result.exitCode == 0) result.output.trim().toIntOrNull()?.takeIf { it == 0 || it == 1 } else null

}

private data class NativeMediaControlsUiState(
    val rootAvailable: Boolean? = null,
    /** 0 means enabled, 1 means disabled; null means the system value could not be read. */
    val enabled: Boolean? = null,
    val loading: Boolean = false,
)

private data class RootCommandResult(val exitCode: Int, val output: String)

@Composable
private fun MediaCardApp(
    nativeMediaControls: NativeMediaControlsUiState,
    onNativeMediaControlsChange: (Boolean) -> Unit,
) {
    var connection by remember { mutableStateOf(SettingsService.Connection()) }
    var hookSettings by remember { mutableStateOf(HookFeatureConfig.Settings()) }
    val callback: (SettingsService.Connection) -> Unit = { next ->
        connection = next
        hookSettings = HookFeatureConfig.read(next.preferences)
    }
    DisposableEffect(Unit) {
        SettingsService.connect(callback)
        onDispose { SettingsService.disconnect(callback) }
    }

    val prefs = connection.preferences
    DisposableEffect(prefs) {
        if (prefs == null) return@DisposableEffect onDispose { }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, _ ->
            hookSettings = HookFeatureConfig.read(changed)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        hookSettings = HookFeatureConfig.read(prefs)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val updateHookSetting: (String, Boolean) -> Unit = { key, value ->
        hookSettings = HookFeatureConfig.withValue(hookSettings, key, value)
        runCatching { prefs?.edit()?.putBoolean(key, value)?.apply() }
    }

    val appBarState = rememberTopAppBarState()
    val scrollBehavior = MiuixScrollBehavior(state = appBarState)
    val title = stringResource(R.string.settings_title)
    val appBarSurface = MiuixTheme.colorScheme.surface

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = title,
                largeTitle = title,
                color = appBarSurface,
                scrollBehavior = scrollBehavior,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) { padding ->
        SettingsPage(
            modifier = Modifier.fillMaxSize().padding(padding),
            prefs = prefs,
            settings = hookSettings,
            onSettingChange = updateHookSetting,
            nativeMediaControls = nativeMediaControls,
            onNativeMediaControlsChange = onNativeMediaControlsChange,
        )
    }
}

@Composable
private fun SettingsPage(
    modifier: Modifier = Modifier,
    prefs: SharedPreferences?,
    settings: HookFeatureConfig.Settings,
    onSettingChange: (String, Boolean) -> Unit,
    nativeMediaControls: NativeMediaControlsUiState,
    onNativeMediaControlsChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val performanceWarning = stringResource(R.string.logs_performance_warning)
    val noRootMessage = stringResource(R.string.settings_native_media_controls_no_root)
    val settingUnavailableMessage = stringResource(R.string.settings_native_media_controls_unavailable)
    var launcherHidden by remember { mutableStateOf(isLauncherHidden(context)) }

    LazyColumn(
        modifier = modifier.fillMaxSize().scrollEndHaptic().overScrollVertical(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = true,
        overscrollEffect = null,
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                SettingsToggle(
                    titleId = R.string.settings_master,
                    summaryId = R.string.settings_master_summary,
                    checked = settings.master,
                    enabled = prefs != null,
                    onChange = { onSettingChange(HookFeatureConfig.MASTER, it) },
                )
                SettingsToggle(
                    titleId = R.string.settings_swipe,
                    summaryId = R.string.settings_swipe_summary,
                    checked = settings.swipe,
                    enabled = prefs != null && settings.master,
                    onChange = { onSettingChange(HookFeatureConfig.SWIPE, it) },
                )
                SettingsToggle(
                    titleId = R.string.settings_native_media_controls,
                    summaryId = R.string.settings_native_media_controls_summary,
                    checked = nativeMediaControls.enabled == true,
                    enabled = nativeMediaControls.rootAvailable == true &&
                        nativeMediaControls.enabled != null && !nativeMediaControls.loading,
                    disabledClick = {
                        when {
                            nativeMediaControls.rootAvailable == false ->
                                Toast.makeText(context, noRootMessage, Toast.LENGTH_LONG).show()
                            nativeMediaControls.rootAvailable == true &&
                                nativeMediaControls.enabled == null && !nativeMediaControls.loading ->
                                Toast.makeText(context, settingUnavailableMessage, Toast.LENGTH_LONG).show()
                        }
                    },
                    onChange = onNativeMediaControlsChange,
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                SettingsToggle(
                    titleId = R.string.settings_logs,
                    summaryId = R.string.settings_logs_summary,
                    checked = settings.logsEnabled,
                    enabled = prefs != null,
                    onChange = { enabled ->
                        val wasEnabled = settings.logsEnabled
                        onSettingChange(HookFeatureConfig.LOGS, enabled)
                        if (enabled && !wasEnabled) {
                            Toast.makeText(context, performanceWarning, Toast.LENGTH_LONG).show()
                        }
                    },
                )
                SettingsToggle(
                    titleId = R.string.settings_hide_launcher,
                    summaryId = R.string.settings_hide_launcher_summary,
                    checked = launcherHidden,
                    enabled = true,
                    onChange = { hidden ->
                        if (setLauncherHidden(context, hidden)) launcherHidden = hidden
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    titleId: Int,
    summaryId: Int,
    checked: Boolean,
    enabled: Boolean,
    disabledClick: (() -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    val clickable = enabled || disabledClick != null
    BasicComponent(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled || disabledClick == null) 1f else 0.48f),
        title = stringResource(titleId),
        summary = stringResource(summaryId),
        enabled = clickable,
        onClick = {
            if (enabled) onChange(!checked) else disabledClick?.invoke()
        },
        endActions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = checked,
                    onCheckedChange = { checkedValue ->
                        if (enabled) onChange(checkedValue) else disabledClick?.invoke()
                    },
                    enabled = clickable,
                    modifier = Modifier.size(width = 48.dp, height = 32.dp),
                )
            }
        },
    )
}

private fun launcherComponent(context: android.content.Context) =
    ComponentName(context.packageName, "${context.packageName}.Launcher")

private fun isLauncherHidden(context: android.content.Context): Boolean =
    context.packageManager.getComponentEnabledSetting(launcherComponent(context)) ==
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED

private fun setLauncherHidden(context: android.content.Context, hidden: Boolean): Boolean = runCatching {
    context.packageManager.setComponentEnabledSetting(
        launcherComponent(context),
        if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        PackageManager.DONT_KILL_APP,
    )
}.isSuccess

