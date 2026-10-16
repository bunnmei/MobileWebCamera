package space.webkombinat.MobileWebCamera.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/** エンコード済みのアクセスユニット（Annex-B, キーフレームには SPS/PPS を先頭付与済み） */
class EncodedFrame(val data: ByteArray, val ptsUs: Long, val isKey: Boolean)

object EncoderSupport {
    const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    private val encoderInfo: MediaCodecInfo? by lazy {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }
        all.firstOrNull { Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated } ?: all.firstOrNull()
    }

    fun isSizeSupported(w: Int, h: Int): Boolean {
        val caps = encoderInfo?.getCapabilitiesForType(MIME)?.videoCapabilities ?: return true
        return caps.isSizeSupported(w, h)
    }

    fun codecName(): String? = encoderInfo?.name
}

/**
 * Surface 入力の H.264 ハードウェアエンコーダ。
 * Camera2 のセッション出力に [inputSurface] を加えるだけでエンコードされる。
 */
class H264Encoder(
    /** カメラから受け取るサイズ（センサー向き） */
    val inputWidth: Int,
    val inputHeight: Int,
    /** 作成時の回転。90/270 の場合は縦横を入れ替えてエンコードする */
    initialRotation: Int,
    val fps: Int,
    bitrate: Int,
    private val onFrame: (EncodedFrame) -> Unit,
) {
    private val thread = HandlerThread("H264Encoder").apply { start() }
    private val handler = Handler(thread.looper)

    /** true = 縦横入れ替え（縦長配信） */
    val swapped: Boolean = initialRotation % 180 == 90
    val width: Int = if (swapped) inputHeight else inputWidth
    val height: Int = if (swapped) inputWidth else inputHeight
    private val codec: MediaCodec
    private val codecSurface: Surface
    private val rotator: GlRotator

    /** カメラの出力先（GlRotator 経由でエンコーダへ） */
    val inputSurface: Surface get() = rotator.inputSurface

    /** 映像の回転。配信中は縦横が変わらない範囲（0↔180, 90↔270）で変更可能 */
    var rotation: Int
        get() = rotator.rotation
        set(value) {
            rotator.rotation = value
        }

    @Volatile
    private var codecConfig: ByteArray? = null

    @Volatile
    private var released = false

    init {
        val name = EncoderSupport.codecName()
        codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(EncoderSupport.MIME)
        val caps = codec.codecInfo.getCapabilitiesForType(EncoderSupport.MIME)

        val format = MediaFormat.createVideoFormat(EncoderSupport.MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // カメラ切替中などフレームが止まっても配信が途切れないよう前フレームを繰り返す
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps * 3)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            // デスクトップ側 (openh264) との互換性のため Baseline を優先。B フレームも出ないので低遅延。
            val baseline = caps.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
            }
            val profile = if (baseline) MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
            else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            if (caps.profileLevels.any { it.profile == profile }) {
                setInteger(MediaFormat.KEY_PROFILE, profile)
            }
            if (Build.VERSION.SDK_INT >= 30 &&
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            ) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codecConfig = bytes
                        } else {
                            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val cfg = codecConfig
                            val payload = if (isKey && cfg != null) cfg + bytes else bytes
                            onFrame(EncodedFrame(payload, info.presentationTimeUs, isKey))
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "output after release", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "codec error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                // csd-0/csd-1 が来る端末もある
                val sps = format.getByteBuffer("csd-0")
                val pps = format.getByteBuffer("csd-1")
                if (sps != null && codecConfig == null) {
                    val a = ByteArray(sps.remaining()).also { sps.duplicate().get(it) }
                    val b = pps?.let { p -> ByteArray(p.remaining()).also { p.duplicate().get(it) } } ?: ByteArray(0)
                    codecConfig = a + b
                }
            }
        }, handler)

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codecSurface = codec.createInputSurface()
        rotator = try {
            GlRotator(inputWidth, inputHeight, codecSurface).also { it.rotation = initialRotation }
        } catch (e: Exception) {
            codec.release()
            codecSurface.release()
            throw e
        }
        codec.start()
        Log.i(TAG, "encoder ${codec.name} ${width}x$height@$fps ${bitrate}bps")
    }

    fun requestKeyFrame() {
        if (released) return
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    fun setBitrate(bps: Int) {
        if (released) return
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        }
    }

    fun release() {
        if (released) return
        released = true
        rotator.release()
        handler.post {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { codecSurface.release() }
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "H264Encoder"
    }
}
