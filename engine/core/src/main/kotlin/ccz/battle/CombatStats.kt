package ccz.battle

import ccz.data.Characters
import ccz.data.Equipment
import ccz.data.Stats
import ccz.script.EngineMemory

/**
 * 單位的戰鬥能力值（原版函式表 0x440151 → 0x43F5FF…0x43F6AF：0 攻擊、1 防禦、2 精神、3 爆發、4 士氣）。
 * 規則見 docs/battle.md「戰鬥能力值」。用 tools/re/gen_damage_vectors.py 的 stats 欄位跟原版比對（DamageTest）。
 */
class CombatStats(private val bf: BattleField) {
    private val mem: EngineMemory get() = bf.mem
    private val effects get() = bf.effects

    private fun charPtr(u: Long) = Characters.addr(mem, mem.u16(u))
    private fun charId(cp: Long) = ((cp - mem.u32(Characters.PTR)) / Characters.SIZE).toInt()

    /** 0x43F5FF 一族：[0x500AC8] = 單位；min(0x42211E(武將能力 0x40715A…, 0x22, 能力狀態 +0x18+i, 單位), 9999)。 */
    fun stat(u: Long, i: Int): Long {
        mem.setU32(EVALUATED_UNIT, u)
        val v = characterStat(charPtr(u), i)
        val r = statusAdjust(v, 0x22, mem.u8(u + 0x18 + i), u)
        return if (r >= 9999) 9999 else r
    }

    /** 0x40715A 一族：min(基礎 0x43C4D3 + MOD 成長差 0x436E02 + 裝備 0x407C29, 9999)（有號比較）。 */
    fun characterStat(cp: Long, i: Int): Long {
        val gap = growthGap(cp, i, 0).first
        val equip = Equipment.bonus(mem, charId(cp), i) { s -> growthGap(cp, s, 0).takeIf { it.second }?.third }
        val sum = (gap + equip) and M
        val total = (base(cp, i) + sum).toInt()
        return (if (total > 9999) 9999 else total).toLong() and M
    }

    /**
     * 0x436E02(i)（eax = [eaxIn]）：特效 (0x3D, 0x5C, 0, 0)（0x42518F）有值時，用它（0xFF 或 0 → 兵種成長）當每級成長，
     * 成長總和 = 成長 × 等級 + 五維；回傳 (max(總和 + 修正紀錄 − 戰鬥能力 word, 0) + eaxIn, 成立, 總和)。
     * 特效的 eax、edx 都是 0 時不成立：(eaxIn, false, 0)。
     */
    fun growthGap(cp: Long, i: Int, eaxIn: Long): Triple<Long, Boolean, Long> {
        if (i >= 5) return Triple(eaxIn, false, 0)
        val r = effect42518F(cp, 0x3D, 0x5C, 0, 0)
        var rate = (r.eax and 0xff).toInt()
        if (r.edx == 0L) {
            if (rate == 0) return Triple(eaxIn, false, 0)
            if (rate == 0xFF) rate = 0
        }
        // 0x407D48
        val ch = charId(cp)
        if (rate == 0) rate = Stats.growth(mem, mem.u8(cp + 0x2B), i, ch) and 0xff
        val total = (rate.toLong() * mem.u8(cp + 0x2C) + mem.u8(cp + 0x21 + i)) and M
        val e = (Stats.bonus(mem, ch, i).toLong() + total) and M
        val w = mem.u16(cp + 0x11 + 2 * i).toLong()
        val gap = if (e < w) 0L else e - w
        return Triple((gap + eaxIn) and M, true, total)
    }

    /** 0x42518F：戰場狀態時走 0x4101D9（[0x500AC8] 找單位），否則只看武將（0x41301E）。 */
    private fun effect42518F(cp: Long, a0: Int, a1: Int, a2: Int, a3: Int): Effects.Result =
        if (mem.u8(Effects.GAME_MODE) == 3) effects.check(cp, a0, a1, a2, a3) else effects.character(cp, a0, a1, a2, a3)

    /** 0x43C4D3：0x405B86(30, 戰鬥能力 word)；[0x505FA4] ≠ 0 而且五維 > 100 時再加 × (五維 − 100)%；最多 9999。 */
    private fun base(cp: Long, i: Int): Long {
        val b = mod405B86(cp, 0x1E, mem.u16(cp + 0x11 + 2 * i).toLong())
        var v = b
        val five = mem.u8(cp + 0x21 + i)
        if (mem.u8(0x505FA4L) != 0 && five > 100) v = (percent(b, (five - 100).toLong()) + b) and M
        return if (v >= 9999) 9999 else v
    }

    /** 0x405B86(k, 基礎)：MOD 選項 [0x505F38] ≠ 0 時 + 基礎 × (武將 +0x35 / k) / 100（k = 0x7F 時：+0x35 ≥ 100 → +1）。 */
    fun mod405B86(cp: Long, k: Int, base: Long): Long {
        if (mem.u8(0x505F38L) == 0) return base
        val w = mem.u16(cp + 0x35).toLong()
        if (k == 0x7F) return base + (if (w >= 100) 1 else 0)
        return (percent(base, w / k) + base) and M
    }

    /**
     * 0x42211E(v, 0x22, 狀態, 單位)：狀態 ≥ 4 → v × (10 + b) / 10；≤ 2 → v × (10 − a) / 10（a = b = 2）。
     * 自己和八方的同方單位（從 0x425325 決定的位置看）有特效 (0x74, 0x4C) 時：b += 值 / 10，a −= min(值 % 10, a)。
     */
    fun statusAdjust(v: Long, param: Int, status: Int, u: Long): Long {
        var a = param % 16
        var b = (param / 16) % 16
        val side = effects.sideCategory(u)
        val pos = bf.search.positionFor(u, statusPositionKind(u, side))
        for (k in 0 until 9) {
            val cu = if (k == 0) u else {
                val n = bf.neighbor(pos, k - 1)
                if (n and 0xff == 0xFF) continue
                val o = bf.occupantAt(n)
                if (o == 0xFF) continue
                val nu = Units.unit(o)
                if (effects.sideCategory(nu) != side) continue
                nu
            }
            val e = effects.value(cu, 0x74, 0x4C, 1, 0).toInt()
            if (e == 0) continue
            b = (b + e / 10) and 0xff
            val r = minOf(e % 10 and 0xff, a)
            a -= r
        }
        return when {
            status >= 4 -> (v * (b + 10) and M) / 10
            status <= 2 -> (v * (10 - a) and M) / 10
            else -> v
        }
    }

    /** 0x425325：[0x505FDC] ≠ 0 → 2；[0x4B3D18] < 2 → 陣營分類 1 時 1，否則 2；否則單位是 [0x4B5DF0] 的武將時 1。 */
    private fun statusPositionKind(u: Long, side: Int): Int = when {
        mem.u8(0x505FDCL) != 0 -> 2
        mem.u8(0x4B3D18L) < 2 -> if (side == 1) 1 else 2
        mem.u32(0x4B5DF0L) == mem.u32(u) -> 1
        else -> 2
    }

    companion object {
        private const val M = 0xFFFFFFFFL
        const val EVALUATED_UNIT = 0x500AC8L

        /** 0x425AD9：a × b / 100（32 位元、無號）。 */
        fun percent(a: Long, b: Long): Long = ((a * b) and M) / 100
    }
}
