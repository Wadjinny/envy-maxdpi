package envy.maxdpi

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Raw printing on TCP port 9100 (what the HP Windows driver uses).
 *
 * Do not send these jobs over IPP as application/vnd.hp-PCL: on the ENVY 6030 that froze the
 * printer and dropped it off Wi-Fi. The printer accepts data only as fast as it prints, so a
 * Max DPI A4 page takes ~6 minutes; the write timeout must be long.
 */
object PrinterConnection {
    const val RAW_PORT = 9100

    /** [onOpen] gets the socket so another thread can close it to abort a write the printer is holding up. */
    fun <T> send(host: String, port: Int = RAW_PORT, onOpen: (Socket) -> Unit = {}, block: (OutputStream) -> T): T {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 10_000)
            onOpen(socket)
            socket.soTimeout = 15 * 60_000
            socket.sendBufferSize = 256 * 1024
            val out = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
            val result = block(out)
            out.flush()
            socket.shutdownOutput()
            return result
        }
    }
}
