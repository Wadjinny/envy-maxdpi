package envy.maxdpi.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Finds the printer on the network. Not every phone can resolve `.local` names, so Bonjour (NSD) is the fallback. */
object PrinterFinder {
    data class Found(val name: String, val address: String)

    private var lastSearch: Pair<Long, Found?>? = null

    /**
     * Turns what the user typed into an IP address. Blocking. Falls back to a Bonjour search for
     * `.local` names; its result is reused for 5 minutes (1 minute if nothing was found) so status
     * polling doesn't keep the phone searching.
     */
    fun resolve(context: Context, host: String): String {
        // Prefer IPv4: some phones resolve .local names to the printer's IPv6 link-local address first.
        runCatching { return pick(InetAddress.getAllByName(host).toList())!! }
        if (host.endsWith(".local")) {
            val now = System.currentTimeMillis()
            val cached = lastSearch?.takeIf { (at, found) -> now - at < if (found != null) 300_000 else 60_000 }
            val found = if (cached != null) cached.second else discover(context).also { lastSearch = now to it }
            found?.let { return it.address }
        }
        throw IllegalStateException("Can't find \"$host\" on this network")
    }

    private fun pick(addrs: List<InetAddress>): String? =
        (addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull())?.hostAddress

    /** Looks for raw-print (port 9100) printers advertised over Bonjour. Blocking, up to [timeoutMs]. */
    fun discover(context: Context, timeoutMs: Long = 8000): Found? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val done = CountDownLatch(1)
        var result: Found? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val addrs = if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses
                        else @Suppress("DEPRECATION") listOfNotNull(resolved.host)
                        val addr = pick(addrs) ?: return
                        if (result == null) result = Found(resolved.serviceName, addr)
                        done.countDown()
                    }
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) {}
                })
            }
            override fun onStartDiscoveryFailed(type: String, error: Int) { done.countDown() }
            override fun onDiscoveryStarted(type: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
        }
        nsd.discoverServices("_pdl-datastream._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return result
    }
}
