package ccz

import ccz.battle.BattleSession
import ccz.battle.BattleTests
import ccz.battle.Units
import ccz.data.ClassTables
import ccz.data.GameData
import ccz.io.GameFiles
import ccz.script.Instr
import ccz.script.Param
import ccz.script.ScriptEvent
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 方法 1：戰場條件指令跟原版比對。
 * 標準答案：python3 tools/re/gen_test_vectors.py <遊戲目錄>（輸出 out/test_vectors.json，不進 git）。
 * 引擎先跑 S_002 scene 0（出陣只派曹操，跟產生器一樣），再套用每一組的戰場狀態逐筆比對。
 */
class TestOpsTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val vectors = File(System.getProperty("ccz.testVectors") ?: "../../out/test_vectors.json")

    @Test
    fun matchesOriginal() {
        val f = files ?: return
        if (!vectors.exists()) { println("沒有 ${vectors.path}，略過"); return }
        val root = JSONObject(vectors.readText())
        val s = BattleSession(f, GameData(f), ClassTables(f), 2)
        while (true) { val e = s.next() ?: break; if (e is ScriptEvent.Choice || e is ScriptEvent.YesNo) s.answer(1) }
        val field = s.field!!
        val tests = s.tests!!
        val mem = s.mem
        val bad = ArrayList<String>()
        var total = 0
        var gaps = 0
        val groups = root.getJSONArray("groups")
        for (g in 0 until groups.length()) {
            val grp = groups.getJSONObject(g)
            val units = grp.getString("units").chunked(2).map { it.toInt(16) }
            units.forEachIndexed { i, b -> mem.setU8(Units.UNITS + i, b) }
            grp.getString("occupant").chunked(2).forEachIndexed { i, h -> field.occupant[i] = h.toInt(16).toByte() }
            mem.setU8(BattleTests.BATTLE + 2, grp.getInt("turn"))
            mem.setU8(BattleTests.BATTLE + 0x10, grp.getInt("side"))
            grp.optJSONArray("globals")?.let { gl ->
                for (i in 0 until gl.length()) {
                    val (addr, n, v) = gl.getJSONArray(i).let { Triple(it.getLong(0), it.getInt(1), it.getLong(2)) }
                    for (b in 0 until n) mem.setU8(addr + b, ((v shr (8 * b)) and 0xff).toInt())
                }
            }
            val list = grp.getJSONArray("tests")
            for (k in 0 until list.length()) {
                val t = list.getJSONObject(k)
                if (!t.optBoolean("ok", true)) continue   // 原版自己跑不完（例如範圍 y 到 255，byte 迴圈不會結束）
                val op = t.getInt("op")
                val ps = t.getJSONArray("params")
                val params = (0 until ps.length()).map { ps.getJSONArray(it).let { a -> Param(a.getInt(0), a.getLong(1).toInt()) } }
                total++
                val got = if (op == 0x37) s.vm.evaluate(Instr(0, op, params)) else tests.test(Instr(0, op, params))
                if (got == null) { gaps++; continue }
                val want = t.getInt("ret") != 0
                if (got != want) bad += "組 $g #$k op %02x 參數 %s：原版 %s 引擎 %s".format(op, params.map { it.value }, want, got)
            }
        }
        println("條件指令：$total 筆，還沒實作 $gaps 筆；" + s.vm.gaps.entries.joinToString { "${it.key} ×${it.value}" })
        if (bad.isNotEmpty()) fail("${bad.size} 筆不同：\n" + bad.take(20).joinToString("\n"))
        assertTrue(gaps < total / 5)
    }
}
