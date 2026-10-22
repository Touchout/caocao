package ccz

import ccz.script.Eex
import ccz.script.EngineMemory
import ccz.script.VarOps
import org.json.JSONArray
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跟原版比對：tools/re/gen_vectors.py 用模擬器執行原版處理函式產生的測試資料。
 * 需要遊戲檔（記憶體映像來自 Ekd5.exe）：./gradlew :core:test -Pgame=<遊戲目錄>
 */
class VarOpsTest {
    private val exe: ByteArray? = System.getProperty("ccz.game")?.let { File(it, "Ekd5.exe").readBytes() }

    @Test
    fun matchesOriginal() {
        val exe = exe ?: return
        val vectors = JSONArray(VarOpsTest::class.java.getResource("/ccz/vm_vectors.json")!!.readText())
        val failures = ArrayList<String>()
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val op = v.getInt("op")
            val code = byteArrayOf(op.toByte(), 0) + hex(v.getString("code"))
            val mem = EngineMemory(exe)
            val init = v.getJSONObject("init")
            init.keys().forEach { mem.setU32(it.toLong(16), init.getLong(it)) }
            val (ins, _) = Eex.decode(code, 0)
            val p = ins.params
            val ret: Long = when (op) {
                0x77 -> { VarOps.assign(mem, p[0].value, p[1].value.toLong() and 0xFFFFFFFFL, p[2].value, p[3].value, p[4].value.toLong() and 0xFFFFFFFFL); 1 }
                0x79 -> if (VarOps.compare(mem, p[0].value, p[1].value.toLong() and 0xFFFFFFFFL, p[2].value, p[3].value, p[4].value.toLong() and 0xFFFFFFFFL)) 1 else 0
                0x05 -> {
                    val n1 = p[0].value
                    val on = p.subList(1, 1 + n1).map { it.value }
                    val off = p.subList(2 + n1, p.size).map { it.value }
                    if (VarOps.flagTest(mem, on, off)) 1 else 0
                }
                0x0B -> { mem.setFlag(p[0].value, p[1].value != 0); 1 }
                else -> error("op $op")
            }
            val wantRet = v.getLong("ret")
            if (op != 0x77 && op != 0x0B && ret != wantRet) failures += "#$i op %02x ret $ret != $wantRet code ${v.getString("code")}".format(op)
            val expect = v.getJSONObject("expect")
            expect.keys().forEach { k ->
                val got = mem.u32(k.toLong(16))
                if (got != expect.getLong(k)) failures += "#$i op %02x @$k got %x want %x code ${v.getString("code")}".format(op, got, expect.getLong(k))
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} 筆不符：\n" + failures.take(20).joinToString("\n"))
        assertEquals(1200, vectors.length())
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
