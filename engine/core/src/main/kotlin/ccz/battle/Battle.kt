package ccz.battle

import ccz.data.ClassTables
import ccz.data.GameData
import ccz.data.HexzMap

/**
 * 一場戰鬥：單位都在 [field] 的記憶體單位表裡（開戰初始化 0x44D2CA 建立），這裡只是給畫面用的檢視與玩家操作。
 */
class Battle(val data: GameData, val tables: ClassTables, val map: HexzMap, val field: BattleField) {
    private val views = Array(BattleField.SLOTS) { BattleUnit(field.mem, it) }

    /** 有單位的格子（含伏兵），依格號排列。 */
    val units: List<BattleUnit> get() = views.filter { it.present }
    /** 敵軍／友軍的 AI 方針（劇本 0x46／0x47 的參數），依武將，顯示用。 */
    val aiOf = HashMap<Int, Int>()
    /** 0x75 S特殊形象指定：武將 → 形象編號 */
    val imageOverrides = HashMap<Int, Int>()
    val movement = Movement(map, tables)
    var turn = 1

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
        field.moveUnit(unit.slot, x, y)
        unit.moved = true
        return path
    }

    /** 我軍全部行動完，或玩家按結束回合：重設（原型還沒有敵軍行動）。 */
    fun endTurn() {
        views.forEach { it.moved = false }
        turn++
    }
}
