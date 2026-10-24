package ccz.battle

import ccz.script.EngineMemory

/**
 * 原版的亂數（狀態放在記憶體 [0x48ECD4]，跟原版一樣，存檔／比對時可以重現）與兩個用亂數的表。
 * 規則見 docs/battle.md「亂數」。
 */
class Rng(private val mem: EngineMemory) {
    /** 0x47FAE7：LCG，狀態 × 0x41C64E6D + 0x3039，回傳 bit 16–30。 */
    fun next(): Int {
        val s = (mem.u32(SEED) * 0x41C64E6DL + 0x3039L) and 0xFFFFFFFFL
        mem.setU32(SEED, s)
        return ((s and 0x7FFF0000L) ushr 16).toInt()
    }

    /** 0x47FB13：n < 2 時 0，否則 next() % n。 */
    fun below(n: Int): Int = if (n < 2) 0 else next() % n

    /** 0x47FB32：亂數 0–99 < [rate] 時成立。 */
    fun chance(rate: Int): Boolean = below(100) < (rate.toLong() and 0xFFFFFFFFL)

    /** 0x423490：表 [addr] 填 104 個 1–100 的亂數（我方階段開始時重洗 0x508998）。 */
    fun shuffle(addr: Long) {
        for (i in 0 until 0x68) mem.setU8(addr + i, below(100) + 1)
    }

    /** 0x455E25：讀序列表 0x508998 + 0x34·[kind] 的第 [counter] 格，計數器 0–0x33 循環。 */
    fun sequence(kind: Int, counter: Long): Int {
        val c = mem.u8(counter)
        mem.setU8(counter, if (c < 0x33) c + 1 else 0)
        return mem.u8(SEQUENCE + 0x34L * kind + c)
    }

    companion object {
        const val SEED = 0x48ECD4L
        const val SEQUENCE = 0x508998L
    }
}
