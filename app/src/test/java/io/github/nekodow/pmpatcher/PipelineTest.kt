package io.github.nekodow.pmpatcher

import io.github.nekodow.pmpatcher.core.Pipeline
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real pipeline on local ROMs (never committed). Pass -Dpm.dir=<folder containing
 * the US ROM, the FR ROM and the .xdelta> and -Dpm.out=<output folder>.
 */
class PipelineTest {
    private val dir = File(System.getProperty("pm.dir") ?: "")
    private val outDir = File(System.getProperty("pm.out") ?: "")

    private fun listener() = object : Pipeline.Listener {
        override fun step(step: Pipeline.Step) = println("step $step")
        override fun progress(fraction: Float) {}
        override fun log(msg: String) = println(msg)
    }

    private fun run(patchName: String, french: Boolean, outName: String) {
        assumeTrue(dir.isDirectory)
        val assets = File("src/main/assets")
        val t0 = System.currentTimeMillis()
        val r = Pipeline.run(
            Pipeline.Options(
                usRom = File(dir, "Pokemon - Platinum Version (USA) (Rev 1).nds"),
                patch = File(dir, patchName).readBytes(),
                frRom = if (french) File(dir, "Pokemon - Version Platine (France).nds") else null,
                frenchGraphics = true,
                charmap = File(assets, "charmap.txt").readText(),
                translationsJson = File(assets, "trad_fr.json").readText(),
                output = File(outDir, outName),
            ),
            listener(),
        )
        val s = r.stats
        if (s != null) println("filesFr=${s.filesFr} fr=${s.stringsFr} moved=${s.stringsMoved} translated=${s.translated} english=${s.english} gfx=${s.gfx}")
        println("$outName in ${System.currentTimeMillis() - t0} ms")
    }

    @Test fun patchOnly() = run("PlatinumMultiplayerV0.4.4.xdelta", false, "kt_us.nds")
    @Test fun patchFrench() = run("PlatinumMultiplayerV0.4.4.xdelta", true, "kt_fr.nds")
    @Test fun patchVisualCameraFrench() = run("PlatinumMultiplayerV0.4.4-VisualPlusCamera.xdelta", true, "kt_fr_vpc.nds")
}
