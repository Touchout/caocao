package ccz.android

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import ccz.battle.BattleSession
import ccz.battle.BattleSetup
import ccz.data.ClassTables
import ccz.script.Eex
import kotlin.concurrent.thread

/**
 * 戰場：先選出陣武將（暫時的出陣畫面，測試用），再執行戰場劇本的開戰處理（對話、選擇），之後可以操作（點選、移動）。
 */
class BattleActivity : Activity() {
    private val stage get() = intent.getIntExtra(EXTRA_STAGE, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(message("載入 S_%03d…".format(stage)))
        thread {
            val result = runCatching {
                val (files, data) = Game.load(this)
                val setup = BattleSetup.from(Eex.parse(files.bytes("RS/S_%03d.eex".format(stage))))
                val slots = setup.playerSlots.map { it.index }.distinct().size.coerceAtLeast(1)
                val candidates = (0 until CANDIDATES).filter { data.characters[it].name.isNotBlank() }
                val forced = setup.forcedPlayers.filter { it in candidates }
                Triple(slots, candidates.map { it to Game.tw(data.characters[it].name) }, forced)
            }
            runOnUiThread {
                result.onSuccess { (slots, candidates, forced) -> pickSortie(slots, candidates, forced) }
                    .onFailure { e -> setContentView(message("無法載入 S_%03d：\n".format(stage) + e.message)) }
            }
        }
    }

    /** 暫時的出陣畫面：從曹操軍武將（編號 0–101）勾選，最多 [slots] 人；強制出場的、曹操預設勾選。 */
    private fun pickSortie(slots: Int, candidates: List<Pair<Int, String>>, forced: List<Int>) {
        val checked = BooleanArray(candidates.size) { i -> candidates[i].first == 0 || candidates[i].first in forced }
        AlertDialog.Builder(this)
            .setTitle("出陣（最多 $slots 人，測試用）")
            .setMultiChoiceItems(candidates.map { it.second }.toTypedArray(), checked) { dialog, which, isChecked ->
                if (isChecked && checked.count { it } > slots) {
                    checked[which] = false
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(this, "最多 $slots 人", Toast.LENGTH_SHORT).show()
                } else {
                    checked[which] = isChecked
                }
            }
            .setPositiveButton("出陣") { _, _ ->
                val sortie = candidates.indices.filter { checked[it] }.map { candidates[it].first }
                start(sortie.ifEmpty { listOf(0) })
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun start(sortie: List<Int>) {
        setContentView(message("載入 S_%03d…".format(stage)))
        thread {
            val result = runCatching {
                val (files, data) = Game.load(this)
                files to BattleSession(files, data, ClassTables(files), stage, sortie = sortie, testArmy = true)
            }
            // View（GestureDetector）要在主執行緒建立
            runOnUiThread {
                result.onSuccess { (files, session) -> setContentView(BattleView(this, session, files)) }
                    .onFailure { e -> setContentView(message("無法載入 S_%03d：\n".format(stage) + e.message)) }
            }
        }
    }

    private fun message(s: String) = TextView(this).apply {
        text = s
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.BLACK)
    }

    companion object {
        const val EXTRA_STAGE = "stage"
        /** 能力修正紀錄只記 0x66 號以前的武將（原版 0x408B52），曹操軍的武將都在這個範圍。 */
        private const val CANDIDATES = 0x66
    }
}
