package ccz.io

import java.io.File
import java.nio.charset.Charset

/** 遊戲目錄。Windows 版檔名大小寫不一致（例如 m000.JPG / m001.jpg），一律不分大小寫查找。 */
class GameFiles(val root: File) {
    private val index = HashMap<String, File>()

    init {
        root.walkTopDown().filter { it.isFile }.forEach {
            index[it.relativeTo(root).invariantSeparatorsPath.lowercase()] = it
        }
    }

    fun find(path: String): File? = index[path.replace('\\', '/').lowercase()]

    fun bytes(path: String): ByteArray = (find(path) ?: error("找不到遊戲檔：$path")).readBytes()

    fun exists(path: String): Boolean = find(path) != null

    fun list(dir: String): List<String> {
        val prefix = dir.trimEnd('/').lowercase() + "/"
        return index.keys.filter { it.startsWith(prefix) && '/' !in it.substring(prefix.length) }.sorted()
    }

    fun archive(path: String): Ls12 = Ls12(bytes(path))

    companion object {
        val GBK: Charset = Charset.forName("GBK")

        /** 讀 \0 結尾的 GBK 字串。 */
        fun cString(b: ByteArray, off: Int, max: Int): String {
            var end = off
            while (end < off + max && end < b.size && b[end] != 0.toByte()) end++
            return String(b, off, end - off, GBK)
        }
    }
}
