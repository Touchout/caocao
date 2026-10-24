package ccz.battle

import ccz.data.Characters
import ccz.data.Equipment
import ccz.data.Stats
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

    // ---- 物理傷害 ----

    /** 原版回傳 (eax = 傷害（最多目標 HP）, ecx = 不限 HP 的傷害)。 */
    data class Damage(val value: Long, val raw: Long)

    private fun group(u: Long): Int = mem.u8(charPtr(u) + 0x2B).let { c -> when { c <= 0x3B -> c / 3; c < 0x50 -> c - 0x28; else -> 0x28 } }
    private fun maxHp(u: Long) = Equipment.maxHp(mem, mem.u16(u))
    private val rng get() = bf.rng

    /** 0x42A9F7：v > t ? v − t : 0，結果 0 時 1。 */
    private fun reduce(v: Long, t: Long): Long = (if (v > t) v - t else 0L).let { if (it == 0L) 1 else it }

    /**
     * 0x43BC5A(攻擊方, 目標, 估算, 旗標)：物理攻擊的傷害。估算 = 1 時是 AI 預估（不判定命中、沒有浮動與爆擊，[0x500E9A] = 2 時 ×1.5）。
     * 流程見 docs/battle.md「物理傷害」。
     */
    fun damage(a: Long, t: Long, est: Int, flag: Int): Damage {
        if (mem.u8(t + 4) == 0xFF) return Damage(0, 0)
        var dmg: Long
        val kill = instantKill(a, t)
        if (kill != 0L) {
            dmg = kill
        } else {
            val special = strategyValue(0x15)
            var pct = 0L
            var flat = false
            if (special != 0) pct = special.toLong()
            else {
                val e = effects.value(a, 0x16, 0x1F, 1, 0)
                if (e != 0L) { pct = e; flat = mem.u8(0x4CB6BFL) == 1 }
            }
            if (pct != 0L) {
                dmg = if (flat) pct else percent(maxHp(t), pct)
            } else {
                val (atk, def) = attackDefense(a, 0, t, 1, 0)
                if (est == 0 && !hit(a, t)) return Damage(0, 0)
                dmg = (mem.u8(charPtr(a) + 0x2C) + 0x19).toLong()
                dmg = if (atk >= def) dmg + (atk - def) / 5
                    else Stats.satSub(dmg, (def - atk) / 5).let { if (it == 0L) 1 else it }
                val slow = effects.value(a, 0x75, 0x4C, 1, 0)
                if (slow != 0L) {
                    val diff = (bf.search.movePoints(t) - slow) and M
                    if ((diff and 0xff).toByte() < 0) dmg = (dmg + percent((-diff and M) * 5 and M, dmg)) and M
                }
                val m = matchup(t, a)
                if (m != 100L) dmg = percent(m, dmg).let { if (it == 0L) 1 else it }
                if (group(a) <= 0x27 && mem.u8(MATCHUP_FLAG + group(a)) != 0) {                     // 0x4281AF
                    val e = effects.value(t, 0x22, 0x45, 1, 0)
                    if (e != 0L) dmg = reduce(dmg, percent(e, dmg))
                }
                dmg = knockback(t, a, dmg)
                val e1 = effects.value(a, 0x19, 0x5F, 1, 0)
                val c = mem.u8(0x500C48L)
                if (e1 != 0L && c != 0) dmg = (dmg + percent((e1 * c) and M, dmg)) and M
                val e2 = effects.value(t, 0x23, 0x3D, 1, 0)
                if (e2 != 0L) dmg = reduce(dmg, percent(e2, dmg))
                if (flag == 0) {
                    val e3 = effects.value(a, 0x2C, 0x4A, 1, 0)
                    if (e3 != 0L) dmg = (dmg + percent((e3 * mem.u8(ACTED + mem.u8(a + 4))) and M, dmg)) and M
                } else if (effects.value(a, 0x48, 0x6A, 1, 1) == 0L) {
                    dmg = ((dmg * 3) and M ushr 2).let { if (it == 0L) 1 else it }
                }
                dmg = variance(a, dmg, t, est)
                if (est == 0) dmg = critical(a, dmg, t)
            }
            val cap = effects.value(t, 0x56, 0x72, 1, 0)                                     // 0x43C3F0
            if (cap != 0L && cap < dmg) dmg = cap
        }
        if (est != 0 && mem.u8(0x500E9AL) == 2) dmg = (dmg + percent(dmg, 0x32)) and M     // 0x406B0E
        val hp = mem.u32(t + 0x10)
        return Damage(if (dmg > hp) hp else dmg, dmg)
    }

    /** 0x43637B：攻擊方有特效 (0x6E, 0x7D) 時，目標 HP ≤ HP 上限 × 值% → 傷害 = 目標 HP（一擊必殺）。 */
    private fun instantKill(a: Long, t: Long): Long {
        val e = effects.value(a, 0x6E, 0x7D, 1, 0)
        if (e == 0L) return 0
        val q = percent(maxHp(t), e)
        val hp = mem.u32(t + 0x10)
        return if (hp > q) 0 else hp
    }

    /** 0x436334([0x500E9B], id)：正在用的策略 = [id] 時取策略資料（0x4A7510 + 16·id）+0x0F，否則 0。 */
    private fun strategyValue(id: Int): Int {
        val s = mem.u8(0x500E9BL)
        if (s == 0 || s != id) return 0
        return mem.u8(0x4A7510L + 16L * s + 0x0F)
    }

    /**
     * 0x426737(目標, 攻擊方)：兵種相剋表 0x4D1A80[攻擊方系別 × 40 + 目標系別] + 攻擊方特效 (0x76, 0x4E) − 目標的，最少 0。
     */
    private fun matchup(t: Long, a: Long): Long {
        var v = mem.u8(MATCHUP + group(a) * 0x28L + group(t)).toLong()
        v += effects.value(a, 0x76, 0x4E, 1, 0)
        v -= effects.value(t, 0x76, 0x4E, 1, 0)
        return if (v.toInt() < 0) 0 else v and M
    }

    /**
     * 0x411072(0, 目標, 攻擊方)：特殊策略 0x16、攻擊方特效 (0x62, 0x35) 的值、或特效 (0x61, 0x35) 時：
     * [0x500E9A] = 1 或目標不健康（HP 0 或健康狀態第 2 組）→ +30%（特效 0x62 時加它的值%）；
     * 否則看擊退：目標往離開攻擊方的方向一格，地圖內、沒有單位、進得去 → 不加；擋住 → +30%。
     */
    private fun knockback(t: Long, a: Long, dmg: Long): Long {
        var pct: Long
        if (strategyValue(0x16) != 0) pct = -1
        else {
            val e = effects.value(a, 0x62, 0x35, 0, 0)
            if (e and 0xff != 0L) return (dmg + percent(dmg, e)) and M
            if (effects.value(a, 0x61, 0x35, 1, 1) == 0L) return dmg
            pct = -1
        }
        if (pct < 0) {
            val unhealthy = mem.u32(t + 0x10) == 0L || (mem.u8(t + 0x1E) shr 4) and 3 != 0
            if (mem.u8(0x500E9AL) == 1 || unhealthy) return (dmg + percent(dmg, 0x1E)) and M
            val tp = pos(t)
            val ap = pos(a)
            fun step(av: Int, tv: Int) = when { av == tv -> 0; av < tv -> 1; else -> -1 }
            val nx = ((tp and 0xff) + step(ap and 0xff, tp and 0xff)) and 0xff
            val ny = (((tp shr 8) and 0xff) + step((ap shr 8) and 0xff, (tp shr 8) and 0xff)) and 0xff
            val np = nx or (ny shl 8)
            val free = nx < bf.width && ny < bf.height && bf.occupantAt(np) == 0xFF &&
                bf.moveCost(np, mem.u8(t + 4)) < 0xFE
            if (!free) return (dmg + percent(dmg, 0x1E)) and M
        }
        return dmg
    }

    /**
     * 0x412C78(攻擊方, 傷害, 目標, 估算)：方向加成 0x408DB1；估算時到此為止。
     * 否則 0x41D716（迷霧裡看不到的目標 × 80%）與亂數浮動：r = 亂數 % 7，> 2 → + (r − 2)，否則 − r（最少 1）。
     */
    private fun variance(a: Long, dmg0: Long, t: Long, est: Int): Long {
        var dmg = direction(a, t, dmg0)
        if (est == 1) return dmg
        dmg = fog(a, t, dmg)
        val r = rng.below(7)
        return if (r > 2) (dmg + r - 2) and M else Stats.satSub(dmg, r.toLong()).let { if (it == 0L) 1 else it }
    }

    /**
     * 0x408DB1：[0x505F88] ≠ 0 時依目標朝向與攻擊方位置（AI 預估時用 [0x48E8C2]）：同一行／列 +5%，背後 +10%；
     * 目標不是我方而且 [0x500E9A] = 1 時再 +5%。
     */
    private fun direction(a: Long, t: Long, dmg0: Long): Long {
        var dmg = dmg0
        if (mem.u32(0x505F88L) != 0L) {
            val ap = if (mem.u8(a + 0x0E) == 7 || mem.u16(ALT_POS) == 0xFFFF) pos(a) else mem.u16(ALT_POS)
            val ax = ap and 0xff
            val ay = (ap shr 8) and 0xff
            val d = mem.u8(t + 0x0F)
            val k = if (d and 1 == 0) {
                val ty = mem.u8(t + 7)
                when {
                    ty == ay -> 1
                    ty > ay -> if (d == 2) 2 else 0
                    else -> if (d == 0) 2 else 0
                }
            } else {
                val tx = mem.u8(t + 6)
                when {
                    tx == ax -> 1
                    tx > ax -> if (d == 3) 0 else 2
                    else -> if (d == 1) 0 else 2
                }
            }
            dmg = (dmg + percent(dmg, (k * 5).toLong())) and M
        }
        if (effects.sideCategory(t) != 1 && mem.u8(0x500E9AL) == 1) dmg = (dmg + percent(dmg, 5)) and M
        return dmg
    }

    /**
     * 0x41D716：迷霧（[0x505FDC] = 0、[0x501BD2] ≥ 2）而且兩方的視野類別（0x4088B4）不同時，
     * 目標所在格的可見表 0x4934B0 & (目標類別 2 → 0x0D，否則 0x0E) = 0 → ×80%。
     */
    private fun fog(a: Long, t: Long, dmg: Long): Long {
        if (mem.u8(0x505FDCL) != 0 || mem.u8(0x501BD2L) < 2) return dmg
        fun view(u: Long) = if (mem.u8(u + 4) < 0x3C || mem.u8(u + 5) == 5) 1 else 2           // 0x4088B4
        val vt = view(t)
        val va = view(a)
        if (vt == va) return dmg
        val mask = if (vt == 2) 0x0D else 0x0E
        if (mem.u8(VISIBLE + mem.u8(t + 7) * bf.width + mem.u8(t + 6)) and mask != 0) return dmg
        return percent(dmg, 0x50)
    }

    /**
     * 0x42423D：攻擊方特效 (0x6F, 0x48, 3) 的 bit 0 → 爆擊：+ 目標 HP 上限 × (序列表 0 的下一個值 / 20 + 10)%（計數器 0x501BF5）。
     */
    private fun critical(a: Long, dmg: Long, t: Long): Long {
        if (effects.value(a, 0x6F, 0x48, 3, 0) and 1L == 0L) return dmg
        val max = maxHp(t)
        val roll = rng.sequence(0, 0x501BF5L)
        return (dmg + percent(max, (roll / 20 + 10).toLong())) and M
    }

    // ---- 命中 ----

    /**
     * 0x43B4B6(目標, 1, 0xFF)：物理攻擊命中。命中率 0x43B623 ≥ 100 → 命中；[0x505EC8] > 1 時：命中率 ≥ 它 → 命中、
     * < [0x505EC4] → 不中。否則最多擲兩次（0x4287A8），第一次不中而且命中率 ≥ 85 時用命中率 − 10 再擲一次。
     */
    fun hit(a: Long, t: Long): Boolean {
        var rate = hitRate(a, t)
        if (rate >= 0x64) return true
        val th = mem.u32(0x505EC8L)
        if (th > 1) {
            if (rate >= th) return true
            if (rate < mem.u32(0x505EC4L)) return false
        }
        for (k in 0 until 2) {
            val r = roll(rate.toInt(), 1)
            if (mem.u32(0x505EC8L) == 1L || r) return r
            if (rate < 0x55) return false
            rate -= 10
        }
        return false
    }

    /**
     * 0x43B623：[0x500E9A] = 2、或攻擊方 0x47EF3D 不成立、或目標麻痺 → 100。否則 0x43B53A(爆發攻防)，
     * 特效 (0x1E, 0x24) → 100；特效 (0x1F, 0x7E) 加（最多 100）；目標特效 (0x20, 0x29) 減；0x408EEE 方向等修正。
     */
    fun hitRate(a: Long, t: Long): Long {
        if (mem.u8(0x500E9AL) == 2) return 0x64
        if (!canEvade(a, t)) return 0
        if (mem.u8(t + 0x1E) and 3 != 0) return 0x64
        val (atk, def) = attackDefense(a, 3, t, 3, 0)
        var r = ratio(atk, def)
        if (effects.value(a, 0x1E, 0x24, 1, 1) != 0L) return 0x64
        val up = effects.value(a, 0x1F, 0x7E, 1, 0)
        if (up != 0L) r = (r + up).toInt().let { if (it > 0x64) 0x64 else it }.toLong() and M
        val down = effects.value(t, 0x20, 0x29, 1, 0)
        if (down != 0L) r = Stats.satSub(r, down)
        return adjustRate(a, t, r)
    }

    /** 0x47EF3D：攻擊方系別在表 0x4D2138 有值而且目標有特效 (0x2F, 0x4E) → 不成立（0x43B623 回傳 0）。 */
    private fun canEvade(a: Long, t: Long): Boolean {
        val g = group(a)
        if (g > 0x27 || mem.u8(MATCHUP_FLAG + g) == 0) return true
        return effects.value(t, 0x2F, 0x4E, 1, 1) == 0L
    }

    /** 0x43B53A(攻, 防)：攻 ≥ 2 防 → 100；攻 ≥ 防 → 90 + (攻 − 防) × 10 / 防；攻 ≥ 防 / 2 → 60 + …×30 / (防 / 2)；否則 30 + …。 */
    private fun ratio(atk: Long, def: Long): Long = when {
        atk >= (def shl 1 and M) -> 0x64
        atk >= def -> ((Stats.satSub(atk, def) * 10 and M) / def) + 0x5A
        atk >= def ushr 1 -> (def ushr 1).let { h -> ((Stats.satSub(atk, h) * 0x1E and M) / h) + 0x3C }
        else -> (def / 3).let { q -> ((Stats.satSub(atk, q) * 0x1E and M) / q) + 0x1E }
    }

    /** 0x408EEE(攻擊方, 目標, 命中率)：+ 方向加成（0x408DB1 只取加成），最多 100；− 目標特效 (0x21, 0x22)（最少 1）。 */
    private fun adjustRate(a: Long, t: Long, r0: Long): Long {
        val bonus = direction(a, t, r0) - r0
        var r = (r0 + bonus).toInt().let { if (it > 0x64) 0x64 else it }.toLong() and M
        r = Stats.satSub(r, effects.value(t, 0x21, 0x22, 1, 0))
        return if (r == 0L) 1 else r
    }

    /**
     * 0x4287A8(命中率, 類別)：命中累積表 0x4930C8（依 [0x4927F0]／[0x497AF8] 的組 × 4 + 類別 % 16）加命中率，滿 100 命中。
     * 結果在 [0x505EC8] ≠ 1 時改用 0x43786B（序列表或真亂數）；[0x505FDC] ≠ 0 時直接 0x43786B。
     */
    private fun roll(rate: Int, type: Int): Boolean {
        if (mem.u8(0x505FDCL) != 0) return chanceRoll(rate, 0)
        val hi = type / 16
        val lo = type % 16
        val odd = lo and 1
        val (table, idx) = if (hi == 1) 0x497AF8L to (if (odd != 0) 0x0C else 1) else 0x4927F0L to odd
        val cell = mem.u8(table + idx) * 4 + lo
        var dl = mem.u8(ACCUM + cell)
        var r = false
        var store = true
        if (odd == 1) {
            dl = (dl + 0x64 - rate) and 0xff
            if (dl >= 0x64) dl -= 0x64 else r = true
        } else {
            dl = (dl + rate) and 0xff
            if (dl >= 0x64) { r = true; dl -= 0x64 }
            if (type == 0 && mem.u8(0x492DF8L).toByte() >= 1) store = false
        }
        if (store) mem.setU8(ACCUM + cell, dl)
        if (mem.u8(0x505EC8L) == 1) return r
        return chanceRoll(rate, 0)
    }

    /** 0x43786B(機率, 方式)：[0x505FB4] < 2 而且方式 ≠ 2 → 序列表 [0x505FB4]（計數器 0x501BF6）的值 ≤ 機率；否則真亂數。 */
    private fun chanceRoll(rate: Int, mode: Int): Boolean {
        val k = mem.u8(0x505FB4L)
        if (mode == 2 || k >= 2) return rng.chance(rate and 0xff)
        val v = rng.sequence(k, 0x501BF6L)
        val p = if (rate and 0xff >= 0x64) 0x64 else rate and 0xff
        return if (mode == 1) v >= 0x64 - p else p >= v
    }

    companion object {
        private const val M = 0xFFFFFFFFL
        /** 兵種相剋表（系別 × 40 + 系別）。 */
        private const val MATCHUP = 0x4D1A80L
        /** 0x4281AF：系別 → 是否吃「特效 0x22 減傷」、0x47EF3D 的判斷。 */
        private const val MATCHUP_FLAG = 0x4D2138L
        private const val ALT_POS = 0x48E8C2L
        /** 每格一個 byte（0x420952 清除；特效 0x2C 的倍數）。 */
        private const val ACTED = 0x508A00L
        /** 命中累積表。 */
        private const val ACCUM = 0x4930C8L
        /** 迷霧的可見表（0x4934B0）。 */
        private const val VISIBLE = 0x4934B0L
    }
}
