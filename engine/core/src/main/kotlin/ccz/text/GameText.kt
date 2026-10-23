package ccz.text

/** 遊戲文字的顯示前處理。 */
object GameText {
    /** 顏色標記：[C0D太平清領道]（C + 兩位十六進位顏色 + 文字 + ]）。 */
    private val COLOR = Regex("""\[C[0-9A-Fa-f]{2}([^\]]*)]""")

    /** 去掉顏色標記並轉成繁體。之後要上色時改成回傳分段。 */
    fun display(s: String): String = Zh.toTraditional(COLOR.replace(s) { it.groupValues[1] })
}
