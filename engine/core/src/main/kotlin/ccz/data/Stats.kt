package ccz.data

import ccz.script.Attributes
import ccz.script.EngineMemory

/**
 * 武將能力值：成長、基礎值公式、取值／設定／增加、能力修正紀錄。全部直接讀寫 EngineMemory 的武將資料（原版格式）。
 * 規則見 docs/battle.md「能力值」；函式位址寫在各函式上。用 tools/re/gen_stats_vectors.py 跟原版比對（StatsTest）。
 *
 * 能力項目（0x38 武将能力设定 的「能力項目」）：0–4 攻擊、防禦、精神、爆發、士氣，5 HP，6 MP，7/8 目前 HP/MP（戰場單位），
 * 9 等級，10–14 武力、統率、智力、敏捷、運氣，15 頭像。
 */
object Stats {
    /** 每名武將的成長修正表（MOD，6.6 是空的）：0xFF 項目 {值 u16武將}* … 0xFF 0xFF（0x436F16）。 */
    const val GROWTH_ADJUST = 0x507EC0L
    /** 能力修正紀錄：武將 n（< 0x66）在 +14n，項目 0–6 各一個 word（0x408B52）。 */
    const val BONUS_LOG = 0x508400L
    const val BONUS_LOG_LIMIT = 0x66
    /** 0x406ECA／0x407027 處理目前 HP/MP 時用的旗標，結束時一律清 0。 */
    const val STAT_BUSY = 0x505F30L

    private fun rec(mem: EngineMemory, n: Int) = Characters.addr(mem, n)

    /** 0x47FA89：min(a + b, cap)，有號比較。 */
    fun addCap(a: Int, b: Int, cap: Int): Int = (a + b).let { if (it > cap) cap else it }

    /** 0x47FA9E：a > b ? a − b : 0（無號）。 */
    fun satSub(a: Long, b: Long): Long = if ((b and 0xffffffffL) < (a and 0xffffffffL)) (a - b) and 0xffffffffL else 0

    /** 0x436E3D：value + 成長修正表裡（武將, 項目）的值（負數會 +1，照原版）。 */
    fun growthAdjust(mem: EngineMemory, character: Int, stat: Int, value: Int): Int {
        var p = GROWTH_ADJUST
        while (true) {
            if (mem.u8(p) == 0xFF) {
                p++
                if (mem.u8(p) == 0xFF) return value
                if (mem.u8(p) == (stat and 0xff)) {
                    p++
                    while (mem.u8(p) != 0xFF) {
                        if (mem.u16(p + 1) == (character and 0xffff)) {
                            val v = mem.u8(p).toByte().toInt()
                            return (if (v < 0) v + 1 else v) + value
                        }
                        p += 3
                    }
                    return value
                }
            }
            p++
        }
    }

    /** 0x4072F2：五維換成等第（≥99 → 10、≥90 → 8、≥70 → 6、≥50 → 4、其他 2），再加成長修正。項目 > 4 是 0。 */
    fun rank(mem: EngineMemory, character: Int, stat: Int): Int {
        val r = if (stat in 0..4) {
            val b = mem.u8(rec(mem, character) + 0x21 + stat)
            when {
                b >= 0x63 -> 10
                b >= 0x5A -> 8
                b >= 0x46 -> 6
                b >= 0x32 -> 4
                else -> 2
            }
        } else 0
        return growthAdjust(mem, character, stat, r)
    }

    /** 0x4250B6：兵種 [unitClass] 的項目 [stat] 每級成長。項目 0–4 = (等第 + 兵種值) / 2（負數當 0），其他 = 兵種值。 */
    fun growth(mem: EngineMemory, unitClass: Int, stat: Int, character: Int): Int {
        val g = growthAdjust(mem, character, stat, mem.u8(NewGame.CLASSES + 0x23L * (unitClass and 0xff) + 2 + stat))
        if ((stat and 0xff) > 4) return g
        val e = rank(mem, character, stat) + g
        return (if (e < 0) 0 else e) ushr 1
    }

    /**
     * 0x408A60：項目 [stat] 的等級成長總和。兵種 ≤ 59 時依轉職階段（兵種 % 3）把前段等級算在低階兵種：
     * 第 2 階且等級 ≥ 30：15 級算兵種 −1；接著（第 1、2 階）等級 ≥ 15：15 級算兵種 −1（第 2 階已扣過時是 −2）；剩下的算現在的兵種。
     */
    fun levelGrowth(mem: EngineMemory, character: Int, stat: Int): Int {
        val r = rec(mem, character)
        val cls = mem.u8(r + 0x2B)
        var level = mem.u8(r + 0x2C).toLong()
        var lower = cls
        var acc = 0
        if (cls <= 0x3B) {
            val tier = cls % 3
            if (tier != 0) {
                if (tier == 2 && level >= 30) {
                    lower = (lower - 1) and 0xff
                    acc += growth(mem, lower, stat, character) * 15
                    level = satSub(level, 15)
                }
                if (level >= 15) {
                    lower = (lower - 1) and 0xff
                    acc += growth(mem, lower, stat, character) * 15
                    level = satSub(level, 15)
                }
            }
        }
        return acc + growth(mem, cls, stat, character) * level.toInt()
    }

    /** 0x422E9D：取值。0–4 word、5 HP dword、6 MP word、9 等級、10–14 五維；其他 0。 */
    fun get(mem: EngineMemory, character: Int, stat: Int): Long {
        val r = rec(mem, character)
        return when (stat) {
            in 0..4 -> mem.u16(r + 0x11 + 2 * stat).toLong()
            5 -> mem.u32(r + 0x1B)
            6 -> mem.u16(r + 0x1F).toLong()
            9 -> mem.u8(r + 0x2C).toLong()
            in 10..14 -> mem.u8(r + 0x17 + stat).toLong()
            else -> 0
        }
    }

    /** 0x407027：設定（有上限）。回傳 false = 目前 HP/MP（戰場單位，0x45AA37）還沒移植。 */
    fun set(mem: EngineMemory, character: Int, stat: Int, value: Long): Boolean {
        val r = rec(mem, character)
        val v16 = value and 0xffff
        when (stat) {
            in 0..4 -> mem.setU16(r + 0x11 + 2 * stat, if (v16 >= 9999) 9999 else v16)
            5 -> mem.setU32(r + 0x1B, if ((value and 0xffffffffL) >= 0xffff) 0xffff else value)
            6 -> mem.setU16(r + 0x1F, if (v16 >= 9999) 9999 else v16)
            7, 8 -> return false
            in 10..14 -> mem.setU8(r + 0x17 + stat, if ((value and 0xffffffffL) >= 100) 100 else (value and 0xff).toInt())
            15 -> {
                val f = if (v16 >= 0xffff) 0xffff else v16
                mem.setU16(r + 4, f)
                mem.setU16(Attributes.FACES + 2L * character, f) // 0x423E30
            }
        }
        return true
    }

    /** 0x406ECA：增加（有上限，可以是負數）。fromZero = 從 0 開始加（只對 0–4 有效）。回傳 false = 還沒移植的路徑。 */
    fun add(mem: EngineMemory, character: Int, stat: Int, value: Int, fromZero: Boolean = false): Boolean {
        val r = rec(mem, character)
        var ok = true
        when (stat) {
            in 0..4 -> {
                val a = r + 0x11 + 2 * stat
                mem.setU16(a, (addCap(if (fromZero) 0 else mem.u16(a), value, 9999).toLong() and 0xffff))
            }
            5 -> mem.setU32(r + 0x1B, addCap(mem.u32(r + 0x1B).toInt(), value, 9999).toLong() and 0xffffffffL)
            6 -> mem.setU16(r + 0x1F, addCap(mem.u16(r + 0x1F), value, 9999).toLong() and 0xffff)
            7, 8 -> ok = false
            in 10..14 -> mem.setU8(r + 0x17 + stat, addCap(mem.u8(r + 0x17 + stat), value, 100) and 0xff)
            15 -> ok = false // 戰場上改 0x501A84 + 單位（0x477ADC）；不在戰場時什麼都不做，引擎還沒有遊戲狀態
        }
        mem.setU8(STAT_BUSY, 0)
        return ok
    }

    /**
     * 0x408B52：能力修正紀錄（只記 0x66 號以前、我軍標識 ≠ 0xFF 的武將，項目 0–6）。在改能力之前呼叫。
     * op 0（設定）：紀錄 = 新值 −（0–4：等級成長 + 五維；5/6：目前值 − 紀錄）。
     * op 1（增加）：我軍標識 ≠ 0 時紀錄 = 值，否則 += 值。
     * 其他（減少）：值先限制在目前值以內；我軍標識 ≠ 0 時紀錄 = −值，否則 −= 值。
     */
    fun logBonus(mem: EngineMemory, character: Int, value: Long, op: Int, stat: Int) {
        if ((character and 0xffff) >= BONUS_LOG_LIMIT) return
        val r = rec(mem, character)
        val flag = mem.u8(r + 0x2A)
        if (flag == 0xFF) return
        val slot = BONUS_LOG + 0xEL * character + 2 * stat
        var v = value.toInt()
        when (op) {
            0 -> {
                val minus = when (stat) {
                    5 -> mem.u16(r + 0x1B) - mem.u16(slot)
                    6 -> mem.u16(r + 0x1F) - mem.u16(slot)
                    else -> levelGrowth(mem, character, stat) + mem.u8(r + 0x21 + stat)
                }
                mem.setU16(slot, (v - minus).toLong() and 0xffff)
            }
            1 -> mem.setU16(slot, (if (flag != 0) v else mem.u16(slot) + v).toLong() and 0xffff)
            else -> {
                val cur = get(mem, character, stat).toInt()
                if (cur < v) v = cur
                mem.setU16(slot, (if (flag != 0) -v else mem.u16(slot) - v).toLong() and 0xffff)
            }
        }
    }

    /**
     * 0x38 武将能力设定（0x412007）：(武將, 項目, 運算 0 = 1 + 2 −, 值)。回傳 false = 遇到還沒移植的路徑。
     * 武將 0xFFFF、> 0x400 或項目 > 15 時什麼都不做；0x400–0x402 在戰場上代表目前行動的單位（還沒移植）。
     */
    fun op38(mem: EngineMemory, character: Int, stat: Int, op: Int, value: Long): Boolean {
        if (character in 0x400..0x402) return false
        if (character == 0xFFFF || character > 0x400 || (stat and 0xff) > 15) return true
        if (stat <= 6) logBonus(mem, character, value, op, stat)
        return when (op) {
            0 -> set(mem, character, stat, value)
            1 -> add(mem, character, stat, value.toInt())
            2 -> if (stat == 7 || stat == 8) false else set(mem, character, stat, satSub(get(mem, character, stat), value))
            else -> true
        }
    }

    /** 0x41055D：重算用的修正值。0x66 號以前、我軍標識 == 0 的武將取修正紀錄（i16），其他 0。 */
    fun bonus(mem: EngineMemory, character: Int, stat: Int): Int {
        if ((character and 0xffff) >= BONUS_LOG_LIMIT) return 0
        if (mem.u8(rec(mem, character) + 0x2A) != 0) return 0
        return mem.u16(BONUS_LOG + 0xEL * character + 2 * stat).toShort().toInt()
    }

    /** 0x413FAC：HP/MP 的轉職加成。不是我軍（標識 0xFF）或兵種 > 59 時 0；否則 2 × 階段 × 兵種值（含成長修正）。 */
    fun promotionBonus(mem: EngineMemory, character: Int, stat: Int): Int {
        val r = rec(mem, character)
        if (mem.u8(r + 0x2A) == 0xFF) return 0
        val cls = mem.u8(r + 0x2B)
        if (cls > 0x3B) return 0
        val g = growthAdjust(mem, character, stat, mem.u8(NewGame.CLASSES + 0x23L * cls + 2 + stat))
        return g * (cls % 3) * 2
    }

    /**
     * 0x4149CF：依兵種、等級重算能力（0x52 兵种改变 在整數變數 4081 的低位 byte ≠ 0 時呼叫）。
     * 攻擊～士氣 = 等級成長 + 修正 + 五維；HP、MP = 等級成長 + 修正 + 轉職加成 + Data.e5 原始值（只寫低 16 位）。
     */
    fun recompute(mem: EngineMemory, character: Int, rawCharacters: ByteArray) {
        val r = rec(mem, character)
        val raw = character * 32
        for (stat in 0 until 7) {
            val base = levelGrowth(mem, character, stat) + bonus(mem, character, stat)
            if (stat < 5) {
                mem.setU16(r + 0x11 + 2 * stat, (base + mem.u8(r + 0x21 + stat)).toLong() and 0xffff)
            } else {
                val t = (base and 0xffff) + promotionBonus(mem, character, stat)
                if (stat == 5) {
                    val hp = (rawCharacters[raw + 0x17].toInt() and 0xff) or ((rawCharacters[raw + 0x18].toInt() and 0xff) shl 8)
                    mem.setU16(r + 0x1B, (t + hp).toLong() and 0xffff)
                } else {
                    mem.setU16(r + 0x1F, (t + (rawCharacters[raw + 0x19].toInt() and 0xff)).toLong() and 0xffff)
                }
            }
        }
    }
}
