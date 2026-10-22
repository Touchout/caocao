package ccz.battle

import ccz.data.ClassTables
import ccz.data.GameData
import ccz.data.HexzMap
import ccz.io.GameFiles
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
) : ScriptHost {
    val vm = ScriptVM(files.bytes("RS/S_%03d.eex".format(stage)), mem, this)

    /** 0x27 背景显示（戰場）執行後才知道地圖，之前的配置先暫存。 */
    var battle: Battle? = null
        private set
    private val early = ArrayList<(Battle) -> Unit>()
    private val events = ArrayList<ScriptEvent>()
    var setupDone = false
        private set

    /** 武將屬性（0x78 讀寫）。原型只保存劇本寫過的值，其餘取 Data.e5。 */
    private val attrs = HashMap<Pair<Int, Int>, Long>()

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
        vm.start(0)
    }

    fun next(): ScriptEvent? {
        val e = vm.next()
        if (e == null && !setupDone) {
            setupDone = true
            battle?.fillPlayers()
        }
        return e
    }

    fun answer(value: Int) = vm.answer(value)

    private fun onBattle(f: (Battle) -> Unit) {
        val b = battle
        if (b != null) f(b) else early += f
    }

    override fun exec(ins: Instr, vm: ScriptVM): Boolean {
        val p = ins.params
        when (ins.op) {
            // 背景显示：模式 3 = 戰場，0x15 = 地圖編號 + 變數4051 × 100
            0x27 -> if (ins.param(0x2D)?.value == 3 && battle == null) {
                // 0xFFFF 時原版用 99 號（0x413DA8）
                val raw = ins.param(0x15)!!.value.let { if (it == 0xFFFF) 99 else it }
                val id = raw + (mem.intVar(4051) * 100).toInt()
                val b = Battle(data, tables, HexzMap.load(files, id))
                battle = b
                early.forEach { it(b) }
                early.clear()
            }
            0x46 -> placements(ins, 11, Side.ALLY).forEach { pl -> onBattle { it.add(pl) } }
            0x47 -> placements(ins, 12, Side.ENEMY).forEach { pl -> onBattle { it.add(pl) } }
            0x4B -> {
                val ints = p.filter { it.type == 0x04 }
                val slot = PlayerSlot(ints[0].value, ints[1].value, ints[2].value, ins.param(0x2B)!!.value)
                onBattle { b -> b.playerSlots.removeAll { it.index == slot.index }; b.playerSlots += slot }
            }
            0x4A -> p.filter { it.type == 0x38 && it.value < 0xEFFF }.map { it.value }
                .forEach { c -> onBattle { b -> if (c !in b.forcedPlayers) b.forcedPlayers += c } }
            0x75 -> { val c = p[0].value; val img = p[1].value; onBattle { it.imageOverrides[c] = img } }
            0x52 -> { val c = p[0].value; val cls = p[1].value; onBattle { it.classOverrides[c] = cls; it.units.filter { u -> u.character == c }.forEach { u -> u.unitClass = cls } } }
            // 战场障碍设定：(42 編號, 43 ?, 44 地形, x, y, 26, 26)。原型直接改地形。
            0x58 -> { val t = p[2].value; val x = p[3].value; val y = p[4].value; onBattle { it.map.setTerrain(x, y, t) } }
            // 战场转向设置（武將, 面向的武將, 方向, …）
            0x4F -> onBattle { b ->
                val u = b.unitOf(p[0].value) ?: return@onBattle
                val target = b.unitOf(p[1].value)
                u.direction = if (target != null) Direction.between(u.x, u.y, target.x, target.y)
                else Direction.of(p[2].value).takeIf { p[2].value != 0xFFFF } ?: u.direction
            }
            // 武将移动（模式, 武將, ?, x, y, 方向）
            0x32 -> onBattle { b ->
                val u = b.unitOf(p[1].value) ?: return@onBattle
                val (fx, fy) = u.x to u.y
                val tx = p[3].value
                val ty = p[4].value
                if (fx != tx || fy != ty) u.direction = Direction.between(fx, fy, tx, ty)
                u.x = tx
                u.y = ty
                if (p[5].value != 0xFFFF) u.direction = Direction.of(p[5].value)
                events += ScriptEvent.Moved(u.character, fx, fy, tx, ty)
            }
            // 隐藏武将出现（?, 武將, ?）
            0x4C -> onBattle { b -> b.units.filter { it.character == p[1].value }.forEach { it.hidden = false } }
            // 武将消失（選取方式 2C, 武將, …）：原型只處理指定單一武將
            0x31 -> if (p[0].value == 0) onBattle { b -> b.units.removeAll { it.character == p[1].value } }
            // 認得但原型還不處理的：战斗初始化、战场全局变量、敌方装备设定、绘图、CD音轨、音效、信息传送、菜单处理、
            // 延时、战场动作设定、战场操作开始、武将能力设定、方针变更、状态变更、单挑动作…
            0x44, 0x45, 0x48, 0x1C, 0x1D, 0x1E, 0x24, 0x23, 0x72, 0x08, 0x20, 0x5D, 0x56, 0x57, 0x1B,
            0x09, 0x50, 0x5A, 0x38, 0x4E, 0x4D, 0x64, 0x65, 0x66, 0x5B, 0x5C, 0x21 -> {}
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

    override fun getAttr(character: Int, attr: Int): Long = attrs[character to attr] ?: when (attr) {
        ATTR_R_IMAGE -> data.characters.getOrNull(character)?.rImage?.toLong() ?: 0
        ATTR_FACE -> data.characters.getOrNull(character)?.guessFace?.toLong() ?: 0
        ATTR_CLASS -> (battle?.unitClass(character) ?: data.characters.getOrNull(character)?.unitClass ?: 0).toLong()
        ATTR_LEVEL -> data.characters.getOrNull(character)?.level?.toLong() ?: 0
        else -> 0
    }

    override fun setAttr(character: Int, attr: Int, value: Long) {
        attrs[character to attr] = value
        if (attr == ATTR_CLASS) onBattle { b ->
            b.classOverrides[character] = value.toInt()
            b.units.filter { it.character == character }.forEach { it.unitClass = value.toInt() }
        }
    }

    override fun drainEvents(): List<ScriptEvent> = events.toList().also { events.clear() }

    override fun characterByName(name: String): Int? = nameIndex[name] ?: nameIndex[Zh.toTraditional(name)]

    /** 頭像編號（劇本改過的優先）。 */
    fun face(character: Int): Int = getAttr(character, ATTR_FACE).toInt()

    /**
     * 0x46 友軍每人 11 個參數：武將, 26, x, y, 朝向, AI, 45, 07, 目標武將, 目標x, 目標y
     * 0x47 敵軍每人 12 個參數：武將, 26, 26, x, y, 朝向, AI, …（多一個 26）
     */
    private fun placements(ins: Instr, stride: Int, side: Side): List<Placement> =
        ins.params.chunked(stride).mapNotNull { r ->
            val ch = r[0].value
            if (r.size < stride || ch == 0xFFFF || ch >= 0xEFFF) return@mapNotNull null
            val off = stride - 11
            Placement(ch, side, r[2 + off].value, r[3 + off].value, r[4 + off].value, r[5 + off].value, r[1 + off].value == 1)
        }

    companion object {
        // 型別 55 屬性編號（tools/eex_types.json）
        const val ATTR_R_IMAGE = 0
        const val ATTR_FACE = 1
        const val ATTR_CLASS = 17
        const val ATTR_LEVEL = 18
    }
}
