package space.webkombinat.MobileWebCamera.stream

import space.webkombinat.MobileWebCamera.R.string as S
import space.webkombinat.MobileWebCamera.util.UiText
import space.webkombinat.MobileWebCamera.util.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class StreamStatus(
    val serversRunning: Boolean = false,
    val tsPort: Int = StreamManager.TS_PORT,
    val rawPort: Int = StreamManager.RAW_PORT,
    val tsClients: Int = 0,
    val rawClients: Int = 0,
    val encoderSize: String? = null,
    val addresses: List<LocalAddress> = emptyList(),
    val error: UiText? = null,
)

/** エンコーダと配信サーバの管理。カメラ側 (CameraController) からエンコーダ Surface を取得する。 */
@Singleton
class StreamManager @Inject constructor() {

    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    @Volatile
    private var encoder: H264Encoder? = null

    private val tsServer = StreamServer(TS_PORT, StreamProtocol.MPEG_TS, ::requestKeyFrame, ::updateClients)
    private val rawServer = StreamServer(RAW_PORT, StreamProtocol.RAW, ::requestKeyFrame, ::updateClients)

    @Synchronized
    fun startServers() {
        if (_status.value.serversRunning) return
        try {
            tsServer.start()
            rawServer.start()
            _status.update {
                it.copy(serversRunning = true, addresses = NetworkUtils.localAddresses(), error = null)
            }
        } catch (e: Exception) {
            tsServer.stop()
            rawServer.stop()
            _status.update { it.copy(serversRunning = false, error = text(S.error_server_start, e.message ?: e.toString())) }
        }
    }

    @Synchronized
    fun stopServers() {
        tsServer.stop()
        rawServer.stop()
        _status.update { it.copy(serversRunning = false, tsClients = 0, rawClients = 0) }
    }

    /** 新しいエンコーダを作成して差し替える。古いエンコーダは戻り値として返すので、カメラセッションから外した後に release すること。 */
    @Synchronized
    fun replaceEncoder(
        inputWidth: Int,
        inputHeight: Int,
        rotation: Int,
        fps: Int,
        bitrate: Int,
    ): Pair<H264Encoder, H264Encoder?> {
        val old = encoder
        val enc = H264Encoder(inputWidth, inputHeight, rotation, fps, bitrate) { frame ->
            tsServer.broadcast(frame)
            rawServer.broadcast(frame)
        }
        encoder = enc
        _status.update { it.copy(encoderSize = "${enc.width}x${enc.height}@$fps") }
        return enc to old
    }

    @Synchronized
    fun detachEncoder(): H264Encoder? {
        val old = encoder
        encoder = null
        _status.update { it.copy(encoderSize = null) }
        return old
    }

    fun setBitrate(bps: Int) {
        encoder?.setBitrate(bps)
    }

    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    fun refreshAddresses() {
        val list = NetworkUtils.localAddresses()
        if (list != _status.value.addresses) _status.update { it.copy(addresses = list) }
    }

    private fun updateClients() {
        _status.update { it.copy(tsClients = tsServer.clientCount, rawClients = rawServer.clientCount) }
    }

    companion object {
        const val TS_PORT = 4747
        const val RAW_PORT = 4748
    }
}
