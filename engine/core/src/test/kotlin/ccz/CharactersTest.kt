package ccz

import ccz.data.Characters
import ccz.io.GameFiles
import ccz.script.EngineMemory
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * 方法 1：武將執行期資料跟原版逐 byte 比對。
 * 標準答案由 tools/re/trace_original.py 在模擬器跑原版載入函式 0x41B639 產生：
 *   python3 tools/re/dump_characters.py <遊戲目錄> out/expected_characters.bin
 * 需要遊戲檔與標準答案：./gradlew :core:test -Pgame=<遊戲目錄>（-Dccz.expected 預設 out/expected_characters.bin）
 */
class CharactersTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val expected = File(System.getProperty("ccz.expected") ?: "../../out/expected_characters.bin")

    @Test
    fun matchesOriginal() {
        val f = files ?: return
        if (!expected.exists()) { println("沒有 ${expected.path}，略過"); return }
        val want = expected.readBytes()
        val mem = EngineMemory(f.bytes("Ekd5.exe"))
        Characters.init(mem, f.archive("Data.e5")[0])
        val bad = ArrayList<String>()
        for (n in 0 until Characters.COUNT) {
            val got = mem.bytes(Characters.addr(mem, n), Characters.SIZE)
            for (i in 0 until Characters.SIZE) {
                val w = want[n * Characters.SIZE + i]
                if (got[i] != w) { bad += "武將 $n +%02x：原版 %02x 引擎 %02x".format(i, w, got[i]); break }
            }
        }
        if (bad.isNotEmpty()) fail("${bad.size} 名不同：\n" + bad.take(20).joinToString("\n"))
    }
}
