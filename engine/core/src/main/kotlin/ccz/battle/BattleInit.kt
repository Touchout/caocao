package ccz.battle

import ccz.data.Characters
import ccz.data.Equipment
import ccz.data.HexzMap
import ccz.data.NewGame
import ccz.data.Stats
import ccz.script.EngineMemory

/**
 * 開戰初始化（原版 0x44D2CA）：0x27 背景显示 進入戰場狀態時執行，從出場配置表建立戰場單位表（0x4A7B20）。
 * 規則與函式位址見 docs/battle.md「戰場單位表」「開戰初始化」。只做影響遊戲狀態的部分，畫面（單位圖、地圖圖片）不做。
 *
 * 地形與佔位表在原版放在緩衝區 8（0x48B140，GlobalAlloc 配置），位址沒有意義，這裡放在 [terrain]、[occupant]。
 */
class BattleInit(
    private val mem: EngineMemory,
    val map: HexzMap,
    /** Data.e5 第 0 項（敵軍重新載入用，原版開戰時重讀）。 */
    private val rawCharacters: ByteArray,
    /** 遇到還沒移植的原版路徑時記一筆（同 ScriptVM.gaps）。 */
    private val gap: (String) -> Unit,
) {
    /** 地形（緩衝區 8 + 0x640，寬 × 高）。 */
    val terrain = ByteArray(0x640)
    /** 佔位表（緩衝區 8 + 0xC80）：每格上的單位格號，0xFF = 沒有。 */
    val occupant = ByteArray(0x640) { 0xFF.toByte() }

    val width get() = mem.u8(MAP_WIDTH)
    val height get() = mem.u8(MAP_HEIGHT)

    fun run() {
        // 0x435A8D：250 格全部重設
        for (slot in 0 until SLOTS) resetUnit(slot)
        // 0x44C1C2：載入地圖（寬、高寫 0x4B422C／D，地形抄進緩衝區）
        mem.setU8(MAP_WIDTH, map.width)
        mem.setU8(MAP_HEIGHT, map.height)
        for (y in 0 until map.height) for (x in 0 until map.width) terrain[y * map.width + x] = map.terrain(x, y).toByte()
        createUnits()
        // 每一格收尾（0x44D3EF 起的迴圈）
        for (slot in 0 until SLOTS) {
            val u = unit(slot)
            mem.setU8(u + 0x0D, mem.u8(u + 0x0D) and 0x80.inv())   // 0x406690(0x80)
            clearHealth(u)                                          // 0x423FB9(0x1E)
            if (mem.u8(u + 0x0C) == 0) continue
            // 0x43FE2C 載入單位圖（畫面，不做）
            if (slot < 60 && side(u) == 0) setAction(u, 7)           // 0x413172、0x442669(7)
        }
    }

    // ---- 單位 ----

    fun unit(slot: Int): Long = UNITS + UNIT_SIZE.toLong() * slot

    /** 0x47D0BC：重設一格。 */
    private fun resetUnit(slot: Int) {
        val u = unit(slot)
        mem.setU8(u + 4, slot)
        mem.setU8(u + 0x0C, 0)
        mem.setU8(u + 0x2F, 0)
        mem.setU16(u + 0x0D, 0)
        mem.setU32(u, 0xFFFF)
        mem.setU16(u + 6, 0xFFFF)
        mem.setU8(u + 8, 0xFF)
        mem.setU32(u + 0x20, 0xFFFF)
        mem.setU8(u + 0x1F, 1)
    }

    /** 0x413172：陣營（0–6 以外當 0）。 */
    private fun side(u: Long): Int = mem.u8(u + 5).let { if (it <= 6) it else 0 }

    /** 0x442669：+0x0E = 行動方式（0–7）。 */
    private fun setAction(u: Long, v: Int) { if (v in 0..7) mem.setU8(u + 0x0E, v) }

    /** 0x423FB9(0x1E)：健康狀態 4 組全部清除。 */
    private fun clearHealth(u: Long) {
        var mask = 0
        var a = 0x1E
        for (i in 0 until 4) {
            a = a ushr 1
            if (a and 1 == 0) mask = mask or mem.u8(HEALTH_MASKS + i)
        }
        mem.setU8(u + 0x1E, mem.u8(u + 0x1E) and mask)
    }

    private fun inArmy(c: Int) = mem.u8(Characters.addr(mem, c) + 0x2A) != 0xFF // 0x42AC58

    /** 0x43BB15：依出場配置表建立單位。 */
    private fun createUnits() {
        var player = 0
        var ally = 20
        var enemy = 60
        for (i in 0 until SLOTS) {
            val rec = Deployment.record(i)
            val ch = mem.u16(rec)
            if (ch == 0xFFFF) continue
            val f = mem.u8(rec + 8)
            val x = mem.u8(rec + 4)
            val y = mem.u8(rec + 5)
            val slot: Int
            if (f and 3 == 0) {
                createPlayer(player++, ch, f, x, y)
                continue
            } else if (f and 1 == 0 && f and 2 != 0) {
                slot = ally++
                if (inArmy(ch)) createPlayer(slot, ch, f, x, y)
                else createOther(slot, f, rec, ch, x, y)
            } else {
                slot = if (f and 1 == 0) ally++ else enemy++
                createOther(slot, f, rec, ch, x, y)
            }
            setAi(ch, mem.u8(rec + 0x0C), mem.u16(rec + 0x0E), mem.u8(rec + 0x0E), mem.u8(rec + 0x0F), slot)
        }
    }

    /** 0x40C990：我軍（含我軍標識 ≠ 0xFF 的友軍）。 */
    private fun createPlayer(slot: Int, ch: Int, f: Int, x: Int, y: Int) {
        create(slot, ch, f, x, y)
        if (mem.u8(mem.intVarAddr(4039)) != 0) gap("0x40C990 變數 4039 的武器等級調整")
    }

    /** 0x42AD35：敵軍、非我軍的友軍。 */
    private fun createOther(slot: Int, f: Int, rec: Long, ch: Int, x: Int, y: Int) {
        // 0x46206C(武將, 50, 1)：從 Data.e5 重新載入
        if (ch < Characters.COUNT) Characters.load(mem, ch, rawCharacters, ch * 32, 1) else gap("0x46206C 武將編號 ≥ 1024")
        if (mem.u32(mem.intVarAddr(4075)) != 0L && mem.u8(0x5083FCL) != 0) gap("0x46206C 變數 4075 的五維調整")
        create(slot, ch, f, x, y)
        levelUp(slot, ch, mem.u8(rec + 2), mem.u8(rec + 3))
        equip(slot, 0, mem.u8(rec + 9), mem.u32(rec + 6))
        equip(slot, 1, mem.u8(rec + 0x0A), mem.u32(rec + 7))
        equip(slot, 2, mem.u8(rec + 0x0B), 0xFF)
        val u = unit(slot)
        // 0x43F6DB、0x43F715：目前 HP/MP = min(建立時的值, 上限)，傳進去的就是上限
        mem.setU32(u + 0x10, Equipment.maxHp(mem, ch))
        mem.setU32(u + 0x14, Equipment.maxMp(mem, ch))
    }

    /** 0x44274C：建立一個單位（最後一個參數開戰時一律 0）。 */
    private fun create(slot: Int, ch: Int, f: Int, x: Int, y: Int) {
        val u = unit(slot)
        val c = Characters.addr(mem, ch)
        mem.setU32(u, ch.toLong())
        mem.setU8(u + 0x0C, 1)
        // 0x425D9A：陣營
        mem.setU8(u + 5, if (f and 1 != 0) (if (f and 2 != 0) 3 else 2) else if (f and 2 != 0) 1 else 0)
        // 0x41DDCF：朝向
        mem.setU8(u + 0x0F, when {
            f and 0x08 != 0 -> 1
            f and 0x10 != 0 -> 3
            f and 0x20 != 0 -> 0
            else -> 2
        })
        mem.setU32(u + 0x10, Equipment.maxHp(mem, ch))
        mem.setU32(u + 0x14, Equipment.maxMp(mem, ch))
        mem.setU32(u + 0x18, 0x03030303)
        mem.setU16(u + 0x1C, 0x0303)
        mem.setU8(u + 0x1E, 0)
        place(slot, x, y, f and 8 != 0, f and 4 != 0)
        if (inArmy(ch)) {
            mem.setU8(c + 0x27, minOf(mem.u8(c + 0x27) + 1, 0xFF))  // 0x47FAB5：出戰場數
        } else {
            mem.setU8(c + 0x27, 0)
            mem.setU8(c + 0x28, 0)
            mem.setU8(c + 0x29, 0)
        }
    }

    /** 0x442B03：放上地圖。隱藏的不放（+0x0C 留 1、座標 0xFFFF）。 */
    private fun place(slot: Int, x0: Int, y0: Int, flip: Boolean, hidden: Boolean) {
        if (hidden) return
        val u = unit(slot)
        var x = x0
        var y = y0
        if (mem.u8(u + 0x0C) != 2) {
            mem.setU8(u + 0x0C, 2)
            // 0x43FD34：+0x0D bit 0x08 = 配置旗標 0x08
            mem.setU8(u + 0x0D, if (flip) mem.u8(u + 0x0D) or 0x08 else mem.u8(u + 0x0D) and 0x08.inv())
            val p = findPosition(x, y, slot, false)
            x = p and 0xff
            y = (p shr 8) and 0xff
        } else {
            gap("0x442B03 已經在地圖上的單位再放一次（0x4193B0）")
        }
        mem.setU8(u + 6, x)
        mem.setU8(u + 7, y)
        // 0x42DC72：佔位表
        if (x < width && y < height) occupant[y * width + x] = (if (slot >= 0xFA || mem.u8(unit(slot) + 0x0C) == 3) 0xFF else slot).toByte()
        else gap("0x42DC72 地圖外的座標")
        if (mem.u8(FOG) >= 2) gap("0x42A2AF 戰場迷霧")
    }

    // ---- 位置 ----

    /** 0x4359F5：往方向 d（0 北、1 東、2 南、3 西）的鄰格；超出地圖時 x = 0xFF。 */
    private fun neighbor(p: Int, d: Int): Int {
        val x = ((p and 0xff) + mem.u8(DIRECTIONS + 2 * d)) and 0xff
        val y = (((p shr 8) and 0xff) + mem.u8(DIRECTIONS + 2 * d + 1)) and 0xff
        return if (x >= width || y >= height) 0xFF or (y shl 8) else x or (y shl 8)
    }

    /** 0x435884：格上的單位格號（< 0xFA），沒有或地圖外是 0xFF。 */
    private fun occupantAt(p: Int): Int {
        val x = p and 0xff
        val y = (p shr 8) and 0xff
        if (x >= width || y >= height) return 0xFF
        val v = occupant[y * width + x].toInt() and 0xff
        return if (v < 0xFA) v else 0xFF
    }

    /** 0x41C2E6：可以放（沒有單位、移動消耗 ≠ 0xFF）。 */
    private fun canPlace(p: Int, slot: Int): Boolean = occupantAt(p) == 0xFF && moveCost(p, slot) != 0xFF

    /** 0x45936D：單位 [slot] 進入格 p 的移動消耗（地圖矩形限制、地形、兵種；不能進入時看特效 54 穿越移動）。 */
    private fun moveCost(p: Int, slot: Int): Int {
        if (!insideRect(p)) return 0xFF
        val ch = mem.u32(unit(slot)).toInt()
        val cls = mem.u8(Characters.addr(mem, ch) + 0x2B)
        val group = when {                                    // 0x40BF0C
            cls <= 0x3B -> cls / 3
            cls < 0x50 -> cls - 0x28
            else -> 0x28
        }
        val x = p and 0xff
        val y = (p shr 8) and 0xff
        if (x >= width || y >= height) return 0xFF            // 0x435991
        val t = terrainAt(x, y)
        val cost = mem.u8(NewGame.TERRAIN + 60L * group + 0x1E + t)  // 0x41B7D2、0x4060C4
        if (cost != 0xFF) return cost
        if (mem.u8(0x500AD3L) == 1) return 1                  // 0x403A6F
        gap("0x403A6F 特效 54 穿越移動（0x4101D9）")
        return 0xFF
    }

    /** 0x412557：地形（格上有單位時原版會看那個單位的特效 0xAB，放置時不會遇到）。 */
    private fun terrainAt(x: Int, y: Int): Int {
        if (occupant[y * width + x].toInt() and 0xff != 0xFF) gap("0x412557 有單位的格（特效 0xAB）")
        return terrain[y * width + x].toInt() and 0xff
    }

    /** 0x4362E6：地圖矩形限制 [0x501BF0]（x, y, 寬, 高；0 = 不限制）。 */
    private fun insideRect(p: Int): Boolean {
        val r = mem.u32(RECT)
        if (r == 0L) return true
        val x = p and 0xff
        val y = (p shr 8) and 0xff
        val rx = (r and 0xff).toInt()
        val ry = ((r shr 8) and 0xff).toInt()
        if (x < rx || y < ry) return false
        val ex = (rx + ((r shr 16) and 0xff).toInt()) and 0xff
        val ey = (ry + ((r shr 24) and 0xff).toInt()) and 0xff
        return x < ex && y < ey
    }

    /**
     * 0x43F07C：找放置位置。先看 (x, y) 本身；不行就看它的四個鄰格；再不行就往北、東、南、西逐步移動重看（共 4 次）。
     * 都不行時掃整張地圖，找曼哈頓距離最近的（同距離取先掃到的：y、x 由小到大）。回傳 x | y << 8，找不到 0xFFFF。
     */
    private fun findPosition(x0: Int, y0: Int, slot: Int, marked: Boolean): Int {
        var cur = x0 or (y0 shl 8)
        var found = 0xFFFF
        for (i in 0 until 4) {
            if (cur and 0xff == 0xFF) {
                cur = neighbor(cur, i)
                continue
            }
            if (canPlace(cur, slot)) {
                if (!marked) found = cur
                break
            }
            for (j in 0 until 4) {
                val n = neighbor(cur, j)
                if (n and 0xff != 0xFF && canPlace(n, slot)) { found = n; break }
            }
            if (found and 0xff != 0xFF) break
            cur = neighbor(cur, i)
        }
        if (found and 0xff != 0xFF) return found
        if (marked) gap("0x43F07C 只找標記格")
        var best = Long.MAX_VALUE
        for (y in 0 until height) for (x in 0 until width) {
            val p = x or (y shl 8)
            if (!canPlace(p, slot)) continue
            val d = (Math.abs(y0 - y) + Math.abs(x0 - x)).toLong()
            if (d < best) { best = d; found = p }
        }
        return found
    }

    // ---- 敵軍等級、裝備 ----

    /**
     * 0x442890(型別 45, 等級)：依型別 45（0 不轉職、1 15 級以上轉一階、2 30 級以上轉兩階）調兵種，設等級，
     * 再把攻擊～MP 各加上等級成長（0x406ECA(項目, 0x408A60(項目), 0)）。
     */
    private fun levelUp(slot: Int, ch: Int, promote: Int, level: Int) {
        val c = Characters.addr(mem, ch)
        val cls = mem.u8(c + 0x2B)
        var mode = promote
        if (ch >= 0x400) mode = when (mem.u8(0x510CF9L)) { 0 -> 0; 1 -> 1; 2, 3 -> 2; else -> promote }
        val l = level.toByte().toInt()
        val small = cls.toByte() <= 0x3B
        when (mode and 0xff) {
            1 -> if (small && l >= 15) promoteClass(c, cls, 1)
            2 -> if (small) { if (l >= 30) promoteClass(c, cls, 2) else if (l >= 15) promoteClass(c, cls, 1) }
        }
        mem.setU8(c + 0x2C, level)                               // 0x40E012
        for (stat in 0 until 7) Stats.add(mem, ch, stat, Stats.levelGrowth(mem, ch, stat))
    }

    /** 0x422E07(兵種, n)：兵種 + n 跟目前兵種同一組（0x40687F）就換過去，否則 n − 1 再試（n > 1 時）。 */
    private fun promoteClass(c: Long, cls: Int, n0: Int) {
        val group = groupOf(mem.u8(c + 0x2B))
        var n = n0
        while (true) {
            val t = cls + n
            if (groupOf(t and 0xff) == group) { mem.setU8(c + 0x2B, t and 0xff); return }   // 0x40E01F
            if (n <= 1) return
            n--
        }
    }

    /** 0x40687F／0x40BF0C：兵種 → 組。 */
    private fun groupOf(cls: Int) = when {
        cls < 0x3C -> cls / 3
        cls < 0x50 -> cls - 0x28
        else -> 0x28
    }

    /**
     * 0x44299B(裝備欄, 值, 等級)：配置 +9／+0xA／+0xB 的值。0xFF、≥ 150 不換；0 = 依武將等級自動配（0x407FD4）；
     * 1 = 卸下（0x406CBC）；其他 = 指定道具 值 − 2（防具 +70、輔助 +109；等級 0xFE 時不減不加，0x407DF1）。
     * [level] 是原版壓進堆疊的 dword（配置 +6 起 4 bytes），多數地方只看低位 byte。
     */
    private fun equip(slot: Int, kind: Int, value: Int, level: Long) {
        if (value == 0xFF || value >= 0x96 || kind > 2) return
        val u = unit(slot)
        if (mem.u8(u + 0x0C) == 0 || mem.u32(u) == 0xFFFFL) return
        val ch = mem.u32(u).toInt()
        val c = Characters.addr(mem, ch)
        when (value) {
            0 -> Equipment.autoEquip(mem, ch, kind, false)
            1 -> {
                if (level == 1L && mem.u8(c + 0x2A) == 0) gap("0x406CBC 卸下的裝備放回道具欄")
                setEquip(c, kind, 0xFF, 0xFF, 0xFF)                 // 0x420649
            }
            else -> giveItem(c, kind, value, (level and 0xff).toInt())
        }
        // 0x4158DC → 0x479A01：狀態視窗更新（畫面）
    }

    /** 0x41CA49：裝備欄 [kind] = 道具；輔助（kind 2）只寫道具。 */
    private fun setEquip(c: Long, kind: Int, id: Int, level: Int, exp: Int) {
        mem.setU8(c + 0x2E + 3 * kind, id)
        if (kind != 2) {
            mem.setU8(c + 0x2F + 3 * kind, level)
            mem.setU8(c + 0x30 + 3 * kind, exp)
        }
    }

    /**
     * 0x407DF1(裝備欄, 值, 等級)：裝上指定道具。道具類別（0x40B444）跟欄位不合就不裝。
     * 等級 0 = 依武將等級（0x407DA3）；超過上限（會成長的奇數種類 10，其他 5）取上限；0xFF 照寫。
     * 經驗 = 等級 < 上限 ? 0 : 0xFF，等級 9 時再減 1（0x4201D5）。
     */
    private fun giveItem(c: Long, kind: Int, value: Int, level: Int) {
        var lv = level
        val id = if (lv == 0xFE) { lv = 0; value } else value - 2 + when (kind) { 0 -> 0; 1 -> 0x46; else -> 0x6D }
        val ip = Equipment.ITEMS + Equipment.ITEM_SIZE.toLong() * id
        val k = mem.u8(ip + 0x11)
        val category = when { k < 0x14 -> 0; k < 0x1A -> 1; else -> mem.u8(ip + 0x14) }
        if (category != kind) return
        val odd = Equipment.levels(mem, id) && k % 2 == 1
        val max = if (odd) 10 else 5
        if (lv == 0) {
            val cl = mem.u8(c + 0x2C)
            lv = if (odd) minOf(cl / 6 + 1, 9) else if (cl >= 60) 5 else (cl % 20) / 4 + 1
        } else if (lv != 0xFF && lv > max) {
            lv = max
        }
        var exp = if (lv < max) 0 else 0xFF
        if (lv == 9) exp = (exp - 1) and 0xff
        setEquip(c, kind, id and 0xff, if (kind == 2) 0xFF else lv, exp)
        if (mem.u8(c + 0x2A) != 0xFF && id < 0xFF) gap("0x408B0D 我軍取得道具的紀錄")
    }

    // ---- AI ----

    /** 0x459D95(武將, AI, 目標武將, 目標x, 目標y, 格)。 */
    private fun setAi(ch: Int, ai: Int, targetChar: Int, tx: Int, ty: Int, slot: Int) {
        val u: Long
        if (slot != 0) {
            u = unit(slot)
            if (mem.u16(u) == 0xFFFF) return
        } else {
            gap("0x459D95 格 0（用武將找單位，0x41B882）")
            return
        }
        if (ai.toByte() < 0 || ai > 7) return
        if (inArmy(mem.u32(u).toInt())) return
        mem.setU8(u + 0x0D, mem.u8(u + 0x0D) and 0x80.inv())
        mem.setU8(u + 8, 0xFF)
        setAction(u, mem.u8(AI_ACTIONS + ai))
        when (ai) {
            3, 5 -> {                                              // 0x42632C：目標武將
                val t = (0 until SLOTS).firstOrNull { mem.u32(unit(it)) == targetChar.toLong() && mem.u8(unit(it) + 0x0C) != 0 }
                if (t != null) {
                    mem.setU8(u + 0x0D, mem.u8(u + 0x0D) and 0x80.inv())
                    mem.setU8(u + 8, mem.u8(unit(t) + 4))
                }
            }
            4, 6 -> if (tx < width && ty < height) {               // 0x427FB0、0x44268C：目標位置
                mem.setU8(u + 0x0D, mem.u8(u + 0x0D) and 0x80.inv())
                mem.setU8(u + 9, tx)
                mem.setU8(u + 0x0A, ty)
                mem.setU8(u + 8, 0xFF)
            }
        }
    }

    companion object {
        const val UNITS = 0x4A7B20L
        const val UNIT_SIZE = 0x30
        const val SLOTS = 250
        const val MAP_WIDTH = 0x4B422CL
        const val MAP_HEIGHT = 0x4B422DL
        /** 地圖矩形限制（0x4362E6）。 */
        const val RECT = 0x501BF0L
        /** 戰場迷霧模式（≥ 2 時放置單位會更新視野，0x42A297）。 */
        const val FOG = 0x501BD2L
        /** 方向 → (dx, dy)（0x435A3E）。 */
        private const val DIRECTIONS = 0x435A3EL
        /** AI 0–7 → 行動方式（0x48E379）。 */
        private const val AI_ACTIONS = 0x48E379L
        /** 健康狀態 4 組的遮罩（0x4240D2）。 */
        private const val HEALTH_MASKS = 0x4240D2L
    }
}
