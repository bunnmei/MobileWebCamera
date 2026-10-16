package space.webkombinat.MobileWebCamera.stream

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * MPEG-TS over TCP（HTTP GET にも応答）の配信サーバ。OBS メディアソース / VLC 用。クライアントごとに送信スレッドとキューを持ち、
 * 回線が詰まったクライアントだけ次のキーフレームまでスキップする。
 */
class StreamServer(
    val port: Int,
    private val onKeyFrameNeeded: () -> Unit,
    private val onClientCountChanged: () -> Unit,
) {
    private val clients = CopyOnWriteArrayList<Client>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    val clientCount: Int get() = clients.size

    fun start() {
        if (running) return
        running = true
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = ss
        acceptThread = Thread({
            while (running) {
                try {
                    val s = ss.accept()
                    val c = Client(s)
                    clients += c
                    c.start()
                    onClientCountChanged()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept failed", e)
                }
            }
        }, "StreamServer-$port").apply { start() }
        Log.i(TAG, "listening on $port")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { it.close() }
        clients.clear()
        onClientCountChanged()
    }

    fun broadcast(frame: EncodedFrame) {
        for (c in clients) c.offer(frame)
    }

    private inner class Client(private val socket: Socket) : Thread("StreamClient-$port") {
        private val queue = ArrayBlockingQueue<EncodedFrame>(QUEUE_CAPACITY)

        @Volatile
        private var needKey = true

        @Volatile
        private var closed = false

        fun offer(frame: EncodedFrame) {
            if (closed) return
            if (!queue.offer(frame)) {
                // 送信が追いつかない: 溜まったフレームを捨てて次の IDR から再開
                queue.clear()
                needKey = true
                onKeyFrameNeeded()
            }
        }

        override fun run() {
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 512 * 1024
                val out = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
                handleOptionalHttp(out)
                val muxer = MpegTsMuxer()
                onKeyFrameNeeded()
                while (!closed) {
                    val f = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    if (needKey) {
                        if (!f.isKey) continue
                        needKey = false
                    }
                    out.write(muxer.mux(f))
                    if (queue.isEmpty()) out.flush()
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                close()
                clients.remove(this)
                onClientCountChanged()
            }
        }

        /** ブラウザ/VLC/OBS が http:// でアクセスしてきた場合は HTTP ヘッダを返す */
        private fun handleOptionalHttp(out: OutputStream) {
            val input = socket.getInputStream()
            socket.soTimeout = 120 // HTTP クライアントは接続直後に GET を送るので短くてよい
            try {
                val buf = ByteArray(8192)
                var len = 0
                while (len < buf.size) {
                    val n = input.read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                    val text = String(buf, 0, len, Charsets.ISO_8859_1)
                    if (text.contains("\r\n\r\n")) {
                        out.write(
                            ("HTTP/1.0 200 OK\r\n" +
                                "Content-Type: video/mp2t\r\n" +
                                "Cache-Control: no-cache\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                        )
                        out.flush()
                        break
                    }
                }
            } catch (_: SocketTimeoutException) {
                // 何も送ってこない = 生の tcp:// クライアント
            } finally {
                socket.soTimeout = 0
            }
        }

        fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "StreamServer"
        private const val QUEUE_CAPACITY = 45
    }
}
