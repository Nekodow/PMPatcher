package io.github.nekodow.pmpatcher.core

/** Gen 4 message files: per-string encrypted UTF-16-like code units. */
object Msg {
    class File(val strings: List<IntArray>, val seed: Int)

    fun decode(data: ByteArray): File {
        val n = NdsRom.u16(data, 0); val seed = NdsRom.u16(data, 2)
        val out = ArrayList<IntArray>(n)
        for (i in 0 until n) {
            val k = (seed * 0x2FD * (i + 1)) and 0xFFFF
            val kf = (k.toLong() or (k.toLong() shl 16))
            val off = (NdsRom.u32(data, 4 + 8 * i) xor kf).toInt()
            val len = (NdsRom.u32(data, 8 + 8 * i) xor kf).toInt()
            var key = (0x91BD3 * (i + 1)) and 0xFFFF
            val s = IntArray(len)
            for (j in 0 until len) {
                s[j] = NdsRom.u16(data, off + 2 * j) xor key
                key = (key + 0x493D) and 0xFFFF
            }
            out += s
        }
        return File(out, seed)
    }

    fun encode(strings: List<IntArray>, seed: Int): ByteArray {
        val n = strings.size
        val total = 4 + 8 * n + strings.sumOf { it.size * 2 }
        val o = ByteArray(total)
        NdsRom.put16(o, 0, n); NdsRom.put16(o, 2, seed)
        var off = 4 + 8 * n
        for ((i, s) in strings.withIndex()) {
            val k = (seed * 0x2FD * (i + 1)) and 0xFFFF
            val kf = (k.toLong() or (k.toLong() shl 16))
            NdsRom.put32(o, 4 + 8 * i, off.toLong() xor kf)
            NdsRom.put32(o, 8 + 8 * i, s.size.toLong() xor kf)
            var key = (0x91BD3 * (i + 1)) and 0xFFFF
            for (c in s) {
                NdsRom.put16(o, off, c xor key); off += 2
                key = (key + 0x493D) and 0xFFFF
            }
        }
        return o
    }
}

/** Text <-> code units, using the pret/pokeplatinum charmap and {NL}/{SCROLL}/{PAGE}/{XXXX a b} tokens. */
class Charset(charmap: String) {
    private val dec = HashMap<Int, String>()
    private val enc = HashMap<String, Int>()

    init {
        for (raw in charmap.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("//") || !line.contains('=')) continue
            val eq = line.indexOf('=')
            val code = line.substring(0, eq).toIntOrNull(16) ?: continue
            val ch = line.substring(eq + 1)
            if (ch.startsWith("{") || ch.codePointCount(0, ch.length) != 1) continue
            dec.putIfAbsent(code, ch)
            val prev = enc[ch]
            if (prev == null || (code in 0x121 until 0x400 && prev !in 0x121 until 0x400)) enc[ch] = code
        }
    }

    fun toText(s: IntArray): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.size) {
            val c = s[i]
            if (c == 0xFFFF) break
            if (c == 0xFFFE) {
                val cmd = s[i + 1]; val n = s[i + 2]
                sb.append('{').append("%04X".format(cmd))
                for (k in 0 until n) sb.append(' ').append(s[i + 3 + k])
                sb.append('}'); i += 3 + n; continue
            }
            when {
                c == 0xE000 -> sb.append("{NL}")
                c == 0x25BC -> sb.append("{SCROLL}")
                c == 0x25BD -> sb.append("{PAGE}")
                c >= 0x121 && dec.containsKey(c) -> sb.append(dec[c])
                else -> sb.append("{#").append("%04X".format(c)).append('}')
            }
            i++
        }
        return sb.toString()
    }

    fun fromText(t: String): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i < t.length) {
            if (t[i] == '{') {
                val end = t.indexOf('}', i)
                val tok = t.substring(i + 1, end)
                when {
                    tok == "NL" -> out += 0xE000
                    tok == "SCROLL" -> out += 0x25BC
                    tok == "PAGE" -> out += 0x25BD
                    tok.startsWith("#") -> out += tok.substring(1).toInt(16)
                    else -> {
                        val parts = tok.split(' ')
                        out += 0xFFFE; out += parts[0].toInt(16); out += parts.size - 1
                        for (k in 1 until parts.size) out += parts[k].toInt()
                    }
                }
                i = end + 1
            } else {
                val cp = t.codePointAt(i)
                val ch = String(Character.toChars(cp))
                out += enc[ch] ?: throw PatchException("bad_char:$ch")
                i += Character.charCount(cp)
            }
        }
        out += 0xFFFF
        return out.toIntArray()
    }
}
