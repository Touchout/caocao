package ccz

import ccz.battle.BattleSession
import ccz.battle.Effects
import ccz.battle.Units
import ccz.data.Characters
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
 * 方法 1：特效判定 0x4101D9 跟原版比對。
 * 標準答案：python3 tools/re/gen_effect_vectors.py <遊戲目錄>（輸出 out/effect_vectors.json，不進 git）。
 * 每關引擎先跑 scene 0（出陣只派曹操，跟產生器一樣），套用每組的單位表、佔位表、改過的武將資料後逐筆比對 eax／edx。
 */
class EffectTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val vectors = File(System.getProperty("ccz.effectVectors") ?: "../../out/effect_vectors.json")

    @Test
    fun matchesOriginal() {
        val f = files ?: return
        if (!vectors.exists()) { println("沒有 ${vectors.path}，略過"); return }
        val data = GameData(f)
        val tables = ClassTables(f)
        val bad = ArrayList<String>()
        var total = 0
        var nonzero = 0
        val stages = JSONArray(vectors.readText())
        for (k in 0 until stages.length()) {
            val st = stages.getJSONObject(k)
            val stage = st.getInt("stage")
            val s = BattleSession(f, data, tables, stage)
            while (true) { val e = s.next() ?: break; if (e is ScriptEvent.Choice || e is ScriptEvent.YesNo) s.answer(1) }
            val field = s.field!!
            val mem = s.mem
            val effects = Effects(field)
            val baseChars = mem.bytes(mem.u32(Characters.PTR), Characters.COUNT * Characters.SIZE)
            val baseExcl = mem.bytes(mem.u32(0x500C3BL), 0x1000)
            val baseCombo = mem.bytes(0x50E000L, 0x1000)
            val baseLimit = mem.u32(0x505F78L)
            val groups = st.getJSONArray("groups")
            for (g in 0 until groups.length()) {
                val grp = groups.getJSONObject(g)
                grp.getString("units").chunked(2).forEachIndexed { i, h -> mem.setU8(Units.UNITS + i, h.toInt(16)) }
                grp.getString("occupant").chunked(2).forEachIndexed { i, h -> field.occupant[i] = h.toInt(16).toByte() }
                baseChars.forEachIndexed { i, b -> mem.setU8(mem.u32(Characters.PTR) + i, b.toInt() and 0xff) }
                // 特效表：沒隨機填的組用 scene 0 跑完時的內容
                val excl = mem.u32(0x500C3BL)
                baseExcl.forEachIndexed { i, b -> mem.setU8(excl + i, b.toInt() and 0xff) }
                baseCombo.forEachIndexed { i, b -> mem.setU8(0x50E000L + i, b.toInt() and 0xff) }
                mem.setU32(0x505F78L, baseLimit)
                grp.optJSONObject("regions")?.let { r ->
                    r.getString("excl").chunked(2).forEachIndexed { i, h -> mem.setU8(excl + i, h.toInt(16)) }
                    r.getString("combo").chunked(2).forEachIndexed { i, h -> mem.setU8(0x50E000L + i, h.toInt(16)) }
                    mem.setU32(0x505F78L, r.getLong("limit"))
                }
                val chars = grp.getJSONObject("chars")
                for (key in chars.keys()) {
                    val a = Characters.addr(mem, key.toInt())
                    chars.getString(key).chunked(2).forEachIndexed { i, h -> mem.setU8(a + i, h.toInt(16)) }
                }
                val tests = grp.getJSONArray("tests")
                for (t in 0 until tests.length()) {
                    val x = tests.getJSONObject(t)
                    if (!x.getBoolean("ok")) continue
                    val a = x.getJSONArray("args").let { arr -> IntArray(4) { arr.getInt(it) } }
                    val slot = x.getInt("slot")
                    total++
                    val r = effects.check(Units.unit(slot), a[0], a[1], a[2], a[3])
                    val want = Effects.Result(x.getLong("eax"), x.getLong("edx"))
                    if (want.eax != 0L) nonzero++
                    if (r != want) bad += "S_%03d 組 $g 格 $slot 參數 %s：原版 %s 引擎 %s".format(stage, a.toList(), want, r)
                }
            }
        }
        println("特效判定：$total 筆（非 0 的 $nonzero 筆）")
        if (bad.isNotEmpty()) fail("${bad.size} 筆不同：\n" + bad.take(20).joinToString("\n"))
        assertTrue(total > 0)
    }
}
