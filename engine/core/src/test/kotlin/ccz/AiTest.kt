package ccz

import ccz.battle.Ai
import ccz.battle.BattleSession
import ccz.battle.Units
import ccz.data.Characters
import ccz.data.ClassTables
import ccz.data.GameData
import ccz.io.GameFiles
import ccz.script.ScriptEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 方法 1：AI 行動評估（物理攻擊得分 0x438914、地形價值 0x452E18、路徑 0x437E23、模式 4 搜尋 0x438484 + 0x43A55E）跟原版比對。
 * 標準答案：python3 tools/re/gen_ai_vectors.py <遊戲目錄>（輸出 out/ai_vectors.json，不進 git）。
 */
class AiTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val vectors = File(System.getProperty("ccz.aiVectors") ?: "../../out/ai_vectors.json")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16) }

    @Test
    fun aiMatch() {
        val f = files ?: return
        if (!vectors.exists()) { println("沒有 ${vectors.path}，略過"); return }
        val data = GameData(f)
        val tables = ClassTables(f)
        val stages = JSONArray(vectors.readText())
        val bad = ArrayList<String>()
        var tiles = 0
        var full = 0
        var skipped = 0
        var lists = 0
        var acts = 0
        for (k in 0 until stages.length()) {
            val st = stages.getJSONObject(k)
            val stage = st.getInt("stage")
            val s = BattleSession(f, data, tables, stage)
            while (true) { val e = s.next() ?: break; if (e is ScriptEvent.Choice || e is ScriptEvent.YesNo) s.answer(1) }
            val bf = s.field!!
            val mem = s.mem
            val n = bf.width * bf.height
            val baseChars = mem.bytes(mem.u32(Characters.PTR), Characters.COUNT * Characters.SIZE)
            val groups = st.getJSONArray("groups")
            for (g in 0 until groups.length()) {
                val grp = groups.getJSONObject(g)
                baseChars.forEachIndexed { i, b -> mem.setU8(mem.u32(Characters.PTR) + i, b.toInt() and 0xff) }
                val chars = grp.getJSONObject("chars")
                for (key in chars.keys()) {
                    val a = Characters.addr(mem, key.toInt())
                    hex(chars.getString(key)).forEachIndexed { i, b -> mem.setU8(a + i, b) }
                }
                val units = hex(grp.getString("units"))
                val occupant = hex(grp.getString("occupant"))
                val tests = grp.getJSONArray("tests")
                for (t in 0 until tests.length()) {
                    val x = tests.getJSONObject(t)
                    val slot = x.getInt("unit")
                    val u = Units.unit(slot)
                    val where = "S_%03d 組 $g 格 $slot".format(stage)
                    val marks = hex(x.getString("marks"))
                    val weights = hex(x.getString("weights"))
                    fun setup() {
                        units.forEachIndexed { i, b -> mem.setU8(Units.UNITS + i, b) }
                        occupant.forEachIndexed { i, b -> bf.occupant[i] = b.toByte() }
                        for (i in 0 until n) { bf.scratch[i] = marks[i].toByte(); bf.scratch[0x640 + i] = weights[i].toByte() }
                        mem.setU16(Ai.AVOID, x.getLong("avoid"))
                        mem.setU32(0x48ECD4L, x.getLong("seed"))
                        for (i in 0 until 0x80) mem.setU8(0x500E49L + i, 0)
                        mem.setU8(Ai.PATH_LENGTH, 0)
                        bf.ai.path.fill(0, 0, 0x40)
                        val rolls = x.optJSONObject("rolls")
                        if (rolls != null) for (key in rolls.keys()) hex(rolls.getString(key)).forEachIndexed { i, b -> mem.setU8(key.toLong() + i, b) }
                    }
                    setup()
                    val mp = bf.search.movePoints(u)
                    if (mp != x.getInt("mp")) { bad += "$where：移動力 原版 ${x.getInt("mp")} 引擎 $mp"; continue }
                    bf.search.search(u, mp, 0, 0xFFFF, 1)
                    val hasList = x.has("slist")
                    if (hasList) {
                        bf.ai.buildStrategies(u)
                        val got = StringBuilder()
                        var q = Ai.STRATEGIES
                        while (mem.u8(q) != 0xFF) got.append("%02x".format(mem.u8(q++)))
                        if (got.toString() != x.getString("slist")) { bad += "$where：策略清單 ${x.getString("slist")}/$got"; continue }
                        lists++
                    }
                    val tl = x.getJSONArray("tiles")
                    for (i in 0 until tl.length()) {
                        val y = tl.getJSONObject(i)
                        if (!y.getBoolean("ok")) continue
                        tiles++
                        val p = y.getInt("pos")
                        val score = bf.ai.physicalScore(u, p)
                        val tv = bf.ai.terrainValue(u, p)
                        val sv = if (hasList) bf.ai.strategyScore(u, p, 0) else 0L
                        val sb = if (hasList) hex(y.getString("sbest")) else emptyList()
                        val plen = mem.u8(Ai.PATH_LENGTH)
                        val path = (0 until plen).joinToString("") { "%02x".format(bf.ai.path[it].toInt() and 0xff) }
                        val spread = (0 until 0x80).joinToString("") { "%02x".format(mem.u8(0x500E49L + it)) }
                        val diffs = listOfNotNull(
                            "得分 ${y.getLong("eax")}/$score".takeIf { y.getLong("eax") != score },
                            "目標 ${y.getInt("target")}/${mem.u8(Ai.PHYS_TARGET)}".takeIf { y.getInt("target") != mem.u8(Ai.PHYS_TARGET) },
                            "路徑長 ${y.getInt("plen")}/$plen".takeIf { y.getInt("plen") != plen },
                            "路徑 ${y.getString("path").take(2 * plen)}/$path".takeIf { y.getString("path").take(2 * plen) != path },
                            "擴散記錄".takeIf { y.getString("spread") != spread },
                            "地形價值 ${y.getInt("terrain")}/$tv".takeIf { y.getInt("terrain") != tv },
                            "策略得分 ${y.optLong("strat")}/$sv".takeIf { hasList && y.optLong("strat") != sv },
                            "策略目標 ${sb.getOrNull(0)}/${mem.u8(Ai.STRAT_TARGET)}".takeIf { hasList && sb[0] != mem.u8(Ai.STRAT_TARGET) },
                            "策略 ${sb.getOrNull(0x14)}/${mem.u8(Ai.STRAT_BEST)}".takeIf { hasList && sb[0x14] != mem.u8(Ai.STRAT_BEST) },
                            "亂數".takeIf { y.getLong("seed") != mem.u32(0x48ECD4L) },
                        )
                        if (diffs.isNotEmpty()) bad += "$where 位置 %04x：$diffs".format(p)
                    }
                    val ac = x.optJSONObject("act")
                    if (ac != null && ac.getBoolean("ok")) {
                        acts++
                        setup()
                        val action = bf.ai.act(u)
                        val wantUnits = hex(ac.getString("units"))
                        val unitDiff = (0 until wantUnits.size).firstOrNull { wantUnits[it] != mem.u8(Units.UNITS + it) }
                        val occ = hex(ac.getString("occupant"))
                        val best = hex(ac.getString("best"))
                        // 0x4B2C3C、0x4B2C48 是 0x4359F5 的暫存（只寫不讀），不比
                        val ignore = setOf(0x4B2C3C - 0x4B2C1C, 0x4B2C3D - 0x4B2C1C, 0x4B2C48 - 0x4B2C1C)
                        val scores = hex(ac.getString("scores"))
                        val calls = ac.getJSONArray("calls")
                        val strat = (0 until calls.length()).map { calls.getJSONArray(it) }.firstOrNull { it.getString(0) == "strategy" }?.getInt(1) ?: 0xFF
                        val misc = ac.getJSONArray("misc")
                        val diffs = listOfNotNull(
                            unitDiff?.let { "單位表 格 ${it / 0x30} +%02x 原版 %02x 引擎 %02x".format(it % 0x30, wantUnits[it], mem.u8(Units.UNITS + it)) },
                            "佔位表".takeIf { (0 until n).any { occ[it] != (bf.occupant[it].toInt() and 0xff) } },
                            ("全域 " + (0 until best.size).filter { it !in ignore && best[it] != mem.u8(0x4B2C1CL + it) }.joinToString { "%x:%02x/%02x".format(0x4B2C1C + it, best[it], mem.u8(0x4B2C1CL + it)) })
                                .takeIf { (0 until best.size).any { it !in ignore && best[it] != mem.u8(0x4B2C1CL + it) } },
                            "每格得分".takeIf { (0 until n).any { scores[it] != (bf.scratch[0x640 + it].toInt() and 0xff) } },
                            "策略 $strat/${action?.strategy}".takeIf { strat != 0xFF && strat != action?.strategy },
                            "0x501BF9 ${misc.getInt(0)}/${mem.u8(0x501BF9L)}".takeIf { misc.getInt(0) != mem.u8(0x501BF9L) },
                            "亂數".takeIf { ac.getLong("seed") != mem.u32(0x48ECD4L) },
                        )
                        if (diffs.isNotEmpty()) bad += "$where 執行：$diffs"
                    }
                    val fu = x.getJSONObject("full")
                    if (!fu.getBoolean("ok")) continue
                    if (!hasList && hex(fu.getString("strategies"))[0] != 0xFF) { skipped++; continue }
                    full++
                    setup()
                    bf.search.search(u, mp, 4, 0xFFFF, 1)
                    val best = hex(fu.getString("best"))
                    fun b(a: Long) = best[(a - Ai.BEST_POS).toInt()]
                    val scores = hex(fu.getString("scores"))
                    val cost = hex(fu.getString("cost"))
                    val diffs = listOfNotNull(
                        "位置 %02x%02x/%04x".format(b(Ai.BEST_POS + 1), b(Ai.BEST_POS), mem.u16(Ai.BEST_POS)).takeIf { b(Ai.BEST_POS) != mem.u8(Ai.BEST_POS) || b(Ai.BEST_POS + 1) != mem.u8(Ai.BEST_POS + 1) },
                        "目標 ${b(Ai.BEST_TARGET)}/${mem.u8(Ai.BEST_TARGET)}".takeIf { b(Ai.BEST_TARGET) != mem.u8(Ai.BEST_TARGET) },
                        "最高分 ${b(Ai.BEST_SCORE)}/${mem.u8(Ai.BEST_SCORE)}".takeIf { b(Ai.BEST_SCORE) != mem.u8(Ai.BEST_SCORE) },
                        "策略 ${b(Ai.BEST_STRATEGY)}/${mem.u8(Ai.BEST_STRATEGY)}".takeIf { b(Ai.BEST_STRATEGY) != mem.u8(Ai.BEST_STRATEGY) },
                        ("每格得分 " + (0 until n).filter { scores[it] != (bf.scratch[0x640 + it].toInt() and 0xff) }.take(4).joinToString {
                            "%02x%02x:%d/%d(w%d m%02x)".format(it / bf.width, it % bf.width, scores[it], bf.scratch[0x640 + it].toInt() and 0xff, weights[it], marks[it])
                        }).takeIf { (0 until n).any { scores[it] != (bf.scratch[0x640 + it].toInt() and 0xff) } },
                        "花費表".takeIf { (0 until n).any { cost[it] != (bf.search.cost[it].toInt() and 0xff) } },
                        "亂數".takeIf { fu.getLong("seed") != mem.u32(0x48ECD4L) },
                    )
                    if (diffs.isNotEmpty()) bad += "$where 模式 4：$diffs"
                }
            }
        }
        println("AI 評估：策略清單 $lists、物理／策略得分 $tiles 格、模式 4 搜尋 $full 次（有策略略過 $skipped）、執行 $acts 次")
        if (bad.isNotEmpty()) fail("${bad.size} 筆不同：\n" + bad.take(30).joinToString("\n"))
        assertTrue(tiles > 0)
    }
}
