package ccz.data

import ccz.io.GameFiles
import ccz.script.EngineMemory

/**
 * 開新遊戲時原版載入資料（0x41B639）後的記憶體，位址與格式都跟原版一樣。
 * 各段的格式見 docs/battle.md「載入後的記憶體」。`NewGameTest` 跟模擬器跑原版載入函式後的整個映像逐 byte 比對。
 */
object NewGame {
    const val ITEMS = 0x49F820L          // 道具 512 × 25（0x4837C2）
    const val ITEM_SIZE = 25
    const val ITEM_COUNT = 512
    const val STAR_ITEMS = 0x4A0248L     // Star.e5 開頭最多 0x27D8 bytes 放這裡（道具 104 起，0x451313）
    const val STAR_MAX = 0x27D8
    const val ITEM_FLAG_COUNT = 0x500ADAL // 前 255 個道具裡 +0x18 bit 0 有設的個數（0x427A98）
    const val CLASSES = 0x4A2A27L        // 兵種 80 × 0x23（0x41B782）
    const val TERRAIN = 0x4A3517L        // 地形 40 組 × 60（0x41B7D2）
    const val STRATEGIES = 0x4A3E77L     // 策略 144 × 0x61（0x484002）
    const val AFTER_STRATEGIES = 0x4A7510L // 策略後面的 0x610 bytes 原樣複製
    const val ENTRY2_PTR = 0x4CEA04L     // Data.e5 第 2 項：128 × 0x28（0x41B7E0）
    const val ENTRY2 = EngineMemory.HEAP + 0x200000

    fun init(mem: EngineMemory, files: GameFiles) {
        val data = files.archive("Data.e5")
        Characters.init(mem, data[0])
        loadItems(mem, data[1], files.bytesOrNull("Star.e5"))
        copy(mem, CLASSES, data[3], 80 * 0x23)
        copy(mem, TERRAIN, data[4], 40 * 60)
        loadEntry2(mem, data[2])
        loadStrategies(mem, data[5])
    }

    private fun copy(mem: EngineMemory, dest: Long, src: ByteArray, n: Int, from: Int = 0) {
        for (i in 0 until n) mem.setU8(dest + i, src[from + i].toInt() and 0xff)
    }

    /** 0x4010B0：第 1 項原樣放 ITEMS，Star.e5 接在後面；每個道具把 +0x12..0x14 輪換成 (+0x13, +0x14, +0x12)，+0x0E 清 0。 */
    private fun loadItems(mem: EngineMemory, entry: ByteArray, star: ByteArray?) {
        copy(mem, ITEMS, entry, entry.size)
        if (star != null) copy(mem, STAR_ITEMS, star, minOf(star.size, STAR_MAX))
        for (i in 0 until ITEM_COUNT) {
            val r = ITEMS + ITEM_SIZE.toLong() * i
            mem.setU8(r + 0x0E, 0)
            val b12 = mem.u8(r + 0x12)
            mem.setU8(r + 0x12, mem.u8(r + 0x13))
            mem.setU8(r + 0x13, mem.u8(r + 0x14))
            mem.setU8(r + 0x14, b12)
        }
        val n = (0 until 0xFF).count { mem.u8(ITEMS + ITEM_SIZE.toLong() * it + 0x18) and 1 != 0 }
        mem.setU8(ITEM_FLAG_COUNT, n)
    }

    /** 0x40D793：每筆來源 0x24 bytes → u32(u16 +0)、u32(u16 +2)、+4 起 0x20 bytes 原樣。 */
    private fun loadEntry2(mem: EngineMemory, src: ByteArray) {
        mem.setU32(ENTRY2_PTR, ENTRY2)
        for (i in 0 until 128) {
            val d = ENTRY2 + 0x28L * i
            val s = 0x24 * i
            mem.setU32(d, ((src[s].toInt() and 0xff) or ((src[s + 1].toInt() and 0xff) shl 8)).toLong())
            mem.setU32(d + 4, ((src[s + 2].toInt() and 0xff) or ((src[s + 3].toInt() and 0xff) shl 8)).toLong())
            copy(mem, d + 8, src, 0x20, s + 4)
        }
    }

    /**
     * 0x40D737：每筆 0x61 bytes。+0..9 名稱、+0A 固定 0、+0B..0E ← 來源 +0C..0F、+0F ← 來源 +0B、+10 ← 來源 +10、
     * +11 起 0x50 bytes 原樣。144 筆之後的 0x610 bytes 複製到 AFTER_STRATEGIES。
     */
    private fun loadStrategies(mem: EngineMemory, src: ByteArray) {
        for (i in 0 until 144) {
            val d = STRATEGIES + 0x61L * i
            val s = 0x61 * i
            copy(mem, d, src, 10, s)
            mem.setU8(d + 0x0A, 0)
            copy(mem, d + 0x0B, src, 4, s + 0x0C)
            mem.setU8(d + 0x0F, src[s + 0x0B].toInt() and 0xff)
            mem.setU8(d + 0x10, src[s + 0x10].toInt() and 0xff)
            copy(mem, d + 0x11, src, 0x50, s + 0x11)
        }
        copy(mem, AFTER_STRATEGIES, src, 0x610, 144 * 0x61)
    }
}
