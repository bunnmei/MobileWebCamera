package space.webkombinat.MobileWebCamera.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MpegTsMuxerTest {

    @Test
    fun packetsAreAligned() {
        val muxer = MpegTsMuxer()
        for (size in listOf(1, 100, 170, 175, 176, 177, 182, 183, 184, 185, 1000, 50_000)) {
            val out = muxer.mux(EncodedFrame(ByteArray(size) { 7 }, size * 1000L, size % 2 == 0))
            assertEquals(0, out.size % 188)
            for (i in out.indices step 188) assertEquals(0x47.toByte(), out[i])
        }
    }

    /** -Dts.in=<annexb.h264> -Dts.out=<out.ts> を指定すると実ファイルを TS 化（ffprobe で検証用） */
    @Test
    fun muxFileIfGiven() {
        val input = System.getProperty("ts.in") ?: return
        val output = System.getProperty("ts.out") ?: return
        val data = File(input).readBytes()
        // Annex-B をアクセスユニット（VCL NAL ごと）に分割
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts += if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
                i += 3
            } else i++
        }
        val muxer = MpegTsMuxer()
        val out = File(output).outputStream()
        var pending = java.io.ByteArrayOutputStream()
        var key = false
        var frame = 0
        for ((n, s) in starts.withIndex()) {
            val e = if (n + 1 < starts.size) starts[n + 1] else data.size
            val nal = data.copyOfRange(s, e)
            val hdr = nal.indexOfFirst { it == 1.toByte() } + 1
            val type = nal[hdr].toInt() and 0x1F
            pending.write(nal)
            if (type == 5) key = true
            if (type == 1 || type == 5) {
                out.write(muxer.mux(EncodedFrame(pending.toByteArray(), 1_000_000L + frame * 33_333L, key)))
                pending = java.io.ByteArrayOutputStream(); key = false; frame++
            }
        }
        out.close()
        assertTrue(frame > 0)
    }
}
