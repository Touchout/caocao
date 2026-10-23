package ccz

import ccz.battle.Units
import ccz.data.Characters
import ccz.data.NewGame
import ccz.data.Stats
import ccz.io.GameFiles
import ccz.script.Attributes
import ccz.script.EngineMemory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 方法 1：能力值（0x38、0x78 寫能力、基礎值公式 0x408A60）跟原版比對。
 * 標準答案：python3 tools/re/gen_stats_vectors.py <遊戲目錄>（輸出 out/stats_vectors.json，不進 git）。
 */
class StatsTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val vectors = File(System.getProperty("ccz.statsVectors") ?: "../../out/stats_vectors.json")

    private val regions = listOf(
        EngineMemory.HEAP + 0x100000 to Characters.SIZE * Characters.COUNT,
        Stats.BONUS_LOG to 0xE * Stats.BONUS_LOG_LIMIT,
        Attributes.FACES to 0x800,
        EngineMemory.INT_VARS to 0x4000,
        Units.UNITS to Units.SIZE * Units.SLOTS,
    )
    /** 劇本 VM 物件（讀參數會動串流位置），不比。 */
    private val vmObject = 0x492F90L until 0x492FC8L

    @Test
    fun matchesOriginal() {
        val f = files ?: return
        if (!vectors.exists()) { println("沒有 ${vectors.path}，略過"); return }
        val root = JSONObject(vectors.readText())
        val mem = EngineMemory(f.bytes("Ekd5.exe"))
        NewGame.init(mem, f)
        root.getJSONArray("init").forEach { (it as JSONArray).let { w -> mem.setU8(w.getLong(0), w.getInt(1)) } }

        val ops = root.getJSONArray("ops")
        val bad = ArrayList<String>()
        var gaps = 0
        for (k in 0 until ops.length()) {
            val o = ops.getJSONObject(k)
            val want = HashMap<Long, Int>()
            // 只比遊戲狀態區域（畫面物件，例如狀態視窗 0x4B80E8，不比）
            o.getJSONArray("writes").forEach { w ->
                (w as JSONArray).let { if (it.getLong(0) !in vmObject && regions.any { (a, n) -> it.getLong(0) in a until a + n }) want[it.getLong(0)] = it.getInt(1) }
            }
            if (o.getString("kind") == "78") mem.setU32(mem.intVarAddr(o.getInt("var")), o.getLong("value"))
            val before = regions.map { (a, n) -> mem.bytes(a, n) }
            val ch = o.getInt("char")
            val where = "#$k ${o.getString("kind")} 武將 $ch"
            val done = when (o.getString("kind")) {
                "38" -> {
                    // 讀參數的規則（ScriptVM.decode）：i32 負數 = 整數變數 −值
                    val raw = o.getLong("value").toInt()
                    val v = if (raw < 0) mem.intVar(-raw) else raw.toLong()
                    Stats.op38(mem, ch, o.getInt("stat"), o.getInt("op"), v)
                }
                "78" -> Attributes.execute(mem, o.getInt("attr"), ch, o.getInt("mode"), o.getInt("var"))
                else -> {
                    val got = Stats.levelGrowth(mem, ch, o.getInt("stat"))
                    val exp = o.getLong("expect").toInt()
                    if (got != exp) bad += "$where 項目 ${o.getInt("stat")} 兵種 ${mem.u8(Characters.addr(mem, ch) + 0x2B)} " +
                        "等級 ${mem.u8(Characters.addr(mem, ch) + 0x2C)}：基礎值 原版 $exp 引擎 $got"
                    true
                }
            }
            if (!done) { gaps++; want.forEach { (a, b) -> mem.setU8(a, b) }; continue }
            val detail = "$where " + listOf("stat", "op", "value", "attr", "mode").filter { o.has(it) }.joinToString(" ") { "$it=${o.get(it)}" } +
                " 我軍標識=${mem.u8(Characters.addr(mem, ch) + 0x2A)}"
            for ((a, b) in want) if (mem.u8(a) != b) { bad += "$detail：%#x 原版 %02x 引擎 %02x".format(a, b, mem.u8(a)); break }
            regions.forEachIndexed { r, (base, n) ->
                val now = mem.bytes(base, n)
                for (i in 0 until n) if (now[i] != before[r][i] && (base + i) !in want) {
                    bad += "$detail：引擎多寫了 %#x".format(base + i); return@forEachIndexed
                }
            }
            want.forEach { (a, b) -> mem.setU8(a, b) }
        }
        println("能力值：${ops.length()} 筆，還沒實作 $gaps 筆")
        if (bad.isNotEmpty()) fail("${bad.size} 筆不同：\n" + bad.take(15).joinToString("\n"))
        assertTrue(gaps < ops.length() / 10)
    }
}
