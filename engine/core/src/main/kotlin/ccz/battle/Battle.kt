package ccz.battle

import ccz.data.ClassTables
import ccz.data.GameData
import ccz.data.HexzMap

/** 一場戰鬥的狀態（原型：只有位置、朝向、是否已行動）。 */
class Battle(val data: GameData, val tables: ClassTables, val map: HexzMap, val setup: BattleSetup) {
    val units: MutableList<BattleUnit> = ArrayList()
    /** 敵軍／友軍的 AI 方針（劇本 3E 參數），顯示用。 */
    val aiOf = HashMap<BattleUnit, Int>()
    val movement = Movement(map, tables)

    init {
        for (p in setup.units) {
            val u = BattleUnit(p.character, p.side, p.x, p.y, Direction.of(p.direction), data.characters[p.character].unitClass, p.hidden)
            units += u
            aiOf[u] = p.ai
        }
        // 我軍：強制出場的先排，空格先用曹操軍預設武將（0 號起、還沒出場的）補上。
        // 正式版要換成出陣選擇畫面。
        val used = units.map { it.character }.toMutableSet()
        val roster = (setup.forcedPlayers + (0 until 20)).distinct().filter { it !in used }.iterator()
        for (slot in setup.playerSlots.sortedBy { it.index }) {
            if (!roster.hasNext()) break
            val c = roster.next()
            used += c
            units += BattleUnit(c, Side.PLAYER, slot.x, slot.y, Direction.of(slot.direction), data.characters[c].unitClass)
        }
    }

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
    fun endTurn() = units.forEach { it.moved = false }
}
