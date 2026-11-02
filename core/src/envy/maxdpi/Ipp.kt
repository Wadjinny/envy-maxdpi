package envy.maxdpi

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal IPP/1.1 client: printer status and Print-Job with a streamed document. */
class IppClient(host: String, private val port: Int = 631, private val path: String = "/ipp/print") {
    /** IPv6 literals need brackets. Java's HTTP client takes the zone raw ("%wlan0"); a URI encodes it ("%25wlan0"). */
    private val urlHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
    private val printerUri = "ipp://${urlHost.replace("%", "%25")}:$port$path"

    data class Marker(val name: String, val level: Int, val color: String)
    data class Status(
        val model: String,
        val state: String,
        val reasons: List<String>,
        val message: String,
        val markers: List<Marker>,
        /** Human-readable alerts the printer shows on its panel (e.g. "Ready", "Out of paper"). */
        val alerts: List<String> = emptyList(),
    )

    data class Job(val id: Int, val name: String, val state: Int, val reasons: List<String>) {
        val stateName get() = when (state) {
            3 -> "pending"; 4 -> "held"; 5 -> "processing"; 6 -> "stopped"
            7 -> "canceled"; 8 -> "aborted"; 9 -> "completed"; else -> "unknown($state)"
        }
        val finished get() = state >= 7
    }

    class IppException(val statusCode: Int, message: String) : IOException(message)

    @Volatile private var active: HttpURLConnection? = null

    /** Aborts a request in progress (from another thread), e.g. a document upload. */
    fun abort() { active?.disconnect() }

    fun status(): Status {
        val req = Request(0x000B)
        req.operation()
        req.keywords("requested-attributes", listOf(
            "printer-make-and-model", "printer-state", "printer-state-reasons", "printer-state-message",
            "marker-names", "marker-levels", "marker-colors", "printer-alert-description",
        ))
        val attrs = send(req.end(), readTimeoutMs = 5_000) { }
        fun s(name: String) = attrs[name]?.map { it.toString() } ?: emptyList()
        val names = s("marker-names"); val levels = attrs["marker-levels"] ?: emptyList(); val colors = s("marker-colors")
        return Status(
            model = s("printer-make-and-model").firstOrNull() ?: "",
            state = when (attrs["printer-state"]?.firstOrNull()) { 3 -> "idle"; 4 -> "printing"; 5 -> "stopped"; else -> "unknown" },
            reasons = s("printer-state-reasons").filter { it != "none" },
            message = s("printer-state-message").firstOrNull() ?: "",
            markers = names.indices.map { Marker(names[it], levels.getOrNull(it) as? Int ?: -1, colors.getOrNull(it) ?: "") },
            alerts = s("printer-alert-description").filter { it.isNotBlank() },
        )
    }

    /** The printer's job list, newest first. [which]: "all", "completed" or "not-completed". */
    fun jobs(which: String = "all", limit: Int = 5): List<Job> {
        val req = Request(0x000A)
        req.operation()
        req.int(0x21, "limit", limit)
        req.attr(0x44, "which-jobs", which)
        req.keywords("requested-attributes", listOf("job-id", "job-name", "job-state", "job-state-reasons"))
        return sendGroups(req.end(), readTimeoutMs = 5_000) { }
            .filter { it.first == 0x02 }
            .map { (_, a) ->
                Job(
                    id = a["job-id"]?.firstOrNull() as? Int ?: 0,
                    name = a["job-name"]?.firstOrNull()?.toString() ?: "",
                    state = a["job-state"]?.firstOrNull() as? Int ?: 0,
                    reasons = a["job-state-reasons"]?.map { it.toString() } ?: emptyList(),
                )
            }
            .sortedByDescending { it.id }
    }

    data class JobAttributes(
        val jobName: String,
        val documentFormat: String,
        val quality: Int,             // 3 draft, 4 normal, 5 high
        val dpi: Int,
        val colorMode: String,        // "color" or "monochrome"
        val sides: String,            // one-sided, two-sided-long-edge, two-sided-short-edge
        val media: String = "iso_a4_210x297mm",
    )

    /** Sends Print-Job; [writeDocument] streams the document. Retries while the printer is busy. */
    fun printJob(job: JobAttributes, isCancelled: () -> Boolean = { false }, writeDocument: (OutputStream) -> Unit): Int {
        val req = Request(0x0002)
        req.operation()
        req.attr(0x42, "requesting-user-name", "android")
        req.attr(0x42, "job-name", job.jobName.take(200))
        req.attr(0x49, "document-format", job.documentFormat)
        req.group(0x02)
        req.int(0x23, "print-quality", job.quality)
        req.resolution("printer-resolution", job.dpi)
        req.attr(0x44, "print-color-mode", job.colorMode)
        req.attr(0x44, "sides", job.sides)
        req.attr(0x44, "media", job.media)
        val body = req.end()
        val deadline = System.currentTimeMillis() + 10 * 60_000
        while (true) {
            try {
                val attrs = send(body, writeDocument = writeDocument)
                return (attrs["job-id"]?.firstOrNull() as? Int) ?: 0
            } catch (e: IppException) {
                // server-error-busy: the printer is still on a previous job.
                if (e.statusCode != 0x0507 || System.currentTimeMillis() > deadline || isCancelled()) throw e
                Thread.sleep(5000)
            }
        }
    }

    private fun send(request: ByteArray, readTimeoutMs: Int = 15 * 60_000, writeDocument: (OutputStream) -> Unit): Map<String, List<Any>> {
        val merged = LinkedHashMap<String, List<Any>>()
        sendGroups(request, readTimeoutMs, writeDocument).forEach { merged.putAll(it.second) }
        return merged
    }

    private fun sendGroups(request: ByteArray, readTimeoutMs: Int, writeDocument: (OutputStream) -> Unit): List<Pair<Int, Map<String, List<Any>>>> {
        val conn = URL("http://$urlHost:$port$path").openConnection() as HttpURLConnection
        active = conn
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = minOf(10_000, readTimeoutMs)
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/ipp")
            conn.setChunkedStreamingMode(64 * 1024)
            conn.outputStream.buffered(256 * 1024).use { out ->
                out.write(request)
                writeDocument(out)
            }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} from printer")
            val data = conn.inputStream.use { it.readBytes() }
            return parse(data)
        } finally {
            conn.disconnect()
        }
    }

    /** Attribute groups in order, as (group tag, attributes); 0x02 groups are jobs. */
    private fun parse(data: ByteArray): List<Pair<Int, Map<String, List<Any>>>> {
        val input = DataInputStream(data.inputStream())
        input.readShort() // version
        val status = input.readShort().toInt() and 0xFFFF
        input.readInt()   // request id
        val groups = mutableListOf<Pair<Int, Map<String, List<Any>>>>()
        var attrs = LinkedHashMap<String, MutableList<Any>>()
        var current: MutableList<Any>? = null
        while (input.available() > 0) {
            val tag = input.readUnsignedByte()
            if (tag == 0x03) break
            if (tag < 0x10) { // group delimiter
                attrs = LinkedHashMap()
                groups += tag to attrs
                current = null
                continue
            }
            val nameLen = input.readUnsignedShort()
            val name = String(ByteArray(nameLen).also { input.readFully(it) }, Charsets.UTF_8)
            val value = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
            val v: Any = when (tag) {
                0x21, 0x23 -> if (value.size == 4) java.nio.ByteBuffer.wrap(value).int else 0
                0x22 -> value.firstOrNull()?.toInt() == 1
                in 0x40..0x4F -> String(value, Charsets.UTF_8)
                else -> value
            }
            if (nameLen > 0) current = attrs.getOrPut(name) { mutableListOf() }
            current?.add(v)
        }
        if (status >= 0x0100) {
            val msg = groups.firstNotNullOfOrNull { it.second["status-message"]?.firstOrNull() }?.toString() ?: ""
            throw IppException(status, "printer refused the request (IPP status 0x%04x) %s".format(status, msg).trim())
        }
        return groups
    }

    private inner class Request(operation: Int) {
        private val buf = ByteArrayOutputStream()
        init {
            buf.write(1); buf.write(1)            // IPP/1.1
            short(operation)
            buf.write(byteArrayOf(0, 0, 0, 1))   // request id
        }
        fun operation() {
            group(0x01)
            attr(0x47, "attributes-charset", "utf-8")
            attr(0x48, "attributes-natural-language", "en")
            attr(0x45, "printer-uri", printerUri)
        }
        fun group(tag: Int) = buf.write(tag)
        fun attr(tag: Int, name: String, value: String) = raw(tag, name, value.toByteArray(Charsets.UTF_8))
        fun keywords(name: String, values: List<String>) = values.forEachIndexed { i, v ->
            raw(0x44, if (i == 0) name else "", v.toByteArray(Charsets.UTF_8))
        }
        fun int(tag: Int, name: String, v: Int) = raw(tag, name, java.nio.ByteBuffer.allocate(4).putInt(v).array())
        fun resolution(name: String, dpi: Int) =
            raw(0x32, name, java.nio.ByteBuffer.allocate(9).putInt(dpi).putInt(dpi).put(3).array())
        fun end(): ByteArray { buf.write(0x03); return buf.toByteArray() }
        private fun raw(tag: Int, name: String, value: ByteArray) {
            buf.write(tag)
            val n = name.toByteArray(Charsets.UTF_8)
            short(n.size); buf.write(n)
            short(value.size); buf.write(value)
        }
        private fun short(v: Int) { buf.write(v shr 8 and 0xFF); buf.write(v and 0xFF) }
    }
}
