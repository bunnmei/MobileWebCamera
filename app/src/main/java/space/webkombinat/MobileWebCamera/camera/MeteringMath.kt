package space.webkombinat.MobileWebCamera.camera

import android.graphics.PointF
import android.graphics.Rect
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

object MeteringMath {

    /** センサー画像を画面上でどれだけ（時計回りに）回して表示しているか */
    fun relativeRotation(sensorOrientation: Int, displayRotationDeg: Int, front: Boolean): Int =
        if (front) (sensorOrientation + displayRotationDeg) % 360
        else (sensorOrientation - displayRotationDeg + 360) % 360

    /** プレビュー上の正規化座標 → センサー上の正規化座標 */
    fun viewToSensor(nx: Float, ny: Float, sensorOrientation: Int, displayRotationDeg: Int, front: Boolean): PointF {
        val x = if (front) 1f - nx else nx
        val y = ny
        return when (relativeRotation(sensorOrientation, displayRotationDeg, front)) {
            90 -> PointF(y, 1f - x)
            180 -> PointF(1f - x, 1f - y)
            270 -> PointF(1f - y, x)
            else -> PointF(x, y)
        }
    }

    /**
     * センサー正規化座標から 3A 領域を作る。
     * CONTROL_ZOOM_RATIO 使用時はズーム後の視野がアクティブ配列全体に対応するため [crop] は null を渡す。
     */
    fun meteringRect(p: PointF, activeW: Int, activeH: Int, crop: Rect?): MeteringRectangle {
        val area = crop ?: Rect(0, 0, activeW, activeH)
        val half = (minOf(area.width(), area.height()) * 0.08f).toInt().coerceAtLeast(16)
        val cx = (area.left + p.x * area.width()).toInt()
        val cy = (area.top + p.y * area.height()).toInt()
        val l = (cx - half).coerceIn(0, activeW - 2 * half)
        val t = (cy - half).coerceIn(0, activeH - 2 * half)
        return MeteringRectangle(l, t, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    fun cropForZoom(activeW: Int, activeH: Int, zoom: Float): Rect {
        val w = (activeW / zoom).toInt()
        val h = (activeH / zoom).toInt()
        val l = (activeW - w) / 2
        val t = (activeH - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    /** 色温度 (K) の光源を白にするための RGGB ゲイン（近似） */
    fun kelvinToGains(kelvin: Int): RggbChannelVector {
        val t = kelvin / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 1.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        val rr = r.coerceIn(1.0, 255.0)
        val gg = g.coerceIn(1.0, 255.0)
        val bb = b.coerceIn(1.0, 255.0)
        return RggbChannelVector((gg / rr).toFloat(), 1f, 1f, (gg / bb).toFloat())
    }
}
