package ccz.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import kotlin.concurrent.thread

/** 關卡清單。還沒放遊戲檔時顯示放置方式。 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Game.isInstalled(this)) {
            showMessage(
                "找不到遊戲檔。\n\n請把《新改曹操傳 6.6》的遊戲目錄內容整個複製到：\n\n" +
                    "${Game.dir(this).absolutePath}\n\n" +
                    "（Ekd5.exe、Data.e5、Imsg.e5、E5、MAP、RS… 都要放在這一層）\n\n" +
                    "需要的檔案：${Game.REQUIRED.joinToString("、")}"
            )
            return
        }
        showMessage("載入中…")
        thread {
            val (files, data) = Game.load(this)
            val stages = files.list("RS")
                .mapNotNull { Regex("rs/s_(\\d+)\\.eex").matchEntire(it)?.groupValues?.get(1)?.toInt() }
                .sorted()
            val labels = stages.map { n ->
                val name = data.stageNames.getOrNull(n)?.takeIf { it.isNotBlank() } ?: ""
                "S_%03d　%s".format(n, Game.tw(name))
            }
            runOnUiThread { showStages(stages, labels) }
        }
    }

    private fun showMessage(text: String) {
        setContentView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(48, 96, 48, 48)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
        })
    }

    private fun showStages(stages: List<Int>, labels: List<String>) {
        val list = ListView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, labels) {
                override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup) =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.WHITE)
                        textSize = 18f
                    }
            }
            setOnItemClickListener { _, _, pos, _ ->
                startActivity(Intent(this@MainActivity, BattleActivity::class.java).putExtra(BattleActivity.EXTRA_STAGE, stages[pos]))
            }
        }
        setContentView(list)
    }
}
