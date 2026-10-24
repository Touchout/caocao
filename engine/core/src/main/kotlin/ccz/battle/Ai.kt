package ccz.battle

import ccz.data.Characters
import ccz.data.Equipment
import ccz.script.EngineMemory

/**
 * 電腦方的行動評估（原版 0x43A55E 每格評估、0x438914 物理攻擊得分、0x452E18 地形價值、0x437E23 路徑、0x43817B 目標周圍加權）。
 * 規則見 docs/battle.md「AI」。用 tools/re/gen_ai_vectors.py 跟原版比對（AiTest）。
 *
 * 原版的表：每格得分在緩衝區 0x48B0E0 + 0x640（[BattleField.scratch]）、花費表在戰場緩衝區 +0x12C0（[Search.cost]）、
 * 路徑在戰場緩衝區 +0x1900（[path]）；最佳結果放在記憶體 0x4B2C20…（位址同原版）。
 */
class Ai(private val bf: BattleField) {
    private val mem: EngineMemory get() = bf.mem
    private val effects get() = bf.effects
    private val search get() = bf.search
    private val combat get() = bf.combat

    /** 戰場緩衝區 +0x1900：0x437E23 算出的路徑（從起點出發每一步的方向），長度在 [0x500C48]。 */
    val path: ByteArray get() = search.path

    private fun charPtr(u: Long) = Characters.addr(mem, mem.u16(u))
    private fun pos(u: Long) = mem.u8(u + 6) or (mem.u8(u + 7) shl 8)
    private fun index(p: Int) = ((p shr 8) and 0xff) * bf.width + (p and 0xff)
    private fun slotOf(u: Long) = mem.u8(u + 4)

    // ---- 每格評估 ----

    /**
     * 0x43A55E(&位置)（[0x500ACE] = 0 時）：別人站的格不評估。不是幽靈單位時：行動方式不是 4 →
     * 得分 = min(255, 原有加權 + max(物理得分, 策略得分))（物理得分只在格被標記過或是原位時算；策略得分在健康狀態第 1 組沒有時算）；
     * 再加地形價值、原位又沒有物理得分時 +1（都不超過 255）。格是 [0x4B2C38] → 0。
     * 比目前最佳 [0x4B2C4C] 高（行動方式 0、4 同分也算）就更新：位置 [0x4B2C20]、目標 [0x4B2C28]、策略 [0x4B2C44]（物理 0xFF）。
     */
    fun evaluate(u: Long, p: Int) {
        val i = 0x640 + index(p)
        val o = bf.occupantAt(p)
        if (o != 0xFF && p != pos(u)) return
        var phys = 0L
        var strat = 0L
        if (mem.u8(u + 0x0D) and 0x80 == 0) {
            if (mem.u8(u + 0x0E) != 4) {
                if (bf.scratch[index(p)].toInt() and 0xff != 0xFF || p == pos(u)) phys = physicalScore(u, p)
                if (Units.health(mem, u, 4) == 0) strat = strategyScore(u, p, 0)
                val s = (bf.scratch[i].toInt() and 0xff) + maxOf(phys, strat)
                bf.scratch[i] = (if (s > 0xFF) 0xFF else s).toByte()
            }
            val tv = terrainValue(u, p)
            val room = 0xFF - (bf.scratch[i].toInt() and 0xff)
            bf.scratch[i] = (bf.scratch[i] + (if (tv < room) tv else room)).toByte()
            if (p == pos(u) && phys == 0L) {
                val r = 0xFF - (bf.scratch[i].toInt() and 0xff)
                bf.scratch[i] = (bf.scratch[i] + (if (r > 1) 1 else r)).toByte()
            }
        }
        if (mem.u8(AVOID) != 0xFF && p == mem.u16(AVOID)) bf.scratch[i] = 0
        val best = mem.u8(BEST_SCORE)
        val s = bf.scratch[i].toInt() and 0xff
        if (best > s) return
        val ai = mem.u8(u + 0x0E)
        if (best == s && ai != 0 && ai != 4) return
        if (strat > phys) {
            val st = mem.u8(STRAT_BEST)
            mem.setU8(BEST_STRATEGY, st)
            mem.setU8(BEST_TARGET, if (st == 0x48) mem.u8(PHYS_TARGET) else mem.u8(STRAT_TARGET))
        } else {
            mem.setU8(BEST_STRATEGY, 0xFF)
            mem.setU8(BEST_TARGET, mem.u8(PHYS_TARGET))
        }
        mem.setU8(BEST_SCORE, s)
        mem.setU16(BEST_POS, p.toLong())
    }

    // ---- 物理攻擊得分 ----

    /**
     * 0x438914(&位置)：單位假設站在 [p]（[0x48E8C2] = p）時，攻擊範圍內每個敵人的得分（[targetScore]）取最大（同分取後面的），
     * 目標記在 [0x4B2C30]。先算從 p 走回原位的路徑（0x437E23，路徑長度影響傷害）。[0x500ACE] 模式時改回傳 0x421D6C。
     */
    fun physicalScore(u: Long, p: Int): Long {
        mem.setU16(ALT_POS, p.toLong())
        val desc = bf.range.descriptor(u)
        mem.setU8(PHYS_TARGET, 0xFF)
        var best = 0L
        if (bf.range.collect(u, p, desc, 0x80, 0) != 0xFF) {
            val s = scripted()
            if (s != 0xFF) {
                mem.setU16(ALT_POS, 0xFFFF)
                return s.toLong()
            }
            walkPath(u, pos(u), p, 0xFF, 0)
            var q = AttackRange.LIST
            while (true) {
                val t = mem.u8(q)
                if (t == 0xFF || t >= 0xFA) break
                val v = targetScore(u, t, p)
                if (v >= best) {
                    best = v
                    mem.setU8(PHYS_TARGET, t)
                }
                q++
            }
        }
        mem.setU16(ALT_POS, 0xFFFF)
        return best
    }

    /** 0x421D6C：[0x500ACE] = 0 → 0xFF；否則目標清單裡有目前行動的武將（[0x4B5DF0]）→ 1，沒有 → 0。 */
    private fun scripted(): Int {
        if (mem.u8(0x500ACEL) == 0) return 0xFF
        var q = AttackRange.LIST
        while (mem.u8(q) != 0xFF) {
            if (mem.u32(BattleField.CURRENT_CHAR) == mem.u32(Units.unit(mem.u8(q)))) return 1
            q++
        }
        return 0
    }

    /**
     * 0x4389BD(目標, 位置)：是自己的目標 +0x62；目標有特效 (0x53, 0x6F) bit 0 → 低位元組設 0x65；不同方時
     * + max(預估傷害 × 100 / 目標 HP, 1)（0x47EF3D 或 0x401E1D 不成立時直接回傳 1）。健康狀態第 0 組 +8；
     * 有分時目標是主將（[0x496C96]）或格 0x3C +8，否則 +4；不到 0x4B 而且攻擊方系別在表 0x4D2188 是 1 → 0。
     * 有擴散攻擊（0x407835）時加上波及的單位（0x438BF3）。最後：目標從原位打得到 p 時 −1（至少留 1）。
     */
    private fun targetScore(a: Long, ts: Int, p: Int): Long {
        val t = Units.unit(ts)
        var score = 0L
        if (ts < 0xFA) {
            if (mem.u8(a + 8) == ts) score += 0x62
            if (effects.value(t, 0x53, 0x6F, 3, 0) and 1L != 0L) score = (score and 0xFFFFFF00L) or 0x65   // 0x44F9C9(1)
            if (effects.sideCategory(a) != effects.sideCategory(t)) {
                val dmg = combat.damage(a, t, 1, 0).value
                if (!combat.canEvade(a, t) || !canHurt(a, t)) return 1
                val v = ((dmg * 100) and M) / mem.u32(t + 0x10)
                score = (score + (if (v == 0L) 1 else v)) and M
            }
        }
        if (Units.health(mem, t, 2) != 0) score += 8
        if (score > 0) score += if (mem.u16(0x496C96L).toLong() == mem.u32(t) || ts == 0x3C) 8 else 4
        if (score < 0x4B && archerLike(a)) return 0
        val sp = spreadType(a)
        if (sp != 0xFF && sp != 0) {
            val list = spread(a, ts, sp, 0)
            score = adjust(list, score, 0, slotOf(a), 0xFF)
        }
        if (score > 0 && mem.u8(0x500E9AL) != 2) {
            val tp = pos(t)
            val desc = bf.range.descriptor(t)
            bf.scratch.fill(0, 0xC80, 0xC80 + 0x640)
            bf.range.collect(t, tp, desc, 0x80, 1)
            if (bf.scratch[0xC80 + index(p)].toInt() == 1 && score > 1) score--
        }
        return score
    }

    /**
     * 0x401E1D(目標)：攻擊方 [0x501C00 + 武將] ≥ [0x505F74]、有特效 (0x70, 0x34)、沒有 (0x33, 0x31) bit 0、
     * 目標沒有 (0x30, 0x41) → 成立。
     */
    private fun canHurt(a: Long, t: Long): Boolean {
        if (mem.u8(0x501C00L + mem.u32(a)) >= mem.u8(0x505F74L)) return true
        if (effects.value(a, 0x70, 0x34, 1, 1) != 0L) return true
        if (effects.value(a, 0x33, 0x31, 3, 0) and 1L == 0L) return true
        return effects.value(t, 0x30, 0x41, 1, 1) == 0L
    }

    /** 0x4190E8 == 1：系別 ≤ 0x27 而且表 0x4D2188[系別] = 1。 */
    private fun archerLike(a: Long): Boolean {
        val g = group(a)
        return g <= 0x27 && mem.u8(0x4D2188L + g) == 1
    }

    private fun group(u: Long): Int = mem.u8(charPtr(u) + 0x2B).let { c -> when { c <= 0x3B -> c / 3; c < 0x50 -> c - 0x28; else -> 0x28 } }

    // ---- 擴散攻擊 ----

    /**
     * 0x407835：擴散型態。正在用策略（[0x500E9A] = 2）而且策略資料 +0x0D ≠ 0 → 用它；否則裝備特效 0x33 的值，
     * 沒有就看兵種表 0x407881。[0x505FDC] ≠ 0 → 0；特效 (0xA7, 0x57) bit 0 時用表 0x45D343 升級。
     */
    fun spreadType(u: Long): Int {
        val cp = charPtr(u)
        var t = 0
        val rec = if (mem.u8(0x500E9AL) == 2) mem.u8(0x4A7510L + 16L * mem.u8(0x500E9BL) + 0x0D) else 0
        if (rec != 0) t = rec
        else {
            val v = effects.itemsOnly(cp, 0x33, 0, 5)
            t = if (v != 0L) (v and 0xff).toInt() else mem.u8(0x407881L + mem.u8(cp + 0x2B))
        }
        if (mem.u8(0x505FDCL) != 0) return 0                                             // 0x45D316
        val e = if (mem.u8(Effects.GAME_MODE) == 3) effects.check(cp, 0xA7, 0x57, 3, 0).eax else effects.character(cp, 0xA7, 0x57, 3, 0).eax
        return if (e and 1L != 0L) mem.u8(0x45D343L + t) else t
    }

    /**
     * 0x435941(目標, 1, 型態, 清單, 0xFF, 0xFF) → 0x4364C7 → 0x435CE9(目標位置, 型態, 清單, 旗標)：擴散波及的單位
     * （不含目標本身）。型態 1–10：表 0x406236 → 形狀（0x4D13A0，10 以攻擊方為中心）、0x60 目標兩側、0x61 往攻擊方向的直線
     * （4：1 格、5：5 格、7：2 格）。波及的格也記在 0x500E49（[0x500E7C] 個 byte）。旗標 0：不同方（[0x500C44] = 1 時都算）、
     * 1：同方、2：都算。強度 100 − 10 × 距離（至少 50）寫在 0x500E7D。
     */
    fun spread(a: Long, ts: Int, type: Int, flag: Int): List<Int> = spreadAt(a, pos(Units.unit(ts)), type, flag)

    /** 0x435CE9 本體：以 [center0] 為中心。 */
    fun spreadAt(a: Long, center0: Int, type: Int, flag: Int): List<Int> {
        val out = ArrayList<Int>()
        var count = 0
        var center = center0
        var limit = type
        val ap = pos(a)
        fun hit(x: Int, y: Int, order: Int) {
            count = spreadTile(a, x and 0xff, y and 0xff, order, count, flag, out)
        }
        if (type != 0 && type < 0x0B) {
            when (val k = mem.u8(0x406236L + type - 1)) {
                0x60 -> {
                    val (d0, d1) = sides(center, ap)
                    for ((dx, dy) in listOf(d0, d1)) hit((center and 0xff) + dx, ((center shr 8) and 0xff) + dy, 1)
                }
                0x61 -> {
                    val dx = step(ap and 0xff, center and 0xff)
                    val dy = step((ap shr 8) and 0xff, (center shr 8) and 0xff)
                    limit = when (type) { 5 -> 5; 4 -> 1; else -> 2 }
                    var x = center and 0xff
                    var y = (center shr 8) and 0xff
                    for (i in 0 until limit) {
                        y = (y + dy) and 0xff
                        x = (x + dx) and 0xff
                        hit(x, y, i + 1)
                    }
                }
                else -> {
                    if (type == 0x0A) center = ap
                    var p = mem.u32(SPREAD_SHAPES + 4L * k)
                    while (mem.u16(p) != 0xFFFF) {
                        val dx = mem.u8(p)
                        val dy = mem.u8(p + 1)
                        val r = maxOf(dx, dy)
                        val x = center and 0xff
                        val y = (center shr 8) and 0xff
                        if (dx == 0 && dy == 0) bf.gapNote("0x42399B 形狀 (0, 0)")
                        if (dx != 0 && dy != 0) {                                                  // 0x42399B
                            hit(x + dx, y - dy, r); hit(x + dx, y + dy, r); hit(x - dx, y + dy, r); hit(x - dx, y - dy, r)
                        } else if (r != 0) {
                            hit(x, y - r, r); hit(x + r, y, r); hit(x, y + r, r); hit(x - r, y, r)
                        }
                        p += 2
                    }
                }
            }
        }
        val n = mem.u8(TILE_COUNT)
        mem.setU8(TILES + n, 0xFF)
        mem.setU8(TILE_COUNT, limit)
        return out
    }

    /** 0x42230E 的一軸：攻擊方 == 目標 → 0，攻擊方較大 → −1，否則 +1（從攻擊方往目標的方向）。 */
    private fun step(av: Int, tv: Int) = when { av == tv -> 0; av > tv -> -1; else -> 1 }

    /** 0x4254FA(目標, 攻擊方)：目標兩側的兩個位移。 */
    private fun sides(t: Int, a: Int): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val tx = t and 0xff; val ty = (t shr 8) and 0xff
        val ax = a and 0xff; val ay = (a shr 8) and 0xff
        return when {
            ax == tx -> (1 to 0) to (-1 to 0)
            ay == ty -> (0 to 1) to (0 to -1)
            (ax > tx) == (ay > ty) -> (-1 to 1) to (1 to -1)
            else -> (1 to 1) to (-1 to -1)
        }
    }

    /**
     * 0x435C10(x, y)：記下這格（0x4174FF，位址 0x500E49 + [0x500E7C] 只加低位元組），格上有單位而且陣營符合旗標就加進清單、
     * 強度寫 0x500E7D + 數量。原版還比較清單位址的第 2 個 byte（0x28 → 略過 [0x492800]、0x7B → 略過 [0x497B05]），
     * 只有用全域清單時才可能成立；AI 評估用的是堆疊上的清單，不做。
     */
    private fun spreadTile(a: Long, x: Int, y: Int, order: Int, count: Int, flag: Int, out: MutableList<Int>): Int {
        if (x >= bf.width || y >= bf.height) return count
        val n = mem.u8(TILE_COUNT)
        val addr = 0x500E00L or ((0x49 + n) and 0xff).toLong()
        mem.setU16(addr, (x or (y shl 8)).toLong())
        mem.setU8(TILE_COUNT, (n + 2) and 0xff)
        val o = bf.occupantAt(x or (y shl 8))
        if (o == 0xFF) return count
        val same = effects.sideCategory(Units.unit(o)) == effects.sideCategory(a)
        val ok = when (flag) {
            0 -> mem.u8(0x500C44L) == 1 || !same
            1 -> same
            2 -> true
            else -> false
        }
        if (!ok) return count
        out += o
        val s = (0x64 - order * 10).let { if (it in 0 until 0x32) 0x32 else it and 0xff }
        mem.setU8(0x500E7DL + count, s)
        return (count + 1) and 0xff
    }

    /**
     * 0x438BF3(波及清單, 得分, 種類, 攻擊方, 策略)：清單空 → 得分 > 4 時 −2，否則 2。否則每個波及的單位依種類加分：
     * 0：策略（要 0x4390EF 合）——是自己的目標 +3、策略傷害會打死 +10 否則 +3；物理（策略 0xFF）——預估傷害會打死 +10 否則 +3。
     * 1：HP < 60% +5；2：攻擊狀態 < 4 +3；3、4：防禦狀態 < 4 +3；5：有健康狀態 +3（1–5 都要 0x4390EF 合）。結果 < 0 → 0。
     */
    private fun adjust(list: List<Int>, score0: Long, kind: Int, attacker: Int, strat: Int): Long {
        var score = score0.toInt()
        var bonus = 0
        val au = Units.unit(attacker)
        if (list.isEmpty()) {
            score = if (score > 4) score - 2 else 2
        } else for (t in list) {
            val tu = Units.unit(t)
            when (kind) {
                0 -> if (strat == 0xFF) {
                    bonus += if (combat.damage(au, tu, 1, 0).value >= mem.u32(tu + 0x10)) 10 else 3
                } else if (strategies.valid(attacker, t, strat)) {
                    if (mem.u8(au + 8) == t) bonus += 3
                    bonus += if (strategies.damage(au, tu, strat, 0) >= mem.u32(tu + 0x10)) 10 else 3
                }
                1 -> if (strategies.valid(attacker, t, strat) && hpPercent(tu) < 0x3C) bonus += 5
                2 -> if (strategies.valid(attacker, t, strat) && mem.u8(tu + 0x18) < 4) bonus += 3
                3, 4 -> if (strategies.valid(attacker, t, strat) && mem.u8(tu + 0x19) < 4) bonus += 3
                5 -> if (strategies.valid(attacker, t, strat) && mem.u8(tu + 0x1E) != 0) bonus += 3
            }
        }
        val r = score + bonus
        return if (r > 0) r.toLong() else 0L
    }

    /** 0x4078D1：HP × 100 / HP 上限（無號）。 */
    private fun hpPercent(u: Long): Long = ((mem.u32(u + 0x10) * 100) and M) / combat.maxHp(u)

    // ---- 策略得分 ----

    private val strategies get() = bf.strategies

    /** 0x442A26(單位, 2, 0x4AC808)：可用的策略清單。 */
    fun buildStrategies(u: Long) {
        strategies.build(u, 2, STRATEGIES)
    }

    /**
     * 0x439169(&位置, 旗標)：清單裡每個策略：範圍（0x21 用攻擊範圍 0x43F8C0，其他 0x401DFF）。範圍 byte 0 = 0xFF（以自己為中心）→
     * 0x4358DF 的波及單位得分（0x4395DC，加總 > 0 時再加 0x40C854）加總；否則收集範圍內的目標（旗標 ≠ 0 時目標就是旗標），
     * 每個目標得分（> 0 時加 0x40C854）。取最高，旗標 0 時記目標 [0x4B2C2C]、策略 [0x4B2C40]。
     */
    fun strategyScore(u: Long, p: Int, flag: Int): Long {
        if (flag == 0) {
            mem.setU8(STRAT_TARGET, 0xFF)
            mem.setU8(STRAT_BEST, 0xFF)
        }
        var best = 0L
        var q = STRATEGIES
        while (true) {
            val s = mem.u8(q)
            if (s == 0xFF) break
            q++
            val range = if (s == 0x21) bf.range.descriptor(u) else strategies.rangeOf(u, s)
            val side = strategies.side(s)
            if (side == 2) continue
            if (range and 0xff == 0xFFL) {
                bf.gapNote("0x439169 範圍 0xFF 的策略（原版 0x41A10C 的 ecx 沒清乾淨）")
                val area = strategies.areaOf(u, s)
                val mode = if (area != 0) mem.u8(strategies.record(s) + 0x0B) + 1 else 0
                val list = if (mode == 0) {
                    if (bf.occupantAt(p) == 0xFF) listOf(slotOf(u)) else emptyList()
                } else spreadAt(u, p, area, if (mode == 1) 0 else 1)
                var sum = 0L
                for (t in list) {
                    sum = (sum + targetStrategyScore(u, t, s, p, flag)) and M
                    if (sum > 0) sum = (sum + priority(u, t)) and M
                }
                if (sum > best) {
                    best = sum
                    if (flag == 0) { mem.setU8(STRAT_TARGET, 0xFF); mem.setU8(STRAT_BEST, s) }
                }
                continue
            }
            val targets = ArrayList<Int>()
            if (flag != 0) {
                mem.setU32(AttackRange.LIST, flag.toLong())
            } else {
                bf.range.collect(u, p, range, side or 0x80, 0)
                val sc = scripted()
                if (sc != 0xFF) return sc.toLong()
            }
            var r = AttackRange.LIST
            while (mem.u8(r) != 0xFF) targets += mem.u8(r++)
            for (t in targets) {
                var v = targetStrategyScore(u, t, s, p, flag)
                if (v == 0L) continue
                v = (v + priority(u, t)) and M
                if (v > best) {
                    best = v
                    if (flag == 0) { mem.setU8(STRAT_TARGET, t); mem.setU8(STRAT_BEST, s) }
                }
            }
        }
        return best
    }

    /** 0x40C854(施放者, 目標)：不同方而且目標有特效 (0x53, 0x6F) bit 1 → 0x65；否則是自己的目標 → 0x62。 */
    private fun priority(u: Long, t: Int): Long {
        val tu = Units.unit(t)
        if (effects.sideCategory(tu) != effects.sideCategory(u) && effects.value(tu, 0x53, 0x6F, 3, 0) and 2L != 0L) return 0x65
        return if (mem.u8(u + 8) == t) 0x62 else 0
    }

    /**
     * 0x4395DC(目標, 策略, 位置, 地形, 旗標)：依策略類別（表 0x4D1600，[0x505FDC] 時 0x4D1690）算對這個目標的得分。
     * 細節見 docs/battle.md「策略得分」。有波及型態時最後用波及清單修正（0x438BF3）。
     */
    private fun targetStrategyScore(a: Long, ts: Int, s: Int, p: Int, flag: Int): Long {
        val t = Units.unit(ts)
        val tc = charPtr(t)
        val range = strategies.rangeOf(a, s)
        val area = strategies.areaOf(a, s)
        val mode = if (area != 0) mem.u8(strategies.record(s) + 0x0B) + 1 else 0
        var list: List<Int> = emptyList()
        val hpPct = hpPercent(t).toInt()
        var score = 0L
        val slotA = slotOf(a)
        if (range and 0xff != 0xFFL) {
            if (mode != 0) list = spread(a, ts, area, if (mode == 1) 0 else 1)
            if (!strategies.valid(slotA, ts, s)) return 0
        }
        if (s >= 0x90) return 0
        val cat = mem.u8((if (mem.u8(0x505FDCL) != 0) 0x4D1690L else 0x4D1600L) + s)
        var kind: Int          // 0x438BF3 的種類
        when (cat) {
            0, 1, 5 -> {
                if (!strategies.valid(slotA, ts, s)) return 0
                val d = strategies.damage(a, t, s, flag)
                kind = 0
                if (d.toInt() >= 0) {
                    var v = ((d * 100) and M) / mem.u32(t + 0x10)
                    if (v == 0L) v = 1
                    if (v == 0x64L) score += v
                    else {
                        score = v
                        if (s == 0x2B) score += statusBonus(t)
                        else if (s in 0x1E..0x31) {
                            when {
                                s == 0x1E -> {
                                    if (mem.u32(a + 0x10) < combat.maxHp(a)) score += 5
                                    if (lowHealth(a)) score += 0x0A
                                }
                                s >= 0x30 -> if (!adjacentUnits(a, t)) score += 0x1B
                                hpPct >= 0x28 -> score += 8
                            }
                        }
                    }
                }
            }
            4 -> {
                if (!strategies.valid(slotA, ts, s)) return score
                if (strategies.hitRate(0, a, t, s) < 0x23) return score
                score += 0x1A
                if (hpPct >= 0x50) score += 0x1A
                kind = 0
            }
            0x0B -> {
                val h = mem.u8(t + 0x1E)
                var count = 0
                for (i in 0 until 4) {
                    if (h and mem.u8(0x4240D2L + i) == 0) continue
                    val c = mem.u8(0x412793L + i)
                    if ((score and 0xff) < c) score = (score and 0xFFFFFF00L) or c.toLong()
                    count++
                }
                score = (score and 0xFFFFFF00L) or ((score + count + 5) and 0xff)
                kind = 5
            }
            7, 8, 9 -> {
                if (!lowHealth(t)) return score
                score += 0x32
                when (s) {
                    0x37 -> return score
                    0x38 -> return score + 5
                    0x3B -> return score + 0x0A
                }
                kind = 1
            }
            3 -> {
                if (strategies.valid(slotA, ts, s) && mem.u16(a + 0x14) <= Equipment.maxMp(mem, mem.u16(a)) / 5 &&
                    mem.u16(t + 0x14) >= 0x1E) score += 0x14
                return score
            }
            6 -> {
                if (mem.u8(0x4D2188L + strategies.group(tc)) == 2 || strategies.hitRate(0, a, t, s) >= 0x3C) {
                    score += 0x19
                    if (hpPct.toByte() >= 0x50) score += 7
                }
                return score
            }
            0x0A -> {
                if (mem.u16(t + 0x14) > Equipment.maxMp(mem, mem.u16(t)) / 5) return score
                if (mem.u16(a + 0x14) < strategies.cost(s) + 6) return score
                val g = strategies.group(tc)
                when {
                    g == 9 -> score += 1
                    strategies.attackKind(t) != 0 || effects.hasItemCode(tc, 0x48) -> score += 0x0C
                    g == 0 -> score += 6
                }
                return score
            }
            0x0C -> {
                val ai = mem.u8(t + 0x0E)
                if (ai == 3 || ai == 2) return score
                if (s == 0x59 && learned(tc, s)) return score
                val g = strategies.group(tc)
                when (g) {
                    9 -> {}
                    0, 0x13 -> score += 0x0F
                    8 -> score += 0x0A
                    else -> score += bf.rng.below(if (g == 5) 5 else 4) + 0x0A
                }
                return score
            }
            2 -> {
                if ((hpPercent(a) and 0xff) > 0x28 && strategies.attackKind(a) == 0) {
                    score += 0x64
                    if (flag == 0) score += physicalScore(a, p)
                }
                return score
            }
            0x0E, 0x0F -> {
                val k = strategies.attackKind(t)
                val d = (((s - (if (cat == 0x0E) 0x23 else 0x41)) and 0xff) shr 1)
                fun st(i: Int) = mem.u8(t + 0x18 + d + i)
                val add = if (cat == 0x0E) {
                    if (d != 0) { if (st(0) >= 3) 0x0C else 0 }
                    else when (k) {
                        0 -> if (st(0) > 3) 0x0A else 0
                        1 -> if (st(2) > 3) 0x0A else 0
                        else -> if (st(0) > 3 || st(2) > 3) 0x0B else 0
                    }
                } else {
                    if (d != 0) { if (st(0) < 3) 0x0B else 0 }
                    else when (k) {
                        0 -> if (st(0) < 3) 0x0A else 0
                        1 -> if (st(2) < 3) 0x0A else 0
                        else -> if (st(0) < 3 || st(2) < 3) 0x0B else 0
                    }
                }
                if (add == 0) return score
                score += add
                kind = 0
            }
            else -> return score
        }
        if (area and 0xff != 0) score = adjust(list, score, kind, slotA, s)
        return score
    }

    /**
     * 0x45AFCA(a 武將, b 武將, 1)：戰場狀態下，兩個武將（各取第一個在場、HP > 0 的單位，0x42466D）八方相鄰。
     * 武將 ≥ 0x400 的情況（0x404E16、目前行動的單位）還沒移植。
     */
    private fun adjacentUnits(a: Long, b: Long): Boolean {
        if (mem.u8(Effects.GAME_MODE) != 3) return false
        val ca = mem.u32(a)
        val cb = mem.u32(b)
        if (ca and 0xffff >= 0x400 || cb and 0xffff >= 0x400) { bf.gapNote("0x45AFCA 武將 ≥ 0x400"); return false }
        fun first(c: Long): Int {
            for (s in 0 until 0xFA) {
                val u = Units.unit(s)
                if (mem.u32(u) == c && mem.u8(u + 0x0C) == 2 && mem.u32(u + 0x10) != 0L) return s
            }
            return 0xFF
        }
        val sa = first(ca)
        if (sa == 0xFF) return false
        val sb = first(cb)
        if (sb == 0xFF) return false
        val p = pos(Units.unit(sa))
        for (d in 0 until 8) {
            val n = bf.neighbor(p, d)
            if (n and 0xff == 0xFF) continue
            if (bf.occupantAt(n) == sb) return true
        }
        return false
    }

    /** 0x417A61：10 + 攻／防／精狀態 ≥ 3 的各加表 0x481DED 的值（> 3 再 +1）。 */
    private fun statusBonus(t: Long): Long {
        var v = 0x0A
        for (i in 0 until 3) {
            val st = mem.u8(t + 0x18 + i)
            if (st < 3) continue
            v = (v + mem.u8(0x481DEDL + i)) and 0xff
            if (st != 3) v++
        }
        return v.toLong()
    }

    /** 0x439CFE：HP > 0 而且 HP ≤ 上限 × 2/5（武將 ≥ 0x400：兵種 0x31／系別 0x32 除外，上限 / 5）。 */
    private fun lowHealth(u: Long): Boolean {
        val hp = mem.u32(u + 0x10)
        if (hp == 0L) return false
        val max = combat.maxHp(u)
        if (mem.u32(u) < 0x400) return ((max * 2) and M) / 5 >= hp
        val cp = charPtr(u)
        if (mem.u8(cp + 0x2B) == 0x31 || strategies.group(cp) == 0x32) return false
        return max / 5 >= hp
    }

    /** 0x41832B(武將, 策略) 的 al：兵種習得等級 ≠ 0 而且等級夠。 */
    private fun learned(cp: Long, s: Int): Boolean {
        val need = mem.u8(strategies.record(s) + 0x11 + mem.u8(cp + 0x2B))
        return need != 0 && mem.u8(cp + 0x2C) >= need
    }

    // ---- 地形價值 ----

    /**
     * 0x452E18(&位置)：地形 0x12–0x16（村莊、兵營…）：HP 不到上限 20% → +8，否則 +1；
     * 單位原位（0x43F7B8(0x1E)）的適性 > 100 → + (適性 − 100) / 8。
     */
    fun terrainValue(u: Long, p: Int): Int {
        var v = 0
        val t = search.terrainIn(p)
        if (t in 0x12..0x16) v += if (lowHp(u, 20)) 8 else 1
        val apt = search.moveCost(u, 0x1E).second
        if (apt > 100) v += (apt - 100) shr 3
        return v and 0xff
    }

    /** 0x4221DD(k) == 0：[0x500AF0] ≠ 2 而且 HP < HP 上限 × k%（上限 × k% 為 0 時也算 0）。 */
    private fun lowHp(u: Long, k: Int): Boolean {
        if (mem.u8(0x500AF0L) == 2) return false
        val r = CombatStats.percent(combat.maxHp(u), k.toLong())
        return mem.u32(u + 0x10) < r || r == 0L
    }

    // ---- 路徑 ----

    /**
     * 0x437E23(&終點, &起點, 上限, 旗標)：從起點沿花費表往下走到終點（每步挑四鄰中花費最小、移動花費不超過剩下上限的格；
     * ZOC 的格只有終點或幽靈單位能進；上限 ≠ 0xFF 時還要 0x4077F6）。走不到時旗標 1 會重試或在停下的格加權（0x43817B）。
     * 步數寫 [0x500C48]，[path] 反轉成從終點出發的方向（原版從起點往回走，所以是移動路徑）。
     */
    fun walkPath(u: Long, dest: Int, start: Int, limit0: Int, flag: Int): ByteArray {
        val ghost = mem.u8(u + 0x0D) and 0x80 != 0
        var retries = 4
        var n: Int
        while (true) {
            var cur = start
            n = 0
            var ok = true
            var limit = limit0 and 0xff
            while (true) {
                if (cur == dest || !ok) break
                var best = search.cost[index(cur)].toInt() and 0xff
                var bestDir = 0xFF
                var bestCost = 0
                var d = if (retries == 4) 0 else retries
                while (d < 4) {
                    val nb = bf.neighbor(cur, d)
                    d++
                    if (nb and 0xff == 0xFF) continue
                    if (search.zoc(u, nb, 1) && nb != dest && !ghost) continue
                    if (limit0 and 0xff != 0xFF && !withinLimits(u, nb)) continue
                    val nv = search.cost[index(nb)].toInt() and 0xff
                    if (best <= nv) continue
                    val c = search.moveCost(u, search.terrainIn(nb)).first
                    if (limit < c) continue
                    best = nv
                    bestDir = d - 1
                    bestCost = c
                }
                if (bestDir != 0xFF) {
                    cur = bf.neighbor(cur, bestDir)
                    limit = (limit - bestCost) and 0xff
                    path[n] = bestDir.toByte()
                } else ok = false
                n++
            }
            if (!ok && flag == 1) {
                val ai = mem.u8(u + 0x0E)
                if (bf.occupantAt(cur) == 0xFF && !canStepDown(cur, u, limit)) {
                    weight(cur, 0x0A, slotOf(u))
                } else if ((ai == 0 || ai == 4) && retries != 0) {
                    search.cost[index(cur)] = 0xFF.toByte()                                    // 0x43FF64(1, 格, 0)
                    retries--
                    continue
                } else weight(cur, 0x14, slotOf(u))
            }
            break
        }
        mem.setU8(PATH_LENGTH, n and 0xff)
        var i = 0
        var j = n - 1
        if (j < 0) path[0] = 0xFF.toByte()
        while (j >= 0 && i <= j) {
            val tmp = path[i].toInt() and 0xff
            path[i] = ((path[j] + 2) and 3).toByte()
            path[j] = ((tmp + 2) and 3).toByte()
            i++
            j--
        }
        return path
    }

    /** 0x43FF64(上限, 格, 單位)：四鄰有花費比這格小、而且移動花費 ≤ 上限（有號 byte 比較）的格。上限 0 → 不成立。 */
    private fun canStepDown(p: Int, u: Long, limit: Int): Boolean {
        if (limit and 0xff == 0) return false
        val here = search.cost[index(p)].toInt() and 0xff
        for (d in 0 until 4) {
            val nb = bf.neighbor(p, d)
            if (nb and 0xff == 0xFF) continue
            if (here <= (search.cost[index(nb)].toInt() and 0xff)) continue
            val c = search.moveCost(u, search.terrainIn(nb)).first
            if (limit.toByte() >= c.toByte()) return true
        }
        return false
    }

    /**
     * 0x4077F6(格)：行動方式不是 7、bit 0 有設（1 主動、3 被動…）而且 +0x20 ≠ 0xFFFF 時，要在 +0x09 與 +0x20 圍成的範圍內
     * （0x414F05，還沒移植）。
     */
    fun withinLimits(u: Long, p: Int): Boolean {
        val ai = mem.u8(u + 0x0E)
        if (ai == 7 || ai and 1 == 0 || mem.u16(u + 0x20) == 0xFFFF) return true
        bf.gapNote("0x414F05 AI 行動範圍")
        return true
    }

    /**
     * 0x43817B(&格, 權重, 格號)：權重 < 9 不做。單位原位地形的適性 ≠ 0xFF、這格的得分（scratch +0x640）是 0、
     * 格上沒人或就是單位自己 → 加上權重。兵種不是 15、16 時往四鄰遞迴（權重 > 10 → −10，否則 −1）。
     */
    fun weight(p: Int, w: Int, slot: Int) {
        if (w < 9) return
        val i = 0x640 + index(p)
        val u = Units.unit(slot)
        if (search.moveCost(u, 0x1E).second != 0xFF && bf.scratch[i].toInt() == 0) {
            val o = bf.occupantAt(p)
            if (o == 0xFF || p == pos(u)) bf.scratch[i] = (bf.scratch[i] + w).toByte()
        }
        val cls = mem.u8(charPtr(u) + 0x2B)
        if (cls in 0x0F..0x10) return
        for (d in 0 until 4) {
            val nb = bf.neighbor(p, d)
            if (nb and 0xff == 0xFF) continue
            weight(nb, if (w > 10) w - 10 else w - 1, slot)
        }
    }

    companion object {
        private const val M = 0xFFFFFFFFL
        const val ALT_POS = 0x48E8C2L
        /** 物理攻擊得分最高的目標。 */
        const val PHYS_TARGET = 0x4B2C30L
        const val PATH_LENGTH = 0x500C48L
        /** 最佳結果：位置（word）、目標、得分、策略。 */
        const val BEST_POS = 0x4B2C20L
        const val BEST_TARGET = 0x4B2C28L
        const val BEST_SCORE = 0x4B2C4CL
        const val BEST_STRATEGY = 0x4B2C44L
        /** 策略得分最高的目標、策略（0x439169 寫）。 */
        const val STRAT_TARGET = 0x4B2C2CL
        const val STRAT_BEST = 0x4B2C40L
        /** 不要停的格（word，0xFF = 沒有）。 */
        const val AVOID = 0x4B2C38L
        /** 單位可用的策略清單（0x442A26 建，0xFF 結尾）。 */
        const val STRATEGIES = 0x4AC808L
        private const val SPREAD_SHAPES = 0x4D13A0L
        private const val TILES = 0x500E49L
        private const val TILE_COUNT = 0x500E7CL
    }
}
