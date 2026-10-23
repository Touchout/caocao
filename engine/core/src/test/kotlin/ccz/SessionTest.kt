package ccz

import ccz.battle.BattleSession
import ccz.battle.Side
import ccz.data.ClassTables
import ccz.data.GameData
import ccz.io.GameFiles
import ccz.script.ScriptEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 執行戰場劇本 scene 0。需要遊戲檔：./gradlew :core:test -Pgame=<遊戲目錄> */
class SessionTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }

    /** 選擇題一律選第一個／是，回傳所有事件。 */
    private fun runAll(s: BattleSession, limit: Int = 100000): List<ScriptEvent> {
        val out = ArrayList<ScriptEvent>()
        while (out.size < limit) {
            val e = s.next() ?: break
            out += e
            if (e is ScriptEvent.Choice || e is ScriptEvent.YesNo) s.answer(1)
        }
        return out
    }

    @Test
    fun s002Setup() {
        val f = files ?: return
        val s = BattleSession(f, GameData(f), ClassTables(f), 2)
        val events = runAll(s)
        val b = assertNotNull(s.battle)
        assertEquals(2, b.map.id)
        val lvbu = b.units.first { it.character == 119 }
        assertEquals(Side.ENEMY, lvbu.side)
        assertEquals(8 to 9, lvbu.x to lvbu.y)
        assertEquals(41, lvbu.unitClass) // 0x52 兵种改变 吕布 → 41
        assertEquals(17, b.map.terrain(6, 9)) // 0x58 城門
        val dialogues = events.filterIsInstance<ScriptEvent.Dialogue>()
        assertTrue(dialogues.size > 10, "對話數 ${dialogues.size}")
        assertTrue(dialogues.any { it.speaker == 119 }, "呂布有說話")
        println("S_002：${events.size} 個事件，前 5 句：" + dialogues.take(5).joinToString(" | ") { "${it.name}:${it.text.take(12)}" })
        println("不認得的指令：" + s.vm.unknown.entries.sortedByDescending { it.value }.joinToString { "%02x×%d".format(it.key, it.value) })
    }

    /** 對話頭像（原版 0x40765C）：頭像表的值 + 7；曹操 0 → 第 0 張。使用者回報許子將顯示成女生（之前沒加 7）。 */
    @Test
    fun faceIndex() {
        val f = files ?: return
        val s = BattleSession(f, GameData(f), ClassTables(f), 0)
        assertEquals(listOf(0, 8, 13, 213), listOf(0, 1, 3, 157).map { s.face(it) })   // 曹操、夏侯惇、關羽、許子將
    }

    /** 暫時的出陣畫面：出陣的武將當成已入隊，S_001（汜水關）照出場格放上地圖。 */
    @Test
    fun testArmySortie() {
        val f = files ?: return
        val s = BattleSession(f, GameData(f), ClassTables(f), 1, sortie = listOf(0, 1, 6, 10), testArmy = true)
        runAll(s)
        val players = assertNotNull(s.battle).units.filter { it.side == Side.PLAYER && !it.hidden }.map { it.character }.sorted()
        assertEquals(listOf(0, 1, 6, 10), players)
    }

    @Test
    fun allStagesRunSetup() {
        val f = files ?: return
        val data = GameData(f)
        val tables = ClassTables(f)
        val stages = f.list("RS").mapNotNull { Regex("rs/s_(\\d+)\\.eex").matchEntire(it)?.groupValues?.get(1)?.toInt() }.sorted()
        val failures = ArrayList<String>()
        val unknown = HashMap<Int, Int>()
        var noMap = 0
        for (n in stages) {
            runCatching {
                val s = BattleSession(f, data, tables, n)
                runAll(s)
                if (s.battle == null) noMap++
                s.vm.unknown.forEach { (k, v) -> unknown.merge(k, v, Int::plus) }
            }.onFailure { failures += "S_%03d: %s".format(n, it) }
        }
        println("戰場 ${stages.size} 關，沒有地圖 $noMap 關；不認得的指令：" +
            unknown.entries.sortedByDescending { it.value }.joinToString { "%02x×%d".format(it.key, it.value) })
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
