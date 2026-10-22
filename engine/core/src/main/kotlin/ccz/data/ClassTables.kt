package ccz.data

import ccz.io.GameFiles

/**
 * 兵種資料（Data.e5 第 3 項，80 × 35 bytes）與地形表（第 4 項，40 組 × (適性 30 + 移動消耗 30)）。
 * 依據見 docs/battle.md「移動」。
 */
class ClassTables(files: GameFiles) {
    private val classes: ByteArray
    private val terrain: ByteArray

    init {
        val data = files.archive("Data.e5")
        classes = data[3]
        terrain = data[4]
    }

    /** 兵種移動力（byte 0）。 */
    fun movement(unitClass: Int): Int = u8(classes, unitClass * CLASS_SIZE)

    /** 攻擊範圍編號（byte 1，對應 E5/Hitarea.e5 與社群編輯器的 Hitarea 清單）。 */
    fun hitArea(unitClass: Int): Int = u8(classes, unitClass * CLASS_SIZE + 1)

    /** 地形移動消耗；IMPASSABLE = 不能進入。 */
    fun moveCost(unitClass: Int, terrainId: Int): Int {
        val g = group(unitClass)
        if (g >= GROUPS || terrainId >= TERRAINS) return IMPASSABLE
        return u8(terrain, g * 60 + 30 + terrainId)
    }

    /** 地形適性（%）；不能進入時是 0。 */
    fun aptitude(unitClass: Int, terrainId: Int): Int {
        val g = group(unitClass)
        if (g >= GROUPS || terrainId >= TERRAINS || moveCost(unitClass, terrainId) == IMPASSABLE) return 0
        return u8(terrain, g * 60 + terrainId)
    }

    companion object {
        const val CLASS_SIZE = 35
        const val GROUPS = 40
        const val TERRAINS = 30
        const val IMPASSABLE = 0xFF

        /**
         * 兵種 → 地形表組別（exe 0x40BF0C）：前 60 個兵種每 3 個一組（初／中／高級共用），
         * 60–79 號各自一組（20–39），其餘 40（無效）。
         */
        fun group(unitClass: Int): Int = when {
            unitClass <= 59 -> unitClass / 3
            unitClass < 80 -> unitClass - 40
            else -> GROUPS
        }

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    }
}
