package id.xydesk.remote.ui

import id.xydesk.remote.core.SessionState
import id.xydesk.remote.ui.input.XyStickMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import id.xydesk.remote.core.RdpOptions
import id.xydesk.remote.core.SmartResolution
import id.xydesk.remote.ui.components.XyGlassTile
import id.xydesk.remote.ui.components.XyDialog
import id.xydesk.remote.ui.components.XyField
import id.xydesk.remote.ui.components.XyIcons
import id.xydesk.remote.ui.components.XyNoticeState
import id.xydesk.remote.ui.components.XyOverlay
import id.xydesk.remote.ui.components.XyPillButton
import id.xydesk.remote.ui.components.XySegmented
import id.xydesk.remote.ui.components.XySlider
import id.xydesk.remote.ui.components.XyToggleRow
import id.xydesk.remote.ui.components.xyGlass
import id.xydesk.remote.ui.theme.XyPill
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Warna tombol HUD tetap gelap-transparan karena duduk di atas desktop remote;
 * panel, banner editor, dan dialog mengikuti tema app.
 */
internal fun updateHudEditorDraft(current: HudKey?, updated: HudKey): HudKey? =
    if (current?.id == updated.id) updated else current

/** Tab di panel sesi — SATU panel, empat seksi jelas. Dulu dua panel kiri/kanan tanpa label: nobody tahu isinya apa. */
private enum class PanelTab(val id: String, val en: String) {
    SCREEN("Layar", "Screen"),
    INPUT("Input", "Input"),
    BUTTONS("Tombol", "Buttons"),
    SESSION("Sesi", "Session"),
}

/**
 * Kontrol sesi.
 *
 * Model (ronde 8): satu handle di tepi kanan-atas membuka SATU panel bertab —
 * Layar (zoom/resolusi/orientasi), Input (mode/keyboard/clipboard/pointer),
 * Tombol (editor tombol HUD), Sesi (screenshot/putus/info teknis).
 * Rail kanan-bawah tetap terlihat untuk keyboard, monitor, putus, dan toggle
 * visibilitas HUD; auto-hide hanya memengaruhi tombol overlay yang dapat dipindah.
 */
@Composable
fun SessionControls(
    deviceId: String,
    hostLabel: String,
    statusText: String,
    remoteSize: String,
    zoomPercent: Int,
    remoteDpi: Int,
    telemetry: id.xydesk.remote.core.TelemetrySample = id.xydesk.remote.core.TelemetrySample.EMPTY,
    pointerScreen: Offset,
    pointerVisible: Boolean,
    remoteCursor: RemoteCursor? = null,
    zoom: Float = 1f,
    inputMode: InputMode,
    externalInputDeviceConnected: Boolean = false,
    onInputModeChange: (InputMode) -> Unit,
    keys: List<HudKey>,
    onKeysChange: (List<HudKey>) -> Unit,
    latchedKeyIds: Set<String>,
    onKeyEdited: (HudKey) -> Unit,
    onDeleteKey: (HudKey) -> Unit,
    mappingMode: Boolean,
    onMappingModeChange: (Boolean) -> Unit,
    onPhase: (HudKey, HudPhase) -> Unit,
    onScrollUnits: (Int) -> Unit,
    /** Sumbu joystick HUD (HudKind.PAD_STICK), -1..1 per sumbu. */
    onStickAxis: (Float, Float) -> Unit = { _, _ -> },
    /** Stik KANAN preset gamepad selalu jadi pointer, apa pun mode stik. */
    onStickPointer: (Float, Float) -> Unit = { _, _ -> },
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onZoomActual: () -> Unit,
    onZoomScale: (Float) -> Unit,
    onRemoteDpiChange: (Int) -> Unit,
    onDisplayRefreshPreferenceChange: (Int) -> Unit = {},
    recordingLabel: String = "Record PC",
    recordingStatus: String? = null,
    recordingActionEnabled: Boolean = true,
    recordingActive: Boolean = false,
    stickModeProvider: () -> XyStickMode = { XyStickMode.POINTER },
    onStickModeChange: (XyStickMode) -> Unit = {},
    onToggleRecording: () -> Unit = {},
    onScreenshot: () -> Unit,
    onDisconnect: () -> Unit,
    onGoHome: () -> Unit = {},
    onPointerVisibilityChange: (Boolean) -> Unit,
    onResetCluster: () -> Unit,
    onRotationChange: (String) -> Unit,
    onResolutionChange: (String) -> Unit,
    onOpenKeyboard: () -> Unit = {},
    onOpenHome: () -> Unit = {},
    lastClipboard: String? = null,
    clipboardSyncEnabled: Boolean = false,
    onSendText: (String) -> Unit,
    onSendRemoteClipboardText: (String) -> Boolean = { false },
    activeUsername: String? = null,
    consoleAdminMode: Boolean = false,
    onSwitchConsoleMode: (Boolean) -> Unit = {},
    onSwitchUserSession: (String, String?, String?) -> Unit = { _, _, _ -> },
    swapMouseButtons: Boolean = false,
    onSwapMouseButtonsChange: (Boolean) -> Unit = {},
    onLockRemotePc: () -> Unit = {},
    onActivatePrivacyCurtain: () -> Unit = {},
    onGyroMouseChanged: (Boolean) -> Unit = {},
    onOverlayActiveChange: (Boolean) -> Unit = {},
    onRegisterBackHandler: ((() -> Boolean)?) -> Unit = {},
    fpsGameMode: Boolean = false,
    rawInputUdpAlive: Boolean = false,
    onFpsGameModeChange: (Boolean) -> Unit = {},
    voiceMicEnabled: Boolean = false,
    onToggleVoiceMic: () -> Unit = {},
    coreInfo: List<String> = emptyList(),
    notice: XyNoticeState,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val view = androidx.compose.ui.platform.LocalView.current
    val prefs = remember { SessionPrefs(context) }

    // Tangkap otomatis tampilan desktop / wallpaper sesi RDP yang sedang berjalan
    // supaya kartu Preview Sesi di Beranda (Home) sesuai dengan wallpaper/desktop PC/RDP pengguna.
    LaunchedEffect(deviceId) {
        delay(2500L)
        SessionPreviewStore.captureFromActiveRdpSessions(context, force = true)
        delay(5500L)
        SessionPreviewStore.captureFromActiveRdpSessions(context, force = true)
        while (true) {
            delay(15_000L)
            SessionPreviewStore.captureFromActiveRdpSessions(context, force = false)
        }
    }
    var scrollPillOpen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var monitorGridOpen by remember { mutableStateOf(false) }
    var railMenuOpen by remember { mutableStateOf(false) }
    var drawerMode by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(PanelTab.SCREEN) }
    var pickerOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HudKey?>(null) }
    var pointerSize by remember { mutableFloatStateOf(prefs.pointerSize) }
    var pointerStyle by remember { mutableStateOf(prefs.pointerStyle) }
    var pointerSensitivity by remember { mutableFloatStateOf(prefs.pointerSensitivity) }
    var pointerAcceleration by remember { mutableStateOf(prefs.pointerAcceleration) }
    var inertialScroll by remember { mutableStateOf(prefs.inertialScroll) }
    var edgeScrollZone by remember { mutableStateOf(prefs.edgeScrollZone) }
    var gamepadEnabled by remember { mutableStateOf(prefs.gamepadEnabled) }
    // Mode stick dibaca lewat provider karena nilai sesungguhnya hidup di
    // activity (dipakai mapper dari luar komposisi), bukan di prefs saja.
    var stickModeUi by remember { mutableStateOf(stickModeProvider()) }
    var gyroMouseEnabled by remember { mutableStateOf(prefs.gyroMouseEnabled) }
    var showTelemetryPill by remember { mutableStateOf(prefs.showTelemetryPill) }
    var hudProfile by remember(deviceId) { mutableStateOf(prefs.hudProfile(deviceId)) }
    val pcConnectMode = remember(deviceId) { RdpOptions.of(context, deviceId).pcConnectMode }
    var haptics by remember { mutableStateOf(prefs.haptics) }
    var autoFit by remember { mutableStateOf(prefs.autoFit) }
    var plate by remember { mutableStateOf(prefs.hudPlate) }
    var hudOpacity by remember { mutableFloatStateOf(prefs.hudOpacity) }
    var showHudButtons by remember(deviceId) { mutableStateOf(prefs.hudButtonsVisible(deviceId)) }
    var autoHideHudOnExternalInput by remember {
        mutableStateOf(prefs.autoHideHudOnExternalInput)
    }
    var forceShowHudForExternalInput by remember(deviceId) { mutableStateOf(false) }
    val hudAutomaticallyHidden = autoHideHudOnExternalInput && externalInputDeviceConnected &&
        !forceShowHudForExternalInput
    val hudButtonsVisibleNow = showHudButtons && !hudAutomaticallyHidden
    var resolution by remember { mutableStateOf(DisplayPrefs.resolution(context, deviceId)) }
    var rotation by remember { mutableStateOf(DisplayPrefs.rotation(context, deviceId)) }
    var custom by remember { mutableStateOf("") }
    var textOpen by remember { mutableStateOf(false) }
    var textValue by remember { mutableStateOf("") }
    var layoutJsonOpen by remember { mutableStateOf(false) }
    var layoutJsonValue by remember { mutableStateOf("") }
    var confirmDeleteKey by remember { mutableStateOf<HudKey?>(null) }
    var confirmResetHud by remember { mutableStateOf(false) }
    var confirmImportLayout by remember { mutableStateOf<List<HudKey>?>(null) }
    var confirmApplyPreset by remember { mutableStateOf<HudProfilePreset?>(null) }
    var confirmLockPc by remember { mutableStateOf(false) }
    var confirmResolution by remember { mutableStateOf<String?>(null) }
    var validationAlert by remember { mutableStateOf<Pair<String, String>?>(null) }



    LaunchedEffect(deviceId, externalInputDeviceConnected, autoHideHudOnExternalInput) {
        if (!externalInputDeviceConnected || !autoHideHudOnExternalInput) {
            forceShowHudForExternalInput = false
        }
    }

    val anyOverlayOpen = panelOpen || monitorGridOpen || pickerOpen || editing != null || textOpen || layoutJsonOpen ||
        confirmDeleteKey != null || confirmResetHud || confirmImportLayout != null ||
        confirmApplyPreset != null || confirmLockPc ||
        confirmResolution != null || validationAlert != null
    LaunchedEffect(anyOverlayOpen) {
        onOverlayActiveChange(anyOverlayOpen)
    }

    LaunchedEffect(
        panelOpen, monitorGridOpen, pickerOpen, editing, textOpen, layoutJsonOpen, mappingMode,
        scrollPillOpen,
        confirmDeleteKey, confirmResetHud, confirmImportLayout, confirmApplyPreset,
        confirmLockPc, confirmResolution, validationAlert,
    ) {
        onRegisterBackHandler {
            when {
                validationAlert != null -> {
                    validationAlert = null
                    true
                }
                confirmDeleteKey != null -> {
                    confirmDeleteKey = null
                    true
                }
                confirmResetHud -> {
                    confirmResetHud = false
                    true
                }
                confirmImportLayout != null -> {
                    confirmImportLayout = null
                    true
                }
                confirmApplyPreset != null -> {
                    confirmApplyPreset = null
                    true
                }
                confirmLockPc -> {
                    confirmLockPc = false
                    true
                }
                confirmResolution != null -> {
                    confirmResolution = null
                    true
                }
                editing != null -> {
                    editing = null
                    true
                }
                pickerOpen -> {
                    pickerOpen = false
                    true
                }
                layoutJsonOpen -> {
                    layoutJsonOpen = false
                    true
                }
                textOpen -> {
                    textOpen = false
                    true
                }
                scrollPillOpen -> {
                    scrollPillOpen = false
                    true
                }
                monitorGridOpen -> {
                    monitorGridOpen = false
                    true
                }
                panelOpen -> {
                    panelOpen = false
                    true
                }
                mappingMode -> {
                    onMappingModeChange(false)
                    true
                }
                else -> false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onOverlayActiveChange(false)
            onRegisterBackHandler(null)
        }
    }

    fun copyCoreInfo() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        // Laporan teknis pakai label netral bahasa supaya lampiran bug report
        // konsisten; metrik pipeline frame menyertai RTT agar laporan latensi
        // bisa langsung dipakai untuk optimasi (issue #15 B.6).
        val lines = coreInfo.toMutableList()
        if (telemetry.rttMs > 0) lines += "RTT jaringan: ${telemetry.rttMs} ms"
        if (telemetry.frameIntervalMs > 0) lines += "Interval update: ${telemetry.frameIntervalMs} ms"
        if (telemetry.pipelineLatencyMs > 0) lines += "Latensi pipeline (update->gambar): ${telemetry.pipelineLatencyMs} ms"
        if (telemetry.frameCopyMs >= 0f) lines += "Salin frame: %.1f ms".format(telemetry.frameCopyMs)
        if (telemetry.queueDepth > 0f) {
            lines += "Antrean flush: rata-rata %.1f, puncak %d".format(telemetry.queueDepth, telemetry.queuePeak)
        }
        cm?.setPrimaryClip(
            ClipData.newPlainText("XyDesk Remote", lines.joinToString("\n"))
        )
        notice.show(xyNow("Info teknis tersalin", "Technical info copied"))
    }

    fun clipboardText(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(context).toString()
    }

    fun exportHudLayoutToClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val json = HudKey.exportLayoutJson(keys)
        cm?.setPrimaryClip(ClipData.newPlainText("XyDesk HUD Layout", json))
        notice.show(xyNow("Layout tombol JSON disalin ke clipboard", "Button layout JSON copied to clipboard"))
    }

    fun importHudLayoutFromRaw(raw: String): Boolean {
        val imported = HudKey.importLayoutJson(raw, prefs.hudButtonSize)
        if (imported == null) {
            notice.show(xyNow("JSON layout tidak valid atau kosong", "Layout JSON is invalid or empty"))
            return false
        }
        onKeysChange(imported)
        notice.show(
            xyNow(
                "Layout diimpor ({0} tombol)",
                "Layout imported ({0} buttons)",
                imported.size,
            ),
        )
        return true
    }

    fun haptic() {
        if (haptics) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun toggleHudButtonVisibility() {
        if (autoHideHudOnExternalInput && externalInputDeviceConnected) {
            if (hudButtonsVisibleNow) {
                forceShowHudForExternalInput = false
            } else {
                showHudButtons = true
                prefs.setHudButtonsVisible(deviceId, true)
                forceShowHudForExternalInput = true
            }
        } else {
            showHudButtons = !showHudButtons
            prefs.setHudButtonsVisible(deviceId, showHudButtons)
        }
    }

    fun replace(updated: HudKey) {
        // Keep the editor's live draft in sync with the persisted per-device layout.
        editing = updateHudEditorDraft(editing, updated)
        val updatedKeys = keys.map { if (it.id == updated.id) updated else it }
        prefs.setCustomHudKeys(deviceId, updatedKeys)
        onKeyEdited(updated)
    }

    fun add(option: HudKeyOption) {
        if (keys.size >= 36) {
            notice.show(xyNow("Maksimal 36 tombol HUD", "Maximum 36 HUD buttons"))
            return
        }
        val width = configuration.screenWidthDp.toFloat().coerceAtLeast(1f)
        val height = configuration.screenHeightDp.toFloat().coerceAtLeast(1f)
        val size = prefs.hudButtonSize
        val maxX = (width - size).coerceAtLeast(1f)
        val maxY = (height - size).coerceAtLeast(1f)
        val railCenters = listOf(
            androidx.compose.ui.geometry.Offset(width - 32f, height - 210f),
            androidx.compose.ui.geometry.Offset(width - 32f, height - 156f),
            androidx.compose.ui.geometry.Offset(width - 32f, height - 102f),
        )
        val candidates = buildList {
            var y = 72f
            while (y <= maxY) {
                var x = 12f
                while (x <= maxX) {
                    this.add(androidx.compose.ui.geometry.Offset(x, y))
                    x += 88f
                }
                y += 64f
            }
        }
        val position = candidates.firstOrNull { candidate ->
            val cx = candidate.x + size / 2f
            val cy = candidate.y + size / 2f
            val hitsExisting = keys.any { oldKey ->
                val oldCx = oldKey.x * (width - oldKey.size).coerceAtLeast(1f) + oldKey.size / 2f
                val oldCy = oldKey.y * (height - oldKey.size).coerceAtLeast(1f) + oldKey.size / 2f
                val dx = cx - oldCx
                val dy = cy - oldCy
                val minDistance = (size + oldKey.size) / 2f + 8f
                dx * dx + dy * dy < minDistance * minDistance
            }
            val hitsRail = railCenters.any { rail ->
                val dx = cx - rail.x
                val dy = cy - rail.y
                val minDistance = size / 2f + 22f + 8f
                dx * dx + dy * dy < minDistance * minDistance
            }
            val hitsPanelHandle = kotlin.math.abs(cx - (width - 30f)) < size / 2f + 38f &&
                kotlin.math.abs(cy - 70f) < size / 2f + 60f
            !hitsExisting && !hitsRail && !hitsPanelHandle
        }
        if (position == null) {
            notice.show(xyNow("Ruang kontrol penuh — pindahkan tombol dulu", "No free control space — move a button first"))
            return
        }
        val key = HudKey(
            id = "k${java.util.UUID.randomUUID()}",
            kind = option.kind,
            label = option.label,
            keyCode = option.keyCode,
            shift = option.shift,
            combo = option.combo,
            action = option.defaultAction.takeIf { it in HudKey.allowedActions(option.kind) } ?: HudAction.TAP,
            x = (position.x / maxX).coerceIn(0f, 1f),
            y = (position.y / maxY).coerceIn(0f, 1f),
            size = size,
            macroText = option.macroText,
            macroSendEnter = option.macroSendEnter,
        )
        val updatedKeys = keys + key
        onKeysChange(updatedKeys)
        prefs.setCustomHudKeys(deviceId, updatedKeys)
        hudProfile = HudProfilePreset.CUSTOM
        prefs.setHudProfile(deviceId, HudProfilePreset.CUSTOM)
        panelOpen = false
        onMappingModeChange(true)
        notice.show(
            xyNow(
                "Tombol \"{0}\" ditambahkan — geser ke posisi yang kamu mau atau tekan Selesai.",
                "Button \"{0}\" added — drag it into position or press Done.",
                key.label,
            ),
        )
    }

    Box(Modifier.fillMaxSize().zIndex(10f)) {
        if (pointerVisible && !panelOpen && !monitorGridOpen) {
            XyPointer(
                position = pointerScreen,
                sizeDp = pointerSize,
                style = pointerStyle,
                remote = remoteCursor,
                zoom = zoom,
            )
        }

        if (showTelemetryPill && !panelOpen && !monitorGridOpen && !mappingMode) {
            LiveTelemetryPill(
                telemetry = telemetry,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(start = 8.dp, top = 8.dp)
                    .zIndex(19f),
            )
        }

        // Hanya tombol HUD yang ikut auto-hide/transparansi; pointer, menu, dan rail
        // (keyboard, monitor, putus, serta toggle HUD) tetap terjangkau.
        if (hudButtonsVisibleNow) {
            Box(Modifier.fillMaxSize().alpha(hudOpacity)) {
                HudKeyLayer(
                    keys = keys,
                    mappingMode = mappingMode,
                    plate = plate,
                    latchedKeys = latchedKeyIds,
                    onMove = { id, x, y ->
                        val moved = keys.map { if (it.id == id) it.copy(x = x, y = y) else it }
                        onKeysChange(moved)
                        prefs.setCustomHudKeys(deviceId, moved)
                    },
                    onPhase = { key, phase ->
                        if (phase != HudPhase.UP) haptic()
                        onPhase(key, phase)
                    },
                    scrollSpeed = prefs.scrollSpeed,
                    onScrollUnits = onScrollUnits,
                    onEdit = { editing = it },
                    onStickAxis = { stickKey, x, y ->
                        // Stik kanan gamepad = mouse (permintaan pemilik:
                        // "analog kok cuma wasd"); stik kiri ikut mode.
                        if (stickKey.pad && stickKey.label == "R") {
                            if (x != 0f || y != 0f) onStickPointer(x, y)
                        } else {
                            onStickAxis(x, y)
                        }
                    },
                    stickMode = stickModeUi,
                )
            }
        }

        // Indikator rekaman sengaja ditaruh DI LUAR syarat !panelOpen: status
        // "sedang merekam" tidak boleh hilang hanya karena pengguna membuka
        // menu. Tidak ada clickable, jadi tidak menyerap sentuhan layar remote.
        if (recordingActive) {
            RecordingIndicator(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(start = 10.dp, top = 10.dp)
                    .zIndex(24f),
            )
        }

        if (scrollPillOpen && !panelOpen && !monitorGridOpen && !mappingMode) {
            SessionScrollPill(
                plate = plate,
                scrollSpeed = prefs.scrollSpeed,
                onScrollUnits = onScrollUnits,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .displayCutoutPadding()
                    .padding(end = 68.dp)
                    .zIndex(23f),
            )
        }

        // Rail diminimalkan atas permintaan pemilik (2026-10-10): hanya dua
        // tombol tetap — pembuka menu bubble (panah atas) dan keyboard di
        // paling bawah. Sisanya pindah ke dalam bubble popup.
        if (!panelOpen && !monitorGridOpen && !mappingMode) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .padding(end = 10.dp, bottom = 18.dp)
                    .zIndex(24f),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Bubble rail muncul rapi di sebelah kiri tombol panah & keyboard
                // sehingga tidak mendorong tombol keyboard ke bawah dan tidak menutupi handle MENU di kanan atas.
                AnimatedVisibility(
                    visible = railMenuOpen,
                    enter = fadeIn(androidx.compose.animation.core.tween(140)) +
                        scaleIn(
                            androidx.compose.animation.core.tween(230, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            initialScale = 0.25f,
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.85f),
                        ),
                    exit = fadeOut(androidx.compose.animation.core.tween(120)) +
                        scaleOut(
                            androidx.compose.animation.core.tween(170, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                            targetScale = 0.2f,
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.85f),
                        ),
                ) {
                    val railOpts = remember(deviceId, railMenuOpen) { id.xydesk.remote.core.RdpOptions.of(context, deviceId) }
                    var livelySyncEnabled by remember(railOpts) {
                        mutableStateOf(railOpts.desktopWallpaper && railOpts.desktopComposition)
                    }
                    Column(
                        Modifier
                            .widthIn(min = 224.dp, max = 262.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xF5101013), RoundedCornerShape(18.dp))
                            .border(1.2.dp, Color(0xFF3C3C42), RoundedCornerShape(18.dp))
                            .padding(horizontal = 9.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = xy("AKSI CEPAT OVERLAY", "QUICK OVERLAY ACTIONS"),
                                color = Color(0xFFF3F3F5),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.9.sp,
                            )
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF232327))
                                    .border(1.dp, Color(0xFF3C3C42), RoundedCornerShape(50))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "RAIL",
                                    color = Color(0xFFE4E4E8),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        RailMenuItem(
                            icon = XyIcons.ScrollSlide,
                            label = xy("Kontrol scroll", "Scroll control"),
                            active = scrollPillOpen,
                            badge = if (scrollPillOpen) "ON" else "OFF",
                        ) {
                            scrollPillOpen = !scrollPillOpen
                        }
                        RailMenuItem(
                            icon = if (hudButtonsVisibleNow) XyIcons.Eye else XyIcons.EyeOff,
                            label = xy("Overlay tombol HUD", "HUD button overlay"),
                            active = hudButtonsVisibleNow,
                            badge = if (hudButtonsVisibleNow) "ON" else "OFF",
                        ) {
                            toggleHudButtonVisibility()
                        }
                        RailMenuItem(
                            icon = if (voiceMicEnabled) XyIcons.VolumeOn else XyIcons.VolumeOff,
                            label = xy("Voice / Mic Party", "Voice / Mic Party"),
                            active = voiceMicEnabled,
                            badge = if (voiceMicEnabled) "ON" else "OFF",
                        ) {
                            onToggleVoiceMic()
                        }
                        val floatingChatHidden = InAppLiveNotificationBus.floatingOverlayHidden
                        RailMenuItem(
                            icon = XyIcons.Chat,
                            label = xy("Floating Chat Global", "Global Floating Chat"),
                            active = !floatingChatHidden,
                            badge = if (!floatingChatHidden) "ON" else "OFF",
                        ) {
                            val nextHidden = !floatingChatHidden
                            InAppLiveNotificationBus.setFloatingHidden(context, nextHidden)
                            notice.show(
                                if (nextHidden) {
                                    xyNow("Floating Chat disembunyikan dari layar sesi", "Floating Chat hidden from session screen")
                                } else {
                                    xyNow("Floating Chat ditampilkan kembali", "Floating Chat restored")
                                },
                            )
                        }
                        RailMenuItem(
                            icon = XyIcons.Star,
                            label = xy("Lively Wallpaper 60Hz", "Lively Wallpaper 60Hz"),
                            active = livelySyncEnabled,
                            badge = if (livelySyncEnabled) "SYNC" else "OFF",
                        ) {
                            val next = !livelySyncEnabled
                            livelySyncEnabled = next
                            val updated = railOpts.copy(
                                desktopWallpaper = next,
                                desktopComposition = next,
                                asyncUpdate = true,
                            )
                            updated.write(context, deviceId)
                            notice.show(
                                if (next) {
                                    xyNow("Lively Live Wallpaper 60 FPS Sync diaktifkan (Anti-Stuck)", "Lively Live Wallpaper 60 FPS Sync enabled (Anti-Stuck)")
                                } else {
                                    xyNow("Sinkronisasi Live Wallpaper dimatikan", "Live Wallpaper sync disabled")
                                },
                            )
                        }
                        RailMenuItem(
                            icon = XyIcons.Monitor,
                            label = xy("Monitor & multi-sesi", "Monitors & sessions"),
                        ) {
                            railMenuOpen = false
                            monitorGridOpen = true
                        }
                        RailMenuItem(
                            icon = XyIcons.Home,
                            label = xy("Ke beranda (sesi jalan)", "Go home (keep alive)"),
                        ) {
                            railMenuOpen = false
                            onGoHome()
                        }
                        RailMenuItem(
                            icon = XyIcons.Power,
                            label = xy("Putuskan sesi", "Disconnect"),
                            danger = true,
                        ) {
                            railMenuOpen = false
                            onDisconnect()
                        }
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RailButton(
                        icon = if (railMenuOpen) XyIcons.ChevronDown else XyIcons.ChevronUp,
                        active = railMenuOpen,
                        description = if (railMenuOpen) {
                            xy("Tutup aksi cepat", "Close quick actions")
                        } else {
                            xy("Buka aksi cepat di atas keyboard", "Open quick actions above keyboard")
                        },
                        plate = plate,
                        onClick = { railMenuOpen = !railMenuOpen },
                    )
                    RailButton(
                        icon = XyIcons.Keyboard,
                        active = false,
                        description = xy("Buka keyboard HP", "Open phone keyboard"),
                        plate = plate,
                    ) { onOpenKeyboard() }
                }
            }
        }

        if (mappingMode) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(top = 8.dp)
                    .zIndex(26f),
            ) {
                HudMappingBanner(
                    keyCount = keys.size,
                    onAdd = { pickerOpen = true },
                    onScaleAll = { deltaDp ->
                        val scaled = keys.map { k ->
                            k.copy(size = (k.size + deltaDp).coerceIn(HudKey.MIN_SIZE, HudKey.MAX_SIZE))
                        }
                        onKeysChange(scaled)
                        prefs.setCustomHudKeys(deviceId, scaled)
                    },
                    onSnapGrid = {
                        val snapped = keys.map { k ->
                            val sx = ((k.x * 50f).roundToInt() / 50f).coerceIn(0f, 1f)
                            val sy = ((k.y * 50f).roundToInt() / 50f).coerceIn(0f, 1f)
                            k.copy(x = sx, y = sy)
                        }
                        onKeysChange(snapped)
                        prefs.setCustomHudKeys(deviceId, snapped)
                        notice.show(xyNow("Semua tombol diratakan ke grid presisi", "All buttons snapped to precision grid"))
                    },
                    onReset = { confirmResetHud = true },
                    onDone = { onMappingModeChange(false) },
                )
            }
        }

        if (textOpen) {
            XyOverlay(title = xy("Kirim teks", "Send text"), onDismiss = { textOpen = false }) {
                Text(
                    xy(
                        "Dikirim sebagai ketikan. Pakai 'Kirim sebagai clipboard' untuk Paste di Windows.",
                        "Sent as keystrokes. Use 'Send as clipboard' for Windows Paste.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                XyField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = xy("Teks", "Text"),
                    hint = xy("mis. password, alamat URL", "e.g. password, a URL"),
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XyPillButton(
                        xy("Tempel", "Paste"),
                        {
                            val paste = clipboardText()
                            textValue = paste
                            if (paste.isEmpty()) notice.show(xyNow("Clipboard HP kosong", "Phone clipboard is empty"))
                        },
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    XyPillButton(
                        xy("Ketik ke remote", "Type to remote"),
                        {
                            val t = textValue
                            if (t.isEmpty()) {
                                validationAlert = xyNow("Teks kosong", "Empty text") to
                                    xyNow("Masukkan teks terlebih dahulu sebelum mengirim ke remote.", "Enter some text before sending to the remote.")
                            } else {
                                onSendText(t)
                                notice.show(xyNow("Teks diketik ke jendela remote", "Text typed into the remote window"))
                                textOpen = false
                            }
                        },
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                XyPillButton(
                    xy("Kirim sebagai clipboard Windows", "Send as Windows clipboard"),
                    {
                        val text = textValue
                        when {
                            text.isEmpty() -> validationAlert = xyNow("Teks kosong", "Empty text") to
                                xyNow("Masukkan teks terlebih dahulu sebelum mengirim ke clipboard Windows.", "Enter some text before sending to Windows clipboard.")
                            !clipboardSyncEnabled -> validationAlert = xyNow("Kanal clipboard nonaktif", "Clipboard channel off") to
                                xyNow("Aktifkan kanal clipboard di pengaturan perangkat lalu sambungkan ulang.", "Enable the clipboard channel in device settings and reconnect.")
                            onSendRemoteClipboardText(text) -> {
                                notice.show(xyNow("Permintaan clipboard dikirim; tunggu sebentar sebelum Paste", "Clipboard request sent; wait briefly before Paste"))
                                textOpen = false
                            }
                            else -> notice.show(xyNow("Gagal mengirim permintaan clipboard", "Failed to queue clipboard request"))
                        }
                    },
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = clipboardSyncEnabled && textValue.isNotEmpty(),
                )
                XyPillButton(
                    xy("Selesai", "Done"),
                    { textOpen = false },
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (layoutJsonOpen) {
            XyOverlay(
                title = xy("Tata letak tombol (JSON)", "Button layout (JSON)"),
                onDismiss = { layoutJsonOpen = false },
            ) {
                Text(
                    xy(
                        "Salin JSON untuk memindahkan tata letak tombol ke perangkat lain, atau tempel JSON lalu tekan Terapkan.",
                        "Copy JSON to reuse this button layout on another device, or paste JSON and press Apply.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                XyField(
                    value = layoutJsonValue,
                    onValueChange = { layoutJsonValue = it },
                    label = xy("JSON layout", "Layout JSON"),
                    hint = "{\"version\":1,\"keys\":[...]}",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XyPillButton(
                        xy("Tempel dari clipboard", "Paste clipboard"),
                        {
                            val clip = clipboardText()
                            if (clip.isEmpty()) {
                                notice.show(xyNow("Clipboard HP kosong", "Phone clipboard is empty"))
                            } else {
                                layoutJsonValue = clip
                            }
                        },
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    XyPillButton(
                        xy("Terapkan JSON", "Apply JSON"),
                        {
                            val imported = HudKey.importLayoutJson(layoutJsonValue, prefs.hudButtonSize)
                            if (imported == null) {
                                validationAlert = xyNow("JSON tidak valid", "Invalid JSON") to
                                    xyNow(
                                        "Format JSON tata letak tombol tidak valid atau tidak memuat tombol kontrol.",
                                        "The button layout JSON format is invalid or contains no control buttons.",
                                    )
                            } else {
                                confirmImportLayout = imported
                            }
                        },
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                XyPillButton(
                    xy("Salin layout saat ini", "Copy current layout"),
                    {
                        exportHudLayoutToClipboard()
                        layoutJsonValue = HudKey.exportLayoutJson(keys)
                    },
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                XyPillButton(
                    xy("Tutup", "Close"),
                    { layoutJsonOpen = false },
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- handle panel di kanan atas, terpisah dari rail kontrol ----
        if (!panelOpen && !monitorGridOpen && !mappingMode) {
            PanelHandle(
                onClick = {
                    scrollPillOpen = false
                    panelOpen = true
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(top = 12.dp, end = 6.dp),
            )
        }

        if (monitorGridOpen) {
            MonitorAndUserGridModal(
                deviceId = deviceId,
                hostLabel = hostLabel,
                remoteSize = remoteSize,
                codecLabel = telemetry.codecLabel,
                resolution = resolution,
                pcConnectMode = pcConnectMode,
                activeUsername = activeUsername,
                consoleAdminMode = consoleAdminMode,
                onSelectMonitorResolution = { selectedRes ->
                    monitorGridOpen = false
                    resolution = selectedRes
                    DisplayPrefs.setResolution(context, deviceId, selectedRes)
                    onResolutionChange(selectedRes)
                },
                onSwitchConsoleMode = { useConsole ->
                    monitorGridOpen = false
                    onSwitchConsoleMode(useConsole)
                },
                onSwitchUserSession = { user, dom, pass ->
                    monitorGridOpen = false
                    onSwitchUserSession(user, dom, pass)
                },
                onActivatePrivacyCurtain = {
                    monitorGridOpen = false
                    onActivatePrivacyCurtain()
                },
                onLockRemotePc = {
                    monitorGridOpen = false
                    onLockRemotePc()
                },
                onOpenHome = {
                    monitorGridOpen = false
                    onOpenHome()
                },
                notice = notice,
                onDismiss = { monitorGridOpen = false },
            )
        }

        if (panelOpen) {
            SessionPanel(
                drawerMode = drawerMode,
                onDrawerModeChange = { v ->
                    drawerMode = v
                    prefs.menuDrawer = v
                },
                deviceId = deviceId,
                hostLabel = hostLabel,
                statusText = statusText,
                remoteSize = remoteSize,
                zoomPercent = zoomPercent,
                remoteDpi = remoteDpi,
                tab = tab,
                onTab = { tab = it },
                notice = notice,
                // Layar
                resolution = resolution,
                rotation = rotation,
                custom = custom,
                autoFit = autoFit,
                onAutoFitChange = { autoFit = it; prefs.autoFit = it },
                onCustomChange = { custom = it },
                onCustomApply = {
                    val parsed = DisplayPrefs.parseCustom(custom)
                    if (parsed == null) {
                        validationAlert = xyNow("Resolusi kustom tidak valid", "Invalid custom resolution") to
                            xyNow(
                                "Masukkan format WxH yang valid (640..8192 x 480..8192, lebar harus genap), contoh: 1920x1080.",
                                "Enter a valid WxH format (640..8192 x 480..8192, even width), e.g. 1920x1080.",
                            )
                    } else {
                        confirmResolution = parsed
                    }
                },
                onResolution = { selected ->
                    if (selected != resolution) {
                        confirmResolution = selected
                    }
                },
                onRotation = {
                    rotation = it
                    DisplayPrefs.setRotation(context, deviceId, it)
                    onRotationChange(it)
                },
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onFit = onFit,
                onZoomActual = onZoomActual,
                onZoomScale = onZoomScale,
                onRemoteDpiChange = onRemoteDpiChange,
                onDisplayRefreshPreferenceChange = onDisplayRefreshPreferenceChange,
                showTelemetryPill = showTelemetryPill,
                onShowTelemetryPillChange = { showTelemetryPill = it; prefs.showTelemetryPill = it },
                onOpenMonitorGrid = {
                    panelOpen = false
                    monitorGridOpen = true
                },
                pcConnectMode = pcConnectMode,
                // Input
                inputMode = inputMode,
                onInputModeChange = onInputModeChange,
                swapMouseButtons = swapMouseButtons,
                onSwapMouseButtonsChange = onSwapMouseButtonsChange,
                onSendTextClick = { textValue = ""; textOpen = true },
                lastClipboard = lastClipboard,
                clipboardSyncEnabled = clipboardSyncEnabled,
                pointerVisible = pointerVisible,
                onPointerVisibilityChange = onPointerVisibilityChange,
                pointerStyle = pointerStyle,
                onPointerStyle = { pointerStyle = it; prefs.pointerStyle = it },
                pointerSize = pointerSize,
                onPointerSize = { pointerSize = it; prefs.pointerSize = it },
                pointerSensitivity = pointerSensitivity,
                onPointerSensitivity = { pointerSensitivity = it; prefs.pointerSensitivity = it },
                pointerAcceleration = pointerAcceleration,
                onPointerAcceleration = { pointerAcceleration = it; prefs.pointerAcceleration = it },
                inertialScroll = inertialScroll,
                onInertialScroll = { inertialScroll = it; prefs.inertialScroll = it },
                edgeScrollZone = edgeScrollZone,
                onEdgeScrollZone = { edgeScrollZone = it; prefs.edgeScrollZone = it },
                gamepadEnabled = gamepadEnabled,
                onGamepadEnabled = { gamepadEnabled = it; prefs.gamepadEnabled = it },
                stickMode = stickModeUi,
                onStickModeChange = { next ->
                    stickModeUi = next
                    prefs.virtualPadStickMode = next
                    // Activity ikut diberi tahu karena field padStickMode dibaca
                    // mapper dari luar komposisi.
                    onStickModeChange(next)
                },
                gyroMouseEnabled = gyroMouseEnabled,
                onGyroMouseEnabled = {
                    gyroMouseEnabled = it
                    prefs.gyroMouseEnabled = it
                    onGyroMouseChanged(it)
                },
                fpsGameMode = fpsGameMode,
                rawInputUdpAlive = rawInputUdpAlive,
                onFpsGameModeChange = onFpsGameModeChange,
                // Tombol
                keys = keys,
                hudProfile = hudProfile,
                onSelectHudProfile = { selectedPreset ->
                    if (selectedPreset == HudProfilePreset.CUSTOM) {
                        hudProfile = HudProfilePreset.CUSTOM
                        prefs.setHudProfile(deviceId, HudProfilePreset.CUSTOM)
                        val savedCustom = prefs.customHudKeys(deviceId)
                        if (savedCustom != null && savedCustom.isNotEmpty()) {
                            onKeysChange(savedCustom)
                        } else {
                            prefs.setCustomHudKeys(deviceId, keys)
                        }
                        panelOpen = false
                        onMappingModeChange(true)
                        notice.show(
                            xyNow(
                                "Mode Kustom aktif — geser, tambah, atau ubah tombol sesuai keinginanmu",
                                "Custom mode active — drag, add, or edit buttons to build your layout",
                            ),
                        )
                    } else if (selectedPreset != hudProfile) {
                        confirmApplyPreset = selectedPreset
                    }
                },
                onAddKey = { pickerOpen = true },
                onEditKey = { editing = it },
                onDeleteKey = { confirmDeleteKey = it },
                mappingMode = mappingMode,
                onMappingModeChange = { enabled ->
                    onMappingModeChange(enabled)
                    if (enabled) panelOpen = false
                },
                plate = plate,
                onPlate = { plate = it; prefs.hudPlate = it },
                haptics = haptics,
                onHaptics = { haptics = it; prefs.haptics = it },
                onResetCluster = { confirmResetHud = true },
                onExportLayoutJson = { exportHudLayoutToClipboard() },
                onOpenLayoutJson = {
                    layoutJsonValue = HudKey.exportLayoutJson(keys)
                    layoutJsonOpen = true
                },
                showHudButtons = showHudButtons,
                onShowHudButtonsChange = { visible ->
                    showHudButtons = visible
                    prefs.setHudButtonsVisible(deviceId, visible)
                    if (!visible) forceShowHudForExternalInput = false
                },
                autoHideHudOnExternalInput = autoHideHudOnExternalInput,
                onAutoHideHudOnExternalInputChange = { enabled ->
                    autoHideHudOnExternalInput = enabled
                    prefs.autoHideHudOnExternalInput = enabled
                    if (!enabled) forceShowHudForExternalInput = false
                },
                hudOpacity = hudOpacity,
                onHudOpacityChange = { opacity ->
                    hudOpacity = opacity.coerceIn(0f, 1f)
                    prefs.hudOpacity = hudOpacity
                },
                // Sesi
                recordingLabel = recordingLabel,
                recordingStatus = recordingStatus,
                recordingActionEnabled = recordingActionEnabled,
                recordingActive = recordingActive,
                onToggleRecording = onToggleRecording,
                onScreenshot = onScreenshot,
                onLockRemotePc = { confirmLockPc = true },
                onActivatePrivacyCurtain = {
                    panelOpen = false
                    onActivatePrivacyCurtain()
                },
                onDisconnect = {
                    panelOpen = false
                    onDisconnect()
                },
                voiceMicEnabled = voiceMicEnabled,
                onToggleVoiceMic = onToggleVoiceMic,
                scrollPillOpen = scrollPillOpen,
                onToggleScrollPill = { scrollPillOpen = !scrollPillOpen },
                onGoHome = {
                    panelOpen = false
                    onGoHome()
                },
                coreInfo = coreInfo,
                onCopyCoreInfo = { copyCoreInfo() },
                onClose = { panelOpen = false },
            )
        }
    }

    if (pickerOpen) {
        HudKeyPicker(
            onPick = { add(it); pickerOpen = false },
            onDismiss = { pickerOpen = false },
        )
    }
    editing?.let { key ->
        HudKeyEditor(
            key = key,
            onChange = { updated ->
                val persisted = if (updated.size != key.size) {
                    resizeHudKeyPreservingCenter(
                        key, updated.size,
                        configuration.screenWidthDp.toFloat(),
                        configuration.screenHeightDp.toFloat(),
                    )
                } else updated
                replace(persisted)
            },
            onDelete = {
                confirmDeleteKey = key
            },
            onDismiss = { editing = null },
            onDuplicate = { source ->
                if (keys.size >= 36) {
                    notice.show(xyNow("Maksimal 36 tombol HUD", "Maximum 36 HUD buttons"))
                } else {
                    val salinan = source.copy(
                        id = "k${java.util.UUID.randomUUID()}",
                        x = (source.x + 0.06f).coerceIn(0f, 1f),
                        y = (source.y + 0.06f).coerceIn(0f, 1f),
                    )
                    val updatedKeys = keys + salinan
                    onKeysChange(updatedKeys)
                    prefs.setCustomHudKeys(deviceId, updatedKeys)
                    hudProfile = HudProfilePreset.CUSTOM
                    prefs.setHudProfile(deviceId, HudProfilePreset.CUSTOM)
                    editing = salinan
                    notice.show(
                        xyNow(
                            "Tombol \"{0}\" disalin — geser ke posisi yang kamu mau.",
                            "Button \"{0}\" duplicated — drag it into position.",
                            salinan.label,
                        ),
                    )
                }
            },
        )
    }

    confirmDeleteKey?.let { key ->
        XyDialog(
            title = xy("Hapus tombol kontrol?", "Delete control button?"),
            body = xy(
                "Hapus tombol \"{0}\" dari tata letak sesi ini?",
                "Delete button \"{0}\" from this session layout?",
                key.label,
            ),
            confirmLabel = xy("Hapus", "Delete"),
            onConfirm = {
                confirmDeleteKey = null
                if (editing?.id == key.id) editing = null
                onDeleteKey(key)
                notice.show(xyNow("Tombol dihapus", "Button deleted"))
            },
            dismissLabel = xy("Batal", "Cancel"),
            onDismiss = { confirmDeleteKey = null },
        )
    }

    if (confirmResetHud) {
        XyDialog(
            title = xy("Reset tata letak tombol?", "Reset button layout?"),
            body = xy(
                "Kembalikan semua tombol kontrol ke setelan bawaan? Susunan tombol kustom saat ini akan diganti.",
                "Restore all control buttons to the default set? Your current custom button layout will be replaced.",
            ),
            confirmLabel = xy("Reset", "Reset"),
            onConfirm = {
                confirmResetHud = false
                editing = null
                onResetCluster()
                notice.show(xyNow("Tata letak tombol dikembalikan ke bawaan", "Button layout restored to default"))
            },
            dismissLabel = xy("Batal", "Cancel"),
            onDismiss = { confirmResetHud = false },
        )
    }

    confirmImportLayout?.let { imported ->
        XyDialog(
            title = xy("Terapkan tata letak JSON?", "Apply JSON layout?"),
            body = xy(
                "Ganti tata letak tombol saat ini dengan {0} tombol dari JSON?",
                "Replace the current button layout with {0} buttons from JSON?",
                imported.size,
            ),
            confirmLabel = xy("Terapkan", "Apply"),
            onConfirm = {
                confirmImportLayout = null
                layoutJsonOpen = false
                panelOpen = false
                onKeysChange(imported)
                notice.show(
                    xyNow(
                        "Layout diimpor ({0} tombol)",
                        "Layout imported ({0} buttons)",
                        imported.size,
                    ),
                )
            },
            dismissLabel = xy("Batal", "Cancel"),
            onDismiss = { confirmImportLayout = null },
        )
    }

    confirmApplyPreset?.let { preset ->
        XyDialog(
            title = xy("Ganti profil tombol HUD?", "Switch HUD button profile?"),
            body = xy(
                "Terapkan profil \"{0}\"? Susunan tombol kontrol di layar sesi akan disesuaikan dengan profil ini.",
                "Apply profile \"{0}\"? The on-screen control buttons will be updated to match this preset.",
                xy(preset.title, preset.titleEn),
            ),
            confirmLabel = xy("Terapkan", "Apply"),
            onConfirm = {
                confirmApplyPreset = null
                hudProfile = preset
                prefs.setHudProfile(deviceId, preset)
                onKeysChange(HudKey.presetLayout(preset, prefs.hudButtonSize))
                notice.show(
                    xyNow(
                        "Profil tombol diubah ke {0}",
                        "Button profile switched to {0}",
                        xyNow(preset.title, preset.titleEn),
                    ),
                )
            },
            dismissLabel = xy("Batal", "Cancel"),
            onDismiss = { confirmApplyPreset = null },
        )
    }

    if (confirmLockPc) {
        XyDialog(
            title = xy("Kunci layar PC (Win+L)?", "Lock PC screen (Win+L)?"),
            body = xy(
                "Kirim perintah Win+L untuk mengunci sesi Windows di komputer remote sekarang?",
                "Send Win+L to lock the Windows session on the remote computer now?",
            ),
            confirmLabel = xy("Kunci PC", "Lock PC"),
            onConfirm = {
                confirmLockPc = false
                panelOpen = false
                onLockRemotePc()
            },
            dismissLabel = xy("Batal", "Cancel"),
            onDismiss = { confirmLockPc = false },
        )
    }

    confirmResolution?.let { targetRes ->
        XyDialog(
            title = xy("Ubah resolusi remote?", "Change remote resolution?"),
            body = xy(
                "Terapkan resolusi \"{0}\"? Jika server belum menerapkan perubahan langsung, sesi akan menyambung ulang otomatis.",
                "Apply resolution \"{0}\"? If the server does not apply live resize, the session will reconnect automatically.",
                targetRes,
            ),
            confirmLabel = xy("Terapkan", "Apply"),
            onConfirm = {
                confirmResolution = null
                resolution = targetRes
                DisplayPrefs.setResolution(context, deviceId, targetRes)
                onResolutionChange(targetRes)
            },
            dismissLabel = xy("Batal", "Cancel"),
            onDismiss = { confirmResolution = null },
        )
    }

    validationAlert?.let { (alertTitle, alertBody) ->
        XyDialog(
            title = alertTitle,
            body = alertBody,
            confirmLabel = xy("Mengerti", "Got it"),
            onConfirm = { validationAlert = null },
            onDismiss = { validationAlert = null },
        )
    }
}

@Composable
private fun LiveTelemetryPill(
    telemetry: id.xydesk.remote.core.TelemetrySample,
    modifier: Modifier = Modifier,
) {
    val rtt = telemetry.rttMs
    val rttColor = when {
        rtt <= 0 -> Color(0xFFF2F2F4)
        rtt < 45 -> Color(0xFFE4E4E8)
        rtt < 120 -> Color(0xFFAAAAAA)
        else -> Color(0xFF858585)
    }
    val fpsColor = when {
        telemetry.fps >= 45 -> Color(0xFFF2F2F4)
        telemetry.fps >= 24 -> Color(0xFFE4E4E8)
        telemetry.fps > 0 -> Color(0xFFAAAAAA)
        else -> Color(0xFFE4E4E8)
    }
    val textShadow = Shadow(
        color = Color(0xF2000000),
        offset = Offset(1.2f, 1.2f),
        blurRadius = 3f,
    )
    val labelStyle = TextStyle(
        color = Color(0xB8D9D9DE),
        fontSize = 7.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.4.sp,
        lineHeight = 8.sp,
        shadow = textShadow,
    )
    val valueStyle = TextStyle(
        color = Color(0xFFF2F2F4),
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        lineHeight = 10.sp,
        shadow = textShadow,
    )
    val rttLabel = if (rtt > 0) "${rtt} ms" else "-- ms"
    val resLabel = if (telemetry.width > 0) "${telemetry.width}x${telemetry.height}" else "--"
    val netColor = if (telemetry.udpActive) Color(0xFFF2F2F4) else Color(0xFFB2B2B8)
    val items = listOf(
        Triple("UI/s", "${telemetry.fps}", fpsColor),
        Triple("LATENCY", rttLabel, rttColor),
        Triple("RESOLUSI", resLabel, Color(0xFFF2F2F4)),
        Triple("ENCODE", telemetry.codecLabel.replace("FreeRDP", "XyRDP"), Color(0xFFF2F2F4)),
        Triple("RELAY", telemetry.relayLabel.replace("FreeRDP", "XyRDP"), Color(0xFFE4E4E8)),
        Triple("NETWORK", telemetry.networkLabel, netColor),
    )

    Column(
        modifier = modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEach { (label, value, color) ->
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(text = label, style = labelStyle)
                Text(text = value, style = valueStyle.copy(color = color))
            }
        }
    }
}

/** Tombol rail sesi: latar mengikuti rasa tombol HUD, ikon selalu terbaca. */
@Composable
private fun RailButton(
    icon: ImageVector,
    active: Boolean,
    description: String,
    plate: HudPlate,
    onClick: () -> Unit,
) {
    val pal = hudPalette(plate)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xE9F2F2F4) else pal.plate)
            .border(
                if (active) 2.dp else 1.2.dp,
                if (active) Color(0xFFFFFFFF) else pal.border,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) Color(0xFF0B0B0E) else pal.ink,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RailMenuItem(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val rowBg = when {
        danger -> Color(0xFF1A1A1E)
        active -> Color(0xFF232327)
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(rowBg, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                danger -> Color(0xFFB2B2B8)
                active -> Color(0xFFFFFFFF)
                else -> ink
            },
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontSize = 12.sp,
            color = when {
                danger -> Color(0xFFD4D4D8)
                active -> Color(0xFFFFFFFF)
                else -> ink
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (active) Color(0xFFF2F2F4) else Color(0xFF1A1A1E),
                    )
                    .border(
                        1.dp,
                        if (active) Color(0xFFFFFFFF) else Color(0xFF3C3C42),
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = badge,
                    color = if (active) Color(0xFF111114) else Color(0xFFB2B2B8),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

@Composable
private fun PanelHandle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp, horizontal = 5.dp)
            .zIndex(20f),
    ) {
        Box(
            Modifier
                .shadow(10.dp, XyPill)
                .clip(XyPill)
                .background(Color(0xF2101013))
                .border(1.2.dp, Color(0xFF3C3C42), XyPill)
                .padding(horizontal = 11.dp, vertical = 14.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF2F2F4)),
                )
                Spacer(Modifier.height(6.dp))
                Icon(
                    XyIcons.Sliders,
                    contentDescription = xy("Buka panel sesi", "Open session panel"),
                    tint = Color(0xFFF3F3F5),
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    xy("MENU", "MENU"),
                    color = Color(0xFFE4E4E8),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.9.sp,
                )
            }
        }
    }
}

/**
 * Pointer tunggal.
 *
 * Urutan prioritas:
 *  1. Bentuk kursor yang DIKIRIM SERVER (panah, tangan, I-beam, resize, ...)
 *     — digambar apa adanya, jadi bentuknya berubah selayaknya pointer desktop.
 *  2. Kalau server tidak mengirim apa-apa: panah/titik bawaan app.
 */
@Composable
private fun XyPointer(
    position: Offset,
    sizeDp: Float,
    style: PointerStyle,
    remote: RemoteCursor?,
    zoom: Float,
) {
    val remoteBitmap = remote?.bitmap
    if (remote != null && remote.visible && remoteBitmap != null) {
        val image = remember(remoteBitmap) { remoteBitmap.asImageBitmap() }
        val scale = zoom.coerceIn(0.35f, 3f)
        Canvas(Modifier.fillMaxSize().zIndex(11f)) {
            val w = (remoteBitmap.width * scale).roundToInt().coerceAtLeast(1)
            val h = (remoteBitmap.height * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(remoteBitmap.width, remoteBitmap.height),
                dstOffset = IntOffset(
                    (position.x - remote.hotX * scale).roundToInt(),
                    (position.y - remote.hotY * scale).roundToInt(),
                ),
                dstSize = IntSize(w, h),
            )
        }
        return
    }

    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { sizeDp.dp.toPx() }
    Box(
        Modifier
            .offset {
                IntOffset(
                    (position.x - sizePx / 2f).roundToInt(),
                    (position.y - sizePx / 2f).roundToInt(),
                )
            }
            .size(sizeDp.dp)
            .zIndex(11f),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            when (style) {
                PointerStyle.DOT -> {
                    val radius = w / 2f - 1.5f
                    drawCircle(Color.White, radius = radius, center = Offset(w / 2f, h / 2f))
                    drawCircle(
                        Color.Black.copy(alpha = 0.55f),
                        radius = radius,
                        center = Offset(w / 2f, h / 2f),
                        style = Stroke(width = 3f),
                    )
                }

                PointerStyle.ARROW -> {
                    val path = Path().apply {
                        val sx = w / 24f
                        val sy = h / 24f
                        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
                        val a = p(6f, 3f)
                        val b = p(6f, 18.5f)
                        val c = p(10.2f, 14.6f)
                        val d = p(13.1f, 20.8f)
                        val e = p(15.6f, 19.6f)
                        val f = p(12.8f, 13.6f)
                        val g = p(18.4f, 13.1f)
                        moveTo(a.x, a.y)
                        lineTo(b.x, b.y)
                        lineTo(c.x, c.y)
                        lineTo(d.x, d.y)
                        lineTo(e.x, e.y)
                        lineTo(f.x, f.y)
                        lineTo(g.x, g.y)
                        close()
                    }
                    drawPath(path, Color.White)
                    drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(width = 2.4f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ panel

@Composable
private fun SessionPanel(
    drawerMode: Boolean = false,
    onDrawerModeChange: (Boolean) -> Unit = {},
    deviceId: String,
    hostLabel: String,
    statusText: String,
    remoteSize: String,
    zoomPercent: Int,
    remoteDpi: Int,
    tab: PanelTab,
    onTab: (PanelTab) -> Unit,
    notice: XyNoticeState,
    onClose: () -> Unit,
    // Layar
    resolution: String,
    rotation: String,
    custom: String,
    autoFit: Boolean,
    onAutoFitChange: (Boolean) -> Unit,
    onCustomChange: (String) -> Unit,
    onCustomApply: () -> Unit,
    onResolution: (String) -> Unit,
    onRotation: (String) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onZoomActual: () -> Unit,
    onZoomScale: (Float) -> Unit,
    onRemoteDpiChange: (Int) -> Unit,
    onDisplayRefreshPreferenceChange: (Int) -> Unit,
    showTelemetryPill: Boolean,
    onShowTelemetryPillChange: (Boolean) -> Unit,
    onOpenMonitorGrid: () -> Unit,
    pcConnectMode: Boolean = false,
    // Input
    inputMode: InputMode,
    onInputModeChange: (InputMode) -> Unit,
    swapMouseButtons: Boolean,
    onSwapMouseButtonsChange: (Boolean) -> Unit,
    onSendTextClick: () -> Unit,
    lastClipboard: String?,
    clipboardSyncEnabled: Boolean,
    pointerVisible: Boolean,
    onPointerVisibilityChange: (Boolean) -> Unit,
    pointerStyle: PointerStyle,
    onPointerStyle: (PointerStyle) -> Unit,
    pointerSize: Float,
    onPointerSize: (Float) -> Unit,
    pointerSensitivity: Float,
    onPointerSensitivity: (Float) -> Unit,
    pointerAcceleration: Boolean,
    onPointerAcceleration: (Boolean) -> Unit,
    inertialScroll: Boolean,
    onInertialScroll: (Boolean) -> Unit,
    edgeScrollZone: Boolean,
    onEdgeScrollZone: (Boolean) -> Unit,
    gamepadEnabled: Boolean,
    stickMode: XyStickMode,
    onStickModeChange: (XyStickMode) -> Unit,
    onGamepadEnabled: (Boolean) -> Unit,
    gyroMouseEnabled: Boolean,
    onGyroMouseEnabled: (Boolean) -> Unit,
    fpsGameMode: Boolean = false,
    rawInputUdpAlive: Boolean = false,
    onFpsGameModeChange: (Boolean) -> Unit = {},
    // Tombol
    keys: List<HudKey>,
    hudProfile: HudProfilePreset,
    onSelectHudProfile: (HudProfilePreset) -> Unit,
    onAddKey: () -> Unit,
    onEditKey: (HudKey) -> Unit,
    onDeleteKey: (HudKey) -> Unit,
    mappingMode: Boolean,
    onMappingModeChange: (Boolean) -> Unit,
    plate: HudPlate,
    onPlate: (HudPlate) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
    onResetCluster: () -> Unit,
    onExportLayoutJson: () -> Unit,
    onOpenLayoutJson: () -> Unit,
    // Tombol overlay HUD
    showHudButtons: Boolean,
    onShowHudButtonsChange: (Boolean) -> Unit,
    autoHideHudOnExternalInput: Boolean,
    onAutoHideHudOnExternalInputChange: (Boolean) -> Unit,
    hudOpacity: Float,
    onHudOpacityChange: (Float) -> Unit,
    // Sesi
    recordingLabel: String,
    recordingStatus: String?,
    recordingActionEnabled: Boolean,
    recordingActive: Boolean,
    onToggleRecording: () -> Unit,
    onScreenshot: () -> Unit,
    onLockRemotePc: () -> Unit,
    onActivatePrivacyCurtain: () -> Unit,
    onDisconnect: () -> Unit,
    voiceMicEnabled: Boolean = false,
    onToggleVoiceMic: () -> Unit = {},
    scrollPillOpen: Boolean = false,
    onToggleScrollPill: () -> Unit = {},
    onGoHome: () -> Unit = {},
    coreInfo: List<String>,
    onCopyCoreInfo: () -> Unit,
) {
    val context = LocalContext.current
    // Buka seksi pertama secara otomatis saat berpindah tab agar tidak kosong.
    var openSection by remember(tab) { mutableStateOf<String?>("__FIRST__") }
    var panelShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { panelShown = true }
    val panelScale by animateFloatAsState(
        targetValue = if (panelShown) 1f else 0.92f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
    )
    val panelAlpha by animateFloatAsState(
        targetValue = if (panelShown) 1f else 0f,
        animationSpec = tween(160),
    )
    val panelSlide by animateFloatAsState(
        targetValue = if (panelShown) 0f else 1f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
    )
    val panelShape = if (drawerMode) {
        RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)
    } else {
        RoundedCornerShape(26.dp)
    }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(30f),
        contentAlignment = if (drawerMode) Alignment.CenterEnd else Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x9904060A))
                .consumeBackgroundPointer(onClose),
        )
        Column(
            if (drawerMode) {
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.76f)
                    .widthIn(min = 280.dp, max = 360.dp)
            } else {
                Modifier
                    .fillMaxHeight(0.92f)
                    .fillMaxWidth(0.95f)
                    .widthIn(max = 860.dp)
            }
                .graphicsLayer {
                    scaleX = panelScale
                    scaleY = panelScale
                    alpha = panelAlpha
                    transformOrigin = TransformOrigin(1f, 0f)
                    if (drawerMode) translationX = panelSlide * size.width
                }
                .clip(panelShape)
                .background(Color(0xFF101013), panelShape)
                .border(1.2.dp, Color(0xFF3C3C42), panelShape)
                .pointerInput(Unit) {}
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A1A1E), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF3C3C42), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(0xFF232327))
                        .border(1.dp, Color(0xFF3C3C42), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        XyIcons.Monitor,
                        contentDescription = null,
                        tint = Color(0xFFF3F3F5),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            hostLabel,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFFF2F2F4))
                                .border(1.dp, Color(0xFFFFFFFF), RoundedCornerShape(50))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "60Hz LIVELY SYNC",
                                color = Color(0xFF111114),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                    Text(
                        "$statusText  ·  $zoomPercent%  ·  $remoteSize",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tab == PanelTab.BUTTONS) {
                    PanelChip(xy("+ Tombol", "+ Add"), onAddKey)
                }
                PanelChip(
                    if (drawerMode) xy("Popup", "Popup") else xy("Sidebar", "Sidebar"),
                    { onDrawerModeChange(!drawerMode) },
                )
                PanelChip(xy("Tutup", "Close"), onClose)
            }

            // Area konten utama dengan animasi perpindahan tab halus +
            // Floating Rounded Full Bottom Navigation Bar di bagian bawah popup
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                androidx.compose.animation.AnimatedContent(
                    targetState = tab,
                    label = "sessionPopupTabAnim",
                    modifier = Modifier.fillMaxSize(),
                ) { activeTab ->
                    val tabScroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(tabScroll)
                            .padding(bottom = 74.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        XyDiscordVoiceStageBar(compact = true)
                        when (activeTab) {
                            PanelTab.SCREEN -> ScreenTab(
                                openKey = openSection,
                                onOpenKey = { openSection = it },
                                deviceId = deviceId,
                                remoteSize = remoteSize,
                                zoomPercent = zoomPercent,
                                remoteDpi = remoteDpi,
                                resolution = resolution,
                                rotation = rotation,
                                custom = custom,
                                autoFit = autoFit,
                                onAutoFitChange = onAutoFitChange,
                                onCustomChange = onCustomChange,
                                onCustomApply = onCustomApply,
                                onResolution = onResolution,
                                onRotation = onRotation,
                                onZoomIn = onZoomIn,
                                onZoomOut = onZoomOut,
                                onFit = onFit,
                                onZoomActual = onZoomActual,
                                onZoomScale = onZoomScale,
                                onRemoteDpiChange = onRemoteDpiChange,
                                onDisplayRefreshPreferenceChange = onDisplayRefreshPreferenceChange,
                                showTelemetryPill = showTelemetryPill,
                                onShowTelemetryPillChange = onShowTelemetryPillChange,
                                onOpenMonitorGrid = onOpenMonitorGrid,
                                pcConnectMode = pcConnectMode,
                                notice = notice,
                            )

                            PanelTab.INPUT -> InputTab(
                                openKey = openSection,
                                onOpenKey = { openSection = it },
                                inputMode = inputMode,
                                onInputModeChange = onInputModeChange,
                                swapMouseButtons = swapMouseButtons,
                                onSwapMouseButtonsChange = onSwapMouseButtonsChange,
                                onSendTextClick = onSendTextClick,
                                lastClipboard = lastClipboard,
                                clipboardSyncEnabled = clipboardSyncEnabled,
                                pointerVisible = pointerVisible,
                                onPointerVisibilityChange = onPointerVisibilityChange,
                                pointerStyle = pointerStyle,
                                onPointerStyle = onPointerStyle,
                                pointerSize = pointerSize,
                                onPointerSize = onPointerSize,
                                pointerSensitivity = pointerSensitivity,
                                onPointerSensitivity = onPointerSensitivity,
                                pointerAcceleration = pointerAcceleration,
                                onPointerAcceleration = onPointerAcceleration,
                                inertialScroll = inertialScroll,
                                onInertialScroll = onInertialScroll,
                                edgeScrollZone = edgeScrollZone,
                                onEdgeScrollZone = onEdgeScrollZone,
                                gamepadEnabled = gamepadEnabled,
                                stickMode = stickMode,
                                onStickModeChange = onStickModeChange,
                                onGamepadEnabled = onGamepadEnabled,
                                gyroMouseEnabled = gyroMouseEnabled,
                                onGyroMouseEnabled = onGyroMouseEnabled,
                                fpsGameMode = fpsGameMode,
                                rawInputUdpAlive = rawInputUdpAlive,
                                onFpsGameModeChange = onFpsGameModeChange,
                            )

                            PanelTab.BUTTONS -> ButtonsTab(
                                openKey = openSection,
                                onOpenKey = { openSection = it },
                                keys = keys,
                                hudProfile = hudProfile,
                                onSelectHudProfile = onSelectHudProfile,
                                onAddKey = onAddKey,
                                onEditKey = onEditKey,
                                onDeleteKey = onDeleteKey,
                                mappingMode = mappingMode,
                                onMappingModeChange = onMappingModeChange,
                                plate = plate,
                                onPlate = onPlate,
                                haptics = haptics,
                                onHaptics = onHaptics,
                                onResetCluster = onResetCluster,
                                onExportLayoutJson = onExportLayoutJson,
                                onOpenLayoutJson = onOpenLayoutJson,
                                showHudButtons = showHudButtons,
                                onShowHudButtonsChange = onShowHudButtonsChange,
                                autoHideHudOnExternalInput = autoHideHudOnExternalInput,
                                onAutoHideHudOnExternalInputChange = onAutoHideHudOnExternalInputChange,
                                hudOpacity = hudOpacity,
                                onHudOpacityChange = onHudOpacityChange,
                            )

                            PanelTab.SESSION -> SessionTab(
                                deviceId = deviceId,
                                notice = notice,
                                openKey = openSection,
                                onOpenKey = { openSection = it },
                                onScreenshot = onScreenshot,
                                onLockRemotePc = onLockRemotePc,
                                onActivatePrivacyCurtain = onActivatePrivacyCurtain,
                                onDisconnect = onDisconnect,
                                recordingLabel = recordingLabel,
                                recordingStatus = recordingStatus,
                                recordingActionEnabled = recordingActionEnabled,
                                recordingActive = recordingActive,
                                onToggleRecording = onToggleRecording,
                                coreInfo = coreInfo,
                                onCopyCoreInfo = onCopyCoreInfo,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // FLOATING ROUNDED FULL BOTTOM NAVIGATION BAR
                PanelTabTiles(
                    tab = tab,
                    onTab = onTab,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ tab: layar

@Composable
private fun ScreenTab(
    deviceId: String,
    remoteSize: String,
    zoomPercent: Int,
    remoteDpi: Int,
    resolution: String,
    rotation: String,
    custom: String,
    autoFit: Boolean,
    onAutoFitChange: (Boolean) -> Unit,
    onCustomChange: (String) -> Unit,
    onCustomApply: () -> Unit,
    onResolution: (String) -> Unit,
    onRotation: (String) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onZoomActual: () -> Unit,
    onZoomScale: (Float) -> Unit,
    onRemoteDpiChange: (Int) -> Unit,
    onDisplayRefreshPreferenceChange: (Int) -> Unit,
    showTelemetryPill: Boolean,
    onShowTelemetryPillChange: (Boolean) -> Unit,
    onOpenMonitorGrid: () -> Unit,
    pcConnectMode: Boolean = false,
    notice: XyNoticeState,
    openKey: String?,
    onOpenKey: (String?) -> Unit,
) {
    val context = LocalContext.current
    var pcOptions by remember(deviceId) { mutableStateOf(RdpOptions.of(context, deviceId)) }

    PanelSectionGrid(openKey = openKey, onOpenKey = onOpenKey) {
    section(
        "telemetri-kontrol-monitor-fisik-pc",
        if (pcConnectMode) xy("Lively Wallpaper 60Hz & Monitor Fisik PC", "Lively Wallpaper 60Hz & Physical PC Monitor")
        else xy("Lively Live Wallpaper 60Hz & Multi-Sesi RDP", "Lively Live Wallpaper 60Hz & Multi-Session RDP"),
    ) {
            XyToggleRow(
                title = xy("Status telemetri live (Update UI, Latency, Network)", "Live telemetry status (UI updates, latency, network)"),
                checked = showTelemetryPill,
                onCheckedChange = onShowTelemetryPillChange,
            )
            XyToggleRow(
                title = xy("Support Lively Live Wallpaper (60 FPS Continuous Sync Anti-Stuck)", "Support Lively Live Wallpaper (60 FPS Continuous Sync Anti-Stuck)"),
                checked = pcOptions.desktopWallpaper && pcOptions.desktopComposition,
                onCheckedChange = { enabled ->
                    val next = pcOptions.copy(
                        desktopWallpaper = enabled,
                        desktopComposition = enabled,
                        asyncUpdate = true,
                    )
                    pcOptions = next
                    next.write(context, deviceId)
                    notice.show(
                        if (enabled) {
                            xyNow("Lively Live Wallpaper 60 FPS Sync aktif (Anti-Stuck)", "Lively Live Wallpaper 60 FPS Sync enabled (Anti-Stuck)")
                        } else {
                            xyNow("Sinkronisasi Live Wallpaper dimatikan", "Live Wallpaper sync disabled")
                        },
                    )
                },
            )
            XyPillButton(
                text = if (pcConnectMode) {
                    xy("Monitor Fisik 1:1 & Mesin Direct Stream", "1:1 Physical Monitor & Direct Stream Engine")
                } else {
                    xy("Grid Monitor & Sesi Multi-User / Konsol", "Active Monitors & Multi-User / Console Grid")
                },
                onClick = onOpenMonitorGrid,
                icon = XyIcons.DualMonitor,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

    section("orientasi",xy("Orientasi", "Orientation")) {
            // Dulu label mentah "Auto/Portrait/Landscape" — satu-satunya baris
            // Inggris di panel Indonesia.
            val labels = listOf(
                xy("Otomatis", "Auto"),
                xy("Potret", "Portrait"),
                xy("Lanskap", "Landscape"),
            )
            XySegmented(
                options = labels,
                selectedIndex = DisplayPrefs.rotations.indexOf(rotation).coerceAtLeast(0),
                onSelect = { onRotation(DisplayPrefs.rotations[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ------------------------------------------------------------------ tab: input

@Composable
private fun InputTab(
    inputMode: InputMode,
    onInputModeChange: (InputMode) -> Unit,
    swapMouseButtons: Boolean,
    onSwapMouseButtonsChange: (Boolean) -> Unit,
    onSendTextClick: () -> Unit,
    lastClipboard: String?,
    clipboardSyncEnabled: Boolean,
    pointerVisible: Boolean,
    onPointerVisibilityChange: (Boolean) -> Unit,
    pointerStyle: PointerStyle,
    onPointerStyle: (PointerStyle) -> Unit,
    pointerSize: Float,
    onPointerSize: (Float) -> Unit,
    pointerSensitivity: Float,
    onPointerSensitivity: (Float) -> Unit,
    pointerAcceleration: Boolean,
    onPointerAcceleration: (Boolean) -> Unit,
    inertialScroll: Boolean,
    onInertialScroll: (Boolean) -> Unit,
    edgeScrollZone: Boolean,
    onEdgeScrollZone: (Boolean) -> Unit,
    gamepadEnabled: Boolean,
    stickMode: XyStickMode,
    onStickModeChange: (XyStickMode) -> Unit,
    onGamepadEnabled: (Boolean) -> Unit,
    gyroMouseEnabled: Boolean,
    onGyroMouseEnabled: (Boolean) -> Unit,
    fpsGameMode: Boolean = false,
    rawInputUdpAlive: Boolean = false,
    onFpsGameModeChange: (Boolean) -> Unit = {},
    openKey: String?,
    onOpenKey: (String?) -> Unit,
) {
    PanelSectionGrid(openKey = openKey, onOpenKey = onOpenKey) {
    section("mode-input-klik-mouse",xy("Mode input & Klik Mouse", "Input mode & Mouse Click")) {
            XyToggleRow(
                title = xy("Mode Game FPS (120Hz · Rotasi 360° RawInput)", "FPS Game Mode (120Hz · 360° RawInput Aim)"),
                checked = fpsGameMode,
                onCheckedChange = onFpsGameModeChange,
            )
            PanelHint(
                if (fpsGameMode) {
                    if (rawInputUdpAlive) {
                        xy(
                            "AKTIF: Injeksi SendInput(MOUSEEVENTF_MOVE) via UDP :4433 (<1 ms) + Crosshair Tengah + 120Hz.",
                            "ACTIVE: Hardware SendInput(MOUSEEVENTF_MOVE) via UDP :4433 (<1 ms) + Center Crosshair + 120Hz.",
                        )
                    } else {
                        xy(
                            "AKTIF: Rotasi Kamera 360° Relative Delta + Crosshair Tengah + Layar 120Hz.",
                            "ACTIVE: 360° Relative Delta Camera Aim + Center Crosshair + 120Hz Display.",
                        )
                    }
                } else {
                    xy(
                        "Nyalakan saat masuk match FPS (CS2, GTA/SAMP, Valorant, COD, Roblox) agar kamera berputar 360° tanpa mentok layar.",
                        "Enable inside FPS matches (CS2, GTA/SAMP, Valorant, COD, Roblox) for 360° camera rotation without screen-edge lock.",
                    )
                },
            )
            XySegmented(
                options = InputMode.entries.map { xy(it.title, it.titleEn) },
                selectedIndex = inputMode.ordinal,
                onSelect = { onInputModeChange(InputMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            PanelHint(xy(InputMode.entries[inputMode.ordinal].detail, InputMode.entries[inputMode.ordinal].detailEn))
            // Switch Ikon Mouse Kiri <-> Kanan (tanpa bergantung pada teks)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (swapMouseButtons) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    )
                    .border(
                        1.dp,
                        if (swapMouseButtons) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSwapMouseButtonsChange(!swapMouseButtons) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (swapMouseButtons) XyIcons.ClickRight else XyIcons.ClickLeft,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Icon(
                        XyIcons.MouseSwap,
                        contentDescription = xy("Tukar Klik Kiri & Kanan", "Swap Left & Right Click"),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (swapMouseButtons) XyIcons.ClickLeft else XyIcons.ClickRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                id.xydesk.remote.ui.components.XySwitch(
                    checked = swapMouseButtons,
                    onCheckedChange = onSwapMouseButtonsChange,
                )
            }
            XyPillButton(
                xy("Kirim teks ke remote", "Send text to remote"),
                onSendTextClick,
                icon = XyIcons.Keyboard,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

    section("fisika-trackpad-sensor",xy("Fisika Trackpad & Sensor", "Trackpad Physics & Sensors"), advanced = true) {
            PanelHint(
                xy(
                    "Sensitivitas pointer: {0}x",
                    "Pointer sensitivity: {0}x",
                    String.format(java.util.Locale.US, "%.1f", pointerSensitivity),
                ),
            )
            XySlider(
                value = pointerSensitivity,
                onValueChange = onPointerSensitivity,
                valueRange = 0.4f..3.0f,
            )
            XyToggleRow(
                title = xy("Akselerasi pointer dinamis", "Dynamic pointer acceleration"),
                checked = pointerAcceleration,
                onCheckedChange = onPointerAcceleration,
            )
            XyToggleRow(
                title = xy("Inertial scroll (momentum)", "Inertial scroll (momentum)"),
                checked = inertialScroll,
                onCheckedChange = onInertialScroll,
            )
            XyToggleRow(
                title = xy("Zona scroll tepi kanan", "Right-edge scroll strip"),
                checked = edgeScrollZone,
                onCheckedChange = onEdgeScrollZone,
            )
            XyToggleRow(
                title = xy("Dukungan Gamepad & Joystick fisik", "Physical Gamepad & Joystick"),
                checked = gamepadEnabled,
                onCheckedChange = onGamepadEnabled,
            )
            PanelHint(
                xy(
                    "Mode stik kiri: Pointer = kursor, WASD/Panah = tombol arah. Stik kanan gamepad selalu kursor.",
                    "Left stick mode: Pointer = cursor, WASD/Arrows = keys. The gamepad's right stick is always the cursor.",
                ),
            )
            XySegmented(
                options = listOf(xy("Pointer", "Pointer"), xy("WASD", "WASD"), xy("Panah", "Arrows")),
                selectedIndex = XyStickMode.entries.indexOf(stickMode).coerceAtLeast(0),
                onSelect = { onStickModeChange(XyStickMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            XyToggleRow(
                title = xy("Gyro Air-Mouse (sensor gerak HP)", "Gyro Air-Mouse (phone motion)"),
                checked = gyroMouseEnabled,
                onCheckedChange = onGyroMouseEnabled,
            )
        }

    section("jendela-sambung-ulang", xy("Jendela sambung ulang", "Auto-reconnect window"), advanced = true) {
        val reconnContext = androidx.compose.ui.platform.LocalContext.current
        val reconnPrefs = remember { SessionPrefs(reconnContext) }
        PanelHint(
            xy(
                "XyDesk mencoba sambung ulang selama durasi ini, termasuk di latar. Setelah lewat, retry berhenti.",
                "XyDesk keeps retrying for this window, even in background. After it ends, retries stop.",
            ),
        )
        var windowHours by remember { mutableStateOf(reconnPrefs.autoReconnectHours) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 6, 24).forEach { hours ->
                val active = windowHours == hours
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        )
                        .border(
                            if (active) 1.4.dp else 1.dp,
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            windowHours = hours
                            reconnPrefs.autoReconnectHours = hours
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        xy("{0} jam", "{0} h", hours),
                        color = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    section("tombol-notifikasi-kustom", xy("Tombol notifikasi sesi", "Session notification buttons"), advanced = true) {
        val notifContext = androidx.compose.ui.platform.LocalContext.current
        val notifPrefs = remember { AppPrefs(notifContext) }
        var notifHome by remember { mutableStateOf(notifPrefs.notifHomeButton) }
        fun refreshNotif() {
            runCatching {
                notifContext.startService(
                    android.content.Intent(notifContext, id.xydesk.remote.XySessionService::class.java),
                )
            }
        }
        XyToggleRow(
            title = xy("Tombol \"Beranda\" di notifikasi", "\"Home\" button in notification"),
            checked = notifHome,
            onCheckedChange = { v -> notifHome = v; notifPrefs.notifHomeButton = v; refreshNotif() },
        )
    }

    section("clipboard-otomatis-teks-gambar",xy("Clipboard otomatis (Teks & Gambar)", "Automatic clipboard (Text & Image)"), advanced = true) {
            PanelHint(
                if (clipboardSyncEnabled) {
                    xy(
                        "Sinkronisasi dua arah aktif otomatis: teks maupun gambar/screenshot yang disalin di PC langsung masuk ke clipboard HP, dan sebaliknya.",
                        "Two-way sync is active automatically: text and images/screenshots copied on PC go straight to the phone clipboard, and vice versa.",
                    )
                } else {
                    xy(
                        "Kanal clipboard mati di pengaturan perangkat. Aktifkan lalu sambungkan ulang untuk sinkronisasi otomatis PC dan HP.",
                        "Clipboard channel is off in device settings. Enable it and reconnect for automatic PC and phone clipboard sync.",
                    )
                },
            )
            if (clipboardSyncEnabled && !lastClipboard.isNullOrEmpty()) {
                PanelHint(
                    xy(
                        "Status: teks terakhir dari remote sudah tersalin otomatis ke clipboard HP.",
                        "Status: latest remote text has been automatically copied to the phone clipboard.",
                    ),
                )
            }
            ClipboardHistoryBlock(clipboardSyncEnabled = clipboardSyncEnabled)
        }

    section("pointer",xy("Pointer", "Pointer")) {
            XyToggleRow(
                title = xy("Tampilkan pointer", "Show pointer"),
                checked = pointerVisible,
                onCheckedChange = onPointerVisibilityChange,
            )
            PanelHint(
                xy(
                    "Bentuk pointer mengikuti kursor yang dikirim server (panah, tangan, I-beam).",
                    "Pointer shape follows the cursor sent by the server (arrow, hand, I-beam).",
                ),
            )
            XySegmented(
                options = PointerStyle.entries.map { xy(it.title, it.titleEn) },
                selectedIndex = pointerStyle.ordinal,
                onSelect = { onPointerStyle(PointerStyle.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            PanelHint(xy("Ukuran pointer: {0} dp", "Pointer size: {0} dp", pointerSize.toInt()))
            XySlider(value = pointerSize, onValueChange = onPointerSize, valueRange = 10f..52f)
        }
    }
}

// ------------------------------------------------------------- tab: tombol

@Composable
private fun ButtonsTab(
    keys: List<HudKey>,
    hudProfile: HudProfilePreset,
    onSelectHudProfile: (HudProfilePreset) -> Unit,
    onAddKey: () -> Unit,
    onEditKey: (HudKey) -> Unit,
    onDeleteKey: (HudKey) -> Unit,
    mappingMode: Boolean,
    onMappingModeChange: (Boolean) -> Unit,
    plate: HudPlate,
    onPlate: (HudPlate) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
    onResetCluster: () -> Unit,
    onExportLayoutJson: () -> Unit,
    onOpenLayoutJson: () -> Unit,
    showHudButtons: Boolean,
    onShowHudButtonsChange: (Boolean) -> Unit,
    autoHideHudOnExternalInput: Boolean,
    onAutoHideHudOnExternalInputChange: (Boolean) -> Unit,
    hudOpacity: Float,
    onHudOpacityChange: (Float) -> Unit,
    openKey: String?,
    onOpenKey: (String?) -> Unit,
) {
    PanelSectionGrid(openKey = openKey, onOpenKey = onOpenKey) {
    section("tampilan-tombol-overlay",xy("Tampilan tombol overlay", "Overlay button display"), advanced = true) {
            XyToggleRow(
                title = xy("Aktifkan tombol HUD", "Show HUD buttons"),
                checked = showHudButtons,
                onCheckedChange = onShowHudButtonsChange,
            )
            XyToggleRow(
                title = xy("Sembunyikan otomatis saat input fisik tersambung", "Auto-hide when physical input is connected"),
                checked = autoHideHudOnExternalInput,
                onCheckedChange = onAutoHideHudOnExternalInputChange,
            )
            PanelHint(
                xy(
                    "Opasitas tombol HUD: {0}% (0% transparan, 100% solid). Hanya tombol overlay berubah; layar remote dan rail tidak terpengaruh.",
                    "HUD button opacity: {0}% (0% transparent, 100% opaque). Only overlay buttons change; the remote screen and rail are unaffected.",
                    (hudOpacity * 100).roundToInt(),
                ),
            )
            XySlider(
                value = hudOpacity,
                onValueChange = onHudOpacityChange,
                valueRange = 0f..1f,
            )
        }

    section("preset-profil-hud",xy("Preset Profil HUD", "HUD Profile Presets")) {
            PanelHint(
                xy(
                    "Ganti susunan tombol instan sesuai aktivitas: Standar, Coding/Terminal, Gaming/WASD, Office, atau Desain/Video.",
                    "Switch button layouts instantly by activity: Standard, Coding/Terminal, Gaming/WASD, Office, or Design/Video.",
                ),
            )
            HudProfilePreset.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { preset ->
                        XyPillButton(
                            text = xy(preset.title, preset.titleEn),
                            onClick = { onSelectHudProfile(preset) },
                            primary = preset == hudProfile,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

    section("tombol-kontrol-0",xy("Tombol kontrol ({0})", "Control buttons ({0})", keys.size)) {
            PanelHint(
                if (mappingMode) {
                    xy(
                        "Mode atur posisi MENYALA: geser tombol ke tempat yang kamu mau, " +
                            "ketuk tombol untuk ubah aksi/ukuran, atau tekan + Tambah di atas layar.",
                        "Layout mode is ON: drag buttons where you want them, tap a " +
                            "button to change its action/size, or press + Add at the top bar.",
                    )
                } else {
                    xy(
                        "Tekan \"Atur posisi & ukuran\" untuk menggeser tombol atau menambah kontrol dari bar atas (Kombinasi, F1-F12, Single Key, Numpad, Modifier, Mouse).",
                        "Press \"Edit layout & size\" to drag buttons or add controls from the top bar (Combos, F1-F12, Single Key, Numpad, Modifiers, Mouse).",
                    )
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XyPillButton(
                    xy("+ Tambah tombol", "+ Add button"),
                    onAddKey,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                XyPillButton(
                    if (mappingMode) xy("Selesai atur", "Done layout") else xy("Atur posisi", "Edit layout"),
                    { onMappingModeChange(!mappingMode) },
                    primary = mappingMode,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            HudLayoutPreview(keys)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XyPillButton(
                    xy("Salin JSON", "Copy JSON"),
                    onExportLayoutJson,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                XyPillButton(
                    xy("Impor JSON", "Import JSON"),
                    onOpenLayoutJson,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            XyPillButton(
                xy("Kembalikan bawaan", "Restore default"),
                onResetCluster,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PanelHint(
                xy(
                    "Pratinjau di bawah memakai warna tombol sungguhan di atas contoh gambar desktop, jadi hasilnya sama dengan yang tampil saat sesi berjalan.",
                    "The preview below uses the real button colors over a sample desktop, so it matches what you get during a session.",
                ),
            )
            HudPlatePicker(
                selected = plate,
                onSelect = onPlate,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (keys.isEmpty()) {
                    PanelHint(
                        xy(
                            "Belum ada tombol. Tekan \"+ Tambah tombol\".",
                            "No buttons yet. Press \"+ Add button\".",
                        ),
                    )
                }
                keys.forEach { key -> KeyRow(key, onEditKey, onDeleteKey) }
            }
            XyToggleRow(
                title = xy("Getaran saat tombol ditekan", "Haptic feedback on button press"),
                checked = haptics,
                onCheckedChange = onHaptics,
            )
        }
    }
}

// -------------------------------------------------------------- tab: sesi

@Composable
private fun SessionTab(
    deviceId: String,
    notice: id.xydesk.remote.ui.components.XyNoticeState,
    onScreenshot: () -> Unit,
    onLockRemotePc: () -> Unit,
    onActivatePrivacyCurtain: () -> Unit,
    onDisconnect: () -> Unit,
    recordingLabel: String,
    recordingStatus: String?,
    recordingActionEnabled: Boolean,
    recordingActive: Boolean,
    onToggleRecording: () -> Unit,
    coreInfo: List<String>,
    onCopyCoreInfo: () -> Unit,
    openKey: String?,
    onOpenKey: (String?) -> Unit,
) {
    PanelSectionGrid(openKey = openKey, onOpenKey = onOpenKey) {
    section("keamanan-privasi-cepat",xy("Keamanan & Privasi Cepat", "Quick Security & Privacy")) {
            PanelHint(
                xy(
                    "Kunci sesi Windows dari jarak jauh (Win+L) atau aktifkan Tirai Privasi agar layar HP tertutup gelap sementara.",
                    "Remotely lock the Windows session (Win+L) or activate the Privacy Curtain to black out the phone screen temporarily.",
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XyPillButton(
                    xy("Kunci PC", "Lock PC"),
                    onLockRemotePc,
                    icon = XyIcons.Lock,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                XyPillButton(
                    xy("Tirai Privasi", "Privacy Curtain"),
                    onActivatePrivacyCurtain,
                    icon = XyIcons.EyeOff,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

    section("perekaman-lokal-pc-ke-hp",xy("Perekaman lokal · PC ke HP", "Local recording · PC to phone"), advanced = true) {
            PanelHint(
                xy(
                    "Desktop PC + audio direkam ke MP4 di Movies/XyDesk. Layar HP tidak pernah ikut terekam.",
                    "PC desktop + audio are recorded to one MP4 in Movies/XyDesk. Your phone screen is never captured.",
                ),
            )
            XyPillButton(
                text = recordingLabel,
                onClick = onToggleRecording,
                enabled = recordingActionEnabled,
                primary = recordingActive,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            recordingStatus?.let { status ->
                Text(
                    status,
                    color = if (recordingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }

    section("utilitas-pro-makro-antiafk",xy("Utilitas Pro: Anti-AFK, Makro RP & Optimasi Engine", "Pro Utilities: Anti-AFK, RP Macros & Engine Boost")) {
            val proCtx = LocalContext.current
            val proPrefs = remember(proCtx) { proCtx.getSharedPreferences("xydesk.pro.session.engine", Context.MODE_PRIVATE) }
            var antiAfkActive by remember { mutableStateOf(proPrefs.getBoolean("anti_afk_$deviceId", false)) }
            var adaptiveFramePacing by remember { mutableStateOf(proPrefs.getBoolean("adaptive_pacing", true)) }
            var zeroLagCursorBoost by remember { mutableStateOf(proPrefs.getBoolean("zero_lag_cursor", true)) }

            XyToggleRow(
                title = xy("Anti-AFK Guard (Auto-Pulse tiap 45 dtk)", "Anti-AFK Guard (Auto-Pulse every 45s)"),
                checked = antiAfkActive,
                onCheckedChange = {
                    antiAfkActive = it
                    proPrefs.edit().putBoolean("anti_afk_$deviceId", it).apply()
                    notice.show(
                        if (it) xyNow("Anti-AFK Guard aktif — Sesi game terjaga dari kick AFK", "Anti-AFK Guard active — Game session protected from AFK kick")
                        else xyNow("Anti-AFK Guard dimatikan", "Anti-AFK Guard disabled"),
                    )
                },
            )
            XyToggleRow(
                title = xy("Adaptive Frame Pacing (60/90/120Hz Choreographer)", "Adaptive Frame Pacing (60/90/120Hz Choreographer)"),
                checked = adaptiveFramePacing,
                onCheckedChange = {
                    adaptiveFramePacing = it
                    proPrefs.edit().putBoolean("adaptive_pacing", it).apply()
                },
            )
            XyToggleRow(
                title = xy("Zero-Lag Predictive Cursor & Dirty-Rect Tile Boost", "Zero-Lag Predictive Cursor & Dirty-Rect Tile Boost"),
                checked = zeroLagCursorBoost,
                onCheckedChange = {
                    zeroLagCursorBoost = it
                    proPrefs.edit().putBoolean("zero_lag_cursor", it).apply()
                },
            )
            XyPillButton(
                text = xy("Mode AFK Hemat Baterai (Layar Hitam OLED 0%)", "OLED 0% Battery Saver AFK Mode"),
                onClick = onActivatePrivacyCurtain,
                icon = XyIcons.EyeOff,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PanelHint(
                xy(
                    "Snippet Cepat Roleplay (SA-MP / Roblox): Ketuk untuk menyalin instan ke Clipboard PC & HP.",
                    "Quick Roleplay Snippets (SA-MP / Roblox): Tap to copy instantly to PC & phone clipboard.",
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("/engine", "/lock", "/stats", "/me ").forEach { cmd ->
                    XyPillButton(
                        text = cmd.trim(),
                        onClick = {
                            val cm = proCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("RP Command", cmd))
                            notice.show(xyNow("Disalin ke Clipboard PC: $cmd", "Copied to PC Clipboard: $cmd"))
                        },
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

    section("transfer-file",xy("Transfer file HP ↔ PC", "File transfer phone ↔ PC")) {
            TransferFileSection(deviceId = deviceId, notice = notice)
        }

    section("sesi",xy("Sesi", "Session")) {
            val ctx = LocalContext.current
            val floatingHidden = InAppLiveNotificationBus.floatingOverlayHidden
            PanelHint(
                xy(
                    "Tombol bulat kanan bawah (ikon power) juga memutus — langsung kembali ke beranda setelah konfirmasi.",
                    "The bottom-right round button (power icon) also disconnects — returns straight to home after confirmation.",
                ),
            )
            XyPillButton(
                text = if (floatingHidden) {
                    xy("Tampilkan Floating Chat Global", "Show Floating Global Chat")
                } else {
                    xy("Sembunyikan Floating Chat Global", "Hide Floating Global Chat")
                },
                onClick = {
                    val nextHidden = !floatingHidden
                    InAppLiveNotificationBus.setFloatingHidden(ctx, nextHidden)
                    notice.show(
                        if (nextHidden) {
                            xyNow("Floating Chat disembunyikan dari layar sesi", "Floating Chat hidden from session screen")
                        } else {
                            xyNow("Floating Chat ditampilkan kembali", "Floating Chat restored")
                        },
                    )
                },
                icon = XyIcons.Chat,
                primary = !floatingHidden,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            XyPillButton(
                xy("Ambil screenshot", "Take screenshot"),
                onScreenshot,
                icon = XyIcons.Shot,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            XyPillButton(
                xy("Putuskan sesi", "Disconnect"),
                onDisconnect,
                icon = XyIcons.Power,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PanelSection(xy("Info teknis & modul XyCore (.so / .dll)", "Technical info & XyCore modules (.so / .dll)")) {
            XyRuntimeCatalog.sessionSummaryLines(
                coreInfo.firstOrNull() ?: "v3.4.23-xy",
            ).forEach { line ->
                Text(
                    line,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            coreInfo.drop(1).forEach { line ->
                Text(
                    line,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.5.sp,
                )
            }
            XyPillButton(
                xy("Salin info teknis & driver", "Copy technical & driver info"),
                onCopyCoreInfo,
                icon = XyIcons.Copy,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PanelChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(XyPill)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, XyPill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
    }
}

@Composable
internal fun PanelHint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

/**
 * Floating Rounded Full Bottom Navigation Bar di bagian bawah Popup Menu Sesi.
 * Berbentuk kapsul penuh (`RoundedCornerShape(50)`) dengan pill aktif beranimasi
 * untuk berpindah antar tab Layar, Input, Kontrol/HUD, dan Sesi.
 */
@Composable
private fun PanelTabTiles(
    tab: PanelTab,
    onTab: (PanelTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .shadow(14.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(Color(0xF5101013))
            .border(1.2.dp, Color(0xFF3C3C42), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelTab.entries.forEach { entry ->
            val active = entry == tab
            val bg by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                animationSpec = tween(durationMillis = 190),
                label = "panelTabBg",
            )
            val fg by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(durationMillis = 190),
                label = "panelTabFg",
            )
            val label = xy(entry.id, entry.en)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onTab(entry) }
                    .semantics { contentDescription = label }
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    panelTabIcon(entry),
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    color = fg,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun panelTabIcon(entry: PanelTab): ImageVector = when (entry) {
    PanelTab.SCREEN -> XyIcons.Fit
    PanelTab.INPUT -> XyIcons.Keyboard
    PanelTab.BUTTONS -> XyIcons.Grid
    PanelTab.SESSION -> XyIcons.Gear
}

/**
 * Ikon kotak untuk satu seksi panel. Dicocokkan dari kunci seksi, bukan judul,
 * supaya perubahan terjemahan judul tidak mengubah ikonnya.
 */
private fun panelSectionIcon(key: String): ImageVector = when {
    key.startsWith("telemetri") -> XyIcons.Monitor
    key.startsWith("ukuran-tampilan") -> XyIcons.Fit
    key.startsWith("mesin-direct-pc-stream") -> XyIcons.Terminal
    key.startsWith("skala-tampilan-windows") -> XyIcons.Windows
    key.startsWith("resolusi-desktop") -> XyIcons.DualMonitor
    key.startsWith("orientasi") -> XyIcons.Rotate
    key.startsWith("mode-input") -> XyIcons.Mouse
    key.startsWith("fisika-trackpad") -> XyIcons.Sliders
    key.startsWith("clipboard") -> XyIcons.Copy
    key.startsWith("pointer") -> XyIcons.Cursor
    key.startsWith("tampilan-tombol") -> XyIcons.Eye
    key.startsWith("preset-profil-hud") -> XyIcons.Star
    key.startsWith("tombol-kontrol") -> XyIcons.Grid
    key.startsWith("transfer-file") -> XyIcons.Swap
    key.startsWith("keamanan") -> XyIcons.Shield
    key.startsWith("perekaman") -> XyIcons.Shot
    key.startsWith("sesi") -> XyIcons.Power
    key.startsWith("info-teknis") -> XyIcons.Info
    else -> XyIcons.ChevronRight
}

/**
 * Cakupan pembangun [PanelSectionGrid].
 *
 * Seksi direkam sebagai data dulu, tidak langsung digambar. Inilah yang membuat
 * panel bisa tampil sebagai kotak-kotak: selama belum ada seksi yang dibuka, yang
 * digambar hanya ikon + judul, jadi satu tab muat tanpa digulir. Isi seksi baru
 * disusun untuk satu seksi yang sedang terbuka.
 */
internal class PanelSectionScope {
    internal class Spec(
        val key: String,
        val title: String,
        val advanced: Boolean = false,
        val content: @Composable () -> Unit,
    )

    internal val specs: MutableList<Spec> = mutableListOf()

    /**
     * Rekam satu seksi. [content] belum dijalankan di sini.
     * [advanced] = seksi jarang dipakai; disembunyikan di balik ubin
     * "Lanjutan" supaya menu utama tetap pendek dan bersih.
     */
    fun section(
        key: String,
        title: String,
        advanced: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        specs.add(Spec(key, title, advanced, content))
    }
}

/**
 * Daftar seksi panel sebagai grid kotak dengan drill-in.
 *
 * Menggantikan accordion. Satu tab berisi 3-6 seksi dan tiap seksi memuat banyak
 * blok penjelasan, jadi accordion pun tetap menuntut scroll. Di sini tab digambar
 * sebagai dua kolom kotak; mengetuk satu kotak membuka seksi itu penuh lebar
 * dengan tombol kembali. Seksi bersarang di dalam badan seksi tetap memakai
 * [PanelSection] biasa.
 *
 * [builder] dijalankan tiap komposisi karena judul seksi memakai `xy()` yang
 * composable dan beberapa seksi bersyarat (`if (pcConnectMode)`). Scope dibuat
 * baru tiap kali supaya seksi dari komposisi sebelumnya tidak tertinggal.
 */
@Composable
private fun QuickActionPill(
    icon: ImageVector,
    label: String,
    active: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        danger -> Color(0xFF232327)
        active -> Color(0xFFF2F2F4)
        else -> Color(0xFF1A1A1E)
    }
    val borderCol = when {
        danger -> Color(0xFF858585)
        active -> Color(0xFFFFFFFF)
        else -> Color(0xFF3C3C42)
    }
    val fg = when {
        danger -> Color(0xFFE4E4E8)
        active -> Color(0xFF111114)
        else -> Color(0xFFD4D4D8)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, borderCol, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (active || danger) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PanelSectionGrid(
    openKey: String?,
    onOpenKey: (String?) -> Unit,
    builder: @Composable PanelSectionScope.() -> Unit,
) {
    val scope = PanelSectionScope()
    scope.builder()
    val allSpecs = scope.specs
    if (allSpecs.isEmpty()) return
    val advancedCount = allSpecs.count { it.advanced }
    var showAdvanced by remember { mutableStateOf(true) }
    val specs = if (showAdvanced) allSpecs else allSpecs.filter { !it.advanced }
    if (specs.isEmpty()) return
    val resolvedOpenKey = if (openKey == "__FIRST__") specs.firstOrNull()?.key else openKey
    Column(
        Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        specs.chunked(2).forEach { rowSpecs ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowSpecs.forEach { spec ->
                    val selected = spec.key == resolvedOpenKey
                    Box(Modifier.weight(1f)) {
                        XyGlassTile(
                            icon = panelSectionIcon(spec.key),
                            title = spec.title,
                            onClick = { onOpenKey(if (selected) null else spec.key) },
                            selected = selected,
                            titleMaxLines = 2,
                            trailing = {
                                Icon(
                                    imageVector = if (selected) XyIcons.ChevronUp else XyIcons.ChevronDown,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
                repeat(2 - rowSpecs.size) { Spacer(Modifier.weight(1f)) }
            }
            val open = rowSpecs.firstOrNull { it.key == resolvedOpenKey }
            if (open != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1A1A1E), RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFF3C3C42), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        open.title.uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    open.content()
                }
            }
        }
        if (advancedCount > 0) {
            XyGlassTile(
                icon = XyIcons.Sliders,
                title = if (showAdvanced) {
                    xy("Sembunyikan seksi lanjutan", "Hide advanced sections")
                } else {
                    xy(
                        "Lanjutan ({0} seksi lagi)",
                        "Advanced ({0} more sections)",
                        advancedCount,
                    )
                },
                onClick = {
                    showAdvanced = !showAdvanced
                    // Seksi lanjutan yang sedang terbuka ikut tertutup supaya
                    // isi yang disembunyikan tidak menggantung tanpa ubinnya.
                    if (!showAdvanced) onOpenKey(null)
                },
                selected = false,
                titleMaxLines = 1,
                trailing = {
                    Icon(
                        imageVector = if (showAdvanced) XyIcons.ChevronUp else XyIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
internal fun PanelSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Setiap seksi digambar sebagai kartu yang bisa dilipat. Satu fungsi ini
    // dipakai 17 seksi di empat tab, jadi seluruh panel ikut berubah tanpa
    // menyentuh masing-masing tab.
    //
    // Alasan accordion: satu tab berisi 3-6 seksi dengan total 24 blok teks
    // penjelasan, sehingga tidak mungkin semua muat sekaligus di layar HP.
    // Dengan seksi terlipat jadi judul saja, satu tab muat tanpa scroll;
    // seksi pertama tiap tab tetap terbuka supaya isinya langsung kelihatan.
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    Column(
        Modifier
            .fillMaxWidth()
            // Kartu kaca: satu perubahan di sini mengubah 18 seksi di empat tab,
            // karena semua seksi digambar lewat fungsi ini.
            .xyGlass(shape = RoundedCornerShape(14.dp), opacity = 1.25f, strength = 0.9f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title.uppercase(),
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = if (expanded) XyIcons.ChevronUp else XyIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { content() }
        }
    }
}

@Composable
private fun KeyRow(
    key: HudKey,
    onEdit: (HudKey) -> Unit,
    onDelete: (HudKey) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp))
            .clickable { onEdit(key) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            HudKeyGlyph(key = key, tint = MaterialTheme.colorScheme.onSurface, boxDp = 30f)
        }
        Column(Modifier.weight(1f)) {
            Text(
                key.label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                xy(
                    "{0} · {1} · {2} dp · {3}%,{4}%",
                    "{0} · {1} · {2} dp · {3}%,{4}%",
                    xy(key.kind.title, key.kind.titleEn),
                    xy(key.action.title, key.action.titleEn),
                    key.size.toInt(),
                    (key.x * 100).toInt(),
                    (key.y * 100).toInt(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable { onDelete(key) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                XyIcons.Trash,
                contentDescription = xy("Hapus", "Delete"),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private data class MonitorGridItem(
    val badge: String,
    val title: String,
    val subtitle: String,
    val value: String,
    val icon: ImageVector,
    val active: Boolean,
)

/**
 * Modal Grid Monitor & Sesi User Aktif (dibuka dari tombol Monitor di atas Disconnect).
 * Mendeteksi otomatis monitor aktif (DISP / resolusi sesi) serta sesi konsol (/admin)
 * maupun akun user tersimpan untuk berpindah secara langsung saat sesi berlangsung.
 */
@Composable
private fun MonitorAndUserGridModal(
    deviceId: String,
    hostLabel: String,
    remoteSize: String,
    codecLabel: String,
    resolution: String,
    pcConnectMode: Boolean,
    activeUsername: String?,
    consoleAdminMode: Boolean,
    onSelectMonitorResolution: (String) -> Unit,
    onSwitchConsoleMode: (Boolean) -> Unit,
    onSwitchUserSession: (String, String?, String?) -> Unit,
    onActivatePrivacyCurtain: () -> Unit,
    onLockRemotePc: () -> Unit,
    onOpenHome: () -> Unit,
    notice: XyNoticeState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var pcOptions by remember(deviceId) { mutableStateOf(RdpOptions.of(context, deviceId)) }
    val repo = remember { id.xydesk.remote.sessions.SessionsRepository(context) }
    val allFavorites by repo.favorites().collectAsState(initial = emptyList())
    val savedAccounts = remember(allFavorites) {
        allFavorites
            .filter { !it.username.isNullOrBlank() && !it.username.equals("XyDesk", ignoreCase = true) }
            .distinctBy { "${it.domain.orEmpty().lowercase()}\\${it.username.orEmpty().lowercase()}" }
    }
    var customUserOpen by remember { mutableStateOf(false) }
    var customUsername by remember { mutableStateOf(activeUsername.orEmpty()) }
    var customDomain by remember { mutableStateOf("") }
    var customPassword by remember { mutableStateOf("") }

    val cleanRemoteSize = remoteSize.replace(" ", "")
    val monitorItems = listOf(
        MonitorGridItem(
            badge = "MON 1",
            title = xy("Monitor 1 · Utama", "Monitor 1 · Primary"),
            subtitle = if (cleanRemoteSize.isNotBlank() && cleanRemoteSize != "--") "$cleanRemoteSize · 16:9" else "1280x720 · 16:9",
            value = DisplayPrefs.AUTOMATIC,
            icon = XyIcons.Monitor,
            active = resolution == DisplayPrefs.AUTOMATIC || resolution == "1280x720",
        ),
        MonitorGridItem(
            badge = "MON 2",
            title = xy("Monitor 2 · QHD", "Monitor 2 · QHD"),
            subtitle = "2560x1440 · Studio",
            value = "2560x1440",
            icon = XyIcons.DualMonitor,
            active = resolution == "2560x1440",
        ),
        MonitorGridItem(
            badge = "SPAN",
            title = xy("Dual-Monitor Span", "Dual-Monitor Span"),
            subtitle = "2560x1080 · 21:9",
            value = "2560x1080",
            icon = XyIcons.DualMonitor,
            active = resolution == "2560x1080",
        ),
        MonitorGridItem(
            badge = "VIEW",
            title = xy("Layar Penuh HP", "Phone Native View"),
            subtitle = xy("Ikuti Viewport", "Follow Viewport"),
            value = DisplayPrefs.FOLLOW,
            icon = XyIcons.Fit,
            active = resolution == DisplayPrefs.FOLLOW,
        ),
    )

    XyOverlay(
        title = if (pcConnectMode) {
            xy("Monitor Fisik 1:1 & Mesin Direct Stream", "1:1 Physical Monitor & Direct Stream Engine")
        } else {
            xy("Monitor & Sesi Multi-User / Konsol RDP", "Active Monitors & Multi-User / Console Grid")
        },
        maxWidth = 440.dp,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Baris status deteksi otomatis
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    XyIcons.DualMonitor,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "$hostLabel · $remoteSize",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            if (pcConnectMode) {
                                append(xy("Koneksi PC · Direct Stream 1:1", "PC Connect · 1:1 Direct Stream"))
                                append(" · ${pcOptions.pcStreamEngine.badge}")
                            } else {
                                append(
                                    if (consoleAdminMode) xy("Konsol Fisik / Runner (/admin)", "Physical / Runner Console (/admin)")
                                    else xy("Sesi Virtual Multi-User RDP", "Multi-User Virtual RDP Session"),
                                )
                                if (!activeUsername.isNullOrBlank()) append(" · $activeUsername")
                            }
                            append(" · $codecLabel")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (pcConnectMode) {
                // Mode Koneksi PC: Eksklusif Direct Stream Engine (Tidak ada Multi-User RDP)
                Text(
                    text = xy("MESIN DIRECT PC STREAM (EKSKLUSIF KONEKSI PC)", "DIRECT PC STREAM ENGINE (PC CONNECT EXCLUSIVE)"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp,
                )
                id.xydesk.remote.core.XyPcStreamEngine.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { engine ->
                            val isActive = pcOptions.pcStreamEngine == engine
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                    )
                                    .border(
                                        if (isActive) 1.4.dp else 1.dp,
                                        if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(11.dp),
                                    )
                                    .clickable {
                                        val next = pcOptions.withPcStreamEngine(engine)
                                        pcOptions = next
                                        next.write(context, deviceId)
                                        notice.show(xyNow("Mesin Direct Stream: {0} aktif", "Direct Stream Engine: {0} active", engine.badge))
                                    }
                                    .padding(10.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(
                                            XyIcons.Monitor,
                                            contentDescription = null,
                                            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = if (isActive) xy("AKTIF", "ACTIVE") else engine.badge,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = xy(engine.title, engine.titleEn),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = xy(engine.detail, engine.detailEn),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 9.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XyPillButton(
                        text = xy("Layar Stealth (Privacy)", "Stealth Screen (Privacy)"),
                        icon = XyIcons.EyeOff,
                        onClick = onActivatePrivacyCurtain,
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    XyPillButton(
                        text = xy("Kunci Fisik PC (Win+L)", "Lock Physical PC (Win+L)"),
                        icon = XyIcons.Windows,
                        onClick = onLockRemotePc,
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                // Mode Koneksi RDP: Grid Monitor Virtual + Multi-User & Konsol Runner
                Text(
                    text = xy("GRID MONITOR & LAYAR AKTIF", "ACTIVE MONITORS & DISPLAYS GRID"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp,
                )
                monitorItems.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { item ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (item.active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                    )
                                    .border(
                                        if (item.active) 1.4.dp else 1.dp,
                                        if (item.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(11.dp),
                                    )
                                    .clickable {
                                        onSelectMonitorResolution(item.value)
                                    }
                                    .padding(10.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(
                                            item.icon,
                                            contentDescription = null,
                                            tint = if (item.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(17.dp),
                                        )
                                        Text(
                                            text = if (item.active) xy("AKTIF", "ACTIVE") else item.badge,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (item.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = item.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = item.subtitle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 9.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // Grid Sesi User & Konsol Runner Windows
                Text(
                    text = xy("GRID SESI WINDOWS: KONSOL RUNNER (/ADMIN) VS MULTI-USER", "WINDOWS SESSIONS: RUNNER CONSOLE (/ADMIN) VS MULTI-USER"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp,
                )
                val isConsole = consoleAdminMode && !activeUsername.equals("XyDesk", ignoreCase = true)
                val isVirtualUser = !isConsole
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Card 1: Sesi Konsol Runner (/admin)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (isConsole) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            )
                            .border(
                                if (isConsole) 1.4.dp else 1.dp,
                                if (isConsole) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(11.dp),
                            )
                            .clickable { onSwitchConsoleMode(true) }
                            .padding(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    XyIcons.Monitor,
                                    contentDescription = null,
                                    tint = if (isConsole) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(17.dp),
                                )
                                Text(
                                    text = if (isConsole) xy("AKTIF", "ACTIVE") else "/ADMIN",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isConsole) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = xy("Konsol Runner / Admin", "Runner / Admin Console"),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                text = xy("Sesi 1 Konsol (runneradmin)", "Session 1 Console (runneradmin)"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.5.sp,
                                maxLines = 1,
                            )
                        }
                    }

                    // Card 2: Sesi User RDP Virtual
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (isVirtualUser) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            )
                            .border(
                                if (isVirtualUser) 1.4.dp else 1.dp,
                                if (isVirtualUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(11.dp),
                            )
                            .clickable { onSwitchConsoleMode(false) }
                            .padding(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    XyIcons.Users,
                                    contentDescription = null,
                                    tint = if (isVirtualUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(17.dp),
                                )
                                Text(
                                    text = if (isVirtualUser) xy("AKTIF", "ACTIVE") else "MULTI",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isVirtualUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = xy("Sesi Virtual Multi-User", "Multi-User Virtual Session"),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = xy("Sesi 2 Terpisah (XyDesk)", "Separate Session 2 (XyDesk)"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.5.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // Target Akun Cepat (Runneradmin vs XyDesk + Akun Tersimpan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isRunnerActive = activeUsername.equals("runneradmin", ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                            .border(
                                1.dp,
                                if (isRunnerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                RdpOptions.of(context, deviceId).copy(consoleAdmin = true).write(context, deviceId)
                                onSwitchUserSession("runneradmin", null, null)
                            }
                            .padding(9.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "runneradmin (/admin)",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = xy("Pindah ke Konsol Runner", "Switch to Runner Console"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.5.sp,
                            )
                        }
                    }
                    val isXyDeskActive = activeUsername.equals("XyDesk", ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                            .border(
                                1.dp,
                                if (isXyDeskActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                RdpOptions.of(context, deviceId).copy(consoleAdmin = false).write(context, deviceId)
                                onSwitchUserSession("XyDesk", null, null)
                            }
                            .padding(9.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "XyDesk (Multi-User)",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = xy("Pindah ke Sesi Virtual 2", "Switch to Virtual Session 2"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.5.sp,
                            )
                        }
                    }
                }

                if (savedAccounts.isNotEmpty()) {
                    savedAccounts.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { acc ->
                                val accUser = acc.username.orEmpty()
                                val isCurrentAcc = !activeUsername.isNullOrBlank() &&
                                    activeUsername.equals(accUser, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                        .border(
                                            1.dp,
                                            if (isCurrentAcc) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable {
                                            onSwitchUserSession(accUser, acc.domain, acc.password)
                                        }
                                        .padding(9.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Icon(
                                            XyIcons.Users,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = acc.label?.takeIf { it.isNotBlank() } ?: accUser,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = accUser,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 9.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                if (customUserOpen) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        XyField(
                            value = customUsername,
                            onValueChange = { customUsername = it },
                            label = xy("Username Windows", "Windows Username"),
                            hint = "runneradmin / Administrator / XyDesk",
                        )
                        XyField(
                            value = customPassword,
                            onValueChange = { customPassword = it },
                            label = xy("Password (opsional)", "Password (optional)"),
                            isPassword = true,
                        )
                        XyPillButton(
                            text = xy("Pindah ke User Ini Sekarang", "Switch to This User Now"),
                            icon = XyIcons.Users,
                            onClick = {
                                val u = customUsername.trim()
                                if (u.isNotEmpty()) {
                                    onSwitchUserSession(
                                        u,
                                        customDomain.trim().takeIf { it.isNotEmpty() },
                                        customPassword.takeIf { it.isNotEmpty() },
                                    )
                                }
                            },
                            compact = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XyPillButton(
                        text = xy("Pindah User Lain", "Switch User"),
                        icon = XyIcons.Users,
                        onClick = { customUserOpen = !customUserOpen },
                        primary = customUserOpen,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    XyPillButton(
                        text = xy("Layar Kunci Windows", "Windows Lock Screen"),
                        icon = XyIcons.Windows,
                        onClick = onLockRemotePc,
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XyPillButton(
                    text = xy("Perangkat Lain (Beranda)", "Other Devices (Home)"),
                    icon = XyIcons.Grid,
                    onClick = onOpenHome,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                XyPillButton(
                    text = xy("Tutup", "Close"),
                    icon = XyIcons.Close,
                    onClick = onDismiss,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Badge "REC" + waktu berjalan selama perekaman aktif.
 *
 * Selalu tampil selama [recordingActive] benar, termasuk saat panel atau mode
 * pemetaan tombol terbuka, supaya pengguna tidak lupa rekaman masih jalan.
 * Komponen ini hanya digambar saat merekam, jadi `remember` di dalamnya mulai
 * dari nol tiap sesi rekaman baru.
 */
@Composable
private fun RecordingIndicator(modifier: Modifier = Modifier) {
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsedMs by remember { mutableStateOf(0L) }
    var dotOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            dotOn = !dotOn
            delay(500L)
        }
    }
    val totalSeconds = elapsedMs / 1000L
    val clock = "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.68f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    MaterialTheme.colorScheme.error.copy(alpha = if (dotOn) 1f else 0.25f),
                    CircleShape,
                ),
        )
        Text(
            text = "REC",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = clock,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
        )
    }
}

/**
 * Riwayat clipboard: 10 teks terakhir yang disalin di HP. Ketuk satu entri
 * untuk menyalinnya ulang; bila kanal clipboard sesi aktif, sinkronisasi
 * otomatis yang sudah ada langsung meneruskannya ke PC.
 */
@Composable
private fun ClipboardHistoryBlock(clipboardSyncEnabled: Boolean) {
    val context = LocalContext.current
    val history by ClipboardHistory.entries.collectAsState()
    LaunchedEffect(Unit) { ClipboardHistory.load(context) }
    var feedback by remember { mutableStateOf<String?>(null) }

    Text(
        xy("Riwayat clipboard", "Clipboard history").uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (history.isEmpty()) {
        Text(
            xy(
                "Belum ada yang tersalin. Salin teks di HP atau di PC, lalu daftar ini terisi sendiri (maksimal 10 entri terakhir).",
                "Nothing copied yet. Copy text on the phone or the PC and this list fills itself (last 10 entries).",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        history.forEach { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .clickable {
                        val ok = ClipboardHistory.copyToPhone(context, entry)
                        feedback = when {
                            !ok -> xyNow(
                                "Tidak bisa menyalin ke clipboard HP.",
                                "Could not copy to the phone clipboard.",
                            )
                            clipboardSyncEnabled -> xyNow(
                                "Disalin — otomatis diteruskan ke PC.",
                                "Copied — forwarded to the PC automatically.",
                            )
                            else -> xyNow(
                                "Disalin ke clipboard HP.",
                                "Copied to the phone clipboard.",
                            )
                        }
                    }
                    .padding(vertical = 7.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        xy(
                            "{0} karakter",
                            "{0} characters",
                            entry.text.length,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                    )
                }
                XyPillButton(
                    "\u2715",
                    {
                        ClipboardHistory.remove(context, entry.id)
                        feedback = null
                    },
                    primary = false,
                    compact = true,
                )
            }
        }
    }
    feedback?.let { message ->
        Text(
            message,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
        )
    }
    XyPillButton(
        xy("Hapus semua riwayat", "Clear all history"),
        {
            ClipboardHistory.clear(context)
            feedback = null
        },
        primary = false,
        compact = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
