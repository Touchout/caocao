package ccz.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread

/** 關卡清單。還沒有遊戲檔時，請玩家選遊戲資料夾並匯入。 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Game.isInstalled(this)) loadStages() else showWelcome()
    }

    private fun showWelcome(error: String? = null) {
        val text = (if (error != null) "匯入失敗：$error\n\n" else "") +
            "請選擇《新改曹操傳 6.6》的遊戲資料夾（有 Ekd5.exe 的那一層），App 會把需要的檔案複製進來，約 1.1 GB。\n\n" +
            "可以先把整個遊戲資料夾放到手機的「下載」資料夾，再從這裡選它。"
        showScreen(text, "選擇遊戲資料夾") { pickFolder() }
    }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK)
    }

    @Deprecated("Activity 的舊 API，這個專案沒有用 AndroidX Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || uri == null) return
        startImport(uri)
    }

    private fun startImport(uri: android.net.Uri) {
        val label = label("準備匯入…")
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        setContentView(column(label, bar))
        thread {
            val result = runCatching {
                GameImporter(this).import(uri) { done, total, name ->
                    runOnUiThread {
                        bar.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                        label.text = "匯入中 %.0f / %.0f MB\n%s".format(done / 1e6, total / 1e6, name)
                    }
                }
            }
            runOnUiThread {
                result.onSuccess { loadStages() }.onFailure { showWelcome(it.message ?: it.toString()) }
            }
        }
    }

    private fun loadStages() {
        setContentView(label("載入中…"))
        thread {
            val result = runCatching {
                val (files, data) = Game.load(this)
                val stages = files.list("RS")
                    .mapNotNull { Regex("rs/s_(\\d+)\\.eex").matchEntire(it)?.groupValues?.get(1)?.toInt() }
                    .sorted()
                stages to stages.map { n ->
                    val name = data.stageNames.getOrNull(n)?.takeIf { it.isNotBlank() } ?: ""
                    "S_%03d　%s".format(n, Game.tw(name))
                }
            }
            runOnUiThread {
                result.onSuccess { (stages, labels) -> showStages(stages, labels) }
                    .onFailure { showWelcome("遊戲檔讀取失敗（${it.message}），請重新匯入") }
            }
        }
    }

    private fun showStages(stages: List<Int>, labels: List<String>) {
        val items = listOf("［重新匯入遊戲資料夾］") + labels
        val list = ListView(this).apply {
            setBackgroundColor(Color.BLACK)
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(if (position == 0) Color.rgb(140, 200, 255) else Color.WHITE)
                        textSize = 18f
                    }
            }
            setOnItemClickListener { _, _, pos, _ ->
                if (pos == 0) pickFolder()
                else startActivity(Intent(this@MainActivity, BattleActivity::class.java).putExtra(BattleActivity.EXTRA_STAGE, stages[pos - 1]))
            }
        }
        setContentView(list)
    }

    private fun showScreen(text: String, button: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            this.text = button
            textSize = 18f
            setOnClickListener { onClick() }
        }
        setContentView(column(label(text), b))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
    }

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(48, 48, 48, 48)
        setBackgroundColor(Color.BLACK)
        views.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 }) }
    }

    companion object {
        private const val REQUEST_PICK = 1
    }
}
