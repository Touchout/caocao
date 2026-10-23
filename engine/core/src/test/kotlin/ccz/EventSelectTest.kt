package ccz

import ccz.battle.BattleSession
import ccz.battle.BattleTests
import ccz.data.ClassTables
import ccz.data.GameData
import ccz.io.GameFiles
import ccz.script.ScriptEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * 方法 1：戰場事件的選擇（原版 0x4100A1）跟 ScriptVM.selectEvent 比對。
 * 標準答案：python3 tools/re/gen_event_vectors.py <遊戲目錄>（輸出 out/event_vectors.json，不進 git）。
 * 每關引擎先跑 scene 0（出陣只派曹操，跟產生器一樣），再套用每一組的回合、行動方、旗標，比對選到的位置。
 */
class EventSelectTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val vectors = File(System.getProperty("ccz.eventVectors") ?: "../../out/event_vectors.json")

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
            val s = BattleSession(f, data, tables, stage)
            while (true) { val e = s.next() ?: break; if (e is ScriptEvent.Choice || e is ScriptEvent.YesNo) s.answer(1) }
            val base = (0 until 0x2000).map { s.mem.u8(FLAGS + it) }
            val groups = st.getJSONArray("groups")
            for (g in 0 until groups.length()) {
                val grp: JSONObject = groups.getJSONObject(g)
                if (!grp.getBoolean("ok")) continue
                base.forEachIndexed { i, b -> s.mem.setU8(FLAGS + i, b) }
                val on = grp.getJSONArray("on")
                for (i in 0 until on.length()) s.mem.setFlag(on.getInt(i), true)
                val off = grp.getJSONArray("off")
                for (i in 0 until off.length()) s.mem.setFlag(off.getInt(i), false)
                s.results.fill(0)
                s.mem.setU8(BattleTests.BATTLE + 2, grp.getInt("turn"))
                s.mem.setU8(BattleTests.BATTLE + 0x10, grp.getInt("side"))
                total++
                val want = if (grp.getBoolean("selected")) grp.getInt("pos") else null
                val got = s.vm.selectEvent(1)?.second?.plus(2)
                if (got != want) bad += "S_%03d 組 $g（回合 ${grp.getInt("turn")} 行動方 ${grp.getInt("side")}）：原版 %s 引擎 %s".format(
                    stage, want?.let { "%x".format(it) }, got?.let { "%x".format(it) })
            }
        }
        println("戰場事件選擇：$total 組")
        if (bad.isNotEmpty()) fail("${bad.size} 組不同：\n" + bad.take(20).joinToString("\n"))
    }

    private companion object { const val FLAGS = 0x492FC8L }
}
