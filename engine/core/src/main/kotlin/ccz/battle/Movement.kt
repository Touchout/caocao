package ccz.battle

import ccz.data.ClassTables
import ccz.data.HexzMap

/** 朝向：劇本參數 2B 的值。由開戰配置推定（左邊的軍隊是 1、右邊是 3），順時針。 */
enum class Direction(val dx: Int, val dy: Int) {
    NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

    companion object {
        fun of(value: Int): Direction = entries.getOrElse(value) { SOUTH }

        fun between(fromX: Int, fromY: Int, toX: Int, toY: Int): Direction = when {
            toX > fromX -> EAST
            toX < fromX -> WEST
            toY < fromY -> NORTH
            else -> SOUTH
        }
    }
}

/** 戰場上的一個單位（原型用；之後會加上 HP、狀態等）。 */
class BattleUnit(
    val character: Int,
    val side: Side,
    var x: Int,
    var y: Int,
    var direction: Direction,
    val unitClass: Int,
    var hidden: Boolean = false,
    var moved: Boolean = false,
) {
    fun hostileTo(other: BattleUnit) = (side == Side.ENEMY) != (other.side == Side.ENEMY)
}

/**
 * 移動範圍（exe 0x438484 的規則，詳見 docs/battle.md「移動」）：
 *  - 依已花費移動力由小到大展開四方向鄰格，花費不能超過移動力
 *  - 每格消耗查地形表；不能進入的地形跳過
 *  - 敵軍所在格不能進入；友軍格可以穿過但不能停
 *  - 走到敵軍相鄰的格子就停下（ZOC），不再往外展開
 *  - 尚未實作：穿越移動、突擊移動、惡路移動等特效，地形狀態覆寫，地圖矩形限制
 */
class Movement(private val map: HexzMap, private val tables: ClassTables) {
    class Range(val cost: Array<IntArray>, private val parent: Array<IntArray>, private val stoppable: Array<BooleanArray>) {
        fun reachable(x: Int, y: Int) = y in cost.indices && x in cost[0].indices && cost[y][x] >= 0 && stoppable[y][x]

        /** 起點到 (x, y) 的路徑（含起點與終點）。 */
        fun path(x: Int, y: Int): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var cx = x
            var cy = y
            while (cx >= 0) {
                out += cx to cy
                val p = parent[cy][cx]
                if (p < 0) break
                cx = p % cost[0].size
                cy = p / cost[0].size
            }
            return out.reversed()
        }

        fun tiles(): List<Pair<Int, Int>> =
            cost.indices.flatMap { y -> cost[y].indices.filter { x -> reachable(x, y) }.map { x -> x to y } }
    }

    fun range(unit: BattleUnit, units: List<BattleUnit>, power: Int = tables.movement(unit.unitClass)): Range {
        val w = map.width
        val h = map.height
        val cost = Array(h) { IntArray(w) { -1 } }
        val parent = Array(h) { IntArray(w) { -1 } }
        val stoppable = Array(h) { BooleanArray(w) }
        val occupant = HashMap<Int, BattleUnit>()
        units.filter { !it.hidden && it !== unit }.forEach { occupant[it.y * w + it.x] = it }

        fun zoc(x: Int, y: Int) = Direction.entries.any { d ->
            occupant[(y + d.dy) * w + (x + d.dx)]?.takeIf { map.inside(x + d.dx, y + d.dy) }?.hostileTo(unit) == true
        }

        cost[unit.y][unit.x] = 0
        stoppable[unit.y][unit.x] = true
        // 消耗都是小整數，用分層佇列（跟 exe 一樣依花費由小到大）
        val buckets = Array(power + 1) { ArrayList<Int>() }
        buckets[0] += unit.y * w + unit.x
        for (spent in 0..power) {
            for (p in buckets[spent]) {
                val x = p % w
                val y = p / w
                if (cost[y][x] != spent) continue
                if ((x != unit.x || y != unit.y) && zoc(x, y)) continue
                for (d in Direction.entries) {
                    val nx = x + d.dx
                    val ny = y + d.dy
                    if (!map.inside(nx, ny)) continue
                    val c = tables.moveCost(unit.unitClass, map.terrain(nx, ny))
                    if (c == ClassTables.IMPASSABLE) continue
                    val next = spent + c
                    if (next > power) continue
                    if (cost[ny][nx] in 0..next) continue
                    val other = occupant[ny * w + nx]
                    if (other != null && other.hostileTo(unit)) continue
                    cost[ny][nx] = next
                    parent[ny][nx] = y * w + x
                    stoppable[ny][nx] = other == null
                    buckets[next] += ny * w + nx
                }
            }
        }
        return Range(cost, parent, stoppable)
    }
}
