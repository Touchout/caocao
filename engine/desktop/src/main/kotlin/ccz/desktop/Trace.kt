package ccz.desktop

import ccz.battle.BattleSession
import ccz.battle.Deployment
import ccz.data.Characters
import ccz.data.Stats
import ccz.script.Attributes
import ccz.data.ClassTables
import ccz.data.GameData
import ccz.io.GameFiles
import ccz.script.EngineMemory
import ccz.script.ScriptEvent
import java.io.File

/**
 * 引擎端的劇本執行軌跡（docs/methodology.md 方法 2），格式同 tools/re/trace_original.py：
 * 每條指令一行 JSON：pos、op、ret（條件結果 1/0）、display（彈出的文字）、guide（歷史情報）、writes（變數／旗標變化）。
 * 選擇題一律答 1。
 * 用法：./gradlew :desktop:trace --args="<遊戲目錄> <關卡編號> <輸出.jsonl> [出陣武將，逗號分隔，預設 0]"
 *       關卡編號寫 all 時跑全部戰場劇本，輸出到 <輸出目錄>/engine_NNN.jsonl（給 tools/re/sweep_compare.py）。
 */
fun main(args: Array<String>) {
    val files = GameFiles(File(args[0]))
    val sortie = args.getOrNull(3)?.split(",")?.map { it.trim().toInt() } ?: listOf(0)
    val data = GameData(files)
    val tables = ClassTables(files)
    if (args[1] == "all") {
        File(args[2]).mkdirs()
        for (name in files.list("RS").filter { Regex("rs/s_\\d{3}\\.eex").matches(it) }) {
            val stage = name.substring(5, 8).toInt()
            try {
                traceStage(files, data, tables, stage, File(args[2], "engine_%03d.jsonl".format(stage)), sortie)
            } catch (e: Exception) {
                System.err.println("S_%03d 失敗：$e".format(stage))
            }
        }
    } else {
        traceStage(files, data, tables, args[1].toInt(), File(args[2]), sortie)
    }
}

private fun traceStage(files: GameFiles, data: GameData, tables: ClassTables, stage: Int, file: File, sortie: List<Int>) {
    val session = BattleSession(files, data, tables, stage, sortie = sortie)
    val mem = session.mem
    val out = StringBuilder()
    var before = Snapshot(mem)
    session.vm.trace = { r ->
        val after = Snapshot(mem)
        val fields = ArrayList<String>()
        fields += "\"pos\": ${r.pos}"
        fields += "\"op\": ${r.op}"
        r.test?.let { fields += "\"ret\": ${if (it) 1 else 0}" }
        val texts = r.events.mapNotNull {
            when (it) {
                is ScriptEvent.Dialogue -> it.text
                is ScriptEvent.Message -> it.text
                else -> null
            }
        }
        if (texts.isNotEmpty()) fields += "\"display\": [" + texts.joinToString(", ") { json(it) } + "]"
        r.guide.firstOrNull()?.let { fields += "\"guide\": {\"mode\": ${it.mode}, \"text\": ${json(it.text)}}" }
        val writes = before.diff(after)
        if (writes.isNotEmpty()) fields += "\"writes\": [" + writes.joinToString(", ") + "]"
        out.append("{").append(fields.joinToString(", ")).append("}\n")
        before = after
    }
    var n = 0
    while (true) {
        val e = session.next() ?: break
        if (e is ScriptEvent.Choice || e is ScriptEvent.YesNo) session.answer(1)
        if (++n > 200000) break
    }
    file.writeText(out.toString())
    System.err.println("${out.count { it == '\n' }} 條指令 → $file")
    if (session.vm.gaps.isNotEmpty()) System.err.println("  還沒移植：" + session.vm.gaps.entries.joinToString { "${it.key} ×${it.value}" })
}

/** 逐 byte 比對的記憶體區域，同 tools/re/trace_original.py 的 MEM_REGIONS。 */
private val MEM_REGIONS = listOf(
    Deployment.TABLE to 250 * 16,
    Deployment.ALLY_COUNT to 2,
    Deployment.ROSTER to 42,
    Characters.ARRAY to Characters.COUNT * Characters.SIZE,
    Attributes.UNITS to Attributes.UNIT_SIZE * Attributes.SLOTS,
    Stats.BONUS_LOG to 0x66 * 14,
    Attributes.FACES to 0x1000,
)

private class Snapshot(mem: EngineMemory) {
    val ints = LongArray(0x1000) { mem.u32(EngineMemory.INT_VARS + 4L * it) }
    val ptrs = LongArray(0x400) { mem.u32(EngineMemory.PTR_VARS + 4L * it) }
    val flags = IntArray(0x2000) { mem.u8(EngineMemory.FLAGS + it) }
    val regions = MEM_REGIONS.map { (a, n) -> mem.bytes(a, n) }

    fun diff(after: Snapshot): List<String> {
        val out = ArrayList<String>()
        MEM_REGIONS.forEachIndexed { r, (base, n) ->
            for (i in 0 until n) if (regions[r][i] != after.regions[r][i]) out += "{\"mem\": ${base + i}, \"value\": ${after.regions[r][i].toInt() and 0xff}}"
        }
        for (i in ints.indices) if (ints[i] != after.ints[i]) out += "{\"var\": $i, \"value\": ${after.ints[i]}}"
        for (i in ptrs.indices) if (ptrs[i] != after.ptrs[i]) out += "{\"ptr\": $i, \"value\": ${after.ptrs[i]}}"
        for (i in flags.indices) {
            val x = flags[i] xor after.flags[i]
            for (bit in 0 until 8) if (x and (0x80 shr bit) != 0) {
                out += "{\"flag\": ${i * 8 + bit}, \"value\": ${(after.flags[i] shr (7 - bit)) and 1}}"
            }
        }
        return out
    }
}

private fun json(s: String) = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}
