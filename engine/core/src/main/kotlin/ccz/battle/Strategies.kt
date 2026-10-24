package ccz.battle

import ccz.data.Characters
import ccz.data.Equipment
import ccz.script.EngineMemory

/**
 * 策略（計策）的判定：可用清單 0x442A26／0x465AC1、能不能施放 0x46FCF8、對目標有沒有效 0x42044D、目標合不合 0x4390EF、
 * 範圍 0x401DFF、波及 0x41A10C、策略傷害 0x43C01D、策略命中率 0x43B730。規則見 docs/battle.md「策略」。
 * 策略資料：0x4A3E77 + 0x61 × 編號（+0x0B 對象：0 敵、1 我、2 → 1；+0x0C 範圍型態；+0x0D 波及型態；+0x0E MP；
 * +0x0F 種類；+0x11 起每個兵種的習得等級）。
 */
class Strategies(private val bf: BattleField) {
    private val mem: EngineMemory get() = bf.mem
    private val effects get() = bf.effects
    private val combat get() = bf.combat

    private fun charPtr(u: Long) = Characters.addr(mem, mem.u16(u))
    private fun charId(cp: Long) = ((cp - mem.u32(Characters.PTR)) / Characters.SIZE).toInt()
    private fun pos(u: Long) = mem.u8(u + 6) or (mem.u8(u + 7) shl 8)

    fun record(s: Int): Long = RECORDS + 0x61L * (s and 0xff)
    /** 0x44270C：種類（+0x0F）。 */
    fun kind(s: Int) = mem.u8(record(s) + 0x0F)
    /** 0x423CB3：對象（+0x0B，2 當 1）。 */
    fun side(s: Int) = mem.u8(record(s) + 0x0B).let { if (it == 2) 1 else it }
    /** +0x0E：MP。 */
    fun cost(s: Int) = mem.u8(record(s) + 0x0E)

    /** 0x42518F：戰場狀態走 0x4101D9，否則只看武將。 */
    private fun effect(x: Long, e: Int, c: Int, mode: Int, presence: Int): Long =
        if (mem.u8(Effects.GAME_MODE) == 3) effects.check(x, e, c, mode, presence).eax else effects.character(x, e, c, mode, presence).eax

    // ---- 可用清單 ----

    /** 0x442A26(單位, 模式 ≠ 0, 清單)：0x465AC1 可用的策略依編號寫進 [out]（0xFF 結尾），回傳個數。 */
    fun build(u: Long, mode: Int, out: Long): Int {
        val ok = available(u, mode, 0)
        var n = 0
        for (i in 0 until 0x90) if (ok[i]) mem.setU8(out + n++, i)
        mem.setU8(out + n, 0xFF)
        return n
    }

    /**
     * 0x465AC1(單位, 結果, 模式, 旗標)：k = 0–7 各取特效 (0x49 + k, 0x6C) 的類別遮罩，策略在表 0x4D17B0 的類別符合 → 可用；
     * 否則只在 k = 0 時看：兵種習得等級（+0x11 + 兵種）≠ 0 而且等級夠（[0x501A6B] ≠ 0、模式 0 時不看等級）、
     * 或武將專屬表 0x507800（0x436F16）的等級夠。模式 > 1 時 MP 要夠；旗標 ≠ 0 時表 0x4D14E0 bit 0 要有。
     */
    fun available(u: Long, mode: Int, flag: Int): BooleanArray {
        val out = BooleanArray(0x90)
        val cp = charPtr(u)
        for (k in 0 until 8) {
            val mask = (effect(u, 0x49 + k, 0x6C, 3, 0) and 0xff).toInt()
            for (i in 0 until 0x90) {
                var checkMp = true
                if (!(mask != 0 && mem.u8(CATEGORY + i) and mask != 0)) {
                    if (k != 0) continue
                    val need = mem.u8(record(i) + 0x11 + mem.u8(cp + 0x2B))
                    var ok = false
                    if (need != 0) {
                        if (mem.u8(0x501A6BL) != 0 && mode == 0) { ok = true; checkMp = false }
                        else if (mem.u8(cp + 0x2C) >= need) ok = true
                    }
                    if (!ok && !personal(cp, i)) continue
                }
                if (checkMp && mode > 1 && mem.u32(u + 0x14) < cost(i)) continue
                if (flag != 0 && mem.u8(FLAGS + i) and 1 == 0) continue
                out[i] = true
            }
        }
        return out
    }

    /** 0x40DE69 → 0x436F16：表 0x507800（0xFF 策略 (等級 武將 word)… 0xFF 結尾）有這個武將而且等級夠。 */
    private fun personal(cp: Long, s: Int): Boolean {
        val ch = charId(cp)
        var p = PERSONAL
        while (true) {
            if (mem.u8(p) != 0xFF) { p++; continue }
            p++
            if (mem.u8(p) == 0xFF) return false
            if (mem.u8(p) == s) break
            p++
        }
        p++
        while (mem.u8(p) != 0xFF) {
            if (mem.u16(p + 1) == ch and 0xffff) return mem.u8(cp + 0x2C) >= mem.u8(p)
            p += 3
        }
        return false
    }

    // ---- 施放條件 ----

    /**
     * 0x4390EF(施放者, 目標, 策略)：能施放（0x46FCF8）、對目標有效（0x42044D），而且對象符合（0：不同方、1：同方）。
     */
    fun valid(caster: Int, target: Int, s: Int): Boolean {
        if (!castable(s, caster)) return false
        if (!applicable(s, caster, target)) return false
        val a = effects.sideCategory(Units.unit(caster))
        val t = effects.sideCategory(Units.unit(target))
        return when (mem.u8(record(s) + 0x0B)) {
            0 -> a != t
            1 -> a == t
            else -> true
        }
    }

    /**
     * 0x46FCF8(策略, 施放者, 0)：種類 0（火）：[0x505FDC] ≠ 0 而且 [0x500AF1] = 0 → 可以；否則有特效 (0x2A, 0x3A) 或天氣 < 2 才可以。
     * 種類 0x22／0x23：[0x501AE1] ≥ 10 → 不行；0x22 同火、0x23 要錢 ≥ 1000。0x14：表 0x4D1570 = 0x18 時 HP < 3 不行。
     * 0x19：0x41FF6E。其他可以。
     */
    fun castable(s: Int, caster: Int): Boolean {
        val u = Units.unit(caster)
        val cp = charPtr(u)
        val tbl = mem.u8(0x4D1570L + s)
        val k = kind(s)
        fun fire(): Boolean {
            if (mem.u8(0x505FDCL) != 0 && mem.u8(0x500AF1L) == 0) return true
            if (effects.value(u, 0x2A, 0x3A, 1, 1) != 0L) return true
            return weather() < 2
        }
        return when {
            k == 0 -> fire()
            k == 0x22 || k == 0x23 -> when {
                mem.u8(0x501AE1L) >= 10 -> false
                k == 0x22 -> fire()
                else -> mem.u32(0x4B077CL).toInt() >= 1000
            }
            k > 0x23 -> true
            k == 0x14 -> tbl != 0x18 || mem.u32(u + 0x10) >= 3
            k == 0x19 -> special(cp, u, tbl) > 0
            else -> true
        }
    }

    /**
     * 0x41FF6E(策略, 武將, 0, 單位, 表值)：特效 (0x50, 0x6C) ≥ 0x80 → 1；沒有裝備 0x3A → 0；有特效 (0x2A, 0x3A) → 1；
     * 表值 0x1A：天氣 ≥ 2、0：天氣 0、0x1C：天氣 1 才 1；其他 1。
     */
    private fun special(cp: Long, u: Long, tbl: Int): Int {
        if ((effect(u, 0x49 + 7, 0x6C, 3, 0) and 0xff) >= 0x80) return 1
        if (!effects.hasItemCode(cp, 0x3A)) return 0
        if (effects.value(u, 0x2A, 0x3A, 1, 1) != 0L) return 1
        val w = weather()
        return when (tbl) {
            0x1A -> if (w >= 2) 1 else 0
            0 -> if (w == 0) 1 else 0
            0x1C -> if (w == 1) 1 else 0
            else -> 1
        }
    }

    /** 0x41553B(0x4B3D08)：天氣 = 表 0x48E100[6 × [+0x11] + [+4]]。 */
    fun weather(): Int = mem.u8(0x48E100L + 6 * mem.u8(BattleTests.BATTLE + 0x11) + mem.u8(BattleTests.BATTLE + 4))

    /**
     * 0x42044D(策略, 施放者, 目標, 0)：0x41D90E 成立 → 有效。種類 ≤ 3：目標地形在表 0x420ACC 有對應的 bit 才有效。
     * 以下只限電腦方（+0x0E ≠ 7）：種類 0x0F–0x12：目標已有對應健康狀態 → 無效；0x16：自己 → 無效，
     * 目標 +0x0D 有 4 或 2 而且是我軍（+5 = 0）才有效；其他依表 0x420630 → 0x42060C 的情況（HP／MP 已滿、狀態已到極限…）。
     */
    fun applicable(s: Int, caster: Int, target: Int): Boolean {
        val a = Units.unit(caster)
        val ac = charPtr(a)
        val computer = mem.u8(a + 0x0E) != 7
        if (ignoreTerrain(a, s)) return true
        val t = Units.unit(target)
        val tc = charPtr(t)
        val k = kind(s)
        val cl = mem.u8(0x420630L + k)
        if (k <= 3) return mem.u8(0x420ACCL + bf.search.terrainIn(pos(t))) and (1 shl cl) != 0
        if (k in 0x0F..0x12) return !(computer && Units.health(mem, t, 2 shl cl) != 0)
        if (k == 0x16) {
            if (caster == target) return false
            if (!computer) return true
            val f = mem.u8(t + 0x0D)
            if (f and 4 != 4 && f and 2 != 2) return false
            return mem.u8(t + 5) != 0
        }
        if (!computer) return true
        return when (cl) {
            4 -> mem.u32(a + 0x10) != Equipment.maxHp(mem, charId(ac))
            5 -> mem.u16(a + 0x14).toLong() != Equipment.maxMp(mem, charId(ac))
            6 -> status(s, target) > 2
            7 -> mem.u32(t + 0x10) != Equipment.maxHp(mem, charId(tc))
            8 -> mem.u16(t + 0x14).toLong() != Equipment.maxMp(mem, charId(tc))
            9 -> mem.u8(t + 0x1E) != 0
            0x0A -> status(s, target) < 4
            0x0B -> status(0x35, target) < 4
            in 0..3 -> { bf.gapNote("0x42044D 表值 $cl 越界跳躍"); true }
            else -> true
        }
    }

    /** 0x41D90E(策略)：種類 > 0x18 → 成立；4–0x18 → 不成立；0–3：[0x505FDC] ≠ 0 或特效 (0x2B, 0x38)。 */
    private fun ignoreTerrain(a: Long, s: Int): Boolean {
        val k = kind(s)
        return when {
            k > 0x18 -> true
            k > 3 -> false
            mem.u8(0x505FDCL) != 0 -> true
            else -> effects.value(a, 0x2B, 0x38, 1, 1) != 0L
        }
    }

    /**
     * 0x420BD3(策略, 目標)：策略會改的能力狀態目前的值（攻 +0x18、防 +0x19、精 +0x1A、爆 +0x1B、士 +0x1C、移 +0x1D；
     * 0x1B／0x1C、0x34／0x35 依攻擊方式 0x4190E8 選精神或攻擊）。0x38–0x3B 原版在這裡會改狀態（照做）並回傳 3，其他 3。
     */
    fun status(s: Int, target: Int): Int {
        val t = Units.unit(target)
        val i = (s - 0x17) and 0xff
        if (i > 0x25) return 3
        return when (mem.u8(0x420C8EL + i)) {
            0, 5 -> mem.u8(t + 0x1B)
            1, 6 -> mem.u8(t + 0x1C)
            2, 7 -> if (archerLike(t)) mem.u8(t + 0x1A) else mem.u8(t + 0x18)
            3 -> mem.u8(t + 0x19)
            4 -> mem.u8(t + 0x1D)
            0x0A -> { if (target < 0x14) mem.setU8(0x501B00L + target, 3); 3 }
            8 -> { convert(t, 0x501AD8L + target, 2); 3 }
            else -> 3
        }
    }

    /** 0x4073BC(位址, 值)：寫值；值 ≠ 0 時依 [0x505FB8] 改陣營 +3／行動方式 7，0 時陣營 −3、行動方式 1。 */
    private fun convert(t: Long, addr: Long, v: Int) {
        mem.setU8(addr, v)
        if (v == 0) {
            mem.setU8(t + 5, (mem.u8(t + 5) - 3) and 0xff); mem.setU8(t + 0x0E, 1); return
        }
        when (mem.u8(0x505FB8L)) {
            0 -> { mem.setU8(t + 5, (mem.u8(t + 5) + 3) and 0xff); mem.setU8(t + 0x0E, 7) }
            1 -> mem.setU8(t + 5, (mem.u8(t + 5) + 3) and 0xff)
            else -> mem.setU8(t + 0x0E, 7)
        }
    }

    /** 0x4190E8 == 1：系別 ≤ 0x27 而且表 0x4D2188 = 1（不是 1 時原版回傳表值 xor 2）。 */
    fun attackKind(u: Long): Int {
        val g = group(charPtr(u))
        if (g > 0x27) return g
        val v = mem.u8(0x4D2188L + g)
        return if (v == 1) 1 else v xor 2
    }
    private fun archerLike(u: Long) = attackKind(u) != 0

    fun group(cp: Long): Int = mem.u8(cp + 0x2B).let { c -> when { c <= 0x3B -> c / 3; c < 0x50 -> c - 0x28; else -> 0x28 } }

    // ---- 範圍 ----

    /** 0x401DFF(策略)：範圍型態 = 策略 +0x0C；特效 (0x5C, 0x76) bit 1 時查表 0x41CEAB 延伸。 */
    fun rangeOf(u: Long, s: Int): Long {
        val t = mem.u8(record(s) + 0x0C)
        return if (effects.value(u, 0x5C, 0x76, 3, 0) and 2L != 0L) mem.u8(0x41CEABL + t).toLong() else t.toLong()
    }

    /** 0x41A10C(策略)：波及型態（策略 +0x0D）；表 0x4D14E0 bit 1 時再看 0x45D316(2)（特效 (0xA8, 0x57) bit 1 → 表 0x45D343）。 */
    fun areaOf(u: Long, s: Int): Int {
        var v = mem.u8(record(s) + 0x0D)
        if (mem.u8(FLAGS + s) and 2 != 0) {
            v = if (mem.u8(0x505FDCL) != 0) 0
            else if (effect(u, 0xA8, 0x57, 3, 0) and 2L != 0L) mem.u8(0x45D343L + v) else v
        }
        return v
    }

    // ---- 策略傷害、命中 ----

    /**
     * 0x43C01D(目標, 策略, 旗標)（施放者 [a]）：種類 0x1E → 目標 HP − 施放者 HP；表 0x4D1840 = 0xFF → 目標 HP。
     * 否則 (精神攻, 精神防) = 0x40D2AB(a, 2, t, 2, 1)；基礎 = 等級 + 25 ± (攻 − 防) / 5，× 表 0x4D1840%、× 目標系別表 0x4D21B0%；
     * 依種類加地形／天氣／兵種加成；種類 5 而且系別 0x0C ×2；特效 0x405DBD 或 (0x28, 0x38) 加成、目標 (0x29, 0x43) 減；
     * 0x412C78 浮動（旗標 = 估算）、旗標 ≠ 1 時 0x40CA68 爆擊與追擊；最多目標 HP（種類 6 時 MP）。
     * 旗標 0 時先判定命中（0x43B4B6 類別 2），不中 → 0。
     */
    fun damage(a: Long, t: Long, s: Int, flag: Int): Long {
        if (mem.u8(0x500AD7L) == 1) { bf.gapNote("0x43C01D [0x500AD7] = 1"); return 0 }
        val k = kind(s)
        val power = mem.u8(0x4D1840L + s)
        if (mem.u8(0x4D18D0L + s) >= 4 && k != 0x1E && k != 6) return 0          // 0x415EFA
        if (flag == 0 && !hit(a, t, s)) return 0
        if (!applicable(s, mem.u8(a + 4), mem.u8(t + 4))) return 0
        if (k == 0x1E) return (mem.u32(t + 0x10) - mem.u32(a + 0x10)) and M
        if (power == 0xFF) return mem.u32(t + 0x10)
        val (atk, def) = combat.attackDefense(a, 2, t, 2, 1)
        var dmg = (mem.u8(charPtr(a) + 0x2C) + 0x19).toLong()
        dmg = if (atk >= def) (((atk - def) and M) / 5 + dmg) and M else Stats47FA9E(dmg, ((def - atk) and M) / 5)
        dmg = CombatStats.percent(power.toLong(), dmg)
        val g = group(charPtr(t))
        if (g <= 0x27) dmg = CombatStats.percent(dmg, mem.u8(0x4D21B0L + g).toLong())
        var bonus = 0L
        var add = false
        when (k) {
            0 -> {
                if (g == 0x40) bonus += 0x14
                val tt = bf.search.terrainIn(pos(t))
                if (tt == 2 || tt == 0x17) bonus += 0x19
                add = true
            }
            1, 3, 0x26 -> if (weather() >= 2) { bonus += 0x19; add = true }
            2 -> if (group(charPtr(a)) == 7) { bonus += 0x19; add = true }
        }
        if (add) dmg = (dmg + CombatStats.percent(bonus, dmg)) and M
        if (k == 5 && group(charPtr(a)) == 0x0C) dmg = (dmg shl 1) and M
        val e = if (k >= 4) effects.value(a, 0x27, 0x20, 1, 0) else effects.value(a, 0x28, 0x38, 1, 0)
        if (e != 0L) dmg = (dmg + CombatStats.percent(dmg, e)) and M
        val r = effects.value(t, 0x29, 0x43, 1, 0)
        if (r != 0L) {
            val c = CombatStats.percent(dmg, r)
            dmg = if (dmg < c) 0 else dmg - c
        }
        dmg = combat.variance(a, dmg, t, flag)
        if (flag != 1) dmg = followUp(a, t, dmg)
        val cap = if (k == 6) mem.u16(t + 0x14).toLong() else mem.u32(t + 0x10)
        return if (dmg > cap) cap else dmg
    }

    private fun Stats47FA9E(a: Long, b: Long) = if (b < a) a - b else 0L

    /**
     * 0x40CA68(施放者, 目標, 傷害)：策略爆擊（0x42423D mask 2）；目標是 [0x497B05] 時：機率 0x401DAC（> 100 不擲，
     * 否則 0x4287A8 類別 0x12，成功 +100）→ 傷害 + 機率 / 4 %，[0x497AFB] = 1。
     */
    private fun followUp(a: Long, t: Long, dmg0: Long): Long {
        var dmg = combat.critical(a, dmg0, t, 2)
        if (mem.u8(t + 4) != mem.u8(0x497B05L)) return dmg
        var r = chaseRate(a, t)
        if (r <= 0x64) {
            if (!combat.roll(r.toInt(), 0x12)) return dmg
            r += 0x64
        }
        dmg = (dmg + CombatStats.percent(r ushr 2, dmg)) and M
        mem.setU8(0x497AFBL, 1)
        return dmg
    }

    /** 0x401DAC(a, t)：特效 (0x33, 0x31) bit 1 → +100；+ 0x405D0A(士氣攻, 士氣防)。 */
    private fun chaseRate(a: Long, t: Long): Long {
        var r = 0L
        if (effects.value(a, 0x33, 0x31, 3, 0) and 2L != 0L) r += 0x64
        val (atk, def) = combat.attackDefense(a, 4, t, 4, 0)
        val v = when {
            atk >= def * 3 -> 0x64L
            def * 2 < atk -> ((atk - def * 2) * 0x50 and M) / def + 0x14
            atk >= def -> ((atk - def) * 0x12 and M) / def + 2
            else -> 1L
        }
        return (r + v) and M
    }

    /** 0x43B4B6(目標, 2, 策略)：策略命中（命中率 0x43B730、類別 0x13 擲）。 */
    fun hit(a: Long, t: Long, s: Int): Boolean {
        var rate = hitRate(0, a, t, s)
        if (rate >= 0x64) return true
        val th = mem.u32(0x505EC8L)
        if (th > 1) {
            if (rate >= th) return true
            if (rate < mem.u32(0x505EC4L)) return false
        }
        for (k in 0 until 2) {
            val r = combat.roll(rate.toInt(), 0x13)
            if (mem.u32(0x505EC8L) == 1L || r) return r
            if (rate < 0x55) return false
            rate -= 10
        }
        return false
    }

    /**
     * 0x43B730(旗標, 目標, 策略)：種類 0x21 而且目標武將 < 0xD2 → 0；無效（0x42044D）→ 0；目標麻痺而且不是 0x0A／0x1F／0x35、
     * 或施放者有特效 (0x1B, 0x1B) → 100；目標有特效 (0x63, 0x4F) 而且對象是敵 → 0。
     * 否則 0x43B53A(精神 + 士氣 攻, 防)、減目標 (0x1D, 0x29)、0x408EEE 修正，再依表 0x4D1720 調整；
     * 種類 5 而且系別 0x0C ×1.5、(0x1C, 0x26) 加、0x405DBD 時 +10（最多 100）。只取低位元組，最多 100。
     */
    fun hitRate(flag: Int, a: Long, t: Long, s: Int): Long {
        val sd = side(s)
        val k = kind(s)
        if (k == 0x21 && mem.u32(t) < 0xD2) return 0
        if (!applicable(s, mem.u8(a + 4), mem.u8(t + 4))) return 0
        if (Units.health(mem, t, 2) != 0 && s != 0x0A && s != 0x1F && s != 0x35) return 0x64
        if (effects.value(a, 0x1B, 0x1B, 1, 1) != 0L) return 0x64
        if (effects.value(t, 0x63, 0x4F, 1, 1) != 0L && sd == 0) return 0
        val (a1, d1) = combat.attackDefense(a, 2, t, 2, 0)
        val (a2, d2) = combat.attackDefense(a, 4, t, 4, 0)
        var rate = combat.ratio((a1 + a2) and M, (d1 + d2) and M)
        val e = effects.value(t, 0x1D, 0x29, 1, 0)
        if (e != 0L) rate = Stats47FA9E(rate, e)
        rate = combat.adjustRate(a, t, rate)
        if (s < 0x96) {
            val c = mem.u8(0x4D1720L + s)
            fun low(v: Int) { rate = (rate and 0xFFFFFF00L) or (v.toLong() and 0xff) }
            when {
                c < 5 -> when (c) {
                    0, 1 -> if (flag != 0 && mem.u8(t + 0x1E) == 0) low(0)
                            else if (c == 1) low(0x64) else rate = CombatStats.percent(rate, 0x5A)
                    3, 4 -> if (mem.u8(t + 4) == 0) low(0)
                            else if (mem.u32(t) == mem.u16(0x496C96L).toLong()) low(mem.u8(0x496C96L))
                            else rate = CombatStats.percent(rate, if (c == 3) 0x22 else 0x32)
                    else -> {}
                }
                c == 0x64 -> low(0x64)
                else -> rate = CombatStats.percent(rate, c.toLong())
            }
        }
        if (k == 5 && group(charPtr(a)) == 0x0C) rate = (rate * 3 and M) ushr 1
        val up = effects.value(a, 0x1C, 0x26, 1, 0)
        if (up != 0L) rate = minOf((rate + up).toInt(), 0x64).toLong() and M
        if (effects.value(a, 0x27, 0x20, 1, 0) != 0L) rate = minOf((rate + 0x0A).toInt(), 0x64).toLong() and M
        val b = rate and 0xff
        return if (b > 0x64) 0x64 else b
    }

    companion object {
        private const val M = 0xFFFFFFFFL
        const val RECORDS = 0x4A3E77L
        /** 每個策略的類別（0x465AC1 對特效 0x49–0x50 的遮罩）。 */
        private const val CATEGORY = 0x4D17B0L
        /** bit 0：0x465AC1 旗標時要有；bit 1：0x41A10C 看波及升級。 */
        private const val FLAGS = 0x4D14E0L
        private const val PERSONAL = 0x507800L
    }
}
