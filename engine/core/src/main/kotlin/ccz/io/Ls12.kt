package ccz.io

import java.io.File
import java.io.RandomAccessFile

/**
 * 光榮 LS11/LS12 封包（*.e5）。格式見 docs/formats.md。
 *
 * 目錄是 big-endian 的 (壓縮後大小, 原始大小, 偏移)，一直到第一筆資料的偏移為止。
 * 壓縮後大小 == 原始大小時資料未壓縮。
 */
class Ls12(private val source: Source) {
    /** 封包內容的來源：整個讀進記憶體，或用 RandomAccessFile 隨用隨讀（大檔用後者）。 */
    interface Source {
        val length: Long
        fun read(offset: Long, size: Int): ByteArray
    }

    class BytesSource(private val buf: ByteArray) : Source {
        override val length get() = buf.size.toLong()
        override fun read(offset: Long, size: Int) = buf.copyOfRange(offset.toInt(), offset.toInt() + size)
    }

    class FileSource(file: File) : Source {
        private val raf = RandomAccessFile(file, "r")
        override val length get() = raf.length()
        override fun read(offset: Long, size: Int): ByteArray = synchronized(raf) {
            ByteArray(size).also { raf.seek(offset); raf.readFully(it) }
        }
    }

    constructor(buf: ByteArray) : this(BytesSource(buf))

    data class Entry(val stored: Int, val size: Int, val offset: Int)

    val entries: List<Entry>
    private val dictionary: ByteArray

    init {
        val head = source.read(0, 272)
        require(isLs(head)) { "不是 LS 封包" }
        dictionary = head.copyOfRange(16, 272)
        val list = ArrayList<Entry>()
        var p = 272L
        var end = source.length
        while (p + 12 <= end) {
            val e = source.read(p, 12)
            val stored = be32(e, 0)
            val size = be32(e, 4)
            val off = be32(e, 8)
            p += 12
            if (stored == 0 && size == 0 && off == 0) break
            list += Entry(stored, size, off)
            end = minOf(end, off.toLong())
        }
        entries = list
    }

    val size: Int get() = entries.size

    operator fun get(index: Int): ByteArray {
        val e = entries[index]
        val chunk = source.read(e.offset.toLong(), e.stored)
        return if (e.stored == e.size) chunk else decompress(chunk, e.size, dictionary)
    }

    companion object {
        private val MAGICS = listOf("Ls12", "LS12", "LS11", "Ls11").map { it.toByteArray(Charsets.US_ASCII) }

        fun isLs(buf: ByteArray): Boolean =
            buf.size >= 272 && MAGICS.any { m -> (0 until 4).all { buf[it] == m[it] } }

        private fun be32(b: ByteArray, p: Int): Int =
            ((b[p].toInt() and 0xff) shl 24) or ((b[p + 1].toInt() and 0xff) shl 16) or
                ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)

        /** LZ bitstream（MSB first）：code < 256 查字典，否則是 (往回距離 = code − 256, 長度 = 下一個 code + 3)。 */
        fun decompress(data: ByteArray, size: Int, dictionary: ByteArray): ByteArray {
            val out = ByteArray(size)
            var n = 0
            var pos = 0
            var bit = 0
            fun getBit(): Int {
                val b = (data[pos].toInt() shr (7 - bit)) and 1
                if (++bit == 8) { bit = 0; pos++ }
                return b
            }
            fun getBits(k: Int): Int {
                var v = 0
                repeat(k) { v = (v shl 1) or getBit() }
                return v
            }
            fun getCode(): Int {
                var k = 0
                do { k++ } while (getBit() == 1)
                return (1 shl k) - 2 + getBits(k)
            }
            while (n < size) {
                val code = getCode()
                if (code < 256) {
                    out[n++] = dictionary[code]
                } else {
                    val start = n - (code - 256)
                    val len = getCode() + 3
                    for (i in 0 until len) {
                        if (n >= size) break
                        out[n++] = out[start + i]
                    }
                }
            }
            return out
        }
    }
}
