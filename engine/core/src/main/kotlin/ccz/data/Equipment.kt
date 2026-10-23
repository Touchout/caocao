package ccz.data

import ccz.script.EngineMemory

/**
 * 道具資料（0x49F820 + 25·編號，原版格式）與裝備對能力的加成。規則見 docs/battle.md「HP/MP 上限與裝備加成」。
 * 用 tools/re/gen_hpmp_vectors.py 跟原版比對（HpMpTest）。
 *
 * 武將的三件裝備：+0x2E + 3·k 道具編號（0xFF = 沒有）、+0x2F + 3·k 等級，k = 0 武器、1 防具、2 輔助。
 */
object Equipment {
    const val ITEMS = 0x49F820L
    const val ITEM_SIZE = 25
    /** 道具 +0x11 種類 < 0x1A 時，種類 / 2 → 加成的能力項目（0x4067CE 的表）。 */
    private const val KIND_STAT = 0x40684FL
    /** 道具 +0x14 − 0x20 → 能力項目（0x40678E 的表）。 */
    private const val EFFECT_STAT = 0x4067C5L
    /** MOD 的表（exe .api 區段的靜態資料），索引 = 道具 +0x14 − 0x1A：等級 ≥ 10 時的固定值（0xFE = 不用）。 */
    private const val LEVEL10_VALUE = 0x4CB5CAL
    /** 同上索引：< 7 時等級 ≥ 7 的加成 × 1.5。 */
    private const val EFFECT_CLASS = 0x4CB6BAL
    /** 能力項目 0–6：值 2 = 裝備加成是百分比（0x452825）。 */
    private const val PERCENT_STAT = 0x4CB6C0L
    /** 整數變數 4046 的低位 byte ≠ 0：HP/MP 依 +0x35 加成（MOD 選項，0x405B86）。 */
    private const val VAR_4046 = 4046

    fun item(id: Int): Long = ITEMS + ITEM_SIZE.toLong() * id

    /** 0x4067CE：道具 +0x11（種類）加成的能力項目；0xFF = 沒有。 */
    fun kindStat(mem: EngineMemory, id: Int): Int {
        val k = mem.u8(item(id) + 0x11)
        return when {
            k == 0x5C -> 9
            k < 0x1A -> mem.u8(KIND_STAT + (k shr 1))
            k in 0x20..0x26 -> k - 0x20
            k == 0x27 -> 8
            k == 0x28 -> 7
            k == 0xFE -> 10
            else -> 0xFF
        }
    }

    /** 0x40678E：道具 +0x14（特效）加成的能力項目；0xFF = 沒有。 */
    fun effectStat(mem: EngineMemory, id: Int): Int {
        val e = mem.u8(item(id) + 0x14)
        return when {
            e == 0x5C -> 9
            e == 0xFE -> 10
            ((e - 0x20) and 0xff) <= 8 -> mem.u8(EFFECT_STAT + ((e - 0x20) and 0xff))
            else -> 0xFF
        }
    }

    /** 0x40C45A（0x40B444）：會隨等級成長的道具：種類 < 0x1A，或 +0x14 ≤ 1。 */
    fun levels(mem: EngineMemory, id: Int): Boolean {
        val k = mem.u8(item(id) + 0x11)
        val v = when {
            k < 0x14 -> 0
            k < 0x1A -> 1
            else -> mem.u8(item(id) + 0x14)
        }
        return v <= 1
    }

    /** 0x40B4E1：會成長而且種類是奇數。 */
    private fun levelsOdd(mem: EngineMemory, id: Int) = levels(mem, id) && mem.u8(item(id) + 0x11) % 2 == 1

    /** 0x40B3D7：道具在等級 [level] 的數值 = +0x15 + 會成長時 +0x17 × (等級 − 1)（等級 − 1 取低位 byte）。 */
    fun value(mem: EngineMemory, id: Int, level: Int): Long {
        var s = mem.u8(item(id) + 0x15).toLong()
        if (levels(mem, id)) {
            val inc = mem.u8(item(id) + 0x17)
            s += inc.toLong() * ((level - 1) and 0xff)
        }
        return s and 0xffffffffL
    }

    /** 0x405670 的反面：能力項目 [stat] 吃不吃這件裝備的數值（種類項目 10 算攻擊與精神）。 */
    private fun applies(mem: EngineMemory, stat: Int, id: Int): Boolean {
        val t = kindStat(mem, id)
        return if (t == 10 && (stat == 0 || stat == 2)) true else t == stat
    }

    /** 0x4200F5：特效數值 +0x16 依特效 +0x14 與等級調整。 */
    fun effectValue(mem: EngineMemory, id: Int, level: Int): Long {
        var x = mem.u8(item(id) + 0x16).toLong()
        val e = mem.u8(item(id) + 0x14)
        val lv = level and 0xff
        when {
            e == 0x1F -> x = (x * (lv + 1)) and 0xffffffffL
            lv < 7 -> return x
            e == 0x72 -> x = ((x * 3) and 0xffffffffL) ushr 2
            e == 0x33 -> return if (x and 0xff == 4L) x + 3 else x
            e == 0x32 -> return (x shl 1) and 0xffffffffL
            e == 0x36 || e == 0x42 || e == 0x46 -> return x + 1
            mem.u8(EFFECT_CLASS + ((e - 0x1A) and 0xff)) < 7 -> x = ((x * 3) and 0xffffffffL) ushr 1
        }
        if (lv >= 10) {
            val b = mem.u8(LEVEL10_VALUE + ((e - 0x1A) and 0xff))
            if (b != 0xFE) return b.toLong()
        }
        return x
    }

    /** 0x452825（項目 5、6）：表 0x4CB6C0 標成百分比時 = 基礎值 × v / 100（32 位元），否則 v。 */
    private fun scaled(mem: EngineMemory, character: Int, stat: Int, v: Long): Long {
        if (stat >= 7 || mem.u8(PERCENT_STAT + stat) != 2) return v
        val r = Characters.addr(mem, character)
        val base = when (stat) {
            5 -> mem.u32(r + 0x1B)
            6 -> mem.u16(r + 0x1F).toLong()
            else -> return v // 項目 0–4 走 0x436E02，還沒移植（呼叫端只用 5、6）
        }
        return ((base * v) and 0xffffffffL) / 100
    }

    /** 0x407C29：裝備給能力項目 [stat]（目前只用 5 HP、6 MP）的加成。 */
    fun bonus(mem: EngineMemory, character: Int, stat: Int): Long {
        val r = Characters.addr(mem, character)
        var sum = 0L
        for (k in 0..1) {
            val id = mem.u8(r + 0x2E + 3 * k)
            if (id == 0xFF) continue
            val level = mem.u8(r + 0x2F + 3 * k)
            if (applies(mem, stat, id)) sum += value(mem, id, level)
            if (levelsOdd(mem, id) && effectStat(mem, id) == stat) sum += scaled(mem, character, stat, effectValue(mem, id, level))
        }
        val aux = mem.u8(r + 0x34)
        if (aux != 0xFF && kindStat(mem, aux) == stat) sum += scaled(mem, character, stat, mem.u8(item(aux) + 0x15).toLong())
        return sum and 0xffffffffL
    }

    /** 0x405B86(15, 基礎值)：變數 4046 的低位 byte ≠ 0 時 基礎值 + 基礎值 × (+0x35 / 15) / 100。 */
    private fun withMod(mem: EngineMemory, character: Int, base: Long): Long {
        if (mem.u8(mem.intVarAddr(VAR_4046)) == 0) return base
        val q = mem.u16(Characters.addr(mem, character) + 0x35).toLong() / 15
        return (base + ((q * base) and 0xffffffffL) / 100) and 0xffffffffL
    }

    /** 0x407263：HP 上限 = min(裝備加成 + 基礎 HP（+0x1B 的低 16 位）, 0xFFFF)（有號比較）。 */
    fun maxHp(mem: EngineMemory, character: Int): Long =
        capSigned(bonus(mem, character, 5) + withMod(mem, character, mem.u16(Characters.addr(mem, character) + 0x1B).toLong()), 0xFFFF)

    /** 0x40728F：MP 上限 = min(裝備加成 + 基礎 MP（+0x1F word）, 9999)（有號比較）。 */
    fun maxMp(mem: EngineMemory, character: Int): Long =
        capSigned(bonus(mem, character, 6) + withMod(mem, character, mem.u16(Characters.addr(mem, character) + 0x1F).toLong()), 9999)

    /** 0x47FA89 的 32 位元版本：和超過 cap（有號）時取 cap。 */
    private fun capSigned(sum: Long, cap: Int): Long {
        val s = sum.toInt()
        return (if (s > cap) cap else s).toLong() and 0xffffffffL
    }

    // ---- 換裝 ----

    /** 自動配裝：種類 / 2 → 最低階道具編號（0x482427）。 */
    private const val AUTO_ITEMS = 0x482427L
    /** 裝備限定兵種組的表（0x44C341）：{0xFF 道具+0x17 值 {組}*}* 0xFF 0xFF。 */
    private const val GROUP_LIMITS = 0x44C341L

    /**
     * 0x407FD4(裝備欄, 降級)：依武將等級 L 自動配武器（欄 0）或防具（欄 1），其他欄不動。
     * 階 = min(L / 20, 2)、裝備等級 = L ≥ 60 ? 5 : (L % 20) / 4 + 1；[lower]（換兵種時 0x414B93 用）再降一級：
     * 等級 − 1，降到 0 時階 0 → 等級 1，否則等級 5、階 − 1。
     * 種類 = 兵種表 +9 起第一個可以用的（武器 0–19、防具 20–25），道具 = 表 0x482427[種類 / 2] + 階；都不能用時 0xFF。
     * 經驗 = 等級 ≥ 5 ? 0xFF : 0。
     */
    fun autoEquip(mem: EngineMemory, character: Int, kind: Int, lower: Boolean) {
        val c = Characters.addr(mem, character)
        val lvl = mem.u8(c + 0x2C)
        var tier = minOf(lvl / 20, 2)
        var elv = if (lvl >= 60) 5 else (lvl % 20) / 4 + 1
        if (lower) {
            elv = if (1 >= elv) 0 else elv - 1          // 0x47FAD0
            if (elv < 1) {
                if (tier < 1) { elv = 1; tier = 0 } else { elv = 5; tier = if (1 >= tier) 0 else tier - 1 }
            }
        }
        val row = NewGame.CLASSES + 0x23L * mem.u8(c + 0x2B)
        val range = when (kind) { 0 -> 0 until 0x14; 1 -> 0x14 until 0x1A; else -> return }
        val i = range.firstOrNull { mem.u8(row + 9 + it) != 0 }
        val id = if (i == null) 0xFF else (mem.u8(AUTO_ITEMS + (i shr 1)) + tier) and 0xff
        mem.setU8(c + 0x2E + 3 * kind, id)
        mem.setU8(c + 0x2F + 3 * kind, elv)
        mem.setU8(c + 0x30 + 3 * kind, if (elv >= 5) 0xFF else 0)
    }

    /**
     * 0x407961：武將目前的兵種能不能用道具 [id]（0xFF = 沒裝備，算可以）。
     * 類別（0x40B444）3 不行；2 看限定兵種組表（+0x17 = 0xFF 或等於兵種組時可以，0x420E83）；其他看兵種表 +9 + 種類。
     */
    fun canEquip(mem: EngineMemory, character: Int, id: Int): Boolean {
        if (id == 0xFF) return true
        val c = Characters.addr(mem, character)
        val ip = item(id)
        val k = mem.u8(ip + 0x11)
        val category = when { k < 0x14 -> 0; k < 0x1A -> 1; else -> mem.u8(ip + 0x14) }
        if (category == 3) return false
        if (category == 2) {
            val limit = mem.u8(ip + 0x17)
            val cls = mem.u8(c + 0x2B)
            val group = when { cls <= 0x3B -> cls / 3; cls < 0x50 -> cls - 0x28; else -> 0x28 }
            if (limit == 0xFF || limit == group) return true
            var p = GROUP_LIMITS
            while (true) {
                while (mem.u8(p) != 0xFF) p++
                p++
                if (mem.u8(p) == 0xFF) return false
                if (mem.u8(p) != limit) continue
                while (true) {
                    p++
                    if (mem.u8(p) == group) return true
                    if (mem.u8(p) == 0xFF) return false
                }
            }
        }
        return mem.u8(NewGame.CLASSES + 0x23L * mem.u8(c + 0x2B) + 9 + k) != 0
    }

    /** 0x414B93：換兵種後，三件裝備裡不能用的改成自動配裝（降一級；輔助不動）。 */
    fun checkAfterClassChange(mem: EngineMemory, character: Int) {
        val c = Characters.addr(mem, character)
        for (kind in 0 until 3) {
            if (!canEquip(mem, character, mem.u8(c + 0x2E + 3 * kind))) autoEquip(mem, character, kind, true)
        }
    }
}
