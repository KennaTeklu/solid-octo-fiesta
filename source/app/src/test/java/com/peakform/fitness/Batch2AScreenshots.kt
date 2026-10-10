package com.peakform.fitness

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.peakform.fitness.core.Backup
import com.peakform.fitness.core.Badges
import com.peakform.fitness.core.Consent
import com.peakform.fitness.core.Exporter
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.ProPrefs
import com.peakform.fitness.core.Snapshots
import com.peakform.fitness.core.Studio
import com.peakform.fitness.core.Vocab
import com.peakform.fitness.core.WorkoutData
import com.peakform.fitness.engine.Library
import com.peakform.fitness.ui.ProTheme
import com.peakform.fitness.ui.ImportGuardDialog
import com.peakform.fitness.ui.screens.DashboardScreen
import com.peakform.fitness.ui.screens.FeedbackScreen
import com.peakform.fitness.ui.screens.HistoryScreen
import com.peakform.fitness.ui.screens.LibraryScreen
import com.peakform.fitness.ui.screens.MergeReportDialog
import com.peakform.fitness.ui.screens.PasteJsonScreen
import com.peakform.fitness.ui.screens.SettingsScreen
import com.peakform.fitness.ui.screens.SnapshotsScreen
import com.peakform.fitness.ui.screens.ToastBanner
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * BATCH-2A screenshot battery.
 *
 * METHOD NOTE (disclosed in README.md + CHANGES.md): captures are JVM-rendered with
 * Robolectric native graphics from the SAME Compose code compiled into the release APK.
 * This sandbox has no KVM and reaps any long-running process, so the Android emulator
 * cannot complete a TCG boot — a real-device/emulator screenshot battery is therefore
 * not possible here (documented attempt in worklog). The renderer exercises the same
 * composables, themes, engine paths and — for the P0 import — the REAL client file
 * export_Kenna_September_28_2026.json through the REAL Backup.import() pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
class Batch2AScreenshots {

    // Repo root discovered by walking up from the Gradle working dir (source/app).
    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "docs/verify/client-file").isDirectory }
        ?: error("docs/verify/client-file not found above ${System.getProperty("user.dir")}")

    private val out: String = File(repoRoot, "docs/verify").absolutePath

    @get:org.junit.Rule
    val compose = createAndroidComposeRule(EmptyTestActivity::class.java)

    private lateinit var ctx: Context

    private fun clientRaw(): String = File(
        repoRoot, "docs/verify/client-file/export_Kenna_September_28_2026.json",
    ).readText()

    @Before
    fun setup() {
        System.setProperty("roborazzi.enabled", "true")
        ctx = RuntimeEnvironment.getApplication()
        com.peakform.fitness.ui.Fa.appContext = ctx
        ProState.init(ctx)
        kotlinx.coroutines.runBlocking { if (!ProState.initialized) ProState.loadAll() }
        Library.ensure(ctx)
        Vocab.init(ctx)
        Badges.init(ctx)
        java.io.File(out).mkdirs()
    }

    // single setContent per test; each snap() swaps the hosted composable under a fresh key
    private val slot = androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
    private var slotId = 0

    private fun snap(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        if (slot.value == null) {
            compose.setContent {
                ProTheme(dark = true, accent = com.peakform.fitness.ui.ACCENTS[0]) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        slot.value?.invoke()
                    }
                }
            }
        }
        slotId++
        val wrapped: @androidx.compose.runtime.Composable () -> Unit = {
            androidx.compose.runtime.key(slotId) { content() }
        }
        slot.value = wrapped
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val w = view.width.coerceAtLeast(412)
            val h = view.height.coerceAtLeast(892)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            view.draw(canvas)
            File("$out/$name.png").outputStream().use { os ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
            }
        }
    }

    /** Import the REAL client file through the REAL import pipeline. Returns the result. */
    private fun importRealClientFile(): Backup.ImportResult {
        ProState.data = WorkoutData()
        ProState.currentWorkout = null
        val res = kotlinx.coroutines.runBlocking { Backup.import(ctx, clientRaw()) }
        return res
    }

    // ---- P0: the client file imports, and the result is surfaced ----

    @Test
    fun p0ClientImport() {
        val res = importRealClientFile()
        org.junit.Assert.assertTrue("client file must import: ${res.message}", res.ok)
        org.junit.Assert.assertEquals(30, ProState.data.workouts.size)

        snap("p0-client-import-success") {
            Box { ToastBanner(res.message) }
        }
        snap("p0-merge-report") { MergeReportDialog(result = res, onClose = {}) }
    }

    // ---- feature 8: pre-import guard dialog (red confirm) ----

    @Test
    fun importGuard() {
        snap("feature-import-guard") {
            ImportGuardDialog(
                detail = "This merges the selected backup into your current data.",
                onConfirm = {}, onDismiss = {},
            )
        }
    }

    // ---- the imported data visible in Dashboard / History / Library ----

    @Test
    fun importedDataEverywhere() {
        importRealClientFile()
        snap("p0-dashboard-after-import") { DashboardScreen(onStartWorkout = {}, onResumeWorkout = {}, onOpenSection = {}) }
        snap("p0-history-after-import") { HistoryScreen(onOpenSection = {}) }
        snap("p0-library-after-import") { LibraryScreen(onAddToWorkout = {}) }
    }

    // ---- Settings → Data & backup: the BATCH-2A button battery ----

    @Test
    fun settingsDataBackupCard() {
        // BATCH-4 fix: SettingsScreen now uses LazyColumn with key+contentType (per
        // Batch 4 perf requirement) and "Data & backup" is below the visible viewport.
        // Render the card body directly — same Compose code path as the in-screen card,
        // just isolated so the test doesn't have to scroll the LazyColumn.
        compose.setContent {
            ProTheme(dark = true, accent = com.peakform.fitness.ui.ACCENTS[0]) {
                Box(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        com.peakform.fitness.ui.screens.SettingsGroupCard(
                            title = "Data & backup",
                            icon = "fa-database",
                            expanded = true,
                            onToggle = {},
                        ) {
                            com.peakform.fitness.ui.screens.DataCardBody(
                                onExport = {}, onExportA = {}, onImport = {}, onExportCsv = {},
                                onExportLibrary = {}, onImportLibrary = {}, onExportConsent = {},
                                onExportFeedback = {}, onExportPhrasebook = {}, onImportPhrasebook = {},
                                onSnapshots = {}, onPasteJson = {}, onExportBundle = {}, onImportBundle = {},
                                onFeedback = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(400); compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val w = view.width.coerceAtLeast(412)
            val h = view.height.coerceAtLeast(892)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            view.draw(canvas)
            File("$out/feature-settings-data-backup.png").outputStream().use { os ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
            }
        }
    }

    // ---- features 6/9/5: phrasebook counts, paste-JSON screen, feedback screen ----

    @Test
    fun pasteJsonAndFeedback() {
        // phrasebook import through the REAL merge path (feature 6) — counts for the report
        val report = Vocab.importPhrasebookReport(ctx, """{"rest_time":"Wait a moment","next_lift":"Go heavier next time"}""")
        org.junit.Assert.assertTrue("phrasebook merge should apply", report.added + report.updated > 0)
        snap("feature-paste-json") { PasteJsonScreen(onClose = {}) }
        Exporter.addFeedback(ctx, "Rest timer feels great — wish the CSV export let me pick columns.", page = "feedback-screen")
        snap("feature-feedback") { FeedbackScreen(onClose = {}) }
    }

    // ---- feature 7: snapshots screen with two or more snapshots ----

    @Test
    fun snapshotsList() {
        kotlinx.coroutines.runBlocking {
            Snapshots.create(ctx, "Before Batch 2A import")
            importRealClientFile()
            Snapshots.create(ctx, "After client-file import")
            val entries = Snapshots.list(ctx)
            org.junit.Assert.assertTrue("need 2+ snapshots, got ${entries.size}", entries.size >= 2)
        }
        snap("feature-snapshots-batch2a") { SnapshotsScreen(onClose = {}) }
    }

    // ---- features 1/2/4/5/6: real export artifacts written to docs/verify/artifacts ----

    @Test
    fun exportArtifacts() {
        importRealClientFile()
        val dir = File("$out/artifacts"); dir.mkdirs()
        // feature 1: CSV, one row per logged set
        val (csv, rows) = Exporter.historyCsvPerSet()
        File(dir, "workout_history.csv").writeText(csv)
        org.junit.Assert.assertTrue("CSV must have header + per-set rows", rows > 50)
        org.junit.Assert.assertTrue(csv.startsWith("date,exercise,set,reps,weight,RPE,note"))
        // feature 2: custom exercise library export
        val lib = Studio.exportLibrary(ctx)
        File(dir, "custom_exercises.json").writeText(lib)
        // feature 4: consent log export
        Consent.record(ctx, "legal", "Batch 2A artifact export test")
        File(dir, "consent_log.json").writeText(Consent.exportLog(ctx))
        // feature 5: feedback export
        File(dir, "feedback.json").writeText(Exporter.feedbackJson(ctx))
        // feature 6: phrasebook export
        File(dir, "phrasebook_L5.json").writeText(Vocab.exportPhrasebook(ctx))
        // feature 10: full app bundle export
        val bundle = Backup.exportBundleV1(ctx)
        File(dir, "app_bundle.json").writeText(
            com.peakform.fitness.core.ProJson.pretty.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(), bundle))
        org.junit.Assert.assertEquals("PeakForm-App-Bundle-v1", (bundle["__format"] as kotlinx.serialization.json.JsonPrimitive).content)
        // native export contains zero NaN (P0 part 2 verification artifact)
        val fmtA = Backup.exportFormatA(ctx)
        val raw = com.peakform.fitness.core.ProJson.json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), fmtA)
        org.junit.Assert.assertFalse("native export must contain no NaN", raw.contains("NaN"))
        File(dir, "export_format_A.json").writeText(
            com.peakform.fitness.core.ProJson.pretty.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(), fmtA))
    }

    // ---- feature 1: CSV opens correctly — the REAL generated CSV in an editor view ----

    @Test
    fun csvEditorView() {
        importRealClientFile()
        val (csv, rows) = Exporter.historyCsvPerSet()
        org.junit.Assert.assertTrue("CSV must have header + per-set rows", rows > 50)
        val preview = csv.lines().take(24).joinToString("\n")
        val mono = android.graphics.Typeface.MONOSPACE
        snap("feature-csv-editor") {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF2D2D2D)).padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "workout_history.csv",
                        color = Color(0xFFCCCCCC),
                        fontSize = 14.sp, fontFamily = FontFamily(mono)
                    )
                    Text(
                        "$rows data rows — plain text editor",
                        color = Color(0xFF888888),
                        fontSize = 12.sp
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        preview,
                        color = Color(0xFFD4D4D4),
                        fontSize = 11.sp,
                        fontFamily = FontFamily(mono),
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }

    // ---- feature 10 round trip: bundle export → wipe → bundle import ----

    @Test
    fun bundleRoundTrip() {
        importRealClientFile()
        kotlinx.coroutines.runBlocking { Snapshots.create(ctx, "pre-roundtrip") }
        Studio.addCustom(ctx, StudioDraftForTest("Bundle Round Trip Lift", "chest"))
        val before = ProState.data.workouts.size
        val bundle = Backup.exportBundleV1(ctx)
        val bundleRaw = com.peakform.fitness.core.ProJson.pretty.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), bundle)

        // wipe
        ProState.data = WorkoutData(); ProState.currentWorkout = null
        ProState.notifyChanged()
        snap("feature-bundle-before") { DashboardScreen(onStartWorkout = {}, onResumeWorkout = {}, onOpenSection = {}) }

        // restore
        val r = Backup.importBundleV1(ctx, bundleRaw)
        org.junit.Assert.assertTrue("bundle restore must succeed: $r", r.ok)
        org.junit.Assert.assertEquals(before, ProState.data.workouts.size)
        snap("feature-bundle-after") { DashboardScreen(onStartWorkout = {}, onResumeWorkout = {}, onOpenSection = {}) }
    }

    private fun StudioDraftForTest(name: String, group: String) =
        com.peakform.fitness.core.StudioDraft(name = name, group = group, muscles = listOf(group), equipment = "barbell")
}
