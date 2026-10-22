package ccz.battle

import ccz.data.ClassTables
import ccz.data.GameData
import ccz.data.HexzMap

/** 一場戰鬥的狀態（原型：只有位置、朝向、是否已行動）。單位由劇本執行時逐步加入（BattleSession）。 */
class Battle(val data: GameData, val tables: ClassTables, val map: HexzMap) {
    val units: MutableList<BattleUnit> = ArrayList()
    /** 敵軍／友軍的 AI 方針（劇本 3E 參數），顯示用。 */
    val aiOf = HashMap<BattleUnit, Int>()
    val playerSlots = ArrayList<PlayerSlot>()
    val forcedPlayers = ArrayList<Int>()
    /** 0x75 S特殊形象指定：武將 → 形象編號 */
    val imageOverrides = HashMap<Int, Int>()
    /** 劇本改過的兵種（0x52 兵种改变、0x78 寫兵種）。 */
    val classOverrides = HashMap<Int, Int>()
    val movement = Movement(map, tables)
    var turn = 1

    fun unitClass(character: Int) = classOverrides[character] ?: data.characters[character].unitClass

    fun add(p: Placement) {
        if (units.any { it.character == p.character && !it.hidden }) return
        val u = BattleUnit(p.character, p.side, p.x, p.y, Direction.of(p.direction), unitClass(p.character), p.hidden)
        units += u
        aiOf[u] = p.ai
    }

    /** 我軍：強制出場的先排，空格先用曹操軍預設武將（0 號起、還沒出場的）補上。正式版要換成出陣選擇畫面。 */
    fun fillPlayers() {
        val used = units.map { it.character }.toMutableSet()
        val roster = (forcedPlayers + (0 until 20)).distinct().filter { it !in used }.iterator()
        for (slot in playerSlots.sortedBy { it.index }) {
            if (units.any { it.x == slot.x && it.y == slot.y }) continue
            if (!roster.hasNext()) break
            val c = roster.next()
            used += c
            units += BattleUnit(c, Side.PLAYER, slot.x, slot.y, Direction.of(slot.direction), unitClass(c))
        }
    }

    fun unitOf(character: Int): BattleUnit? = units.firstOrNull { it.character == character }

    fun unitAt(x: Int, y: Int): BattleUnit? = units.firstOrNull { !it.hidden && it.x == x && it.y == y }

    fun range(unit: BattleUnit) = movement.range(unit, units)

    /** 移動到 (x, y)，回傳走過的路徑；不能到達時回傳 null。 */
    fun move(unit: BattleUnit, x: Int, y: Int): List<Pair<Int, Int>>? {
        val r = range(unit)
        if (!r.reachable(x, y)) return null
        val path = r.path(x, y)
        if (path.size >= 2) {
            val (px, py) = path[path.size - 2]
            unit.direction = Direction.between(px, py, x, y)
        }
        unit.x = x
        unit.y = y
        unit.moved = true
        return path
    }

    /** 我軍全部行動完，或玩家按結束回合：重設（原型還沒有敵軍行動）。 */
    fun endTurn() {
        units.forEach { it.moved = false }
        turn++
    }

    companion object {
        /** 靜態掃描建戰場（不執行劇本，desktop 預覽的舊方式）。 */
        fun fromSetup(data: GameData, tables: ClassTables, map: HexzMap, setup: BattleSetup) = Battle(data, tables, map).apply {
            setup.units.forEach { add(it) }
            playerSlots += setup.playerSlots
            forcedPlayers += setup.forcedPlayers
            imageOverrides += setup.imageOverrides
            fillPlayers()
        }
    }
}
