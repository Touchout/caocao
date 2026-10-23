package ccz.script

import ccz.data.Characters
import ccz.data.Stats

/**
 * 0x78 整型变量赋值：整數變數 ↔ 武將屬性（原版 0x4202D8；屬性 → 位置的對照在 0x415418）。
 * 屬性編號見 tools/eex_types.json 型別 55；規則見 docs/script.md「0x78」。
 * 已用 tools/re/gen_attr_vectors.py 跟原版比對（AttrTest）。
 */
object Attributes {
    /** 單位表：250 格 × 0x30，[0] = 武將編號（空格 0xFFFF），原版 0x4061F9。 */
    const val UNITS = 0x4A7B20L
    const val UNIT_SIZE = 0x30
    const val SLOTS = 250
    /** R 形象表（MOD）：武將 n 在 +2n（u16）。 */
    const val R_IMAGES = 0x50F800L
    /** 頭像表：武將 n 在 +2n（u16），改頭像時同時寫（0x423E30）。 */
    const val FACES = 0x50F000L
    /** S 形象表：武將 n 在 +2n（u16）。 */
    const val S_IMAGES = 0x501000L

    private enum class Kind { CHARACTER, S_IMAGE, UNIT }

    /** 位置：種類、偏移、寬度（1/2/4 bytes）。 */
    private class Slot(val kind: Kind, val offset: Int, val width: Int)

    private val slots: List<Slot> = (0 until 42).map { a ->
        when {
            a == 0 -> Slot(Kind.CHARACTER, 2, 2)                     // 另外導到 R_IMAGES
            a == 1 -> Slot(Kind.CHARACTER, 4, 2)
            a <= 6 -> Slot(Kind.CHARACTER, 0x11 + (a - 2) * 2, 2)   // 攻擊～士氣
            a == 7 -> Slot(Kind.CHARACTER, 0x1B, 2)                   // HP
            a == 8 -> Slot(Kind.CHARACTER, 0x1F, 2)                   // MP
            a <= 13 -> Slot(Kind.CHARACTER, a + 0x18, 1)              // 武力～運氣
            a == 14 -> Slot(Kind.CHARACTER, 0x27, 1)
            a <= 26 -> Slot(Kind.CHARACTER, a + 0x1A, 1)              // 撤退場數～輔助
            a == 27 -> Slot(Kind.S_IMAGE, 0x20, 2)
            a == 28 -> Slot(Kind.UNIT, 4, 1)
            a <= 30 -> Slot(Kind.UNIT, a - 0x17, 1)                   // 縱、橫座標
            a == 31 -> Slot(Kind.CHARACTER, 0x35, 2)                  // 戰場行動標識（在武將資料裡）
            a == 32 -> Slot(Kind.UNIT, 0x0E, 1)
            a == 33 -> Slot(Kind.UNIT, 0x10, 4)
            a == 34 -> Slot(Kind.UNIT, 0x14, 4)
            else -> Slot(Kind.UNIT, a - 0x0B, 1)                      // 各種狀態
        }
    }

    /** 武將 [character] 所在的單位格子，不在戰場上回傳 -1（原版 0x43E4D6）。 */
    fun unitSlot(mem: EngineMemory, character: Int): Int =
        (0 until SLOTS).firstOrNull { mem.u32(UNITS + UNIT_SIZE.toLong() * it) == character.toLong() and 0xffffffffL } ?: -1

    /** 開機時的單位表：每格 [0] = 0xFFFF（建構函式 0x4012AF）。 */
    fun initUnits(mem: EngineMemory) {
        for (s in 0 until SLOTS) mem.setU32(UNITS + UNIT_SIZE.toLong() * s, 0xFFFF)
    }

    /** 屬性的記憶體位置；武將不在戰場上（單位屬性）回傳 null。 */
    private fun address(mem: EngineMemory, slot: Slot, character: Int): Long? = when (slot.kind) {
        Kind.CHARACTER -> if (slot.offset == 2) R_IMAGES + 2L * character else Characters.addr(mem, character) + slot.offset
        Kind.S_IMAGE -> S_IMAGES + 2L * character
        Kind.UNIT -> unitSlot(mem, character).takeIf { it >= 0 }?.let { UNITS + UNIT_SIZE.toLong() * it + slot.offset }
    }

    private fun read(mem: EngineMemory, a: Long, width: Int): Long = when (width) {
        1 -> mem.u8(a).toLong()
        2 -> mem.u16(a).toLong()
        else -> mem.u32(a)
    }

    fun get(mem: EngineMemory, attr: Int, character: Int): Long? =
        slots[attr].let { s -> address(mem, s, character)?.let { read(mem, it, s.width) } }

    /**
     * 執行 0x78：mode 0 = 變數 ← 屬性；mode 1 = 屬性 ← 變數。
     * 回傳 false 表示遇到還沒實作的原版行為（升級，0x4073F3）。
     */
    fun execute(mem: EngineMemory, attr: Int, character: Int, mode: Int, variable: Int): Boolean {
        val s = slots[attr]
        val a = address(mem, s, character) ?: return true
        val v = mem.u32(mem.intVarAddr(variable))
        if (mode == 0) {
            mem.setU32(mem.intVarAddr(variable), read(mem, a, s.width))
            return true
        }
        when (s.width) {
            1 -> {
                // 人物等級往上調：原版呼叫升級函式 0x4073F3（依兵種成長算能力值），還沒移植
                if (s.kind == Kind.CHARACTER && s.offset == 0x2C && (v and 0xff) > mem.u8(a)) return false
                mem.setU8(a, (v and 0xff).toInt())
            }
            2 -> {
                // 能力 0x11..0x1F（攻擊～士氣、HP、MP）：先記能力修正紀錄（0x408B52，視為「設定」）
                if (s.kind == Kind.CHARACTER && s.offset in 0x11..0x1F) {
                    val stat = (s.offset - 0x11) / 2
                    Stats.logBonus(mem, character, v, 0, if (stat >= 7) stat - 1 else stat)
                }
                if (s.kind == Kind.CHARACTER && s.offset == 4) mem.setU16(FACES + 2L * character, v and 0xffff)
                mem.setU16(a, v and 0xffff)
            }
            else -> mem.setU32(a, v)
        }
        return true
    }

}
