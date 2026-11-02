package envy.maxdpi

import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Color calibration with the printer's own scanner, one profile per print quality.
 *
 * 1. Print [TargetPage]: corner markers (one hollow, to tell orientation), a 6x6x6 grid of RGB
 *    mixes and a 17-step gray ramp.
 * 2. Scan it (any orientation on the glass) and pass the pixels to [measure]. The result is a
 *    map of "RGB sent -> RGB the scanner sees" for that quality.
 * 3. [buildCorrection] inverts that map: for every wanted color it finds what to send so the print
 *    comes out closest. Wanted colors keep the printer's own gray tone (grays are left alone) and
 *    get hue and saturation scaled to what the inks can reach, so the picture isn't flattened to
 *    match a weak black. Stored as a 17x17x17 table, applied per pixel with trilinear lookup.
 *
 * Layout units are millimetres inside the canvas's printable box. Scanner RGB is used as the
 * measuring scale, so results are as accurate as the scanner's color response.
 */
object Calibration {
    /** Levels of the measured RGB grid (6 per channel). */
    val CUBE = intArrayOf(0, 51, 102, 153, 204, 255)
    const val RAMP = 17
    fun rampLevel(i: Int) = minOf(255, i * 16)

    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
        val cx get() = x + w / 2
        val cy get() = y + h / 2
    }
    data class Patch(val rect: Rect, val rgb: Int)

    object Layout {
        const val FRAME_W = 190.0
        const val FRAME_H = 250.0
        const val MARKER = 5.0
        private const val COLS = 14
        private const val ROWS = 17
        private const val PATCH = 9.5

        /** Markers: TL, TR, BL, BR (BR is the hollow one). */
        val markers = listOf(
            Rect(0.0, 0.0, MARKER, MARKER),
            Rect(FRAME_W - MARKER, 0.0, MARKER, MARKER),
            Rect(0.0, FRAME_H - MARKER, MARKER, MARKER),
            Rect(FRAME_W - MARKER, FRAME_H - MARKER, MARKER, MARKER),
        )

        /** 216 grid mixes (r fastest), then the gray ramp, then white spares. */
        val colors: List<Int> = buildList {
            for (b in CUBE) for (g in CUBE) for (r in CUBE) add((r shl 16) or (g shl 8) or b)
            for (i in 0 until RAMP) { val v = rampLevel(i); add((v shl 16) or (v shl 8) or v) }
            while (size < COLS * ROWS) add(Mode10.WHITE)
        }

        val patches: List<Patch> = colors.mapIndexed { i, c ->
            val pitchX = (FRAME_W - 20.0) / COLS
            val pitchY = (FRAME_H - 20.0) / ROWS
            Patch(Rect(10.0 + (i % COLS) * pitchX + (pitchX - PATCH) / 2,
                10.0 + (i / COLS) * pitchY + (pitchY - PATCH) / 2, PATCH, PATCH), c)
        }
    }

    /** The target, rendered for a canvas whose printable [box] is at [dpi]. */
    class TargetPage(
        override val width: Int, override val height: Int, box: Box, dpi: Int,
    ) : RasterSource {
        private class PxRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val rgb: Int, val hole: PxRect? = null)
        private val rects: List<PxRect>

        init {
            val k = dpi / 25.4
            val ox = box.left + (box.width - Layout.FRAME_W * k) / 2
            val oy = box.top + 12 * k
            fun px(r: Rect, rgb: Int, hole: PxRect? = null) = PxRect(
                (ox + r.x * k).roundToInt(), (oy + r.y * k).roundToInt(),
                (ox + (r.x + r.w) * k).roundToInt(), (oy + (r.y + r.h) * k).roundToInt(), rgb, hole)
            rects = Layout.markers.mapIndexed { i, m ->
                val hole = if (i == 3) px(Rect(m.x + m.w * 0.3, m.y + m.h * 0.3, m.w * 0.4, m.h * 0.4), Mode10.WHITE) else null
                px(m, 0, hole)
            } + Layout.patches.filter { it.rgb != Mode10.WHITE }.map { px(it.rect, it.rgb) }
        }

        override fun readRow(y: Int, out: IntArray) {
            out.fill(Mode10.WHITE, 0, width)
            for (r in rects) {
                if (y < r.y0 || y >= r.y1) continue
                out.fill(r.rgb, r.x0.coerceIn(0, width), r.x1.coerceIn(0, width))
                r.hole?.let { h -> if (y >= h.y0 && y < h.y1) out.fill(h.rgb, h.x0, h.x1) }
            }
        }
    }

    /** What the scanner saw (0xRRGGBB) for each grid mix and gray ramp step. */
    class Measurement(val cube: IntArray, val ramp: IntArray) {
        fun encode() = (cube.toList() + ramp.toList()).joinToString(",")

        companion object {
            fun decode(s: String): Measurement? = runCatching {
                val v = s.split(",").map { it.toInt() }
                require(v.size == 216 + RAMP)
                Measurement(v.subList(0, 216).toIntArray(), v.subList(216, 216 + RAMP).toIntArray())
            }.getOrNull()
        }
    }

    class MeasureException(message: String) : Exception(message)

    /**
     * Finds the markers in a scan ([pixels] 0xRRGGBB, [dpi] its resolution) and reads every patch.
     * Works with the page in any of the four orientations on the glass.
     */
    fun measure(pixels: IntArray, w: Int, h: Int, dpi: Int): Measurement {
        val map = locate(pixels, w, h, dpi)
        val read = Layout.patches.map { sample(pixels, w, h, map, it.rect) }
        val m = Measurement(IntArray(216) { read[it] }, IntArray(RAMP) { read[216 + it] })
        if (luma(m.ramp.last()) - luma(m.ramp.first()) < 40)
            throw MeasureException("The scan doesn't look like the calibration target (no gray ramp found).")
        return m
    }

    /** Maps layout millimetres to scan pixels. */
    private class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
        fun x(mx: Double, my: Double) = a * mx + b * my + c
        fun y(mx: Double, my: Double) = d * mx + e * my + f
    }

    private class Blob(val cx: Double, val cy: Double, val fill: Double)

    private fun locate(pixels: IntArray, w: Int, h: Int, dpi: Int): Affine {
        // Look for markers on a ~50 dpi copy.
        val step = maxOf(1, dpi / 50)
        val sw = w / step; val sh = h / step
        val lum = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sum = 0
            for (dy in 0 until step) for (dx in 0 until step) sum += luma(pixels[(y * step + dy) * w + x * step + dx])
            lum[y * sw + x] = sum / (step * step)
        }
        // "Dark" relative to this page: black can scan as mid-gray (e.g. Max DPI on plain paper).
        val sorted = lum.sortedArray()
        val ink = sorted[(sorted.size * 0.005).toInt()]
        val paper = sorted[(sorted.size * 0.9).toInt()]
        val threshold = ink + (paper - ink) * 0.35
        val dark = BooleanArray(sw * sh) { lum[it] < threshold }

        val side = Layout.MARKER / 25.4 * dpi / step
        val blobs = mutableListOf<Blob>()
        val seen = BooleanArray(sw * sh)
        val queue = IntArray(sw * sh)
        for (start in dark.indices) {
            if (!dark[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var minX = sw; var maxX = 0; var minY = sh; var maxY = 0; var sx = 0L; var sy = 0L
            while (head < tail) {
                val p = queue[head++]
                val x = p % sw; val y = p / sw
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                sx += x; sy += y
                for (n in intArrayOf(p - 1, p + 1, p - sw, p + sw)) {
                    if (n < 0 || n >= dark.size || seen[n] || !dark[n]) continue
                    if ((n == p - 1 || n == p + 1) && n / sw != y) continue
                    seen[n] = true; queue[tail++] = n
                }
            }
            val bw = maxX - minX + 1.0; val bh = maxY - minY + 1.0
            // Markers (5 mm) are well apart in size from the 9.5 mm patches.
            if (bw !in side * 0.6..side * 1.4 || bh !in side * 0.6..side * 1.4) continue
            blobs += Blob((sx.toDouble() / tail + 0.5) * step, (sy.toDouble() / tail + 0.5) * step, tail / (bw * bh))
        }
        if (blobs.size < 4) throw MeasureException("Couldn't find the 4 corner squares. Place the whole sheet on the glass, printed side down.")
        val corners = listOf(
            blobs.minBy { it.cx + it.cy }, blobs.maxBy { it.cx - it.cy },
            blobs.minBy { it.cx - it.cy }, blobs.maxBy { it.cx + it.cy },
        ).distinct()
        if (corners.size != 4) throw MeasureException("Couldn't tell the corner squares apart. Check the sheet isn't badly tilted.")
        // The hollow marker is layout bottom-right; the one farthest from it is top-left.
        val br = corners.minBy { it.fill }
        val tl = corners.maxBy { hypot(it.cx - br.cx, it.cy - br.cy) }
        val others = corners.filter { it !== br && it !== tl }
        fun cross(p: Blob) = (br.cx - tl.cx) * (p.cy - tl.cy) - (br.cy - tl.cy) * (p.cx - tl.cx)
        val tr = others.minBy { cross(it) }
        val bl = others.first { it !== tr }
        val m = Layout.markers.map { it.cx to it.cy }
        return fitAffine(m, listOf(tl, tr, bl, br))
    }

    private fun fitAffine(src: List<Pair<Double, Double>>, dst: List<Blob>): Affine {
        val ata = Array(3) { DoubleArray(3) }
        val atx = DoubleArray(3); val aty = DoubleArray(3)
        src.forEachIndexed { i, (mx, my) ->
            val row = doubleArrayOf(mx, my, 1.0)
            for (r in 0..2) {
                for (c in 0..2) ata[r][c] += row[r] * row[c]
                atx[r] += row[r] * dst[i].cx; aty[r] += row[r] * dst[i].cy
            }
        }
        val cx = solve3(ata, atx); val cy = solve3(ata, aty)
        return Affine(cx[0], cx[1], cx[2], cy[0], cy[1], cy[2])
    }

    private fun det3(a: Array<DoubleArray>) =
        a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
            a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])

    private fun solve3(m: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        val d = det3(m)
        return DoubleArray(3) { col -> det3(Array(3) { r -> DoubleArray(3) { c -> if (c == col) v[r] else m[r][c] } }) / d }
    }

    /** Median color of the middle half of a patch, smoothing out the ink dots. */
    private fun sample(pixels: IntArray, w: Int, h: Int, map: Affine, r: Rect): Int {
        val rs = IntArray(144); val gs = IntArray(144); val bs = IntArray(144)
        val n = 12
        for (j in 0 until n) for (i in 0 until n) {
            val mx = r.x + r.w * (0.25 + 0.5 * i / (n - 1))
            val my = r.y + r.h * (0.25 + 0.5 * j / (n - 1))
            val px = map.x(mx, my).roundToInt(); val py = map.y(mx, my).roundToInt()
            var sr = 0; var sg = 0; var sb = 0; var c = 0
            for (dy in -2..2) for (dx in -2..2) {
                val x = px + dx; val y = py + dy
                if (x !in 0 until w || y !in 0 until h) continue
                val p = pixels[y * w + x]
                sr += p shr 16 and 0xFF; sg += p shr 8 and 0xFF; sb += p and 0xFF; c++
            }
            if (c == 0) throw MeasureException("Part of the target is outside the scan. Place the whole sheet on the glass.")
            rs[j * n + i] = sr / c; gs[j * n + i] = sg / c; bs[j * n + i] = sb / c
        }
        rs.sort(); gs.sort(); bs.sort()
        return (rs[72] shl 16) or (gs[72] shl 8) or bs[72]
    }

    fun luma(p: Int) = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    // ---- Printer model ---------------------------------------------------------------------

    /** Forward model from a [Measurement]: RGB sent -> RGB seen, plus the wanted look of a color. */
    class Model(private val m: Measurement) {
        private val cube = Array(216) { i -> doubleArrayOf((m.cube[i] shr 16 and 0xFF).toDouble(), (m.cube[i] shr 8 and 0xFF).toDouble(), (m.cube[i] and 0xFF).toDouble()) }
        private val ramp = Array(RAMP) { i -> (luma3(m.ramp[i])) }
        private val black = ramp.first()
        private val white = ramp.last()
        /** Overall contrast the printer can reach, used to scale saturation. */
        private val contrast = (white - black) / 255.0

        /** What the print of [d] (0..255 per channel) looks like to the scanner. Trilinear on the grid. */
        fun forward(d: DoubleArray): DoubleArray {
            val out = DoubleArray(3)
            val idx = IntArray(3); val t = DoubleArray(3)
            for (c in 0..2) {
                val f = (d[c] / 51.0).coerceIn(0.0, 5.0)
                idx[c] = minOf(4, f.toInt()); t[c] = f - idx[c]
            }
            for (corner in 0 until 8) {
                val dr = corner and 1; val dg = corner shr 1 and 1; val db = corner shr 2 and 1
                val wgt = (if (dr == 1) t[0] else 1 - t[0]) * (if (dg == 1) t[1] else 1 - t[1]) * (if (db == 1) t[2] else 1 - t[2])
                if (wgt == 0.0) continue
                val p = cube[(idx[0] + dr) + 6 * (idx[1] + dg) + 36 * (idx[2] + db)]
                for (c in 0..2) out[c] += wgt * p[c]
            }
            return out
        }

        /** The printer's own gray at input level [v] (as the scanner sees it). */
        private fun neutral(v: Double): Double {
            val f = (v / 16.0).coerceIn(0.0, (RAMP - 1).toDouble())
            val i = minOf(RAMP - 2, f.toInt()); val t = f - i
            return ramp[i] + (ramp[i + 1] - ramp[i]) * t
        }

        /**
         * How color [t] should look: the printer's own gray at the same lightness, plus the color's
         * difference from gray scaled by the printer's contrast. Grays map to themselves.
         */
        fun wanted(t: DoubleArray): DoubleArray {
            val y = 0.299 * t[0] + 0.587 * t[1] + 0.114 * t[2]
            val n = neutral(y)
            return DoubleArray(3) { n + (t[it] - y) * contrast }
        }

        /** What to send so that the print of [t] looks as close as possible to [wanted]. */
        fun invert(t: DoubleArray): DoubleArray {
            if (t[0] == t[1] && t[1] == t[2]) return t.copyOf() // grays print neutral already
            val goal = wanted(t)
            var d = t.copyOf()
            var best = d.copyOf(); var bestErr = err(forward(d), goal)
            var lambda = 1.0
            repeat(40) {
                val f = forward(d)
                val e = DoubleArray(3) { f[it] - goal[it] }
                // Numeric Jacobian (one-sided, pointing inside the 0..255 cube).
                val j = Array(3) { DoubleArray(3) }
                for (c in 0..2) {
                    val h = if (d[c] > 250) -4.0 else 4.0
                    val dd = d.copyOf(); dd[c] += h
                    val f2 = forward(dd)
                    for (r in 0..2) j[r][c] = (f2[r] - f[r]) / h
                }
                // Levenberg-Marquardt step: (JtJ + lambda I) step = Jt e
                val jtj = Array(3) { r -> DoubleArray(3) { c -> (0..2).sumOf { j[it][r] * j[it][c] } + if (r == c) lambda else 0.0 } }
                val jte = DoubleArray(3) { r -> (0..2).sumOf { j[it][r] * e[it] } }
                if (det3(jtj) == 0.0) return@repeat
                val step = solve3(jtj, jte)
                val next = DoubleArray(3) { (d[it] - step[it]).coerceIn(0.0, 255.0) }
                val ne = err(forward(next), goal)
                if (ne < bestErr) { best = next; bestErr = ne; d = next; lambda = maxOf(0.01, lambda * 0.5) } else lambda *= 4
            }
            return best
        }

        fun err(a: DoubleArray, b: DoubleArray) = sqrt((0..2).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })

        private fun luma3(p: Int) = 0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
    }

    /** 17x17x17 table: input RGB -> RGB to send. Applied with trilinear interpolation. */
    class ColorCorrection(private val lut: IntArray) {
        fun apply(p: Int): Int {
            val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
            // Grid step is 255/16; work in 1/4080 units to stay in integers.
            val fr = r * 16; val fg = g * 16; val fb = b * 16
            val ir = minOf(15, fr / 255); val ig = minOf(15, fg / 255); val ib = minOf(15, fb / 255)
            val tr = fr - ir * 255; val tg = fg - ig * 255; val tb = fb - ib * 255
            var or = 0L; var og = 0L; var ob = 0L
            for (corner in 0 until 8) {
                val dr = corner and 1; val dg = corner shr 1 and 1; val db = corner shr 2 and 1
                val w = (if (dr == 1) tr else 255 - tr).toLong() * (if (dg == 1) tg else 255 - tg) * (if (db == 1) tb else 255 - tb)
                if (w == 0L) continue
                val v = lut[(ir + dr) + N * (ig + dg) + N * N * (ib + db)]
                or += w * (v shr 16 and 0xFF); og += w * (v shr 8 and 0xFF); ob += w * (v and 0xFF)
            }
            val s = 255L * 255 * 255
            return (((or + s / 2) / s).toInt() shl 16) or (((og + s / 2) / s).toInt() shl 8) or ((ob + s / 2) / s).toInt()
        }

        fun encode() = "v2:" + lut.joinToString(",")

        companion object {
            const val N = 17
            fun decode(s: String): ColorCorrection? = runCatching {
                require(s.startsWith("v2:"))
                val v = s.removePrefix("v2:").split(",").map { it.toInt() }.toIntArray()
                require(v.size == N * N * N)
                ColorCorrection(v)
            }.getOrNull()
        }
    }

    /** Builds the correction table by inverting the measured printer response. */
    fun buildCorrection(m: Measurement): ColorCorrection {
        val model = Model(m)
        val n = ColorCorrection.N
        val lut = IntArray(n * n * n)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val t = doubleArrayOf(r * 255.0 / 16, g * 255.0 / 16, b * 255.0 / 16)
            val d = model.invert(t)
            lut[r + n * g + n * n * b] = (d[0].roundToInt().coerceIn(0, 255) shl 16) or
                (d[1].roundToInt().coerceIn(0, 255) shl 8) or d[2].roundToInt().coerceIn(0, 255)
        }
        return ColorCorrection(lut)
    }

    /** Average color error over the grid (scanner RGB distance, 0-441): as printed, and predicted with [correction]. */
    fun errors(m: Measurement, correction: ColorCorrection? = null): Pair<Double, Double?> {
        val model = Model(m)
        var before = 0.0; var after = 0.0
        for (i in 0 until 216) {
            val t = doubleArrayOf(CUBE[i % 6].toDouble(), CUBE[i / 6 % 6].toDouble(), CUBE[i / 36].toDouble())
            val goal = model.wanted(t)
            before += model.err(model.forward(t), goal)
            if (correction != null) {
                val p = correction.apply(((t[0].toInt()) shl 16) or (t[1].toInt() shl 8) or t[2].toInt())
                after += model.err(model.forward(doubleArrayOf((p shr 16 and 0xFF).toDouble(), (p shr 8 and 0xFF).toDouble(), (p and 0xFF).toDouble())), goal)
            }
        }
        return before / 216 to if (correction != null) after / 216 else null
    }

    /**
     * For checking a print made with the correction: measures how far each grid color is from the
     * wanted look, using the reference measurement that built the correction.
     */
    fun checkError(reference: Measurement, check: Measurement): Double {
        val model = Model(reference)
        var sum = 0.0
        for (i in 0 until 216) {
            val t = doubleArrayOf(CUBE[i % 6].toDouble(), CUBE[i / 6 % 6].toDouble(), CUBE[i / 36].toDouble())
            val c = check.cube[i]
            sum += model.err(doubleArrayOf((c shr 16 and 0xFF).toDouble(), (c shr 8 and 0xFF).toDouble(), (c and 0xFF).toDouble()), model.wanted(t))
        }
        return sum / 216
    }

    /** Swatches for the UI: for a few key colors, (wanted look, as printed now, predicted after correction). */
    fun preview(m: Measurement, correction: ColorCorrection?): List<Triple<Int, Int, Int?>> {
        val model = Model(m)
        fun pack(v: DoubleArray) = (v[0].roundToInt().coerceIn(0, 255) shl 16) or (v[1].roundToInt().coerceIn(0, 255) shl 8) or v[2].roundToInt().coerceIn(0, 255)
        fun unpack(p: Int) = doubleArrayOf((p shr 16 and 0xFF).toDouble(), (p shr 8 and 0xFF).toDouble(), (p and 0xFF).toDouble())
        return KEY_COLORS.map { c ->
            val t = unpack(c)
            Triple(pack(model.wanted(t)), pack(model.forward(t)), correction?.let { pack(model.forward(unpack(it.apply(c)))) })
        }
    }

    /** Red, yellow, green, cyan, blue, magenta, orange, skin, brown, sky, olive, pink. */
    val KEY_COLORS = listOf(0xE03030, 0xF0E040, 0x40B040, 0x40C0D0, 0x3050C0, 0xC040B0, 0xF09030, 0xE8B898, 0x8A5A3A, 0x80B0E0, 0x808030, 0xF0A0C0)
}

/** Applies a [Calibration.ColorCorrection] to every pixel. */
class Corrected(private val source: RasterSource, private val correction: Calibration.ColorCorrection) : RasterSource by source {
    override fun readRow(y: Int, out: IntArray) {
        source.readRow(y, out)
        for (i in 0 until width) out[i] = correction.apply(out[i])
    }
}
