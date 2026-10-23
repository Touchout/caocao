package ccz

import ccz.data.Characters
import ccz.data.NewGame
import ccz.io.GameFiles
import ccz.script.EngineMemory
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * 方法 1：開新遊戲後的記憶體跟原版逐 byte 比對（整個 exe 映像 + 武將陣列 + 第 2 項陣列）。
 * 標準答案：python3 tools/re/dump_memory.py <遊戲目錄>（輸出 out/expected_memory.bin，含遊戲資料所以不進 git）。
 */
class NewGameTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }
    private val expected = File(System.getProperty("ccz.expectedMemory") ?: "../../out/expected_memory.bin")

    /** 只有模擬器才有、或跟遊戲邏輯無關的位置。 */
    private val ignored = listOf(
        0x48B0C0L until 0x48B170L, // 緩衝區物件（0x42B88B 配置，GlobalAlloc 的位址只在模擬器裡有意義）
        0x4BE800L until 0x4BE880L, // 畫面 DIB（0x42B88B → 0x40353F，CreateDIBSection）
        0x496F60L until 0x496F64L, // 讀檔緩衝區指標（模擬器的位址）
        0x496E28L until 0x496E34L, // 讀檔時組檔名的字串暫存（"\SV\SV.E5…"）
    )

    @Test
    fun matchesOriginal() {
        val f = files ?: return
        if (!expected.exists()) { println("沒有 ${expected.path}，略過"); return }
        val want = expected.readBytes()
        val mem = EngineMemory(f.bytes("Ekd5.exe"))
        NewGame.init(mem, f)
        val imageSize = want.size - Characters.COUNT * Characters.SIZE - 128 * 0x28
        val bad = ArrayList<String>()
        fun check(base: Long, offset: Int, n: Int) {
            var i = 0
            while (i < n) {
                val a = base + i
                val w = want[offset + i].toInt() and 0xff
                if (mem.u8(a) != w && ignored.none { a in it }) {
                    var j = i
                    while (j < n && mem.u8(base + j) != (want[offset + j].toInt() and 0xff)) j++
                    bad += "%#x..%#x（%d bytes）原版 %02x 引擎 %02x".format(a, base + j, j - i, w, mem.u8(a))
                    i = j
                } else i++
            }
        }
        check(EngineMemory.BASE, 0, imageSize)
        check(Characters.ARRAY, imageSize, Characters.COUNT * Characters.SIZE)
        check(NewGame.ENTRY2, imageSize + Characters.COUNT * Characters.SIZE, 128 * 0x28)
        if (bad.isNotEmpty()) fail("${bad.size} 段不同：\n" + bad.take(30).joinToString("\n"))
    }
}
