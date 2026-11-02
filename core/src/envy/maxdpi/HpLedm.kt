package envy.maxdpi

import java.net.HttpURLConnection
import java.net.URL

/**
 * HP's LEDM status pages on the printer's web server (what HP's own apps use).
 *
 * Unlike IPP, LEDM lists jobs sent over raw port 9100 (Max DPI) and records when one is
 * cancelled on the printer's panel; IPP shows the printer as idle during those jobs.
 * Measured on the ENVY 6030: cancel -> job state "Canceled"; door open -> status
 * "closeDoorOrCover" and job state "ProcessingError" until the door closes.
 */
class HpLedm(host: String) {
    private val base = "http://" + (if (':' in host && !host.startsWith("[")) "[$host]" else host)

    data class Status(val categories: List<String>)
    data class Job(val id: Int, val state: String)

    fun status(): Status =
        Status(Regex("<pscat:StatusCategory>([^<]+)<").findAll(get("/DevMgmt/ProductStatusDyn.xml")).map { it.groupValues[1] }.toList())

    fun jobs(): List<Job> =
        Regex("""<j:JobUrl>/Jobs/JobList/(\d+)</j:JobUrl>.*?<j:JobState>([^<]+)<""", RegexOption.DOT_MATCHES_ALL)
            .findAll(get("/Jobs/JobList"))
            .map { Job(it.groupValues[1].toInt(), it.groupValues[2]) }
            .toList()

    private fun get(path: String): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Categories that are informational and never stop printing. */
        private val INFO = setOf(
            "ready", "processing", "inPowerSave", "initializing", "busy", "scanProcessing",
            "usedConsumable", "nonHPSupply", "lowOnInk", "veryLowOnInk", "cartridgeLow", "cartridgeVeryLow",
        )

        private val TEXT = mapOf(
            "closeDoorOrCover" to "A door is open. Close it to continue.",
            "trayEmptyOrOpen" to "The paper tray is empty or open. Load paper or close the tray to continue.",
            "trayEmpty" to "Out of paper. Load paper to continue.",
            "mediaEmpty" to "Out of paper. Load paper to continue.",
            "outOfPaper" to "Out of paper. Load paper to continue.",
            "trayOpen" to "The paper tray is open. Close it to continue.",
            "jamInPrinter" to "Paper jam. Clear it to continue.",
            "mediaJam" to "Paper jam. Clear it to continue.",
            "paperJam" to "Paper jam. Clear it to continue.",
            "cartridgeMissing" to "A cartridge is missing.",
            "incompatibleConsumable" to "A cartridge isn't compatible with this printer.",
            "cartridgeFailure" to "A cartridge has a problem.",
            "inkSystemFailure" to "Ink system problem. Turn the printer off and on.",
            "carriageStall" to "The print carriage is stuck. Check for paper inside.",
            "cancelJob" to "Cancelling on the printer…",
            "outputBinFull" to "The output tray is full.",
            "inPowerSave" to "Sleeping",
            "usedConsumable" to "A refilled or used HP cartridge is installed.",
            "lowOnInk" to "Ink is low.",
            "veryLowOnInk" to "Ink is very low.",
        )

        /** Plain-language text for a status category; unknown ones are turned into words. */
        fun describe(category: String): String = TEXT[category]
            ?: category.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() } + "."

        /** What needs the user's attention right now, or null when the printer can carry on. */
        fun attention(status: Status): String? =
            status.categories.filter { it !in INFO }.distinct().joinToString(" ") { describe(it) }.ifEmpty { null }
    }
}
