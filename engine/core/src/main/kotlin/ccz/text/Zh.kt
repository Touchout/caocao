package ccz.text

/**
 * 簡體 → 繁體（台灣用字），等同 OpenCC 的 s2twp 設定：
 *  1. CustomPhrases（本專案的修正）+ STPhrases + STCharacters 最長比對
 *  2. TWPhrases 最長比對
 *  3. TWVariantsPhrases + TWVariants 最長比對
 * 原文本來就是繁體的字不會被改動。字典取自 OpenCC（Apache 2.0），見 resources/ccz/opencc/NOTICE.txt。
 */
object Zh {
    private class Dict(val map: Map<String, String>) {
        val maxLen = map.keys.maxOf { it.length }

        fun convert(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                var hit = false
                for (len in minOf(maxLen, s.length - i) downTo 1) {
                    val v = map[s.substring(i, i + len)] ?: continue
                    sb.append(v)
                    i += len
                    hit = true
                    break
                }
                if (!hit) sb.append(s[i++])
            }
            return sb.toString()
        }
    }

    private fun load(vararg names: String): Dict {
        val map = HashMap<String, String>()
        for (name in names) {
            Zh::class.java.getResourceAsStream("/ccz/opencc/$name")!!.bufferedReader().forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val key = line.substring(0, tab)
                    // 同一個鍵在前面的字典已有時保留前者（詞組優先於單字）
                    map.putIfAbsent(key, line.substring(tab + 1).substringBefore(' '))
                }
            }
        }
        return Dict(map)
    }

    private val st by lazy { load("CustomPhrases.txt", "STPhrases.txt", "STCharacters.txt") }
    private val twPhrases by lazy { load("TWPhrases.txt") }
    private val twVariants by lazy { load("TWVariantsPhrases.txt", "TWVariants.txt") }

    fun toTraditional(s: String): String = twVariants.convert(twPhrases.convert(st.convert(s)))
}
