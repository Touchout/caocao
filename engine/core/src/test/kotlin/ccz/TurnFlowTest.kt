package ccz

import ccz.battle.BattleSession
import ccz.battle.BattleTests
import ccz.data.ClassTables
import ccz.data.GameData
import ccz.io.GameFiles
import ccz.script.ScriptEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 回合流程：每關開戰處理跑完後進入回合，玩家每回合直接結束，要能一路跑到戰鬥結束。 */
class TurnFlowTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }

    /** 跑到戰鬥結束，回傳 (結束結果, 回合提示的序列)。 */
    private fun play(s: BattleSession, limit: Int = 200000): Pair<Int?, List<Pair<Int, Int>>> {
        var setup = true
        val banners = ArrayList<Pair<Int, Int>>()
        repeat(limit) {
            val e = s.next()
            when {
                e == null && setup -> { setup = false; s.startTurns() }
                e == null -> if (s.playerPhase) s.endPlayerPhase() else return null to banners
                e is ScriptEvent.Choice || e is ScriptEvent.YesNo -> s.answer(1)
                e is ScriptEvent.TurnStart -> banners += e.turn to e.side
                e is ScriptEvent.BattleEnd -> return e.result to banners
            }
        }
        return null to banners
    }

    /** 潁川之戰：曹操開場是隱藏的援軍，第 2 回合 scene 1「曹操军出场」事件放出（使用者回報過開場沒有曹操）。 */
    @Test
    fun s000CaoCaoArrivesTurn2() {
        val f = files ?: return
        val s = BattleSession(f, GameData(f), ClassTables(f), 0)
        val seen = ArrayList<Pair<Int, Boolean>>()
        var setup = true
        while (seen.size < 2) {
            val e = s.next()
            when {
                e == null && setup -> { setup = false; s.startTurns() }
                e == null -> s.endPlayerPhase()
                e is ScriptEvent.Choice || e is ScriptEvent.YesNo -> s.answer(1)
                e is ScriptEvent.PlayerPhase -> {
                    val cao = s.battle!!.unitOf(0)
                    seen += s.battle!!.turn to (cao != null && !cao.hidden)
                }
            }
        }
        assertEquals(listOf(1 to false, 2 to true), seen)
    }

    @Test
    fun s000RunsOutOfTime() {
        val f = files ?: return
        val s = BattleSession(f, GameData(f), ClassTables(f), 0)
        val (result, banners) = play(s)
        val limit = s.mem.u8(BattleTests.BATTLE + 3)
        println("S_000：回合上限 $limit，結果 $result，提示 ${banners.take(6)}…；未移植：" +
            s.vm.gaps.entries.joinToString { "${it.key} ×${it.value}" })
        assertTrue(result != null)
        assertEquals(1 to 0, banners.first())
        assertTrue(banners.size <= 2 * limit)
    }

    /** 全部關卡（約 5 分鐘）：./gradlew :core:test -Pgame=<遊戲目錄> -Pslow */
    @Test
    fun allStagesFinish() {
        val f = files ?: return
        if (System.getProperty("ccz.slow") == null) return
        val data = GameData(f)
        val tables = ClassTables(f)
        val stages = f.list("RS").mapNotNull { Regex("rs/s_(\\d+)\\.eex").matchEntire(it)?.groupValues?.get(1)?.toInt() }.sorted()
        val failures = ArrayList<String>()
        val results = HashMap<Int?, Int>()
        for (n in stages) {
            val t0 = System.currentTimeMillis()
            runCatching {
                val s = BattleSession(f, data, tables, n)
                val (result, banners) = play(s)
                System.err.println("S_%03d：%d ms，%d 個提示，結果 %s".format(n, System.currentTimeMillis() - t0, banners.size, result))
                if (s.battle != null) {
                    results.merge(result, 1, Int::plus)
                    if (result == null) failures += "S_%03d 沒有結束".format(n)
                }
            }.onFailure { failures += "S_%03d: %s".format(n, it) }
        }
        println("回合流程：結果分布 $results")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
