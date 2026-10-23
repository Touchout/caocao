package ccz

import ccz.battle.BattleSession
import ccz.battle.Search
import ccz.battle.Units
import ccz.data.ClassTables
import ccz.data.GameData
import ccz.io.GameFiles
import ccz.script.ScriptEvent
import org.json.JSONArray
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 方法 1：移動力 0x43F74F 與移動範圍搜尋 0x438484 跟原版比對（花費表逐格）。
 * 標準答案：python3 tools/re/gen_range_vectors.py <遊戲目錄>（輸出 out/range_vectors.json，不進 git）。
 */
class RangeTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val vectors = File(System.getProperty("ccz.rangeVectors") ?: "../../out/range_vectors.json")

    @Test
    fun matchesOriginal() {
        val f = files ?: return
        if (!vectors.exists()) { println("沒有 ${vectors.path}，略過"); return }
        val data = GameData(f)
        val tables = ClassTables(f)
        val bad = ArrayList<String>()
        var total = 0
        val stages = JSONArray(vectors.readText())
        for (k in 0 until stages.length()) {
            val st = stages.getJSONObject(k)
            val stage = st.getInt("stage")
            val w = st.getInt("width")
            val h = st.getInt("height")
            val s = BattleSession(f, data, tables, stage)
            while (true) { val e = s.next() ?: break; if (e is ScriptEvent.Choice || e is ScriptEvent.YesNo) s.answer(1) }
            val field = s.field!!
            val mem = s.mem
            val search = Search(field) { s.vm.gaps.merge(it, 1, Int::plus) }
            val groups = st.getJSONArray("groups")
            for (g in 0 until groups.length()) {
                val grp = groups.getJSONObject(g)
                if (!grp.getBoolean("ok")) continue
                grp.getString("units").chunked(2).forEachIndexed { i, x -> mem.setU8(Units.UNITS + i, x.toInt(16)) }
                grp.getString("occupant").chunked(2).forEachIndexed { i, x -> field.occupant[i] = x.toInt(16).toByte() }
                val slot = grp.getInt("slot")
                val u = Units.unit(slot)
                total++
                val mp = search.movePoints(u)
                if (mp != grp.getInt("move")) { bad += "S_%03d 組 $g 格 $slot：移動力 原版 ${grp.getInt("move")} 引擎 $mp".format(stage); continue }
                search.search(u, mp)
                val want = grp.getString("cost").chunked(2).map { it.toInt(16) }
                val diff = (0 until w * h).filter { (search.cost[it].toInt() and 0xff) != want[it] }
                if (diff.isNotEmpty()) {
                    val i = diff.first()
                    bad += "S_%03d 組 $g 格 $slot（移動力 $mp）：%d 格不同，例如 (%d, %d) 原版 %d 引擎 %d".format(
                        stage, diff.size, i % w, i / w, want[i], search.cost[i].toInt() and 0xff)
                }
            }
            if (s.vm.gaps.isNotEmpty()) println("S_%03d 未移植：".format(stage) + s.vm.gaps.entries.joinToString { "${it.key} ×${it.value}" })
        }
        println("移動範圍：$total 組")
        if (bad.isNotEmpty()) fail("${bad.size} 組不同：\n" + bad.take(25).joinToString("\n"))
        assertTrue(total > 0)
    }
}
