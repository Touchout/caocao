package ccz.data

import ccz.io.GameFiles
import ccz.io.GameFiles.Companion.cString

/** 武將資料（Data.e5 第 0 項，32 bytes）。欄位見 docs/formats.md；名稱開頭有 guess 的是推測。 */
data class Character(
    val id: Int,
    val name: String,
    val rImage: Int,
    val guessFace: Int,
    val force: Int,
    val command: Int,
    val intelligence: Int,
    val agility: Int,
    val luck: Int,
    val guessHpBase: Int,
    val level: Int,
    val unitClass: Int,
)

/** 道具（Data.e5 第 1 項，25 bytes）。數值欄位都還是推測，先只取名稱。 */
data class Item(val id: Int, val name: String)

class GameData(files: GameFiles) {
    val characters: List<Character>
    val items: List<Item>
    val classNames: List<String>
    val effectNames: List<String>
    /** 每名武將的預設戰場形象（0 = 用兵種通用圖）。 */
    val sImages: IntArray
    val terrainNames: List<String>
    /** 關卡名稱（Imsg.e5 0xC350 起每筆 200 bytes），索引 = S_nnn 的 nnn；6.6 只有前 100 關有名稱。 */
    val stageNames: List<String>

    init {
        val data = files.archive("Data.e5")
        val chars = data[0]
        characters = (0 until chars.size / 32).map { n ->
            val o = n * 32
            fun u8(i: Int) = chars[o + i].toInt() and 0xff
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            Character(
                id = n,
                name = cString(chars, o, 13),
                rImage = u16(0x0D),
                guessFace = u8(0x0F),
                force = u8(0x12),
                command = u8(0x13),
                intelligence = u8(0x14),
                agility = u8(0x15),
                luck = u8(0x16),
                guessHpBase = u16(0x17),
                level = u8(0x19),
                unitClass = u8(0x1A),
            )
        }
        val its = data[1]
        items = (0 until its.size / 25).map { n -> Item(n, cString(its, n * 25, 15)) }

        val exe = files.bytes("Ekd5.exe")
        fun table(off: Int, size: Int, stride: Int) = (0 until size / stride).map { cString(exe, off + it * stride, stride) }
        classNames = table(ExeLayout.CLASS_NAMES, 720, 9)
        effectNames = table(ExeLayout.EFFECT_NAMES, 4080, 16)
        terrainNames = TERRAIN_NAMES
        val imsg = files.bytes("Imsg.e5")
        stageNames = (0 until 100).map { cString(imsg, 0xC350 + it * 200, 200) }
        sImages = IntArray(1024) { i ->
            val p = ExeLayout.S_IMAGES + i * 2
            (exe[p].toInt() and 0xff) or ((exe[p + 1].toInt() and 0xff) shl 8)
        }
    }

    companion object {
        /** Ekd5.exe 0x8AE70 的地形名稱表（6.6 版），編號 0–33。 */
        val TERRAIN_NAMES = listOf(
            "平原", "草原", "树林", "荒地", "山地", "岩山", "山崖", "雪原", "桥梁", "浅滩",
            "沼泽", "池塘", "小河", "大河", "栅栏", "城墙", "城内", "城门", "城池", "关隘",
            "鹿砦", "村庄", "兵营", "民居", "宝物库", "水池", "火", "船", "祭坛", "地下",
            "地雷", "战壕", "掩体", "工事",
        )
    }
}

/** Ekd5.exe（新改曹操傳 6.6）內各表的檔案偏移。其他版本的引擎位置不同。 */
object ExeLayout {
    const val CLASS_NAMES = 0xD18D0
    const val EFFECT_NAMES = 0x9E800
    const val S_IMAGES = 0xD2800
}
