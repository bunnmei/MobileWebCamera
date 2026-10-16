package space.webkombinat.MobileWebCamera.camera

import space.webkombinat.MobileWebCamera.R.string as S
import space.webkombinat.MobileWebCamera.util.UiText
import space.webkombinat.MobileWebCamera.util.raw
import space.webkombinat.MobileWebCamera.util.text
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.webkombinat.MobileWebCamera.service.StreamingService
import space.webkombinat.MobileWebCamera.stream.H264Encoder
import space.webkombinat.MobileWebCamera.stream.StreamManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Camera2 の制御。すべての状態は専用の "Camera" スレッド上でのみ変更する。
 * UI からの呼び出しはスレッドセーフ（内部でポストする）。
 */
@Singleton
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: CameraManager,
    private val streams: StreamManager,
) {
    private val thread = HandlerThread("Camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    // ---- 以下 Camera スレッド専用 ----
    private var hasPermission = false
    private var lenses: List<LensInfo> = emptyList()
    private var lens: LensInfo? = null
    private var caps: CameraCapabilities? = null
    private var dump: List<Pair<String, String>> = emptyList()
    private var physicalKeys: Set<CaptureRequest.Key<*>> = emptySet()
    private var usePhysicalStream = false

    private var device: CameraDevice? = null
    private var openToken = 0
    private var opening = false
    private var session: CameraCaptureSession? = null
    private var sessionSurfaces: List<Surface> = emptyList()
    private var sessionGen = 0
    private val closeActions = HashMap<CameraCaptureSession, MutableList<() -> Unit>>()

    private var settings = CameraSettings()
    private var streamConfig = StreamConfig()
    private var previewSize: Size? = null
    private var previewSurface: Surface? = null
    private var previewSurfaceSize: Size? = null

    private var encoder: H264Encoder? = null
    private var streaming = false

    /** 端末の物理的な向き (OrientationEventListener 基準, 0/90/180/270)。-1 = 不明 */
    private var deviceOrientation = -1
    private var streamRotation = 0

    // 加速度センサーで端末の向きを検出（前景サービス中はバックグラウンドでも動作）
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN) return
            val cur = deviceOrientation
            if (cur >= 0) {
                // ヒステリシス: 現在の向きから 60° 以上傾いたときだけ切り替える
                val d = abs(deg - cur) % 360
                if (minOf(d, 360 - d) < 60) return
            }
            val bucket = ((deg + 45) / 90 * 90) % 360
            post {
                if (deviceOrientation != bucket) {
                    deviceOrientation = bucket
                    updateStreamRotation()
                }
            }
        }
    }

    private var readings = LiveReadings()
    private var lastReadingsAt = 0L
    private var error: UiText? = null

    // =========================================================== 公開 API

    fun onPermissionGranted() = post {
        if (hasPermission) return@post
        hasPermission = true
        lenses = runCatching { CapabilitiesParser.enumerateLenses(manager) }.getOrElse {
            error = text(S.error_enumerate_cameras, it.message ?: it.toString())
            emptyList()
        }
        lenses.firstOrNull { it.facing == CameraMetadata.LENS_FACING_BACK }?.let { selectLensInternal(it) }
            ?: lenses.firstOrNull()?.let { selectLensInternal(it) }
        publish()
    }

    fun selectLens(target: LensInfo) = post {
        if (target.key == lens?.key) return@post
        selectLensInternal(target)
    }

    /**
     * プレビュー Surface を設定。null を渡した場合はカメラがその Surface を使わなくなるまでブロックする
     * （SurfaceHolder.Callback.surfaceDestroyed から呼ぶため）。
     */
    fun setPreviewSurface(surface: Surface?, size: Size?) {
        if (surface != null) {
            post {
                previewSurface = surface
                previewSurfaceSize = size
                ensureCamera()
            }
            return
        }
        val latch = CountDownLatch(1)
        post {
            previewSurface = null
            previewSurfaceSize = null
            val s = session
            if (s != null && sessionSurfaces.any { it !== encoder?.inputSurface }) {
                runCatching { s.stopRepeating(); s.abortCaptures() }
                closeActions.getOrPut(s) { mutableListOf() } += { latch.countDown() }
                runCatching { s.close() }
                session = null
                sessionSurfaces = emptyList()
            } else {
                latch.countDown()
            }
            ensureCamera()
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
    }

    fun startStreaming() = post {
        if (streaming) return@post
        streams.startServers()
        if (!streams.status.value.serversRunning) {
            error = streams.status.value.error
            publish()
            return@post
        }
        streaming = true
        if (deviceOrientation < 0) deviceOrientation = orientationFromDisplay()
        orientationListener.enable()
        rebuildEncoder()
        ensureCamera()
        ContextCompat.startForegroundService(context, Intent(context, StreamingService::class.java))
        publish()
    }

    fun stopStreaming() = post {
        if (!streaming) return@post
        streaming = false
        orientationListener.disable()
        streams.detachEncoder()
        encoder?.let { old -> releaseAfterSessionClosed { old.release() } }
        encoder = null
        streams.stopServers()
        ensureCamera()
        context.stopService(Intent(context, StreamingService::class.java))
        publish()
    }

    fun setStreamSize(size: Size) = post {
        if (size == streamConfig.size) return@post
        streamConfig = adjustStreamConfig(streamConfig.copy(size = size))
        previewSize = choosePreviewSize()
        if (streaming) rebuildEncoder()
        if (device != null) createSession()
        publish()
    }

    fun setFps(range: Range<Int>) = post {
        streamConfig = streamConfig.copy(fps = range)
        if (streaming) {
            rebuildEncoder()
            createSession()
        } else applyRepeating()
        publish()
    }

    fun setBitrate(bps: Int) = post {
        streamConfig = streamConfig.copy(bitrate = bps)
        streams.setBitrate(bps)
        publish()
    }

    fun setEnum(id: String, value: Int) = updateSettings { s ->
        var next = s.copy(enums = s.enums + (id to value))
        when (id) {
            CaptureRequest.CONTROL_AE_MODE.name -> if (value == CameraMetadata.CONTROL_AE_MODE_OFF) {
                // 自動露出の現在値からマニュアルを開始
                next = next.copy(
                    iso = readings.iso ?: next.iso,
                    exposureTimeNs = readings.exposureTimeNs ?: next.exposureTimeNs,
                )
            }

            CaptureRequest.CONTROL_AF_MODE.name -> if (value == CameraMetadata.CONTROL_AF_MODE_OFF) {
                next = next.copy(focusDistance = readings.focusDistance ?: next.focusDistance)
            }
        }
        next
    }

    fun setFloat(id: String, value: Float) = updateSettings { it.copy(floats = it.floats + (id to value)) }
    fun setFocusDistance(d: Float) = updateSettings { it.copy(focusDistance = d) }
    fun setIso(iso: Int) = updateSettings { it.copy(iso = iso) }
    fun setExposureTime(ns: Long) = updateSettings { it.copy(exposureTimeNs = ns) }
    fun setEv(ev: Int) = updateSettings { it.copy(evCompensation = ev) }
    fun setAeLock(lock: Boolean) = updateSettings { it.copy(aeLock = lock) }
    fun setAwbLock(lock: Boolean) = updateSettings { it.copy(awbLock = lock) }
    fun setColorTemperature(k: Int) = updateSettings { it.copy(colorTemperature = k) }
    fun setColorTint(t: Int) = updateSettings { it.copy(colorTint = t) }
    fun setZoom(ratio: Float) = updateSettings { s ->
        val r = caps?.zoomRange?.clamp(ratio) ?: ratio
        s.copy(zoomRatio = r)
    }

    fun setTorch(on: Boolean) = updateSettings { it.copy(torch = on) }
    fun setTorchStrength(level: Int) = updateSettings { it.copy(torchStrength = level) }

    /** プレビュー上の正規化座標をタップ → AF/AE 領域設定 + AF トリガー */
    fun tapToFocus(nx: Float, ny: Float, displayRotationDeg: Int) = post {
        val c = caps ?: return@post
        val l = lens ?: return@post
        val p = MeteringMath.viewToSensor(
            nx, ny, c.sensorOrientation, displayRotationDeg, l.facing == CameraMetadata.LENS_FACING_FRONT
        )
        val afModes = c.enumControls.firstOrNull { it.key == CaptureRequest.CONTROL_AF_MODE }?.options?.map { it.value }.orEmpty()
        var enums = settings.enums
        if (CameraMetadata.CONTROL_AF_MODE_AUTO in afModes &&
            enums[CaptureRequest.CONTROL_AF_MODE.name] != CameraMetadata.CONTROL_AF_MODE_OFF
        ) {
            enums = enums + (CaptureRequest.CONTROL_AF_MODE.name to CameraMetadata.CONTROL_AF_MODE_AUTO)
        }
        settings = settings.copy(meteringPoint = p, enums = enums)
        applyRepeating()
        triggerAf()
        publish()
    }

    /** タップ測光を解除してコンティニュアス AF に戻す */
    fun resetFocus() = post {
        val afModes = caps?.enumControls?.firstOrNull { it.key == CaptureRequest.CONTROL_AF_MODE }?.options?.map { it.value }.orEmpty()
        val mode = listOf(
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        ).firstOrNull { it in afModes }
        settings = settings.copy(
            meteringPoint = null,
            enums = if (mode != null) settings.enums + (CaptureRequest.CONTROL_AF_MODE.name to mode) else settings.enums,
        )
        cancelAf()
        applyRepeating()
        publish()
    }

    fun requestKeyFrame() = streams.requestKeyFrame()

    fun setRotationMode(mode: RotationMode) = post {
        streamConfig = streamConfig.copy(rotationMode = mode)
        updateStreamRotation()
    }

    // =========================================================== 内部処理

    private fun post(block: () -> Unit) {
        handler.post {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "camera op failed", e)
                error = raw(e.message ?: e.toString())
                publish()
            }
        }
    }

    private fun updateSettings(transform: (CameraSettings) -> CameraSettings) = post {
        settings = transform(settings)
        applyRepeating()
        publish()
    }

    private fun selectLensInternal(target: LensInfo) {
        closeCamera()
        lens = target
        error = null
        val logical = manager.getCameraCharacteristics(target.cameraId)
        val capsChars = target.physicalId?.let { manager.getCameraCharacteristics(it) } ?: logical
        val physicalFocals = if (target.isLogicalMulti) {
            logical.physicalCameraIds.mapNotNull { pid ->
                runCatching { CapabilitiesParser.equivalentFocal(manager.getCameraCharacteristics(pid)) }.getOrNull()
            }.filter { it > 0f }
        } else emptyList()
        val parsed = CapabilitiesParser.parse(capsChars, physicalFocals)
        caps = parsed
        dump = CapabilitiesParser.dump(capsChars)
        physicalKeys = if (target.physicalId != null) {
            logical.availablePhysicalCameraRequestKeys.orEmpty().toSet()
        } else emptySet()
        usePhysicalStream = target.physicalId != null
        settings = CameraSettings(zoomRatio = parsed.zoomRange.clamp(1f))
        streamConfig = adjustStreamConfig(streamConfig)
        previewSize = choosePreviewSize()
        readings = LiveReadings()
        if (streaming && (encoder?.inputWidth != streamConfig.size.width || encoder?.inputHeight != streamConfig.size.height)) {
            rebuildEncoder()
        }
        updateStreamRotation() // 前面/背面でセンサーの向きが違うため再計算
        ensureCamera()
    }

    private fun adjustStreamConfig(cfg: StreamConfig): StreamConfig {
        val c = caps ?: return cfg
        val sizes = c.streamSizes
        val size = when {
            sizes.isEmpty() -> cfg.size
            cfg.size in sizes -> cfg.size
            else -> {
                val aspect = cfg.size.width.toFloat() / cfg.size.height
                val area = cfg.size.width * cfg.size.height
                sizes.minBy {
                    abs(it.width.toFloat() / it.height - aspect) * 10f +
                        abs(it.width * it.height - area).toFloat() / area
                }
            }
        }
        val fps = when {
            c.fpsRanges.isEmpty() -> cfg.fps
            cfg.fps in c.fpsRanges -> cfg.fps
            else -> c.fpsRanges.firstOrNull { it.lower == 30 && it.upper == 30 }
                ?: c.fpsRanges.minBy { abs(it.upper - 30) * 10 + (it.upper - it.lower) }
        }
        return cfg.copy(size = size, fps = fps)
    }

    private fun choosePreviewSize(): Size {
        val target = streamConfig.size
        val sizes = caps?.previewSizes.orEmpty()
        if (sizes.isEmpty() || target in sizes) return target
        val aspect = target.width.toFloat() / target.height
        return sizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= 1920 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: target
    }

    private fun rebuildEncoder() {
        val cfg = streamConfig
        streamRotation = targetRotation()
        val (enc, old) = streams.replaceEncoder(
            cfg.size.width, cfg.size.height, streamRotation, cfg.fps.upper, cfg.bitrate,
        )
        encoder = enc
        old?.let { releaseAfterSessionClosed { it.release() } }
        publish()
    }

    /** 固定の持ち方が選ばれていればそれ、自動ならセンサーで検出した持ち方 */
    private fun effectiveHolding(): Int =
        streamConfig.rotationMode.deviceOrientation ?: deviceOrientation

    private fun targetRotation(): Int =
        effectiveHolding().takeIf { it >= 0 }?.let(::rotationFor) ?: streamRotation

    /** 配信映像を正立させる回転量を決めてエンコーダへ反映 */
    private fun updateStreamRotation() {
        val target = targetRotation()
        val enc = encoder
        if (enc != null && enc.swapped != (target % 180 == 90)) {
            // 縦持ち ↔ 横持ち: 映像の縦横が変わるのでエンコーダとセッションを作り直す
            rebuildEncoder()
            if (device != null) createSession()
            return
        }
        streamRotation = target
        enc?.rotation = target
        publish()
    }

    /**
     * センサーの向きと端末の持ち方 [dev] から、映像を正立させるのに必要な時計回りの回転を求める。
     * カメラ情報が未取得なら null（直前の値を維持）。
     */
    private fun rotationFor(dev: Int): Int? {
        val c = caps ?: return null
        val l = lens ?: return null
        return if (l.facing == CameraMetadata.LENS_FACING_FRONT) (c.sensorOrientation - dev + 360) % 360
        else (c.sensorOrientation + dev) % 360
    }

    /** センサー値が来る前の初期値として画面の回転から端末の向きを推定 */
    private fun orientationFromDisplay(): Int {
        val rot = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return -1
        val deg = when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (360 - deg) % 360
    }

    private fun releaseAfterSessionClosed(action: () -> Unit) {
        val s = session
        if (s == null) action() else closeActions.getOrPut(s) { mutableListOf() } += action
    }

    private fun runCloseActions(s: CameraCaptureSession) {
        closeActions.remove(s)?.forEach { runCatching(it) }
    }

    private fun ensureCamera() {
        val needed = hasPermission && lens != null && (previewSurface != null || streaming)
        when {
            needed && device == null && !opening -> openCamera()
            !needed && (device != null || opening) -> closeCamera()
            device != null -> createSession()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val l = lens ?: return
        opening = true
        val token = ++openToken
        try {
            manager.openCamera(l.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (token != openToken) {
                        d.close(); return
                    }
                    opening = false
                    device = d
                    initDefaults(d)
                    publish()
                    createSession()
                }

                override fun onDisconnected(d: CameraDevice) {
                    onDeviceGone(d, token, text(S.error_camera_disconnected))
                }

                override fun onError(d: CameraDevice, code: Int) {
                    onDeviceGone(d, token, text(S.error_camera, code))
                }
            })
        } catch (e: Exception) {
            opening = false
            error = text(S.error_open_camera, e.message ?: e.toString())
            publish()
        }
    }

    private fun onDeviceGone(d: CameraDevice, token: Int, message: UiText) {
        d.close()
        if (token != openToken) return
        opening = false
        if (device === d) {
            device = null
            session = null
            sessionSurfaces = emptyList()
        }
        error = message
        publish()
    }

    private fun closeCamera() {
        openToken++
        opening = false
        session?.let { runCatching { it.close() } }
        session = null
        sessionSurfaces = emptyList()
        device?.close()
        device = null
        publish()
    }

    /** TEMPLATE_RECORD の既定値を各コントロールの初期値として取り込む */
    private fun initDefaults(d: CameraDevice) {
        val c = caps ?: return
        val template = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        val enums = HashMap<String, Int>()
        for (ctl in c.enumControls) {
            val v = template.get(ctl.key)
            enums[ctl.id] = if (v != null && ctl.options.any { it.value == v }) v else ctl.options.first().value
        }
        val afOptions = c.enumControls.firstOrNull { it.key == CaptureRequest.CONTROL_AF_MODE }?.options?.map { it.value }.orEmpty()
        if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afOptions) {
            enums[CaptureRequest.CONTROL_AF_MODE.name] = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        }
        val floats = HashMap<String, Float>()
        for (ctl in c.floatControls) {
            floats[ctl.id] = template.get(ctl.key) ?: ctl.options.first()
        }
        settings = settings.copy(
            enums = enums,
            floats = floats,
            iso = c.isoRange?.clamp(100) ?: 100,
            exposureTimeNs = c.exposureTimeRange?.clamp(16_666_666L) ?: 16_666_666L,
            colorTemperature = c.cctRange?.clamp(5500) ?: 5500,
            torchStrength = c.torchStrengthMax.coerceAtLeast(1),
        )
    }

    private fun createSession() {
        val d = device ?: return
        val surfaces = buildList {
            val p = previewSurface
            if (p != null && p.isValid && previewSurfaceSize == previewSize) add(p)
            encoder?.inputSurface?.let { add(it) }
        }
        val gen = ++sessionGen
        val old = session
        session = null
        sessionSurfaces = emptyList()
        if (surfaces.isEmpty()) {
            old?.let { runCatching { it.close() } }
            return
        }
        val physicalId = lens?.physicalId?.takeIf { usePhysicalStream }
        val outputs = surfaces.map { s ->
            OutputConfiguration(s).also { oc -> physicalId?.let { oc.setPhysicalCameraId(it) } }
        }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != sessionGen || device !== d) {
                        s.close(); return
                    }
                    session = s
                    sessionSurfaces = surfaces
                    error = null
                    applyRepeating()
                    publish()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen == sessionGen) {
                        if (physicalId != null) {
                            // 物理カメラ単体出力に失敗したら論理カメラとして再試行
                            usePhysicalStream = false
                            error = text(S.error_physical_fallback, physicalId)
                            createSession()
                        } else {
                            error = text(S.error_session, streamConfig.size.toString())
                        }
                        publish()
                    }
                    runCloseActions(s)
                }

                override fun onClosed(s: CameraCaptureSession) {
                    runCloseActions(s)
                }
            },
        )
        if (physicalId != null && Build.VERSION.SDK_INT >= 29) {
            val supported = runCatching { d.isSessionConfigurationSupported(config) }.getOrDefault(true)
            if (!supported) {
                usePhysicalStream = false
                error = text(S.error_physical_fallback, physicalId)
                createSession()
                return
            }
        }
        d.createCaptureSession(config)
    }

    private fun newRequestBuilder(d: CameraDevice): CaptureRequest.Builder {
        val pid = lens?.physicalId?.takeIf { usePhysicalStream }
        return if (pid != null) d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, setOf(pid))
        else d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
    }

    private fun <T> CaptureRequest.Builder.put(key: CaptureRequest.Key<T>, value: T) {
        set(key, value)
        val pid = lens?.physicalId?.takeIf { usePhysicalStream } ?: return
        if (key in physicalKeys) runCatching { setPhysicalCameraKey(key, value, pid) }
    }

    private fun buildRequest(trigger: Int? = null): CaptureRequest? {
        val d = device ?: return null
        if (sessionSurfaces.isEmpty()) return null
        val b = newRequestBuilder(d)
        sessionSurfaces.forEach(b::addTarget)
        applySettings(b)
        if (trigger != null) b.put(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
        return b.build()
    }

    private fun applyRepeating() {
        val s = session ?: return
        val req = buildRequest() ?: return
        try {
            s.setRepeatingRequest(req, captureCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "setRepeatingRequest failed", e)
            error = text(S.error_apply_settings, e.message ?: e.toString())
        }
    }

    private fun triggerAf() {
        val s = session ?: return
        cancelAf()
        buildRequest(CameraMetadata.CONTROL_AF_TRIGGER_START)?.let { runCatching { s.capture(it, captureCallback, handler) } }
    }

    private fun cancelAf() {
        val s = session ?: return
        buildRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)?.let { runCatching { s.capture(it, captureCallback, handler) } }
    }

    private fun applySettings(b: CaptureRequest.Builder) {
        val c = caps ?: return
        val s = settings
        for (ctl in c.enumControls) s.enums[ctl.id]?.let { b.put(ctl.key, it) }
        for (ctl in c.floatControls) s.floats[ctl.id]?.let { b.put(ctl.key, it) }

        // フォーカス
        val afMode = s.enums[CaptureRequest.CONTROL_AF_MODE.name]
        if (afMode == CameraMetadata.CONTROL_AF_MODE_OFF && c.minFocusDistance != null) {
            b.put(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDistance.coerceIn(0f, c.minFocusDistance))
        }

        // 露出
        val fps = streamConfig.fps
        val aeMode = s.enums[CaptureRequest.CONTROL_AE_MODE.name]
        if (aeMode == CameraMetadata.CONTROL_AE_MODE_OFF) {
            c.isoRange?.let { b.put(CaptureRequest.SENSOR_SENSITIVITY, it.clamp(s.iso)) }
            c.exposureTimeRange?.let { r ->
                val exp = r.clamp(s.exposureTimeNs)
                b.put(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                var frame = maxOf(exp, 1_000_000_000L / fps.upper.coerceAtLeast(1))
                c.maxFrameDuration?.let { frame = frame.coerceAtMost(it) }
                b.put(CaptureRequest.SENSOR_FRAME_DURATION, frame)
            }
        } else {
            b.put(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            b.put(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.evRange.clamp(s.evCompensation))
            if (c.aeLockAvailable) b.put(CaptureRequest.CONTROL_AE_LOCK, s.aeLock)
            if (Build.VERSION.SDK_INT >= 36) {
                when (s.enums[CaptureRequest.CONTROL_AE_PRIORITY_MODE.name]) {
                    1 -> c.isoRange?.let { b.put(CaptureRequest.SENSOR_SENSITIVITY, it.clamp(s.iso)) }
                    2 -> c.exposureTimeRange?.let { b.put(CaptureRequest.SENSOR_EXPOSURE_TIME, it.clamp(s.exposureTimeNs)) }
                }
            }
        }

        // ホワイトバランス
        val awbMode = s.enums[CaptureRequest.CONTROL_AWB_MODE.name]
        if (awbMode == CameraMetadata.CONTROL_AWB_MODE_OFF) {
            if (Build.VERSION.SDK_INT >= 36 && c.cctRange != null) {
                b.put(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_CCT)
                b.put(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, c.cctRange.clamp(s.colorTemperature))
                b.put(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, s.colorTint)
            } else if (c.manualWbGains) {
                b.put(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.put(CaptureRequest.COLOR_CORRECTION_GAINS, MeteringMath.kelvinToGains(s.colorTemperature))
                b.put(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_TRANSFORM)
            }
        } else if (c.awbLockAvailable) {
            b.put(CaptureRequest.CONTROL_AWB_LOCK, s.awbLock)
        }

        // ズーム
        var crop: android.graphics.Rect? = null
        if (c.zoomRatioSupported && Build.VERSION.SDK_INT >= 30) {
            b.put(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoomRange.clamp(s.zoomRatio))
        } else if (c.activeArrayWidth > 0 && s.zoomRatio > 1f) {
            crop = MeteringMath.cropForZoom(c.activeArrayWidth, c.activeArrayHeight, s.zoomRatio)
            b.put(CaptureRequest.SCALER_CROP_REGION, crop)
        }

        // ライト
        if (c.flashAvailable) {
            if (s.torch) {
                b.put(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                if (Build.VERSION.SDK_INT >= 35 && c.torchStrengthMax > 1) {
                    b.put(CaptureRequest.FLASH_STRENGTH_LEVEL, s.torchStrength.coerceIn(1, c.torchStrengthMax))
                }
            } else {
                b.put(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
        }

        // タップ測光領域
        s.meteringPoint?.let { p ->
            if (c.activeArrayWidth > 0) {
                val rect = MeteringMath.meteringRect(p, c.activeArrayWidth, c.activeArrayHeight, crop)
                if (c.maxAfRegions > 0) b.put(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
                if (c.maxAeRegions > 0) b.put(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
            }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReadingsAt < 200) return
            lastReadingsAt = now
            readings = LiveReadings(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                afState = CameraNames.afState(result.get(CaptureResult.CONTROL_AF_STATE)),
                focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH),
                aperture = result.get(CaptureResult.LENS_APERTURE),
                zoomRatio = if (Build.VERSION.SDK_INT >= 30) result.get(CaptureResult.CONTROL_ZOOM_RATIO) else null,
                activePhysicalId = if (Build.VERSION.SDK_INT >= 29) {
                    result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                } else null,
                faces = result.get(CaptureResult.STATISTICS_FACES)?.size ?: 0,
            )
            publish()
        }
    }

    private fun publish() {
        _state.value = CameraUiState(
            hasPermission = hasPermission,
            lenses = lenses,
            currentLens = lens,
            caps = caps,
            settings = settings,
            streamConfig = streamConfig,
            previewSize = previewSize,
            readings = readings,
            cameraOpen = device != null,
            streaming = streaming,
            streamRotation = streamRotation,
            effectiveHolding = effectiveHolding(),
            error = error,
            characteristics = dump,
        )
    }

    private companion object {
        const val TAG = "CameraController"
        val IDENTITY_TRANSFORM = ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
    }
}
