package ccz.script

import ccz.data.Stats

/** 劇本執行時需要畫面處理的事件。 */
sealed interface ScriptEvent {
    /** 對話：speaker 是武將編號（找不到時為 null，只有名字）。 */
    data class Dialogue(val speaker: Int?, val name: String, val text: String) : ScriptEvent

    /** 旁白、信息、場所名、章名、勝利條件等純文字。 */
    data class Message(val text: String, val kind: Int) : ScriptEvent

    /** 選擇題（0x12 选择框）：回答選項編號（從 1 起）。 */
    data class Choice(val speaker: Int?, val options: List<String>) : ScriptEvent

    /** 是非題（0x04 询问测试）：回答 1 = 是、0 = 否。 */
    data class YesNo(val param: Int) : ScriptEvent

    /** 劇本移動了武將（0x32 武将移动），畫面可以播走路動畫；狀態已經更新。 */
    data class Moved(val character: Int, val fromX: Int, val fromY: Int, val toX: Int, val toY: Int) : ScriptEvent

    /** 回合／階段開始的提示（原版 0x44AC28）：turn 從 1 起，side 0 = 我軍、2 = 敵軍。 */
    data class TurnStart(val turn: Int, val side: Int) : ScriptEvent

    /** 輪到玩家操作（原版 0x44E0D9）：操作完呼叫 BattleSession.endPlayerPhase，每個單位行動後呼叫 afterAction。 */
    data object PlayerPhase : ScriptEvent

    /** 戰鬥結束：result 是戰場物件 +1（2 = 0x49 战斗结束，通常是勝利；3 = 失敗或回合用完）。 */
    data class BattleEnd(val result: Int) : ScriptEvent
}

/**
 * 歷史情報的一條：mode 是寫入時變數 4071 的值（格式，1–3），text 開頭的數字是排序用的序號。
 */
data class GuideEntry(val mode: Int, val text: String) {
    /** 開頭的序號；沒有時排最後。 */
    val order: Int get() = text.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

    /** 去掉序號後的內容。 */
    val body: String get() = text.dropWhile { it.isDigit() }
}

/** 軌跡比對（docs/methodology.md 方法 2）：每執行一條指令回報一筆。 */
data class TraceRecord(
    val pos: Int,
    val op: Int,
    /** 條件指令的結果；不是條件指令時為 null。 */
    val test: Boolean?,
    val events: List<ScriptEvent>,
    val guide: List<GuideEntry>,
)

/** 劇本裡跟戰場／城內狀態有關的指令由宿主處理。 */
interface ScriptHost {
    /** 有副作用的指令（配置、地圖、形象…）。回傳 false 表示不認得（VM 會記錄下來）。 */
    fun exec(ins: Instr, vm: ScriptVM): Boolean = false

    /** 跟戰場狀態有關的條件（回合、行動方、單位狀態、勝敗…）。 */
    fun test(ins: Instr, vm: ScriptVM): Boolean = false

    /** 0x78 整型变量赋值 寫了武將屬性之後通知宿主（屬性存在 EngineMemory，見 Attributes）。 */
    fun attrWritten(character: Int, attr: Int) {}

    /** 對話裡用名字指定說話者（"&吕布"）時，換成武將編號。 */
    fun characterByName(name: String): Int? = null

    /** 宿主要讓畫面顯示的事件（例如武將移動），VM 會依序交出去。 */
    fun drainEvents(): List<ScriptEvent> = emptyList()
}

/**
 * EEX 直譯器：照原版在位元組串流上執行（語意見 docs/script.md）。
 * 用法：start(scene) 後反覆呼叫 next()，拿到事件就交給畫面；選擇題用 answer() 回答後再 next()。
 * next() 回傳 null 表示 scene 跑完。
 */
class ScriptVM(private val data: ByteArray, val mem: EngineMemory, private val host: ScriptHost) {
    private class Frame(val start: Int, val end: Int) {
        var cond = true
        var lastSub = true
    }

    private val frames = ArrayDeque<Frame>()
    private val pending = ArrayDeque<ScriptEvent>()
    private var pc = 0
    private var sectionsLeft = ArrayDeque<IntRange>()
    private var answer: Int? = null
    private var selection = 0
    private var finished = true
    /** 「歷史情報」：0x16 在變數 4071 ≠ 0 時寫進來的攻略條目（玩家從選單打開看）。 */
    val guide = ArrayList<GuideEntry>()

    /** 0x19 胜利条件 設定的文字。 */
    var victoryText = ""
        private set

    /** 設定後每執行一條指令就回報（軌跡比對用）。 */
    var trace: ((TraceRecord) -> Unit)? = null

    /** 不認得的指令（opcode → 次數），除錯用。 */
    val unknown = HashMap<Int, Int>()

    /** 認得但還沒移植的原版行為（說明 → 次數），除錯用。 */
    val gaps = HashMap<String, Int>()

    fun u16(p: Int) = (data[p].toInt() and 0xff) or ((data[p + 1].toInt() and 0xff) shl 8)

    /** scene 裡各 section 的本體範圍。 */
    fun sections(scene: Int): List<IntRange> {
        val off = Eex.sceneOffsets(data)[scene]
        var p = off + 2
        return List(u16(off)) {
            val len = u16(p)
            ((p + 2) until (p + 2 + len)).also { p += 2 + len }
        }
    }

    val sceneCount: Int get() = Eex.sceneOffsets(data).size

    /** 依序執行 scene 的所有 section。 */
    fun start(scene: Int) = begin(sections(scene))

    /** 只執行 scene 裡的某一個 section（戰場事件用）。 */
    fun startSection(scene: Int, section: Int) = begin(listOf(sections(scene)[section]))

    /** 劇本執行完了（或還沒開始），可以檢查戰場事件。 */
    val idle: Boolean get() = finished && pending.isEmpty()

    /**
     * 選戰場事件（原版 0x4100A1）：scene 的每個 section 依序執行開頭的指令（名稱、條件…）直到 opcode 0，
     * 條件全部成立的記下來；回傳**最後一個**成立的 section 與它的 opcode 0 位置，沒有回傳 null。
     * 條件指令照常執行（有副作用的也會做），所以每次檢查都會跑過所有 section 的條件。
     */
    fun selectEvent(scene: Int): Pair<Int, Int>? {
        var last: Pair<Int, Int>? = null
        sections(scene).forEachIndexed { i, r ->
            var p = r.first
            var ok = true
            while (p <= r.last) {
                val op = u16(p)
                if (op == 0) break
                if (op == 1) { gaps.merge("戰場事件的條件裡有子事件（opcode 1）", 1, Int::plus); ok = false; break }
                val (ins, after) = decode(p)
                if (isTest(op)) {
                    val t = traced(ins, true) { evalTest(ins) }
                    if (t == null) { gaps.merge("戰場事件的條件要問玩家（0x04）", 1, Int::plus); ok = false } else ok = ok && t
                } else {
                    traced(ins, false) { exec(ins); true }
                }
                p = after
            }
            if (ok && p <= r.last) last = i to p
        }
        return last
    }

    /** 從 [selectEvent] 選到的 section 的 opcode 0 開始執行它的本體（條件已經檢查過）。 */
    fun startEvent(scene: Int, event: Pair<Int, Int>) {
        val s = sections(scene)[event.first]
        sectionsLeft.clear()
        pending.clear()
        answer = null
        finished = false
        frames.clear()
        frames += Frame(s.first, s.last + 1)
        pc = event.second
    }

    private fun begin(list: List<IntRange>) {
        sectionsLeft.clear()
        sectionsLeft.addAll(list)
        frames.clear()
        pending.clear()
        answer = null
        finished = false
        nextSection()
    }

    private fun nextSection(): Boolean {
        frames.clear()
        val s = sectionsLeft.removeFirstOrNull() ?: run { finished = true; return false }
        frames += Frame(s.first, s.last + 1)
        pc = s.first
        return true
    }

    fun answer(value: Int) {
        answer = value
    }

    fun next(): ScriptEvent? {
        while (true) {
            pending.removeFirstOrNull()?.let { return it }
            if (finished) return null
            val top = frames.lastOrNull()
            if (top == null) {
                if (!nextSection()) return null
                continue
            }
            if (pc >= top.end) {
                frames.removeLast()
                if (frames.isEmpty() && !nextSection()) return null
                continue
            }
            val op = u16(pc)
            when (op) {
                0 -> {
                    if (pc + 2 >= top.end) {
                        pc += 2
                        frames.removeLast()
                        if (frames.isEmpty() && !nextSection()) return null
                        continue
                    }
                    val len = u16(pc + 2)
                    val body = pc + 4
                    if (top.cond) {
                        top.cond = true
                        frames += Frame(body, body + len)
                        pc = body
                    } else {
                        top.cond = true
                        pc = body + len
                    }
                }
                1 -> {
                    val (head, after) = decode(pc + 2)
                    val c = when {
                        head.op == 0x03 -> !top.lastSub
                        head.op == 0x12 -> {
                            val a = answer ?: return choice(head)
                            answer = null
                            traced(head, false) { selection = a; true }
                            true
                        }
                        isTest(head.op) -> {
                            val r = traced(head, true) { evalTest(head) } ?: return askFor(head)
                            r
                        }
                        else -> { traced(head, false) { exec(head); true }; true }
                    }
                    val len = u16(after)
                    val body = after + 2
                    top.lastSub = c
                    if (c) {
                        frames += Frame(body, body + len)
                        pc = body
                    } else {
                        pc = body + len
                    }
                }
                else -> {
                    val (ins, after) = decode(pc)
                    if (isTest(op)) {
                        val r = traced(ins, true) { evalTest(ins) } ?: return askFor(ins)
                        top.cond = top.cond && r
                        pc = after
                        continue
                    }
                    if (op == 0x12) {
                        val a = answer ?: return choice(ins)
                        answer = null
                        traced(ins, false) { selection = a; true }
                        pc = after
                        continue
                    }
                    pc = after
                    when (op) {
                        0x0C -> { frames.clear(); if (!nextSection()) return null }
                        0x0D -> { finished = true; return null }
                        0x76 -> traced(ins, false) { jump(after, ins.params[0].value); true }
                        else -> traced(ins, false) { exec(ins); true }
                    }
                }
            }
        }
    }

    /** 執行一條指令並回報軌跡。block 回傳 null 表示要先問玩家，這時不回報。 */
    private inline fun traced(ins: Instr, isTest: Boolean, block: () -> Boolean?): Boolean? {
        val t = trace ?: return block()
        val p0 = pending.size
        val g0 = guide.size
        val r = block() ?: return null
        t(TraceRecord(ins.pos, ins.op, if (isTest) r else null, pending.drop(p0), guide.drop(g0)))
        return r
    }

    private fun askFor(ins: Instr): ScriptEvent =
        ScriptEvent.YesNo(ins.params.firstOrNull()?.value ?: 0)

    private fun choice(ins: Instr): ScriptEvent =
        ScriptEvent.Choice(ins.param(0x02)?.value?.takeIf { it < 0xEFFF }, ins.params[0].text!!.split('\n'))

    private fun jump(from: Int, offset: Int) {
        pc = from + offset
        while (frames.size > 1 && (pc < frames.last().start || pc >= frames.last().end)) frames.removeLast()
    }

    /** 條件指令：回傳 null 表示要先問玩家（0x04）。 */
    /** 單獨算一條條件指令（比對測試用；參數要已經是實際的值）。 */
    fun evaluate(ins: Instr): Boolean? = evalTest(ins)

    private fun evalTest(ins: Instr): Boolean? {
        val p = ins.params
        return when (ins.op) {
            0x05 -> {
                val n1 = p[0].value
                VarOps.flagTest(mem, p.subList(1, 1 + n1).map { it.value }, p.subList(2 + n1, p.size).map { it.value })
            }
            0x79 -> VarOps.compare(mem, p[0].value, p[1].value.toLong() and MASK, p[2].value, p[3].value, p[4].value.toLong() and MASK)
            0x13 -> selection == p[0].value
            0x04 -> answer?.let { answer = null; it != 0 }
            0x37 -> moneyTest(p[0].value and 0xffff, p[1].value.toLong() and MASK, p[2].value and 0xffff)
            else -> host.test(ins, this)
        }
    }

    /**
     * 0x37 钱、章节编号、忠奸测试（0x4118BD）：(項目, 值, 比較)。項目 0 = 金錢 [0x4B077C]（dword），1 = 章節 = word [0x4B0776] / 2
     * （最多 0x7F），2 = byte [0x4B0782]；比較 0 ≥、1 <、其他 =（無號）。其他項目原版拿未初始化的區域變數比，當成還沒處理。
     */
    private fun moneyTest(kind: Int, v: Long, cmp: Int): Boolean? {
        val x = when (kind) {
            0 -> mem.u32(0x4B077CL)
            1 -> minOf(mem.u16(0x4B0776L) / 2, 0x7F).toLong()
            2 -> mem.u8(0x4B0782L).toLong()
            else -> { gaps.merge("0x37 項目 $kind（未初始化）", 1, Int::plus); return null }
        }
        return when (cmp) {
            0 -> x >= v
            1 -> x < v
            else -> x == v
        }
    }

    /**
     * 解碼一條指令，參數照原版讀參數函式（0x4183F7）換成實際的值：
     * - 型別 04（i32）是負數、而且 opcode < 0x6E：整數變數 −值。
     * - 武將型別（02、38、39）在 0xEFFF..0xFFFE：整數變數 0xFFFE − 值（0xFFFF 是「無」，不換）。
     */
    /** 劇本資料不會變，解碼結果（變數參照換成值之前）依位置快取。 */
    private val decoded = HashMap<Int, Pair<Instr, Int>>()
    /** 有變數參照、每次都要換成目前值的位置。 */
    private val withVars = HashSet<Int>()

    private fun decode(at: Int): Pair<Instr, Int> {
        val cached = decoded[at] ?: Eex.decode(data, at).also { d ->
            decoded[at] = d
            if (d.first.params.any { needsVar(d.first.op, it) }) withVars += at
        }
        if (at !in withVars) return cached
        val (ins, after) = cached
        val params = ins.params.map { p ->
            if (!needsVar(ins.op, p)) p
            else Param(p.type, mem.intVar(if (p.type == 4) -p.value else 0xFFFE - p.value).toInt(), p.text)
        }
        return Instr(ins.pos, ins.op, params) to after
    }

    private fun needsVar(op: Int, p: Param) = when (p.type) {
        4 -> p.value < 0 && op < 0x6E
        0x02, 0x38, 0x39 -> p.value in 0xEFFF..0xFFFE
        else -> false
    }

    private fun exec(ins: Instr) {
        val p = ins.params
        when (ins.op) {
            0x02 -> {}
            0x0A -> {
                for (i in 0 until 256) mem.setFlag(i, false)
                for (i in 0 until 320) mem.setU32(EngineMemory.LOCAL_DWORDS + 4L * i, 0)
            }
            0x0B -> mem.setFlag(p[0].value, p[1].value != 0)
            0x77 -> VarOps.assign(mem, p[0].value, p[1].value.toLong() and MASK, p[2].value, p[3].value, p[4].value.toLong() and MASK)
            0x38 -> if (!Stats.op38(mem, p[0].value, p[1].value, p[2].value, p[3].value.toLong() and MASK)) gaps.merge("0x38 戰場單位", 1, Int::plus)
            0x78 -> {
                val (v, mode, ch, attr) = p.take(4).map { it.value }
                if (!Attributes.execute(mem, attr, ch, mode, v)) gaps.merge("0x78 升級", 1, Int::plus)
                else if (mode == 1) host.attrWritten(ch, attr)
            }
            0x14, 0x7A -> dialogue(null, p[0].text!!)
            0x15 -> dialogue(p[0].value, p[2].text!!)
            0x63 -> dialogue(null, p[1].text!!)
            // 信息：變數 4071 = 0 時彈出訊息；否則寫進「歷史情報」（4071 是格式，0xFF 清空），不顯示
            0x16 -> {
                when (val mode = mem.intVar(4071)) {
                    0L -> pending += ScriptEvent.Message(p[0].text!!, ins.op)
                    0xFFL -> guide.clear()
                    else -> guide += GuideEntry(mode.toInt(), p[0].text!!)
                }
                mem.setIntVar(4071, 0) // 原版執行完一律重設（0x413ADF）
            }
            0x17, 0x69 -> pending += ScriptEvent.Message(p.last().text!!, ins.op)
            0x67 -> pending += ScriptEvent.Message(p[1].text!!, ins.op)
            // 0x18 事件名称设定、0x19 胜利条件：只記下來；0x1A 显示胜利条件 才顯示
            0x18 -> {}
            0x19 -> victoryText = p[0].text!!
            0x1A -> pending += ScriptEvent.Message(p[0].text!!.ifBlank { victoryText }, ins.op)
            else -> if (!host.exec(ins, this)) unknown.merge(ins.op, 1, Int::plus)
        }
        pending.addAll(host.drainEvents())
    }

    /** 對話文字："&名字\n內容\n&名字\n內容…"；沒有 & 時用 speaker。 */
    private fun dialogue(speaker: Int?, text: String) {
        var who = speaker?.takeIf { it < 0xEFFF }
        var name = ""
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) pending += ScriptEvent.Dialogue(who, name, buf.toString().trimEnd())
            buf.clear()
        }
        for (line in text.split('\n')) {
            if (line.startsWith("&")) {
                flush()
                name = line.substring(1).trim()
                who = host.characterByName(name)
            } else {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(line)
            }
        }
        flush()
    }

    companion object {
        private const val MASK = 0xFFFFFFFFL

        /** 回傳布林的條件指令（社群編輯器命名為「…测试」以及 case）。 */
        private val TESTS = setOf(
            0x04, 0x05, 0x07, 0x13, 0x25, 0x26, 0x2D, 0x2E, 0x36, 0x37, 0x3C, 0x3F, 0x40, 0x41, 0x42, 0x43,
            0x54, 0x5E, 0x6E, 0x73, 0x79, 0x7B,
        )

        fun isTest(op: Int) = op in TESTS
    }
}
