package ccz

import ccz.data.Characters
import ccz.io.GameFiles
import ccz.script.Attributes
import ccz.script.EngineMemory
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 方法 1：0x78 武將屬性讀寫跟原版 0x4202D8 比對。
 * 標準答案：python3 tools/re/gen_attr_vectors.py <遊戲目錄>（輸出 out/attr_vectors.json，含遊戲資料所以不進 git）。
 */
class AttrTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val vectors = File(System.getProperty("ccz.attrVectors") ?: "../../out/attr_vectors.json")

    /** 會被 0x78 動到的區域，用來抓「引擎多寫了」。 */
    private val regions = listOf(
        EngineMemory.HEAP + 0x100000 to Characters.SIZE * Characters.COUNT,
        Attributes.UNITS to Attributes.UNIT_SIZE * Attributes.SLOTS,
        Attributes.S_IMAGES to 0x800,
        Attributes.FACES to 0x1000,
        EngineMemory.INT_VARS to 0x4000,
    )

    @Test
    fun matchesOriginal() {
        val f = files ?: return
        if (!vectors.exists()) { println("沒有 ${vectors.path}，略過"); return }
        val root = JSONObject(vectors.readText())
        val mem = EngineMemory(f.bytes("Ekd5.exe"))
        Characters.init(mem, f.archive("Data.e5")[0])
        val units = root.getString("units").chunked(2).map { it.toInt(16) }
        units.forEachIndexed { i, b -> mem.setU8(Attributes.UNITS + i, b) }

        val ops = root.getJSONArray("ops")
        val bad = ArrayList<String>()
        var gaps = 0
        for (k in 0 until ops.length()) {
            val o = ops.getJSONObject(k)
            val attr = o.getInt("attr"); val ch = o.getInt("char"); val mode = o.getInt("mode"); val v = o.getInt("var")
            mem.setU32(mem.intVarAddr(v), o.getLong("value"))
            val want = HashMap<Long, Int>()
            o.getJSONArray("writes").forEach { w -> (w as org.json.JSONArray).let { want[it.getLong(0)] = it.getInt(1) } }
            val before = regions.map { (a, n) -> mem.bytes(a, n) }
            if (!Attributes.execute(mem, attr, ch, mode, v)) {
                gaps++
                want.forEach { (a, b) -> mem.setU8(a, b) } // 還沒實作的行為：照原版結果同步，繼續比後面的
                continue
            }
            val where = "#$k 屬性 $attr 武將 $ch 模式 $mode"
            for ((a, b) in want) if (mem.u8(a) != b) { bad += "$where：%#x 原版 %02x 引擎 %02x".format(a, b, mem.u8(a)); break }
            regions.forEachIndexed { r, (base, n) ->
                val now = mem.bytes(base, n)
                for (i in 0 until n) if (now[i] != before[r][i] && (base + i) !in want) {
                    bad += "$where：引擎多寫了 %#x".format(base + i); return@forEachIndexed
                }
            }
            want.forEach { (a, b) -> mem.setU8(a, b) }
        }
        println("0x78：${ops.length()} 筆，還沒實作（升級）$gaps 筆")
        if (bad.isNotEmpty()) fail("${bad.size} 筆不同：\n" + bad.take(20).joinToString("\n"))
        assertTrue(gaps < ops.length() / 20)
    }
}
