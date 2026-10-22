package ccz.android

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * 從玩家選的資料夾（Storage Access Framework）把遊戲需要的檔案複製到 App 的私有空間。
 * 先複製到暫存資料夾，全部成功才換成正式的 game/，中途失敗不會留下不完整的資料。
 */
class GameImporter(private val context: Context) {
    /** 進度：已複製 bytes、總 bytes、目前的檔名。 */
    fun interface Progress {
        fun update(done: Long, total: Long, name: String)
    }

    class ImportException(message: String) : IOException(message)

    fun import(treeUri: Uri, progress: Progress) {
        val picked = DocumentFile.fromTreeUri(context, treeUri) ?: throw ImportException("無法開啟選擇的資料夾")
        val root = findGameRoot(picked)
            ?: throw ImportException("這個資料夾裡找不到 Ekd5.exe。\n請選《新改曹操傳 6.6》的遊戲資料夾（有 Ekd5.exe 的那一層）。")

        val plan = ArrayList<Pair<DocumentFile, String>>()
        for (f in root.listFiles()) {
            val name = f.name ?: continue
            when {
                f.isFile && isRootFile(name) -> plan += f to name
                f.isDirectory && DIRS.any { it.equals(name, ignoreCase = true) } -> collect(f, name, plan)
            }
        }
        val missing = Game.REQUIRED.filter { req -> plan.none { it.second.equals(req, ignoreCase = true) } }
        if (missing.isNotEmpty()) throw ImportException("遊戲資料夾缺少：${missing.joinToString("、")}")

        val total = plan.sumOf { it.first.length() }
        val target = Game.dir(context)
        val temp = File(target.parentFile, "game_importing")
        temp.deleteRecursively()
        temp.mkdirs()
        val free = StatFs(temp.absolutePath).availableBytes
        if (free < total + SPARE) {
            temp.deleteRecursively()
            throw ImportException("手機空間不足：需要 %.1f GB，剩 %.1f GB".format(total / 1e9, free / 1e9))
        }

        var done = 0L
        val buf = ByteArray(1 shl 20)
        try {
            for ((doc, path) in plan) {
                progress.update(done, total, path)
                val out = File(temp, path)
                out.parentFile!!.mkdirs()
                context.contentResolver.openInputStream(doc.uri)!!.use { input ->
                    out.outputStream().use { output ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                        }
                    }
                }
            }
        } catch (e: Exception) {
            temp.deleteRecursively()
            throw e
        }
        target.deleteRecursively()
        if (!temp.renameTo(target)) throw ImportException("無法完成匯入（改名失敗）")
        progress.update(total, total, "")
        Game.reset()
    }

    /** 選到的資料夾本身有 Ekd5.exe 就用它；否則往下找一層（例如選到解壓縮出來的外層資料夾）。 */
    private fun findGameRoot(dir: DocumentFile): DocumentFile? {
        if (dir.findFileIgnoreCase(EXE) != null) return dir
        return dir.listFiles().filter { it.isDirectory }.firstOrNull { it.findFileIgnoreCase(EXE) != null }
    }

    private fun DocumentFile.findFileIgnoreCase(name: String) = listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun collect(dir: DocumentFile, path: String, out: MutableList<Pair<DocumentFile, String>>) {
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (f.isDirectory) collect(f, "$path/$name", out) else out += f to "$path/$name"
        }
    }

    private fun isRootFile(name: String) = name.equals(EXE, ignoreCase = true) || name.endsWith(".e5", ignoreCase = true)

    companion object {
        private const val EXE = "Ekd5.exe"

        /** 要複製的子資料夾。開場動畫（*.avi）和「修改器與修改記錄」不複製。 */
        private val DIRS = listOf("E5", "MAP", "RS", "WAV", "SoundTrk")

        /** 除了遊戲檔之外至少再留的空間。 */
        private const val SPARE = 200L shl 20
    }
}
