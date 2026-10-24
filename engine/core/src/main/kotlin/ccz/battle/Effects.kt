package ccz.battle

import ccz.data.Characters
import ccz.data.Equipment
import ccz.script.EngineMemory

/**
 * 特效判定（原版 0x4101D9(特效, 道具特效碼, 合併方式, 只看有無)，ecx = 單位或武將資料）。規則見 docs/battle.md「特效」。
 * 用 tools/re/gen_effect_vectors.py 跟原版比對（EffectTest）。
 *
 * 一個武將的特效值來自三處，依合併方式（0 相加、1 取大、2 特殊、3 OR）合起來：
 * 武將專屬表 [0x500C3B]（MOD 0x511000）與兵種、0x512DC0 的名單、三件裝備的特效碼、0x50E000 的武將＋裝備組合表。
 * 單位的武將編號 < [0x505F78] 時還要看相鄰：旁邊有「封印」的敵人時特效全部無效，自己有 88 號特效時加上相鄰單位的。
 */
class Effects(private val bf: BattleField) {
    private val mem: EngineMemory get() = bf.mem

    /** 原版回傳的 eax（特效值）與 edx（自己武將三件裝備的特效值 0x43C794）。 */
    data class Result(val eax: Long, val edx: Long)

    /** 0x4101D9。[x] 是單位（+0 武將 < 0x1000）或武將資料（+0 = 編號 | 0x1000）的位址。 */
    fun check(x: Long, effect: Int, code: Int, mode: Int, presence: Int): Result {
        val (al, charPtr, unit) = resolve(x)
        if (al == 0xFF) return Result(0, 0)
        val aura = mem.u32(unit) < mem.u32(LIMIT)
        if (aura && sealImmune(unit) == 0L && sealedByNeighbor(unit) != 0L) return Result(0, 0)
        val own = character(charPtr, effect, code, mode, presence)
        var acc = combine(own.eax, 0, mode)
        if (aura && character(charPtr, 88, 116, 1, 1).eax != 0L) {
            for (d in 0 until 4) {
                val o = neighborUnit(unit, d) ?: continue
                val r = character(charPtrOf(Units.unit(o)), effect, code, mode, presence)
                if (r.eax != 0L) acc = combine(r.eax, acc, mode)
            }
        }
        return Result(acc, own.edx)
    }

    /** 簡寫：只要 eax。 */
    fun value(unit: Long, effect: Int, code: Int, mode: Int, presence: Int): Long = check(unit, effect, code, mode, presence).eax

    /**
     * 0x426A07：(al, 武將資料, 單位)。單位：al = +4（格號）。武將資料：不在戰場狀態時 al = 0、單位 0；
     * 武將就是 [0x500AC8] 指的單位時 al = 武將編號低位；否則找第一個武將相同的單位（0x43E4D6），找不到 al = 0xFF。
     */
    private fun resolve(x: Long): Triple<Int, Long, Long> {
        val w = mem.u16(x)
        if (w < 0x1000) return Triple(mem.u8(x + 4), charPtrOf(x), x)
        if (mem.u8(GAME_MODE) != 3) return Triple(0, x, 0)
        val id = (w and 0xFFF).toLong()
        val p = mem.u32(EVALUATED_UNIT)
        if (id == mem.u32(p)) return Triple((id and 0xff).toInt(), x, p)
        for (s in 0 until 0xFA) if (mem.u32(Units.unit(s)) == id) return Triple(s, x, Units.unit(s))
        return Triple(0xFF, x, 0xFA)
    }

    /** 0x40658F → 0x4061E4：單位的武將資料。 */
    private fun charPtrOf(unit: Long): Long = Characters.addr(mem, mem.u16(unit))

    /** 0x41301E：武將自己的特效（eax = 全部合起來，edx = 只有裝備的部分）。 */
    fun character(charPtr: Long, effect: Int, code: Int, mode: Int, presence: Int): Result {
        val ch = ((charPtr - mem.u32(Characters.PTR)) / Characters.SIZE).toInt()
        val eff = effect and 0xff
        val v1 = table(ch, eff, mode, charPtr)
        val v2 = items(charPtr, code, presence, mode)
        val v3 = combine(v2, v1, mode)
        return Result(combos(ch, charPtr, eff, mode, v3), v2)
    }

    /** 0x4198BE：武將專屬表 [0x500C3B] + 16·特效：4 × (word 武將, byte 值)，0x512DC0 名單，再 2 × (byte 兵種, byte 值)。 */
    private fun table(ch: Int, eff: Int, mode: Int, charPtr: Long): Long {
        val t = mem.u32(EXCLUSIVE) + 16L * eff
        var value = 0L
        for (i in 0 until 4) if (mem.u16(t + 3 * i) == ch and 0xffff) { value = mem.u8(t + 3 * i + 2).toLong(); break }
        var acc = combine(list(eff, ch), value, mode)
        val cls = mem.u8(charPtr + 0x2B)
        var cv = 0L
        for (j in 0 until 2) {
            val p = t + 0x0C + 2 * j
            if (!classMismatch(mem.u8(p), cls)) { cv = mem.u8(p + 1).toLong(); break }
        }
        acc = combine(cv, acc, mode)
        return acc
    }

    /**
     * 0x42FBE1 → 0x436F16：名單 0x512DC0：0xFF, 特效, 每筆 4 bytes (值, 有效, word 武將)…，0xFF, 特效, …，0xFF 0xFF 結束。
     * 找到而且有效時回傳值。
     */
    private fun list(eff: Int, ch: Int): Long {
        var p = LIST
        while (true) {
            if (mem.u8(p) != 0xFF) { p++; continue }
            p++
            if (mem.u8(p) == 0xFF) return 0
            if (mem.u8(p) != eff) { p++; continue }
            p++
            while (mem.u8(p) != 0xFF) {
                if (mem.u16(p + 2) == ch and 0xffff) return if (mem.u8(p + 1) != 0) mem.u8(p).toLong() else 0
                p += 4
            }
            return 0
        }
    }

    /** 0x42F998：兵種 [c] 不適用於 [cls]（c ≥ 0x50、或不同系、或 c 比 cls 高）；[0x505F68] ≠ 0 時要完全相同。 */
    private fun classMismatch(c: Int, cls: Int): Boolean {
        if (c >= 0x50) return true
        if (mem.u8(0x505F68L) != 0) return c != cls
        return !(group(c) == group(cls) && c <= cls)
    }

    /** 0x40687F：兵種系別。 */
    private fun group(c: Int): Int = when {
        c < 0x3C -> c / 3
        c < 0x50 -> c - 0x28
        else -> 0x28
    }

    /** 0x43C794：三件裝備的特效碼有 [code] 時，只看有無 → 100，否則依等級算值（0x407AED）。 */
    private fun items(charPtr: Long, code: Int, presence: Int, mode: Int): Long {
        if (!hasCode(charPtr, code)) return 0
        if (presence != 0) return 0x64
        return itemValue(charPtr, code, mode)
    }

    /** 0x43C794（給其他模組用）。 */
    fun itemsOnly(charPtr: Long, code: Int, presence: Int, mode: Int): Long = items(charPtr, code, presence, mode)

    /** 0x4079DA（給其他模組用）。 */
    fun hasItemCode(charPtr: Long, code: Int): Boolean = hasCode(charPtr, code)

    /** 0x4079DA：武器、防具（0x40B4E1 成立時）看 +0x14，輔助看 +0x11。 */
    private fun hasCode(charPtr: Long, code: Int): Boolean {
        for (k in 0 until 3) {
            val id = mem.u8(charPtr + 0x2E + 3 * k)
            if (id == 0xFF) continue
            val it = Equipment.item(id)
            val c = if (k != 2) {
                if (!hasEffect(it)) continue
                mem.u8(it + 0x14)
            } else mem.u8(it + 0x11)
            if (c == code and 0xff) return true
        }
        return false
    }

    /** 0x40B4E1：0x40B444 ≤ 1（武器、防具；其他種類看 +0x14）而且種類是奇數。 */
    private fun hasEffect(it: Long): Boolean {
        val kind = mem.u8(it + 0x11)
        val r = when {
            kind < 0x14 -> 0
            kind < 0x1A -> 1
            else -> mem.u8(it + 0x14)
        }
        return r <= 1 && kind % 2 == 1
    }

    /** 0x407AED：武器、防具的特效值（0x4200F5，依等級），輔助取 +0x15；用 0x407B9E 依合併方式累加。 */
    private fun itemValue(charPtr: Long, code: Int, mode: Int): Long {
        var acc = 0L
        for (k in 0 until 3) {
            val id = mem.u8(charPtr + 0x2E + 3 * k)
            val v: Long
            if (k < 2) {
                val lvl = mem.u8(charPtr + 0x2F + 3 * k)
                if (id == 0xFF) continue
                val it = Equipment.item(id)
                if (!hasEffect(it) || mem.u8(it + 0x14) != code and 0xff) continue
                v = Equipment.effectValue(mem, id, lvl)
            } else {
                if (id == 0xFF) break
                val it = Equipment.item(id)
                if (mem.u8(it + 0x11) != code and 0xff) break
                v = mem.u8(it + 0x15).toLong()
            }
            acc = accumulate(acc, v, mode, k)
        }
        return acc
    }

    /** 0x407B9E：0 加、1 取大、2（是 7 就不換）、3 OR、4 換掉、5 起：第 (方式 − 5) 件換掉，其他只在 0 時換；超過 0xFF 變 0xFF。 */
    private fun accumulate(acc0: Long, v: Long, mode: Int, k: Int): Long {
        val m = mode and 0xff
        val acc = when {
            m == 0 -> (acc0 + v) and M
            m == 1 -> if (acc0 < v) v else acc0
            m == 2 -> if (acc0 == 7L) acc0 else v
            m == 3 -> acc0 or v
            m == 4 -> v
            m - 5 == k -> v
            acc0 == 0L -> v
            else -> acc0
        }
        return if (acc > 0xFF) 0xFF else acc
    }

    /**
     * 0x402CF9：組合表 0x50E000 + 16·特效。前 8 bytes：2 × (word 武將, byte 裝備, byte 值)，武將相同而且三件裝備有那件 → 值；
     * 後 8 bytes：2 × (3 個裝備（0xFF 不看）, byte 值)，同位置的裝備符合至少 2 件 → 值。
     */
    private fun combos(ch: Int, charPtr: Long, eff: Int, mode: Int, v3: Long): Long {
        val t = COMBOS + 16L * eff
        val items = IntArray(3) { mem.u8(charPtr + 0x2E + 3 * it) }
        var v1 = 0L
        found@ for (i in 0 until 2) {
            val e = t + 4 * i
            if (mem.u16(e) != ch and 0xffff) continue
            for (k in 0 until 3) if (items[k] != 0xFF && items[k] == mem.u8(e + 2)) { v1 = mem.u8(e + 3).toLong(); break@found }
        }
        val r = combine(v3, v1, mode)
        var v2 = 0L
        entry@ for (i in 0 until 2) {
            val e = t + 8 + 4 * i
            var n = 0
            for (k in 0 until 3) {
                val b = mem.u8(e + k)
                if (b == 0xFF) continue
                if (b != items[k]) continue@entry
                n++
            }
            if (n >= 2) { v2 = mem.u8(e + 3).toLong(); break }
        }
        return combine(r, v2, mode)
    }

    /** 0x40F1AC：自己有 0x59（封印免疫？）就回傳它；沒有 88 號就 0；否則看相鄰有 0x59 的單位是不是同一方。 */
    private fun sealImmune(unit: Long): Long {
        val cp = charPtrOf(unit)
        val v = character(cp, 0x59, 0x75, 1, 1).eax
        if (v != 0L) return v
        if (character(cp, 88, 116, 1, 1).eax == 0L) return 0
        val side = sideCategory(unit)
        var res = 0L
        for (d in 0 until 4) {
            val o = neighborUnit(unit, d) ?: continue
            val nb = Units.unit(o)
            if (character(charPtrOf(nb), 0x59, 0x75, 1, 1).eax == 0L) continue
            if (side == sideCategory(nb)) res = 1 else return 0
        }
        return res
    }

    /** 0x425390：相鄰不同方的單位裡，有 0x40F1AC 成立的。 */
    private fun sealedByNeighbor(unit: Long): Long {
        val side = sideCategory(unit)
        for (d in 0 until 4) {
            val o = neighborUnit(unit, d) ?: continue
            val nb = Units.unit(o)
            if (sideCategory(nb) == side) continue
            val r = sealImmune(nb)
            if (r != 0L) return r
        }
        return 0
    }

    /** 0x41031C + 0x435884：單位往方向 d 的鄰格上的單位格號。 */
    private fun neighborUnit(unit: Long, d: Int): Int? {
        val p = bf.neighbor(mem.u8(unit + 6) or (mem.u8(unit + 7) shl 8), d)
        if (p and 0xff == 0xFF) return null
        return bf.occupantAt(p).takeIf { it != 0xFF }
    }

    /** 0x406710：+5 陣營（3 且 [0x505F54] ≠ 0 時 4）查 0x40672D。 */
    fun sideCategory(unit: Long): Int {
        var s = mem.u8(unit + 5)
        if (s == 3 && mem.u8(0x505F54L) != 0) s++
        return mem.u8(SIDE_TABLE + s)
    }

    companion object {
        private const val M = 0xFFFFFFFFL
        const val GAME_MODE = 0x4AC63CL
        /** 武將編號 < 這個值的單位才看相鄰（封印、88 號）。 */
        private const val LIMIT = 0x505F78L
        /** 正在評估的單位（AI 用）。 */
        private const val EVALUATED_UNIT = 0x500AC8L
        private const val EXCLUSIVE = 0x500C3BL
        private const val LIST = 0x512DC0L
        private const val COMBOS = 0x50E000L
        private const val SIDE_TABLE = 0x40672DL

        /** 0x413009 → 0x40BF2F：0 相加、1 取大、3 OR、其他（2）：是 7 就留著，否則用另一個（2 時另一個是 0 就用自己）。 */
        fun combine(eax: Long, edx0: Long, mode: Int): Long {
            val edx = if (mode == 2 && edx0 == 0L) eax else edx0
            return when (mode) {
                0 -> (eax + edx) and M
                1 -> if (eax < edx) edx else eax
                3 -> eax or edx
                else -> if (eax and 0xff == 7L) eax else edx
            }
        }
    }
}
