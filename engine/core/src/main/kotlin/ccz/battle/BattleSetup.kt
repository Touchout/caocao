package ccz.battle

import ccz.data.GameData
import ccz.script.Instr
import ccz.script.Script

enum class Side { PLAYER, ALLY, ENEMY }

data class Placement(
    val character: Int,
    val side: Side,
    val x: Int,
    val y: Int,
    /** 2B 參數：朝向。數值意義還沒確認。 */
    val direction: Int,
    /** 3E 參數：AI 方針，見 tools/eex_types.json。 */
    val ai: Int,
    /** 推測：一開始隱藏、之後才出現的伏兵。 */
    val hidden: Boolean,
)

/** 我軍出場格（0x4B）。哪名武將站上來由玩家決定，強制出場（0x4A）的先排。 */
data class PlayerSlot(val index: Int, val x: Int, val y: Int, val direction: Int)

/**
 * 從戰場劇本（S_*.eex）取出開戰時的配置。只做靜態掃描、不判斷條件，所以條件分支裡的配置也會被收進來，
 * 這是原型階段的簡化。
 */
class BattleSetup(
    val mapId: Int,
    val units: List<Placement>,
    val playerSlots: List<PlayerSlot>,
    val forcedPlayers: List<Int>,
    /** 0x75 S特殊形象指定：武將 → 形象編號 */
    val imageOverrides: Map<Int, Int>,
) {
    companion object {
        private const val T_CHAR = 0x02
        private const val T_INT = 0x04
        private const val NONE = 0xFFFF

        fun from(script: Script): BattleSetup {
            var mapId = -1
            val units = ArrayList<Placement>()
            val slots = ArrayList<PlayerSlot>()
            val forced = ArrayList<Int>()
            val images = HashMap<Int, Int>()
            for (ins in script.walk()) {
                when (ins.op) {
                    // 背景显示：模式 3 = 戰場，型別 0x15 = 地圖編號（實際再加 變數4051×100，原型先當 0）
                    0x27 -> if (mapId < 0 && ins.param(0x2D)?.value == 3) mapId = ins.param(0x15)!!.value
                    0x46 -> units += records(ins, 11, Side.ALLY)
                    0x47 -> units += records(ins, 12, Side.ENEMY)
                    0x4B -> {
                        val ints = ins.params.filter { it.type == T_INT }
                        slots += PlayerSlot(ints[0].value, ints[1].value, ints[2].value, ins.param(0x2B)!!.value)
                    }
                    0x4A -> ins.params.filter { it.type == 0x38 && it.value != NONE && it.value < 0xEFFF }
                        .forEach { if (it.value !in forced) forced += it.value }
                    0x75 -> images[ins.param(T_CHAR)!!.value] = ins.param(0x50)!!.value
                }
            }
            return BattleSetup(mapId, units.distinctBy { Triple(it.character, it.x, it.y) }, slots.distinctBy { it.index }, forced, images)
        }

        /**
         * 0x46 友軍每人 11 個參數：武將, 26, x, y, 朝向, AI, 45, 07, 目標武將, 目標x, 目標y
         * 0x47 敵軍每人 12 個參數：武將, 26, 26, x, y, 朝向, AI, …（多一個 26）
         */
        private fun records(ins: Instr, stride: Int, side: Side): List<Placement> =
            ins.params.chunked(stride).mapNotNull { r ->
                val ch = r[0].value
                if (r.size < stride || ch == NONE || ch >= 0xEFFF) return@mapNotNull null
                val off = stride - 11
                Placement(
                    character = ch,
                    side = side,
                    x = r[2 + off].value,
                    y = r[3 + off].value,
                    direction = r[4 + off].value,
                    ai = r[5 + off].value,
                    hidden = r[1 + off].value == 1,
                )
            }

        /**
         * 戰場人物圖在 Unit_*.e5 的編號：
         *  - 武將有專屬形象 s（> 0）：240 + s − 1
         *  - 否則用兵種通用圖：兵種 × 3 + 陣營（0 我軍、1 友軍、2 敵軍；配色是從圖推測的）
         */
        fun spriteIndex(data: GameData, character: Int, side: Side, overrides: Map<Int, Int>): Int {
            val s = overrides[character] ?: data.sImages.getOrElse(character) { 0 }
            return if (s > 0) 240 + s - 1 else data.characters[character].unitClass * 3 + side.ordinal
        }
    }
}
