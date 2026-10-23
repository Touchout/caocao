package ccz

import ccz.data.Characters
import ccz.data.Equipment
import ccz.data.NewGame
import ccz.io.GameFiles
import ccz.script.EngineMemory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * 方法 1：HP/MP 上限（0x407263／0x40728F，含裝備加成）跟原版比對。
 * 標準答案：python3 tools/re/gen_hpmp_vectors.py <遊戲目錄>（輸出 out/hpmp_vectors.json，不進 git）。
 */
class HpMpTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val vectors = File(System.getProperty("ccz.hpmpVectors") ?: "../../out/hpmp_vectors.json")

    @Test
    fun matchesOriginal() {
        val f = files ?: return
        if (!vectors.exists()) { println("沒有 ${vectors.path}，略過"); return }
        val mem = EngineMemory(f.bytes("Ekd5.exe"))
        NewGame.init(mem, f)
        val cases = JSONArray(vectors.readText())
        val bad = ArrayList<String>()
        for (k in 0 until cases.length()) {
            val o = cases.getJSONObject(k)
            val c = o.getInt("char")
            val data = o.getString("data").chunked(2).map { it.toInt(16) }
            val r = Characters.addr(mem, c)
            data.forEachIndexed { i, b -> mem.setU8(r + i, b) }
            mem.setIntVar(4046, o.getLong("var4046"))
            val hp = Equipment.maxHp(mem, c)
            val mp = Equipment.maxMp(mem, c)
            if (hp != o.getLong("hp") || mp != o.getLong("mp")) {
                bad += "#$k 武將 $c 裝備 ${(0..2).joinToString { "%02x/%d".format(data[0x2E + 3 * it], data[0x2F + 3 * it]) }} " +
                    "變數4046=${o.getLong("var4046")}：HP 原版 ${o.getLong("hp")} 引擎 $hp，MP 原版 ${o.getLong("mp")} 引擎 $mp"
            }
        }
        println("HP/MP 上限：${cases.length()} 筆")
        if (bad.isNotEmpty()) fail("${bad.size} 筆不同：\n" + bad.take(15).joinToString("\n"))
    }
}
