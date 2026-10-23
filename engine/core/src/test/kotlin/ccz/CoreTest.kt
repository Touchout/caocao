package ccz

import ccz.battle.Battle
import ccz.battle.BattleSetup
import ccz.data.ClassTables
import ccz.battle.Side
import ccz.data.GameData
import ccz.data.HexzMap
import ccz.io.GameFiles
import ccz.io.Ls12
import ccz.script.Eex
import ccz.text.Zh
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZhTest {
    @Test
    fun simplifiedToTraditional() {
        assertEquals("張遼", Zh.toTraditional("张辽"))
        assertEquals("傳令全軍，引誘同盟軍深入", Zh.toTraditional("传令全军，引诱同盟军深入"))
        assertEquals("遊戲指南", Zh.toTraditional("遊戲指南"))
        assertEquals("夏侯惇", Zh.toTraditional("夏侯惇"))
        assertEquals("皇后", Zh.toTraditional("皇后"))
        assertEquals("然後再攻擊", Zh.toTraditional("然后再攻击"))
        assertEquals("誰也不准擅自出擊", Zh.toTraditional("谁也不准擅自出击"))
        assertEquals("準備", Zh.toTraditional("准备"))
    }
}

/** 需要遊戲檔：./gradlew :core:test -Pgame=<遊戲目錄>。沒給就跳過。 */
class GameFilesTest {
    private val files: GameFiles? = System.getProperty("ccz.game")?.let { GameFiles(File(it)) }

    @Test
    fun allScriptsParse() {
        val f = files ?: return
        val scripts = f.list("RS").filter { Regex("rs/[rs]_\\d+\\.eex").matches(it) }
        assertEquals(249, scripts.size)
        val failed = scripts.mapNotNull { path ->
            runCatching { Eex.parse(f.bytes(path)) }.exceptionOrNull()?.let { "$path: ${it.message}" }
        }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }

    @Test
    fun battleSetupS002() {
        val f = files ?: return
        val data = GameData(f)
        val setup = BattleSetup.from(Eex.parse(f.bytes("RS/S_002.eex")))
        assertEquals(2, setup.mapId)
        val lvbu = setup.units.first { it.character == 119 }
        assertEquals(Side.ENEMY, lvbu.side)
        assertEquals(8 to 9, lvbu.x to lvbu.y)
        assertEquals("吕布", data.characters[119].name)
        val map = HexzMap.load(f, setup.mapId)
        assertEquals(20 to 20, map.width to map.height)
        assertEquals("兵营", data.terrainNames[map.terrain(8, 9)])
    }

    @Test
    fun compressedArchive() {
        val f = files ?: return
        // DT.e5 第 7 項有壓縮：7508 → 64580 bytes，解出來的檔頭跟未壓縮的項目一致
        val dt = f.archive("E5/DT.e5")
        assertEquals(64580, dt[7].size)
        assertEquals(0x32, dt[7][4].toInt())
        assertTrue(Ls12.isLs(f.bytes("Data.e5")))
    }

    @Test
    fun spriteIndex() {
        val f = files ?: return
        val data = GameData(f)
        fun idx(c: Int, side: Side = Side.PLAYER) = BattleSetup.spriteIndex(data, c, data.characters[c].unitClass, side, emptyMap())
        assertEquals(240, idx(0))   // 曹操 S=1、群雄（兵種 0，初級）
        assertEquals(243, idx(1))   // 夏侯惇 S=2、驃騎兵（33，初級）
        assertEquals(660, idx(6))   // 夏侯淵 S=357 → 形象 500 → 660
        assertEquals(3 * 3 + 2, idx(210, Side.ENEMY)) // 步兵1：沒有專屬形象，輕步兵（3）敵軍
        assertEquals(242, BattleSetup.spriteIndex(data, 0, 2, Side.PLAYER, emptyMap())) // 曹操當霸王（高級）
    }

    @Test
    fun classTables() {
        val f = files ?: return
        val t = ClassTables(f)
        assertEquals(6, t.movement(9))    // 轻骑兵
        assertEquals(4, t.movement(3))    // 轻步兵
        assertEquals(2, t.hitArea(6))     // 弓兵：弓兵（一一格）
        assertEquals(2, t.moveCost(9, 2)) // 轻骑兵在树林
        assertEquals(ClassTables.IMPASSABLE, t.moveCost(3, 6)) // 步兵不能上山崖
        assertEquals(23, ClassTables.group(63))
        assertEquals(1, ClassTables.group(5))
    }

    @Test
    fun movementRangeRules() {
        val f = files ?: return
        val data = GameData(f)
        val tables = ClassTables(f)
        val setup = BattleSetup.from(Eex.parse(f.bytes("RS/S_002.eex")))
        val battle = Battle.fromSetup(data, tables, HexzMap.load(f, setup.mapId), setup)
        val players = battle.units.filter { it.side == Side.PLAYER }
        assertTrue(players.isNotEmpty())
        for (u in players) {
            val r = battle.range(u)
            val power = tables.movement(u.unitClass)
            for ((x, y) in r.tiles()) {
                assertTrue(r.cost[y][x] <= power)
                val other = battle.unitAt(x, y)
                assertTrue(other == null || other === u, "不能停在有人的格子")
                val path = r.path(x, y)
                assertEquals(u.x to u.y, path.first())
                // 路徑中途（不含起點、終點）不能經過敵軍相鄰格（ZOC）
                for ((px, py) in path.drop(1).dropLast(1)) {
                    val adjEnemy = listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0).any { (dx, dy) ->
                        battle.unitAt(px + dx, py + dy)?.side == Side.ENEMY
                    }
                    assertTrue(!adjEnemy, "ZOC：($px, $py) 旁邊有敵軍，不該繼續走")
                }
            }
        }
    }
}
