package ccz.battle

import ccz.data.GameData
import ccz.data.HexzMap
import ccz.text.Zh

/** 戰場上點選武將／格子時顯示的文字（繁體）。 */
object UnitInfo {
    /** 型別 0x3E 的選項，取自社群劇本編輯器。 */
    private val AI = listOf("被動出擊", "主動出擊", "堅守原地", "攻擊武將", "到指定點", "跟隨武將", "逃到指定點")
    private val SIDE = mapOf(Side.PLAYER to "我軍", Side.ALLY to "友軍", Side.ENEMY to "敵軍")

    fun unit(data: GameData, map: HexzMap, u: Placement): List<String> {
        val c = data.characters[u.character]
        val cls = data.classNames.getOrElse(c.unitClass) { "兵種${c.unitClass}" }
        val ai = AI.getOrElse(u.ai) { "方針${u.ai}" }
        val terrain = terrainName(data, map, u.x, u.y)
        return listOf(
            Zh.toTraditional("${c.name}（${SIDE[u.side]}）　$cls　Lv ${c.level}") + if (u.hidden) "　［伏兵］" else "",
            "武力 ${c.force}　統率 ${c.command}　智力 ${c.intelligence}　敏捷 ${c.agility}　運氣 ${c.luck}",
            Zh.toTraditional("座標 (${u.x}, ${u.y})　地形：$terrain　AI：$ai"),
        )
    }

    fun tile(data: GameData, map: HexzMap, x: Int, y: Int): List<String> =
        listOf(Zh.toTraditional("座標 ($x, $y)　地形：${terrainName(data, map, x, y)}"))

    private fun terrainName(data: GameData, map: HexzMap, x: Int, y: Int) =
        if (map.inside(x, y)) data.terrainNames.getOrElse(map.terrain(x, y)) { "?" } else "地圖外"
}
