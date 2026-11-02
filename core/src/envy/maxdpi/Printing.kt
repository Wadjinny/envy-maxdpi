package envy.maxdpi

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

enum class Quality(val dpi: Int, val ippQuality: Int) {
    /** Windows driver "Max DPI": PCL3GUI at 1200 dpi over port 9100. ~6 min per A4 page. */
    MAX_DPI(1200, 5),
    /** The printer's own modes through IPP (PWG raster). */
    BEST(600, 5),
    NORMAL(600, 4),
    DRAFT(300, 3),
}

enum class Duplex(val ippSides: String) {
    OFF("one-sided"),
    LONG_EDGE("two-sided-long-edge"),
    SHORT_EDGE("two-sided-short-edge"),
}

data class PrintOptions(
    val quality: Quality = Quality.MAX_DPI,
    val grayscale: Boolean = false,
    val copies: Int = 1,
    val duplex: Duplex = Duplex.OFF,
    /** Color calibration for this quality mode (from [Calibration]); null prints uncorrected. */
    val correction: Calibration.ColorCorrection? = null,
    val scaling: Scaling = Scaling.FIT,
    val orientation: Orientation = Orientation.AUTO,
)

/** One page of a document (a photo, or one PDF page), laid out onto a canvas when printed. */
interface PageContent {
    /** Content size in any unit; only the aspect ratio matters for layout. */
    val contentWidth: Int
    val contentHeight: Int
    fun open(canvasWidth: Int, canvasHeight: Int, placement: Placement): RasterSource
}

/** Pixel canvas and printable box for a quality mode. */
data class Canvas(val width: Int, val height: Int, val box: Box) {
    companion object {
        fun forQuality(q: Quality): Canvas = when (q) {
            // The Windows driver's raster already is the printable area.
            Quality.MAX_DPI -> PageFormat.A4.let { Canvas(it.widthPx, it.heightPx, Box(0, 0, it.widthPx, it.heightPx)) }
            else -> {
                // Full A4 page; the printer reports 2.96 mm margins on every side.
                val w = Math.round(8.2677 * q.dpi).toInt() and 1.inv()
                val h = Math.round(11.6929 * q.dpi).toInt() and 1.inv()
                val m = Math.round(2.96 / 25.4 * q.dpi).toInt()
                Canvas(w, h, Box(m, m, w - 2 * m, h - 2 * m))
            }
        }
    }
}

/** The job was cancelled on the printer's own panel. */
class CanceledOnPrinterException : IOException("Cancelled on the printer")

object Printing {
    fun interface Progress {
        /** [fraction] 0..1 over the whole job; [page] is 1-based among [pages] sheets sides. */
        fun update(fraction: Double, page: Int, pages: Int)
    }

    /**
     * Prints [pages] on the printer at [host] and blocks until the printer reports the job
     * finished (or, if its status pages can't be read, until it has taken all the data).
     *
     * [onStatus] gets plain-language printer problems while printing ("A door is open...") and
     * null once they clear; printing resumes on its own. Throws [CancellationException] when
     * [isCancelled] turns true and [CanceledOnPrinterException] when cancelled on the printer.
     */
    fun print(
        host: String, jobName: String, pages: List<PageContent>, options: PrintOptions,
        progress: Progress = Progress { _, _, _ -> }, isCancelled: () -> Boolean = { false },
        onStatus: (String?) -> Unit = {},
    ) {
        require(pages.isNotEmpty()) { "nothing to print" }
        val canvas = Canvas.forQuality(options.quality)
        val duplex = options.duplex

        // Collated copies; with two-sided printing, start each copy on a fresh sheet.
        val sequence = ArrayList<PageContent?>()
        repeat(options.copies.coerceIn(1, 99)) {
            sequence += pages
            if (duplex != Duplex.OFF && pages.size % 2 == 1 && options.copies > 1) sequence += null
        }

        val sides = sequence.mapIndexed { i, content ->
            {
                var src: RasterSource = content?.let {
                    it.open(canvas.width, canvas.height,
                        Placement(it.contentWidth, it.contentHeight, canvas.box, options.scaling, options.orientation))
                } ?: BlankPage(canvas.width, canvas.height)
                if (options.grayscale) src = Grayscale(src)
                options.correction?.let { src = Corrected(src, it) }
                // The printer prints back sides from the far end of the sheet, so for long-edge
                // binding they're sent upside down (IPP: pwg-raster-document-sheet-back=rotated;
                // Max DPI: confirmed with test sheets). Short edge needs no rotation.
                if (duplex == Duplex.LONG_EDGE && i % 2 == 1) src = Rotated180(src)
                src
            }
        }

        fun report(index: Int, row: Int, rows: Int) {
            if (isCancelled()) throw CancellationException()
            progress.update((index + row.toDouble() / rows) / sides.size, index + 1, sides.size)
        }

        val monitor = JobMonitor(host, isCancelled, onStatus)
        try {
            try {
                send(host, jobName, sides, options, monitor, isCancelled, ::report)
            } catch (e: IOException) {
                // A connection we closed ourselves, or the printer's IPP "job canceled" reply.
                if (monitor.canceledOnPrinter || (e is IppClient.IppException && e.statusCode == 0x0508)) throw CanceledOnPrinterException()
                if (isCancelled()) throw CancellationException()
                throw e
            }
            // The printer buffers data, so the job is still printing; wait for its verdict.
            if (monitor.awaitFinish() == "Canceled") throw CanceledOnPrinterException()
        } finally {
            monitor.close()
        }
    }

    private fun send(
        host: String, jobName: String, sides: List<() -> RasterSource>, options: PrintOptions,
        monitor: JobMonitor, isCancelled: () -> Boolean, report: (Int, Int, Int) -> Unit,
    ) {
        val duplex = options.duplex
        if (options.quality == Quality.MAX_DPI) {
            // &l1S turns on automatic two-sided printing; the edge is set by the rotation above.
            val pclSides = if (duplex == Duplex.OFF) 0 else 1
            PrinterConnection.send(host, onOpen = { s -> monitor.abort = { runCatching { s.close() } } }) { out ->
                writePclJob(out, sides, jobName, sides = pclSides, onProgress = report)
            }
            return
        }

        val ipp = IppClient(host)
        monitor.abort = { ipp.abort() }
        waitUntilIdle(ipp, isCancelled)
        ipp.printJob(
            IppClient.JobAttributes(
                jobName = jobName,
                documentFormat = "image/pwg-raster",
                quality = options.quality.ippQuality,
                dpi = options.quality.dpi,
                colorMode = if (options.grayscale) "monochrome" else "color",
                sides = duplex.ippSides,
            ),
            isCancelled,
        ) { out ->
            val writer = PwgRasterWriter(out)
            sides.forEachIndexed { i, open ->
                RowCounter(open()) { row, rows -> report(i, row, rows) }.use { counted -> writer.page(counted, PwgRasterWriter.PageSettings(
                    dpi = options.quality.dpi,
                    gray = options.grayscale,
                    quality = options.quality.ippQuality,
                    totalPages = sides.size,
                    duplex = duplex != Duplex.OFF,
                    tumble = duplex == Duplex.SHORT_EDGE,
                )) }
            }
            writer.finish()
        }
    }

    /**
     * Watches the printer's LEDM status pages during a job (every 2 s, on its own thread):
     * reports problems, notices a cancel on the printer, and aborts the transfer when the job
     * is cancelled on either side. If LEDM can't be read, printing just goes ahead without it.
     */
    private class JobMonitor(
        host: String, private val isCancelled: () -> Boolean, private val onStatus: (String?) -> Unit,
    ) : AutoCloseable {
        private val ledm = HpLedm(host)
        // Jobs newer than this one are ours.
        private val baseline: Int? = runCatching { ledm.jobs().maxOfOrNull { it.id } ?: 0 }.getOrNull()
        @Volatile var abort: () -> Unit = {}
        @Volatile var canceledOnPrinter = false
        @Volatile private var jobState: String? = null
        @Volatile private var running = true
        private var lastStatus: String? = null

        private val worker = thread(isDaemon = true, name = "printer-monitor") {
            while (running) {
                poll()
                if (isCancelled() || canceledOnPrinter) runCatching { abort() }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }

        private fun poll() {
            runCatching { HpLedm.attention(ledm.status()) }.onSuccess { msg ->
                if (msg != lastStatus) { lastStatus = msg; onStatus(msg) }
            }
            val since = baseline ?: return
            runCatching {
                ledm.jobs().filter { it.id > since }.maxByOrNull { it.id }?.let {
                    jobState = it.state
                    if (it.state == "Canceled") canceledOnPrinter = true
                }
            }
        }

        /** Waits until the printer reports our job done; returns its final state (null if unknown). */
        fun awaitFinish(timeoutMs: Long = 15 * 60_000): String? {
            if (baseline == null) return null
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs && !isCancelled()) {
                if (canceledOnPrinter) return "Canceled"
                when (jobState) { "Completed", "Canceled", "Aborted" -> return jobState }
                // Never saw our job (status pages not answering): don't hold the user up.
                if (jobState == null && System.currentTimeMillis() - start > 60_000) return null
                Thread.sleep(1000)
            }
            return jobState
        }

        override fun close() {
            running = false
            worker.interrupt()
            if (lastStatus != null) onStatus(null)
        }
    }

    private fun waitUntilIdle(ipp: IppClient, isCancelled: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10 * 60_000
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) throw CancellationException()
            if (runCatching { ipp.status().state }.getOrNull() != "printing") return
            Thread.sleep(3000)
        }
    }

    private class RowCounter(private val source: RasterSource, private val onRow: (Int, Int) -> Unit) : RasterSource by source {
        override fun readRow(y: Int, out: IntArray) {
            if (y % 128 == 0) onRow(y, source.height)
            source.readRow(y, out)
        }
    }
}
