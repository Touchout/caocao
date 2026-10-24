package ccz.battle

import ccz.data.Characters
import ccz.script.EngineMemory

/**
 * 攻擊範圍（原版 0x43F8C0 範圍型態、0x436511 依形狀表展開並收集範圍內的目標）。規則見 docs/battle.md「攻擊範圍」。
 * 標記寫在搜尋的花費表（[Search.cost]，原版戰場緩衝區 +0x12C0）與 [BattleField.scratch]（緩衝區 0x48B0E0）；
 * 目標清單照原版放在記憶體 0x4AC748（0xFF 結尾），寫入位置 [0x4B2C34]。
 * 用 tools/re/gen_damage_vectors.py 的 range 欄位跟原版比對（DamageTest）。
 */
class AttackRange(private val bf: BattleField) {
    private val mem: EngineMemory get() = bf.mem
    private val effects get() = bf.effects

    private fun charPtr(u: Long) = Characters.addr(mem, mem.u16(u))

    /** 上一次 [collect] 收集到的目標數（原版回傳的 edx）。 */
    var lastCount = 0
        private set

    /**
     * 0x43F8C0：4 個範圍型態碼（byte 0 起，0xFF = 沒有）。byte 0 = 兵種的型態（兵種資料 +1；正在用策略時用策略的），
     * 特效 0x5B 時換成延伸型態（表 0x41CEAB）；byte 1 = 裝備特效 0x32 的值；byte 2 = 裝備特效 0x2C → 0x14；
     * byte 3 = 裝備特效 0x54 → 0x0C。健康狀態第 1 組 → 0xFFFFFF0B（不能攻擊）；被四鄰特效 0x60 的敵人牽制 → 0（型態 0x0B 時同上）。
     */
    fun descriptor(u: Long): Long {
        val cp = charPtr(u)
        val d = intArrayOf(0xFF, 0xFF, 0xFF, 0xFF)
        if (mem.u32(0x500AC0L) == u && mem.u8(0x500E9AL) == 2 && mem.u8(0x500E9BL) != 0)
            d[0] = mem.u8(0x4A7510L + 16L * mem.u8(0x500E9BL) + 0x0C)
        val v = effects.itemsOnly(cp, 0x32, 0, 5)                                         // 0x43C794
        if (v != 0L) d[1] = (v and 0xff).toInt()
        if (effects.hasItemCode(cp, 0x2C)) d[2] = 0x14
        if (effects.hasItemCode(cp, 0x54)) d[3] = 0x0C
        var c = d[0]
        if (c == 0xFF) c = mem.u8(CLASSES + 0x23L * mem.u8(cp + 0x2B) + 1)                 // 0x417A38 → 0x44F9A0
        if (effects.value(u, 0x5B, 0x76, 3, 0) and 1L != 0L) c = mem.u8(EXTENDED + c)       // 0x41CE8C
        d[0] = c and 0xff
        val packed = d[0].toLong() or (d[1].toLong() shl 8) or (d[2].toLong() shl 16) or (d[3].toLong() shl 24)
        if ((mem.u8(u + 0x1E) shr 2) and 3 != 0) return NONE                                 // 0x423FE8(4)
        if (notPinned(u)) return packed
        return if (d[0] == 0x0B) NONE else 0
    }

    /** 0x42BB57：四鄰在地圖上的不同方單位有特效 (0x60, 0x7A) 時被牽制（回傳 false）。位置用 0x407BF6(1)。 */
    private fun notPinned(u: Long): Boolean {
        val side = effects.sideCategory(u)
        val pos = bf.search.positionFor(u, 1)
        for (d in 0 until 4) {
            val n = bf.neighbor(pos, d)
            if (n and 0xff == 0xFF) continue
            val o = bf.occupantAt(n)
            if (o == 0xFF) continue
            val nu = Units.unit(o)
            if (mem.u8(nu + 0x0C) != 2 || effects.sideCategory(nu) == side) continue           // 0x43A289
            if (effects.value(nu, 0x60, 0x7A, 1, 1) != 0L) return false                        // 0x4210E2
        }
        return true
    }

    /**
     * 0x436511(位置, 型態, 旗標, 只標記)：依 4 個型態碼展開（0 只在 byte 0 有效、> 0x25 與 0x0B 不算、0x0A = 整張地圖並結束），
     * 旗標 bit 0 連自己的格也算。每格交給 [mark]。不是只標記時：先清空目標清單，最後回傳清單裡的 [0x4B2C28]，
     * 沒有就回傳最後一個（空清單 0xFF）。
     */
    fun collect(u: Long, pos: Int, desc: Long, flags: Int, only: Int): Int {
        if (desc and 0xff == 0xFFL) return 0xFF
        val slot = mem.u8(u + 4)
        if (only and 0xff == 0) {
            mem.setU8(LIST, 0xFF)
            mem.setU32(LIST_END, LIST)
        }
        if (flags and 1 != 0) mark(pos and 0xff, (pos shr 8) and 0xff, slot, flags, only)
        for (i in 0 until 4) {
            val c = ((desc shr (8 * i)) and 0xff).toInt()
            if (c == 0xFF || (c == 0 && i != 0) || c > 0x25) continue
            if (c == 0x0A) {
                for (y in 0 until bf.height) for (x in 0 until bf.width) mark(x, y, slot, flags, only)   // 0x4366F0
                break
            }
            if (c == 0x0B) continue
            var p = mem.u32(SHAPES + 4L * (if (c < 0x0B) c else c - 2))
            while (mem.u16(p) != 0xFFFF) {
                symmetric(pos, mem.u8(p), mem.u8(p + 1), slot, flags, only)
                p += 2
            }
        }
        if (only and 0xff != 0) return 0xFF
        lastCount = (mem.u32(LIST_END) - LIST).toInt()
        mem.setU32(LIST_END, mem.u32(LIST_END) + 1)
        var last = 0xFF
        var p = LIST
        while (mem.u8(p) != 0xFF) {
            last = mem.u8(p)
            if (last == mem.u8(PREFERRED)) break
            p++
        }
        return last
    }

    /** 0x436611：形狀的一筆 (dx, dy)：兩個都不是 0 → 四個斜角；只有一個 → r = 較大者，上下左右。座標是 byte 運算。 */
    private fun symmetric(pos: Int, dx: Int, dy: Int, slot: Int, flags: Int, only: Int) {
        val x = pos and 0xff
        val y = (pos shr 8) and 0xff
        if (dx == 0 && dy == 0) return
        if (dx != 0 && dy != 0) {
            mark((x + dx) and 0xff, (y - dy) and 0xff, slot, flags, only)
            mark((x - dx) and 0xff, (y - dy) and 0xff, slot, flags, only)
            mark((x - dx) and 0xff, (y + dy) and 0xff, slot, flags, only)
            mark((x + dx) and 0xff, (y + dy) and 0xff, slot, flags, only)
        } else {
            val r = maxOf(dx, dy)
            mark((x + r) and 0xff, y, slot, flags, only)
            mark((x - r) and 0xff, y, slot, flags, only)
            mark(x, (y - r) and 0xff, slot, flags, only)
            mark(x, (y + r) and 0xff, slot, flags, only)
        }
    }

    /**
     * 0x437AFF：地圖內的格：旗標沒有 0x80 時在花費表與 scratch 記下格號；只標記時 scratch + 0xC80 設 1 就結束；
     * 否則格上有單位、陣營符合（旗標 2 = 都可以、bit 0 = 同方、否則不同方）、HP > 0 → 加進目標清單（重複的不加）。
     */
    private fun mark(x: Int, y: Int, slot: Int, flags: Int, only: Int) {
        if (x >= bf.width || y >= bf.height) return
        val i = y * bf.width + x
        if (flags and 0x80 == 0) {
            bf.search.cost[i] = slot.toByte()
            bf.scratch[i] = slot.toByte()
        }
        if (only and 0xff != 0) { bf.scratch[0xC80 + i] = 1; return }
        val o = bf.occupantAt(x or (y shl 8))
        if (o == 0xFF) return
        val ou = Units.unit(o)
        val same = effects.sideCategory(Units.unit(slot)) == effects.sideCategory(ou)
        val f = flags and 0xff
        if (f != 2 && (if (f and 1 != 0) !same else same)) return
        if (mem.u32(ou + 0x10) == 0L) return
        var p = LIST
        while (mem.u8(p) != 0xFF) { if (mem.u8(p) == o) return; p++ }
        val end = mem.u32(LIST_END)
        mem.setU16(end, (o or 0xFF00).toLong())
        mem.setU32(LIST_END, end + 1)
    }

    companion object {
        const val NONE = 0xFFFFFF0BL
        const val LIST = 0x4AC748L
        const val LIST_END = 0x4B2C34L
        const val PREFERRED = 0x4B2C28L
        private const val SHAPES = 0x4D1400L
        private const val EXTENDED = 0x41CEABL
        private const val CLASSES = 0x4A2A27L
    }
}
