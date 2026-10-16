package space.webkombinat.MobileWebCamera.stream

import java.io.ByteArrayOutputStream

/**
 * H.264 だけを載せる最小限の MPEG-TS マルチプレクサ。
 * OBS の「メディアソース」(FFmpeg) が tcp:// / http:// で直接読める。
 */
class MpegTsMuxer {
    private val continuity = IntArray(3)
    private var basePtsUs = -1L

    fun mux(frame: EncodedFrame): ByteArray {
        val out = ByteArrayOutputStream(frame.data.size + frame.data.size / 184 * 4 + 188 * 3)
        if (basePtsUs < 0) basePtsUs = frame.ptsUs
        if (frame.isKey) {
            writePsi(out, PID_PAT, CC_PAT, patSection)
            writePsi(out, PID_PMT, CC_PMT, pmtSection)
        }
        val t90 = ((frame.ptsUs - basePtsUs).coerceAtLeast(0) * 9 / 100) and MASK_33
        val pts = (t90 + PTS_OFFSET) and MASK_33

        val pes = ByteArray(14 + AUD.size + frame.data.size)
        pes[0] = 0; pes[1] = 0; pes[2] = 1; pes[3] = 0xE0.toByte()
        pes[4] = 0; pes[5] = 0 // 映像は長さ 0 (無制限) で良い
        pes[6] = 0x80.toByte(); pes[7] = 0x80.toByte(); pes[8] = 5
        pes[9] = (0x21 or ((pts shr 29).toInt() and 0x0E)).toByte()
        pes[10] = (pts shr 22).toByte()
        pes[11] = (((pts shr 14).toInt() and 0xFE) or 1).toByte()
        pes[12] = (pts shr 7).toByte()
        pes[13] = (((pts shl 1).toInt() and 0xFE) or 1).toByte()
        System.arraycopy(AUD, 0, pes, 14, AUD.size)
        System.arraycopy(frame.data, 0, pes, 14 + AUD.size, frame.data.size)

        var offset = 0
        var first = true
        while (offset < pes.size) {
            offset += writePacket(
                out, PID_VIDEO, CC_VIDEO, first, pes, offset,
                pcr = if (first) t90 else null,
                randomAccess = first && frame.isKey,
            )
            first = false
        }
        return out.toByteArray()
    }

    private fun writePacket(
        out: ByteArrayOutputStream,
        pid: Int,
        ccIndex: Int,
        pusi: Boolean,
        data: ByteArray,
        offset: Int,
        pcr: Long?,
        randomAccess: Boolean,
    ): Int {
        val remaining = data.size - offset
        val pkt = ByteArray(PACKET_SIZE)
        pkt[0] = 0x47
        pkt[1] = ((if (pusi) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
        pkt[2] = pid.toByte()
        val cc = continuity[ccIndex]
        continuity[ccIndex] = (cc + 1) and 0x0F

        val afBody = ArrayList<Byte>(8)
        if (pcr != null || randomAccess) {
            afBody += ((if (randomAccess) 0x40 else 0) or (if (pcr != null) 0x10 else 0)).toByte()
            if (pcr != null) {
                afBody += (pcr shr 25).toByte()
                afBody += (pcr shr 17).toByte()
                afBody += (pcr shr 9).toByte()
                afBody += (pcr shr 1).toByte()
                afBody += (((pcr and 1L).toInt() shl 7) or 0x7E).toByte()
                afBody += 0
            }
        }
        val hasAf = afBody.isNotEmpty() || remaining < 184
        var header = 4
        if (hasAf) {
            var afLen = afBody.size
            val payloadSpace = 184 - 1 - afLen
            if (remaining < payloadSpace) {
                var stuffing = payloadSpace - remaining
                if (afLen == 0) { afBody += 0; afLen = 1; stuffing -= 1 }
                afLen += stuffing
            }
            pkt[3] = (0x30 or cc).toByte()
            pkt[4] = afLen.toByte()
            for (i in 0 until afLen) pkt[5 + i] = if (i < afBody.size) afBody[i] else 0xFF.toByte()
            header = 5 + afLen
        } else {
            pkt[3] = (0x10 or cc).toByte()
        }
        val n = minOf(remaining, PACKET_SIZE - header)
        System.arraycopy(data, offset, pkt, header, n)
        out.write(pkt)
        return n
    }

    private fun writePsi(out: ByteArrayOutputStream, pid: Int, ccIndex: Int, section: ByteArray) {
        val pkt = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        pkt[2] = pid.toByte()
        val cc = continuity[ccIndex]
        continuity[ccIndex] = (cc + 1) and 0x0F
        pkt[3] = (0x10 or cc).toByte()
        pkt[4] = 0 // pointer_field
        System.arraycopy(section, 0, pkt, 5, section.size)
        out.write(pkt)
    }

    companion object {
        private const val PACKET_SIZE = 188
        private const val PID_PAT = 0x0000
        private const val PID_PMT = 0x1000
        private const val PID_VIDEO = 0x0100
        private const val CC_PAT = 0
        private const val CC_PMT = 1
        private const val CC_VIDEO = 2
        private const val MASK_33 = (1L shl 33) - 1
        private const val PTS_OFFSET = 9000L // 100ms
        private const val STREAM_TYPE_H264 = 0x1B

        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        private val patSection = section(
            0x00, 0x0001,
            byteArrayOf(0x00, 0x01, (0xE0 or (PID_PMT shr 8)).toByte(), PID_PMT.toByte()),
        )

        private val pmtSection = section(
            0x02, 0x0001,
            byteArrayOf(
                (0xE0 or (PID_VIDEO shr 8)).toByte(), PID_VIDEO.toByte(), // PCR PID
                0xF0.toByte(), 0x00, // program_info_length
                STREAM_TYPE_H264.toByte(),
                (0xE0 or (PID_VIDEO shr 8)).toByte(), PID_VIDEO.toByte(),
                0xF0.toByte(), 0x00,
            ),
        )

        private fun section(tableId: Int, idExt: Int, body: ByteArray): ByteArray {
            val length = 5 + body.size + 4
            val s = ByteArray(3 + length)
            s[0] = tableId.toByte()
            s[1] = (0xB0 or ((length shr 8) and 0x0F)).toByte()
            s[2] = length.toByte()
            s[3] = (idExt shr 8).toByte()
            s[4] = idExt.toByte()
            s[5] = 0xC1.toByte() // version 0, current_next 1
            s[6] = 0
            s[7] = 0
            System.arraycopy(body, 0, s, 8, body.size)
            val crc = crc32Mpeg(s, 0, s.size - 4)
            s[s.size - 4] = (crc ushr 24).toByte()
            s[s.size - 3] = (crc ushr 16).toByte()
            s[s.size - 2] = (crc ushr 8).toByte()
            s[s.size - 1] = crc.toByte()
            return s
        }

        private fun crc32Mpeg(data: ByteArray, off: Int, len: Int): Int {
            var crc = -1
            for (i in off until off + len) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
                repeat(8) {
                    crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
                }
            }
            return crc
        }
    }
}
