package envy.maxdpi

import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * PWG Raster (PWG 5102.4) writer: 8-bit sRGB or sGray pages, the format phones use with IPP.
 * Used for the Best/Normal/Draft modes, which go through the printer's standard IPP path.
 */
class PwgRasterWriter(private val out: OutputStream) {
    data class PageSettings(
        val dpi: Int,
        val gray: Boolean,
        val quality: Int,          // IPP print-quality: 3 draft, 4 normal, 5 high
        val totalPages: Int,
        val duplex: Boolean,
        val tumble: Boolean,
        val mediaType: String = "stationery",
        val sizeName: String = "iso_a4_210x297mm",
        val widthPt: Int = 595,
        val heightPt: Int = 842,
    )

    private var started = false

    fun page(source: RasterSource, s: PageSettings) {
        if (!started) { out.write("RaS2".toByteArray(Charsets.US_ASCII)); started = true }
        val w = source.width
        val h = source.height
        val bpp = if (s.gray) 1 else 3
        out.write(header(w, h, bpp, s))

        var row = IntArray(w)
        var next = IntArray(w)
        val line = ByteArray(w * bpp)
        val packed = ByteArray(w * (bpp + 1) + 16)
        source.readRow(0, row)
        var y = 0
        while (y < h) {
            // Line repeat: how many following rows are identical (max 255 extra).
            var repeat = 0
            var haveNext = false
            while (y + repeat + 1 < h) {
                source.readRow(y + repeat + 1, next)
                if (repeat < 255 && next.contentEquals(row)) repeat++ else { haveNext = true; break }
            }
            toBytes(row, line, bpp)
            out.write(repeat)
            out.write(packed, 0, packLine(line, bpp, w, packed))
            y += repeat + 1
            if (haveNext) { val t = row; row = next; next = t } // `next` holds row y
        }
    }

    fun finish() = out.flush()

    private fun toBytes(row: IntArray, line: ByteArray, bpp: Int) {
        if (bpp == 3) {
            var j = 0
            for (p in row) {
                line[j++] = (p shr 16).toByte(); line[j++] = (p shr 8).toByte(); line[j++] = p.toByte()
            }
        } else {
            for (i in row.indices) line[i] = (row[i] and 0xFF).toByte()
        }
    }

    /** PWG/PackBits-style pixel runs: 0..127 = repeat next pixel n+1 times, 129..255 = 257-n literal pixels. */
    private fun packLine(line: ByteArray, bpp: Int, w: Int, dst: ByteArray): Int {
        var o = 0
        var x = 0
        fun same(a: Int, b: Int): Boolean {
            for (k in 0 until bpp) if (line[a * bpp + k] != line[b * bpp + k]) return false
            return true
        }
        while (x < w) {
            if (x + 1 < w && same(x, x + 1)) {
                var n = 2
                while (x + n < w && n < 128 && same(x, x + n)) n++
                dst[o++] = (n - 1).toByte()
                System.arraycopy(line, x * bpp, dst, o, bpp); o += bpp
                x += n
            } else {
                var n = 1
                while (x + n < w && n < 128 && !(x + n + 1 < w && same(x + n, x + n + 1))) n++
                dst[o++] = if (n == 1) 0 else (257 - n).toByte()
                System.arraycopy(line, x * bpp, dst, o, n * bpp); o += n * bpp
                x += n
            }
        }
        return o
    }

    private fun header(w: Int, h: Int, bpp: Int, s: PageSettings): ByteArray {
        val b = ByteBuffer.allocate(1796) // big-endian by default
        fun str(offset: Int, v: String) { val bytes = v.toByteArray(Charsets.US_ASCII); b.position(offset); b.put(bytes, 0, minOf(63, bytes.size)) }
        fun int(offset: Int, v: Int) { b.putInt(offset, v) }
        str(0, "PwgRaster")
        str(128, s.mediaType)
        int(272, if (s.duplex) 1 else 0)
        int(276, s.dpi); int(280, s.dpi)
        int(340, 1)                         // NumCopies (copies are expanded by the caller)
        int(352, s.widthPt); int(356, s.heightPt)
        int(368, if (s.tumble) 1 else 0)
        int(372, w); int(376, h)
        int(384, 8)                         // bits per color
        int(388, bpp * 8)                   // bits per pixel
        int(392, w * bpp)                   // bytes per line
        int(396, 0)                         // chunky
        int(400, if (bpp == 1) 18 else 19)  // sGray / sRGB
        int(420, bpp)                       // number of colors
        int(452, s.totalPages)              // TotalPageCount
        int(456, 1); int(460, 1)            // Cross/Feed transform
        int(464, 0); int(468, 0); int(472, w); int(476, h) // ImageBox
        int(480, 0xFFFFFF)                  // AlternatePrimary
        int(484, s.quality)                 // PrintQuality
        str(1668, "perceptual")
        str(1732, s.sizeName)
        return b.array()
    }
}
