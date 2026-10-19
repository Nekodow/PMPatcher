package io.github.nekodow.pmpatcher.core

import java.io.RandomAccessFile

/**
 * Visual+ extras (MinT ChoK, backdrops by Young), applied the same way as ProjectPMSetup does
 * since 0.4.7: replace the battle background NARC, and swap the battle camera table.
 */
object VisualPlus {
    const val BATTLE_BG = "battle/graphic/pl_batt_bg.narc"

    fun applyBackgrounds(rom: NdsRom, narc: ByteArray) {
        val old = rom.read(BATTLE_BG)
        if (old.contentEquals(narc)) return
        if (Narc(old).files.size != Narc(narc).files.size) throw PatchException("visual_bg_mismatch")
        rom.write(BATTLE_BG, narc)
    }

    /** Finds the vanilla camera table (must appear once) and replaces it. No-op if already applied. */
    fun applyCamera(f: RandomAccessFile, vanilla: ByteArray, visual: ByteArray) {
        require(vanilla.size == visual.size)
        val van = find(f, vanilla)
        if (van.isEmpty()) {
            if (find(f, visual).size == 1) return
            throw PatchException("visual_cam_not_found")
        }
        if (van.size > 1) throw PatchException("visual_cam_not_found")
        f.seek(van[0]); f.write(visual)
    }

    private fun find(f: RandomAccessFile, needle: ByteArray): List<Long> {
        val hits = ArrayList<Long>()
        val chunk = 4 shl 20
        val buf = ByteArray(chunk + needle.size)
        var pos = 0L
        val len = f.length()
        val first = needle[0]
        while (pos < len) {
            val n = minOf((len - pos).toInt(), buf.size)
            f.seek(pos); f.readFully(buf, 0, n)
            val limit = minOf(n - needle.size, chunk - 1)
            var i = 0
            while (i <= limit) {
                if (buf[i] == first) {
                    var k = 1
                    while (k < needle.size && buf[i + k] == needle[k]) k++
                    if (k == needle.size) hits += pos + i
                }
                i++
            }
            pos += chunk
        }
        return hits
    }
}
