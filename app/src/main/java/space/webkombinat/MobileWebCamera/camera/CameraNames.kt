package space.webkombinat.MobileWebCamera.camera

import android.hardware.camera2.CameraMetadata as M
import space.webkombinat.MobileWebCamera.R.string as S
import space.webkombinat.MobileWebCamera.util.UiText
import space.webkombinat.MobileWebCamera.util.raw
import space.webkombinat.MobileWebCamera.util.text

/** Camera2 の列挙値を UI 表示用のラベルへ変換する。未知の値は "#n" で表示。 */
object CameraNames {

    fun afMode(v: Int): UiText = when (v) {
        M.CONTROL_AF_MODE_OFF -> text(S.af_manual)
        M.CONTROL_AF_MODE_AUTO -> text(S.af_auto)
        M.CONTROL_AF_MODE_MACRO -> text(S.af_macro)
        M.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> text(S.af_continuous_video)
        M.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> text(S.af_continuous_picture)
        M.CONTROL_AF_MODE_EDOF -> raw("EDOF")
        else -> raw("#$v")
    }

    fun aeMode(v: Int): UiText = when (v) {
        M.CONTROL_AE_MODE_OFF -> text(S.mode_manual)
        M.CONTROL_AE_MODE_ON -> text(S.mode_auto)
        M.CONTROL_AE_MODE_ON_AUTO_FLASH -> text(S.ae_auto_flash)
        M.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> text(S.ae_always_flash)
        M.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> text(S.ae_redeye)
        M.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> text(S.ae_external_flash)
        6 /* ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY */ -> text(S.ae_low_light_boost)
        else -> raw("#$v")
    }

    fun awbMode(v: Int): UiText = when (v) {
        M.CONTROL_AWB_MODE_OFF -> text(S.mode_manual)
        M.CONTROL_AWB_MODE_AUTO -> text(S.mode_auto)
        M.CONTROL_AWB_MODE_INCANDESCENT -> text(S.awb_incandescent)
        M.CONTROL_AWB_MODE_FLUORESCENT -> text(S.awb_fluorescent)
        M.CONTROL_AWB_MODE_WARM_FLUORESCENT -> text(S.awb_warm_fluorescent)
        M.CONTROL_AWB_MODE_DAYLIGHT -> text(S.awb_daylight)
        M.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> text(S.awb_cloudy)
        M.CONTROL_AWB_MODE_TWILIGHT -> text(S.awb_twilight)
        M.CONTROL_AWB_MODE_SHADE -> text(S.awb_shade)
        else -> raw("#$v")
    }

    fun controlMode(v: Int): UiText = when (v) {
        M.CONTROL_MODE_OFF -> text(S.control_off)
        M.CONTROL_MODE_AUTO -> raw("AUTO")
        M.CONTROL_MODE_USE_SCENE_MODE -> text(S.control_use_scene)
        M.CONTROL_MODE_OFF_KEEP_STATE -> text(S.control_off_keep_state)
        4 /* USE_EXTENDED_SCENE_MODE */ -> text(S.control_use_extended_scene)
        else -> raw("#$v")
    }

    fun sceneMode(v: Int): UiText = when (v) {
        M.CONTROL_SCENE_MODE_DISABLED -> text(S.disabled)
        M.CONTROL_SCENE_MODE_FACE_PRIORITY -> text(S.scene_face_priority)
        M.CONTROL_SCENE_MODE_ACTION -> text(S.scene_action)
        M.CONTROL_SCENE_MODE_PORTRAIT -> text(S.scene_portrait)
        M.CONTROL_SCENE_MODE_LANDSCAPE -> text(S.scene_landscape)
        M.CONTROL_SCENE_MODE_NIGHT -> text(S.scene_night)
        M.CONTROL_SCENE_MODE_NIGHT_PORTRAIT -> text(S.scene_night_portrait)
        M.CONTROL_SCENE_MODE_THEATRE -> text(S.scene_theatre)
        M.CONTROL_SCENE_MODE_BEACH -> text(S.scene_beach)
        M.CONTROL_SCENE_MODE_SNOW -> text(S.scene_snow)
        M.CONTROL_SCENE_MODE_SUNSET -> text(S.scene_sunset)
        M.CONTROL_SCENE_MODE_STEADYPHOTO -> text(S.scene_steadyphoto)
        M.CONTROL_SCENE_MODE_FIREWORKS -> text(S.scene_fireworks)
        M.CONTROL_SCENE_MODE_SPORTS -> text(S.scene_sports)
        M.CONTROL_SCENE_MODE_PARTY -> text(S.scene_party)
        M.CONTROL_SCENE_MODE_CANDLELIGHT -> text(S.scene_candlelight)
        M.CONTROL_SCENE_MODE_BARCODE -> text(S.scene_barcode)
        M.CONTROL_SCENE_MODE_HDR -> raw("HDR")
        else -> raw("#$v")
    }

    fun effectMode(v: Int): UiText = when (v) {
        M.CONTROL_EFFECT_MODE_OFF -> text(S.effect_none)
        M.CONTROL_EFFECT_MODE_MONO -> text(S.effect_mono)
        M.CONTROL_EFFECT_MODE_NEGATIVE -> text(S.effect_negative)
        M.CONTROL_EFFECT_MODE_SOLARIZE -> text(S.effect_solarize)
        M.CONTROL_EFFECT_MODE_SEPIA -> text(S.effect_sepia)
        M.CONTROL_EFFECT_MODE_POSTERIZE -> text(S.effect_posterize)
        M.CONTROL_EFFECT_MODE_WHITEBOARD -> text(S.effect_whiteboard)
        M.CONTROL_EFFECT_MODE_BLACKBOARD -> text(S.effect_blackboard)
        M.CONTROL_EFFECT_MODE_AQUA -> text(S.effect_aqua)
        else -> raw("#$v")
    }

    fun antibanding(v: Int): UiText = when (v) {
        M.CONTROL_AE_ANTIBANDING_MODE_OFF -> raw("OFF")
        M.CONTROL_AE_ANTIBANDING_MODE_50HZ -> raw("50Hz")
        M.CONTROL_AE_ANTIBANDING_MODE_60HZ -> raw("60Hz")
        M.CONTROL_AE_ANTIBANDING_MODE_AUTO -> text(S.mode_auto)
        else -> raw("#$v")
    }

    fun videoStab(v: Int): UiText = when (v) {
        M.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> raw("OFF")
        M.CONTROL_VIDEO_STABILIZATION_MODE_ON -> raw("ON")
        2 /* PREVIEW_STABILIZATION */ -> text(S.video_stab_preview)
        else -> raw("#$v")
    }

    fun ois(v: Int): UiText = when (v) {
        M.LENS_OPTICAL_STABILIZATION_MODE_OFF -> raw("OFF")
        M.LENS_OPTICAL_STABILIZATION_MODE_ON -> raw("ON")
        else -> raw("#$v")
    }

    /** NOISE_REDUCTION / EDGE / HOT_PIXEL / SHADING / ABERRATION / TONEMAP 等の品質系共通 */
    fun quality(v: Int): UiText = when (v) {
        0 -> raw("OFF")
        1 -> raw("FAST")
        2 -> raw("HIGH_QUALITY")
        3 -> raw("MINIMAL / ZSL")
        4 -> raw("ZSL")
        else -> raw("#$v")
    }

    fun tonemap(v: Int): UiText = when (v) {
        M.TONEMAP_MODE_CONTRAST_CURVE -> text(S.tonemap_contrast_curve)
        M.TONEMAP_MODE_FAST -> raw("FAST")
        M.TONEMAP_MODE_HIGH_QUALITY -> raw("HIGH_QUALITY")
        M.TONEMAP_MODE_GAMMA_VALUE -> text(S.tonemap_gamma)
        M.TONEMAP_MODE_PRESET_CURVE -> text(S.tonemap_preset_curve)
        else -> raw("#$v")
    }

    fun faceDetect(v: Int): UiText = when (v) {
        M.STATISTICS_FACE_DETECT_MODE_OFF -> raw("OFF")
        M.STATISTICS_FACE_DETECT_MODE_SIMPLE -> raw("SIMPLE")
        M.STATISTICS_FACE_DETECT_MODE_FULL -> raw("FULL")
        else -> raw("#$v")
    }

    fun extendedScene(v: Int): UiText = when (v) {
        0 -> text(S.disabled)
        1 -> text(S.bokeh_still)
        2 -> text(S.bokeh_continuous)
        else -> raw("#$v")
    }

    fun onOff(v: Int): UiText = raw(if (v == 0) "OFF" else "ON")

    fun testPattern(v: Int): UiText = when (v) {
        M.SENSOR_TEST_PATTERN_MODE_OFF -> raw("OFF")
        M.SENSOR_TEST_PATTERN_MODE_SOLID_COLOR -> text(S.test_pattern_solid)
        M.SENSOR_TEST_PATTERN_MODE_COLOR_BARS -> text(S.test_pattern_color_bars)
        M.SENSOR_TEST_PATTERN_MODE_COLOR_BARS_FADE_TO_GRAY -> text(S.test_pattern_color_bars_fade)
        M.SENSOR_TEST_PATTERN_MODE_PN9 -> raw("PN9")
        M.SENSOR_TEST_PATTERN_MODE_CUSTOM1 -> raw("CUSTOM1")
        else -> raw("#$v")
    }

    fun afState(v: Int?) = when (v) {
        M.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
        M.CONTROL_AF_STATE_PASSIVE_SCAN -> "SCAN"
        M.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "FOCUSED"
        M.CONTROL_AF_STATE_ACTIVE_SCAN -> "SCANNING"
        M.CONTROL_AF_STATE_FOCUSED_LOCKED -> "LOCKED"
        M.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT FOCUSED"
        M.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "UNFOCUSED"
        null -> "-"
        else -> "#$v"
    }

    fun hardwareLevel(v: Int?) = when (v) {
        M.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        M.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        M.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        M.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        M.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "#$v"
    }

    fun capability(v: Int) = when (v) {
        M.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        M.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        M.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        M.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        M.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        M.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        M.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        M.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        M.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        M.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        M.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        M.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        M.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        M.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        M.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        M.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        16 -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        17 -> "REMOSAIC_REPROCESSING"
        18 -> "DYNAMIC_RANGE_TEN_BIT"
        19 -> "STREAM_USE_CASE"
        20 -> "COLOR_SPACE_PROFILES"
        else -> "#$v"
    }
}
