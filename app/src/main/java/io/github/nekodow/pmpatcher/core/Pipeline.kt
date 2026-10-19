package io.github.nekodow.pmpatcher.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Full job: verify ROMs, apply the xdelta patch, optionally make it French. Pure JVM, no Android. */
object Pipeline {
    const val US_SHA1 = "0862ec35b24de5c7e2dcb88c9eea0873110d755c" // Platinum (USA) (Rev 1), as checked by Project PM Setup
    const val ROM_SIZE = 128L * 1024 * 1024

    enum class Step { VERIFY, PATCH, VISUAL, FRENCH, DONE }

    interface Listener {
        fun step(step: Step)
        fun progress(fraction: Float)
        fun log(msg: String)
    }

    class Options(
        val usRom: File,
        val patch: ByteArray,
        val frRom: File?,
        val frenchGraphics: Boolean,
        val visualBackgrounds: ByteArray?,
        val visualCamera: Pair<ByteArray, ByteArray>?,
        val charmap: String,
        val translationsJson: String,
        val output: File,
    )

    class Result(val stats: Francizer.Stats?)

    fun sha1(f: File, progress: (Float) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(1 shl 20); var done = 0L
        f.inputStream().use { inp ->
            while (true) {
                val n = inp.read(buf); if (n < 0) break
                md.update(buf, 0, n); done += n; progress(done.toFloat() / f.length())
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun gameCode(f: File): String = RandomAccessFile(f, "r").use { raf ->
        val b = ByteArray(4); raf.seek(0xC); raf.readFully(b); String(b, Charsets.US_ASCII)
    }

    /** Throws PatchException("us_wrong_game"/"us_not_clean"/"fr_wrong_game"). */
    fun verify(o: Options, l: Listener) {
        l.step(Step.VERIFY)
        if (gameCode(o.usRom) != "CPUE") throw PatchException("us_wrong_game")
        if (sha1(o.usRom) { l.progress(it) } != US_SHA1) throw PatchException("us_not_clean")
        if (o.frRom != null && gameCode(o.frRom) != "CPUF") throw PatchException("fr_wrong_game")
    }

    fun run(o: Options, l: Listener): Result {
        verify(o, l)
        l.step(Step.PATCH)
        RandomAccessFile(o.usRom, "r").use { src ->
            val map = src.channel.map(FileChannel.MapMode.READ_ONLY, 0, src.length())
            RandomAccessFile(o.output, "rw").use { out ->
                Vcdiff(map, o.patch, out) { l.progress(it) }.decode()
            }
        }
        if (gameCode(o.output) != "CPUE") throw PatchException("bad_output")
        var stats: Francizer.Stats? = null
        if (o.frRom != null || o.visualBackgrounds != null || o.visualCamera != null) {
            RandomAccessFile(o.usRom, "r").use { usF ->
                RandomAccessFile(o.output, "rw").use { pmF ->
                    if (pmF.length() < ROM_SIZE) pmF.setLength(ROM_SIZE)
                    val pm = NdsRom(pmF, writable = true)
                    if (o.visualBackgrounds != null || o.visualCamera != null) {
                        l.step(Step.VISUAL); l.progress(0f)
                        o.visualBackgrounds?.let { VisualPlus.applyBackgrounds(pm, it) }
                        o.visualCamera?.let { (van, vis) -> VisualPlus.applyCamera(pmF, van, vis) }
                    }
                    if (o.frRom != null) {
                        l.step(Step.FRENCH); l.progress(0f)
                        RandomAccessFile(o.frRom, "r").use { frF ->
                            stats = Francizer(
                                NdsRom(usF), NdsRom(frF), pm,
                                Charset(o.charmap), Francizer.parseTranslations(o.translationsJson),
                            ) { l.log(it) }.run(o.frenchGraphics)
                        }
                    }
                    pm.finish()
                }
            }
        }
        l.step(Step.DONE)
        return Result(stats)
    }
}
