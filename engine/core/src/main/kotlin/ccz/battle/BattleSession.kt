package ccz.battle

import ccz.data.Characters
import ccz.data.ClassTables
import ccz.data.Equipment
import ccz.data.GameData
import ccz.data.HexzMap
import ccz.data.NewGame
import ccz.data.Stats
import ccz.io.GameFiles
import ccz.script.Attributes
import ccz.script.EngineMemory
import ccz.script.Instr
import ccz.script.ScriptEvent
import ccz.script.ScriptHost
import ccz.script.ScriptVM
import ccz.text.Zh

/**
 * 一關戰場：執行戰場劇本（S_nnn.eex）的 scene 0（開戰處理與戰前對話），邊執行邊建出戰場。
 * 用法：反覆呼叫 next() 取得要顯示的事件，null 表示開戰處理跑完；之後 battle 就是可以操作的戰場。
 * 劇本執行語意見 docs/script.md。
 */
class BattleSession(
    private val files: GameFiles,
    val data: GameData,
    val tables: ClassTables,
    val stage: Int,
    val mem: EngineMemory = EngineMemory(files.bytes("Ekd5.exe")),
    /** 出陣（玩家輸入）：開新遊戲時當成「上一場的出戰名單」，出陣選擇畫面也回傳這些。原型預設只派曹操。 */
    val sortie: List<Int> = listOf(0),
) : ScriptHost {
    val vm = ScriptVM(files.bytes("RS/S_%03d.eex".format(stage)), mem, this)

    /** 0x27 背景显示（戰場）執行後才知道地圖，之前的配置先暫存。 */
    var battle: Battle? = null
        private set
    /** 戰場狀態（單位表、地形、佔位表；0x27 開戰時建立）。 */
    var field: BattleField? = null
        private set
    private val early = ArrayList<(Battle) -> Unit>()
    private val events = ArrayList<ScriptEvent>()
    var setupDone = false
        private set


    /** Data.e5 第 0 項原始資料（0x46/0x47 讀裝備用，原版每次重讀）。 */
    private val rawCharacters: ByteArray by lazy { files.archive("Data.e5")[0] }

    private val nameIndex: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        for (c in data.characters) {
            if (c.name.isBlank()) continue
            m.putIfAbsent(c.name, c.id)
            m.putIfAbsent(Zh.toTraditional(c.name), c.id)
        }
        m
    }

    init {
        // 開新遊戲：原版載入資料後的記憶體（傳入的記憶體已經有了就沿用，例如接續上一關）
        if (mem.u32(Characters.PTR) != Characters.ARRAY) {
            NewGame.init(mem, files)
            Deployment.setRoster(mem, sortie)
        }
        vm.start(0)
    }

    fun next(): ScriptEvent? {
        val e = vm.next()
        if (e == null && !setupDone) setupDone = true
        return e
    }

    fun answer(value: Int) = vm.answer(value)

    private fun onBattle(f: (Battle) -> Unit) {
        val b = battle
        if (b != null) f(b) else early += f
    }

    override fun exec(ins: Instr, vm: ScriptVM): Boolean {
        val p = ins.params
        val f = field
        when (ins.op) {
            // 背景显示：模式 3 = 戰場，0x15 = 地圖編號 + 變數4051 × 100
            0x27 -> if (ins.param(0x2D)?.value == 3 && battle == null) {
                // 0xFFFF 時原版用 99 號（0x413DA8）
                val raw = ins.param(0x15)!!.value.let { if (it == 0xFFFF) 99 else it }
                val id = (raw + mem.intVar(4051) * 100).toInt() and 0xffff
                mem.setU16(MAP_ID, id.toLong())   // 0x413D13：寫地圖編號，變數 4051 用過歸 0
                mem.setIntVar(4051, 0)
                val map = HexzMap.load(files, id)
                // 原版：0x27 讓遊戲進入戰場狀態，劇本暫停，執行開戰初始化 0x44D2CA 後才繼續
                val nf = BattleField(mem, map, rawCharacters) { vm.gaps.merge(it, 1, Int::plus) }
                nf.init()
                field = nf
                val b = Battle(data, tables, map, nf)
                battle = b
                early.forEach { it(b) }
                early.clear()
            }
            0x46, 0x47 -> {
                val enemy = ins.op == 0x47
                if (!Deployment.place(mem, ins, enemy, rawCharacters)) vm.gaps.merge("出戰名單是空的（原版除以零）", 1, Int::plus)
                placements(ins, if (enemy) 12 else 11, if (enemy) Side.ENEMY else Side.ALLY).forEach { pl -> onBattle { it.aiOf[pl.character] = pl.ai } }
            }
            0x44 -> Deployment.battleInit(mem)
            0x48 -> Deployment.equip(mem, p[0].value, listOf(p[1].value, p[3].value, p[5].value), listOf(p[2].value, p[4].value))
            0x4B -> {
                val ints = p.filter { it.type == 0x04 }
                Deployment.placePlayer(mem, ints[0].value, ints[1].value, ints[2].value, ins.param(0x2B)!!.value, ins.param(0x26)?.value ?: 0xFFFF)
            }
            // 0x4A 我军出场强制设定：原版在這裡開出陣選擇畫面（0x418597），選完寫出戰名單
            0x4A -> Deployment.forceSortie(mem, ins, sortie)
            0x75 -> { val c = p[0].value; val img = p[1].value; onBattle { it.imageOverrides[c] = img } }
            // 0x52 兵种改变（0x414BD4）：寫兵種；不能用的裝備換成自動配裝（0x414B93）；變數 4081 低位 ≠ 0 時重算能力
            0x52 -> {
                val c = p[0].value; val cls = p[1].value
                if (c < 0x400) {
                    mem.setU8(Characters.addr(mem, c) + 0x2B, cls and 0xff)
                    Equipment.checkAfterClassChange(mem, c)
                    if (battle != null) vm.gaps.merge("0x52 戰場上換兵種（0x477ADC 單位更新）", 1, Int::plus)
                    if (mem.u8(EngineMemory.INT_VARS + 4L * 4081) != 0) Stats.recompute(mem, c, rawCharacters)
                } else if (battle != null && c <= 0x402) vm.gaps.merge("0x418CE0 武將 0x400–0x402（目前行動的單位）", 1, Int::plus)
            }
            // 战场障碍设定：(42 編號, 43 ?, 44 地形, x, y, 26, 26)。原型直接改地形（原版 0x58 還沒讀）。
            0x58 -> { val t = p[2].value; val x = p[3].value; val y = p[4].value; onBattle { it.map.setTerrain(x, y, t) } }
            // 战场转向设置（武將, 目標武將, 方向, 背對, ?, ?）：0x4575B8
            0x4F -> f?.turn(p[0].value, p[1].value, p[2].value, p[3].value)
            // 武将移动（模式, 武將, 格, x, y, 方向）：原版在戰場上走 0x4594EA → 移動執行 0x43AD3B（還沒移植），暫代：直接放到目的地
            0x32 -> if (f != null) {
                vm.gaps.merge("0x32 戰場上的武將移動（0x4594EA → 0x43AD3B）", 1, Int::plus)
                val u = battle!!.unitOf(p[1].value)
                if (u != null && !u.hidden) {
                    val (fx, fy) = u.x to u.y
                    f.scriptMove(u.slot, p[3].value, p[4].value, p[5].value)
                    events += ScriptEvent.Moved(u.character, fx, fy, p[3].value, p[4].value)
                }
            }
            // 隐藏武将出现（模式, 武將, 格）：0x414ADC
            0x4C -> f?.appear(p[0].value, p[1].value, p[2].value)
            // 武将消失（模式, 武將, x0, y0, x1, y1, 陣營）：0x413522；不在戰場時原版走 0x406640（還沒移植）
            0x31 -> if (f != null) f.disappear(p[0].value, p[1].value, IntArray(4) { p[2 + it].value }, p[6].value)
                else vm.gaps.merge("0x31 不在戰場時（0x406640）", 1, Int::plus)
            // 武将方针变更：0x4146D4
            0x4E -> f?.changeAi(IntArray(11) { p[it].value })
            // 战场撤退（模式, 武將, x0, y0, x1, y1, 陣營, 旗標）：0x41569E
            0x53 -> f?.retreat(p[0].value, p[1].value, IntArray(4) { p[2 + it].value }, p[6].value, p[7].value)
            // 武将状态变更：0x415551
            0x4D -> if (f != null) f.changeStatus(IntArray(13) { p[it].value })
            // 認得但原型還不處理的：战场全局变量、绘图、CD音轨、音效、信息传送、菜单处理、
            // 延时、战场动作设定、战场操作开始、方针变更、单挑动作…
            0x45, 0x1C, 0x1D, 0x1E, 0x24, 0x23, 0x72, 0x08, 0x20, 0x5D, 0x56, 0x57, 0x1B,
            0x09, 0x50, 0x5A, 0x64, 0x65, 0x66, 0x5B, 0x5C, 0x21 -> {}
            else -> return false
        }
        return true
    }

    override fun test(ins: Instr, vm: ScriptVM): Boolean {
        val b = battle ?: return false
        val p = ins.params
        return when (ins.op) {
            // 回合测试（回合數, 比較）：比較 0 >=、1 <、2 =、3 ≠
            0x3F -> compare(b.turn, p[0].value, p[1].value)
            else -> false
        }
    }

    private fun compare(a: Int, b: Int, cmp: Int) = when (cmp) {
        0 -> a >= b
        1 -> a < b
        2 -> a == b
        else -> a != b
    }

    /** 武將屬性目前的值（0x78 的屬性編號）；單位屬性在武將不在戰場上時是 0。 */
    fun attr(character: Int, attr: Int): Long = Attributes.get(mem, attr, character) ?: 0

    override fun drainEvents(): List<ScriptEvent> = events.toList().also { events.clear() }

    override fun characterByName(name: String): Int? = nameIndex[name] ?: nameIndex[Zh.toTraditional(name)]

    /** 頭像編號（劇本改過的優先）。 */
    fun face(character: Int): Int = attr(character, ATTR_FACE).toInt()

    /**
     * 0x46 友軍每人 11 個參數：武將, 26 隱藏, x, y, 2B 朝向, 3E 等級, 45, 07 AI, 目標武將, 目標x, 目標y
     * 0x47 敵軍每人 12 個參數：武將後面多一個 26，其餘同上
     */
    private fun placements(ins: Instr, stride: Int, side: Side): List<Placement> =
        ins.params.chunked(stride).mapNotNull { r ->
            val ch = r[0].value
            if (r.size < stride || ch == 0xFFFF || ch >= 0xEFFF) return@mapNotNull null
            val off = stride - 11
            Placement(ch, side, r[2 + off].value, r[3 + off].value, r[4 + off].value, r[7 + off].value, r[1 + off].value == 1, r[5 + off].value)
        }

    companion object {
        /** 戰場地圖編號（0x413D13 寫入，0x44C1C2 讀）。 */
        const val MAP_ID = 0x510CFEL
        // 型別 55 屬性編號（tools/eex_types.json）
        const val ATTR_R_IMAGE = 0
        const val ATTR_FACE = 1
        const val ATTR_CLASS = 17
        const val ATTR_LEVEL = 18
    }
}
