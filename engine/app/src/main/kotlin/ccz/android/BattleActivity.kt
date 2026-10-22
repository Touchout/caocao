package ccz.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import ccz.battle.BattleSession
import ccz.data.ClassTables
import kotlin.concurrent.thread

/** 戰場：執行戰場劇本的開戰處理（對話、選擇），之後可以操作（點選、移動）。 */
class BattleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stage = intent.getIntExtra(EXTRA_STAGE, 0)
        setContentView(message("載入 S_%03d…".format(stage)))
        thread {
            val result = runCatching {
                val (files, data) = Game.load(this)
                files to BattleSession(files, data, ClassTables(files), stage)
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
    }
}
