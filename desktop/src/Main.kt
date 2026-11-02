import envy.maxdpi.CroppedImage
import envy.maxdpi.Duplex
import envy.maxdpi.ImagePage
import envy.maxdpi.IppClient
import envy.maxdpi.Mode10
import envy.maxdpi.Orientation
import envy.maxdpi.PageContent
import envy.maxdpi.PageFormat
import envy.maxdpi.PclReader
import envy.maxdpi.Placement
import envy.maxdpi.PrintOptions
import envy.maxdpi.PrinterConnection
import envy.maxdpi.Printing
import envy.maxdpi.Quality
import envy.maxdpi.RasterSource
import envy.maxdpi.Scaling
import envy.maxdpi.writePclJob
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

const val DEFAULT_HOST = "printer.local"

fun usage(): Nothing {
    System.err.println(
        """
        usage:
          decode   <job.prn> <out.png> [fromRow toRow]   decode a PCL3GUI job to an image
          reencode <job.prn>                             re-encode a job's rows with our encoder and compare
          encode   <image> <out.prn> [--chart L,T] [--rows N] [--skip-mm MM]   build a Max DPI job file
          send     <job.prn> [--host H]                  send a job file to the printer (port 9100)
          status   [--host H]                            printer state and ink levels (IPP)
          print    <image>... [print options]            print images, one per page

        encode options:
          --chart LEFT,TOP     image is a full-page 1200 ppi chart; crop it at this pixel offset instead of fitting
          --rows N             only the first N rows of the page (quick test strips)
          --skip-mm MM         with --chart: start the strip MM millimetres further down the chart

        print options:
          --quality max|best|normal|draft   --gray   --copies N   --duplex off|long|short
          --scaling fit|fill   --orientation auto|portrait|landscape   --host H (default $DEFAULT_HOST)
        """.trimIndent()
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val opts = args.drop(1).toMutableList()
    fun opt(name: String): String? {
        val i = opts.indexOf(name)
        if (i < 0) return null
        val v = opts[i + 1]
        opts.removeAt(i); opts.removeAt(i)
        return v
    }
    when (args[0]) {
        "decode" -> decode(opts)
        "reencode" -> reencode(opts)
        "encode" -> {
            val chart = opt("--chart")
            val rows = opt("--rows")?.toInt()
            val skipMm = opt("--skip-mm")?.toDouble() ?: 0.0
            val image = opts.getOrNull(0) ?: usage()
            val source = loadSource(File(image), chart, rows, skipMm)
            val outFile = File(opts.getOrNull(1) ?: usage())
            BufferedOutputStream(FileOutputStream(outFile), 1 shl 20).use { write(it, source, File(image).name) }
            println("wrote ${outFile.length()} bytes to $outFile")
        }
        "pcl-pages" -> {
            // Experiment: each image (9782 px wide, any height) is one Max DPI page, sent as-is.
            val host = opt("--host") ?: DEFAULT_HOST
            val sides = opt("--sides")?.toInt() ?: 0
            val rotateBack = opts.remove("--rotate-back")
            if (opts.isEmpty()) usage()
            val pages = opts.mapIndexed { i, f ->
                {
                    val img = ImageIO.read(File(f))
                    val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
                    val src: RasterSource = CroppedImage(px, img.width, img.height, PageFormat.A4.widthPx, img.height, 0, 0)
                    if (rotateBack && i % 2 == 1) envy.maxdpi.Rotated180(src) else src
                }
            }
            println("sending ${pages.size} page(s), &l${sides}S, rotate back: $rotateBack")
            PrinterConnection.send(host) { writePclJob(it, pages, "duplex-test", sides = sides) }
            println("sent")
        }
        "watch" -> {
            // Logs every change in printer state and the job list, once a second.
            val ipp = IppClient(opt("--host") ?: DEFAULT_HOST)
            var last = ""
            val fmt = java.text.SimpleDateFormat("HH:mm:ss")
            while (true) {
                val line = runCatching {
                    val st = ipp.status()
                    val jobs = ipp.jobs("all", 3).joinToString("; ") { "#${it.id} ${it.name.take(20)} ${it.stateName} ${it.reasons}" }
                    "printer=${st.state} reasons=${st.reasons} msg='${st.message}' alerts=${st.alerts} | jobs: $jobs"
                }.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message}" }
                if (line != last) { println("${fmt.format(java.util.Date())} $line"); System.out.flush(); last = line }
                Thread.sleep(1000)
            }
        }
        "calib-sim" -> calibSim()
        "calib-target" -> {
            val host = opt("--host") ?: DEFAULT_HOST
            val quality = Quality.valueOf((opt("--quality") ?: "max").uppercase().replace("MAX", "MAX_DPI"))
            val canvas = envy.maxdpi.Canvas.forQuality(quality)
            val target = object : PageContent {
                override val contentWidth = 1
                override val contentHeight = 1
                override fun open(canvasWidth: Int, canvasHeight: Int, placement: Placement): RasterSource =
                    envy.maxdpi.Calibration.TargetPage(canvasWidth, canvasHeight, canvas.box, quality.dpi)
            }
            println("printing calibration target ($quality) on $host")
            Printing.print(host, "Color calibration target", listOf(target), PrintOptions(quality = quality),
                onStatus = { println("  printer: ${it ?: "OK again"}") })
            println("done")
        }
        "calib-scan" -> {
            val host = opt("--host") ?: DEFAULT_HOST
            val save = File(opt("--save") ?: "/tmp/envytest/calib-scan.jpg")
            val out = File(opt("--out") ?: "/tmp/envytest/calib-correction.txt")
            val jpeg = if (opts.remove("--reuse")) save.readBytes() else {
                println("scanning at 300 dpi..."); val t = System.currentTimeMillis()
                envy.maxdpi.EsclScanner(host).scan(300).also { save.writeBytes(it); println("  ${it.size / 1000} KB in ${(System.currentTimeMillis() - t) / 1000}s -> $save") }
            }
            val img = ImageIO.read(jpeg.inputStream())
            val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            val m = envy.maxdpi.Calibration.measure(px, img.width, img.height, 300)
            report(m)
            val t0 = System.currentTimeMillis()
            val corr = envy.maxdpi.Calibration.buildCorrection(m)
            val (before, after) = envy.maxdpi.Calibration.errors(m, corr)
            println("built correction in ${System.currentTimeMillis() - t0} ms; average color error %.1f -> %.1f (predicted)".format(before, after))
            out.writeText(corr.encode())
            File(out.path + ".measurement").writeText(m.encode())
            println("saved $out (+ .measurement)")
            println("key colors  wanted / now / after:")
            envy.maxdpi.Calibration.preview(m, corr).zip(envy.maxdpi.Calibration.KEY_COLORS).forEach { (p, c) ->
                println("  #%06X  #%06X / #%06X / #%06X".format(c, p.first, p.second, p.third))
            }
        }
        "status" -> {
            val st = IppClient(opt("--host") ?: DEFAULT_HOST).status()
            println("${st.model}: ${st.state} ${st.reasons} ${st.message}")
            st.markers.forEach { println("  ${it.name}: ${if (it.level < 0) "unknown" else "${it.level}%"} (${it.color})") }
        }
        "print" -> {
            val host = opt("--host") ?: DEFAULT_HOST
            val options = PrintOptions(
                quality = Quality.valueOf((opt("--quality") ?: "max").uppercase().replace("MAX", "MAX_DPI")),
                grayscale = opts.remove("--gray"),
                copies = opt("--copies")?.toInt() ?: 1,
                duplex = when (opt("--duplex")) { "long" -> Duplex.LONG_EDGE; "short" -> Duplex.SHORT_EDGE; else -> Duplex.OFF },
                scaling = Scaling.valueOf((opt("--scaling") ?: "fit").uppercase()),
                orientation = Orientation.valueOf((opt("--orientation") ?: "auto").uppercase()),
            )
            if (opts.isEmpty()) usage()
            val pages = opts.map { ImageFileContent(File(it)) }
            println("printing ${pages.size} page(s) on $host with $options")
            val t = System.currentTimeMillis()
            var last = -1
            try {
                Printing.print(host, File(opts[0]).name, pages, options, { f, page, total ->
                    val pct = (f * 100).toInt()
                    if (pct / 2 != last) { last = pct / 2; println("  $pct%  page $page/$total  ${(System.currentTimeMillis() - t) / 1000}s"); System.out.flush() }
                }, onStatus = { println("  ${(System.currentTimeMillis() - t) / 1000}s printer: ${it ?: "OK again"}"); System.out.flush() })
                println("done in ${(System.currentTimeMillis() - t) / 1000}s")
            } catch (e: envy.maxdpi.CanceledOnPrinterException) {
                println("CANCELLED ON THE PRINTER after ${(System.currentTimeMillis() - t) / 1000}s")
            } catch (e: Exception) {
                println("FAILED after ${(System.currentTimeMillis() - t) / 1000}s: ${e.javaClass.name}: ${e.message}")
            }
        }
        "send" -> {
            val host = opt("--host") ?: DEFAULT_HOST
            val file = File(opts.getOrNull(0) ?: usage())
            val data = file.readBytes()
            val start = data.indexOfUel()
            val t = System.currentTimeMillis()
            PrinterConnection.send(host) { out ->
                var sent = start
                while (sent < data.size) {
                    val n = minOf(1 shl 20, data.size - sent)
                    out.write(data, sent, n); out.flush(); sent += n
                    println("  ${(System.currentTimeMillis() - t) / 1000}s ${sent / 1_000_000}/${data.size / 1_000_000} MB")
                }
            }
            println("sent ${data.size - start} bytes in ${(System.currentTimeMillis() - t) / 1000}s")
        }
        else -> usage()
    }
}

fun write(out: OutputStream, source: RasterSource, name: String) {
    val t = System.currentTimeMillis()
    writePclJob(out, listOf { source }, name) { _, row, total ->
        if (row % 2048 == 0 || row == total) println("  row $row/$total  ${(System.currentTimeMillis() - t) / 1000}s")
    }
}

/** An image file as a page (desktop: decoded with ImageIO). */
class ImageFileContent(private val file: File) : PageContent {
    private val image by lazy { ImageIO.read(file) ?: error("cannot read image $file") }
    override val contentWidth get() = image.width
    override val contentHeight get() = image.height
    override fun open(canvasWidth: Int, canvasHeight: Int, placement: Placement): RasterSource {
        val px = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return ImagePage(px, image.width, image.height, canvasWidth, canvasHeight, placement)
    }
}

fun loadSource(file: File, chart: String?, rows: Int?, skipMm: Double): RasterSource {
    val img = ImageIO.read(file) ?: error("cannot read image $file")
    val w = img.width; val h = img.height
    val px = img.getRGB(0, 0, w, h, null, 0, w)
    val page = PageFormat.A4
    val height = minOf(rows ?: page.heightPx, page.heightPx)
    return if (chart != null) {
        val (left, top0) = chart.split(",").map { it.trim().toInt() }
        val top = top0 + Math.round(skipMm * 1200 / 25.4).toInt()
        CroppedImage(px, w, h, page.widthPx, minOf(height, h - top), left, top)
    } else {
        val box = envy.maxdpi.Box(0, 0, page.widthPx, page.heightPx)
        val fitted = ImagePage(px, w, h, page.widthPx, page.heightPx, Placement(w, h, box, Scaling.FIT, Orientation.AUTO))
        object : RasterSource by fitted { override val height = height }
    }
}

fun decode(opts: List<String>) {
    val file = File(opts.getOrNull(0) ?: usage())
    val outFile = File(opts.getOrNull(1) ?: usage())
    val from = opts.getOrNull(2)?.toInt() ?: 0
    val to = opts.getOrNull(3)?.toInt()
    val reader = PclReader(file.readBytes())
    val rows = ArrayList<IntArray>()
    var count = reader.decodePage { y, row -> if (y >= from && (to == null || y < to)) rows += row.copyOf() }
    println("width ${reader.width}, rows $count; header: ${reader.commands.joinToString(" ")}")
    val img = BufferedImage(reader.width, rows.size, BufferedImage.TYPE_INT_RGB)
    rows.forEachIndexed { y, r -> img.setRGB(0, y, reader.width, 1, r, 0, reader.width) }
    ImageIO.write(img, "png", outFile)
    println("wrote rows $from..${from + rows.size} to $outFile")
}

fun reencode(opts: List<String>) {
    val file = File(opts.getOrNull(0) ?: usage())
    val reader = PclReader(file.readBytes())
    var encoder: Mode10.Encoder? = null
    var decoder: Mode10.Decoder? = null
    var original = 0L; var ours = 0L; var sameBytes = 0; var compressedRows = 0; var mismatches = 0
    var lastCompressed: ByteArray? = null
    var maxBlueErr = 0
    val total = reader.decodePage(onCompressed = { _, data, off, len ->
        lastCompressed = data.copyOfRange(off, off + len)
    }) { _, row ->
        val enc = encoder ?: Mode10.Encoder(reader.width).also { encoder = it }
        val dec = decoder ?: Mode10.Decoder(reader.width).also { decoder = it }
        val n = enc.encode(row)
        ours += n
        dec.decode(enc.buffer, 0, n)
        for (x in row.indices) {
            val a = dec.row[x]; val b = row[x]
            if ((a xor b) and 0xFFFF00 != 0) mismatches++
            maxBlueErr = maxOf(maxBlueErr, Math.abs((a and 0xFF) - (b and 0xFF)))
        }
        lastCompressed?.let { orig ->
            compressedRows++
            original += orig.size
            if (orig.size == n && orig.contentEquals(enc.buffer.copyOf(n))) sameBytes++
        }
        lastCompressed = null
    }
    println("rows $total, width ${reader.width}")
    println("our encoder round-trip: red/green pixel errors $mismatches, max blue error $maxBlueErr")
    println("driver rows byte-identical to ours: $sameBytes / $compressedRows")
    println("compressed size: driver $original bytes, ours $ours bytes")
}

fun ByteArray.indexOfUel(): Int {
    val p = "\u001b%-12345X".toByteArray()
    outer@ for (k in 0..size - p.size) {
        for (j in p.indices) if (this[k + j] != p[j]) continue@outer
        return k
    }
    return 0
}

fun report(m: envy.maxdpi.Calibration.Measurement) {
    println("gray ramp as scanned (input -> R G B):")
    for (i in 0 until envy.maxdpi.Calibration.RAMP step 4) {
        val p = m.ramp[i]
        println("  %3d -> %3d %3d %3d".format(envy.maxdpi.Calibration.rampLevel(i), p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF))
    }
}

/**
 * Simulated printer with problems like the real one (cyan in the yellow, strong magenta, black
 * only reaching mid-gray, grays printed neutral), scanned upside down. Checks the analysis and
 * that printing the target through the correction lowers the color error.
 */
fun calibSim() {
    val dpi = 300
    val w = 2550; val h = 3508
    val canvas = envy.maxdpi.Canvas.forQuality(Quality.MAX_DPI)
    val k = dpi / 1200.0
    val box = envy.maxdpi.Box((canvas.box.left * k).toInt() + 40, (canvas.box.top * k).toInt() + 25, (canvas.box.width * k).toInt(), (canvas.box.height * k).toInt())
    val page = envy.maxdpi.Calibration.TargetPage(w, h, box, dpi)
    fun printer(p: Int): Int {
        val r = (p shr 16 and 0xFF) / 255.0; val g = (p shr 8 and 0xFF) / 255.0; val b = (p and 0xFF) / 255.0
        fun out(v: Double) = (119 + 133 * v.coerceIn(0.0, 1.0)).toInt()
        if (p shr 16 and 0xFF == p shr 8 and 0xFF && p shr 8 and 0xFF == p and 0xFF) { val v = out(r); return (v shl 16) or (v shl 8) or v }
        val c = 1 - r; val m = 1 - g; val y = 1 - b
        val c2 = c + 0.25 * y          // cyan rides along with the yellow
        val m2 = minOf(1.0, m * 1.2)   // magenta too strong
        return (out(1 - c2) shl 16) or (out(1 - m2) shl 8) or out(1 - y)
    }
    fun simulate(corr: envy.maxdpi.Calibration.ColorCorrection?): IntArray {
        val px = IntArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            page.readRow(y, row)
            for (x in 0 until w) {
                val p0 = row[x]
                val p = if (p0 == Mode10.WHITE) p0 else (corr?.apply(p0) ?: p0)
                px[(h - 1 - y) * w + (w - 1 - x)] = if (p0 == Mode10.WHITE) 0xFCFCFC else printer(p)  // upside down on the glass
            }
        }
        return px
    }
    val m = envy.maxdpi.Calibration.measure(simulate(null), w, h, dpi)
    report(m)
    val t0 = System.currentTimeMillis()
    val corr = envy.maxdpi.Calibration.buildCorrection(m)
    val (before, predicted) = envy.maxdpi.Calibration.errors(m, corr)
    println("built in ${System.currentTimeMillis() - t0} ms; average color error before %.1f, predicted after %.1f".format(before, predicted))
    val check = envy.maxdpi.Calibration.measure(simulate(corr), w, h, dpi)
    println("printed through the correction and re-measured: %.1f".format(envy.maxdpi.Calibration.checkError(m, check)))
    println("key colors  wanted / now / after:")
    envy.maxdpi.Calibration.preview(m, corr).zip(envy.maxdpi.Calibration.KEY_COLORS).forEach { (p, c) ->
        println("  #%06X  #%06X / #%06X / #%06X".format(c, p.first, p.second, p.third))
    }
}
