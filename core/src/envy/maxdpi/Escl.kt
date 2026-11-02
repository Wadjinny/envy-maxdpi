package envy.maxdpi

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * eSCL (AirScan / Mopria scan) client for the printer's flatbed: one platen scan, returned as
 * JPEG bytes. The ENVY 6000 offers RGB24 at 75-1200 dpi over plain HTTP.
 */
class EsclScanner(host: String) {
    private val base = "http://" + (if (':' in host && !host.startsWith("[")) "[$host]" else host)

    /** Scans the whole glass in color at [dpi]. Blocks until the image is ready (~20-60 s). */
    fun scan(dpi: Int = 300): ByteArray {
        val settings = """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
  <pwg:Version>2.0</pwg:Version>
  <pwg:ScanRegions>
    <pwg:ScanRegion>
      <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
      <pwg:Width>2550</pwg:Width>
      <pwg:Height>3508</pwg:Height>
      <pwg:XOffset>0</pwg:XOffset>
      <pwg:YOffset>0</pwg:YOffset>
    </pwg:ScanRegion>
  </pwg:ScanRegions>
  <pwg:InputSource>Platen</pwg:InputSource>
  <scan:ColorMode>RGB24</scan:ColorMode>
  <scan:XResolution>$dpi</scan:XResolution>
  <scan:YResolution>$dpi</scan:YResolution>
  <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
  <scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>
</scan:ScanSettings>"""
        val job = request("POST", "/eSCL/ScanJobs", settings.toByteArray()) { conn ->
            if (conn.responseCode != 201) throw IOException("The scanner refused the scan (HTTP ${conn.responseCode}).")
            conn.getHeaderField("Location") ?: throw IOException("The scanner didn't start a scan job.")
        }
        val path = URL(URL(base), job).path.trimEnd('/') + "/NextDocument"
        // The scanner answers 503 while it's still warming up or scanning.
        val deadline = System.currentTimeMillis() + 3 * 60_000
        while (true) {
            val result = request("GET", path, null) { conn ->
                when (conn.responseCode) {
                    200 -> conn.inputStream.use { it.readBytes() }
                    503 -> null
                    else -> throw IOException("The scan failed (HTTP ${conn.responseCode}).")
                }
            }
            if (result != null) return result
            if (System.currentTimeMillis() > deadline) throw IOException("The scanner didn't finish in time.")
            Thread.sleep(1000)
        }
    }

    private fun <T> request(method: String, path: String, body: ByteArray?, handle: (HttpURLConnection) -> T): T {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = false
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/xml")
                conn.outputStream.use { it.write(body) }
            }
            return handle(conn)
        } finally {
            conn.disconnect()
        }
    }
}
