package ccz

import ccz.battle.BattleSetup
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
}
