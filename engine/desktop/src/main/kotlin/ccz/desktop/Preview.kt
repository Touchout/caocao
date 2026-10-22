package ccz.desktop

import ccz.battle.BattleSetup
import ccz.battle.Placement
import ccz.battle.Side
import ccz.battle.UnitInfo
import ccz.data.GameData
import ccz.data.HexzMap
import ccz.data.HexzMap.Companion.TILE
import ccz.io.GameFiles
import ccz.script.Eex
import ccz.text.Zh
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 把戰場開戰配置畫成 PNG（跟 Android 版 BattleView 同樣的規則）。
 * 用法：./gradlew :desktop:run --args="<遊戲目錄> <關卡編號> <輸出.png> [要顯示資料的武將編號]"
 */
fun main(args: Array<String>) {
    val files = GameFiles(File(args[0]))
    val stage = args[1].toInt()
    val data = GameData(files)
    val setup = BattleSetup.from(Eex.parse(files.bytes("RS/S_%03d.eex".format(stage))))
    val map = HexzMap.load(files, setup.mapId)
    val mapImage = ImageIO.read(files.find(HexzMap.imagePath(setup.mapId)))
    val players = setup.playerSlots.sortedBy { it.index }.mapIndexedNotNull { i, slot ->
        setup.forcedPlayers.getOrNull(i)?.let { Placement(it, Side.PLAYER, slot.x, slot.y, slot.direction, 0, false) }
    }
    val units = setup.units + players

    val infoLines = args.getOrNull(3)?.toInt()?.let { id -> units.firstOrNull { it.character == id }?.let { UnitInfo.unit(data, map, it) } }
        ?: listOf(Zh.toTraditional(data.stageNames.getOrElse(stage) { "" }))
    val panel = 40 + infoLines.size * 30
    val img = BufferedImage(mapImage.width, mapImage.height + panel, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.drawImage(mapImage, 0, 0, null)
    g.color = Color(60, 140, 255, 70)
    for (s in setup.playerSlots) g.fillRect(s.x * TILE, s.y * TILE, TILE, TILE)

    val sprites = HashMap<Int, BufferedImage?>()
    for (u in units) {
        val index = BattleSetup.spriteIndex(data, u.character, u.side, setup.imageOverrides)
        val sprite = sprites.getOrPut(index) {
            runCatching {
                val strip = ImageIO.read(ByteArrayInputStream(files.archive("Unit_mov.e5")[index]))
                val f = BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until TILE) for (x in 0 until TILE) {
                    val rgb = strip.getRGB(x, 6 * TILE + y)
                    f.setRGB(x, y, if (rgb and 0xffffff == 0xF700FF) 0 else rgb)
                }
                f
            }.getOrNull()
        } ?: continue
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, if (u.hidden) 0.45f else 1f)
        g.drawImage(sprite, u.x * TILE, u.y * TILE, null)
        g.composite = AlphaComposite.SrcOver
        g.color = when (u.side) { Side.PLAYER -> Color(230, 60, 60); Side.ALLY -> Color(240, 170, 40); Side.ENEMY -> Color(60, 120, 240) }
        g.fillRect(u.x * TILE + 2, u.y * TILE + TILE - 6, TILE - 4, 4)
    }
    g.color = Color.BLACK
    g.fillRect(0, mapImage.height, img.width, panel)
    g.color = Color.WHITE
    g.font = Font("WenQuanYi Zen Hei", Font.PLAIN, 22)
    infoLines.forEachIndexed { i, line -> g.drawString(line, 16, mapImage.height + 34 + i * 30) }
    g.dispose()
    ImageIO.write(img, "png", File(args[2]))
    println("S_%03d 地圖 %d：%d 名武將、%d 個我軍出場格 → %s".format(stage, setup.mapId, units.size, setup.playerSlots.size, args[2]))
}
