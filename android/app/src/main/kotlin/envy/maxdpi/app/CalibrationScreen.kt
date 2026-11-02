package envy.maxdpi.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import envy.maxdpi.Calibration
import envy.maxdpi.EsclScanner
import envy.maxdpi.PageContent
import envy.maxdpi.Placement
import envy.maxdpi.PrintOptions
import envy.maxdpi.Quality
import envy.maxdpi.RasterSource
import java.text.DateFormat
import java.util.Date

/** Saved color profiles, one per print quality. */
object CalibrationStore {
    data class Saved(
        val correction: Calibration.ColorCorrection,
        val measurement: Calibration.Measurement,
        val enabled: Boolean,
        val savedAt: Long,
        val errorBefore: Double,
        val errorAfter: Double,
    )

    /** Bumped on every change so the screen re-reads. */
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("calibration", Context.MODE_PRIVATE)

    fun load(ctx: Context, q: Quality): Saved? {
        val p = prefs(ctx); val k = q.name
        val lut = Calibration.ColorCorrection.decode(p.getString("$k.lut", null) ?: return null) ?: return null
        val m = Calibration.Measurement.decode(p.getString("$k.measurement", null) ?: return null) ?: return null
        return Saved(lut, m, p.getBoolean("$k.on", true), p.getLong("$k.at", 0),
            p.getFloat("$k.before", 0f).toDouble(), p.getFloat("$k.after", 0f).toDouble())
    }

    fun save(ctx: Context, q: Quality, c: Calibration.ColorCorrection, m: Calibration.Measurement, before: Double, after: Double) {
        val k = q.name
        prefs(ctx).edit().putString("$k.lut", c.encode()).putString("$k.measurement", m.encode()).putBoolean("$k.on", true)
            .putLong("$k.at", System.currentTimeMillis()).putFloat("$k.before", before.toFloat()).putFloat("$k.after", after.toFloat()).apply()
        version++
    }

    fun setEnabled(ctx: Context, q: Quality, on: Boolean) { prefs(ctx).edit().putBoolean("${q.name}.on", on).apply(); version++ }

    fun remove(ctx: Context, q: Quality) {
        val e = prefs(ctx).edit()
        listOf("lut", "measurement", "on", "at", "before", "after").forEach { e.remove("${q.name}.$it") }
        e.apply(); version++
    }

    /** The correction to use for a print in [q], if a profile is saved and switched on. */
    fun active(ctx: Context, q: Quality): Calibration.ColorCorrection? = load(ctx, q)?.takeIf { it.enabled }?.correction
}

/** Screen state, kept for the life of the process like [AppState]. */
private object CalibState {
    var quality by mutableStateOf(Quality.MAX_DPI)
    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    /** A fresh measurement of the plain target, with the correction built from it. */
    var result by mutableStateOf<Calibration.Measurement?>(null)
    var correction by mutableStateOf<Calibration.ColorCorrection?>(null)
    var errors by mutableStateOf<Pair<Double, Double?>?>(null)
    /** Error measured on a check sheet (printed with the saved profile). */
    var checked by mutableStateOf<Double?>(null)
}

private val QUALITY_LABELS = listOf(Quality.MAX_DPI to "Max DPI", Quality.BEST to "Best", Quality.NORMAL to "Normal", Quality.DRAFT to "Draft")

/** The calibration target as a printable page. */
private fun targetContent(q: Quality) = object : PageContent {
    override val contentWidth = 1
    override val contentHeight = 1
    override fun open(canvasWidth: Int, canvasHeight: Int, placement: Placement): RasterSource =
        Calibration.TargetPage(canvasWidth, canvasHeight, envy.maxdpi.Canvas.forQuality(q).box, q.dpi)
}

@Composable
fun CalibrationScreen() {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = CalibrationStore.version // re-read saved state on change
    val quality = CalibState.quality
    val label = QUALITY_LABELS.first { it.first == quality }.second
    val saved = CalibrationStore.load(context, quality)
    val printing = PrintState.current.active

    fun printTarget(corrected: Boolean) {
        val q = quality
        val options = PrintOptions(quality = q, correction = if (corrected) saved?.correction else null)
        val title = if (corrected) "Calibration check" else "Calibration target"
        PrintService.print(context, JobSpec(AppState.host, title, options, emptyList(), listOf(targetContent(q))))
    }

    fun scan(verify: Boolean) {
        CalibState.error = null; CalibState.result = null; CalibState.correction = null; CalibState.errors = null; CalibState.checked = null
        val reference = saved?.measurement
        CalibState.busy = "Scanning… (about 30 seconds)"
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        AppState.io.execute {
            val outcome = runCatching {
                val address = PrinterFinder.resolve(app, AppState.host)
                val jpeg = EsclScanner(address).scan(300)
                main.post { CalibState.busy = "Analyzing…" }
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("The scan couldn't be read.")
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val w = bmp.width; val h = bmp.height
                bmp.recycle()
                val m = Calibration.measure(px, w, h, 300)
                if (verify && reference != null) {
                    Triple(m, null, Calibration.checkError(reference, m))
                } else {
                    main.post { CalibState.busy = "Building the color profile…" }
                    val corr = Calibration.buildCorrection(m)
                    Triple(m, corr to Calibration.errors(m, corr), null)
                }
            }
            main.post {
                CalibState.busy = null
                outcome.onSuccess { (m, built, check) ->
                    if (check != null) CalibState.checked = check
                    else { CalibState.result = m; CalibState.correction = built?.first; CalibState.errors = built?.second }
                }
                outcome.onFailure {
                    CalibState.error = when (it) {
                        is Calibration.MeasureException -> it.message
                        is java.net.SocketTimeoutException, is java.net.ConnectException -> "Can't reach the scanner. Check that the printer is on."
                        else -> "Scan failed: ${it.message ?: it.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            GradientHeader("Color calibration", "One color profile per print quality", painterResource(R.drawable.ic_tune))
            Column(Modifier.offset(y = -OVERLAP).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SoftCard {
                Text("How it works", style = Type.heading, modifier = Modifier.padding(bottom = 8.dp))
                Text(
                    "The app prints 216 color mixes, reads them back with the printer's scanner, learns how this " +
                        "print quality really reproduces colors, and corrects colors before printing. It fixes color " +
                        "shifts such as weak or contaminated ink in refilled cartridges. Each quality has its own profile.",
                    style = Type.body,
                )
                Spacer(Modifier.height(14.dp))
                Label("Profile for")
                PillChoice(QUALITY_LABELS, quality, {
                    CalibState.quality = it; CalibState.result = null; CalibState.correction = null
                    CalibState.errors = null; CalibState.checked = null; CalibState.error = null
                })
            }

            SavedCard(context, quality, label, saved, printing, onCheck = { printTarget(corrected = true) }, onVerify = { scan(verify = true) })

            SoftCard {
                StepTitle(1, "Print the target")
                Text(
                    "Uses one sheet of the paper you print on. " +
                        if (quality == Quality.MAX_DPI) "Takes about 6 minutes in Max DPI." else "Takes about a minute.",
                    style = Type.body,
                )
                Spacer(Modifier.height(12.dp))
                GhostButton(if (printing) "Printing…" else "Print target", enabled = !printing) { printTarget(corrected = false) }
                Divider()
                StepTitle(2, "Scan it")
                Text(
                    "Let the ink dry for a minute, then put the sheet printed side down on the scanner glass " +
                        "(any orientation) and close the lid.",
                    style = Type.body,
                )
                CalibState.busy?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Tokens.indigo, strokeWidth = 2.dp)
                        Text(it, style = Type.body, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                CalibState.error?.let { Text(it, style = Type.strong, modifier = Modifier.padding(top = 14.dp)) }
            }

            CalibState.result?.let { ResultCard(it, CalibState.correction, CalibState.errors) }
            }
        }

        // The one feature button: scan, or save once there's a result to save.
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 6.dp)) {
            val result = CalibState.result
            val corr = CalibState.correction
            val errs = CalibState.errors
            if (result != null && corr != null && errs != null) {
                PrimaryButton("Save profile for $label", enabled = true) {
                    CalibrationStore.save(context, quality, corr, result, errs.first, errs.second ?: 0.0)
                    CalibState.result = null; CalibState.correction = null; CalibState.errors = null
                }
                Text("Scan again", style = Type.label, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)
                    .clickable(enabled = CalibState.busy == null) { scan(verify = false) })
            } else {
                PrimaryButton(if (CalibState.busy != null) "Working…" else "Scan and analyze", enabled = CalibState.busy == null) {
                    scan(verify = false)
                }
            }
        }
    }
}

@Composable
private fun StepTitle(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Bubble(28.dp) { Text("$n", style = Type.chip.copy(color = Tokens.indigo)) }
        Text(text, style = Type.heading, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun SavedCard(
    context: Context, quality: Quality, label: String, saved: CalibrationStore.Saved?, printing: Boolean,
    onCheck: () -> Unit, onVerify: () -> Unit,
) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label profile", style = Type.heading, modifier = Modifier.weight(1f))
            when {
                saved == null -> StatusPill("None", Tone.NEUTRAL)
                saved.enabled -> StatusPill("On", Tone.OK)
                else -> StatusPill("Off", Tone.NEUTRAL)
            }
        }
        if (saved == null) {
            Text("No profile for $label yet. Prints in this quality use the printer's own colors.", style = Type.body, modifier = Modifier.padding(top = 8.dp))
            return@SoftCard
        }
        Text(
            "Saved ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(saved.savedAt))}. " +
                "Average color error %.0f → %.0f (predicted).".format(saved.errorBefore, saved.errorAfter),
            style = Type.body, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
        )
        CalibState.checked?.let {
            Text("Check sheet measured: average error %.0f (was %.0f without the profile).".format(it, saved.errorBefore),
                style = Type.strong, modifier = Modifier.padding(bottom = 14.dp))
        }
        PillChoice(listOf(true to "Use it", false to "Don't use it"), saved.enabled, { CalibrationStore.setEnabled(context, quality, it) })
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Print check sheet", enabled = !printing, onClick = onCheck)
            GhostButton("Verify", onClick = onVerify)
            GhostButton("Remove") { CalibrationStore.remove(context, quality) }
        }
        Text("Check sheet: the target printed through the profile. Verify: scan it to measure the result.",
            style = Type.label, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun ResultCard(m: Calibration.Measurement, correction: Calibration.ColorCorrection?, errors: Pair<Double, Double?>?) {
    SoftCard {
        Text("Measured", style = Type.heading)
        if (errors == null) {
            Text("Building the color profile…", style = Type.body, modifier = Modifier.padding(top = 8.dp))
            return@SoftCard
        }
        Row(Modifier.padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("%.0f".format(errors.first), style = Type.stat)
                Text("Average color error now", style = Type.label)
            }
            Column(Modifier.weight(1f)) {
                Text("%.0f".format(errors.second ?: 0.0), style = Type.stat.copy(color = Tokens.indigo))
                Text("With this profile (predicted)", style = Type.label)
            }
        }
        Label("Key colors: wanted, printed now, with the profile")
        val rows = Calibration.preview(m, correction)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { (want, now, after) ->
                Column(Modifier.weight(1f).clip(Tokens.ctrl)) {
                    Box(Modifier.fillMaxWidth().height(22.dp).background(Color(0xFF000000.toInt() or want)))
                    Box(Modifier.fillMaxWidth().height(22.dp).background(Color(0xFF000000.toInt() or now)))
                    Box(Modifier.fillMaxWidth().height(22.dp).background(Color(0xFF000000.toInt() or (after ?: now))))
                }
            }
        }
        Text("Error is the distance between wanted and printed colors as the scanner sees them (0 = perfect). " +
            "Colors stay within what the inks can reach, so very vivid colors and deep blacks stay limited.",
            style = Type.label, modifier = Modifier.padding(top = 12.dp))
    }
}
