package ccz.script

import ccz.io.GameFiles

/**
 * EEX 劇本。結構見 docs/formats.md「EEX 腳本」：
 *  - scene = u16 section 數 + (u16 長度 + 區塊)*
 *  - 區塊 = 指令*；opcode 0 在區塊結尾是結束，否則後面接 u16 長度 + 子區塊（前面的條件都成立才執行）
 *  - opcode 1 子事件 = 一個指令（名稱或條件）+ u16 長度 + 子區塊
 *  - 參數 = u16 型別 + 值（型別 4 是 i32，5 是 GBK 字串，其他 u16）
 */
class Param(val type: Int, val value: Int, val text: String? = null) {
    val isRaw get() = type == RAW

    override fun toString() = when {
        text != null -> "\"$text\""
        isRaw -> "#$value"
        else -> "%02x:%d".format(type, value)
    }

    companion object {
        const val RAW = -1
    }
}

class Instr(val pos: Int, val op: Int, val params: List<Param>) {
    fun param(type: Int, nth: Int = 0): Param? = params.filter { it.type == type }.getOrNull(nth)
    override fun toString() = "%06x %02x ${params.joinToString(" ")}".format(pos, op)
}

sealed interface Node

data class Ins(val instr: Instr) : Node

/** opcode 0 / 1 帶出的子區塊。opcode 1 時 head 是子事件的名稱或條件指令。 */
data class Sub(val instr: Instr, val head: Instr?, val body: List<Node>) : Node

class Script(val scenes: List<List<List<Node>>>) {
    /** 依執行順序（忽略條件）走過所有指令。 */
    fun walk(): Sequence<Instr> = sequence {
        suspend fun SequenceScope<Instr>.visit(nodes: List<Node>) {
            for (n in nodes) when (n) {
                is Ins -> yield(n.instr)
                is Sub -> {
                    yield(n.instr)
                    n.head?.let { yield(it) }
                    visit(n.body)
                }
            }
        }
        for (scene in scenes) for (section in scene) visit(section)
    }
}

class EexParseException(msg: String) : Exception(msg)

object Eex {
    private const val OP_VAR_TEST = 0x05

    /** opcode → 參數型別序列；不在表裡的 opcode 沒在任何劇本出現過。 */
    val signatures: Map<Int, IntArray> by lazy {
        resourceLines("signatures.txt").mapNotNull { line ->
            val (k, v) = line.split(":", limit = 2)
            val types = v.trim()
            if (types == "VAR") null else k.toInt(16) to (if (types.isEmpty()) IntArray(0) else types.split(" ").map { it.toInt(16) }.toIntArray())
        }.toMap()
    }

    /** opcode 中文名稱（社群劇本編輯器的命名）。 */
    val opNames: Map<Int, String> by lazy {
        resourceLines("opcodes.txt").associate { line -> line.substring(0, 2).toInt(16) to line.substring(3) }
    }

    private fun resourceLines(name: String) =
        Eex::class.java.getResourceAsStream("/ccz/eex/$name")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

    fun parse(data: ByteArray): Script = Parser(data).parse()

    private class Parser(val d: ByteArray) {
        fun u16(p: Int) = (d[p].toInt() and 0xff) or ((d[p + 1].toInt() and 0xff) shl 8)
        fun i32(p: Int) = u16(p) or (u16(p + 2) shl 16)

        fun parse(): Script {
            if (!(d[0] == 'E'.code.toByte() && d[1] == 'E'.code.toByte() && d[2] == 'X'.code.toByte())) throw EexParseException("不是 EEX")
            val first = i32(0x0A)
            val offsets = (0x0A until first step 4).map { i32(it) }
            val scenes = offsets.mapIndexed { n, off ->
                val end = offsets.getOrNull(n + 1) ?: d.size
                parseScene(off, end)
            }
            return Script(scenes)
        }

        fun parseScene(start: Int, end: Int): List<List<Node>> {
            val count = u16(start)
            var p = start + 2
            val sections = ArrayList<List<Node>>()
            repeat(count) {
                val len = u16(p)
                sections += parseBlock(p + 2, p + 2 + len)
                p += 2 + len
            }
            if (p != end) throw EexParseException("scene 結束位置 %x != %x".format(p, end))
            return sections
        }

        fun parseBlock(start: Int, end: Int): List<Node> {
            val out = ArrayList<Node>()
            var p = start
            while (p < end) {
                val op = u16(p)
                when (op) {
                    0 -> {
                        p += 2
                        if (p == end) {
                            out += Ins(Instr(p - 2, 0, emptyList()))
                            return out
                        }
                        val len = u16(p)
                        out += Sub(Instr(p - 2, 0, listOf(Param(Param.RAW, len))), null, parseBlock(p + 2, p + 2 + len))
                        p += 2 + len
                        if (p == end) return out
                    }
                    1 -> {
                        val at = p
                        val (head, next) = parseInstr(p + 2)
                        val len = u16(next)
                        out += Sub(Instr(at, 1, listOf(Param(Param.RAW, len))), head, parseBlock(next + 2, next + 2 + len))
                        p = next + 2 + len
                    }
                    else -> {
                        val (ins, next) = parseInstr(p)
                        out += Ins(ins)
                        p = next
                    }
                }
            }
            throw EexParseException("區塊在 %x 沒有以 opcode 0 結尾".format(end))
        }

        fun parseInstr(at: Int): Pair<Instr, Int> {
            val op = u16(at)
            var p = at + 2
            val params = ArrayList<Param>()
            fun read(want: Int) {
                val t = u16(p)
                if (t != want) throw EexParseException("%x op %02x：參數型別 %x，應為 %x".format(at, op, t, want))
                when (t) {
                    4 -> { params += Param(t, i32(p + 2)); p += 6 }
                    5 -> {
                        var z = p + 2
                        while (d[z] != 0.toByte()) z++
                        params += Param(t, z - p - 2, String(d, p + 2, z - p - 2, GameFiles.GBK))
                        p = z + 1
                    }
                    else -> { params += Param(t, u16(p + 2)); p += 4 }
                }
            }
            if (op == OP_VAR_TEST) {
                // 兩組（要成立／要不成立）：(0x35, N) + N 個原始 u16 變數編號
                repeat(2) {
                    read(0x35)
                    repeat(params.last().value) { params += Param(Param.RAW, u16(p)); p += 2 }
                }
            } else {
                val sig = signatures[op] ?: throw EexParseException("%x：沒有 opcode %02x 的參數表".format(at, op))
                sig.forEach { read(it) }
            }
            return Instr(at, op, params) to p
        }
    }
}
