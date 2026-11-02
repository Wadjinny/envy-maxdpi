package envy.maxdpi

/**
 * Decodes the raster part of a PCL3GUI mode-10 job (ours or the Windows driver's) row by row.
 * Used to check our output and to inspect driver captures; not needed for printing.
 */
class PclReader(private val data: ByteArray) {
    var width = 0
        private set
    var commands = mutableListOf<String>()
        private set

    /**
     * Calls [onRow] with each decoded row of the first page. A vertical skip (`ESC*b#Y`) emits
     * white rows and resets the seed row, as the printer does.
     */
    fun decodePage(
        onCompressed: ((y: Int, data: ByteArray, offset: Int, length: Int) -> Unit)? = null,
        onRow: (y: Int, row: IntArray) -> Unit,
    ): Int {
        var i = data.indexOf("ENTER LANGUAGE=PCL3GUI")
        require(i >= 0) { "not a PCL3GUI job" }
        i = data.indexOf(0x1B, i)
        var decoder: Mode10.Decoder? = null
        var y = 0
        while (i in 0 until data.size) {
            if (data[i].toInt() != 0x1B) { i++; continue }
            val g1 = data[i + 1].toInt().toChar()
            if (g1 !in '!'..'/') { commands += "ESC$g1"; i += 2; continue }
            if (g1 == '%') break // UEL: end of job
            val g2 = data[i + 2].toInt().toChar()
            i += 3
            while (true) {
                val start = i
                while (data[i].toInt().toChar().let { it.isDigit() || it == '-' || it == '+' || it == '.' }) i++
                val value = String(data, start, i - start, Charsets.US_ASCII)
                val term = data[i++].toInt().toChar()
                val key = "$g1$g2${term.uppercaseChar()}"
                val n = value.toIntOrNull() ?: 0
                when (key) {
                    "*bW" -> {
                        val dec = decoder ?: Mode10.Decoder(width).also { decoder = it }
                        dec.decode(data, i, n)
                        onCompressed?.invoke(y, data, i, n)
                        onRow(y++, dec.row)
                        i += n
                    }
                    "*bY" -> {
                        val dec = decoder ?: Mode10.Decoder(width).also { decoder = it }
                        dec.reset()
                        repeat(n) { onRow(y++, dec.row) }
                    }
                    "*rS" -> { width = n; commands += "$key$value" }
                    "*rC" -> return y
                    else -> {
                        commands += "$key$value"
                        if (term.uppercaseChar() == 'W') i += n
                    }
                }
                if (term.isUpperCase()) break
            }
        }
        return y
    }

    private fun ByteArray.indexOf(b: Int, from: Int): Int {
        for (k in from until size) if (this[k].toInt() == b) return k
        return -1
    }

    private fun ByteArray.indexOf(s: String): Int {
        val p = s.toByteArray(Charsets.US_ASCII)
        outer@ for (k in 0..size - p.size) {
            for (j in p.indices) if (this[k + j] != p[j]) continue@outer
            return k
        }
        return -1
    }
}
