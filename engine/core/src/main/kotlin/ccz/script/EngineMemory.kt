package ccz.script

/**
 * 跟原版 Ekd5.exe 位址相同的記憶體映像。
 *
 * 6.6 的劇本會用「指標變數」直接讀寫引擎記憶體（例如 MOD 放在 0x511000 的裝備專屬表），
 * 所以變數、旗標、MOD 資料表都放在這份映像裡，位址跟原版一樣，行為才會一致。
 * 位址見 docs/script.md。映像外的位址寫到稀疏表（不會出錯，但也沒有意義）。
 */
class EngineMemory(exe: ByteArray) {
    private val image: ByteArray
    /** 原版用 new 配置的陣列（武將執行期資料等），放在固定的 HEAP 區，位址同 tools/re/trace_original.py。 */
    private val heap = ByteArray(HEAP_SIZE)
    private val extra = HashMap<Long, Byte>()

    init {
        val pe = i32(exe, 0x3C)
        val sections = u16(exe, pe + 6)
        val optSize = u16(exe, pe + 20)
        val imageSize = i32(exe, pe + 24 + 56)
        image = ByteArray(imageSize)
        System.arraycopy(exe, 0, image, 0, 0x400)
        for (i in 0 until sections) {
            val s = pe + 24 + optSize + 40 * i
            val vsize = i32(exe, s + 8)
            val va = i32(exe, s + 12)
            val rsize = i32(exe, s + 16)
            val raw = i32(exe, s + 20)
            val n = minOf(rsize, maxOf(vsize, rsize), exe.size - raw, imageSize - va)
            if (n > 0) System.arraycopy(exe, raw, image, va, n)
        }
        Attributes.initUnits(this)
    }

    fun u8(addr: Long): Int {
        val o = addr - BASE
        if (o >= 0 && o < image.size) return image[o.toInt()].toInt() and 0xff
        val h = addr - HEAP
        if (h >= 0 && h < HEAP_SIZE) return heap[h.toInt()].toInt() and 0xff
        return (extra[addr]?.toInt() ?: 0) and 0xff
    }

    fun setU8(addr: Long, v: Int) {
        val o = addr - BASE
        if (o >= 0 && o < image.size) { image[o.toInt()] = v.toByte(); return }
        val h = addr - HEAP
        if (h >= 0 && h < HEAP_SIZE) { heap[h.toInt()] = v.toByte(); return }
        extra[addr] = v.toByte()
    }

    fun bytes(addr: Long, n: Int) = ByteArray(n) { u8(addr + it).toByte() }

    fun u16(addr: Long) = u8(addr) or (u8(addr + 1) shl 8)
    fun u32(addr: Long): Long = (u16(addr).toLong() or (u16(addr + 2).toLong() shl 16))

    fun setU16(addr: Long, v: Long) { setU8(addr, (v and 0xff).toInt()); setU8(addr + 1, ((v shr 8) and 0xff).toInt()) }
    fun setU32(addr: Long, v: Long) { setU16(addr, v and 0xffff); setU16(addr + 2, (v shr 16) and 0xffff) }

    // ---- 劇本變數 ----

    /** 整數變數 n（0 ≤ n < 0x1000）的位址。 */
    fun intVarAddr(n: Int) = INT_VARS + 4L * n

    /** 指標變數 n（0 ≤ n ≤ 480）的位址。 */
    fun ptrVarAddr(n: Int) = PTR_VARS + 4L * n

    fun intVar(n: Int): Long = u32(intVarAddr(n))
    fun setIntVar(n: Int, v: Long) = setU32(intVarAddr(n), v)

    /** 旗標：劇本 VM 物件 +0x38 起的位元陣列，每個 byte 由高位元往低位元排。 */
    fun flag(n: Int): Boolean = (u8(FLAGS + n / 8) shr (7 - n % 8)) and 1 == 1

    fun setFlag(n: Int, v: Boolean) {
        val a = FLAGS + n / 8
        val bit = 1 shl (7 - n % 8)
        setU8(a, if (v) u8(a) or bit else u8(a) and bit.inv())
    }

    /** 變數 4076：0x77／0x79 讀寫指標目標的寬度，1 = byte、2 = word、其他 = dword。 */
    val width: Int get() = u8(intVarAddr(4076))

    companion object {
        const val BASE = 0x400000L
        const val HEAP = 0x40000000L
        const val HEAP_SIZE = 0x400000
        const val INT_VARS = 0x502000L
        const val PTR_VARS = 0x506000L
        /** 劇本 VM 物件（0x492F90）+0x38，共 0x2000 bytes = 65536 個旗標。 */
        const val FLAGS = 0x492FC8L
        /** 0x0A 初始化局部变量 會清掉的 320 個 dword。 */
        const val LOCAL_DWORDS = 0x501B00L

        private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
        private fun i32(b: ByteArray, p: Int) = u16(b, p) or (u16(b, p + 2) shl 16)
    }
}
