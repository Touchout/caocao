package ccz.battle

import ccz.data.Characters
import ccz.data.Equipment
import ccz.script.EngineMemory

/**
 * 戰鬥公式（原版 0x40D2AB 攻擊／防禦、0x43BC5A 物理傷害…）。規則見 docs/battle.md「攻擊與防禦」「物理傷害」。
 * 用 tools/re/gen_damage_vectors.py 跟原版比對（DamageTest）。
 */
class Combat(private val bf: BattleField) {
    private val mem: EngineMemory get() = bf.mem
    private val effects get() = bf.effects
    private val stats get() = bf.stats

    private fun charPtr(u: Long) = Characters.addr(mem, mem.u16(u))
    private fun percent(a: Long, b: Long) = CombatStats.percent(a, b)

    // ---- 攻擊與防禦 ----

    /**
     * 0x40D2AB(攻擊方, 攻擊項目, 目標, 防禦項目, 不看地形)：回傳 (攻擊, 防禦)。
     * 攻擊 = 0x414CCB(攻擊方, 項目, 目標的特效 0x6A) → 0x423CED → 目標的削弱 0x416A38；
     * 防禦 = 0x414CCB(目標, 項目, 攻擊方的特效 0x54) → 0x423CED → 攻擊方的削弱 0x416A38；
     * 再各乘上所在地形的適性 %（0x43F7B8，攻擊方用地形 30、目標用 32 → 自己的位置）。
     */
    fun attackDefense(attacker: Long, atkStat: Int, target: Long, defStat: Int, noTerrain: Int = 0): Pair<Long, Long> {
        var a = value(attacker, atkStat, effects.value(target, 0x6A, 0x6A, 1, 0))
        a = convert(attacker, atkStat, a)
        a = weaken(target, atkStat, a)
        var d = value(target, defStat, effects.value(attacker, 0x54, 0x70, 1, 0))
        d = convert(target, defStat, d)
        d = weaken(attacker, defStat, d)
        if (noTerrain == 0) {
            a = (a * bf.search.moveCost(attacker, 0x1E).second and M) / 100
            d = (d * bf.search.moveCost(target, 0x20).second and M) / 100
        }
        return a to d
    }

    /**
     * 0x414CCB(單位, 項目, b)：特效 (0x39, 0x61) 時——b = 0 取 5 項戰鬥能力的最大值，否則取項目本身；
     * 沒有時——b = 0 取項目本身，b ≥ 6 取 5 項的最小值，其他取第 b − 1 項（項目也改成 b − 1）。
     * 再經過 HP/MP 加成 0x428430、包圍 0x478EAB、援護 0x4363B0、光環與難度 0x4200A2。
     */
    fun value(u: Long, stat: Int, b: Long): Long {
        var a = stat
        val e = effects.value(u, 0x39, 0x61, 1, 1)
        var minMode = false
        var best = 0L
        val range: IntRange
        if (b == 0L) {
            range = if (e != 0L) 0..4 else a..a
        } else if (e != 0L) {
            range = a..a
        } else {
            minMode = true
            best = 0xFFFF
            if (b >= 6) range = 0..4 else { a = (b - 1).toInt(); range = a..a }
        }
        for (k in range) {
            if (k > 4) { bf.gapNote("0x414CCB 項目 $k（函式表外）"); continue }
            val v = stats.stat(u, k)
            if (minMode) { if (best > v) best = v } else if (best < v) best = v
        }
        var r = hpMpBonus(u, best, a)
        r = surrounded(r, u)
        r = support(r, u)
        r = difficulty(aura(r, u), u)
        return r
    }

    /** 0x428430：i = 0..4 依序：特效 (0x9D + i, 0x7D) 的 bit a → + HP / 5；特效 (0xA2 + i, 0x7E) 的 bit a → + MP；有加就停。 */
    private fun hpMpBonus(u: Long, v0: Long, a: Int): Long {
        var v = v0
        for (i in 0 until 5) {
            var found = false
            val e = effects.value(u, 0x9D + i, 0x7D, 3, 0)
            if (e != 0L && (e ushr a) and 1L != 0L) { v = (v + mem.u32(u + 0x10) / 5) and M; found = true }
            val e2 = effects.value(u, 0xA2 + i, 0x7E, 3, 0)
            if (e2 != 0L && (e2 ushr a) and 1L != 0L) { v = (v + mem.u16(u + 0x14)) and M; found = true }
            if (found) break
        }
        return v
    }

    /** 0x478EAB：特效 (0x47, 0x69) 的 %；周圍 3×3 的敵方（我方單位看 4、其他看 5）超過 1 個、或唯一的不是自己 → 減半。 */
    private fun surrounded(r: Long, u: Long): Long {
        var e = effects.value(u, 0x47, 0x69, 0, 0)
        if (e == 0L) return r
        val (n, first) = nearby(u, if (effects.sideCategory(u) == 1) 4 else 5)
        if (n != 0 && !(n == 1 && (first.second == pos(u) || first.first == mem.u8(u + 4)))) e = e ushr 1
        return (r + percent(r, e)) and M
    }

    /** 0x4363B0：特效 (0x55, 0x71) 時 + r × (周圍 3×3 的同方數 + 1) × 值 / 100（我方看 5，其他看 4）。 */
    private fun support(r: Long, u: Long): Long {
        val e = effects.value(u, 0x55, 0x71, 1, 0)
        if (e == 0L) return r
        val (n, _) = nearby(u, if (effects.sideCategory(u) == 1) 5 else 4)
        return (r + percent(r, ((n + 1).toLong() * e) and M)) and M
    }

    /**
     * 0x41BC4A：單位位置（不是我軍而且 [0x4B3D18] ≠ 0 時用 0x407BF6(1)）周圍 3×3（0x428DF1 模式 1）在地圖上、符合篩選的數目，
     * 以及 (第一個的格號, 位置)。
     */
    private fun nearby(u: Long, filter: Int): Pair<Int, Pair<Int, Int>> {
        val p = if (mem.u8(u + 0x0E) == 7 || mem.u8(0x4B3D18L) == 0) pos(u) else bf.search.positionFor(u, 1)
        val x = p and 0xff
        val y = (p shr 8) and 0xff
        val x0 = if (x != 0) x - 1 else 0
        val y0 = if (y != 0) y - 1 else 0
        var n = 0
        var first = 0xFF
        for (yy in y0..((y + 1) and 0xff)) for (xx in x0..((x + 1) and 0xff)) {
            val o = bf.occupantAt(xx or (yy shl 8))
            if (o == 0xFF || mem.u8(Units.unit(o) + 0x0C) != 2) continue
            if (!Units.countMatch(mem, filter, o)) continue
            if (n == 0) first = o
            n = (n + 1) and 0xff
        }
        return n to (first to p)
    }

    /** 0x4200A2：光環 0x43CB00 之後，敵方（+5 ≥ 2）乘 [0x505F04]%、友軍（+5 = 1）乘 [0x505F08]%（0 = 不乘）。 */
    private fun difficulty(r: Long, u: Long): Long {
        val s = mem.u8(u + 5)
        val m = when {
            s >= 2 -> mem.u32(0x505F04L)
            s == 1 -> mem.u32(0x505F08L)
            else -> 0L
        }
        return if (m != 0L) percent(m, r) else r
    }

    /**
     * 0x43CB00：其他在地圖上、武將 < 0x400、有特效 (0x3C, 0x1C) 的單位，周圍一格（含斜角，0x420B08）有這個單位時，
     * 值 × 它周圍同方的數目（0x4024AA）：同方加、不同方減；總和的 % 加到 r（減的最多減到 1）。
     * 之後 0x45B67C：特效 (0x51, 0x6D) 時依失去的 HP 比例 / 2（不超過特效值）加 %。最少 1。
     */
    private fun aura(r0: Long, u: Long): Long {
        var r = r0
        val saved = saveRange()
        mem.setU8(AttackRange.PREFERRED, mem.u8(u + 4))
        val side = effects.sideCategory(u)
        var sum = 0
        for (i in 0 until 0xFA) {
            val cu = Units.unit(i)
            if (cu == u || mem.u8(cu + 0x0C) != 2 || mem.u16(cu) >= 0x400) continue
            val e = effects.value(cu, 0x3C, 0x1C, 1, 0)
            if (e and 0xff == 0L) continue
            val flags = if (i >= 0x3C) side xor 1 else side
            if (auraReach(cu, flags, u) != mem.u8(u + 4)) continue
            val d = auraCount(cu, mem.u8(u + 4))
            val v = (d * e).toInt()
            if (effects.sideCategory(cu) == side) sum += v else sum -= v
        }
        if (sum != 0) {
            val neg = sum < 0
            val t0 = percent(r, (if (neg) -sum else sum).toLong() and M)
            if (neg) { val t = if (r <= t0) r - 1 else t0; r = (r - t) and M } else r = (r + t0) and M
        }
        restoreRange(saved)
        r = hpLost(u, r)
        return if (r.toInt() <= 0) 1 else r
    }

    /** 0x420B08：我軍或 [0x4B3D18] = 0 → 來源周圍（範圍型態 1）的目標裡優先 [0x4B2C28]；否則看單位的 0x407BF6(1) 位置在不在來源八方。 */
    private fun auraReach(src: Long, flags: Int, u: Long): Int {
        if (mem.u8(u + 0x0E) == 7 || mem.u8(0x4B3D18L) == 0) return bf.range.collect(src, pos(src), 1, flags, 0)
        val p = bf.search.positionFor(u, 1)
        for (d in 0 until 8) if (bf.neighbor(pos(src), d) == p) return mem.u8(u + 4)
        return 0xFF
    }

    /** 0x4024AA：來源周圍（範圍型態 1、同方）的目標數，加上單位不是我軍、[0x4B3D18] ≠ 0 而且不在清單優先位置時 +1。 */
    private fun auraCount(src: Long, slot: Int): Long {
        val r = bf.range.collect(src, pos(src), 1, 1, 0)
        var n = bf.range.lastCount.toLong()
        val su = Units.unit(slot)
        if (mem.u8(su + 0x0E) != 7 && mem.u8(0x4B3D18L) != 0 && r != slot) n++
        return n
    }

    /** 0x45B67C：特效 (0x51, 0x6D) 時 + r × min((HP 上限 − HP) × 100 / HP 上限 / 2, 值) / 100。 */
    private fun hpLost(u: Long, r: Long): Long {
        val e = effects.value(u, 0x51, 0x6D, 0, 0)
        if (e == 0L) return r
        val hp = mem.u32(u + 0x10)
        val max = Equipment.maxHp(mem, mem.u16(u))
        if (max == 0L) return r
        var q = ((((max - hp) * 100).toInt() / max.toInt()).toLong() and M) ushr 1
        if (q == 0L) return r
        if (q > e) q = e
        return (r + percent(r, q)) and M
    }

    /** 0x43CC92：光環計算前後保存／還原目標清單、花費表、[0x4B2C34]、[0x4B2C28]。 */
    private fun saveRange(): Triple<ByteArray, ByteArray, Pair<Long, Int>> {
        val n = (bf.width * bf.height) / 4 * 4
        return Triple(mem.bytes(AttackRange.LIST, 0x50), bf.search.cost.copyOf(n),
            mem.u32(AttackRange.LIST_END) to mem.u8(AttackRange.PREFERRED))
    }

    private fun restoreRange(s: Triple<ByteArray, ByteArray, Pair<Long, Int>>) {
        s.first.forEachIndexed { i, b -> mem.setU8(AttackRange.LIST + i, b.toInt() and 0xff) }
        s.second.copyInto(bf.search.cost)
        mem.setU32(AttackRange.LIST_END, s.third.first)
        mem.setU8(AttackRange.PREFERRED, s.third.second)
    }

    /**
     * 0x423CED：兩輪（j = 0、1）× 項目 i = 0..4，0x412BE6：特效 (0x80 + i, 0x7C)（j = 0）或 (0x85 + i, 0x7B)（j = 1），
     * 值 % 32 的 bit [stat] 有設時取 S = 0x414CCB(單位, 值 / 32, 0)：j = 0 → 取 max(v, S)（項目 4 直接 S）；
     * j = 1 → 同一個特效值第一次出現時 v + S × 20%。
     */
    private fun convert(u: Long, stat: Int, v0: Long): Long {
        var v = v0
        val seen = IntArray(5)
        for (j in 0 until 2) for (i in 0 until 5) {
            val e = if (j == 0) effects.value(u, 0x80 + i, 0x7C, 2, 0) else effects.value(u, 0x85 + i, 0x7B, 2, 0)
            if (e == 0L) continue
            val q = (e.toInt() / 32)
            var m = (e.toInt() % 32)
            var hit = false
            for (k in 0 until 5) {
                if (k == stat && m and 1 != 0) { hit = true; break }
                m = m ushr 1
            }
            if (!hit) continue
            val s = value(u, q, 0)
            if (j == 0) {
                v = if (stat == 4) s else if (v <= s) s else v
            } else {
                var dup = false
                for (k in 0..i) if (seen[k] == (e and 0xff).toInt()) { dup = true; break }
                if (dup) continue
                seen[i] = (e and 0xff).toInt()
                v = (percent(s, 0x14) + v) and M
            }
        }
        return v
    }

    /** 0x416A38：i = 0..5，[x] 的特效 (0x8A + i, 0x7A) 的 bit a → v − v × 20%，只算一次。 */
    private fun weaken(x: Long, a: Int, v: Long): Long {
        for (i in 0 until 6) {
            val e = effects.value(x, 0x8A + i, 0x7A, 3, 0)
            if (e != 0L && (e ushr a) and 1L != 0L) return (v - percent(0x14, v)) and M
        }
        return v
    }

    private fun pos(u: Long) = mem.u8(u + 6) or (mem.u8(u + 7) shl 8)

    companion object {
        private const val M = 0xFFFFFFFFL
    }
}
