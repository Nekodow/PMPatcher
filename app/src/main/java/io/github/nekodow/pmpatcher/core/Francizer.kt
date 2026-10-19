package io.github.nekodow.pmpatcher.core

import org.json.JSONObject

/**
 * Port of franciser.py: turns a Project PM ROM (built from the US ROM) into French using
 * the player's own French Platinum ROM plus the bundled translations of the mod's new text.
 */
class Francizer(
    private val us: NdsRom,
    private val fr: NdsRom,
    private val pm: NdsRom,
    private val charset: Charset,
    private val translations: Map<String, String?>,
    private val log: (String) -> Unit = {},
) {
    class Stats {
        var filesFr = 0; var stringsFr = 0; var stringsMoved = 0; var translated = 0; var english = 0; var gfx = 0
    }

    fun run(withGraphics: Boolean): Stats {
        val st = Stats()
        val nu = Narc(us.read(MSG)); val nf = Narc(fr.read(MSG)); val np = Narc(pm.read(MSG))
        if (nu.files.size != nf.files.size || nu.files.size != np.files.size) throw PatchException("msg_count")

        // English text -> French replacement (null = keep the official French string at that spot)
        val tr = HashMap<List<Int>, IntArray?>()
        for ((en, value) in translations) tr[charset.fromText(en).toList()] = value?.let { charset.fromText(it) }

        for (i in np.files.indices) {
            val a = nu.files[i]; val b = nf.files[i]; val c = np.files[i]
            if (a.contentEquals(c)) { np.files[i] = b; st.filesFr++; continue }
            val su = Msg.decode(a).strings; val sf = Msg.decode(b).strings
            val pmFile = Msg.decode(c)
            if (su.size != sf.size) continue
            val index = HashMap<List<Int>, MutableList<Int>>()
            su.forEachIndexed { j, s -> index.getOrPut(s.toList()) { ArrayList() }.add(j) }
            val res = ArrayList<IntArray>(pmFile.strings.size)
            pmFile.strings.forEachIndexed { j, s ->
                val key = s.toList()
                when {
                    j < su.size && su[j].contentEquals(s) -> { res += sf[j]; st.stringsFr++ }
                    index.containsKey(key) -> {
                        val k = index[key]!!.minBy { kotlin.math.abs(it - j) }
                        res += sf[k]; st.stringsMoved++
                    }
                    tr.containsKey(key) -> { res += tr[key] ?: (if (j < sf.size) sf[j] else s); st.translated++ }
                    else -> { res += s; st.english++ }
                }
            }
            np.files[i] = Msg.encode(res, pmFile.seed)
        }
        pm.write(MSG, np.save())

        if (withGraphics) {
            for ((u, f) in GFX) {
                val a = us.read(u); val c = pm.read(u)
                if (!a.contentEquals(c)) { log("keep: $u"); continue }
                val b = fr.read(f)
                if (Narc(a).files.size != Narc(b).files.size) { log("keep: $u"); continue }
                pm.write(u, b); st.gfx++
            }
        }
        return st
    }

    companion object {
        const val MSG = "msgdata/pl_msg.narc"

        fun parseTranslations(json: String): Map<String, String?> {
            val o = JSONObject(json); val m = LinkedHashMap<String, String?>()
            for (k in o.keys()) m[k] = if (o.isNull(k)) null else o.getString(k)
            return m
        }

        val GFX = listOf(
            "graphic/bag_gra.narc" to "resource/fra/bag/bag_gra.narc",
            "graphic/pl_bag_gra.narc" to "resource/fra/bag/pl_bag_gra.narc",
            "battle/graphic/b_plist_gra.narc" to "resource/fra/b_plist/b_plist_gra.narc",
            "battle/graphic/pl_b_plist_gra.narc" to "resource/fra/b_plist/pl_b_plist_gra.narc",
            "resource/eng/batt_rec/batt_rec_gra.narc" to "resource/fra/batt_rec/batt_rec_gra.narc",
            "battle/graphic/batt_obj.narc" to "resource/fra/battle_graphic/batt_obj.narc",
            "battle/graphic/pl_batt_obj.narc" to "resource/fra/battle_graphic/pl_batt_obj.narc",
            "graphic/box.narc" to "resource/fra/box/box.narc",
            "contest/graphic/contest_bg.narc" to "resource/fra/contest_graphic/contest_bg.narc",
            "contest/graphic/contest_obj.narc" to "resource/fra/contest_graphic/contest_obj.narc",
            "graphic/imageclip.narc" to "resource/fra/dress_up_graphic/imageclip.narc",
            "resource/eng/frontier_graphic/frontier_bg.narc" to "resource/fra/frontier_graphic/frontier_bg.narc",
            "resource/eng/frontier_graphic/frontier_obj.narc" to "resource/fra/frontier_graphic/frontier_obj.narc",
            "graphic/mystery.narc" to "resource/fra/mysterycard/mystery.narc",
            "graphic/nutmixer.narc" to "resource/fra/nutmixer/nutmixer.narc",
            "demo/title/op_demo.narc" to "resource/fra/opening_demo/op_demo.narc",
            "graphic/pl_pst_gra.narc" to "resource/fra/p_status/pl_pst_gra.narc",
            "graphic/pst_gra.narc" to "resource/fra/p_status/pst_gra.narc",
            "resource/eng/pms_aikotoba/pms_aikotoba.narc" to "resource/fra/pms_aikotoba/pms_aikotoba.narc",
            "graphic/pl_plist_gra.narc" to "resource/fra/pokelist/pl_plist_gra.narc",
            "graphic/plist_gra.narc" to "resource/fra/pokelist/plist_gra.narc",
            "graphic/poketch.narc" to "resource/fra/poketch/poketch.narc",
            "resource/eng/scratch/scratch.narc" to "resource/fra/scratch/scratch.narc",
            "data/slot.narc" to "resource/fra/slot/slot.narc",
            "demo/title/titledemo.narc" to "resource/fra/title/titledemo.narc",
            "graphic/touch_subwindow.narc" to "resource/fra/touch_subwindow/touch_subwindow.narc",
            "graphic/trainer_case.narc" to "resource/fra/trainer_case/trainer_case.narc",
            "battle/graphic/vs_demo_gra.narc" to "resource/fra/vs_demo/vs_demo_gra.narc",
            "resource/eng/zukan/zukan.narc" to "resource/fra/zukan/zukan.narc",
        )
    }
}
