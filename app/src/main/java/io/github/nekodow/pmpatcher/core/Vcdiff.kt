package io.github.nekodow.pmpatcher.core

import org.tukaani.xz.SingleXZInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.Adler32

/**
 * VCDIFF (RFC 3284) decoder with the xdelta3 extensions used by Project PM patches:
 * per-window Adler-32 checksums and LZMA ("xz") secondary compression, where each
 * section type keeps one xz stream running across windows and is never terminated.
 */
class Vcdiff(
    private val source: ByteBuffer,
    private val patch: ByteArray,
    private val out: RandomAccessFile,
    private val progress: (Float) -> Unit = {},
) {
    private var p = 0
    private val sections = arrayOfNulls<ChunkFeed>(3)
    private val xz = arrayOfNulls<InputStream>(3)
    private var secondaryId = 0

    private fun byte(): Int = patch[p++].toInt() and 0xFF

    private fun varint(): Long {
        var v = 0L
        while (true) {
            val b = byte()
            v = (v shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) return v
        }
    }

    private fun take(n: Int): ByteArray {
        val r = patch.copyOfRange(p, p + n); p += n; return r
    }

    fun decode(): Long {
        if (take(4).contentEquals(byteArrayOf(0xD6.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0))) {
            // ok
        } else throw PatchException("not_vcdiff")
        val hi = byte()
        if (hi and 1 != 0) secondaryId = byte()
        if (hi and 2 != 0) throw PatchException("custom_code_table")
        if (hi and 4 != 0) { val n = varint().toInt(); p += n }
        if (secondaryId != 0 && secondaryId != 2) throw PatchException("secondary_$secondaryId")

        var written = 0L
        out.setLength(0)
        while (p < patch.size) {
            val wi = byte()
            var segLen = 0
            var seg: ByteArray? = null
            var srcPos = 0L
            if (wi and 3 != 0) {
                segLen = varint().toInt()
                srcPos = varint()
                if (wi and 2 != 0) { // segment taken from already written target
                    seg = ByteArray(segLen)
                    out.seek(srcPos); out.readFully(seg)
                }
            }
            varint() // length of delta encoding
            val tlen = varint().toInt()
            val di = byte()
            val ld = varint().toInt(); val li = varint().toInt(); val la = varint().toInt()
            var cksum = -1L
            if (wi and 4 != 0) {
                cksum = ((byte().toLong() shl 24) or (byte().toLong() shl 16) or (byte().toLong() shl 8) or byte().toLong())
            }
            var data = take(ld); var inst = take(li); var addr = take(la)
            if (di and 1 != 0) data = secondary(0, data)
            if (di and 2 != 0) inst = secondary(1, inst)
            if (di and 4 != 0) addr = secondary(2, addr)

            val tgt = ByteArray(tlen)
            var t = 0
            var dp = 0; var ip = 0; var ap = 0
            val near = LongArray(4); var ni = 0
            val same = LongArray(3 * 256)

            fun readVar(buf: ByteArray, startPos: Int): Pair<Long, Int> {
                var pos = startPos; var v = 0L
                while (true) {
                    val b = buf[pos++].toInt() and 0xFF
                    v = (v shl 7) or (b and 0x7F).toLong()
                    if (b and 0x80 == 0) return v to pos
                }
            }

            fun exec(type: Int, size: Int, mode: Int) {
                when (type) {
                    ADD -> { System.arraycopy(data, dp, tgt, t, size); dp += size; t += size }
                    RUN -> { val b = data[dp++]; java.util.Arrays.fill(tgt, t, t + size, b); t += size }
                    COPY -> {
                        val here = segLen.toLong() + t
                        val a: Long = when {
                            mode == 0 -> { val (v, np) = readVar(addr, ap); ap = np; v }
                            mode == 1 -> { val (v, np) = readVar(addr, ap); ap = np; here - v }
                            mode < 6 -> { val (v, np) = readVar(addr, ap); ap = np; near[mode - 2] + v }
                            else -> { val b = addr[ap++].toInt() and 0xFF; same[(mode - 6) * 256 + b] }
                        }
                        near[ni] = a; ni = (ni + 1) % 4; same[(a % 768).toInt()] = a
                        var k = 0
                        // part inside the source/target segment
                        if (a < segLen) {
                            val n = minOf(size.toLong(), segLen - a).toInt()
                            if (seg != null) System.arraycopy(seg, a.toInt(), tgt, t, n)
                            else { source.position((srcPos + a).toInt()); source.get(tgt, t, n) }
                            k = n
                        }
                        // part inside the current target window (may overlap itself)
                        while (k < size) {
                            tgt[t + k] = tgt[(a + k - segLen).toInt()]; k++
                        }
                        t += size
                    }
                }
            }

            while (ip < inst.size) {
                val code = inst[ip++].toInt() and 0xFF
                val e = CODE_TABLE[code]
                if (e[0] != NOOP) {
                    var s = e[1]
                    if (s == 0) { val (v, np) = readVar(inst, ip); ip = np; s = v.toInt() }
                    exec(e[0], s, e[2])
                }
                if (e[3] != NOOP) {
                    var s = e[4]
                    if (s == 0) { val (v, np) = readVar(inst, ip); ip = np; s = v.toInt() }
                    exec(e[3], s, e[5])
                }
            }
            if (t != tlen) throw PatchException("bad_window_length")
            if (cksum >= 0) {
                val ad = Adler32(); ad.update(tgt)
                if (ad.value != cksum) throw PatchException("checksum")
            }
            out.seek(written); out.write(tgt)
            written += tlen
            progress(p.toFloat() / patch.size)
        }
        return written
    }

    private fun secondary(kind: Int, section: ByteArray): ByteArray {
        var pos = 0; var size = 0L
        while (true) {
            val b = section[pos++].toInt() and 0xFF
            size = (size shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) break
        }
        val feed = sections[kind] ?: ChunkFeed().also { sections[kind] = it }
        feed.push(section, pos)
        val stream = xz[kind] ?: SingleXZInputStream(feed, -1, false).also { xz[kind] = it }
        val res = ByteArray(size.toInt())
        var off = 0
        while (off < res.size) {
            val n = stream.read(res, off, res.size - off)
            if (n <= 0) throw PatchException("lzma")
            off += n
        }
        return res
    }

    /** Input stream fed window by window; the xz decoder never needs to read ahead past a window. */
    private class ChunkFeed : InputStream() {
        private var buf = ByteArray(0); private var pos = 0
        fun push(b: ByteArray, from: Int) {
            buf = if (pos < buf.size) buf.copyOfRange(pos, buf.size) + b.copyOfRange(from, b.size)
            else b.copyOfRange(from, b.size)
            pos = 0
        }
        override fun read(): Int = if (pos < buf.size) buf[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= buf.size) return -1
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n); pos += n; return n
        }
        override fun available(): Int = buf.size - pos
    }

    companion object {
        const val NOOP = 0; const val ADD = 1; const val RUN = 2; const val COPY = 3

        /** Default RFC 3284 code table: [type1,size1,mode1,type2,size2,mode2]. */
        val CODE_TABLE: Array<IntArray> = run {
            val t = ArrayList<IntArray>()
            t += intArrayOf(RUN, 0, 0, NOOP, 0, 0)
            for (s in 0..17) t += intArrayOf(ADD, s, 0, NOOP, 0, 0)
            for (m in 0..8) {
                t += intArrayOf(COPY, 0, m, NOOP, 0, 0)
                for (s in 4..18) t += intArrayOf(COPY, s, m, NOOP, 0, 0)
            }
            for (m in 0..5) for (a in 1..4) for (c in 4..6) t += intArrayOf(ADD, a, 0, COPY, c, m)
            for (m in 6..8) for (a in 1..4) t += intArrayOf(ADD, a, 0, COPY, 4, m)
            for (m in 0..8) t += intArrayOf(COPY, 4, m, ADD, 1, 0)
            check(t.size == 256)
            t.toTypedArray()
        }
    }
}

class PatchException(val code: String) : Exception(code)
