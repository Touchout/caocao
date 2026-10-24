package ccz

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
 * 方法 1：戰鬥能力值（0x43F5FF…）與物理傷害（0x43BC5A）跟原版比對。
 * 標準答案：python3 tools/re/gen_damage_vectors.py <遊戲目錄>（輸出 out/damage_vectors.json，不進 git）。
 */
class DamageTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val vectors = File(System.getProperty("ccz.damageVectors") ?: "../../out/damage_vectors.json")

    private fun forEachTest(block: (BattleSession, Int, Int, JSONObject) -> Unit) {
        val f = files ?: return
        if (!vectors.exists()) { println("沒有 ${vectors.path}，略過"); return }
        val data = GameData(f)
        val tables = ClassTables(f)
        val stages = JSONArray(vectors.readText())
        for (k in 0 until stages.length()) {
            val st = stages.getJSONObject(k)
            val stage = st.getInt("stage")
            val s = BattleSession(f, data, tables, stage)
            while (true) { val e = s.next() ?: break; if (e is ScriptEvent.Choice || e is ScriptEvent.YesNo) s.answer(1) }
            val field = s.field!!
            val mem = s.mem
            val baseChars = mem.bytes(mem.u32(Characters.PTR), Characters.COUNT * Characters.SIZE)
            val groups = st.getJSONArray("groups")
            for (g in 0 until groups.length()) {
                val grp = groups.getJSONObject(g)
                baseChars.forEachIndexed { i, b -> mem.setU8(mem.u32(Characters.PTR) + i, b.toInt() and 0xff) }
                val chars = grp.getJSONObject("chars")
                for (key in chars.keys()) {
                    val a = Characters.addr(mem, key.toInt())
                    chars.getString(key).chunked(2).forEachIndexed { i, h -> mem.setU8(a + i, h.toInt(16)) }
                }
                val units = grp.getString("units").chunked(2).map { it.toInt(16) }
                grp.getString("occupant").chunked(2).forEachIndexed { i, h -> field.occupant[i] = h.toInt(16).toByte() }
                val tests = grp.getJSONArray("tests")
                for (t in 0 until tests.length()) {
                    units.forEachIndexed { i, b -> mem.setU8(Units.UNITS + i, b) }
                    block(s, stage, g, tests.getJSONObject(t))
                }
            }
        }
    }

    @Test
    fun combatStatsMatch() {
        val bad = ArrayList<String>()
        var total = 0
        forEachTest { s, stage, g, x ->
            if (!x.getBoolean("ok")) return@forEachTest
            val stats = x.getJSONArray("stats")
            listOf(x.getInt("attacker"), x.getInt("target")).forEachIndexed { side, slot ->
                val want = stats.getJSONArray(side)
                for (i in 0 until 5) {
                    total++
                    val got = s.field!!.stats.stat(Units.unit(slot), i)
                    if (got != want.getLong(i)) bad += "S_%03d 組 $g 格 $slot 能力 $i：原版 ${want.getLong(i)} 引擎 $got".format(stage)
                }
            }
        }
        println("戰鬥能力值：$total 筆")
        if (bad.isNotEmpty()) fail("${bad.size} 筆不同：\n" + bad.take(25).joinToString("\n"))
        if (files != null && vectors.exists()) assertTrue(total > 0)
    }
}
