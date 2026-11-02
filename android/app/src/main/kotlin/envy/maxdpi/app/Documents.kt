package envy.maxdpi.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import envy.maxdpi.PageContent
import envy.maxdpi.Placement
import envy.maxdpi.ImagePage
import envy.maxdpi.RasterSource
import java.io.File
import java.util.UUID

/** A file the user added. It is copied into the app's cache so the print service can read it later. */
sealed class Doc(val id: String, val file: File, val name: String) {
    abstract val pageCount: Int
    abstract fun page(index: Int): PageRef
}

class ImageDoc(id: String, file: File, name: String, val width: Int, val height: Int) : Doc(id, file, name) {
    override val pageCount = 1
    override fun page(index: Int) = PageRef(this, 0, width, height)
}

/** Page sizes are in PDF points. */
class PdfDoc(id: String, file: File, name: String, val sizes: List<Pair<Int, Int>>) : Doc(id, file, name) {
    override val pageCount get() = sizes.size
    override fun page(index: Int) = PageRef(this, index, sizes[index].first, sizes[index].second)
}

data class PageRef(val doc: Doc, val index: Int, val width: Int, val height: Int) {
    val key get() = "${doc.id}#$index"
}

object Documents {
    /** Copies [uri] into the cache and reads its size or page sizes. Runs on a background thread. */
    fun import(context: Context, uri: Uri): Doc {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "document"
        val type = resolver.getType(uri) ?: ""
        val isPdf = type == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
        val id = UUID.randomUUID().toString()
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val file = File(dir, id + if (isPdf) ".pdf" else ".img")
        resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }

        return if (isPdf) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    val sizes = (0 until r.pageCount).map { i -> r.openPage(i).use { it.width to it.height } }
                    PdfDoc(id, file, name, sizes)
                }
            }
        } else {
            // Decode a small version to learn the size after EXIF rotation.
            val thumb = decodeImage(file, 512)
            ImageDoc(id, file, name, thumb.width, thumb.height).also { thumb.recycle() }
        }
    }

    fun delete(doc: Doc) = doc.file.delete()

    /** Decodes with EXIF rotation applied, shrunk so neither side exceeds [maxSide]. */
    fun decodeImage(file: File, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val scale = minOf(1.0, maxSide.toDouble() / maxOf(w, h))
            if (scale < 1.0) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
        }

    /** Small picture of a page for the preview. */
    fun thumbnail(page: PageRef, maxSide: Int): Bitmap = when (val doc = page.doc) {
        is ImageDoc -> decodeImage(doc.file, maxSide)
        is PdfDoc -> ParcelFileDescriptor.open(doc.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(page.index).use { p ->
                    val s = maxSide.toFloat() / maxOf(p.width, p.height)
                    val bmp = Bitmap.createBitmap(maxOf(1, (p.width * s).toInt()), maxOf(1, (p.height * s).toInt()), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, Matrix().apply { setScale(s, s) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    /** The printable form of a page, used by the print service. */
    fun content(page: PageRef): PageContent = when (val doc = page.doc) {
        is ImageDoc -> ImageContent(doc.file, page.width, page.height)
        is PdfDoc -> PdfContent(doc.file, page.index, page.width, page.height)
    }
}

class ImageContent(private val file: File, override val contentWidth: Int, override val contentHeight: Int) : PageContent {
    override fun open(canvasWidth: Int, canvasHeight: Int, placement: Placement): RasterSource {
        // Decoding beyond the size it is drawn at adds nothing.
        val bmp = Documents.decodeImage(file, maxOf(placement.drawW, placement.drawH))
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        return ImagePage(pixels, w, h, canvasWidth, canvasHeight, placement)
    }
}

class PdfContent(
    private val file: File, private val index: Int,
    override val contentWidth: Int, override val contentHeight: Int,
) : PageContent {
    override fun open(canvasWidth: Int, canvasHeight: Int, placement: Placement): RasterSource =
        PdfBandSource(file, index, canvasWidth, canvasHeight, placement)
}

/**
 * Renders a PDF page at print resolution one horizontal band at a time, so even a 1200 dpi
 * A4 page (9782 x 13754) only needs a few MB.
 */
class PdfBandSource(
    file: File, index: Int,
    override val width: Int, override val height: Int,
    private val placement: Placement,
) : RasterSource {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val page = renderer.openPage(index)
    private val band = Bitmap.createBitmap(width, BAND, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(width * BAND)
    private var bandIndex = -1

    // Visible rectangle: drawn content cropped to the printable box.
    private val visible = Rect(
        maxOf(placement.drawLeft, placement.box.left),
        maxOf(placement.drawTop, placement.box.top),
        minOf(placement.drawLeft + placement.drawW, placement.box.left + placement.box.width),
        minOf(placement.drawTop + placement.drawH, placement.box.top + placement.box.height),
    )

    override fun readRow(y: Int, out: IntArray) {
        val b = y / BAND
        if (b != bandIndex) render(b)
        val off = (y - b * BAND) * width
        for (x in 0 until width) out[x] = pixels[off + x] and 0xFFFFFF
    }

    private fun render(b: Int) {
        bandIndex = b
        val top = b * BAND
        band.eraseColor(Color.WHITE)
        val clip = Rect(visible.left, visible.top - top, visible.right, visible.bottom - top)
        if (clip.intersect(0, 0, width, BAND)) {
            val pw = page.width.toFloat(); val ph = page.height.toFloat()
            val m = Matrix()
            if (placement.rotate) {
                m.postRotate(90f)
                m.postTranslate(ph, 0f)
            }
            val cw = if (placement.rotate) ph else pw
            val ch = if (placement.rotate) pw else ph
            m.postScale(placement.drawW / cw, placement.drawH / ch)
            m.postTranslate(placement.drawLeft.toFloat(), (placement.drawTop - top).toFloat())
            page.render(band, clip, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        }
        band.getPixels(pixels, 0, width, 0, 0, width, BAND)
    }

    override fun close() {
        page.close()
        renderer.close()
        fd.close()
        band.recycle()
    }

    private companion object {
        const val BAND = 128
    }
}
