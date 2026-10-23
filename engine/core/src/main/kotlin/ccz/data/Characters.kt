package ccz.data

import ccz.script.Attributes
import ccz.script.EngineMemory

/**
 * 武將執行期資料：1024 × 0x48 bytes，陣列指標在 0x4CEA00（原版 0x4061E4 取第 n 筆）。
 * 開新遊戲時由 Data.e5 第 0 項轉換而來（原版 0x41B666 → 0x408C0C），格式見 docs/battle.md「武將執行期資料」。
 * 放在 EngineMemory，位址與格式都跟原版一樣，劇本的指標讀寫、0x78 屬性讀寫才會一致。
 */
object Characters {
    const val PTR = 0x4CEA00L
    const val ARRAY = EngineMemory.HEAP + 0x100000
    const val SIZE = 0x48
    const val COUNT = 1024

    fun addr(mem: EngineMemory, n: Int): Long = mem.u32(PTR) + SIZE.toLong() * n

    /** 照原版 0x408C0C 把 Data.e5 的 32 bytes 記錄轉成執行期資料（開新遊戲時全部 1024 人，模式 0）。 */
    fun init(mem: EngineMemory, data: ByteArray) {
        mem.setU32(PTR, ARRAY)
        for (n in 0 until COUNT) load(mem, n, data, n * 32, 0)
    }

    /**
     * 0x408C0C：武將 [n] 從 Data.e5 記錄（[data] 的 [off] 起 32 bytes）重新載入。
     * 模式 0 = 頭像寫進頭像表；其他 = 頭像沿用頭像表（劇本改過的頭像保留，0x423E30）。開戰時敵軍用模式 1（0x46206C）。
     */
    fun load(mem: EngineMemory, n: Int, data: ByteArray, off: Int, mode: Int) {
        val r = addr(mem, n)
        fun src(i: Int) = data[off + i].toInt() and 0xff
        for (i in 0 until SIZE) mem.setU8(r + i, 0)
        mem.setU32(r, (n or 0x1000).toLong())
        for (i in 0 until 12) mem.setU8(r + 8 + i, src(i))
        nameSuffix(mem, r)
        val face = (src(0x0D) or (src(0x0E) shl 8)).toLong()
        mem.setU32(r + 4, face)
        if (mode == 0) mem.setU16(Attributes.FACES + 2L * n, face) // 0x423E30
        else mem.setU32(r + 4, mem.u16(Attributes.FACES + 2L * n).toLong())
        mem.setU8(r + 0x26, src(0x10))
        mem.setU8(r + 0x2A, src(0x11))
        for (i in 0 until 5) {
            mem.setU16(r + 0x11 + 2 * i, src(0x12 + i).toLong())
            mem.setU8(r + 0x21 + i, src(0x12 + i))
        }
        mem.setU32(r + 0x1B, (src(0x17) or (src(0x18) shl 8)).toLong())
        mem.setU8(r + 0x1F, src(0x19))
        for (i in 0 until 4) mem.setU8(r + 0x2B + i, src(0x1A + i))
        mem.setU8(r + 0x31, src(0x1E))
        mem.setU8(r + 0x34, src(0x1F))
        if (mem.u16(r + 0x1B) > 500) mem.setU16(r + 0x1B, 500)
        if (mem.u8(r + 0x1F) > 250) mem.setU8(r + 0x1F, 250)
        if (mem.u8(r + 0x2C) == 0) mem.setU8(r + 0x2C, 1)
    }

    /**
     * 0x40A72C：名字第 9 個 byte 起最多 5 個（遇到 0 或數字停）抄到 +0x3F 起，後面補 0。
     * 抄滿 5 個時原版的 0 寫到 +0x33（照做）。
     */
    private fun nameSuffix(mem: EngineMemory, r: Long) {
        var i = 0
        while (true) {
            val c = mem.u8(r + 0x10 + i)
            if (c == 0 || c in 0x30..0x39) { mem.setU8(r + 0x3F + i, 0); return }
            mem.setU8(r + 0x3F + i, c)
            if (++i > 4) { mem.setU8(r + 0x33, 0); return }
        }
    }
}
