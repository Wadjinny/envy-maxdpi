package envy.maxdpi

/**
 * HP PCL raster compression mode 10 ("Bert"), the RGB delta-row scheme the HP ENVY 6000
 * Windows driver uses for its 1200 dpi "Max DPI" jobs.
 *
 * Pixels are 0xRRGGBB ints. Every row is coded against the previous row (the seed row),
 * which starts out white. Blue is stored with only 7 bits, so the encoder rounds blue to an
 * even value (255 stays 255) and keeps a mirror of the printer's decoder: each row is coded
 * against what the printer actually holds, so blue rounding can never build up down the page.
 * HP's original encoder codes against the exact previous row instead, and on smooth content
 * its blue error grows row by row until it wraps around.
 *
 * The encoder is a port of HPLIP's hpcups Mode10.cpp (BSD licensed, Hewlett-Packard), with
 * the closed-loop seed described above.
 */
object Mode10 {
    const val WHITE = 0xFFFFFF

    private const val LITERAL = 0x00
    private const val RLE = 0x80

    private const val SRC_NEW = 0x00
    private const val SRC_WEST = 0x20
    private const val SRC_NORTHEAST = 0x40
    private const val SRC_CACHED = 0x60

    class Encoder(val width: Int) {
        init {
            require(width >= 2) { "row must be at least 2 pixels" }
        }

        private val mirror = Decoder(width)
        private val seed = IntArray(width) { WHITE }
        private val cur = IntArray(width)

        /** Compressed bytes of the last [encode] call are `buffer[0 until size]`. */
        val buffer = ByteArray(width * 5 + 64)
        var size = 0
            private set
        private var o = 0

        /** Starts a new page: the printer resets its seed row to white. */
        fun reset() {
            seed.fill(WHITE)
            mirror.reset()
        }

        /** Compresses one row of [width] pixels. A size of 0 means "same as previous row". */
        fun encode(row: IntArray): Int {
            for (i in 0 until width) {
                val p = row[i] and WHITE
                cur[i] = if (p and 0xFF == 0xFF) p else p and 0xFFFFFE
            }
            o = 0
            val last = width - 1
            val realLast = cur[last]

            // Sentinel: make the last pixel differ from its west and north neighbours so the
            // scans below always stop at the end of the row.
            var newLast = realLast
            while (cur[last - 1] == newLast || seed[last] == newLast) {
                newLast = (newLast + 0x100) and WHITE
                cur[last] = newLast
            }

            var curPixel = 0
            var cached = WHITE

            rows@ do {
                var cmd: Int
                var src = SRC_NEW
                var replacementCount: Int

                var seedCopy = curPixel
                while (seed[curPixel] == cur[curPixel]) curPixel++
                seedCopy = curPixel - seedCopy

                if (curPixel == last) {
                    cur[last] = realLast
                    if (seed[curPixel] == realLast) break@rows
                    cmd = LITERAL
                    replacementCount = 1
                    curPixel++
                } else {
                    cmd = LITERAL
                    replacementCount = curPixel
                    val run = cur[curPixel]
                    curPixel++
                    while (run == cur[curPixel]) curPixel++
                    curPixel--
                    replacementCount = curPixel - replacementCount

                    if (replacementCount > 0) {
                        curPixel++
                        replacementCount++
                        val start = curPixel - replacementCount
                        src = when {
                            cached == run -> SRC_CACHED
                            seed[start + 1] == run -> SRC_NORTHEAST
                            start > 0 && cur[start - 1] == run -> SRC_WEST
                            else -> { cached = run; SRC_NEW }
                        }
                        cmd = RLE
                    }

                    if (curPixel == last && realLast == run) {
                        cur[last] = realLast
                        replacementCount++
                        curPixel++
                    }

                    if (replacementCount == 0) {
                        val temp = cur[curPixel]
                        src = when {
                            cached == temp -> SRC_CACHED
                            seed[curPixel + 1] == temp -> SRC_NORTHEAST
                            curPixel > 0 && cur[curPixel - 1] == temp -> SRC_WEST
                            else -> { cached = temp; SRC_NEW }
                        }
                        replacementCount = curPixel
                        var next = cur[curPixel + 1]
                        while (true) {
                            curPixel++
                            if (curPixel == last) {
                                cur[last] = realLast
                                curPixel++
                                break
                            }
                            val prev = next
                            next = cur[curPixel + 1]
                            if (prev == next || prev == seed[curPixel]) break
                        }
                        replacementCount = curPixel - replacementCount
                    }
                }

                if (cmd == LITERAL) {
                    put(src or (minOf(3, seedCopy) shl 3) or minOf(7, replacementCount - 1))
                    if (seedCopy >= 3) putVli(seedCopy - 3)
                    val total = replacementCount
                    var up = 1
                    if (src != SRC_NEW) { replacementCount--; up = 2 }
                    while (up <= total) {
                        val idx = curPixel - replacementCount
                        putPixel(cur[idx], seed[idx])
                        if ((up - 8) % 255 == 0) put(minOf(255, total - up))
                        replacementCount--
                        up++
                    }
                } else {
                    put(RLE or src or (minOf(3, seedCopy) shl 3) or minOf(7, replacementCount - 2))
                    if (seedCopy >= 3) putVli(seedCopy - 3)
                    if (src == SRC_NEW) {
                        val idx = curPixel - replacementCount
                        putPixel(cur[idx], seed[idx])
                    }
                    if (replacementCount - 2 >= 7) putVli(replacementCount - 9)
                }
            } while (curPixel <= last)

            mirror.decode(buffer, 0, o)
            System.arraycopy(mirror.row, 0, seed, 0, width)
            size = o
            return o
        }

        private fun put(b: Int) {
            buffer[o++] = b.toByte()
        }

        private fun putVli(n: Int) {
            var number = n
            do {
                put(minOf(number, 255))
                if (number == 255) put(0)
                number -= minOf(number, 255)
            } while (number != 0)
        }

        private fun putPixel(pixel: Int, upper: Int) {
            val dr = (pixel shr 16 and 0xFF) - (upper shr 16 and 0xFF)
            val dg = (pixel shr 8 and 0xFF) - (upper shr 8 and 0xFF)
            val db = (pixel and 0xFF) - (upper and 0xFF)
            // A short delta rounds blue down to even; never let that take it below 0.
            if (dr in -16..15 && dg in -16..15 && db in -32..30 && (upper and 0xFF) + ((db shr 1) shl 1) >= 0) {
                val v = ((dr shl 10) and 0x7C00) or ((dg shl 5) and 0x3E0) or ((db shr 1) and 0x1F) or 0x8000
                put(v shr 8)
                put(v)
            } else {
                val v = pixel shr 1
                put(v shr 16)
                put(v shr 8)
                put(v)
            }
        }
    }

    class Decoder(val width: Int) {
        /** The current row; after [decode] it holds the decoded pixels and is the next seed. */
        val row = IntArray(width) { WHITE }
        private val prev = IntArray(width)

        fun reset() = row.fill(WHITE)

        fun decode(data: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            System.arraycopy(row, 0, prev, 0, width)
            val seed = prev
            val out = row
            var p = offset
            val end = offset + length
            var pos = 0
            var cached = WHITE

            fun u8() = data[p++].toInt() and 0xFF
            fun vli(): Int {
                var sum = 0
                do {
                    val b = u8()
                    sum += b
                } while (b == 255)
                return sum
            }
            fun pixel(upper: Int): Int {
                val b0 = u8()
                return if (b0 and 0x80 != 0) {
                    val v = (b0 shl 8) or u8()
                    val dr = ((v shr 10) and 0x1F).let { if (it >= 16) it - 32 else it }
                    val dg = ((v shr 5) and 0x1F).let { if (it >= 16) it - 32 else it }
                    val db = (v and 0x1F).let { if (it >= 16) it - 32 else it } * 2
                    val r = ((upper shr 16 and 0xFF) + dr) and 0xFF
                    val g = ((upper shr 8 and 0xFF) + dg) and 0xFF
                    val b = ((upper and 0xFF) + db) and 0xFF
                    (r shl 16) or (g shl 8) or b
                } else {
                    val v = (b0 shl 16) or (u8() shl 8) or u8()
                    (v shl 1) and WHITE
                }
            }
            fun alternate(src: Int, at: Int) = when (src) {
                SRC_WEST -> out[at - 1]
                SRC_NORTHEAST -> seed[at + 1]
                else -> cached
            }

            while (p < end && pos < width) {
                val cmd = u8()
                var seedCopy = (cmd shr 3) and 3
                if (seedCopy == 3) seedCopy += vli()
                pos += seedCopy // out already holds the seed row
                val src = cmd and 0x60
                if (cmd and RLE == 0) {
                    var total = (cmd and 7) + 1
                    var up = 1
                    if (src != SRC_NEW) {
                        out[pos] = alternate(src, pos)
                        pos++
                        up = 2
                    }
                    while (up <= total) {
                        val px = pixel(seed[pos])
                        if (up == 1) cached = px
                        out[pos++] = px
                        if ((up - 8) % 255 == 0) total = up + u8()
                        up++
                    }
                } else {
                    // The run's pixel comes before the extended count in the stream.
                    val px = if (src == SRC_NEW) pixel(seed[pos]).also { cached = it } else alternate(src, pos)
                    var total = (cmd and 7) + 2
                    if (total == 9) total += vli()
                    out.fill(px, pos, pos + total)
                    pos += total
                }
            }
        }
    }
}
