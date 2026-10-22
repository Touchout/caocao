package ccz.script

/**
 * 變數相關指令，照原版處理函式的語意實作（docs/script.md「變數」），
 * 並用 tools/re/gen_vectors.py 以模擬器跑原版產生的測試資料驗證。
 */
object VarOps {
    // 0x77 變數類別（型別 0x51）
    private const val KIND_DEREF = 0     // *p：指標變數存的位址
    private const val KIND_PTR = 1       // p：指標變數本身
    private const val KIND_INT = 2       // 整數變數

    /** 0x77 变量运算：kind, index, op, operandKind, operand。回傳 false 表示參數不合法（原版會跳錯誤訊息）。 */
    fun assign(mem: EngineMemory, kind: Int, index: Long, op: Int, okind: Int, oval: Long): Boolean {
        val target = when (kind) {
            KIND_INT -> if (index < 0x1000) mem.intVarAddr(index.toInt()) else return false
            KIND_PTR, KIND_DEREF -> {
                if (index > 480) return false
                val a = mem.ptrVarAddr(index.toInt())
                if (kind == KIND_DEREF) mem.u32(a) else a
            }
            else -> return false
        }
        // 運算元：0 常數、1 *p、2 p、3 &p、4 整數 a、5 &a（0x77 讀 *p 一律 dword）
        val x: Long = when (okind) {
            0 -> oval
            1, 2, 3 -> {
                if (oval > 480) return false
                val a = mem.ptrVarAddr(oval.toInt())
                when (okind) { 1 -> mem.u32(mem.u32(a)); 2 -> mem.u32(a); else -> a }
            }
            4, 5 -> {
                if (oval >= 0x1000) return false
                val a = mem.intVarAddr(oval.toInt())
                if (okind == 4) mem.u32(a) else a
            }
            else -> return false
        } and MASK
        // 運算：0 +=、1 -=、2 =、3 *=、4 /=（商）、5 %=（乘以百分比）、6 M=（餘數）
        val old = mem.u32(target)
        val (mode, value) = when (op) {
            0 -> STORE_ADD to x
            1 -> STORE_SUB to x
            2 -> STORE_SET to x
            3 -> STORE_SET to ((old * x) and MASK)
            5 -> STORE_SET to (((old * x) and MASK) * 1 / 100 and MASK)
            4 -> STORE_SET to (if (x == 0L) 0 else old / x)
            6 -> STORE_SET to (if (x == 0L) 0 else old % x)
            else -> STORE_SET to x
        }
        // 寫入寬度：寫 p 本身或運算元類別 >= 2 時一律 dword，其他依變數 4076
        val width = if (kind == KIND_PTR || okind >= 2) 4 else when (mem.width) { 1 -> 1; 2 -> 2; else -> 4 }
        store(mem, target, mode, value, width)
        bookkeeping(mem, kind, index, x)
        return true
    }

    private const val STORE_ADD = 0
    private const val STORE_SUB = 1
    private const val STORE_SET = 2
    private const val MASK = 0xFFFFFFFFL

    private fun store(mem: EngineMemory, addr: Long, mode: Int, v: Long, width: Int) {
        when (width) {
            1 -> {
                val old = mem.u8(addr).toLong()
                mem.setU8(addr, (apply(mode, old, v) and 0xff).toInt())
            }
            2 -> mem.setU16(addr, apply(mode, mem.u16(addr).toLong(), v) and 0xffff)
            else -> mem.setU32(addr, apply(mode, mem.u32(addr), v) and MASK)
        }
    }

    private fun apply(mode: Int, old: Long, v: Long) = when (mode) {
        STORE_ADD -> old + v
        STORE_SUB -> old - v
        else -> v
    }

    /** 原版 0x413604：*p 而且指標變數編號 >= 200 時，記下寫入後的值、寬度與第一次的運算元。 */
    private fun bookkeeping(mem: EngineMemory, kind: Int, index: Long, x: Long) {
        if (kind != KIND_DEREF || index < 0xC8) return
        val base = mem.ptrVarAddr(index.toInt())
        mem.setU32(base + 0x464, mem.u32(mem.u32(base)))
        mem.setU8(EngineMemory.PTR_VARS + 0xF84 + index, mem.width)
        if (mem.u32(base + 0x8C8) == 0L) mem.setU32(base + 0x8C8, x)
    }

    /** 0x79 / 0x77 讀值（原版 0x415133）：*p 依變數 4076 的寬度讀。 */
    fun value(mem: EngineMemory, kind: Int, v: Long): Long = when (kind) {
        0 -> v and MASK
        1, 2, 3 -> {
            if (v > 480) v and MASK
            else {
                val a = mem.ptrVarAddr(v.toInt())
                when (kind) {
                    1 -> {
                        val p = mem.u32(a)
                        when (mem.width) { 1 -> mem.u8(p).toLong(); 2 -> mem.u16(p).toLong(); else -> mem.u32(p) }
                    }
                    2 -> mem.u32(a)
                    else -> a
                }
            }
        }
        4, 5 -> if (v >= 0x1000) v and MASK else mem.intVarAddr(v.toInt()).let { if (kind == 4) mem.u32(it) else it }
        else -> v and MASK
    }

    /** 0x79 变量测试：比較 0 ==、1 >=、2 <=、3 ≠、4 <、5 >（無號）。 */
    fun compare(mem: EngineMemory, ka: Int, va: Long, cmp: Int, kb: Int, vb: Long): Boolean {
        val a = value(mem, ka, va)
        val b = value(mem, kb, vb)
        return when (cmp) {
            0 -> a == b
            1 -> a >= b
            2 -> a <= b
            3 -> a != b
            4 -> a < b
            else -> a > b
        }
    }

    /** 0x05 变量测试：第一組旗標都要成立、第二組都要不成立。 */
    fun flagTest(mem: EngineMemory, on: List<Int>, off: List<Int>): Boolean =
        on.all { mem.flag(it) } && off.none { mem.flag(it) }
}
