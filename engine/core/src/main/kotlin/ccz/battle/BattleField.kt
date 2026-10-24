package ccz.battle

import ccz.data.Characters
import ccz.data.Equipment
import ccz.data.HexzMap
import ccz.data.NewGame
import ccz.data.Stats
import ccz.script.EngineMemory

/**
 * 戰場狀態：戰場單位表（0x4A7B20，在 EngineMemory 裡，原版格式）、地形、佔位表，以及劇本對單位的操作。
 * [init] = 開戰初始化（原版 0x44D2CA，0x27 背景显示 進入戰場狀態時執行），從出場配置表建立單位。
 * 規則與函式位址見 docs/battle.md「戰場單位表」「開戰初始化」「劇本對單位的操作」。只做影響遊戲狀態的部分，畫面不做。
 *
 * 地形與佔位表在原版放在緩衝區 8（0x48B140，GlobalAlloc 配置），位址沒有意義：地形直接用 [map]（0x58 會改它），
 * 佔位表放在 [occupant]。
 */
class BattleField(
    val mem: EngineMemory,
    val map: HexzMap,
    /** Data.e5 第 0 項（敵軍重新載入用，原版開戰時重讀）。 */
    private val rawCharacters: ByteArray,
    /** 遇到還沒移植的原版路徑時記一筆（同 ScriptVM.gaps）。 */
    private val gap: (String) -> Unit,
) {
    /** 佔位表（緩衝區 8 + 0xC80）：每格上的單位格號，0xFF = 沒有。 */
    val occupant = ByteArray(0x640) { 0xFF.toByte() }
    /** 特效判定（0x4101D9）。 */
    val effects by lazy { Effects(this) }
    /** 移動力、移動花費、移動範圍（0x43F74F、0x43F7B8、0x438484）。 */
    val search by lazy { Search(this, gap) }
    /** 戰鬥能力值（0x43F5FF…）。 */
    val stats by lazy { CombatStats(this) }
    /** 戰鬥公式（攻擊、防禦、傷害）。 */
    val combat by lazy { Combat(this) }
    /** 記一筆還沒移植的原版路徑。 */
    fun gapNote(s: String) = gap(s)
    /** 攻擊範圍（0x43F8C0、0x436511）。 */
    val range by lazy { AttackRange(this) }
    /** 緩衝區 0x48B0E0：攻擊範圍的標記（+0 格號、+0xC80 只標記的旗標）。 */
    val scratch = ByteArray(0x1900)

    val width get() = mem.u8(MAP_WIDTH)
    val height get() = mem.u8(MAP_HEIGHT)

    fun init() {
        // 0x44D2CA 開頭：戰場物件 0x4B3D08 的勝敗 +1、回合 +2 = 0，回合上限 +3 = VM +0x3D0E，天氣 +4／+0x11 取 0x496E21，行動方 +0x10 = 0
        val b = BattleTests.BATTLE
        mem.setU8(b + 5, 0)
        mem.setU16(b + 1, 0)
        mem.setU32(b + 8, 0)
        mem.setU32(b + 0x0C, 0)
        mem.setU8(b + 0x10, 0)
        mem.setU32(b + 0x4FC, 0)
        mem.setU32(b + 0x500, 0)
        mem.setU8(b + 0x11, mem.u8(0x496E21L))
        mem.setU8(b + 4, mem.u8(0x496E22L))
        mem.setU8(b + 3, mem.u8(0x492F90L + 0x3D0E))
        // 0x435A8D：250 格全部重設
        for (slot in 0 until SLOTS) resetUnit(slot)
        // 0x44C1C2：載入地圖（寬、高寫 0x4B422C／D，地形抄進緩衝區 = [map]）
        mem.setU8(MAP_WIDTH, map.width)
        mem.setU8(MAP_HEIGHT, map.height)
        createUnits()
        // 每一格收尾（0x44D3EF 起的迴圈）
        for (slot in 0 until SLOTS) {
            val u = unit(slot)
            mem.setU8(u + 0x0D, mem.u8(u + 0x0D) and 0x80.inv())   // 0x406690(0x80)
            Units.clearHealth(mem, u, 0x1E)                                    // 0x423FB9(0x1E)
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
    fun place(slot: Int, x0: Int, y0: Int, flip: Boolean, hidden: Boolean) {
        if (hidden) return
        val u = unit(slot)
        var x = x0
        var y = y0
        if (mem.u8(u + 0x0C) != 2) {
            mem.setU8(u + 0x0C, 2)
            setFlip(u, flip)
            val p = findPosition(x, y, slot, false)
            x = p and 0xff
            y = (p shr 8) and 0xff
        } else {
            gap("0x442B03 已經在地圖上的單位再放一次（0x4193B0）")
        }
        mem.setU8(u + 6, x)
        mem.setU8(u + 7, y)
        setOccupant(x, y, slot)
        if (mem.u8(FOG) >= 2) gap("0x42A2AF 戰場迷霧")
    }

    /** 0x43FD34：+0x0D bit 0x08（單位圖左右翻轉，朝東時 1）。 */
    private fun setFlip(u: Long, on: Boolean) {
        mem.setU8(u + 0x0D, if (on) mem.u8(u + 0x0D) or 0x08 else mem.u8(u + 0x0D) and 0x08.inv())
    }

    /** 0x42DC72：佔位表 (x, y) = 格號（≥ 0xFA 或那格單位狀態 3 時寫 0xFF）。 */
    private fun setOccupant(x: Int, y: Int, slot: Int) {
        val v = if (slot >= 0xFA || mem.u8(unit(slot) + 0x0C) == 3) 0xFF else slot
        if (x < width && y < height) occupant[y * width + x] = v.toByte() else gap("0x42DC72 地圖外的座標")
    }

    /** 單位在佔位表上移動（引擎的玩家操作用；原版的移動函式 0x43AD3B 還沒移植，這裡只做佔位與座標）。 */
    fun moveUnit(slot: Int, x: Int, y: Int) {
        moveOnGrid(slot, x, y)
    }

    /**
     * 0x32 武将移动 在戰場上的暫代版（推測）：原版 0x4594EA 用移動執行 0x43AD3B 沿路走過去（還沒移植），
     * 這裡直接放到目的地，朝向 = 指定的方向（0–3），沒指定時取移動方向；左右翻轉跟著朝向（東 = 翻轉）。
     * 原版確定的部分：已經在目的地或目的地在地圖外就什麼都不做（回傳 false）；移動執行一開始先設成目前行動的單位（0x406240）。
     */
    fun scriptMove(slot: Int, x: Int, y: Int, dir: Int): Boolean {
        val u = unit(slot)
        val fx = mem.u8(u + 6)
        val fy = mem.u8(u + 7)
        if ((fx == x && fy == y) || x >= width || y >= height) return false
        setCurrent(slot)
        if (fx != x || fy != y) mem.setU8(u + 0x0F, Direction.between(fx, fy, x, y).ordinal)
        moveOnGrid(slot, x, y)
        if (dir in 0..3) mem.setU8(u + 0x0F, dir)
        setFlip(u, mem.u8(u + 0x0F) == 1)
        return true
    }

    /** 0x406240：設成目前行動的單位：+0x24 清 bit 0x80，[0x4B5DF0] = 武將，[0x500AC0] = 單位位址。 */
    fun setCurrent(slot: Int) {
        val u = unit(slot)
        mem.setU8(u + 0x24, mem.u8(u + 0x24) and 0x7F)
        mem.setU32(CURRENT_CHAR, mem.u32(u))
        mem.setU32(CURRENT_UNIT_PTR, u)
    }

    private fun moveOnGrid(slot: Int, x: Int, y: Int) {
        val u = unit(slot)
        setOccupant(mem.u8(u + 6), mem.u8(u + 7), 0xFF)
        mem.setU8(u + 6, x)
        mem.setU8(u + 7, y)
        setOccupant(x, y, slot)
    }

    // ---- 位置 ----

    /** 0x4359F5：往方向 d（0 北、1 東、2 南、3 西）的鄰格；超出地圖時 x = 0xFF。 */
    fun neighbor(p: Int, d: Int): Int {
        val x = ((p and 0xff) + mem.u8(DIRECTIONS + 2 * d)) and 0xff
        val y = (((p shr 8) and 0xff) + mem.u8(DIRECTIONS + 2 * d + 1)) and 0xff
        return if (x >= width || y >= height) 0xFF or (y shl 8) else x or (y shl 8)
    }

    /** 0x435884：格上的單位格號（< 0xFA），沒有或地圖外是 0xFF。 */
    fun occupantAt(p: Int): Int {
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
        // 0x403A6F：特效 54 穿越移動（[0x500AD3] = 1 時一律成立）
        if (mem.u8(0x500AD3L) == 1 || effects.value(unit(slot), 0x36, 0x5D, 1, 1) != 0L) return 1
        return 0xFF
    }

    /** 0x412557：地形（格上有單位時原版會看那個單位的特效 0xAB，放置時不會遇到）。 */
    private fun terrainAt(x: Int, y: Int): Int {
        if (occupant[y * width + x].toInt() and 0xff != 0xFF) gap("0x412557 有單位的格（特效 0xAB）")
        return map.terrain(x, y)
    }

    /** 0x4362E6：地圖矩形限制 [0x501BF0]（x, y, 寬, 高；0 = 不限制）。 */
    fun insideRect(p: Int): Boolean {
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
    private fun setAi(ch: Int, aiParam: Int, targetChar: Int, tx: Int, ty: Int, slot: Int) {
        val ai = aiParam and 0xff
        val u: Long
        if (slot != 0) {
            u = unit(slot)
            if (mem.u16(u) == 0xFFFF) return
        } else {
            val a = Units.findUnit(mem, ch) { Units.liveUnitOf(mem, it) }
            if (a < 0) return
            u = unit(a)
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

    // ---- 劇本對單位的操作 ----

    /** 0x418CE0：武將參數 0x400–0x402（目前行動的單位等），還沒移植。 */
    private fun currentUnitParam(ch: Int): Boolean {
        if (ch in 0x400..0x402) { gap("0x418CE0 武將 0x400–0x402（目前行動的單位）"); return true }
        return false
    }

    /**
     * 0x435539(a, b, 八方向?, 設定 a, 設定 b)：格 a 看向格 b 的方向。四方向 0 北 1 東 2 南 3 西；八方向時斜向是 4–7。
     * 縱向距離大於橫向取南北，否則取東西（相等時取東西）。a 或 b 是 0xFF、或 a == b 時回傳 a（或 b）目前的朝向。
     * 設定 a／b：a 朝向結果的四方向、b 朝向相反方向。
     */
    private fun direction(a: Int, b: Int, eight: Boolean, setA: Boolean, setB: Boolean): Int {
        if (a == b || b == 0xFF) return mem.u8(unit(a) + 0x0F)
        if (a == 0xFF) return mem.u8(unit(b) + 0x0F)
        val ax = mem.u8(unit(a) + 6).toByte().toInt()
        val ay = mem.u8(unit(a) + 7).toByte().toInt()
        val bx = mem.u8(unit(b) + 6).toByte().toInt()
        val by = mem.u8(unit(b) + 7).toByte().toInt()
        val r: Int
        val da: Int
        when {
            ay > by -> {
                val dy = (ay - by).toByte().toInt()
                when {
                    ax == bx -> { r = 0; da = 0 }
                    ax > bx -> if (dy > (ax - bx).toByte()) { r = if (eight) 7 else 0; da = 0 } else { r = if (eight) 7 else 3; da = 3 }
                    else -> if (dy > (bx - ax).toByte()) { r = if (eight) 4 else 0; da = 0 } else { r = if (eight) 4 else 1; da = 1 }
                }
            }
            ay == by -> if (ax > bx) { r = 3; da = 3 } else { r = 1; da = 1 }
            else -> {
                val dy = (by - ay).toByte().toInt()
                when {
                    ax == bx -> { r = 2; da = 2 }
                    ax > bx -> if (dy > (ax - bx).toByte()) { r = if (eight) 6 else 2; da = 2 } else { r = if (eight) 6 else 3; da = 3 }
                    else -> if (dy > (bx - ax).toByte()) { r = if (eight) 5 else 2; da = 2 } else { r = if (eight) 5 else 1; da = 1 }
                }
            }
        }
        if (setA) mem.setU8(unit(a) + 0x0F, da)
        if (setB) mem.setU8(unit(b) + 0x0F, (da + 2) % 4)
        return r
    }

    /** 0x435829(a, b)：a 轉向 b（b = 0xFF 時不轉），左右翻轉 = 結果是東。 */
    private fun face(a: Int, b: Int) {
        val r = direction(a, b, false, true, false)
        setFlip(unit(a), r == 1)
    }

    /**
     * 0x4F 战场转向设置（0x4575B8）：(武將, 目標武將, 方向, 背對, ?, ?)。單位要在地圖上（狀態 2）。
     * 目標武將 0xFFFF：轉向指定方向（相同時不動）；否則轉向目標（目標也要在地圖上），背對 > 0 時再轉成相反方向。
     */
    fun turn(ch: Int, target: Int, dir: Int, back: Int) {
        val a = Units.findUnit(mem, ch) { if (it < 0x400) Units.anyUnitOf(mem, it) else -1 }
        if (a < 0 || mem.u8(unit(a) + 0x0C) != 2) return
        val u = unit(a)
        val d0 = mem.u8(u + 0x0F)
        if (target and 0xffff == 0xFFFF) {
            if (d0 == (dir and 0xff)) return
            mem.setU8(u + 0x0F, dir and 0xff)
            face(a, 0xFF)
            return
        }
        val b = Units.findUnit(mem, target) { Units.anyUnitOf(mem, it) }
        if (b < 0 || mem.u8(unit(b) + 0x0C) != 2) return
        if (direction(a, b, false, false, false) == d0) return
        face(a, b)
        if ((back and 0xffff).toShort() > 0) {
            val d = mem.u8(u + 0x0F)
            if (d <= 3) {
                mem.setU8(u + 0x0F, when (d) { 0 -> 2; 2 -> 0; 1 -> 3; else -> 1 })
                if (d == 1) setFlip(u, false) else if (d == 3) setFlip(u, true)
            }
        }
    }

    /**
     * 0x4C 隐藏武将出现（0x414ADC）：(模式, 武將, 格)。模式 0：武將（0x40216A）；1：格號（狀態要是 1）。
     * 位置用配置表裡的座標（模式 0 找武將相同的配置，模式 1 用格號當配置索引），放置同開戰（0x409229 → 0x442B03）。
     */
    fun appear(mode: Int, ch: Int, slotParam: Int) {
        val m = if (mode == 0xFFFF) 0 else mode
        when (m) {
            0 -> {
                val c = ch.toShort().toInt()   // movsx
                val a = Units.findUnit(mem, c) { Units.liveUnitOf(mem, it) }
                if (a < 0 || mem.u8(unit(a) + 0x0C) == 2) return
                var rec = if (c >= 0x404 && mem.u16(Deployment.record(c - 0x404)) == mem.u16(unit(a))) Deployment.record(c - 0x404) else 0L
                if (rec == 0L) {
                    val i = (0 until SLOTS).firstOrNull { mem.u16(Deployment.record(it)) == (c and 0xffff) } ?: return
                    rec = Deployment.record(i)
                }
                appearAt(a, rec)
            }
            1 -> {
                if (slotParam !in 0 until SLOTS) { gap("0x4C 格號超出範圍"); return }
                if (mem.u8(unit(slotParam) + 0x0C) != 1) return
                appearAt(slotParam, Deployment.record(slotParam))
            }
        }
    }

    /** 0x409229：依配置記錄的 (x, y)、翻轉旗標放上地圖。 */
    private fun appearAt(slot: Int, rec: Long) {
        place(slot, mem.u8(rec + 4), mem.u8(rec + 5), mem.u8(rec + 8) and 8 != 0, false)
    }

    /**
     * 0x31 武将消失（0x413522，戰場上）：(模式, 武將, x0, y0, x1, y1, 陣營)。
     * 模式 0：單一武將（0x45A7A2）；其他：範圍內、陣營相符的單位（0x45A80F，範圍限制在地圖內）。
     */
    fun disappear(mode: Int, ch: Int, area: IntArray, sideFilter: Int) = removeUnits(mode, ch, area, sideFilter, true, 0)

    /**
     * 0x53 战场撤退（0x41569E）：(模式, 武將, x0, y0, x1, y1, 陣營, 旗標)。同 0x31，但算撤退：
     * 我軍標識 ≠ 0xFF 的武將撤退場數 +1（到 0xFF 為止），我軍撤退時有撤退台詞。
     */
    fun retreat(mode: Int, ch: Int, area: IntArray, sideFilter: Int, flag: Int) =
        removeUnits(mode, ch, area, sideFilter, false, if (flag and 0xffff == 0xFFFF) 0 else flag)

    private fun removeUnits(mode: Int, ch: Int, area: IntArray, sideFilter: Int, vanish: Boolean, flag: Int) {
        if (mode == 0) {
            if (currentUnitParam(ch)) return
            val a = Units.findUnit(mem, ch) { Units.liveUnitOf(mem, it) }
            if (a >= 0) remove(a, vanish, flag)
            return
        }
        // 0x45A80F
        val filter = if (sideFilter and 0xffff == 0xFFFF) 6 else sideFilter
        val x1 = minOf(area[2] and 0xff, width - 1)
        val y1 = minOf(area[3] and 0xff, height - 1)
        var y = area[1] and 0xff
        while (y <= y1) {
            var x = area[0] and 0xff
            while (x <= x1) {
                val o = occupantAt(x or (y shl 8))
                if (o != 0xFF && mem.u8(unit(o) + 0x0C) == 2 && matchSide(filter, sideCategory(o))) remove(o, vanish, flag)
                x++
            }
            y++
        }
    }

    /**
     * 0x45A7A2 → 0x441744(消失, 旗標)：從地圖上移除（狀態 3），以這個單位為目標的單位改回預設行動。
     * 0x45A7A2 在前後設、清 +0x0D bit 0x80，結果是清掉。
     */
    private fun remove(slot: Int, vanish: Boolean, flag: Int) {
        val u = unit(slot)
        if (mem.u8(u + 0x0C) == 2) removeFromMap(slot, vanish)
        mem.setU8(u + 0x0D, mem.u8(u + 0x0D) and 0x80.inv())
    }

    /**
     * 0x44EEE5 回合用完：格 0–59 裡第一個武將 0（曹操）的單位撤退（0x441744(0, 0)，不看狀態）；
     * 都沒有而且格 0 在地圖上時，格 0 撤退。
     */
    fun timeUp() {
        val s = (0 until 0x3C).firstOrNull { mem.u32(unit(it)) == 0L }
        when {
            s != null -> removeFromMap(s, false)
            mem.u8(unit(0) + 0x0C) == 2 -> removeFromMap(0, false)
        }
    }

    /** 0x441744 的狀態部分（動畫不做）。 */
    private fun removeFromMap(slot: Int, vanish: Boolean) {
        val u = unit(slot)
        val ch = mem.u32(u).toInt()
        // 撤退時我軍（+5 == 0）而且 +0x1F ≠ 0：撤退台詞（0x40C00F，Imsg 0x99A + 武將，武將 < 0x31）
        if (!vanish && mem.u8(u + 0x1F) != 0 && mem.u8(u + 5) == 0 && ch < 0x31) gap("0x40C00F 撤退台詞")
        setOccupant(mem.u8(u + 6), mem.u8(u + 7), 0xFF)          // 0x4193B0 → 0x40D84B
        if (mem.u8(FOG) >= 2) gap("0x42A2AF 戰場迷霧")
        if (!vanish && inArmy(ch)) {
            val c = Characters.addr(mem, ch)
            val n = mem.u8(c + 0x29)
            if (n != 0xFF) mem.setU8(c + 0x29, n + 1)            // 0x440015、0x443100：撤退場數
        }
        mem.setU8(u + 0x0C, 3)
        mem.setU8(u + 0x1E, 0)
        setFlip(u, false)
        for (t in 0 until SLOTS) {
            val tu = unit(t)
            if (mem.u8(tu + 8) == slot && mem.u8(tu + 0x0E).let { it == 0 || it == 4 }) {
                mem.setU8(tu + 8, 0xFF)
                setAction(tu, 1)
            }
        }
    }

    /**
     * 0x4E 武将方针变更（0x4146D4）：(模式, 武將, x0, y0, x1, y1, 陣營, AI, 目標武將, 目標x, 目標y)，x、y 只取低位 byte。
     * 模式 0（0xFFFF 也是）：單一武將（0x459D95）；其他：範圍內、+5 陣營相符的單位（0x459E79，範圍限制在地圖內）。
     */
    fun changeAi(p: IntArray) {
        val mode = if (p[0] == 0xFFFF) 0 else p[0]
        val ai = p[7]
        val target = p[8]
        val tx = p[9] and 0xff
        val ty = p[10] and 0xff
        if (mode == 0) { setAi(p[1], ai, target, tx, ty, 0); return }
        val filter = p[6] and 0xff
        val x1 = minOf(p[4] and 0xff, width - 1)
        val y1 = minOf(p[5] and 0xff, height - 1)
        var y = p[3] and 0xff
        while (y <= y1) {
            var x = p[2] and 0xff
            while (x <= x1) {
                val o = occupantAt(x or (y shl 8))
                if (o != 0xFF && mem.u8(unit(o) + 0x0C) == 2 && matchSide(filter, side(unit(o)))) {
                    setAi(mem.u32(unit(o)).toInt(), ai and 0xff, target, tx, ty, o)
                }
                x++
            }
            y++
        }
    }

    /**
     * 0x42E2ED：陣營分類（0 我軍、1 友軍、2 敵軍、3 …）。格 < 20 → 0、< 60 → 1；其他看 +5：2 → 2；3 → 3；
     * 5 → [0x501AD8 + 格] ≠ 0 ? 4 : 2；6 → [0x501AD8 + 格] ≠ 0 ? 5 : 3；其他照 +5（≥ 4 時減 1）。
     */
    private fun sideCategory(slot: Int): Int {
        val c = when {
            slot < 0x14 -> 0
            slot < 0x3C -> 1
            else -> {
                val s = mem.u8(unit(slot) + 5)
                val extra = mem.u8(0x505F54L) != 0
                when (s) {
                    2 -> 2
                    5 -> if (mem.u8(0x501AD8L + slot) != 0) 5 else 2
                    3 -> if (extra) 4 else 3
                    6 -> if (mem.u8(0x501AD8L + slot) != 0) 6 else if (extra) 4 else 3
                    else -> s
                }
            }
        }
        return if (c >= 4) c - 1 else c
    }

    /** 0x45AED1：陣營篩選 0–3 = 分類相同、4 = 0 或 1、5 = 2 或 3、6 = 全部。 */
    private fun matchSide(filter: Int, cat: Int): Boolean = when (filter) {
        in 0..3 -> cat == filter
        4 -> cat <= 1
        5 -> cat == 2 || cat == 3
        6 -> true
        else -> false
    }

    /**
     * 0x4D 武将状态变更（0x415551）：(模式, 武將, 格, x0, y0, x1, y1, 陣營, 能力項目, 狀態, 健康, HP, MP)。
     * 模式 0 武將、1 格（要在地圖上）、2 範圍。能力狀態 1 → 3、2 → 6；健康、HP、MP 是 0xFFFF／0 時不改。
     */
    fun changeStatus(p: IntArray) {
        val mode = p[0]
        var ch = p[1]
        if (mode == 0 && currentUnitParam(ch)) return
        if (mode == 1) {
            if (p[2] !in 0 until SLOTS || mem.u8(unit(p[2]) + 0x0C) != 2) return
            ch = mem.u32(unit(p[2])).toInt()
        }
        val side = if (mode == 2 && p[7] and 0xffff == 0xFFFF) 6 else p[7]
        fun apply(kind: Int, value: Int) {
            if (mode == 2) applyArea(p[3], p[4], p[5], p[6], side, kind, value) else unitStat(ch, kind, value, 0)
        }
        if (p[8] and 0xffff != 0xFFFF && p[9] and 0xffff != 0xFFFF) apply(p[8], when (p[9]) { 2 -> 6; 1 -> 3; else -> p[9] })
        if (p[10] and 0xffff != 0xFFFF) apply(6, p[10])
        if (p[11] != 0) apply(7, p[11])
        if (p[12] != 0) apply(8, p[12])
    }

    /** 0x45ADE7：範圍 (x0, y0)–(x1, y1)（不限制在地圖內）裡陣營相符的單位各做一次 0x45AA37。 */
    private fun applyArea(x0: Int, y0: Int, x1: Int, y1: Int, side: Int, kind: Int, value: Int) {
        var y = y0.toByte().toInt()
        while (y <= y1.toByte().toInt()) {
            var x = x0.toByte().toInt()
            while (x <= x1.toByte().toInt()) {
                val o = occupantAt((x and 0xff) or ((y and 0xff) shl 8))
                if (o != 0xFF && mem.u8(unit(o) + 0x0C) == 2 && matchSide(side, sideCategory(o))) {
                    unitStat(mem.u32(unit(o)).toInt(), kind, value, o)
                }
                x++
            }
            y++
        }
    }

    private fun unitStat(ch: Int, kind: Int, value: Int, slot: Int) {
        if (!Units.setStat(mem, ch, kind, value, slot)) gap("0x45AA37 項目 ${kind and 0xff}")
    }

    companion object {
        const val UNITS = 0x4A7B20L
        const val UNIT_SIZE = 0x30
        const val SLOTS = 250
        const val MAP_WIDTH = 0x4B422CL
        const val MAP_HEIGHT = 0x4B422DL
        /** 目前行動的單位：武將（0x4B5DF0）、單位位址（0x500AC0），0x406240 設定。 */
        const val CURRENT_CHAR = 0x4B5DF0L
        const val CURRENT_UNIT_PTR = 0x500AC0L
        /** 地圖矩形限制（0x4362E6）。 */
        const val RECT = 0x501BF0L
        /** 戰場迷霧模式（≥ 2 時放置單位會更新視野，0x42A297）。 */
        const val FOG = 0x501BD2L
        /** 方向 → (dx, dy)（0x435A3E）。 */
        private const val DIRECTIONS = 0x435A3EL
        /** AI 0–7 → 行動方式（0x48E379）。 */
        private const val AI_ACTIONS = 0x48E379L
    }
}
