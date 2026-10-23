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
    /** 第幾回合（從 1 起）：原版存在戰場物件 0x4B3D08 +2（從 0 起）。 */
    val turn: Int get() = this.field.mem.u8(BattleTests.BATTLE + 2) + 1

    fun unitOf(character: Int): BattleUnit? = units.firstOrNull { it.character == character }

    fun unitAt(x: Int, y: Int): BattleUnit? = units.firstOrNull { !it.hidden && it.x == x && it.y == y }

    /**
     * 移動範圍：原版玩家選單位時的搜尋 0x438484(&單位+6, 移動力 0x43F74F, 0, 0xFF, 0xFF, 1)（`Search`，跟原版逐格一致）。
     * 可停留 = 到得了而且格上沒有別的單位。路徑（只給動畫用）從終點往回找花費接得上的鄰格。
     */
    fun range(unit: BattleUnit): Movement.Range {
        val s = field.search
        val u = Units.unit(unit.slot)
        s.search(u, s.movePoints(u))
        val w = map.width
        val h = map.height
        fun c(x: Int, y: Int) = (s.cost[y * w + x].toInt() and 0xff).let { if (it == 0xFF) -1 else it }
        val cost = Array(h) { y -> IntArray(w) { x -> c(x, y) } }
        val parent = Array(h) { IntArray(w) { -1 } }
        val stoppable = Array(h) { y -> BooleanArray(w) { x -> cost[y][x] >= 0 && field.occupantAt(x or (y shl 8)).let { it == 0xFF || it == unit.slot } } }
        for (y in 0 until h) for (x in 0 until w) {
            val v = cost[y][x]
            if (v <= 0) continue
            val step = s.moveCost(u, s.terrainAt(x or (y shl 8))).first
            parent[y][x] = Direction.entries.map { (x - it.dx) to (y - it.dy) }
                .firstOrNull { (px, py) -> px in 0 until w && py in 0 until h && cost[py][px] >= 0 && cost[py][px] + step == v }
                ?.let { (px, py) -> py * w + px } ?: -1
        }
        return Movement.Range(cost, parent, stoppable)
    }

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

    /** 我軍階段開始：清除「已行動」（回合數由 TurnFlow 管）。 */
    fun startPlayerPhase() {
        views.forEach { it.moved = false }
    }
}
