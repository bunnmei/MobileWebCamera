package space.webkombinat.MobileWebCamera.ui

import androidx.compose.ui.res.stringResource
import space.webkombinat.MobileWebCamera.R
import space.webkombinat.MobileWebCamera.util.asString
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import space.webkombinat.MobileWebCamera.camera.RotationMode
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import space.webkombinat.MobileWebCamera.camera.CameraUiState
import space.webkombinat.MobileWebCamera.camera.MeteringMath
import space.webkombinat.MobileWebCamera.stream.StreamStatus
import space.webkombinat.MobileWebCamera.ui.components.CameraPreview
import space.webkombinat.MobileWebCamera.ui.components.formatFocus
import space.webkombinat.MobileWebCamera.ui.components.formatShutter
import kotlin.math.roundToInt

@Composable
fun CameraScreen(vm: CameraViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stream by vm.streamStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(LOCAL_NETWORK_PERMISSION)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true) vm.onPermissionGranted()
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.onPermissionGranted() else launcher.launch(permissions)
    }

    // 映像の向きを固定したときだけ画面もその向きに固定する。自動なら端末の向きに合わせて回転
    val activity = LocalActivity.current
    val rotationMode = state.streamConfig.rotationMode
    LaunchedEffect(activity, rotationMode) {
        activity?.requestedOrientation = rotationMode.screenOrientation()
    }

    val displayRotation = rememberDisplayRotation()
    val content: @Composable (Modifier) -> Unit = { modifier ->
        if (!state.hasPermission) {
            Box(modifier, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.permission_camera_required), color = Color.White)
                    Button(onClick = { launcher.launch(permissions) }) { Text(stringResource(R.string.permission_grant)) }
                }
            }
        } else {
            PreviewArea(modifier, state, stream, vm, displayRotation)
        }
    }

    if (LocalConfiguration.current.orientation != Configuration.ORIENTATION_PORTRAIT) {
        // 横画面: 左にプレビュー、右に設定パネルを常に表示
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            content(Modifier.weight(1f).fillMaxHeight())
            ControlPanel(
                modifier = Modifier.width(PANEL_WIDTH).fillMaxHeight(),
                state = state,
                stream = stream,
                vm = vm,
            )
        }
    } else {
        PortraitLayout(state, stream, vm, content)
    }
}

/** 縦画面: プレビューを全面に表示し、設定パネルは右上のメニューボタンで開閉する */
@Composable
private fun PortraitLayout(
    state: CameraUiState,
    stream: StreamStatus,
    vm: CameraViewModel,
    content: @Composable (Modifier) -> Unit,
) {
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = panelOpen) { panelOpen = false }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val panelWidth = minOf(PANEL_WIDTH, maxWidth * 0.88f)
        content(Modifier.fillMaxSize())

        AnimatedVisibility(visible = panelOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x66000000))
                    .pointerInput(Unit) { detectTapGestures { panelOpen = false } }
            )
        }
        AnimatedVisibility(
            visible = panelOpen,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            ControlPanel(
                modifier = Modifier.width(panelWidth).fillMaxHeight(),
                state = state,
                stream = stream,
                vm = vm,
            )
        }

        // 開いているときはパネルの左隣に「閉じる」を出す（パネルのタブに重ならないように）
        val buttonEnd by animateDpAsState(if (panelOpen) panelWidth + 4.dp else 0.dp, label = "menuButton")
        IconButton(
            onClick = { panelOpen = !panelOpen },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(top = 8.dp, end = 8.dp + buttonEnd)
                .background(Color(0x99000000), CircleShape),
        ) {
            Icon(
                painterResource(if (panelOpen) R.drawable.ic_close else R.drawable.ic_menu),
                contentDescription = stringResource(if (panelOpen) R.string.menu_close else R.string.menu_open),
                tint = Color.White,
            )
        }
    }
}

private fun RotationMode.screenOrientation(): Int = when (this) {
    // 回転ロックに関係なく端末の向きに追従する（配信映像の自動回転と同じ動き）
    RotationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
    RotationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    // 反時計回りに倒す（右側面が上）= 画面の回転 90°
    RotationMode.LANDSCAPE_RIGHT_UP -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    RotationMode.LANDSCAPE_LEFT_UP -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
}

/**
 * 画面の回転 (0/90/180/270)。横 → 逆横の 180° 回転は構成変更として通知されないため、
 * DisplayListener で監視する。
 */
@Composable
private fun rememberDisplayRotation(): Int {
    val view = LocalView.current
    val context = LocalContext.current
    var rotation by remember { mutableIntStateOf(view.display.rotationDegrees()) }
    DisposableEffect(view, context) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                rotation = view.display.rotationDegrees()
            }

            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        rotation = view.display.rotationDegrees()
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    return rotation
}

private fun Display?.rotationDegrees(): Int = when (this?.rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private val PANEL_WIDTH = 400.dp

@Composable
private fun PreviewArea(
    modifier: Modifier,
    state: CameraUiState,
    stream: StreamStatus,
    vm: CameraViewModel,
    displayRotation: Int,
) {
    val caps = state.caps
    val lens = state.currentLens
    val buffer = state.previewSize
    val rel = if (caps != null && lens != null) {
        MeteringMath.relativeRotation(caps.sensorOrientation, displayRotation, lens.facing == CameraMetadata.LENS_FACING_FRONT)
    } else 0
    val aspect = when {
        buffer == null -> 16f / 9f
        rel % 180 == 90 -> buffer.height.toFloat() / buffer.width
        else -> buffer.width.toFloat() / buffer.height
    }
    var focusMark by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focusMark) {
        if (focusMark != null) {
            delay(1500)
            focusMark = null
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(aspect)) {
            CameraPreview(
                bufferSize = buffer,
                onSurfaceReady = { s, size -> vm.camera.setPreviewSurface(s, size) },
                onSurfaceDestroyed = { vm.camera.setPreviewSurface(null, null) },
            )
            // タップでピント・露出を合わせる
            Box(
                Modifier.fillMaxSize().pointerInput(displayRotation) {
                    detectTapGestures(
                        onTap = { o ->
                            focusMark = o
                            vm.camera.tapToFocus(o.x / size.width, o.y / size.height, displayRotation)
                        },
                        onLongPress = {
                            focusMark = null
                            vm.camera.resetFocus()
                        },
                    )
                }
            )
            focusMark?.let { o ->
                val half = with(LocalDensity.current) { 32.dp.toPx() }
                Box(
                    Modifier
                        .offset { IntOffset((o.x - half).roundToInt(), (o.y - half).roundToInt()) }
                        .size(64.dp)
                        .border(2.dp, Color(0xFFFFD54F), RoundedCornerShape(6.dp))
                )
            }
        }

        // 左上: 状態表示
        Column(
            Modifier.align(Alignment.TopStart).padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp)
        ) {
            if (state.streaming) {
                Text(
                    "● LIVE  OBS:${stream.tsClients}",
                    color = Color(0xFFFF5252), style = MaterialTheme.typography.labelLarge,
                )
            }
            val r = state.readings
            val lensLabel = state.currentLens?.label?.asString() ?: "-"
            val facesLabel = stringResource(R.string.overlay_faces, r.faces)
            Text(
                buildString {
                    append(lensLabel)
                    r.activePhysicalId?.let { append(" [phys $it]") }
                    append("\nISO ${r.iso ?: "-"}  ${r.exposureTimeNs?.let(::formatShutter) ?: "-"}")
                    r.aperture?.let { append("  f/$it") }
                    append("\nAF ${r.afState}  ${r.focusDistance?.let(::formatFocus) ?: "-"}")
                    r.zoomRatio?.let { append("  ${"%.2f".format(it)}x") }
                    if (r.faces > 0) append("  $facesLabel")
                },
                color = Color.White, style = MaterialTheme.typography.labelMedium,
            )
            state.error?.let { Text(it.asString(), color = Color(0xFFFFAB91), style = MaterialTheme.typography.labelMedium) }
        }

        // 下部: レンズ・ズームのクイック切替
        Row(
            Modifier.align(Alignment.BottomCenter).padding(8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            caps?.zoomPresets?.forEach { z ->
                FilterChip(
                    selected = kotlin.math.abs(state.settings.zoomRatio - z) < 0.05f,
                    onClick = { vm.camera.setZoom(z) },
                    label = { Text(if (z % 1f == 0f) "${z.toInt()}x" else "%.1fx".format(z)) },
                    colors = FilterChipDefaults.filterChipColors(containerColor = Color(0x99000000), labelColor = Color.White),
                )
            }
            state.lenses.forEach { l ->
                FilterChip(
                    selected = l.key == lens?.key,
                    onClick = { vm.camera.selectLens(l) },
                    label = { Text(l.label.asString()) },
                    colors = FilterChipDefaults.filterChipColors(containerColor = Color(0x99000000), labelColor = Color.White),
                )
            }
        }
    }
}

/** Android 17+ で PC からの接続（LAN / USB テザリング）を受けるための実行時権限 */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
