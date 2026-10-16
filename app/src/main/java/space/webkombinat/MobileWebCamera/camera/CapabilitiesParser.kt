package space.webkombinat.MobileWebCamera.camera

import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest as R
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import space.webkombinat.MobileWebCamera.R.string as S
import space.webkombinat.MobileWebCamera.stream.EncoderSupport
import space.webkombinat.MobileWebCamera.util.UiText
import space.webkombinat.MobileWebCamera.util.raw
import space.webkombinat.MobileWebCamera.util.text
import kotlin.math.abs
import kotlin.math.sqrt

object CapabilitiesParser {

    private const val MAX_STREAM_W = 3840
    private const val MAX_STREAM_H = 2160

    fun enumerateLenses(manager: CameraManager): List<LensInfo> {
        val ids = manager.cameraIdList.toList()
        val result = mutableListOf<LensInfo>()
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val caps = chars.get(C.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) continue
            val isMulti = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
            result += lensOf(id, null, chars, isMulti)
            if (isMulti) {
                for (pid in chars.physicalCameraIds) {
                    if (pid in ids) continue // 単体で公開済み
                    val pChars = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    result += lensOf(id, pid, pChars, false)
                }
            }
        }
        // 背面 → 前面 → 外部、同じ向きは焦点距離順
        return result.sortedWith(
            compareBy<LensInfo> {
                when (it.facing) {
                    CameraMetadata.LENS_FACING_BACK -> 0
                    CameraMetadata.LENS_FACING_FRONT -> 1
                    else -> 2
                }
            }.thenBy { if (it.isLogicalMulti) 0 else 1 }.thenBy { it.equivalentFocalMm }
        )
    }

    private fun lensOf(id: String, physicalId: String?, chars: C, isMulti: Boolean): LensInfo {
        val focal = chars.get(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
        return LensInfo(
            cameraId = id,
            physicalId = physicalId,
            facing = chars.get(C.LENS_FACING) ?: CameraMetadata.LENS_FACING_EXTERNAL,
            focalLengthMm = focal,
            equivalentFocalMm = equivalentFocal(chars),
            isLogicalMulti = isMulti,
        )
    }

    /** 35mm 換算焦点距離 */
    fun equivalentFocal(chars: C): Float {
        val focal = chars.get(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return 0f
        val size = chars.get(C.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        val diag = sqrt(size.width * size.width + size.height * size.height)
        if (diag <= 0f) return 0f
        return focal * 43.27f / diag
    }

    fun parse(chars: C, physicalFocals: List<Float>): CameraCapabilities {
        val capsList = chars.get(C.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val aeModes = chars.get(C.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        val awbModes = chars.get(C.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capsList
        val manualPost = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capsList

        val enums = buildList {
            // フォーカス
            add(enumCtl(R.CONTROL_AF_MODE, text(S.ctl_af_mode), ControlGroup.FOCUS, chars.get(C.CONTROL_AF_AVAILABLE_MODES), CameraNames::afMode))
            // 露出
            add(enumCtl(R.CONTROL_AE_MODE, text(S.ctl_ae_mode), ControlGroup.EXPOSURE, aeModes, CameraNames::aeMode))
            if (Build.VERSION.SDK_INT >= 36) {
                add(enumCtl(R.CONTROL_AE_PRIORITY_MODE, text(S.ctl_ae_priority_mode), ControlGroup.EXPOSURE,
                    chars.get(C.CONTROL_AE_AVAILABLE_PRIORITY_MODES)) {
                    when (it) { 0 -> raw("OFF"); 1 -> text(S.ae_priority_iso); 2 -> text(S.ae_priority_shutter); else -> raw("#$it") }
                })
            }
            add(enumCtl(R.CONTROL_AE_ANTIBANDING_MODE, text(S.ctl_antibanding), ControlGroup.EXPOSURE,
                chars.get(C.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES), CameraNames::antibanding))
            // 色
            add(enumCtl(R.CONTROL_AWB_MODE, text(S.ctl_awb_mode), ControlGroup.COLOR, awbModes, CameraNames::awbMode))
            add(enumCtl(R.CONTROL_EFFECT_MODE, text(S.ctl_effect), ControlGroup.COLOR,
                chars.get(C.CONTROL_AVAILABLE_EFFECTS), CameraNames::effectMode))
            add(enumCtl(R.TONEMAP_MODE, text(S.ctl_tonemap), ControlGroup.COLOR,
                chars.get(C.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.filter {
                    it == CameraMetadata.TONEMAP_MODE_FAST || it == CameraMetadata.TONEMAP_MODE_HIGH_QUALITY
                }?.toIntArray(), CameraNames::tonemap))
            add(enumCtl(R.COLOR_CORRECTION_ABERRATION_MODE, text(S.ctl_aberration), ControlGroup.COLOR,
                chars.get(C.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES), CameraNames::quality))
            // 画像処理
            add(enumCtl(R.CONTROL_VIDEO_STABILIZATION_MODE, text(S.ctl_video_stabilization), ControlGroup.IMAGE,
                chars.get(C.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), CameraNames::videoStab))
            add(enumCtl(R.LENS_OPTICAL_STABILIZATION_MODE, text(S.ctl_optical_stabilization), ControlGroup.IMAGE,
                chars.get(C.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), CameraNames::ois))
            add(enumCtl(R.NOISE_REDUCTION_MODE, text(S.ctl_noise_reduction), ControlGroup.IMAGE,
                chars.get(C.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), CameraNames::quality))
            add(enumCtl(R.EDGE_MODE, text(S.ctl_edge), ControlGroup.IMAGE,
                chars.get(C.EDGE_AVAILABLE_EDGE_MODES), CameraNames::quality))
            add(enumCtl(R.CONTROL_MODE, text(S.ctl_control_mode), ControlGroup.IMAGE,
                chars.get(C.CONTROL_AVAILABLE_MODES), CameraNames::controlMode))
            add(enumCtl(R.CONTROL_SCENE_MODE, text(S.ctl_scene_mode), ControlGroup.IMAGE,
                chars.get(C.CONTROL_AVAILABLE_SCENE_MODES)?.let { it + CameraMetadata.CONTROL_SCENE_MODE_DISABLED },
                CameraNames::sceneMode))
            if (Build.VERSION.SDK_INT >= 30) {
                add(enumCtl(R.CONTROL_EXTENDED_SCENE_MODE, text(S.ctl_extended_scene), ControlGroup.IMAGE,
                    chars.get(C.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)?.map { it.mode }?.toIntArray(),
                    CameraNames::extendedScene))
            }
            if (Build.VERSION.SDK_INT >= 34 && chars.get(C.CONTROL_AUTOFRAMING_AVAILABLE) == true) {
                add(enumCtl(R.CONTROL_AUTOFRAMING, text(S.ctl_autoframing), ControlGroup.IMAGE, intArrayOf(0, 1), CameraNames::onOff))
            }
            // 詳細
            add(enumCtl(R.STATISTICS_FACE_DETECT_MODE, text(S.ctl_face_detect), ControlGroup.ADVANCED,
                chars.get(C.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES), CameraNames::faceDetect))
            add(enumCtl(R.HOT_PIXEL_MODE, text(S.ctl_hot_pixel), ControlGroup.ADVANCED,
                chars.get(C.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES), CameraNames::quality))
            add(enumCtl(R.SHADING_MODE, text(S.ctl_shading), ControlGroup.ADVANCED,
                chars.get(C.SHADING_AVAILABLE_MODES), CameraNames::quality))
            add(enumCtl(R.DISTORTION_CORRECTION_MODE, text(S.ctl_distortion), ControlGroup.ADVANCED,
                chars.get(C.DISTORTION_CORRECTION_AVAILABLE_MODES), CameraNames::quality))
            add(enumCtl(R.SENSOR_TEST_PATTERN_MODE, text(S.ctl_test_pattern), ControlGroup.ADVANCED,
                chars.get(C.SENSOR_AVAILABLE_TEST_PATTERN_MODES), CameraNames::testPattern))
        }.filterNotNull()

        val floats = listOfNotNull(
            floatCtl(R.LENS_APERTURE, text(S.ctl_aperture), chars.get(C.LENS_INFO_AVAILABLE_APERTURES)),
            floatCtl(R.LENS_FILTER_DENSITY, text(S.ctl_nd_filter), chars.get(C.LENS_INFO_AVAILABLE_FILTER_DENSITIES)),
            floatCtl(R.LENS_FOCAL_LENGTH, text(S.ctl_focal_length), chars.get(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)),
        )

        val ratioRange: Range<Float>? =
            if (Build.VERSION.SDK_INT >= 30) chars.get(C.CONTROL_ZOOM_RATIO_RANGE) else null
        val zoomRange = ratioRange ?: Range(1f, chars.get(C.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)

        val baseFocal = equivalentFocal(chars)
        val presets = sortedSetOf<Float>()
        if (zoomRange.lower < 0.99f) presets += zoomRange.lower
        presets += 1f
        if (baseFocal > 0f) {
            for (f in physicalFocals) {
                val r = (f / baseFocal * 10f).toInt() / 10f
                if (r > 1.1f && zoomRange.contains(r)) presets += r
            }
        }
        if (zoomRange.contains(2f)) presets += 2f
        val zoomPresets = presets.fold(mutableListOf<Float>()) { acc, v ->
            if (acc.none { abs(it - v) < 0.15f }) acc += v
            acc
        }

        val map = chars.get(C.SCALER_STREAM_CONFIGURATION_MAP)
        val streamSizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            .filter { it.width in 320..MAX_STREAM_W && it.height <= MAX_STREAM_H }
            .filter { EncoderSupport.isSizeSupported(it.width, it.height) }
            .distinct()
            .sortedByDescending { it.width * it.height }
        val previewSizes = map?.getOutputSizes(SurfaceHolder::class.java).orEmpty().toList()

        val fpsRanges = chars.get(C.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .distinct()
            .sortedWith(compareByDescending<Range<Int>> { it.upper }.thenByDescending { it.lower })

        val cctRange: Range<Int>? = if (Build.VERSION.SDK_INT >= 36) {
            val modes = chars.get(C.COLOR_CORRECTION_AVAILABLE_MODES) ?: intArrayOf()
            if (CameraMetadata.COLOR_CORRECTION_MODE_CCT in modes) chars.get(C.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE) else null
        } else null

        val active = chars.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val manualExposurePossible = manualSensor || CameraMetadata.CONTROL_AE_MODE_OFF in aeModes

        return CameraCapabilities(
            hardwareLevel = CameraNames.hardwareLevel(chars.get(C.INFO_SUPPORTED_HARDWARE_LEVEL)),
            capabilities = capsList.map(CameraNames::capability),
            enumControls = enums,
            floatControls = floats,
            minFocusDistance = chars.get(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.takeIf { it > 0f },
            hyperfocalDistance = chars.get(C.LENS_INFO_HYPERFOCAL_DISTANCE),
            isoRange = if (manualExposurePossible) chars.get(C.SENSOR_INFO_SENSITIVITY_RANGE) else null,
            exposureTimeRange = if (manualExposurePossible) chars.get(C.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null,
            maxFrameDuration = chars.get(C.SENSOR_INFO_MAX_FRAME_DURATION),
            evRange = chars.get(C.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            evStep = chars.get(C.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            zoomRange = zoomRange,
            zoomRatioSupported = ratioRange != null,
            zoomPresets = zoomPresets,
            flashAvailable = chars.get(C.FLASH_INFO_AVAILABLE) == true,
            torchStrengthMax = if (Build.VERSION.SDK_INT >= 35) chars.get(C.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1 else 1,
            manualWbGains = manualPost && CameraMetadata.CONTROL_AWB_MODE_OFF in awbModes,
            cctRange = cctRange,
            maxAfRegions = chars.get(C.CONTROL_MAX_REGIONS_AF) ?: 0,
            maxAeRegions = chars.get(C.CONTROL_MAX_REGIONS_AE) ?: 0,
            maxAwbRegions = chars.get(C.CONTROL_MAX_REGIONS_AWB) ?: 0,
            aeLockAvailable = chars.get(C.CONTROL_AE_LOCK_AVAILABLE) == true,
            awbLockAvailable = chars.get(C.CONTROL_AWB_LOCK_AVAILABLE) == true,
            fpsRanges = fpsRanges,
            streamSizes = streamSizes,
            previewSizes = previewSizes,
            sensorOrientation = chars.get(C.SENSOR_ORIENTATION) ?: 0,
            activeArrayWidth = active?.width() ?: 0,
            activeArrayHeight = active?.height() ?: 0,
        )
    }

    private fun enumCtl(
        key: R.Key<Int>,
        label: UiText,
        group: ControlGroup,
        values: IntArray?,
        namer: (Int) -> UiText,
    ): EnumControl? {
        if (values == null || values.isEmpty()) return null
        return EnumControl(key, label, group, values.distinct().sorted().map { EnumOption(it, namer(it)) })
    }

    private fun floatCtl(key: R.Key<Float>, label: UiText, values: FloatArray?): FloatChoiceControl? {
        if (values == null || values.size < 2) return null
        return FloatChoiceControl(key, label, values.distinct().sorted())
    }

    /** CameraCharacteristics の全キーを文字列化（情報タブ用） */
    fun dump(chars: C): List<Pair<String, String>> =
        chars.keys.mapNotNull { key ->
            @Suppress("UNCHECKED_CAST")
            val v = runCatching { chars.get(key as C.Key<Any>) }.getOrNull()
            key.name to stringify(v)
        }.sortedBy { it.first }

    fun stringify(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.contentToString()
        is LongArray -> v.contentToString()
        is FloatArray -> v.contentToString()
        is DoubleArray -> v.contentToString()
        is BooleanArray -> v.contentToString()
        is ByteArray -> v.contentToString()
        is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        is Size -> "${v.width}x${v.height}"
        else -> v.toString()
    }
}
