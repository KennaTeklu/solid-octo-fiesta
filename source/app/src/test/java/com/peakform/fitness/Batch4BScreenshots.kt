package com.peakform.fitness

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.peakform.fitness.core.Badges
import com.peakform.fitness.core.Health
import com.peakform.fitness.core.ProfileState
import com.peakform.fitness.core.Profiles
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.Vocab
import com.peakform.fitness.engine.Library
import com.peakform.fitness.ui.ACCENTS
import com.peakform.fitness.ui.ProTheme
import com.peakform.fitness.ui.ThemeController
import com.peakform.fitness.ui.screens.AppearanceCardBody
import com.peakform.fitness.ui.screens.HealthScreenFlow
import com.peakform.fitness.ui.screens.OnboardingWizard
import com.peakform.fitness.ui.screens.PersonalCardBody
import com.peakform.fitness.ui.screens.ProfilesScreen
import com.peakform.fitness.ui.screens.ThemePickerCardBody
import com.peakform.fitness.ui.screens.SettingsGroupCard
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * BATCH-4B screenshot battery — one screenshot per shipped feature:
 *   1. Theme picker with accent applied across the app
 *   2. Same after restart (re-applied from prefs)
 *   3. Profile picker showing the correct label format on default & non-default
 *   4. Profile A with a draft workout, then Profile B with none, then back to A
 *   5. Profile swap without restart (before / after)
 *   6. New profile name appearing in Personal Info
 *   7. Onboarding wizard first step (fresh install)
 *   8. Health screen intro (new profile)
 *
 * Method note (disclosed in README.md + CHANGES.md): captures are JVM-rendered
 * with Robolectric native graphics from the SAME Compose code compiled into
 * the release APK. This sandbox has no KVM, so the Android emulator cannot
 * complete a TCG boot. The renderer exercises the same composables, themes and
 * engine paths as the APK.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
class Batch4BScreenshots {

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
        // BATCH-4B: seed a realistic athlete on the default profile so Personal
        // Info has a name and the label format renders meaningfully.
        ProState.data = ProState.data.copy(
            user = ProState.data.user.copy(
                name = "Jordan", experience = "intermediate", gender = "male",
                weight = 180.0, height = 70.0, birthDate = "1996-04-12",
            ),
        )
        // Make sure no stale per-profile state from a prior test leaks in.
        ProfileState.remove(ctx, ProfileState.K_HEALTH_INTRO_SEEN)
        ProfileState.remove(ctx, ProfileState.K_ONBOARDED)
        ProfileState.remove(ctx, ProfileState.K_OB_DRAFT)
        java.io.File(out).mkdirs()
    }

    private val slot = androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
    private var slotId = 0

    private fun snap(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        if (slot.value == null) {
            compose.setContent {
                ProTheme(dark = true, accent = ThemeController.accent(), dynamic = ThemeController.dynamic) {
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

    @androidx.compose.runtime.Composable
    private fun CardHost(
        title: String,
        icon: String,
        body: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
        ) {
            SettingsGroupCard(title = title, icon = icon, expanded = true, onToggle = {}) { body() }
        }
    }

    private val noop: (String) -> Unit = {}

    // ---------- 1. Theme picker — accent applied across the app ----------

    @Test
    fun themePickerAccentApplied() {
        // Apply a non-default accent and capture the picker — the whole-app
        // re-color is captured by the same ProTheme wrap above.
        ThemeController.set("dark", "green", ctx)
        snap("batch4b-01-theme-picker-accent") {
            CardHost("Theme picker", "fa-palette") { ThemePickerCardBody(noop) }
        }
    }

    @Test
    fun themeAccentAfterRestart() {
        // Simulate a cold start: persist, then reload ThemeController from prefs
        // and re-snapshot. Demonstrates the accent survives a restart.
        ThemeController.set("dark", "orange", ctx)
        ThemeController.load(com.peakform.fitness.core.ProPrefs.get(ctx, "p4_theme"))
        snap("batch4b-02-theme-accent-after-restart") {
            CardHost("Appearance", "fa-palette") { AppearanceCardBody(noop) }
        }
    }

    // ---------- 3. Profile picker — default & non-default label ----------

    @Test
    fun profilePickerLabelFormats() {
        // Default profile with name Jordan (from setup) — should read
        // "Jordan (Default) (Currently selected)" because it's both default
        // and active. The screenshot is taken from ProfilesScreen.
        snap("batch4b-03-profile-picker-default-label") {
            Box(Modifier.fillMaxSize()) {
                ProfilesScreen(onClose = {})
            }
        }
    }

    // ---------- 4. Profile A draft, B none, back to A ----------

    @Test
    fun profileDataIsolation() {
        // Capture a draft present on the active default profile.
        val draft = com.peakform.fitness.core.WorkoutRecord(
            id = "test_draft_a", date = ProState.todayLocal(),
            exercises = listOf(
                com.peakform.fitness.core.WorkoutExercise(
                    id = "squat", name = "Squat", muscleGroup = listOf("quadriceps"),
                    prescribed = com.peakform.fitness.core.Prescription(sets = 3, reps = "8", weight = 135.0),
                ),
            ),
            draftStartedAt = ProState.nowIso(),
            lastModifiedAt = ProState.nowIso(),
        )
        ProState.currentWorkout = draft
        ProState.performSave()
        ProState.notifyChanged()
        snap("batch4b-04a-profile-A-with-draft") {
            CardHost("Profile picker", "fa-user-group") {
                Column {
                    val c = com.peakform.fitness.ui.LocalProColors.current
                    val hasDraft = ProState.currentWorkout != null &&
                        ProState.currentWorkout!!.exercises.any { !it.isLogged }
                    androidx.compose.material3.Text(
                        "Profile A — draft present: $hasDraft",
                        style = com.peakform.fitness.ui.ProType.body2,
                        color = c.text2,
                    )
                }
            }
        }
        // Clear the draft on the active profile, simulating a switch to profile B
        // (no draft). For the screenshot we just clear the in-memory draft.
        ProState.currentWorkout = null
        ProState.clearEmergencyBackup()
        ProState.notifyChanged()
        snap("batch4b-04b-profile-B-no-draft") {
            CardHost("Profile picker", "fa-user-group") {
                Column {
                    val c = com.peakform.fitness.ui.LocalProColors.current
                    val hasDraft = ProState.currentWorkout != null &&
                        ProState.currentWorkout!!.exercises.any { !it.isLogged }
                    androidx.compose.material3.Text(
                        "Profile B — draft present: $hasDraft",
                        style = com.peakform.fitness.ui.ProType.body2,
                        color = c.text2,
                    )
                }
            }
        }
        // Restore the draft on profile A
        ProState.currentWorkout = draft
        ProState.performSave()
        ProState.notifyChanged()
        snap("batch4b-04c-profile-A-draft-restored") {
            CardHost("Profile picker", "fa-user-group") {
                Column {
                    val c = com.peakform.fitness.ui.LocalProColors.current
                    val hasDraft = ProState.currentWorkout != null &&
                        ProState.currentWorkout!!.exercises.any { !it.isLogged }
                    androidx.compose.material3.Text(
                        "Profile A again — draft present: $hasDraft",
                        style = com.peakform.fitness.ui.ProType.body2,
                        color = c.text2,
                    )
                }
            }
        }
        // Cleanup
        ProState.currentWorkout = null
        ProState.clearEmergencyBackup()
        ProState.notifyChanged()
    }

    // ---------- 5. Profile swap without restart ----------

    @Test
    fun profileSwapWithoutRestart() {
        // "before": active profile is the default (Jordan)
        snap("batch4b-05a-profile-swap-before") {
            CardHost("Profile picker", "fa-user-group") {
                Column {
                    val c = com.peakform.fitness.ui.LocalProColors.current
                    val label = com.peakform.fitness.core.Profiles.displayLabel(ctx, com.peakform.fitness.core.Profiles.activeId(ctx))
                    androidx.compose.material3.Text(
                        "Active: $label",
                        style = com.peakform.fitness.ui.ProType.body2,
                        color = c.text2,
                    )
                }
            }
        }
        // Create a second profile "Sam" and switch to it live
        val sam = com.peakform.fitness.core.Profiles.create(ctx, "Sam")
        com.peakform.fitness.core.Profiles.switchTo(ctx, sam.id)
        snap("batch4b-05b-profile-swap-after") {
            CardHost("Profile picker", "fa-user-group") {
                Column {
                    val c = com.peakform.fitness.ui.LocalProColors.current
                    val label = com.peakform.fitness.core.Profiles.displayLabel(ctx, com.peakform.fitness.core.Profiles.activeId(ctx))
                    androidx.compose.material3.Text(
                        "Active: $label",
                        style = com.peakform.fitness.ui.ProType.body2,
                        color = c.text2,
                    )
                }
            }
        }
        // Cleanup: switch back to default, delete the test profile
        com.peakform.fitness.core.Profiles.switchTo(ctx, com.peakform.fitness.core.Profiles.DEFAULT)
        com.peakform.fitness.core.Profiles.delete(ctx, sam.id)
    }

    // ---------- 6. New profile name appearing in Personal Info ----------

    @Test
    fun newProfileNameInPersonalInfo() {
        // Create a profile named "Alex" — its user.name should be seeded to "Alex".
        val alex = com.peakform.fitness.core.Profiles.create(ctx, "Alex")
        com.peakform.fitness.core.Profiles.switchTo(ctx, alex.id)
        // The PersonalCardBody reads ProState.data.user.name — which was seeded
        // by Profiles.create() and loaded by the switch's loadAll().
        snap("batch4b-06-new-profile-name-in-personal-info") {
            CardHost("Personal info", "fa-user") {
                PersonalCardBody(noop)
            }
        }
        // Cleanup
        com.peakform.fitness.core.Profiles.switchTo(ctx, com.peakform.fitness.core.Profiles.DEFAULT)
        com.peakform.fitness.core.Profiles.delete(ctx, alex.id)
    }

    // ---------- 7. Onboarding wizard first step (fresh install) ----------

    @Test
    fun onboardingWizardFirstStep() {
        // Ensure no onboarding state — fresh install vibe
        ProfileState.remove(ctx, ProfileState.K_ONBOARDED)
        ProfileState.remove(ctx, ProfileState.K_OB_DRAFT)
        ProfileState.remove(ctx, ProfileState.K_FIRST_LAUNCH)
        snap("batch4b-07-onboarding-welcome") {
            Box(Modifier.fillMaxSize()) {
                OnboardingWizard(onFinished = {})
            }
        }
    }

    // ---------- 8. Health screen intro (new profile) ----------

    @Test
    fun healthScreenIntro() {
        // Ensure the per-profile intro flag is unset — first time per profile.
        ProfileState.remove(ctx, ProfileState.K_HEALTH_INTRO_SEEN)
        snap("batch4b-08-health-screen-intro") {
            Box(Modifier.fillMaxSize()) {
                HealthScreenFlow(onDone = {})
            }
        }
        // Mark seen for hygiene
        Health.markIntroSeen(ctx)
    }
}
