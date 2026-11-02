package envy.maxdpi

/**
 * Something that can produce page rows of 0xRRGGBB pixels on demand, so a full 1200 dpi page
 * never sits in memory. Rows may be requested in any order. [close] releases resources such
 * as an open PDF page; wrappers delegate it.
 */
interface RasterSource : AutoCloseable {
    val width: Int
    val height: Int
    fun readRow(y: Int, out: IntArray)
    override fun close() {}
}

enum class Scaling { FIT, FILL }
enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }

/** An axis-aligned box in canvas pixels. */
data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Where content of [srcW]x[srcH] (after optional 90 degree rotation) lands inside [box]:
 * the drawn rectangle, which with [Scaling.FILL] can be larger than the box (and is cropped to it).
 */
class Placement(srcW: Int, srcH: Int, val box: Box, scaling: Scaling, orientation: Orientation) {
    val rotate = when (orientation) {
        Orientation.AUTO -> (srcW > srcH) != (box.width > box.height)
        Orientation.PORTRAIT -> box.width > box.height
        Orientation.LANDSCAPE -> box.width < box.height
    }
    /** Content size as it will be drawn, before scaling. */
    val contentW = if (rotate) srcH else srcW
    val contentH = if (rotate) srcW else srcH
    val scale = when (scaling) {
        Scaling.FIT -> minOf(box.width.toDouble() / contentW, box.height.toDouble() / contentH)
        Scaling.FILL -> maxOf(box.width.toDouble() / contentW, box.height.toDouble() / contentH)
    }
    val drawW = Math.round(contentW * scale).toInt()
    val drawH = Math.round(contentH * scale).toInt()
    val drawLeft = box.left + (box.width - drawW) / 2
    val drawTop = box.top + (box.height - drawH) / 2
}

/**
 * Places an image on the page according to a [Placement]; white elsewhere. Scaling is
 * bicubic (Catmull-Rom), computed one output row at a time.
 *
 * @param pixels source image, 0xRRGGBB (alpha ignored), row-major
 */
class ImagePage(
    pixels: IntArray, srcWidth: Int, srcHeight: Int,
    override val width: Int, override val height: Int,
    private val placement: Placement,
) : RasterSource {
    private val sw: Int
    private val sh: Int
    private val src: IntArray

    // Visible part of the drawn rectangle, in canvas pixels.
    private val visLeft: Int
    private val visRight: Int
    private val visTop: Int
    private val visBottom: Int

    // Horizontal taps for each visible output column: 4 source columns and weights.
    private val xIdx: IntArray
    private val xW: FloatArray
    private val tmpR: FloatArray
    private val tmpG: FloatArray
    private val tmpB: FloatArray

    init {
        if (placement.rotate) {
            // 90 degrees clockwise.
            sw = srcHeight; sh = srcWidth
            src = IntArray(sw * sh)
            for (y in 0 until srcHeight) for (x in 0 until srcWidth) {
                src[x * sw + (sw - 1 - y)] = pixels[y * srcWidth + x]
            }
        } else {
            sw = srcWidth; sh = srcHeight; src = pixels
        }
        val box = placement.box
        visLeft = maxOf(placement.drawLeft, box.left, 0)
        visRight = minOf(placement.drawLeft + placement.drawW, box.left + box.width, width)
        visTop = maxOf(placement.drawTop, box.top, 0)
        visBottom = minOf(placement.drawTop + placement.drawH, box.top + box.height, height)

        val n = maxOf(0, visRight - visLeft)
        xIdx = IntArray(n * 4)
        xW = FloatArray(n * 4)
        val sx = sw.toDouble() / placement.drawW
        for (i in 0 until n) {
            val fx = (visLeft + i - placement.drawLeft + 0.5) * sx - 0.5
            val x0 = Math.floor(fx).toInt()
            val t = fx - x0
            for (k in 0 until 4) {
                xIdx[i * 4 + k] = (x0 - 1 + k).coerceIn(0, sw - 1)
                xW[i * 4 + k] = cubic(t - (k - 1)).toFloat()
            }
        }
        tmpR = FloatArray(sw); tmpG = FloatArray(sw); tmpB = FloatArray(sw)
    }

    override fun readRow(y: Int, out: IntArray) {
        out.fill(Mode10.WHITE)
        if (y < visTop || y >= visBottom) return

        // Vertical pass: blend 4 source rows into float rows.
        val fy = (y - placement.drawTop + 0.5) * sh / placement.drawH - 0.5
        val y0 = Math.floor(fy).toInt()
        val t = fy - y0
        tmpR.fill(0f); tmpG.fill(0f); tmpB.fill(0f)
        for (k in 0 until 4) {
            val w = cubic(t - (k - 1)).toFloat()
            val base = (y0 - 1 + k).coerceIn(0, sh - 1) * sw
            for (x in 0 until sw) {
                val p = src[base + x]
                tmpR[x] += w * (p shr 16 and 0xFF)
                tmpG[x] += w * (p shr 8 and 0xFF)
                tmpB[x] += w * (p and 0xFF)
            }
        }
        // Horizontal pass.
        for (i in 0 until visRight - visLeft) {
            var r = 0f; var g = 0f; var b = 0f
            for (k in 0 until 4) {
                val j = xIdx[i * 4 + k]
                val w = xW[i * 4 + k]
                r += w * tmpR[j]; g += w * tmpG[j]; b += w * tmpB[j]
            }
            out[visLeft + i] = (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
        }
    }

    private fun clamp(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)

    private fun cubic(x: Double): Double {
        val a = Math.abs(x)
        return when {
            a < 1 -> 1.5 * a * a * a - 2.5 * a * a + 1
            a < 2 -> -0.5 * a * a * a + 2.5 * a * a - 4 * a + 2
            else -> 0.0
        }
    }
}

/** Uses an image that is already exactly page-sized at 1200 dpi, cropped at an offset (for test charts). */
class CroppedImage(
    private val pixels: IntArray, private val srcWidth: Int, private val srcHeight: Int,
    override val width: Int, override val height: Int,
    private val left: Int, private val top: Int,
) : RasterSource {
    override fun readRow(y: Int, out: IntArray) {
        out.fill(Mode10.WHITE)
        val sy = y + top
        if (sy < 0 || sy >= srcHeight) return
        for (x in 0 until width) {
            val sx = x + left
            if (sx in 0 until srcWidth) out[x] = pixels[sy * srcWidth + sx] and 0xFFFFFF
        }
    }
}

/** Luma grayscale (Rec. 601 weights). */
class Grayscale(private val source: RasterSource) : RasterSource by source {
    override fun readRow(y: Int, out: IntArray) {
        source.readRow(y, out)
        for (i in 0 until width) {
            val p = out[i]
            val l = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114 + 500) / 1000
            out[i] = (l shl 16) or (l shl 8) or l
        }
    }
}

/** Turns the page upside down (for duplex back sides). */
class Rotated180(private val source: RasterSource) : RasterSource by source {
    override fun readRow(y: Int, out: IntArray) {
        source.readRow(height - 1 - y, out)
        out.reverse(0, width)
    }
}

/** A white page. */
class BlankPage(override val width: Int, override val height: Int) : RasterSource {
    override fun readRow(y: Int, out: IntArray) = out.fill(Mode10.WHITE, 0, width)
}
