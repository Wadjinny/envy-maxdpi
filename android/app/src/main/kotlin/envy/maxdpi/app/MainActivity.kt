package envy.maxdpi.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import envy.maxdpi.Canvas as PageCanvas
import envy.maxdpi.Duplex
import envy.maxdpi.HpLedm
import envy.maxdpi.IppClient
import envy.maxdpi.Orientation
import envy.maxdpi.Placement
import envy.maxdpi.PrintOptions
import envy.maxdpi.Quality
import envy.maxdpi.Scaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** UI state that should survive rotation: kept for the life of the process. */
enum class Screen { PRINT, CALIBRATE, PRINTER }

object AppState {
    var screen by mutableStateOf(Screen.PRINT)
    val docs = mutableStateListOf<Doc>()
    val thumbs = mutableStateMapOf<String, Bitmap>()
    var options by mutableStateOf(PrintOptions())
    var pageRange by mutableStateOf("")
    var previewIndex by mutableStateOf(0)
    var host by mutableStateOf("printer.local")
    var status by mutableStateOf<IppClient.Status?>(null)
    var statusError by mutableStateOf<String?>(null)
    /** What the printer needs from the user (LEDM), e.g. "A door is open. Close it to continue." */
    var attention by mutableStateOf<String?>(null)
    /** LEDM says the printer is working (also covers Max DPI jobs, which IPP doesn't show). */
    var busy by mutableStateOf(false)
    var importing by mutableStateOf(0)
    var importError by mutableStateOf<String?>(null)
    var finding by mutableStateOf(false)

    val pages: List<PageRef> get() = docs.flatMap { d -> (0 until d.pageCount).map { d.page(it) } }

    val io = Executors.newFixedThreadPool(2)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The gradient header sits under the status bar, so its icons are always light.
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        AppState.host = prefs().getString("host", "printer.local") ?: "printer.local"
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        handleIncoming(intent)
        setContent { SoftTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Files shared to the app, or a PDF opened with it. */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        importAll(this, uris)
    }

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)
}

fun importAll(context: Context, uris: List<Uri>) {
    val app = context.applicationContext
    uris.forEach { uri ->
        AppState.importing++
        AppState.io.execute {
            val doc = runCatching { Documents.import(app, uri) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                AppState.importing--
                doc.onSuccess { AppState.docs += it; AppState.importError = null }
                doc.onFailure { AppState.importError = "Couldn't open a file: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }
}

@Composable
fun App() {
    StatusPoller()
    Column(Modifier.fillMaxSize().background(Tokens.bg)) {
        Box(Modifier.weight(1f)) {
            when (AppState.screen) {
                Screen.PRINT -> PrintTab()
                Screen.CALIBRATE -> CalibrationScreen()
                Screen.PRINTER -> PrinterTab()
            }
            StatusBarScrim()
        }
        TabBar(
            listOf(
                Triple(Screen.PRINT, "Print", painterResource(R.drawable.ic_stat_print)),
                Triple(Screen.CALIBRATE, "Calibrate", painterResource(R.drawable.ic_tune)),
                Triple(Screen.PRINTER, "Printer", painterResource(R.drawable.ic_settings)),
            ),
            AppState.screen,
        ) { AppState.screen = it }
    }
}

/** Keeps the printer status fresh for every tab (header tiles, printer card, attention banner). */
@Composable
private fun StatusPoller() {
    val context = LocalContext.current
    val host = AppState.host
    LaunchedEffect(host) {
        while (true) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val address = PrinterFinder.resolve(context, host)
                    val ipp = IppClient(address).status()
                    // LEDM is optional: older or non-HP printers just won't have it.
                    val ledm = runCatching { HpLedm(address).status() }.getOrNull()
                    Triple(ipp, ledm?.let { HpLedm.attention(it) }, ledm?.categories?.contains("processing") == true)
                }
            }
            result.onSuccess { (ipp, attention, busy) ->
                AppState.status = ipp; AppState.statusError = null
                AppState.attention = attention; AppState.busy = busy
            }.onFailure {
                AppState.status = null; AppState.attention = null; AppState.busy = false
                AppState.statusError = when (it) {
                    is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
                        "Can't reach the printer. Check that it's on and on the same Wi-Fi."
                    else -> it.message ?: "Printer not reachable"
                }
            }
            delay(if (PrintState.current.active) 10_000 else 20_000)
        }
    }

}

/** Short printer state and its color, shared by the header tiles and the printer card. */
fun printerState(): Pair<String, Tone> {
    val status = AppState.status
    return when {
        status == null && AppState.statusError == null -> "Checking" to Tone.NEUTRAL
        status == null -> "Offline" to Tone.ERROR
        AppState.attention != null -> "Attention" to Tone.WARN
        status.state == "printing" || AppState.busy -> "Printing" to Tone.BUSY
        status.state == "idle" -> "Ready" to Tone.OK
        else -> status.state.replaceFirstChar { it.uppercase() } to Tone.NEUTRAL
    }
}

/** Glass tiles in the header: printer state, black ink, color ink. */
@Composable
fun RowScope.PrinterTiles() {
    val (text, tone) = printerState()
    val dot = when (tone) {
        Tone.OK -> Color(0xFF4ADE80); Tone.WARN -> Color(0xFFFBBF24); Tone.ERROR -> Color(0xFFFDA4AF)
        Tone.BUSY -> Color.White; Tone.NEUTRAL -> Color.White.copy(alpha = 0.6f)
    }
    val markers = AppState.status?.markers.orEmpty()
    fun level(black: Boolean) = markers.firstOrNull { it.name.contains("black", ignoreCase = true) == black }
        ?.let { if (it.level >= 0) "${it.level}%" else "—" } ?: "—"
    GlassTile(text, "Printer", dot)
    GlassTile(level(true), "Black ink")
    GlassTile(level(false), "Color ink")
}

@Composable
private fun PrintTab() {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importAll(context, uris)
    }
    val print = PrintState.current
    val pages = AppState.pages
    val selection = parseRange(AppState.pageRange, pages.size)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            GradientHeader("Envy Max DPI", "HP ENVY 6000 · up to 1200 × 1200 dpi", painterResource(R.drawable.ic_stat_print),
                tiles = { PrinterTiles() })
            Column(Modifier.offset(y = -OVERLAP).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AttentionBanner()
                DocumentCard(pages, onAdd = { picker.launch(arrayOf("image/*", "application/pdf")) })
                if (pages.isNotEmpty()) SettingsCard(pages.size, selection)
            }
        }
        BottomBar(print, selection, pages)
    }
}

/** A colored banner when the printer needs something or can't be reached. */
@Composable
private fun AttentionBanner() {
    val attention = AppState.attention
    val offline = AppState.status == null && AppState.statusError != null
    if (attention == null && !offline) return
    val (bg, fg) = if (attention != null) Tokens.warnBg to Tokens.warnFg else Tokens.errBg to Tokens.errFg
    Row(
        Modifier.fillMaxWidth().clip(Tokens.card).background(bg).clickable { AppState.screen = Screen.PRINTER }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(Tokens.ctrl).background(fg.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text("!", color = fg, fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
        Text(attention ?: (AppState.statusError ?: ""), color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp,
            modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun PrinterTab() {
    val status = AppState.status
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GradientHeader("Printer", status?.model?.ifEmpty { null } ?: AppState.host, painterResource(R.drawable.ic_settings),
            tiles = { PrinterTiles() })
        Column(Modifier.offset(y = -OVERLAP).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PrinterCard()
        }
    }
}

@Composable
private fun PrinterCard() {
    val context = LocalContext.current
    val status = AppState.status
    val host = AppState.host

    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Bubble(accent = Tokens.indigo) { Icon(painterResource(R.drawable.ic_stat_print), null, tint = Tokens.indigo, modifier = Modifier.size(20.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(status?.model?.ifEmpty { null } ?: "Printer", style = Type.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(host, style = Type.label.copy(fontSize = 12.sp), maxLines = 1)
            }
            val (stateText, tone) = printerState()
            StatusPill(stateText, tone)
        }
        AppState.attention?.let {
            Text(it, style = Type.strong, modifier = Modifier.padding(top = 12.dp))
        }
        if (status != null && status.markers.isNotEmpty()) {
            Divider()
            status.markers.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Text(m.name.replaceFirstChar { it.uppercase() }, style = Type.body, modifier = Modifier.width(140.dp), maxLines = 1)
                    SoftProgress(if (m.level >= 0) m.level / 100f else 0f, Modifier.weight(1f))
                    Text(if (m.level >= 0) "${m.level}%" else "—", style = Type.strong, modifier = Modifier.padding(start = 12.dp).width(40.dp))
                }
            }
            if (status.markers.any { it.level < 0 }) {
                Text("A dash means the printer can't read that cartridge's level (common with refilled ones).", style = Type.label)
            }
        } else if (AppState.statusError != null) {
            Text(AppState.statusError!!, style = Type.label, modifier = Modifier.padding(top = 10.dp))
        }
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            var draft by remember(host) { mutableStateOf(host) }
            SoftField(draft, { draft = it.trim() }, "printer.local or IP address", Modifier.weight(1f), KeyboardType.Uri)
            Bubble(46.dp, tint = draft != host, onClick = {
                AppState.host = draft
                context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("host", draft).apply()
                AppState.status = null; AppState.statusError = null
            }) { Icon(Icons.Default.Refresh, "Connect", tint = if (draft != host) Tokens.indigo else Tokens.g500) }
            Bubble(46.dp, tint = false, onClick = {
                if (!AppState.finding) {
                    AppState.finding = true
                    AppState.io.execute {
                        val found = PrinterFinder.discover(context)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            AppState.finding = false
                            if (found != null) {
                                AppState.host = found.address
                                context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("host", found.address).apply()
                                AppState.status = null; AppState.statusError = null
                            } else AppState.statusError = "No printer found on this Wi-Fi."
                        }
                    }
                }
            }) {
                if (AppState.finding) CircularProgressIndicator(Modifier.size(18.dp), color = Tokens.g500, strokeWidth = 2.dp)
                else Icon(Icons.Default.Search, "Find printer", tint = Tokens.g500)
            }
        }
    }
}

@Composable
private fun DocumentCard(pages: List<PageRef>, onAdd: () -> Unit) {
    val context = LocalContext.current
    SoftCard {
        if (pages.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Bubble(56.dp, accent = Tokens.pink) { Icon(Icons.Default.Add, null, tint = Tokens.pink, modifier = Modifier.size(28.dp)) }
                Text("Add photos or a PDF", style = Type.heading, modifier = Modifier.padding(top = 14.dp))
                Text("Or share them to Envy Max DPI from any app.", style = Type.body, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                AppState.importError?.let { Text(it, style = Type.label, modifier = Modifier.padding(bottom = 12.dp)) }
                if (AppState.importing > 0) CircularProgressIndicator(Modifier.size(24.dp), color = Tokens.indigo, strokeWidth = 2.5.dp)
                else GhostButton("Choose files", onClick = onAdd)
            }
            return@SoftCard
        }
        val index = AppState.previewIndex.coerceIn(0, pages.size - 1)
        val page = pages[index]
        CardTitle(painterResource(R.drawable.ic_doc), Tokens.pink, "Preview") {
            Text("Page ${index + 1} of ${pages.size}", style = Type.label.copy(fontSize = 12.5.sp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Bubble(36.dp, tint = false, onClick = { if (index > 0) AppState.previewIndex = index - 1 }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", tint = if (index > 0) Tokens.g700 else Tokens.g300)
            }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                PagePreview(page, AppState.options, Modifier.fillMaxWidth(0.82f))
            }
            Bubble(36.dp, tint = false, onClick = { if (index < pages.size - 1) AppState.previewIndex = index + 1 }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = if (index < pages.size - 1) Tokens.g700 else Tokens.g300)
            }
        }
        AppState.importError?.let { Text(it, style = Type.label, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppState.docs.toList().forEach { doc ->
                Row(
                    Modifier.clip(Tokens.pill).background(Tokens.violetSoft).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        doc.name + if (doc.pageCount > 1) " · ${doc.pageCount} p." else "",
                        color = Tokens.violetText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp),
                    )
                    Box(Modifier.size(26.dp).clip(Tokens.pill).clickable {
                        AppState.docs.remove(doc)
                        AppState.io.execute { Documents.delete(doc) }
                    }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, "Remove", tint = Tokens.violetText, modifier = Modifier.size(14.dp))
                    }
                }
            }
            if (AppState.importing > 0) CircularProgressIndicator(Modifier.size(20.dp), color = Tokens.indigo, strokeWidth = 2.dp)
            GhostButton("Add", onClick = onAdd)
        }
    }
}

/**
 * Draws the page as it will print: sheet, margins, fit/fill, rotation and grayscale.
 * Pinch to zoom, drag to move while zoomed, double-tap to zoom in or reset. One-finger drags
 * scroll the screen as usual until the preview is zoomed.
 */
@Composable
private fun PagePreview(page: PageRef, options: PrintOptions, modifier: Modifier) {
    var scale by remember(page.key) { mutableFloatStateOf(1f) }
    var offset by remember(page.key) { mutableStateOf(Offset.Zero) }
    val sharpKey = page.key + "#sharp"
    val thumb = AppState.thumbs[sharpKey] ?: AppState.thumbs[page.key]
    LaunchedEffect(page.key) {
        // Only the current page keeps a sharp (zoom) version, to save memory.
        AppState.thumbs.keys.filter { it.endsWith("#sharp") && it != sharpKey }.forEach { AppState.thumbs.remove(it) }
        if (AppState.thumbs[page.key] == null) {
            val bmp = withContext(Dispatchers.IO) { runCatching { Documents.thumbnail(page, 900) }.getOrNull() }
            if (bmp != null) AppState.thumbs[page.key] = bmp
        }
    }
    val zoomed = scale > 1.5f
    LaunchedEffect(page.key, zoomed) {
        if (zoomed && AppState.thumbs[sharpKey] == null) {
            val bmp = withContext(Dispatchers.IO) { runCatching { Documents.thumbnail(page, 2800) }.getOrNull() }
            if (bmp != null) AppState.thumbs[sharpKey] = bmp
        }
    }
    val canvas = PageCanvas.forQuality(options.quality)
    Box(
        modifier
            .aspectRatio(canvas.width.toFloat() / canvas.height)
            .softShadow(Tokens.ctrl, 8.dp)
            .clip(Tokens.ctrl)
            .background(Color.White)
            .pointerInput(page.key) {
                fun clamp(o: Offset, s: Float) = Offset(
                    o.x.coerceIn(size.width * (1 - s), 0f),
                    o.y.coerceIn(size.height * (1 - s), 0f),
                )
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        val c = event.calculateCentroid(useCurrent = true)
                        // Pinch always zooms; one finger only pans once zoomed (otherwise it scrolls the screen).
                        // The event that lifts the last finger has no centroid (NaN); applying it would
                        // push the page off to an invalid position, so it's skipped.
                        if (fingers > 0 && c.isSpecified && (fingers >= 2 || scale > 1f)) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 8f)
                            // Keep the point under the fingers in place while zooming.
                            val o = c - (c - offset) * (newScale / scale) + pan
                            if (!o.x.isNaN() && !o.y.isNaN()) {
                                scale = newScale
                                offset = if (newScale == 1f) Offset.Zero else clamp(o, newScale)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(page.key) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f; offset = Offset.Zero
                    } else {
                        val s = 2.5f
                        offset = Offset(
                            (tap.x - tap.x * s).coerceIn(size.width * (1 - s), 0f),
                            (tap.y - tap.y * s).coerceIn(size.height * (1 - s), 0f),
                        )
                        scale = s
                    }
                })
            },
    ) {
        if (thumb == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = Tokens.indigo, strokeWidth = 2.5.dp)
        } else {
            Canvas(Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            }) {
                val k = size.width / canvas.width
                val p = Placement(page.width, page.height, canvas.box, options.scaling, options.orientation)
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.clipRect(canvas.box.left * k, canvas.box.top * k,
                        (canvas.box.left + canvas.box.width) * k, (canvas.box.top + canvas.box.height) * k)
                    val m = android.graphics.Matrix()
                    if (p.rotate) { m.postRotate(90f); m.postTranslate(thumb.height.toFloat(), 0f) }
                    val rw = if (p.rotate) thumb.height else thumb.width
                    val rh = if (p.rotate) thumb.width else thumb.height
                    m.postScale(p.drawW * k / rw, p.drawH * k / rh)
                    m.postTranslate(p.drawLeft * k, p.drawTop * k)
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    if (options.grayscale) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    nc.drawBitmap(thumb, m, paint)
                    nc.restore()
                }
            }
            if (scale > 1f) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    StatusPill("${"%.1f".format(scale)}× · double-tap to reset", Tone.BUSY)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(pageCount: Int, selection: List<Int>?) {
    val o = AppState.options
    fun set(change: PrintOptions.() -> PrintOptions) { AppState.options = o.change() }
    SoftCard {
        CardTitle(painterResource(R.drawable.ic_tune), Tokens.green, "Print settings")

        Label("Quality")
        PillChoice(
            listOf(Quality.MAX_DPI to "Max DPI", Quality.BEST to "Best", Quality.NORMAL to "Normal", Quality.DRAFT to "Draft"),
            o.quality, { set { copy(quality = it) } },
        )
        Text(
            when (o.quality) {
                Quality.MAX_DPI -> "1200 × 1200 dpi, like the Windows driver's Max DPI. About 6 minutes per page."
                Quality.BEST -> "600 dpi, the printer's best standard mode."
                Quality.NORMAL -> "600 dpi, everyday quality."
                Quality.DRAFT -> "300 dpi, fast and saves ink."
            },
            style = Type.label, modifier = Modifier.padding(top = 8.dp),
        )
        Divider()

        Label("Color")
        PillChoice(listOf(false to "Color", true to "Grayscale"), o.grayscale, { set { copy(grayscale = it) } })
        Divider()

        Label("Two-sided")
        PillChoice(
            listOf(Duplex.OFF to "Off", Duplex.LONG_EDGE to "Long edge", Duplex.SHORT_EDGE to "Short edge"),
            o.duplex, { set { copy(duplex = it) } },
        )
        Divider()

        Label("Size on page")
        PillChoice(listOf(Scaling.FIT to "Fit to page", Scaling.FILL to "Fill page"), o.scaling, { set { copy(scaling = it) } })
        Spacer(Modifier.height(12.dp))
        Label("Orientation")
        PillChoice(
            listOf(Orientation.AUTO to "Auto", Orientation.PORTRAIT to "Portrait", Orientation.LANDSCAPE to "Landscape"),
            o.orientation, { set { copy(orientation = it) } },
        )
        Divider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Copies", style = Type.strong)
                Text("Collated", style = Type.label.copy(fontSize = 12.sp))
            }
            Stepper(o.copies, 1..99) { set { copy(copies = it) } }
        }
        if (pageCount > 1) {
            Divider()
            Label("Pages")
            SoftField(AppState.pageRange, { AppState.pageRange = it }, "All $pageCount pages, or e.g. 1-3, 5", Modifier.fillMaxWidth())
            if (selection == null) Text("Use page numbers 1 to $pageCount, like 1-3, 5.", style = Type.label.copy(color = Tokens.g500), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun BottomBar(print: PrintState.Snapshot, selection: List<Int>?, pages: List<PageRef>) {
    val context = LocalContext.current
    val o = AppState.options
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tokens.bg)
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 6.dp),
    ) {
        if (print.active || print.finished) {
            SoftCard(Modifier.padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(print.title, style = Type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(print.message + if (print.queued > 0) " · ${print.queued} waiting" else "", style = Type.label.copy(fontSize = 12.sp))
                    }
                    if (print.active) {
                        Text("${(print.fraction * 100).toInt()}%", style = Type.stat.copy(fontSize = 22.sp), modifier = Modifier.padding(start = 12.dp))
                    } else {
                        Box(Modifier.size(30.dp).clip(Tokens.pill).clickable { PrintState.current = PrintState.Snapshot() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Close, "Dismiss", tint = Tokens.g400, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                if (print.active) {
                    SoftProgress(print.fraction, Modifier.padding(top = 12.dp))
                    Row(Modifier.padding(top = 12.dp)) {
                        Text("You can leave the app; printing continues.", style = Type.label, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                        GhostButton("Cancel") { PrintService.cancel(context) }
                    }
                }
            }
        }
        val count = (selection?.size ?: 0) * o.copies
        val minutes = if (o.quality == Quality.MAX_DPI) count * 6 else 0
        val label = when {
            pages.isEmpty() -> "Add something to print"
            selection == null -> "Check the page numbers"
            minutes > 0 -> "Print $count ${if (count == 1) "page" else "pages"} · about $minutes min"
            else -> "Print $count ${if (count == 1) "page" else "pages"}"
        }
        PrimaryButton(label, enabled = pages.isNotEmpty() && selection != null) {
            val chosen = selection!!.map { pages[it] }
            val title = AppState.docs.firstOrNull()?.name?.substringBeforeLast('.') ?: "Print"
            PrintService.print(context, JobSpec(AppState.host, title, o.copy(correction = CalibrationStore.active(context, o.quality)), chosen))
        }
    }
}

/** "" = all pages; "1-3, 5" = 0-based indices; null when invalid. */
fun parseRange(text: String, total: Int): List<Int>? {
    if (text.isBlank()) return (0 until total).toList()
    val out = mutableListOf<Int>()
    for (part in text.split(',', ';', ' ').filter { it.isNotBlank() }) {
        val bounds = part.split('-').map { it.trim().toIntOrNull() ?: return null }
        val (a, b) = when (bounds.size) { 1 -> bounds[0] to bounds[0]; 2 -> bounds[0] to bounds[1]; else -> return null }
        if (a < 1 || b > total || a > b) return null
        for (p in a..b) out += p - 1
    }
    return out.ifEmpty { null }
}
