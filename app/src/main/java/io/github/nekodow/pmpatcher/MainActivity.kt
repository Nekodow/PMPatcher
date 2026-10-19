package io.github.nekodow.pmpatcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.nekodow.pmpatcher.core.PatchException
import io.github.nekodow.pmpatcher.core.Pipeline
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var btnUs: Button
    private lateinit var btnPatch: Button
    private lateinit var btnFr: Button
    private lateinit var btnCreate: Button
    private lateinit var statusUs: TextView
    private lateinit var statusPatch: TextView
    private lateinit var statusFr: TextView
    private lateinit var statusMain: TextView
    private lateinit var switchFr: Switch
    private lateinit var checkGfx: CheckBox
    private lateinit var frBlock: View
    private lateinit var progress: ProgressBar
    private lateinit var patchGroup: RadioGroup

    private val usFile by lazy { File(cacheDir, "us.nds") }
    private val frFile by lazy { File(cacheDir, "fr.nds") }
    private val outFile by lazy { File(cacheDir, "out.nds") }

    private var usOk = false
    private var frOk = false
    private var customPatch: ByteArray? = null
    private var customPatchName = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnUs = findViewById(R.id.btnUs); btnPatch = findViewById(R.id.btnPatch)
        btnFr = findViewById(R.id.btnFr); btnCreate = findViewById(R.id.btnCreate)
        statusUs = findViewById(R.id.statusUs); statusPatch = findViewById(R.id.statusPatch)
        statusFr = findViewById(R.id.statusFr); statusMain = findViewById(R.id.statusMain)
        switchFr = findViewById(R.id.switchFr); checkGfx = findViewById(R.id.checkGfx)
        frBlock = findViewById(R.id.frBlock); progress = findViewById(R.id.progress)
        patchGroup = findViewById(R.id.patchGroup)

        // A fresh start: forget files copied by a previous session.
        usFile.delete(); frFile.delete(); outFile.delete()

        btnUs.setOnClickListener { pick(REQ_US) }
        btnPatch.setOnClickListener { pick(REQ_PATCH) }
        btnFr.setOnClickListener { pick(REQ_FR) }
        switchFr.setOnCheckedChangeListener { _, on ->
            frBlock.visibility = if (on) View.VISIBLE else View.GONE
            refresh()
        }
        patchGroup.setOnCheckedChangeListener { _, id ->
            val custom = id == R.id.patchCustom
            findViewById<View>(R.id.customPatchRow).visibility = if (custom) View.VISIBLE else View.GONE
            findViewById<View>(R.id.customPatchHint).visibility = if (custom) View.VISIBLE else View.GONE
            refresh()
        }
        btnCreate.setOnClickListener { askOutput() }
        refresh()
    }

    private fun pick(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, req)
    }

    private fun askOutput() {
        val name0 = selectedPatchName()
        val base = when {
            name0.contains("Camera", true) -> "ProjectPM_VisualPlusCamera"
            name0.contains("Visual", true) -> "ProjectPM_VisualPlus"
            else -> "ProjectPM"
        }
        val name = base + (if (switchFr.isChecked) "_FR" else "") + ".nds"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, REQ_OUT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_US -> loadRom(uri, usFile, statusUs, isUs = true)
            REQ_FR -> loadRom(uri, frFile, statusFr, isUs = false)
            REQ_PATCH -> loadPatch(uri)
            REQ_OUT -> create(uri)
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "?"

    /** Copies a ROM into the cache (needed for random access) and checks it. */
    private fun loadRom(uri: Uri, dest: File, status: TextView, isUs: Boolean) {
        if (isUs) usOk = false else frOk = false
        setBusy(true); refresh()
        status.text = getString(R.string.copying, 0)
        thread {
            val msg: String
            var ok = false
            try {
                copyIn(uri, dest) { pct -> runOnUiThread { status.text = getString(R.string.copying, pct) } }
                runOnUiThread { status.text = getString(R.string.checking) }
                val code = Pipeline.gameCode(dest)
                msg = if (isUs) {
                    when {
                        code != "CPUE" -> "✗ " + getString(R.string.err_us_wrong_game, code)
                        Pipeline.sha1(dest) != Pipeline.US_SHA1 -> "✗ " + getString(R.string.err_us_not_clean)
                        else -> { ok = true; getString(R.string.us_ok) }
                    }
                } else {
                    if (code != "CPUF") "✗ " + getString(R.string.err_fr_wrong_game, code)
                    else { ok = true; getString(R.string.fr_ok) }
                }
            } catch (e: IOException) {
                dest.delete()
                runOnUiThread { status.text = "✗ " + errorText(e); setBusy(false); refresh() }
                return@thread
            }
            if (!ok) dest.delete()
            runOnUiThread {
                status.text = displayName(uri) + "\n" + msg
                if (isUs) usOk = ok else frOk = ok
                setBusy(false); refresh()
            }
        }
    }

    private fun copyIn(uri: Uri, dest: File, progress: (Int) -> Unit) {
        val total = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
        contentResolver.openInputStream(uri)!!.use { inp ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 20); var done = 0L; var last = -1
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        }
    }

    /** Asset name of the selected built-in patch, or null for a custom file. */
    private fun builtInAsset(): String? = when (patchGroup.checkedRadioButtonId) {
        R.id.patchNormal -> "PlatinumMultiplayerV0.4.4.xdelta"
        R.id.patchVisual -> "PlatinumMultiplayerV0.4.4-VisualPlus.xdelta"
        R.id.patchCamera -> "PlatinumMultiplayerV0.4.4-VisualPlusCamera.xdelta"
        else -> null
    }

    private fun selectedPatchName(): String = builtInAsset() ?: customPatchName

    private fun selectedPatch(): ByteArray? =
        builtInAsset()?.let { a -> assets.open("patches/$a").use { it.readBytes() } } ?: customPatch

    private fun loadPatch(uri: Uri) {
        customPatch = null; refresh()
        val name = displayName(uri)
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val magic = bytes.size > 4 && bytes[0] == 0xD6.toByte() && bytes[1] == 0xC3.toByte() && bytes[2] == 0xC4.toByte()
            if (magic) {
                customPatch = bytes; customPatchName = name
                statusPatch.text = getString(R.string.patch_ok, name)
            } else {
                statusPatch.text = name + "\n✗ " + getString(R.string.err_not_vcdiff)
            }
        } catch (e: Exception) {
            statusPatch.text = "✗ " + errorText(e)
        }
        refresh()
    }

    private fun refresh() {
        val patchReady = builtInAsset() != null || customPatch != null
        val ready = usOk && patchReady && (!switchFr.isChecked || frOk)
        btnCreate.isEnabled = ready && !busy
        btnUs.isEnabled = !busy; btnPatch.isEnabled = !busy; btnFr.isEnabled = !busy
        switchFr.isEnabled = !busy; checkGfx.isEnabled = !busy
        for (i in 0 until patchGroup.childCount) patchGroup.getChildAt(i).isEnabled = !busy
    }

    private fun setBusy(b: Boolean) {
        busy = b
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun create(target: Uri) {
        val p = selectedPatch() ?: return
        val french = switchFr.isChecked
        val gfx = checkGfx.isChecked
        setBusy(true); refresh()
        progress.visibility = View.VISIBLE; progress.progress = 0
        statusMain.text = getString(R.string.busy)
        val charmap = assets.open("charmap.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val trad = assets.open("trad_fr.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val name = displayName(target)
        thread {
            val listener = object : Pipeline.Listener {
                override fun step(step: Pipeline.Step) = runOnUiThread {
                    statusMain.text = when (step) {
                        Pipeline.Step.VERIFY -> getString(R.string.step_verify)
                        Pipeline.Step.PATCH -> getString(R.string.step_patching)
                        Pipeline.Step.FRENCH -> getString(R.string.step_french)
                        Pipeline.Step.DONE -> getString(R.string.step_saving)
                    } + "\n" + getString(R.string.busy)
                }
                override fun progress(fraction: Float) = runOnUiThread { progress.progress = (fraction * 1000).toInt() }
                override fun log(msg: String) {}
            }
            try {
                outFile.delete()
                val r = Pipeline.run(
                    Pipeline.Options(usFile, p, if (french) frFile else null, gfx, charmap, trad, outFile),
                    listener,
                )
                contentResolver.openOutputStream(target, "wt")!!.use { out ->
                    outFile.inputStream().use { it.copyTo(out, 1 shl 20) }
                }
                outFile.delete()
                val s = r.stats
                runOnUiThread {
                    progress.progress = 1000
                    statusMain.text = getString(R.string.done, name) +
                        (if (s != null) "\n" + getString(R.string.done_fr_stats, s.translated, s.gfx) else "")
                    setBusy(false); refresh()
                    findViewById<ScrollView>(R.id.scroll).post { findViewById<ScrollView>(R.id.scroll).fullScroll(View.FOCUS_DOWN) }
                }
            } catch (e: Throwable) {
                outFile.delete()
                runOnUiThread {
                    progress.visibility = View.GONE
                    statusMain.text = "✗ " + errorText(e)
                    setBusy(false); refresh()
                }
            }
        }
    }

    private fun errorText(e: Throwable): String = when {
        e is PatchException && e.code == "us_not_clean" -> getString(R.string.err_us_not_clean)
        e is PatchException && e.code == "checksum" -> getString(R.string.err_checksum)
        e is PatchException && e.code == "not_vcdiff" -> getString(R.string.err_not_vcdiff)
        e is PatchException && e.code == "rom_full" -> getString(R.string.err_rom_full)
        e is IOException && (e.message ?: "").contains("ENOSPC") -> getString(R.string.err_storage)
        e is OutOfMemoryError -> getString(R.string.err_generic, "out of memory")
        else -> getString(R.string.err_generic, e.message ?: e.javaClass.simpleName)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !busy) { usFile.delete(); frFile.delete(); outFile.delete() }
    }

    companion object {
        const val REQ_US = 1; const val REQ_PATCH = 2; const val REQ_FR = 3; const val REQ_OUT = 4
    }
}
