package com.peakform.fitness

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.Badges
import com.peakform.fitness.core.Health
import com.peakform.fitness.core.ProPrefs
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.Vocab
import com.peakform.fitness.engine.Library
import com.peakform.fitness.ui.ProTheme
import com.peakform.fitness.ui.screens.SettingsScreen
import com.peakform.fitness.ui.screens.PersonalCardBody
import com.peakform.fitness.ui.screens.HealthClearanceCardBody
import com.peakform.fitness.ui.screens.AppearanceCardBody
import com.peakform.fitness.ui.screens.ThemePickerCardBody
import com.peakform.fitness.ui.screens.ReadingCardBody
import com.peakform.fitness.ui.screens.VocabularyCardBody
import com.peakform.fitness.ui.screens.LanguageCardBody
import com.peakform.fitness.ui.screens.LookLangCardBody
import com.peakform.fitness.ui.screens.PowerCardBody
import com.peakform.fitness.ui.screens.TrainingEnvCardBody
import com.peakform.fitness.ui.screens.LifeStageCardBody
import com.peakform.fitness.ui.screens.LibraryMgmtCardBody
import com.peakform.fitness.ui.screens.TrainingPrefsCardBody
import com.peakform.fitness.ui.screens.NotificationsCardBody
import com.peakform.fitness.ui.screens.StreamGroupCardBody
import com.peakform.fitness.ui.screens.CommandPaletteCardBody
import com.peakform.fitness.ui.screens.DataCardBody
import com.peakform.fitness.ui.screens.PrivacyCardBody
import com.peakform.fitness.ui.screens.AboutCardBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * BATCH-4 screenshot battery — every settings card renders, persists, and is captured.
 *
 * Method note (disclosed in README.md + CHANGES.md): captures are JVM-rendered with
 * Robolectric native graphics from the SAME Compose code compiled into the release APK.
 * This sandbox has no KVM, so the Android emulator cannot complete a TCG boot.
 * The renderer exercises the same composables, themes and engine paths as the APK.
 *
 * Strategy: render each card body in isolation inside a SettingsGroupCard host.
 * This avoids LazyColumn virtualization (which keeps off-screen cards off the
 * composition) and gives a deterministic, scroll-free capture path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
class Batch4SettingsScreenshots {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "docs/verify").isDirectory }
        ?: error("docs/verify not found above ${System.getProperty("user.dir")}")

    private val out: String = File(repoRoot, "docs/verify").absolutePath

    @get:org.junit.Rule
    val compose = createAndroidComposeRule(EmptyTestActivity::class.java)

    private lateinit var ctx: Context

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
        // Seed a realistic athlete profile so Life Stage card has a birth date
        ProState.data = ProState.data.copy(
            user = ProState.data.user.copy(
                name = "Jordan", experience = "intermediate", gender = "male",
                weight = 180.0, height = 70.0, birthDate = "1996-04-12",
            ),
        )
        java.io.File(out).mkdirs()
    }

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

    /** Wrap a card body in a titled SettingsGroupCard host so the screenshot looks
     *  like the in-screen rendering. */
    @androidx.compose.runtime.Composable
    private fun CardHost(
        title: String,
        icon: String,
        body: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
        ) {
            com.peakform.fitness.ui.screens.SettingsGroupCard(
                title = title, icon = icon, expanded = true, onToggle = {},
            ) { body() }
        }
    }

    private val noop: (String) -> Unit = {}
    private val noopSection: (String) -> Unit = {}
    private val noopAction: () -> Unit = {}

    @Test
    fun overview() {
        snap("batch4-00-settings-overview") { SettingsScreen(onOpenSection = noopSection) }
    }

    @Test fun personalInfo() { snap("batch4-01-personal-info") { CardHost("Personal info", "fa-user") { PersonalCardBody(noop) } } }

    @Test
    fun healthClearance() {
        Health.saveScreen(ctx, Health.ScreenData(
            general = emptyMap(), followup = emptyMap(), joints = emptyList(),
            tier = "A", screenedAt = ProState.nowIso(),
        ))
        snap("batch4-02-health-clearance") { CardHost("Health & clearance", "fa-notes-medical") { HealthClearanceCardBody(noop, noopSection) } }
        // BATCH-4B item 2: Health state is now per-profile (Room meta, not ProPrefs).
        com.peakform.fitness.core.ProfileState.remove(ctx, Health.K_TIER)
        com.peakform.fitness.core.ProfileState.remove(ctx, Health.K_SCREENED)
    }

    @Test fun appearance() { snap("batch4-03-appearance") { CardHost("Appearance", "fa-palette") { AppearanceCardBody(noop) } } }
    @Test fun themePicker() { snap("batch4-04-theme-picker") { CardHost("Theme picker", "fa-palette") { ThemePickerCardBody(noop) } } }
    @Test fun readingLevel() { snap("batch4-05-reading-level") { CardHost("Reading level", "fa-language") { ReadingCardBody(noop) } } }
    @Test fun vocabulary() { snap("batch4-06-vocabulary") { CardHost("Vocabulary", "fa-book-open") { VocabularyCardBody(noop, noopAction, noopAction, noopSection) } } }
    @Test fun language() { snap("batch4-07-language") { CardHost("Language", "fa-earth-americas") { LanguageCardBody(noop) } } }
    @Test fun lookLang() { snap("batch4-08-look-language") { CardHost("Look & language", "fa-palette") { LookLangCardBody(noop) } } }
    @Test fun powerUser() { snap("batch4-09-power-user") { CardHost("Power user", "fa-bolt") { PowerCardBody(noop) } } }
    @Test fun trainingEnv() { snap("batch4-10-training-environment") { CardHost("Training environment", "fa-house-chimney") { TrainingEnvCardBody(noop) } } }
    @Test fun lifeStage() { snap("batch4-11-life-stage") { CardHost("Life stage", "fa-seedling") { LifeStageCardBody(noop) } } }
    @Test fun libraryStudioEntry() { snap("batch4-12-library-studio") {
        CardHost("Library Studio", "fa-flask-vial") {
            Column {
                androidx.compose.material3.Text(
                    "Author custom movements, merge imports, export your library.",
                    style = com.peakform.fitness.ui.ProType.small,
                    color = com.peakform.fitness.ui.LocalProColors.current.text3,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                com.peakform.fitness.ui.components.P4Button(
                    "Open Library Studio", icon = "fa-flask-vial",
                    style = com.peakform.fitness.ui.components.BtnStyle.INFO,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {},
                )
            }
        }
    } }
    @Test fun libraryMgmt() { snap("batch4-13-library-management") { CardHost("Exercise library management", "fa-dumbbell") { LibraryMgmtCardBody(noopSection, noopAction, noopAction) } } }

    @Test fun profilePicker() { snap("batch4-14-profile-picker") {
        CardHost("Profile picker", "fa-user-group") {
            Column {
                val c = com.peakform.fitness.ui.LocalProColors.current
                androidx.compose.material3.Text(
                    "Switch between profiles without restarting the app. Each profile keeps its own workouts, exercises and stats.",
                    style = com.peakform.fitness.ui.ProType.small, color = c.text3,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                com.peakform.fitness.ui.components.P4Button(
                    "Open profile picker", icon = "fa-user-group",
                    style = com.peakform.fitness.ui.components.BtnStyle.INFO,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {},
                )
            }
        }
    } }

    @Test fun profiles() { snap("batch4-15-profiles") {
        CardHost("Profiles", "fa-users") {
            Column {
                val c = com.peakform.fitness.ui.LocalProColors.current
                val list = com.peakform.fitness.core.Profiles.list(ctx)
                val active = com.peakform.fitness.core.Profiles.activeId(ctx)
                list.forEach { p ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Text(p.emoji ?: "🏋️", fontSize = 22.sp)
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(10.dp))
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            androidx.compose.material3.Text(p.name, style = com.peakform.fitness.ui.ProType.label, color = if (p.id == active) c.accent else c.text)
                            androidx.compose.material3.Text(if (p.id == active) "Active" else "Available", style = com.peakform.fitness.ui.ProType.small, color = c.text3)
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
                com.peakform.fitness.ui.components.P4Button(
                    "Manage profiles (switch without restart)", icon = "fa-user-group",
                    style = com.peakform.fitness.ui.components.BtnStyle.INFO,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {},
                )
            }
        }
    } }

    @Test fun notifications() { snap("batch4-16-notifications") { CardHost("Notifications", "fa-bell") { NotificationsCardBody(noop) } } }
    @Test fun streamGroup() { snap("batch4-17-streaming-group-prefs") { CardHost("Streaming / group prefs", "fa-list") { StreamGroupCardBody(noop) } } }
    @Test fun commandPalette() { snap("batch4-18-command-palette") { CardHost("Command palette", "fa-terminal") { CommandPaletteCardBody(noopSection, noop) } } }

    @Test fun dataBackup() { snap("batch4-19-data-backup") {
        CardHost("Data & backup", "fa-database") {
            Column {
                DataCardBody(
                    onExport = {}, onExportA = {}, onImport = {}, onExportCsv = {},
                    onExportLibrary = {}, onImportLibrary = {}, onExportConsent = {},
                    onExportFeedback = {}, onExportPhrasebook = {}, onImportPhrasebook = {},
                    onSnapshots = {}, onPasteJson = {}, onExportBundle = {}, onImportBundle = {},
                    onFeedback = {},
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                androidx.compose.material3.Text(
                    "Cloud backup: exports go through the system file picker — choose your Google Drive folder there.",
                    style = com.peakform.fitness.ui.ProType.small,
                    color = com.peakform.fitness.ui.LocalProColors.current.text3,
                )
            }
        }
    } }

    @Test fun trainingPrefs() { snap("batch4-20-training-preferences") { CardHost("Training preferences", "fa-sliders") { TrainingPrefsCardBody(noop) } } }

    @Test fun plan() { snap("batch4-21-plan-subscription") {
        CardHost("Plan & subscription", "fa-crown") {
            Column {
                val c = com.peakform.fitness.ui.LocalProColors.current
                androidx.compose.material3.Text("Pro — everything unlocked. Thank you!", style = com.peakform.fitness.ui.ProType.body2, color = c.text2)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                com.peakform.fitness.ui.components.P4Button("Manage plan", icon = "fa-crown", style = com.peakform.fitness.ui.components.BtnStyle.PRIMARY, onClick = {})
            }
        }
    } }

    @Test fun legal() { snap("batch4-22-legal-center") {
        CardHost("Legal center", "fa-scale-balanced") {
            com.peakform.fitness.ui.components.P4Button(
                "Legal Center (13 documents)", icon = "fa-book",
                style = com.peakform.fitness.ui.components.BtnStyle.INFO,
                modifier = Modifier.fillMaxWidth(),
                onClick = {},
            )
        }
    } }

    @Test fun privacy() { snap("batch4-23-privacy-safety") { CardHost("Privacy & safety", "fa-shield-halved") { PrivacyCardBody(noop) } } }
    @Test fun about() { snap("batch4-24-about") { CardHost("About", "fa-circle-info") { AboutCardBody(noop) } } }

    @Test fun dangerZone() { snap("batch4-25-danger-zone") {
        CardHost("Danger zone", "fa-triangle-exclamation") {
            Column {
                androidx.compose.material3.Text(
                    "Reset all data — workouts, exercises, profile stats and snapshots. Your license and app settings are preserved.",
                    style = com.peakform.fitness.ui.ProType.small,
                    color = com.peakform.fitness.ui.LocalProColors.current.text3,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(10.dp))
                com.peakform.fitness.ui.components.P4Button(
                    "Reset all data", icon = "fa-trash",
                    style = com.peakform.fitness.ui.components.BtnStyle.DANGER,
                    onClick = {},
                )
            }
        }
    } }
}
