package io.github.nekodow.pmpatcher.core

import java.io.RandomAccessFile

/**
 * Minimal NDS ROM access over a RandomAccessFile: file name table lookup, file read,
 * and file replacement without rebuilding the image (in place when the new data fits
 * its slot, otherwise appended after the last used byte).
 */
class NdsRom(private val f: RandomAccessFile, private val writable: Boolean = false) {
    val header = ByteArray(0x200).also { f.seek(0); f.readFully(it) }
    val gameCode = String(header, 0xC, 4, Charsets.US_ASCII)
    private val fatOff = u32(header, 0x48)
    private val fatSize = u32(header, 0x4C)
    private val fileCount = (fatSize / 8).toInt()
    private val starts = LongArray(fileCount)
    private val ends = LongArray(fileCount)
    val paths = HashMap<String, Int>()
    private var usedEnd: Long

    init {
        val fat = ByteArray(fatSize.toInt()); f.seek(fatOff); f.readFully(fat)
        for (i in 0 until fileCount) { starts[i] = u32(fat, i * 8); ends[i] = u32(fat, i * 8 + 4) }
        val fntOff = u32(header, 0x40); val fntSize = u32(header, 0x44)
        val fnt = ByteArray(fntSize.toInt()); f.seek(fntOff); f.readFully(fnt)
        walkDir(fnt, 0xF000, "")
        usedEnd = maxOf(u32(header, 0x80), ends.maxOrNull() ?: 0L)
    }

    private fun walkDir(fnt: ByteArray, dirId: Int, prefix: String) {
        val base = (dirId and 0xFFF) * 8
        var p = u32(fnt, base).toInt()
        var fileId = u16(fnt, base + 4)
        while (true) {
            val len = fnt[p++].toInt() and 0xFF
            if (len == 0) break
            if (len < 0x80) {
                paths[prefix + String(fnt, p, len, Charsets.ISO_8859_1)] = fileId++
                p += len
            } else {
                val n = len - 0x80
                val name = String(fnt, p, n, Charsets.ISO_8859_1); p += n
                val sub = u16(fnt, p); p += 2
                walkDir(fnt, sub, "$prefix$name/")
            }
        }
    }

    fun id(path: String): Int = paths[path] ?: throw PatchException("missing_file:$path")

    fun read(path: String): ByteArray = read(id(path))

    fun read(id: Int): ByteArray {
        val b = ByteArray((ends[id] - starts[id]).toInt())
        f.seek(starts[id]); f.readFully(b); return b
    }

    fun write(path: String, data: ByteArray) {
        check(writable)
        val id = id(path)
        val slot = ends[id] - starts[id]
        val start = if (data.size <= slot) starts[id] else {
            val s = (usedEnd + 0x1FF) and 0x1FFL.inv()
            if (s + data.size > f.length()) throw PatchException("rom_full")
            s
        }
        f.seek(start); f.write(data)
        starts[id] = start; ends[id] = start + data.size
        usedEnd = maxOf(usedEnd, ends[id])
        val e = ByteArray(8); put32(e, 0, start); put32(e, 4, ends[id])
        f.seek(fatOff + id * 8L); f.write(e)
    }

    /** Updates the used-size field and the header CRC after writes. */
    fun finish() {
        check(writable)
        put32(header, 0x80, usedEnd)
        val crc = crc16(header, 0, 0x15E)
        header[0x15E] = crc.toByte(); header[0x15F] = (crc shr 8).toByte()
        f.seek(0); f.write(header, 0, 0x160)
    }

    companion object {
        fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24))

        fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        fun put32(b: ByteArray, o: Int, v: Long) {
            b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
        }

        fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }

        fun crc16(b: ByteArray, off: Int, len: Int): Int {
            var c = 0xFFFF
            for (i in off until off + len) {
                c = c xor (b[i].toInt() and 0xFF)
                repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
            }
            return c
        }
    }
}

/** NARC archive: file list plus the untouched name table. */
class Narc(data: ByteArray) {
    val files = ArrayList<ByteArray>()
    private val btnf: ByteArray

    init {
        require(String(data, 0, 4, Charsets.US_ASCII) == "NARC") { "not a NARC" }
        val hdrSize = NdsRom.u16(data, 0xC)
        var p = hdrSize
        require(String(data, p, 4, Charsets.US_ASCII) == "BTAF")
        val btafSize = NdsRom.u32(data, p + 4).toInt()
        val n = NdsRom.u32(data, p + 8).toInt()
        val ranges = (0 until n).map { NdsRom.u32(data, p + 12 + it * 8).toInt() to NdsRom.u32(data, p + 16 + it * 8).toInt() }
        p += btafSize
        require(String(data, p, 4, Charsets.US_ASCII) == "BTNF")
        val btnfSize = NdsRom.u32(data, p + 4).toInt()
        btnf = data.copyOfRange(p, p + btnfSize)
        p += btnfSize
        require(String(data, p, 4, Charsets.US_ASCII) == "GMIF")
        val base = p + 8
        for ((s, e) in ranges) files += data.copyOfRange(base + s, base + e)
    }

    fun save(): ByteArray {
        val n = files.size
        val btafSize = 12 + 8 * n
        var gmifData = 0
        val offs = IntArray(n)
        for (i in 0 until n) { offs[i] = gmifData; gmifData += files[i].size; gmifData = (gmifData + 3) and 3.inv() }
        val total = 0x10 + btafSize + btnf.size + 8 + gmifData
        val o = ByteArray(total)
        "NARC".toByteArray().copyInto(o, 0)
        NdsRom.put16(o, 4, 0xFFFE); NdsRom.put16(o, 6, 0x0100)
        NdsRom.put32(o, 8, total.toLong()); NdsRom.put16(o, 0xC, 0x10); NdsRom.put16(o, 0xE, 3)
        var p = 0x10
        "BTAF".toByteArray().copyInto(o, p); NdsRom.put32(o, p + 4, btafSize.toLong()); NdsRom.put32(o, p + 8, n.toLong())
        for (i in 0 until n) {
            NdsRom.put32(o, p + 12 + i * 8, offs[i].toLong())
            NdsRom.put32(o, p + 16 + i * 8, (offs[i] + files[i].size).toLong())
        }
        p += btafSize
        btnf.copyInto(o, p); p += btnf.size
        "GMIF".toByteArray().copyInto(o, p); NdsRom.put32(o, p + 4, (8 + gmifData).toLong())
        p += 8
        for (i in 0 until n) files[i].copyInto(o, p + offs[i]) // padding stays 0, as in ndspy
        return o
    }
}
