package ccz.battle

import ccz.data.Characters
import ccz.data.Stats
import ccz.script.EngineMemory
import ccz.script.Instr

/**
 * 戰場劇本的條件指令（原版處理函式回傳 al 0/1）。規則見 docs/script.md「戰場條件指令」。
 * 用 tools/re/gen_test_vectors.py 跟原版比對（TestOpsTest）。回傳 null = 遇到還沒移植的路徑（記在 [gap]）。
 */
class BattleTests(private val bf: BattleField, private val results: IntArray, private val gap: (String) -> Unit) {
    private val mem: EngineMemory get() = bf.mem

    fun test(ins: Instr): Boolean? {
        val p = ins.params.map { it.value }
        return when (ins.op) {
            0x3F -> turnTest(p[0], p[1])
            0x40 -> mem.u8(BATTLE + 0x10) == (p[0] and 0xff)
            0x25 -> if ((p[0] and 0xffff) > 0x4FD) false else atPosition(p[0] and 0xffff, p[1], p[2])
            0x26 -> if ((p[0] and 0xffff) > 0x4FD) false else inArea(p[0] and 0xffff, p[1], p[2], p[3], p[4])
            0x2E -> adjacent(p[0] and 0xffff, p[1] and 0xffff, p[2] and 0xffff)
            0x36 -> statusTest(p[0] and 0xffff, p[1] and 0xffff, p[2], p[3] and 0xffff)
            0x41 -> countTest(p)
            0x42 -> { checkVictory(); results[RESULT_WIN] == 1 }
            0x43 -> { checkDefeat(); results[RESULT_LOSE] == 1 }
            else -> { gap("條件指令 %02x".format(ins.op)); null }
        }
    }

    /** 0x3F 回合测试（0x411664）：回合 = 戰場物件 +2 + 1；比較 0 ≥、1 <、其他 =（無號）。 */
    private fun turnTest(n: Int, cmp: Int): Boolean {
        val turn = mem.u8(BATTLE + 2) + 1L
        val v = n.toLong() and 0xffffffffL
        return when {
            cmp == 0 -> turn >= v
            cmp and 0xff == 1 -> turn < v
            else -> turn == v
        }
    }

    /** 0x421E00：比較 0 ≥、1 <、其他 =（無號）。 */
    private fun compare(cmp: Int, a: Long, b: Long): Boolean {
        val x = a and 0xffffffffL
        val y = b and 0xffffffffL
        return when (cmp and 0xff) {
            0 -> x >= y
            1 -> x < y
            else -> x == y
        }
    }

    // ---- 位置 ----

    /** 0x435884：格上的單位格號（< 0xFA），沒有或地圖外 0xFF。 */
    private fun occupantAt(x: Int, y: Int): Int {
        if (x >= bf.width || y >= bf.height) return 0xFF
        val v = bf.occupant[y * bf.width + x].toInt() and 0xff
        return if (v < 0xFA) v else 0xFF
    }

    /** 0x40672D 表：陣營 → 1 = 我方（我軍、友軍）。 */
    private fun sideFlag(raw: Int): Int = mem.u8(SIDE_FLAGS + raw)

    /** 0x42E2ED 的 al：依格號的陣營分類查 0x40672D。 */
    private fun categoryFlag(slot: Int): Int {
        val u = Units.unit(slot)
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
        return sideFlag(raw)
    }

    /** 0x406710：+5 陣營（3 且 [0x505F54] ≠ 0 時 4）查 0x40672D。 */
    private fun unitSideFlag(u: Long): Int {
        var s = mem.u8(u + 5)
        if (s == 3 && mem.u8(0x505F54L) != 0) s++
        return mem.u8(SIDE_FLAGS + s)
    }

    /**
     * 0x45B50A(誰, (x, y))：格上有單位、在地圖上、HP > 0，而且符合「誰」：0x400 任何人、0x401 我方、0x402 敵方、
     * < 0x400（和 0x403）= 武將編號、≥ 0x404 = 格號 + 0x404。回傳格號，不符合 -1。
     */
    private fun matchAt(who: Int, x: Int, y: Int): Int {
        val o = occupantAt(x, y)
        if (o == 0xFF) return -1
        val u = Units.unit(o)
        if (mem.u8(u + 0x0C) != 2 || mem.u32(u + 0x10) == 0L) return -1
        val ok = when {
            who == 0x400 -> true
            who == 0x401 -> categoryFlag(o) == 1
            who == 0x402 -> categoryFlag(o) != 1
            who <= 0x403 -> mem.u32(u) == who.toLong()
            else -> (who - 0x404) and 0xff == o
        }
        return if (ok) o else -1
    }

    /** 0x25 武将进入指定地点测试（0x411827 → 0x404E16）。 */
    private fun atPosition(who: Int, x: Int, y: Int): Boolean {
        if (who == 0xFFFF) return false
        if ((x and 0xff) >= bf.width || (y and 0xff) >= bf.height) return false
        return matchAt(who, x and 0xff, y and 0xff) >= 0
    }

    /** 0x26 武将进入指定区域测试（0x40F397 → 0x45B597）：範圍內任一格符合。 */
    private fun inArea(who: Int, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        if (who == 0xFFFF) return false
        var y = y0 and 0xff
        while (y <= (y1 and 0xff)) {
            var x = x0 and 0xff
            while (x <= (x1 and 0xff)) {
                if (matchAt(who, x, y) >= 0) return true
                x++
            }
            y++
        }
        return false
    }

    /** 0x42466D：第一個武將 = ch、在地圖上、HP > 0 的格號，沒有 -1。 */
    private fun liveOnMap(ch: Int): Int = (0 until Units.SLOTS).firstOrNull {
        val u = Units.unit(it)
        mem.u32(u) == ch.toLong() && mem.u8(u + 0x0C) == 2 && mem.u32(u + 0x10) != 0L
    } ?: -1

    /**
     * 0x2E 武将相邻测试（0x4119C2 → 0x45AFCA）：(武將 a, 武將 b, 旗標)。a 周圍 8 格（方向表 0x435A3E）有 b 就成立。
     * b ≥ 0x400 時用 0x45B50A 的規則。旗標 = 0 時還要互相在攻擊範圍內（0x426981，戰鬥系統，還沒移植）。
     */
    private fun adjacent(a: Int, b: Int, flag: Int): Boolean? {
        if (a == 0xFFFF || b == 0xFFFF) return false
        val f = if (flag == 0xFFFF) 0 else flag
        val sa = when {
            a < 0x400 -> liveOnMap(a)
            a <= 0x403 -> { gap("0x45AFCA 武將 0x400–0x403（目前行動的單位 [0x4B5DF0]）"); return null }
            else -> {
                val v = (a - 0x404) and 0xffff
                if (v and 0xff == 0xFF) return false
                if (v >= Units.SLOTS) { gap("0x45AFCA 格號超出範圍"); return null }
                v
            }
        }
        if (sa < 0) return false
        val ua = Units.unit(sa)
        var sb = -1
        if (b < 0x400) {
            sb = liveOnMap(b)
            if (sb < 0) return false
        }
        val ax = mem.u8(ua + 6)
        val ay = mem.u8(ua + 7)
        for (d in 0 until 8) {
            val nx = (ax + mem.u8(DIRECTIONS + 2 * d)) and 0xff
            val ny = (ay + mem.u8(DIRECTIONS + 2 * d + 1)) and 0xff
            if (nx >= bf.width || ny >= bf.height) continue
            if (b >= 0x400) { if (matchAt(b, nx, ny) < 0) continue } else if (occupantAt(nx, ny) != sb) continue
            if (f != 0) return true
            gap("0x426981 相鄰測試要互相在攻擊範圍內（戰鬥系統）")
            return null
        }
        return false
    }

    /**
     * 0x36 武将状态测试（0x4112FA）：(武將, 項目, 值, 比較)。項目 7/8 = 單位目前 HP/MP，15 = 裝備裡有道具「值」，
     * 其他 = 武將能力（0x422E9D）。武將 ≥ 0x400（戰場上）：任一在地圖上的單位符合、而且合乎「誰」（0x41147A）。
     */
    private fun statusTest(who: Int, item: Int, value: Int, cmp: Int): Boolean? {
        if (who == 0xFFFF) return false
        val v = value.toLong() and 0xffffffffL
        if (who >= 0x400) {
            for (s in 0 until Units.SLOTS) {
                val u = Units.unit(s)
                if (mem.u8(u + 0x0C) != 2) continue
                val c = mem.u32(u).toInt()
                val r = when (item and 0xff) {
                    7 -> compare(cmp, mem.u32(u + 0x10), v)
                    8 -> compare(cmp, mem.u16(u + 0x14).toLong(), v)
                    15 -> hasItem(c, v)
                    else -> compare(cmp, Stats.get(mem, c, item and 0xff), v)
                }
                if (r && whoFilter(u, who)) return true
            }
            return false
        }
        return when (item and 0xff) {
            7, 8 -> {
                val s = unitOfCharacter(who)
                if (s == 0xFF) return false
                val u = Units.unit(s)
                compare(cmp, if (item and 0xff == 7) mem.u32(u + 0x10) else mem.u16(u + 0x14).toLong(), v)
            }
            15 -> hasItem(who, v)
            else -> compare(cmp, Stats.get(mem, who, item and 0xff), v)
        }
    }

    /** 0x4114D4：三件裝備裡有道具 [v] 時成立，並把 [0x505F90] 設成武將編號。 */
    private fun hasItem(ch: Int, v: Long): Boolean {
        val c = Characters.addr(mem, ch)
        for (k in 0 until 3) if (mem.u8(c + 0x2E + 3 * k).toLong() == v) {
            mem.setU32(0x505F90L, ch.toLong())
            return true
        }
        return false
    }

    /** 0x41147A：0x400 任何人、0x402 敵方、0x401 我方、0x403 起 = 目前行動的單位而且是我軍。 */
    private fun whoFilter(u: Long, who: Int): Boolean {
        val k = (who - 0x400) and 0xff
        if (who == 0x400) return true
        if (k == 2) return unitSideFlag(u) != 1
        if (unitSideFlag(u) != 1) return false
        if (k == 1) return true
        val cur = Units.liveUnitOf(mem, mem.u32(CURRENT_UNIT).toInt())
        if (cur < 0 || Units.unit(cur) != u) return false
        return mem.u8(u + 5).let { if (it <= 6) it else 0 } == 0
    }

    /** 0x477ADC：武將在戰場上的格號（狀態 ≠ 0），沒有 0xFF。[0x505FDC] ≠ 0 時不看格 1–59。 */
    private fun unitOfCharacter(ch: Int): Int {
        var s = 0
        while (s < 0xFA) {
            val u = Units.unit(s)
            if (mem.u8(u + 0x0C) != 0 && mem.u32(u) == (ch and 0x0FFF).toLong()) return s
            s++
            if (mem.u32(0x505FDCL) != 0L) while (s < 0x3C) s++
        }
        return 0xFF
    }

    /**
     * 0x41 战场人数测试（0x411507 → 0x428DF1）：(陣營, 人數, 比較, 模式, x0, y0, x1, y1)。
     * 模式 1 只數範圍內的；陣營篩選 0x44CB12（我軍／友軍格裡的敵軍旗標當成 0／1）。人數是 byte。
     */
    private fun countTest(p: List<Int>): Boolean {
        val side = if (p[0] and 0xffff == 0xFFFF) 6 else p[0]
        val mode = if (p[3] and 0xffff == 0xFFFF) 0 else p[3]
        val n = count(side, mode, p[4], p[5], p[6], p[7])
        return compare(p[2], n.toLong(), p[1].toLong())
    }

    /** 0x428DF1(陣營篩選, 模式, x0, y0, x1, y1)：在地圖上（狀態 2）而且合乎篩選的單位數（byte）；模式 1 只數範圍內的格。 */
    private fun count(side: Int, mode: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var n = 0
        if (mode and 0xff != 1) {
            for (s in 0 until Units.SLOTS) if (mem.u8(Units.unit(s) + 0x0C) == 2 && countMatch(side, s)) n++
        } else {
            var y = y0 and 0xff
            while (y <= (y1 and 0xff)) {
                var x = x0 and 0xff
                while (x <= (x1 and 0xff)) {
                    val o = occupantAt(x, y)
                    if (o != 0xFF && mem.u8(Units.unit(o) + 0x0C) == 2 && countMatch(side, o)) n++
                    x++
                }
                y++
            }
        }
        return n and 0xff
    }

    // ---- 勝敗（0x42 战斗胜利测试 0x411BD0、0x43 战斗失败测试 0x411BA5）----

    /** 0x40DEA6：0x45 設定的「武將」紀錄（word 旗標 bit 0、word 武將）跟 ch 相同。 */
    private fun recordMatches(rec: Long, ch: Int): Boolean =
        mem.u16(rec) and 1 != 0 && mem.u16(rec + 2) == (ch and 0xffff)

    /**
     * 0x428F36 勝利判定：戰場物件 +1 ≠ 0（已分出勝敗）就不動。格 60–250（含 250）撤退／陣亡（狀態 3、4）的單位
     * 是 0x45 指定的武將（0x496C94）→ 勝利；否則地圖上的敵軍（篩選 5）數到 0 → 勝利。勝利 = 結果欄位 5 寫 1（0x44F840）。
     */
    private fun checkVictory() {
        if (mem.u8(BATTLE + 1) != 0) return
        for (s in 0x3C..0xFA) {
            val u = Units.unit(s)
            val st = mem.u8(u + 0x0C)
            if ((st == 3 || st == 4) && recordMatches(TARGET_WIN, mem.u32(u).toInt())) { results[RESULT_WIN] = 1; return }
        }
        if (count(5, 0, 0, 0, 0, 0) == 0) results[RESULT_WIN] = 1
    }

    /**
     * 0x44CA6D 敗北判定：戰場物件 +1 ≠ 0 就不動。格 0–60（含 60）撤退／陣亡的單位是 0x45 指定的武將（0x496C98）→ 敗北；
     * 格 0 的武將 ≠ 0 時地圖上的我軍（篩選 0）數到 0 → 敗北；格 0 是武將 0（曹操）時看格 0–20（含 20）有沒有狀態 1 或 2 的，
     * 都沒有 → 敗北。敗北 = 結果欄位 6 寫 1（0x44F880）。
     */
    private fun checkDefeat() {
        if (mem.u8(BATTLE + 1) != 0) return
        for (s in 0..0x3C) {
            val u = Units.unit(s)
            val st = mem.u8(u + 0x0C)
            if ((st == 3 || st == 4) && recordMatches(TARGET_LOSE, mem.u32(u).toInt())) { results[RESULT_LOSE] = 1; return }
        }
        val alive = if (mem.u32(Units.UNITS) != 0L) count(0, 0, 0, 0, 0, 0) != 0
            else (0..0x14).any { mem.u8(Units.unit(it) + 0x0C).let { st -> st == 1 || st == 2 } }
        if (!alive) results[RESULT_LOSE] = 1
    }

    /** 0x44CB12。 */
    private fun countMatch(filter: Int, slot: Int): Boolean {
        var v = mem.u8(Units.unit(slot) + 5).let { if (it <= 6) it else 0 }
        if (v == 2) v = when { slot < 0x14 -> 0; slot < 0x3C -> 1; else -> 2 }
        return when (val f = filter and 0xff) {
            in 0..3 -> v == f
            4 -> v < 2
            5 -> v == 2 || v == 3
            6 -> true
            else -> false
        }
    }

    companion object {
        const val BATTLE = 0x4B3D08L
        private const val SIDE_FLAGS = 0x40672DL
        private const val DIRECTIONS = 0x435A3EL
        /** 目前行動的單位的武將（0x4B5DF0）。 */
        private const val CURRENT_UNIT = 0x4B5DF0L
        /** 0x45 战场全局变量 設定的勝利／敗北武將紀錄（word 有沒有設定、word 武將）。 */
        private const val TARGET_WIN = 0x496C94L
        private const val TARGET_LOSE = 0x496C98L
        /** 劇本 VM 的結果欄位（VM+0x1C，載入劇本時 0x417E84 配置）：5 = 勝利、6 = 敗北。 */
        const val RESULT_WIN = 5
        const val RESULT_LOSE = 6
    }
}
