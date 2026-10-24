package ccz.battle

import ccz.script.EngineMemory
import ccz.script.ScriptEvent
import java.nio.charset.Charset

/**
 * 戰場的回合流程（原版 0x44EEE5 回合迴圈 → 0x44ED7F 一回合 → 0x44E0D9 我軍操作、0x44E825 電腦方）。
 * 規則見 docs/script.md「戰場的回合流程與事件檢查」。[steps] 依序產生要做的事：
 * [Step.Events] 檢查戰場事件（0x44C601）、[Step.Show] 顯示事件、[Step.Player] 等玩家操作完。
 * 還沒移植的部分（回合開始的單位處理、天氣、電腦方行動）記在 [gap]。
 */
class TurnFlow(private val bf: BattleField, private val gap: (String) -> Unit) {
    sealed interface Step {
        data object Events : Step
        data class Show(val event: ScriptEvent) : Step
        data object Player : Step
    }

    private val mem: EngineMemory get() = bf.mem
    private val result get() = mem.u8(BATTLE + 1)
    private val turn get() = mem.u8(BATTLE + 2)

    val steps: Iterator<Step> = iterator { battleLoop() }

    /** 0x44EEE5：每回合先看勝敗、回合上限，再跑一回合，+2 加 1。 */
    private suspend fun SequenceScope<Step>.battleLoop() {
        while (result == 0) {
            if (turn >= mem.u8(BATTLE + 3)) {
                yield(Step.Show(ScriptEvent.Message(cstr(TIME_UP_TEXT), 2)))   // 0x42F699(2, 0x48E220)
                bf.timeUp()
                mem.setU8(BATTLE + 1, 3)
                break
            }
            mem.setU8(WEATHER_BUSY, 0)
            if (turn != 0) gap("0x449F91 天氣變化")
            oneTurn()
            if (result == 5) { gap("戰場物件 +1 = 5 的結束（0x4364BD…）"); break }
            mem.setU8(BATTLE + 2, turn + 1)
        }
        if (result != 5) gap("0x44EBE8 戰鬥結束處理（剩餘回合 × 4）")
        yield(Step.Show(ScriptEvent.BattleEnd(result)))
    }

    /** 0x44ED7F：回合提示、行動方 0、檢查事件（第一回合兩次）、我軍與友軍的回合開始處理、我軍操作、電腦方。 */
    private suspend fun SequenceScope<Step>.oneTurn() {
        yield(Step.Show(ScriptEvent.TurnStart(turn + 1, 0)))    // 0x44AC28(回合 + 1, 0, 0, 0)
        mem.setU8(BATTLE + 0x10, 0)
        yield(Step.Events)
        if (turn == 0) yield(Step.Events)
        if (result != 0) {
            mem.setU32(BATTLE + 0x504, 0)    // 直接進 0x44E825，馬上因為 +1 ≠ 0 結束
            return
        }
        if (mem.u8(PHASE_KIND) != 2) mem.setU8(PHASE_KIND, 0)
        phaseStart(0)
        bf.rng.shuffle(0x508998L)                                     // 0x423490
        if ((0 until 10).any { mem.u8(0x501AE4L + 3 * it) != 0xFF }) gap("0x40EC9D 計時的效果（0x501AE1）")
        mem.setU32(BATTLE + 0x520, 1)
        yield(Step.Player)
        mem.setU32(BATTLE + 0x520, 0)
        if (result == 0) computerPhase()
    }

    /**
     * 0x44E825：友軍（行動方 1，格 0–0x3B）、敵軍（行動方 2，格 0x3C–0xF9，有提示、回合開始處理）依序；各自先檢查事件再行動。
     * 行動順序：兩輪（[0x500C47] = 0、1）× 優先度 p = 0…0xF9（單位 +0x0B）：每個 p 從頭找第一個在場、優先度 = p、
     * 行動方式 ≠ 7、還沒行動（+0x0D 沒有 4、2）的單位行動（0x43AD3B），然後 p + 1（再行動時 p 不變）。行動後檢查事件。
     */
    private suspend fun SequenceScope<Step>.computerPhase() {
        mem.setU32(BATTLE + 0x504, 0)
        for (k in 0..1) {
            if (result != 0) break
            if (k == 1) {
                yield(Step.Show(ScriptEvent.TurnStart(turn + 1, 2)))    // 0x44AC28(回合 + 1, 1, 0, 2)
                mem.setU8(BATTLE + 0x10, 2)
                yield(Step.Events)
                if (result != 0) break
                if (mem.u8(PHASE_KIND) != 2) mem.setU8(PHASE_KIND, 1)
                phaseStart(1)
            } else {
                mem.setU8(BATTLE + 0x10, 1)
                yield(Step.Events)
                if (result != 0) break
            }
            gap("0x44DA7A 行動方開始的全體處理")
            val (from, to) = if (k == 0) 0 to 0x3C else 0x3C to 0xFA
            for (pass in 0..1) {
                mem.setU8(0x500C47L, pass)
                var p = 0
                while (p < 0xFA && result == 0) {
                    for (slot in from until to) {
                        val u = Units.unit(slot)
                        if (mem.u8(u + 0x0C) != 2 || mem.u8(u + 0x0B) != p || mem.u8(u + 0x0E) == 7) continue
                        if (mem.u8(u + 0x0D) and 4 != 0 || mem.u8(u + 0x0D) and 2 != 0) continue
                        if (mem.u8(0x4B4230L) and 4 == 0) {
                            bf.setCurrent(slot)
                            val fx = mem.u8(u + 6)
                            val fy = mem.u8(u + 7)
                            bf.ai.act(u)
                            mem.setU16(0x500E9AL, 0)
                            if (fx != mem.u8(u + 6) || fy != mem.u8(u + 7))
                                yield(Step.Show(ScriptEvent.Moved(mem.u16(u), fx, fy, mem.u8(u + 6), mem.u8(u + 7), bf.ai.walked.toList())))
                            if (mem.u8(u + 0x0D) and 2 != 0) gap("0x45B609 行動後的記錄")
                        }
                        gap("0x42BC14 行動後的狀態處理")
                        if (mem.u8(u + 0x24) and 0x80 != 0) {                                  // 0x41B508 再行動
                            mem.setU8(u + 0x24, mem.u8(u + 0x24) and 0x7F)
                            if (mem.u8(u + 0x0C) == 2) {
                                mem.setU8(u + 0x24, mem.u8(u + 0x24) + 1)
                                mem.setU8(u + 0x0D, mem.u8(u + 0x0D) and 6.inv())             // 0x412D9D
                                p--
                            }
                        } else gap("0x412D52 再行動特效")
                        if (result == 0 && mem.u8(u + 0x0D) and 2 != 0) yield(Step.Events)
                        break
                    }
                    if (mem.u32(BATTLE + 0x504) != 0L) {
                        gap("0x44DA7A 行動方開始的全體處理")
                        mem.setU32(BATTLE + 0x504, 0)
                        p = -1
                    }
                    p++
                }
            }
        }
    }

    /**
     * 0x44EE30：我方（0，格 0–59）或敵方（1，格 60–249）的回合開始處理。單位的處理（0x4263B0、0x43C940、0x44DFC7、
     * 0x43CDDD：狀態恢復、地形／道具回復等）還沒移植；另一方的 0x508A00 旗標清除（0x420952）照做。
     */
    private fun phaseStart(which: Int) {
        gap("0x44EE30 回合開始的單位處理")
        val (from, to) = if (which == 0) 0x3C to 0xFA else 0 to 0x3C
        for (i in from until to) mem.setU8(ACTED + i, 0)
    }

    private fun cstr(addr: Long): String {
        var n = 0
        while (mem.u8(addr + n) != 0) n++
        return String(mem.bytes(addr, n), Charset.forName("GBK"))
    }

    companion object {
        const val BATTLE = 0x4B3D08L
        /** 「因为战斗时间过长，撤退。」 */
        private const val TIME_UP_TEXT = 0x48E220L
        /** 0x44EEE5 每回合開始清 0（天氣變化前）。 */
        private const val WEATHER_BUSY = 0x4B4238L
        /** 0x44ED7F／0x44E825 寫 0（我方）、1（敵方），已經是 2 時不改。 */
        private const val PHASE_KIND = 0x505FB4L
        /** 每格一個 byte 的旗標（0x420952 清除）。 */
        private const val ACTED = 0x508A00L
    }
}
