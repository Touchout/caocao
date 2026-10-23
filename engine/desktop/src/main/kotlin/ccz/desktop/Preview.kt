package ccz.desktop

import ccz.battle.BattleSession
import ccz.battle.BattleSetup
import ccz.battle.Direction
import ccz.battle.Side
import ccz.battle.UnitInfo
import ccz.data.ClassTables
import ccz.data.GameData
import ccz.data.HexzMap
import ccz.data.HexzMap.Companion.TILE
import ccz.io.GameFiles
import ccz.script.ScriptEvent
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
 * 用法：./gradlew :desktop:run --args="<遊戲目錄> <關卡編號> <輸出.png> [武將編號]"
 * 給了武將編號就顯示他的資料和移動範圍。第 5 個參數給 dialog:N 時，改畫第 N 句對話的對話框（版面同 App）。
 */
fun main(args: Array<String>) {
    val files = GameFiles(File(args[0]))
    val stage = args[1].toInt()
    val data = GameData(files)
    // 執行戰場劇本的開戰處理（選擇題一律選第一個／是），跟 App 一樣
    val session = BattleSession(files, data, ClassTables(files), stage)
    val dialogues = ArrayList<String>()
    val dialogueEvents = ArrayList<ScriptEvent.Dialogue>()
    val dump = System.getenv("CCZ_DUMP_EVENTS") != null
    while (true) {
        val e = session.next() ?: break
        if (dump) println("事件 " + e.toString().replace('\n', '/').take(160))
        when (e) {
            is ScriptEvent.Choice, is ScriptEvent.YesNo -> session.answer(1)
            is ScriptEvent.Dialogue -> {
                dialogues += Zh.toTraditional("${e.name}：${e.text.replace('\n', ' ')}")
                dialogueEvents += e
            }
            else -> {}
        }
    }
    val battle = session.battle!!
    val map = battle.map
    val setup = battle
    val mapImage = ImageIO.read(files.find(HexzMap.imagePath(map.id)))
    val units = battle.units.filter { !it.hidden }
    val focus = args.getOrNull(3)?.toInt()?.let { id -> units.firstOrNull { it.character == id } }

    val infoLines = focus?.let { UnitInfo.unit(data, battle.tables, map, it, battle.aiOf[it]) }
        ?: listOf(Zh.toTraditional(data.stageNames.getOrElse(stage) { "" }))
    val panel = 40 + infoLines.size * 30
    val img = BufferedImage(mapImage.width, mapImage.height + panel, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.drawImage(mapImage, 0, 0, null)
    g.color = Color(60, 140, 255, 70)
    for (s in setup.playerSlots) g.fillRect(s.x * TILE, s.y * TILE, TILE, TILE)

    focus?.let { u ->
        val r = battle.range(u)
        g.color = if (u.side == Side.ENEMY) Color(255, 60, 60, 90) else Color(60, 120, 255, 100)
        for ((x, y) in r.tiles()) g.fillRect(x * TILE, y * TILE, TILE, TILE)
    }

    val sprites = HashMap<Pair<Int, Direction>, BufferedImage?>()
    for (u in units) {
        val index = BattleSetup.spriteIndex(data, u.character, u.unitClass, u.side, battle.imageOverrides)
        val sprite = sprites.getOrPut(index to u.direction) {
            runCatching {
                val strip = ImageIO.read(ByteArrayInputStream(files.archive("Unit_mov.e5")[index]))
                val frame = ccz.battle.Sprites.standFrame(u.direction)
                val f = BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until TILE) for (x in 0 until TILE) {
                    val sx = if (frame.mirror) TILE - 1 - x else x
                    val rgb = strip.getRGB(sx, frame.index * TILE + y)
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
    args.getOrNull(4)?.takeIf { it.startsWith("dialog:") }?.let { arg ->
        val e = dialogueEvents[arg.removePrefix("dialog:").toInt()]
        val lines = Zh.toTraditional(e.text).split('\n')
        val face = 120
        val boxH = maxOf(face + 32, 30 * (lines.size + 1) + 32)
        val top = mapImage.height - boxH
        g.color = Color(10, 10, 30, 215)
        g.fillRect(0, top, img.width, boxH)
        var x = 16
        e.speaker?.let { sp ->
            runCatching {
                val f = ImageIO.read(ByteArrayInputStream(files.archive("E5/Face.e5")[session.face(sp)]))
                val k = BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_ARGB)
                for (yy in 0 until f.height) for (xx in 0 until f.width) {
                    val rgb = f.getRGB(xx, yy); k.setRGB(xx, yy, if (rgb and 0xffffff == 0xF700FF) 0 else rgb)
                }
                g.drawImage(k, 16, top + 16, null)
                x += face + 16
            }
        }
        g.font = Font("WenQuanYi Zen Hei", Font.BOLD, 24)
        g.color = Color(255, 220, 120)
        g.drawString(Zh.toTraditional(e.speaker?.let { data.characters[it].name } ?: e.name), x, top + 40)
        g.font = Font("WenQuanYi Zen Hei", Font.PLAIN, 22)
        g.color = Color.WHITE
        lines.forEachIndexed { i, l -> g.drawString(l.trim(), x, top + 40 + 30 * (i + 1)) }
    }
    g.color = Color.BLACK
    g.fillRect(0, mapImage.height, img.width, panel)
    g.color = Color.WHITE
    g.font = Font("WenQuanYi Zen Hei", Font.PLAIN, 22)
    infoLines.forEachIndexed { i, line -> g.drawString(line, 16, mapImage.height + 34 + i * 30) }
    g.dispose()
    ImageIO.write(img, "png", File(args[2]))
    println("S_%03d 地圖 %d：%d 名武將、%d 個我軍出場格、%d 句對話 → %s".format(stage, map.id, units.size, setup.playerSlots.size, dialogues.size, args[2]))
    dialogues.take(8).forEach { println("  $it") }
    focus?.let { println("${data.characters[it.character].name} @(${it.x},${it.y}) 可到 ${battle.range(it).tiles().size} 格") }
}
