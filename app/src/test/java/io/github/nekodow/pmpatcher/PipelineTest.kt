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

    private fun run(patchName: String, french: Boolean, outName: String, visual: Boolean = false) {
        assumeTrue(dir.isDirectory)
        val assets = File("src/main/assets")
        val t0 = System.currentTimeMillis()
        val r = Pipeline.run(
            Pipeline.Options(
                usRom = File(dir, "Pokemon - Platinum Version (USA) (Rev 1).nds"),
                patch = File(dir, patchName).readBytes(),
                frRom = if (french) File(dir, "Pokemon - Version Platine (France).nds") else null,
                frenchGraphics = true,
                visualBackgrounds = if (visual) File(assets, "visualplus/battlebg.narc").readBytes() else null,
                visualCamera = if (visual) File(assets, "visualplus/cam_vanilla.bin").readBytes() to File(assets, "visualplus/cam_visual.bin").readBytes() else null,
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

    @Test fun patch047() = run("PlatinumMultiplayerV0.4.7.xdelta", false, "kt047.nds")
    @Test fun patch047VisualFrench() = run("PlatinumMultiplayerV0.4.7.xdelta", true, "kt047_vpc_fr.nds", visual = true)
    @Test fun patch044Visual() = run("PlatinumMultiplayerV0.4.4.xdelta", false, "kt044_vpc.nds", visual = true)
}
