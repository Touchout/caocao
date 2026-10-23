package ccz.battle

import ccz.data.Characters
import ccz.script.EngineMemory
import ccz.script.Instr

/**
 * 0x46 友軍出场设定、0x47 敌军出场设定 寫的出場配置表（原版 0x41240B），以及敵軍等級（0x4266C4、0x4184DD、0x4123C8）。
 * 格式見 docs/battle.md「出場配置」「敵軍等級」。配置表之後才變成戰場單位。
 */
object Deployment {
    /** 劇本 VM 物件 0x492F90 + 0x2044：16 bytes × 格（0x418DD0）。 */
    const val TABLE = 0x492F90L + 0x2044
    const val RECORD = 16
    /** 0x46、0x47 各自執行過幾次（決定這次寫到哪一段格子）。 */
    const val ALLY_COUNT = 0x500AD4L
    const val ENEMY_COUNT = 0x500AD5L
    /** 我軍出戰名單：u16 武將 × 最多 20，0xFFFF 結尾。 */
    const val ROSTER = 0x48B350L
    const val ROSTER_MAX = 20
    /** 參考等級的修正（i8）。 */
    const val LEVEL_ADJUST = 0x501BF4L
    /** 0x4A 的強制出場名單（型別 38）與另一份名單（型別 39，出陣選擇時排除），各 u16 × 20；寫入位置各自循環。 */
    const val FORCED = 0x4B09DCL
    const val EXCLUDED = 0x4B0A04L
    const val FORCED_POS = 0x4B0A2CL
    const val EXCLUDED_POS = 0x4B0A30L
    /** 出陣人數上限（byte）。 */
    const val SORTIE_MAX = 0x4B09DBL
    /** 整數變數 985（u16）≠ 0xFFFF 時，0x4B 改寫到 0x507680 + 3·格。 */
    const val ALT_SWITCH = 0x505F64L
    const val ALT_TABLE = 0x507680L

    fun record(slot: Int) = TABLE + RECORD.toLong() * slot

    private fun inArmy(mem: EngineMemory, c: Int) = mem.u8(Characters.addr(mem, c) + 0x2A) != 0xFF // 0x42AC58

    /**
     * 0x44 战斗初始化（0x4121E2）：計數歸零、旗標 0–255 清除、各種戰場狀態重設，
     * 配置表格 0–19 = 出戰名單（到 0xFFFF 為止），其餘 0xFFFF；每格 +8 = 0。
     */
    fun battleInit(mem: EngineMemory) {
        mem.setU16(ALLY_COUNT, 0)
        mem.setU8(Characters.addr(mem, 0x3FF) + 0x19, 0)
        for (i in 0 until 0x100) { mem.setU16(0x508A00L + 2 * i, 0); mem.setFlag(i, false) }
        // 0x426C3B
        for (i in 0 until 0x50) mem.setU8(0x500BA0L + i, 0xFF)
        for (i in 0 until 0x28) mem.setU8(0x500B78L + i, 0)
        for (k in 0 until 0x80) {
            val o = 0x49CF68L + 0x40 * k
            mem.setU16(o + 0x28, 0); mem.setU16(o + 0x2E, 0); mem.setU32(o + 0x10, 0)
        }
        mem.setU16(0x49CEE4L, 0xFFFF)
        var ended = false
        for (slot in 0 until 250) {
            var v = 0xFFFF
            if (slot < 20 && !ended) {
                v = mem.u16(ROSTER + 2L * slot)
                if (v == 0xFFFF) ended = true
            }
            mem.setU32(record(slot), v.toLong())
            mem.setU32(record(slot) + 8, 0)
        }
        val fill = mem.u8(0x505EC4L)
        for (i in 0 until 1000) mem.setU8(0x4930C8L + i, fill)
        for (i in 0 until 0x640) mem.setU8(0x4934B0L + i, 0)
        resetSortieLists(mem)
    }

    /** 0x41C96D：兩份名單全部 0xFFFF，寫入位置歸零。 */
    fun resetSortieLists(mem: EngineMemory) {
        for (i in 0 until 22) mem.setU32(FORCED + 4L * i, if (i < 20) 0xFFFFFFFFL else 0)
    }

    /**
     * 0x4A 我军出场强制设定（0x415BD0）：讀 i32（不用）、5 個型別 38 加進強制名單、5 個型別 39 加進排除名單，
     * 上限設 1，然後出陣（0x418597）。上限是 1，所以這裡永遠不會開選擇畫面。
     */
    fun forceSortie(mem: EngineMemory, ins: Instr, sortie: List<Int>) {
        val forced = ins.params.filter { it.type == 0x38 }.map { it.value }
        val excluded = ins.params.filter { it.type == 0x39 }.map { it.value }
        fun append(list: Long, pos: Long, values: List<Int>) {
            val start = mem.u32(pos).toInt()
            values.forEachIndexed { i, v -> mem.setU16(list + 2L * (start + i), v.toLong() and 0xffff) }
            val next = start + values.size
            mem.setU32(pos, if (next == 20) 0 else next.toLong())
        }
        append(FORCED, FORCED_POS, forced)
        append(EXCLUDED, EXCLUDED_POS, excluded)
        mem.setU8(SORTIE_MAX, 1)
        sortieScreen(mem, sortie)
    }

    /**
     * 出陣（0x418597，參數 0）：排除名單 20 格都有值時先放曹操（0 號）；強制名單裡已經是我軍的依序放入。
     * 上限 = max(上限, 人數 + 1)；剛好等於時不開畫面，否則開選擇畫面（玩家輸入 [sortie]）。結果寫進出戰名單。
     */
    fun sortieScreen(mem: EngineMemory, sortie: List<Int>) {
        val list = ArrayList<Int>()
        if ((0 until 20).takeWhile { mem.u16(EXCLUDED + 2L * it) != 0 }.size == 20) list += 0
        for (i in 0 until 20) {
            val c = mem.u16(FORCED + 2L * i)
            if (c != 0 && c != 0xFFFF && inArmy(mem, c)) list += c
        }
        val want = list.size + 1
        if (mem.u8(SORTIE_MAX) < want) mem.setU8(SORTIE_MAX, want and 0xff)
        val chosen = if (mem.u8(SORTIE_MAX) == want) list else sortie
        chosen.forEachIndexed { i, c -> mem.setU16(ROSTER + 2L * i, c.toLong()) }
        mem.setU16(ROSTER + 2L * chosen.size, 0xFFFF)
        if (mem.u32(EXCLUDED_POS) == 0L) resetSortieLists(mem)
    }

    /** 0x4B 我军出场设定（0x415E33）：(格, x, y, 方向, 隱藏)。 */
    fun placePlayer(mem: EngineMemory, slot: Int, x: Int, y: Int, direction: Int, hidden0: Int) {
        val hidden = if (hidden0 == 0xFFFF) 0 else hidden0
        val rec = record(slot)
        if (mem.u16(ALT_SWITCH) != 0xFFFF) {
            mem.setU32(ALT_TABLE + 3L * slot, ((x and 0xff) or ((y and 0xff) shl 8) or ((hidden and 0xff) shl 16) or (0xFF shl 24)).toLong() and 0xffffffffL)
            return
        }
        if (mem.u32(rec) == 0xFFFFL || mem.u32(rec + 8) and 3 != 0L) return
        mem.setU8(rec + 4, x and 0xff)
        mem.setU8(rec + 5, y and 0xff)
        mem.setU32(rec + 8, (directionBits(direction) or (if (hidden != 0) 4 else 0)).toLong())
    }

    /** 0x48 敌方装备设定（0x411CDB）：(武將, 道具1, 等級1, 道具2, 等級2, 道具3)，0xFFFF 當 0；從格 20 起找這個武將。 */
    fun equip(mem: EngineMemory, character: Int, items: List<Int>, levels: List<Int>) {
        val slot = (20 until 250).firstOrNull { mem.u16(record(it)) == character } ?: return
        val rec = record(slot)
        val it0 = items.map { if (it == 0xFFFF) 0 else it }
        val lv = levels.map { if (it == 0xFFFF) 0 else it }
        mem.setU8(rec + 9, it0[0] and 0xff); mem.setU8(rec + 10, it0[1] and 0xff); mem.setU8(rec + 11, it0[2] and 0xff)
        mem.setU8(rec + 6, lv[0] and 0xff); mem.setU8(rec + 7, lv[1] and 0xff)
    }

    fun roster(mem: EngineMemory): List<Int> =
        (0 until ROSTER_MAX + 1).map { mem.u16(ROSTER + 2L * it) }.takeWhile { it != 0xFFFF }

    fun setRoster(mem: EngineMemory, characters: List<Int>) {
        characters.take(ROSTER_MAX).forEachIndexed { i, c -> mem.setU16(ROSTER + 2L * i, c.toLong()) }
        mem.setU16(ROSTER + 2L * minOf(characters.size, ROSTER_MAX), 0xFFFF)
    }

    /**
     * 參考等級（0x4266C4）：名單等級由大到小前 min(人數, 4) 個的平均 + i8 [LEVEL_ADJUST]，≤ 0 時 1。
     * 名單是空的時原版會除以零；這裡回傳 null。
     */
    fun referenceLevel(mem: EngineMemory): Int? {
        val levels = roster(mem).map { mem.u8(Characters.addr(mem, it) + 0x2C) }.sortedDescending()
        if (levels.isEmpty()) return null
        val top = levels.take(4)
        val v = (top.sum() / top.size) + mem.u8(LEVEL_ADJUST).toByte()
        return if (v <= 0) 1 else v
    }

    /** 0x4123C8：等級參數 p ≤ 100 → min(參考 + p, 100 + k)；p > 100 → 參考 − (p − 100)（≤ 0 時 1）。都是 byte 運算。 */
    fun level(p: Int, reference: Int, k: Int): Int {
        if (p <= 100) return minOf((p and 0xff) + (reference and 0xff), (100 + k) and 0xff)
        val d = ((p - 100) and 0xff).toByte().toInt()
        val r = (reference and 0xff).toByte().toInt()
        val v = if (d < r) ((reference and 0xff) - ((p - 100) and 0xff)) and 0xff else 0
        return if (v == 0) 1 else v
    }

    /** 方向 → 配置表旗標位元（0x449B29）。 */
    private fun directionBits(d: Int) = when (d and 0xff) { 0 -> 0x20; 1 -> 0x08; 2 -> 0x40; 3 -> 0x10; else -> 0x40 }

    /**
     * 執行 0x46（enemy = false，20 筆）或 0x47（enemy = true，80 筆）。rawCharacters = Data.e5 第 0 項（原版每次重讀）。
     * 回傳 false 表示參考等級算不出來（名單空的，原版會除以零）。
     */
    fun place(mem: EngineMemory, ins: Instr, enemy: Boolean, rawCharacters: ByteArray): Boolean {
        val reference = referenceLevel(mem)
        val counter = if (enemy) ENEMY_COUNT else ALLY_COUNT
        val (first, end, stride) = if (enemy) Triple(60, 140, 12) else Triple(20, 40, 11)
        val base = mem.u8(counter) * (if (enemy) 80 else 20)
        val records = ins.params.chunked(stride)
        for (i in first until end) {
            val r = records.getOrNull(i - first)?.takeIf { it.size == stride } ?: break
            val v = r.map { it.value }
            // 原版的陣列：0 武將、1 隱藏、2 x、3 y、4 方向、5 等級、6 型別45、7 AI、8 目標武將、9 目標x、10 目標y、11 敵軍多的那個
            val a = if (enemy) listOf(v[0]) + v.subList(2, 12) + v[1] else v
            if (a[0] == 0xFFFF) continue
            val hidden = if (a[1] == 0xFFFF) 0 else a[1]
            val extra = if (enemy && a[11] == 0xFFFF) 0 else if (enemy) a[11] else 0
            val ai = if (a[7] == 0xFFFF) 1 else a[7]
            val lv = level(if (a[5] == 0xFFFF) 0 else a[5], reference ?: 1, 10)
            val t45 = if (a[6] == 0xFFFF) 2 else a[6]
            val rec = record(i + base)
            mem.setU32(rec, a[0].toLong())
            mem.setU8(rec + 4, a[2] and 0xff)
            mem.setU8(rec + 5, a[3] and 0xff)
            var flags = (if (hidden != 0) 4 else 0) or (if (enemy) 1 or (if (extra != 0) 2 else 0) else 2)
            flags = flags or directionBits(a[4])
            mem.setU8(rec + 8, flags)
            // 0x4264C6：OR 上原始武將資料 +0x1D..0x1F（只有 +0x1D、+0x1E 的 0xFF 會先變 0），+6..7 = 0xFEFE
            for (k in 0 until 3) {
                val b = rawCharacters[a[0] * 32 + 0x1D + k].toInt() and 0xff
                mem.setU8(rec + 9 + k, mem.u8(rec + 9 + k) or (if (b == 0xFF && k < 2) 0 else b))
            }
            mem.setU16(rec + 6, 0xFEFE)
            mem.setU8(rec + 2, t45 and 0xff)
            mem.setU8(rec + 3, lv)
            mem.setU8(rec + 0x0C, ai and 0xff)
            if (ai and 0xff in 3..6) {
                if (ai == 4 || ai == 6) { mem.setU8(rec + 0x0E, a[9] and 0xff); mem.setU8(rec + 0x0F, a[10] and 0xff) }
                else mem.setU16(rec + 0x0E, (a[8] and 0xffff).toLong())
            }
        }
        mem.setU8(counter, (mem.u8(counter) + 1) and 0xff)
        return reference != null
    }
}
