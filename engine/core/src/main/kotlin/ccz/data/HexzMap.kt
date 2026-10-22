package ccz.data

import ccz.io.GameFiles

/**
 * 戰場地形（Hexzmap.e5 第 N 筆 ↔ MAP/mNNN.jpg）。
 * u8 寬、u8 高（單位 16 px，格數 = 值 / 3），後面每格 1 byte 地形編號，逐列排列；一格 48 px。
 */
class HexzMap(val id: Int, val width: Int, val height: Int, private val cells: ByteArray) {
    fun terrain(x: Int, y: Int): Int = cells[y * width + x].toInt() and 0xff

    /** 劇本改寫地形（0x58 战场障碍设定，例如關上城門）。 */
    fun setTerrain(x: Int, y: Int, t: Int) {
        if (inside(x, y)) cells[y * width + x] = t.toByte()
    }

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    companion object {
        const val TILE = 48

        fun load(files: GameFiles, id: Int): HexzMap {
            val d = files.archive("Hexzmap.e5")[id]
            val w = (d[0].toInt() and 0xff) / 3
            val h = (d[1].toInt() and 0xff) / 3
            return HexzMap(id, w, h, d.copyOfRange(2, 2 + w * h))
        }

        fun imagePath(id: Int) = "MAP/m%03d.jpg".format(id)
    }
}
