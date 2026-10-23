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

    fun bytesOrNull(path: String): ByteArray? = find(path)?.readBytes()

    fun list(dir: String): List<String> {
        val prefix = dir.trimEnd('/').lowercase() + "/"
        return index.keys.filter { it.startsWith(prefix) && '/' !in it.substring(prefix.length) }.sorted()
    }

    private val archives = HashMap<String, Ls12>()

    /** LS 封包，隨用隨讀（不會把整個檔讀進記憶體），同一個檔只開一次。 */
    fun archive(path: String): Ls12 = synchronized(archives) {
        archives.getOrPut(path.lowercase()) { Ls12(Ls12.FileSource(find(path) ?: error("找不到遊戲檔：$path"))) }
    }

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
