package ccz.battle

/**
 * Unit_mov.e5 移動圖：48×48 一格、縱向 11 格。
 * 0–1 向下走、2–3 向上走、4–5 向左走、6 向下站、7 向上站、8 向左站、9–10 虛弱；向右 = 向左水平鏡像。
 */
object Sprites {
    data class Frame(val index: Int, val mirror: Boolean)

    fun standFrame(d: Direction) = when (d) {
        Direction.SOUTH -> Frame(6, false)
        Direction.NORTH -> Frame(7, false)
        Direction.WEST -> Frame(8, false)
        Direction.EAST -> Frame(8, true)
    }

    /** 走路動畫的第 step 格（0、1 交替）。 */
    fun walkFrame(d: Direction, step: Int) = when (d) {
        Direction.SOUTH -> Frame(0 + step % 2, false)
        Direction.NORTH -> Frame(2 + step % 2, false)
        Direction.WEST -> Frame(4 + step % 2, false)
        Direction.EAST -> Frame(4 + step % 2, true)
    }
}
