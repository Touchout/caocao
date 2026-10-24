package ccz.battle

import ccz.data.Equipment
import ccz.data.Stats
import ccz.script.EngineMemory

/**
 * 戰場單位表（0x4A7B20，250 × 0x30）上不需要地圖的操作：找單位、改單位狀態（0x45AA37）、健康狀態。
 * 劇本指令（BattleField）和 0x38 武将能力设定 的目前 HP/MP（Stats）共用。規則見 docs/battle.md「劇本對單位的操作」。
 */
object Units {
    const val UNITS = 0x4A7B20L
    const val SIZE = 0x30
    const val SLOTS = 250
    /** 健康狀態 4 組設定的值（0x4240CE）與遮罩（0x4240D2）。 */
    private const val HEALTH_SET = 0x4240CEL
    private const val HEALTH_MASKS = 0x4240D2L

    fun unit(slot: Int): Long = UNITS + SIZE.toLong() * slot

    /**
     * 0x41B882：武將參數 → 單位。≥ 0x404 = 格號 − 0x404（空格回傳 -1）；[0x505EFC] ≠ 0 且 < 0xFA 時也當格號；
     * 其他回傳 0（呼叫端再用武將編號找）。找到時回傳格號 + 1。
     */
    fun unitParam(mem: EngineMemory, ch: Int): Int {
        val slot = when {
            ch >= 0x404 -> ch - 0x404
            mem.u8(0x505EFCL) != 0 && (ch and 0xffff) < 0xFA -> ch
            else -> return 0
        }
        if (slot !in 0 until SLOTS) return -1
        return if (mem.u16(unit(slot)) == 0xFFFF) -1 else slot + 1
    }

    /** 0x456079：第一個武將 = ch 而且狀態 ≠ 0 的單位格號，沒有 -1。 */
    fun liveUnitOf(mem: EngineMemory, ch: Int): Int =
        (0 until SLOTS).firstOrNull { mem.u32(unit(it)) == (ch.toLong() and 0xffffffffL) && mem.u8(unit(it) + 0x0C) != 0 } ?: -1

    /** 0x4029C2／0x43E4D6：第一個武將 = ch 的單位格號（不看狀態），沒有 -1。 */
    fun anyUnitOf(mem: EngineMemory, ch: Int): Int =
        (0 until SLOTS).firstOrNull { mem.u32(unit(it)) == (ch.toLong() and 0xffffffffL) } ?: -1

    /** 0x41B882，找不到時用 [fallback]。回傳格號，-1 = 沒有。 */
    fun findUnit(mem: EngineMemory, ch: Int, fallback: (Int) -> Int): Int {
        val r = unitParam(mem, ch)
        return if (r != 0) r - 1 else fallback(ch)
    }

    /**
     * 0x44CB12(篩選, 單位)：陣營 +5（> 6 當 0；2 依格號：< 20 → 0、< 60 → 1）符合篩選：0–3 相同、4 我方（< 2）、
     * 5 敵方（2、3）、6 全部。
     */
    fun countMatch(mem: EngineMemory, filter: Int, slot: Int): Boolean {
        var v = mem.u8(unit(slot) + 5).let { if (it <= 6) it else 0 }
        if (v == 2) v = when { slot < 0x14 -> 0; slot < 0x3C -> 1; else -> 2 }
        return when (val f = filter and 0xff) {
            in 0..3 -> v == f
            4 -> v < 2
            5 -> v == 2 || v == 3
            6 -> true
            else -> false
        }
    }

    /** 0x423FB9：健康狀態（+0x1E，4 組 2 bits）裡 v 的 bit 1–4 指定的組清除。 */
    fun clearHealth(mem: EngineMemory, u: Long, v: Int) {
        var mask = 0
        var a = v
        for (i in 0 until 4) {
            a = a ushr 1
            if (a and 1 == 0) mask = mask or mem.u8(HEALTH_MASKS + i)
        }
        mem.setU8(u + 0x1E, mem.u8(u + 0x1E) and mask)
    }

    /** 0x4240D6：健康狀態裡 v 的 bit 1–4 指定的組設成「有」（表 0x4240CE）。 */
    fun setHealth(mem: EngineMemory, u: Long, v: Int) {
        var bits = 0
        var a = v
        for (i in 0 until 4) {
            a = a ushr 1
            if (a and 1 != 0) bits = bits or mem.u8(HEALTH_SET + i)
        }
        mem.setU8(u + 0x1E, mem.u8(u + 0x1E) or bits)
    }

    /**
     * 0x45AA37(武將, 項目, 值, 動畫, 格)：改戰場單位的狀態（單位要在地圖上）。項目 0–5 = +0x18 起的能力／移動狀態；
     * 6 = 健康狀態（值 bit 0x80 → 清除，否則設定）；7/8 = 目前 HP/MP，依 [0x505F30]：0 減少（最少 1）、1 增加、其他設定，
     * 再限制在上限內。回傳 false = 項目 > 8（原版跳到表外，還沒處理）。
     */
    fun setStat(mem: EngineMemory, ch: Int, kind: Int, value: Int, slotParam: Int): Boolean {
        val slot: Int
        if (slotParam != 0) {
            if (mem.u16(unit(slotParam)) == 0xFFFF) return true
            slot = slotParam
        } else {
            val r = unitParam(mem, ch)
            slot = when {
                r < 0 -> return true
                r > 0 -> r - 1
                ch > 0x400 -> return true
                else -> anyUnitOf(mem, ch).takeIf { it >= 0 } ?: return true
            }
        }
        val u = unit(slot)
        if (mem.u8(u + 0x0C) != 2) return true
        val c = mem.u32(u).toInt()
        when (kind and 0xff) {
            in 0..5 -> mem.setU8(u + 0x18 + (kind and 0xff), value)
            6 -> if (value and 0x80 != 0) clearHealth(mem, u, value) else setHealth(mem, u, value)
            7 -> mem.setU32(u + 0x10, minOf(hpOp(mem, mem.u32(u + 0x10), value.toLong() and 0xffffffffL), Equipment.maxHp(mem, c)))
            8 -> mem.setU32(u + 0x14, minOf(hpOp(mem, mem.u16(u + 0x14).toLong(), value.toLong() and 0xffff), Equipment.maxMp(mem, c)))
            else -> return false
        }
        return true
    }

    /** 0x40DA4A：依 [0x505F30] 0 減少（最少 1）、1 增加、其他 = 設定。 */
    private fun hpOp(mem: EngineMemory, cur: Long, v: Long): Long = when (mem.u32(Stats.STAT_BUSY)) {
        0L -> if (cur > v) cur - v else 1
        1L -> (cur + v) and 0xffffffffL
        else -> v
    }

    /** 0x4036B0 的項目 7/8：武將第一個單位（0x43E4D6）的目前 HP（dword）／MP（word）；沒有單位時 0xFF。 */
    fun currentHpMp(mem: EngineMemory, ch: Int, stat: Int): Long {
        val s = anyUnitOf(mem, ch)
        if (s < 0) return 0xFF
        return if (stat == 7) mem.u32(unit(s) + 0x10) else mem.u16(unit(s) + 0x14).toLong()
    }
}
