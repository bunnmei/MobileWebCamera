package space.webkombinat.MobileWebCamera.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import space.webkombinat.MobileWebCamera.R
import space.webkombinat.MobileWebCamera.util.asString
import android.hardware.camera2.CameraMetadata
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import space.webkombinat.MobileWebCamera.camera.CameraCapabilities
import space.webkombinat.MobileWebCamera.camera.CameraController
import space.webkombinat.MobileWebCamera.camera.CameraUiState
import space.webkombinat.MobileWebCamera.camera.ControlGroup
import space.webkombinat.MobileWebCamera.camera.RotationMode
import space.webkombinat.MobileWebCamera.stream.StreamStatus
import space.webkombinat.MobileWebCamera.ui.components.ChoiceChips
import space.webkombinat.MobileWebCamera.ui.components.Hint
import space.webkombinat.MobileWebCamera.ui.components.LabeledSlider
import space.webkombinat.MobileWebCamera.ui.components.LogSlider
import space.webkombinat.MobileWebCamera.ui.components.SectionTitle
import space.webkombinat.MobileWebCamera.ui.components.SwitchRow
import space.webkombinat.MobileWebCamera.ui.components.formatFocus
import space.webkombinat.MobileWebCamera.ui.components.formatShutter
import kotlin.math.roundToInt

private enum class PanelTab(@StringRes val title: Int) {
    STREAM(R.string.tab_stream),
    LENS(R.string.tab_lens),
    FOCUS(R.string.tab_focus),
    EXPOSURE(R.string.tab_exposure),
    COLOR(R.string.tab_color),
    IMAGE(R.string.tab_image),
    ADVANCED(R.string.tab_advanced),
    INFO(R.string.tab_info),
}

@Composable
fun ControlPanel(modifier: Modifier, state: CameraUiState, stream: StreamStatus, vm: CameraViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Surface で包んで LocalContentColor を設定する（無いと色未指定の Text が黒になる）
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            PanelTab.entries.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t.title)) })
            }
        }
        val caps = state.caps
        val current = PanelTab.entries[tab]
        if (current == PanelTab.INFO) {
            InfoTab(state)
            return@Column
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
        ) {
            when {
                current == PanelTab.STREAM -> StreamTab(state, stream, vm)
                caps == null -> Hint(stringResource(R.string.camera_preparing))
                else -> {
                    val cam = vm.camera
                    when (current) {
                        PanelTab.LENS -> LensTab(state, caps, cam)
                        PanelTab.FOCUS -> FocusTab(state, caps, cam)
                        PanelTab.EXPOSURE -> ExposureTab(state, caps, cam)
                        PanelTab.COLOR -> ColorTab(state, caps, cam)
                        PanelTab.IMAGE -> EnumGroup(state, caps, cam, ControlGroup.IMAGE)
                        PanelTab.ADVANCED -> AdvancedTab(state, caps, cam)
                        PanelTab.STREAM, PanelTab.INFO -> Unit
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

@Composable
private fun EnumGroup(state: CameraUiState, caps: CameraCapabilities, cam: CameraController, group: ControlGroup) {
    val context = LocalContext.current
    caps.enumControls.filter { it.group == group }.forEach { ctl ->
        ChoiceChips(
            title = ctl.label.asString(),
            options = ctl.options,
            selected = ctl.options.firstOrNull { it.value == state.settings.enums[ctl.id] },
            label = { it.label.resolve(context) },
            onSelect = { cam.setEnum(ctl.id, it.value) },
        )
    }
}

// ------------------------------------------------------------------ 配信

@Composable
private fun StreamTab(state: CameraUiState, stream: StreamStatus, vm: CameraViewModel) {
    val cam = vm.camera
    Spacer(Modifier.height(8.dp))
    val context = LocalContext.current
    fun localNetworkGranted() = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
    var localNetworkOk by remember { mutableStateOf(localNetworkGranted()) }
    val localNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localNetworkOk = granted
        if (granted && !state.streaming) vm.toggleStreaming()
    }
    Button(
        onClick = {
            // 権限が無いと OS が PC からの接続を遮断するため、配信開始前に確認する
            if (!state.streaming && !localNetworkGranted()) {
                localNetworkLauncher.launch(LOCAL_NETWORK_PERMISSION)
            } else {
                vm.toggleStreaming()
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = if (state.streaming) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        else ButtonDefaults.buttonColors(),
    ) { Text(stringResource(if (state.streaming) R.string.stream_stop else R.string.stream_start)) }
    stream.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
    if (!localNetworkOk) {
        Text(
            stringResource(R.string.local_network_denied),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (state.streaming) {
        Hint(stringResource(R.string.stream_clients, stream.tsClients, stream.encoderSize ?: ""))
    }

    // テザリングの付け直しで IP が変わるため、表示中は定期的に取り直す
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshAddresses()
            delay(3000)
        }
    }
    SectionTitle(stringResource(R.string.section_addresses))
    if (stream.addresses.isEmpty()) {
        Text(
            stringResource(R.string.no_network),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    stream.addresses.forEach { addr ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = {}, label = { Text(addr.kind.label) }, modifier = Modifier.width(80.dp))
            Spacer(Modifier.width(8.dp))
            SelectionContainer {
                Text(
                    "tcp://${addr.ip}:${stream.tsPort}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
    Hint(stringResource(R.string.obs_hint))

    val caps = state.caps ?: return
    ChoiceChips(
        title = stringResource(R.string.resolution_title),
        options = caps.streamSizes,
        selected = state.streamConfig.size,
        label = { "${it.width}x${it.height}" },
        onSelect = cam::setStreamSize,
    )
    ChoiceChips(
        title = stringResource(R.string.fps_title),
        options = caps.fpsRanges,
        selected = state.streamConfig.fps,
        label = { if (it.lower == it.upper) "${it.upper}fps" else "${it.lower}-${it.upper}" },
        onSelect = cam::setFps,
    )
    SectionTitle(stringResource(R.string.orientation_title))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        RotationMode.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = state.streamConfig.rotationMode == mode,
                onClick = { cam.setRotationMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, RotationMode.entries.size),
                icon = {}, // チェックマーク分の幅を文字に回す
                label = {
                    // 1 行に収まる短い表記にして全ボタンの高さを揃える（正式名は下の説明に表示）。
                    // 言語によって長さが違うので、収まらない場合は文字を縮める
                    val contentColor = LocalContentColor.current
                    BasicText(
                        stringResource(mode.shortLabel),
                        maxLines = 1,
                        softWrap = false,
                        color = { contentColor },
                        style = MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center),
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                    )
                },
            )
        }
    }
    val holding = stringResource(RotationMode.holdingLabel(state.effectiveHolding))
    Hint(
        if (state.streamConfig.rotationMode == RotationMode.AUTO) stringResource(R.string.holding_detected, holding)
        else stringResource(R.string.holding_fixed, holding)
    )
    Hint(
        stringResource(
            R.string.orientation_explain,
            stringResource(RotationMode.LANDSCAPE_RIGHT_UP.shortLabel),
            stringResource(RotationMode.LANDSCAPE_RIGHT_UP.label),
            stringResource(RotationMode.LANDSCAPE_LEFT_UP.shortLabel),
            stringResource(RotationMode.LANDSCAPE_LEFT_UP.label),
        )
    )
    val mbps = state.streamConfig.bitrate / 1_000_000f
    LabeledSlider(
        stringResource(R.string.bitrate), "%.1f Mbps".format(mbps), mbps, 1f..50f,
        onChange = { cam.setBitrate((it * 1_000_000).toInt()) },
    )
    OutlinedButton(onClick = cam::requestKeyFrame) { Text(stringResource(R.string.request_keyframe)) }
}

// ------------------------------------------------------------------ レンズ

@Composable
private fun LensTab(state: CameraUiState, caps: CameraCapabilities, cam: CameraController) {
    val context = LocalContext.current
    ChoiceChips(
        title = stringResource(R.string.lens_title),
        options = state.lenses,
        selected = state.lenses.firstOrNull { it.key == state.currentLens?.key },
        label = { it.label.resolve(context) },
        onSelect = cam::selectLens,
    )
    Hint(stringResource(R.string.lens_hint))

    val z = caps.zoomRange
    if (z.upper > z.lower) {
        LogSlider(
            stringResource(R.string.zoom), "%.2fx".format(state.settings.zoomRatio),
            state.settings.zoomRatio.toDouble(), z.lower.toDouble(), z.upper.toDouble(),
        ) { cam.setZoom(it.toFloat()) }
        ChoiceChips(stringResource(R.string.zoom_presets), caps.zoomPresets, null, { "%.1fx".format(it) }, cam::setZoom)
    }

    caps.floatControls.firstOrNull { it.key == CaptureRequest.LENS_FOCAL_LENGTH }?.let { ctl ->
        ChoiceChips(ctl.label.asString(), ctl.options, state.settings.floats[ctl.id], { "%.2f".format(it) }) {
            cam.setFloat(ctl.id, it)
        }
    }

    if (caps.flashAvailable) {
        SectionTitle(stringResource(R.string.section_light))
        SwitchRow(stringResource(R.string.torch), state.settings.torch, cam::setTorch)
        if (caps.torchStrengthMax > 1) {
            LabeledSlider(
                stringResource(R.string.torch_strength), "${state.settings.torchStrength}/${caps.torchStrengthMax}",
                state.settings.torchStrength.toFloat(), 1f..caps.torchStrengthMax.toFloat(),
                onChange = { cam.setTorchStrength(it.roundToInt()) },
            )
        }
    }
    SectionTitle(stringResource(R.string.section_hardware))
    Hint(stringResource(R.string.hardware_level, caps.hardwareLevel))
    Hint(caps.capabilities.joinToString(", "))
}

// ------------------------------------------------------------------ フォーカス

@Composable
private fun FocusTab(state: CameraUiState, caps: CameraCapabilities, cam: CameraController) {
    EnumGroup(state, caps, cam, ControlGroup.FOCUS)
    val afMode = state.settings.enums[CaptureRequest.CONTROL_AF_MODE.name]
    val minFocus = caps.minFocusDistance
    if (minFocus == null) {
        Hint(stringResource(R.string.fixed_focus))
        return
    }
    if (afMode == CameraMetadata.CONTROL_AF_MODE_OFF) {
        LabeledSlider(
            stringResource(R.string.focus_position), formatFocus(state.settings.focusDistance),
            state.settings.focusDistance, 0f..minFocus,
            onChange = cam::setFocusDistance,
        )
        Hint(stringResource(R.string.min_focus_distance, formatFocus(minFocus)))
        caps.hyperfocalDistance?.takeIf { it > 0f }?.let { h ->
            OutlinedButton(onClick = { cam.setFocusDistance(h) }) { Text(stringResource(R.string.hyperfocal_distance, formatFocus(h))) }
        }
        OutlinedButton(onClick = { cam.setFocusDistance(0f) }) { Text(stringResource(R.string.focus_infinity)) }
    } else {
        Hint(stringResource(R.string.tap_focus_hint))
        OutlinedButton(onClick = cam::resetFocus) { Text(stringResource(R.string.reset_tap_focus)) }
    }
    SectionTitle(stringResource(R.string.section_current_values))
    Hint(stringResource(R.string.af_readings, state.readings.afState, state.readings.focusDistance?.let(::formatFocus) ?: "-"))
}

// ------------------------------------------------------------------ 露出

@Composable
private fun ExposureTab(state: CameraUiState, caps: CameraCapabilities, cam: CameraController) {
    EnumGroup(state, caps, cam, ControlGroup.EXPOSURE)
    val s = state.settings
    val aeOff = s.enums[CaptureRequest.CONTROL_AE_MODE.name] == CameraMetadata.CONTROL_AE_MODE_OFF
    val priority = if (Build.VERSION.SDK_INT >= 36) s.enums[CaptureRequest.CONTROL_AE_PRIORITY_MODE.name] ?: 0 else 0

    val iso = caps.isoRange
    if (iso != null && (aeOff || priority == 1)) {
        LogSlider("ISO", "${s.iso}", s.iso.toDouble(), iso.lower.toDouble(), iso.upper.toDouble()) {
            cam.setIso(it.roundToInt())
        }
    }
    val exp = caps.exposureTimeRange
    if (exp != null && (aeOff || priority == 2)) {
        // 動画なので上限はフレーム間隔程度に抑える（最大 1/2 秒）
        val maxExp = minOf(exp.upper, 500_000_000L)
        LogSlider(stringResource(R.string.shutter_speed), formatShutter(s.exposureTimeNs), s.exposureTimeNs.toDouble(),
            exp.lower.toDouble(), maxExp.toDouble()) { cam.setExposureTime(it.toLong()) }
        Hint(stringResource(R.string.long_exposure_hint))
    }
    if (aeOff && iso == null) Hint(stringResource(R.string.no_manual_exposure))

    if (!aeOff) {
        val ev = caps.evRange
        if (ev.upper > ev.lower) {
            LabeledSlider(
                stringResource(R.string.exposure_compensation), "%+.1f EV".format(s.evCompensation * caps.evStep),
                s.evCompensation.toFloat(), ev.lower.toFloat()..ev.upper.toFloat(),
                onChange = { cam.setEv(it.roundToInt()) },
                steps = (ev.upper - ev.lower - 1).coerceAtLeast(0),
            )
        }
        if (caps.aeLockAvailable) SwitchRow(stringResource(R.string.ae_lock), s.aeLock, cam::setAeLock)
    }
    SectionTitle(stringResource(R.string.section_current_values))
    val r = state.readings
    Hint(
        stringResource(
            R.string.exposure_readings,
            r.iso?.toString() ?: "-",
            r.exposureTimeNs?.let(::formatShutter) ?: "-",
            r.frameDurationNs?.let { "%.1ffps".format(1e9 / it) } ?: "-",
        )
    )
}

// ------------------------------------------------------------------ 色

@Composable
private fun ColorTab(state: CameraUiState, caps: CameraCapabilities, cam: CameraController) {
    EnumGroup(state, caps, cam, ControlGroup.COLOR)
    val s = state.settings
    val awbOff = s.enums[CaptureRequest.CONTROL_AWB_MODE.name] == CameraMetadata.CONTROL_AWB_MODE_OFF
    if (awbOff) {
        val range = caps.cctRange
        when {
            range != null -> {
                LabeledSlider(stringResource(R.string.color_temperature), "${s.colorTemperature}K", s.colorTemperature.toFloat(),
                    range.lower.toFloat()..range.upper.toFloat(), onChange = { cam.setColorTemperature(it.roundToInt()) })
                LabeledSlider(stringResource(R.string.color_tint), "${s.colorTint}", s.colorTint.toFloat(), -50f..50f,
                    onChange = { cam.setColorTint(it.roundToInt()) })
            }

            caps.manualWbGains -> {
                LabeledSlider(stringResource(R.string.color_temperature_approx), "${s.colorTemperature}K", s.colorTemperature.toFloat(),
                    2000f..10000f, onChange = { cam.setColorTemperature(it.roundToInt()) })
            }

            else -> Hint(stringResource(R.string.no_manual_wb))
        }
    } else if (caps.awbLockAvailable) {
        SwitchRow(stringResource(R.string.awb_lock), s.awbLock, cam::setAwbLock)
    }
}

// ------------------------------------------------------------------ 詳細

@Composable
private fun AdvancedTab(state: CameraUiState, caps: CameraCapabilities, cam: CameraController) {
    EnumGroup(state, caps, cam, ControlGroup.ADVANCED)
    caps.floatControls.filter { it.key != CaptureRequest.LENS_FOCAL_LENGTH }.forEach { ctl ->
        ChoiceChips(ctl.label.asString(), ctl.options, state.settings.floats[ctl.id], { "%.2f".format(it) }) {
            cam.setFloat(ctl.id, it)
        }
    }
    if (caps.enumControls.none { it.group == ControlGroup.ADVANCED } && caps.floatControls.isEmpty()) {
        Hint(stringResource(R.string.no_advanced_settings))
    }
}

// ------------------------------------------------------------------ 情報

@Composable
private fun InfoTab(state: CameraUiState) {
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        item { SectionTitle("CameraCharacteristics (${state.characteristics.size} keys)") }
        items(state.characteristics, key = { it.first }) { (k, v) ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                SelectionContainer {
                    Text(v.take(2000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider()
        }
    }
}
