package ccz.battle

import ccz.data.Characters
import ccz.data.Equipment
import ccz.script.EngineMemory

/**
 * 移動相關的原版函式：移動力 0x43F74F、移動花費 0x43F7B8、ZOC 0x437D59、移動範圍搜尋 0x438484。
 * 規則見 docs/battle.md「移動」。用 tools/re/gen_range_vectors.py 跟原版比對（RangeTest）。
 */
class Search(private val bf: BattleField, private val gap: (String) -> Unit) {
    private val mem: EngineMemory get() = bf.mem
    val effects = Effects(bf)

    /** 搜尋結果的花費表（原版戰場緩衝區 +0x12C0）：每格累計花費，0xFF = 沒到過。 */
    val cost = ByteArray(0x640)

    private fun unit(slot: Int) = Units.unit(slot)
    private fun charPtr(unit: Long) = Characters.addr(mem, mem.u16(unit))
    private fun pos(unit: Long) = mem.u8(unit + 6) or (mem.u8(unit + 7) shl 8)
    private fun index(p: Int) = ((p shr 8) and 0xff) * bf.width + (p and 0xff)

    // ---- 移動力 ----

    /** 0x43F74F：麻痺（健康狀態第 0 組）→ 0；移動狀態 +0x1D ≥ 4 → +2（最多 0xFF）、≠ 3 → −2（最少 0）。 */
    fun movePoints(u: Long): Int {
        val mp = basePoints(charPtr(u))
        if (mem.u8(u + 0x1E) and 3 != 0) return 0                // 0x423FE8(2)
        val st = mem.u8(u + 0x1D)
        return when {
            st >= 4 -> minOf(mp + 2, 0xFF)
            st != 3 -> if (2 < mp) mp - 2 else 0
            else -> mp
        }
    }

    /** 0x4072BB：min(裝備加成（項目 7） + 兵種移動力 + MOD 加成, 0x7F)。 */
    private fun basePoints(cp: Long): Int {
        val ch = ((cp - mem.u32(Characters.PTR)) / Characters.SIZE).toInt()
        val bonus = Equipment.bonus(mem, ch, 7).toInt()
        var base = mem.u8(CLASSES + 0x23L * mem.u8(cp + 0x2B))                 // 0x41B782
        if (mem.u8(0x505F38L) != 0 && mem.u16(cp + 0x35) >= 100) base++       // 0x405B86(0x7F, …)
        return (bonus + base).let { if (it > 0x7F) 0x7F else it } and 0xff
    }

    // ---- 地形與花費 ----

    /** 0x412557：地形；格上的單位有特效 171 而且值有 bit 4 → 地形 19。 */
    fun terrainAt(p: Int): Int {
        val x = p and 0xff
        val y = (p shr 8) and 0xff
        val t = bf.map.terrain(x, y)
        val o = bf.occupant[y * bf.width + x].toInt() and 0xff
        if (o != 0xFF) {
            val v = effects.value(unit(o), 0xAB, 0x2A, 0, 0)
            if (v != 0L && v and 4 != 0L) return 0x13
        }
        return t
    }

    /** 0x435991：地圖內的地形，外面 0xFF。 */
    private fun terrainIn(p: Int): Int =
        if ((p and 0xff) < bf.width && ((p shr 8) and 0xff) < bf.height) terrainAt(p) else 0xFF

    /**
     * 0x43F7B8：單位進入地形 [t] 的 (花費, 適性)。兵種系別 ≥ 40 或 [0x505FDC] ≠ 0 → 不能進入。
     * 地形 ≥ 30 用單位腳下（或 [0x48E8C2]）的地形；兵種地形表（0x4A3517 + 60·系別）的適性與花費（0x412B5F：
     * 相鄰的敵人有特效 0x60 時至少 0x80）；地形狀態特效 169、170（0x435BB1）；62 號適性至少 110；63 號可進入的花費 1；
     * 54 號穿越移動：≥ 0x80 → 1，> 1 → −1。
     */
    fun moveCost(u: Long, t0: Int): Pair<Int, Int> {
        val cp = charPtr(u)
        val group = classGroup(mem.u8(cp + 0x2B))
        if (mem.u8(0x505FDCL) != 0) return 0xFF to 0x64
        if (group >= 0x28) return 0xFF to 0
        var t = t0
        if (t >= 0x1E) t = terrainIn(positionFor(u, t - 0x1E))
        val row = TERRAIN + 60L * group
        var apt = mem.u8(row + t)                                           // 0x41F62A
        var cost = rowCost(u, row, t)
        if (cost != 0xFF) {
            val (a, n) = terrainState(u, t)
            if (a != 0 && apt < a) apt = a
            if (n != 0) cost = n
        }
        if (effects.value(u, 62, 42, 1, 1) != 0L && apt < 0x6E) apt = 0x6E   // 0x478E39
        if (effects.value(u, 63, 42, 1, 1) != 0L && cost != 0xFF) cost = 1   // 0x478E4B
        if (passThrough(u)) {
            if (cost >= 0x80) cost = 1 else if (cost > 1) cost--
        }
        return cost to apt
    }

    /** 0x40BF0C／0x40687F：兵種系別。 */
    private fun classGroup(c: Int): Int = when {
        c <= 0x3B -> c / 3
        c < 0x50 -> c - 0x28
        else -> 0x28
    }

    /**
     * 0x407BF6(地形 − 30)：我軍、k = 2、[0x48E8C2] = 0xFFFF → 單位位置；k = 1 → [0x48E8C2]；
     * 其他看 [0x505F84]：0 → 單位位置，否則 [0x48E8C2]。
     */
    fun positionFor(u: Long, k: Int): Int = when {
        mem.u8(u + 0x0E) == 7 || k == 2 || mem.u16(ALT_POS) == 0xFFFF -> pos(u)
        k == 1 || mem.u32(0x505F84L) != 0L -> mem.u16(ALT_POS)
        else -> pos(u)
    }

    /** 0x412B5F：花費 = row[30 + 地形]；+0x0D < 0x80 而且相鄰不同方的單位有特效 0x60 時至少 0x80。 */
    private fun rowCost(u: Long, row: Long, t: Int): Int {
        var floor = 0
        if (mem.u8(u + 0x0D) < 0x80) {
            val side = effects.sideCategory(u)
            for (d in 0 until 4) {
                val n = bf.neighbor(pos(u), d)
                if (n and 0xff == 0xFF) continue
                val o = bf.occupantAt(n)
                if (o == 0xFF) continue
                val nb = unit(o)
                if (side == effects.sideCategory(nb)) continue
                if (effects.value(nb, 0x60, 0x7A, 1, 1) != 0L) { floor = 0x80; break }
            }
        }
        val c = mem.u8(row + 0x1E + t)
        return if (c < floor) floor else c
    }

    /** 0x435BB1：特效 169、170 的值跟地形表 0x435BF2[地形] 有交集 → 適性 120；有任一個 → 花費 1。 */
    private fun terrainState(u: Long, t: Int): Pair<Int, Int> {
        var any = false
        for (i in 0 until 2) {
            val v = effects.value(u, 0xA9 + i, 0x2A, 0, 0)
            if (v == 0L) continue
            any = true
            if (mem.u8(TERRAIN_MASK + t).toLong() and v and 0xff != 0L) return 0x78 to 1
        }
        return 0 to (if (any) 1 else 0)
    }

    /** 0x403A6F：特效 54 穿越移動（[0x500AD3] = 1 時一律成立）。 */
    fun passThrough(u: Long): Boolean = mem.u8(0x500AD3L) == 1 || effects.value(u, 0x36, 0x5D, 1, 1) != 0L

    // ---- ZOC ----

    /**
     * 0x437D59：單位停在 [p] 時不能再往外走。陷阱／伏擊（0x428B7A）成立；否則有穿越移動、55 突擊移動時不受 ZOC；
     * 四鄰有敵對單位（0x42E2B7）就停下。
     */
    fun zoc(u: Long, p: Int, flag: Int = 0): Boolean {
        if (trap(u, p)) return true
        if (passThrough(u)) return false
        if (effects.value(u, 55, 41, 1, 1) != 0L) return false              // 0x472C65
        for (d in 0 until 4) {
            val n = bf.neighbor(p, d)
            if (n and 0xff == 0xFF) continue
            val o = bf.occupantAt(n)
            if (o == 0xFF) continue
            if (hostile(u, unit(o), flag != 0)) return true
        }
        return false
    }

    /**
     * 0x428B7A(格, 0, 旗標)：我軍單位（+0x0D 沒有 0x80、+0x0E = 7）要看敵人的伏擊（0x428A53，特效 0x52），
     * 其他看格上的陷阱（0x4749B5，表 0x510000）。兩者都還沒移植：遇到時記 gap、當成沒有。
     */
    private fun trap(u: Long, p: Int): Boolean {
        val player = mem.u8(u + 0x0D) and 0x80 == 0 && mem.u8(u + 0x0E) == 7
        if (player && bf.occupantAt(p) == 0xFF) {
            val side = effects.sideCategory(u)
            for (s in 0 until 0xFA) {
                val e = unit(s)
                if (mem.u8(e + 0x0C) != 2 || effects.sideCategory(e) == side || mem.u32(e + 0x10) == 0L) continue
                val v = effects.value(e, 0x52, 0x51, 0, 0)
                if (v != 0L && mem.u8(e + 0x22) < (v and 0xff)) { gap("0x428A53 伏擊（特效 0x52）"); break }
            }
        }
        if (classGroup(mem.u8(charPtr(u) + 0x2B)) == 0x19) return false
        val x = p and 0xff
        val y = (p shr 8) and 0xff
        if (mem.u8(TRAPS + 2L * (y * (bf.width - 1) + x)) != 0) gap("0x4749B5 陷阱")
        return false
    }

    /** 0x42E2B7：兩個單位的陣營分類不同（分類函式：[flag] 時 0x406710，否則表 0x42E2E5[[0x505ECC]]）。 */
    fun hostile(a: Long, b: Long, flag: Boolean = false): Boolean {
        val f: (Long) -> Int = if (flag || mem.u8(0x505ECCL) == 0) effects::sideCategory else ::slotCategory
        return f(a) != f(b)
    }

    /** 0x42E2ED：依格號的陣營分類（格 0–19 我軍、20–59 友軍、60 起看 +5）。 */
    private fun slotCategory(u: Long): Int {
        val slot = ((u - Units.UNITS) / Units.SIZE).toInt()
        val raw = when {
            slot < 0x14 -> 0
            slot < 0x3C -> 1
            else -> {
                val s = mem.u8(u + 5)
                val extra = mem.u8(0x505F54L) != 0
                when (s) {
                    2 -> 2
                    5 -> if (mem.u8(0x501AD8L + slot) != 0) 5 else 2
                    3 -> if (extra) 4 else 3
                    6 -> if (mem.u8(0x501AD8L + slot) != 0) 6 else if (extra) 4 else 3
                    else -> s
                }
            }
        }
        return mem.u8(SIDE_TABLE + raw)
    }

    // ---- 搜尋 ----

    /**
     * 0x438484(&單位+6, 上限, 模式, 目標, 旗標)：從單位位置逐層展開（每次處理花費等於目前層的格，下一層 = 新到的格裡最小的花費），
     * 結果在 [cost]。模式 2：到了目標就回傳 1；8：到了旁邊有單位的空格就回傳那個單位（原版另一張表，還沒移植）；
     * 4：每格評估攻擊（0x405DA7，還沒移植）。旗標 bit 0：要看 ZOC、不能穿過敵人。回傳 0xFF = 沒有結果。
     */
    fun search(u: Long, limit: Int, mode: Int = 0, target: Int = 0xFFFF, flag: Int = 1): Int {
        val start = pos(u)
        if (start == target) return 0xFF
        val w = bf.width
        val h = bf.height
        cost.fill(0xFF.toByte())
        cost[index(start)] = 0
        val zocFlag = flag and 1
        val ghost = mem.u8(u + 0x0D) and 0x80 != 0                         // 0x425AF0(0x80)
        if (mode and 4 != 0) { gap("0x438484 模式 4（攻擊評估 0x405DA7）"); return 0xFF }
        if (moveCost(u, terrainIn(start)).first == 0xFF) return 0xFF
        var level = 0
        var progress = true
        while (true) {
            if (!progress || level >= limit) return 0xFF
            progress = false
            var next = 0xFF
            var nextMin = 0xFF
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = cost[y * w + x].toInt() and 0xff
                    if (c > level && c != 0xFF && nextMin > c) nextMin = c
                    if (c != level) continue
                    val p = x or (y shl 8)
                    if (p != start && zocFlag == 1 && zoc(u, p)) continue
                    for (d in 0 until 4) {
                        val n = bf.neighbor(p, d)
                        if (n and 0xff == 0xFF || !bf.insideRect(n)) continue
                        val i = index(n)
                        if (cost[i].toInt() and 0xff != 0xFF) continue
                        val sc = moveCost(u, terrainIn(n)).first
                        if (sc == 0xFF || sc + c > limit) continue
                        if (limit != 0xFF && mode != 0 && flag != 3) { gap("0x4077F6 AI 行動範圍限制"); }
                        val o = bf.occupant[i].toInt() and 0xff
                        if (o != 0xFF && flag != 2 && !passThrough(u) && hostile(u, unit(o))) {
                            if (zocFlag == 1 || !ghost) continue
                        }
                        val v = (c + sc) and 0xff
                        cost[i] = v.toByte()
                        progress = true
                        if (next > v) {
                            if (zocFlag == 1 && !ghost) {
                                if (!zoc(u, n)) next = if (v > nextMin) nextMin else v
                            } else next = v
                        }
                        if (mode and 8 != 0 && bf.occupantAt(n) == 0xFF) gap("0x438484 模式 8")
                        if (mode and 2 != 0 && n == target) return 1
                    }
                }
                if (!progress) {
                    if (nextMin != 0xFF) { next = nextMin; progress = true }
                } else if (next > nextMin) next = nextMin
            }
            level = next
        }
    }

    companion object {
        /** 兵種資料（0x41B782）：0x4A2A27 + 0x23·兵種，+0 移動力。 */
        private const val CLASSES = 0x4A2A27L
        /** 兵種地形表（0x41B7D2）：0x4A3517 + 60·系別，+地形 = 適性、+30 + 地形 = 花費。 */
        private const val TERRAIN = 0x4A3517L
        private const val TERRAIN_MASK = 0x435BF2L
        private const val ALT_POS = 0x48E8C2L
        private const val TRAPS = 0x510000L
        private const val SIDE_TABLE = 0x40672DL
    }
}
