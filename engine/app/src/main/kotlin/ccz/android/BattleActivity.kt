package ccz.android

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import ccz.battle.Battle
import ccz.battle.BattleSetup
import ccz.data.ClassTables
import ccz.data.HexzMap
import ccz.script.Eex
import kotlin.concurrent.thread

/** 戰場：開戰配置、點選查看、我軍移動。 */
class BattleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stage = intent.getIntExtra(EXTRA_STAGE, 0)
        setContentView(TextView(this).apply {
            text = "載入 S_%03d…".format(stage)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
        })
        thread {
            val (files, data) = Game.load(this)
            val result = runCatching {
                val setup = BattleSetup.from(Eex.parse(files.bytes("RS/S_%03d.eex".format(stage))))
                require(setup.mapId >= 0) { "劇本裡找不到戰場地圖（0x27 背景显示）" }
                val map = HexzMap.load(files, setup.mapId)
                val image = BitmapFactory.decodeFile(files.find(HexzMap.imagePath(setup.mapId))!!.absolutePath)
                Battle(data, ClassTables(files), map, setup) to image
            }
            // View（GestureDetector）要在主執行緒建立
            runOnUiThread {
                result.onSuccess { (battle, image) ->
                    setContentView(BattleView(this, stage, battle, files, image))
                }.onFailure { e ->
                    setContentView(TextView(this).apply {
                        text = "無法載入 S_%03d：\n".format(stage) + e.message
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.BLACK)
                    })
                }
            }
        }
    }

    companion object {
        const val EXTRA_STAGE = "stage"
    }
}
