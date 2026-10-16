package space.webkombinat.MobileWebCamera.camera

import android.graphics.PointF
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.annotation.StringRes
import space.webkombinat.MobileWebCamera.R.string as S
import space.webkombinat.MobileWebCamera.util.UiText
import space.webkombinat.MobileWebCamera.util.text

/**
 * 選択可能なレンズ（カメラ）。
 * 論理マルチカメラの物理カメラが cameraIdList に公開されていない場合は
 * [physicalId] を指定して論理カメラ経由でストリームを流す。
 */
data class LensInfo(
    val cameraId: String,
    val physicalId: String?,
    val facing: Int,
    val focalLengthMm: Float,
    val equivalentFocalMm: Float,
    val isLogicalMulti: Boolean,
) {
    val key: String get() = if (physicalId == null) cameraId else "$cameraId/$physicalId"

    val facingLabel: UiText
        get() = when (facing) {
            android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT -> text(S.lens_front)
            android.hardware.camera2.CameraMetadata.LENS_FACING_BACK -> text(S.lens_back)
            else -> text(S.lens_external)
        }

    val label: UiText
        get() {
            val focal = if (equivalentFocalMm > 0f) "${equivalentFocalMm.toInt()}mm" else "#$cameraId"
            return when {
                isLogicalMulti -> text(S.lens_label_multi, facingLabel, focal)
                physicalId != null -> text(S.lens_label_physical, facingLabel, focal, physicalId)
                else -> text(S.lens_label, facingLabel, focal)
            }
        }
}

enum class ControlGroup { FOCUS, EXPOSURE, COLOR, IMAGE, ADVANCED }

data class EnumOption(val value: Int, val label: UiText)

/** 整数列挙型の CaptureRequest キー（AF/AE/AWB モード、各種処理モードなど）を汎用的に扱う。 */
data class EnumControl(
    val key: CaptureRequest.Key<Int>,
    val label: UiText,
    val group: ControlGroup,
    val options: List<EnumOption>,
) {
    val id: String get() = key.name
}

/** 離散 float 値の CaptureRequest キー（絞り、NDフィルタ、焦点距離）。 */
data class FloatChoiceControl(
    val key: CaptureRequest.Key<Float>,
    val label: UiText,
    val options: List<Float>,
) {
    val id: String get() = key.name
}

data class CameraCapabilities(
    val hardwareLevel: String,
    val capabilities: List<String>,
    val enumControls: List<EnumControl>,
    val floatControls: List<FloatChoiceControl>,
    /** null = 固定焦点。値は diopter (1/m) */
    val minFocusDistance: Float?,
    val hyperfocalDistance: Float?,
    val isoRange: Range<Int>?,
    val exposureTimeRange: Range<Long>?,
    val maxFrameDuration: Long?,
    val evRange: Range<Int>,
    val evStep: Float,
    val zoomRange: Range<Float>,
    /** true = CONTROL_ZOOM_RATIO を使用、false = SCALER_CROP_REGION */
    val zoomRatioSupported: Boolean,
    val zoomPresets: List<Float>,
    val flashAvailable: Boolean,
    val torchStrengthMax: Int,
    val manualWbGains: Boolean,
    val cctRange: Range<Int>?,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    val maxAwbRegions: Int,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,
    val fpsRanges: List<Range<Int>>,
    val streamSizes: List<Size>,
    val previewSizes: List<Size>,
    val sensorOrientation: Int,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
)

data class CameraSettings(
    /** EnumControl.id -> 値 */
    val enums: Map<String, Int> = emptyMap(),
    /** FloatChoiceControl.id -> 値 */
    val floats: Map<String, Float> = emptyMap(),
    val focusDistance: Float = 0f,
    val iso: Int = 100,
    val exposureTimeNs: Long = 16_666_666L,
    val evCompensation: Int = 0,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
    val colorTemperature: Int = 5500,
    val colorTint: Int = 0,
    val zoomRatio: Float = 1f,
    val torch: Boolean = false,
    val torchStrength: Int = 1,
    /** 正規化座標 (0..1) でのタップ測光点。null = 全体 */
    val meteringPoint: PointF? = null,
)

/**
 * 配信映像の向き。スマホの持ち方で指定する。
 * AUTO = 加速度センサーで持ち方を検出して自動切替。縦持ちのときは縦長の映像として配信する。
 *
 * [deviceOrientation] は OrientationEventListener と同じ基準
 * (0 = 縦、90 = 左側面が上、270 = 右側面が上)。
 */
enum class RotationMode(
    @StringRes val label: Int,
    @StringRes val shortLabel: Int,
    val deviceOrientation: Int?,
) {
    AUTO(S.rotation_auto, S.rotation_auto_short, null),
    PORTRAIT(S.rotation_portrait, S.rotation_portrait_short, 0),
    LANDSCAPE_RIGHT_UP(S.rotation_landscape_right_up, S.rotation_landscape_right_up_short, 270),
    LANDSCAPE_LEFT_UP(S.rotation_landscape_left_up, S.rotation_landscape_left_up_short, 90),
    ;

    companion object {
        /** 端末の向き (0/90/180/270) の表示名 */
        @StringRes
        fun holdingLabel(deviceOrientation: Int): Int = when (deviceOrientation) {
            0 -> S.rotation_portrait
            90 -> S.rotation_landscape_left_up
            180 -> S.holding_upside_down
            270 -> S.rotation_landscape_right_up
            else -> S.holding_unknown
        }
    }
}

data class StreamConfig(
    val size: Size = Size(1920, 1080),
    val fps: Range<Int> = Range(30, 30),
    val bitrate: Int = 8_000_000,
    val rotationMode: RotationMode = RotationMode.AUTO,
)

data class LiveReadings(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val frameDurationNs: Long? = null,
    val focusDistance: Float? = null,
    val afState: String = "-",
    val focalLength: Float? = null,
    val aperture: Float? = null,
    val zoomRatio: Float? = null,
    val activePhysicalId: String? = null,
    val faces: Int = 0,
)

data class CameraUiState(
    val hasPermission: Boolean = false,
    val lenses: List<LensInfo> = emptyList(),
    val currentLens: LensInfo? = null,
    val caps: CameraCapabilities? = null,
    val settings: CameraSettings = CameraSettings(),
    val streamConfig: StreamConfig = StreamConfig(),
    val previewSize: Size? = null,
    val readings: LiveReadings = LiveReadings(),
    val cameraOpen: Boolean = false,
    val streaming: Boolean = false,
    /** 現在適用中の配信映像の回転 (0/90/180/270) */
    val streamRotation: Int = 0,
    /** 配信映像の向きの基準にしている持ち方 (0/90/180/270)。-1 = 不明 */
    val effectiveHolding: Int = -1,
    val error: UiText? = null,
    val characteristics: List<Pair<String, String>> = emptyList(),
)
