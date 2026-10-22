package ccz.android

import android.content.Context
import ccz.data.GameData
import ccz.io.GameFiles
import ccz.text.Zh
import java.io.File

/**
 * 遊戲資料。App 不附任何原版素材：玩家在 App 裡選自己的遊戲資料夾，由 GameImporter 複製到
 * Android/data/ccz.android/files/game/（也可以手動複製到這裡）。
 */
object Game {
    @Volatile private var loaded: Pair<GameFiles, GameData>? = null

    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "game")

    fun isInstalled(context: Context): Boolean =
        REQUIRED.all { name -> dir(context).listFiles()?.any { it.name.equals(name, ignoreCase = true) } == true }

    /** 第一次呼叫會建索引、讀 Data.e5 和 exe 裡的表，請在背景執行緒呼叫。 */
    fun load(context: Context): Pair<GameFiles, GameData> = loaded ?: synchronized(this) {
        loaded ?: GameFiles(dir(context)).let { f -> (f to GameData(f)).also { loaded = it } }
    }

    /** 重新匯入後要清掉快取。 */
    fun reset() = synchronized(this) { loaded = null }

    val files get() = loaded!!.first
    val data get() = loaded!!.second

    /** 顯示用文字一律轉成繁體（台灣用字）。 */
    fun tw(s: String): String = Zh.toTraditional(s)

    val REQUIRED = listOf("Ekd5.exe", "Data.e5", "Imsg.e5", "Hexzmap.e5", "Unit_mov.e5")
}
