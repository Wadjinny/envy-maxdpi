package envy.maxdpi

import java.io.OutputStream

/** Printable area of a page at 1200 dpi, as the HP Windows driver lays it out. */
data class PageFormat(val name: String, val widthPx: Int, val heightPx: Int, val pclSizeId: Int) {
    companion object {
        /** A4 with the driver's standard margins (taken from a Windows Max DPI capture). */
        val A4 = PageFormat("A4", 9782, 13754, 26)
    }
}

/**
 * Media settings sent in the page header. Values are copied from Windows driver captures;
 * only plain paper has been captured so far.
 */
data class MediaProfile(val name: String, val pclMediaType: Int, val mediaSubtype: Int, val printQuality: Int) {
    companion object {
        /** HP driver: plain paper, Advanced > Print in Max DPI = Yes. */
        val PLAIN_MAX_DPI = MediaProfile("plain, max dpi", pclMediaType = 5, mediaSubtype = 0x042D, printQuality = 2)
    }
}

/**
 * Writes a PCL3GUI job in the same shape as the HP ENVY 6000 Windows driver:
 * RGB rows at 1200x1200 dpi, compressed with [Mode10].
 */
class PclJobWriter(
    private val out: OutputStream,
    private val page: PageFormat = PageFormat.A4,
    private val media: MediaProfile = MediaProfile.PLAIN_MAX_DPI,
    /** PCL `&l#S`: 0 simplex, 1 duplex long edge, 2 duplex short edge. */
    private val sides: Int = 0,
) {
    private val encoder = Mode10.Encoder(page.widthPx)
    private var rowsInPage = 0

    fun beginJob(jobName: String) {
        val name = jobName.replace("\"", "'")
        ascii("\u001b%-12345X@PJL JOB NAME=\"$name\"\n@PJL ENTER LANGUAGE=PCL3GUI\n")
        esc("E")
    }

    fun beginPage() {
        encoder.reset()
        rowsInPage = 0
        esc("&l${sides}S")                              // simplex / duplex
        esc("&l1H")                                     // main tray
        esc("*o5W"); bytes(0x0D, 0x03, 0x00, media.mediaSubtype shr 8, media.mediaSubtype and 0xFF)
        esc("*o${media.printQuality}M")
        esc("&l${page.pclSizeId}A")
        esc("&l${media.pclMediaType}M")
        esc("*o5W"); bytes(0x0B, 0x01, 0x00, 0x00, 0x00)
        esc("&u1200D")
        esc("*t1200R")
        // Configure raster data: format 6, 1 component, 1200x1200 dpi, mode 10, RGB 32-bit.
        esc("*g12W"); bytes(0x06, 0x07, 0x00, 0x01, 0x04, 0xB0, 0x04, 0xB0, 0x0A, 0x01, 0x20, 0x01)
        esc("*r${page.widthPx}S")
        esc("&l-2H")                                    // preload paper
        esc("*r1A")                                     // start raster graphics
        esc("*p0Y")
    }

    /** Adds one row of [PageFormat.widthPx] 0xRRGGBB pixels. */
    fun row(pixels: IntArray) {
        check(rowsInPage < page.heightPx) { "page is full (${page.heightPx} rows)" }
        val n = encoder.encode(pixels)
        ascii("\u001b*b${n}W")
        if (n > 0) out.write(encoder.buffer, 0, n)
        rowsInPage++
    }

    fun endPage() {
        esc("*rC")
        out.write(0x0C)
    }

    fun endJob() {
        esc("E")
        ascii("\u001b%-12345X")
        out.flush()
    }

    private fun esc(s: String) = ascii("\u001b$s")
    private fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
    private fun bytes(vararg b: Int) = b.forEach { out.write(it) }
}

/** Writes a complete job: every source becomes one page, in order. */
fun writePclJob(out: OutputStream, pages: List<() -> RasterSource>, jobName: String,
                page: PageFormat = PageFormat.A4, media: MediaProfile = MediaProfile.PLAIN_MAX_DPI,
                sides: Int = 0,
                onProgress: (pageIndex: Int, row: Int, rows: Int) -> Unit = { _, _, _ -> }) {
    val writer = PclJobWriter(out, page, media, sides)
    val line = IntArray(page.widthPx)
    writer.beginJob(jobName)
    pages.forEachIndexed { index, open ->
        open().use { source -> writePage(writer, source, index, page, line, onProgress) }
    }
    writer.endJob()
}

private fun writePage(writer: PclJobWriter, source: RasterSource, index: Int, page: PageFormat, line: IntArray,
                      onProgress: (pageIndex: Int, row: Int, rows: Int) -> Unit) {
    run {
        require(source.width == page.widthPx) { "source width ${source.width} != page width ${page.widthPx}" }
        val rows = minOf(source.height, page.heightPx)
        writer.beginPage()
        for (y in 0 until rows) {
            source.readRow(y, line)
            writer.row(line)
            if (y % 128 == 0) onProgress(index, y, rows)
        }
        writer.endPage()
        onProgress(index, rows, rows)
    }
}
