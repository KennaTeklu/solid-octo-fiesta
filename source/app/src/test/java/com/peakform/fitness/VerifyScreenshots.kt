package com.peakform.fitness

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.peakform.fitness.core.Badges
import com.peakform.fitness.core.Health
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.ProPrefs
import com.peakform.fitness.core.Vocab
import com.peakform.fitness.core.WorkoutExercise
import com.peakform.fitness.core.Prescription
import com.peakform.fitness.core.WorkoutRecord
import com.peakform.fitness.engine.Library
import com.peakform.fitness.TopBar
import com.peakform.fitness.ui.ProTheme
import com.peakform.fitness.ui.ShareSheet
import com.peakform.fitness.ui.screens.BadgeGalleryScreen
import com.peakform.fitness.ui.screens.DashboardScreen
import com.peakform.fitness.ui.screens.ExerciseDetailSheet
import com.peakform.fitness.ui.screens.GlossaryScreen
import com.peakform.fitness.ui.screens.HealthScreenFlow
import com.peakform.fitness.ui.screens.HistoryScreen
import com.peakform.fitness.ui.screens.LegalCenterScreen
import com.peakform.fitness.ui.screens.LibraryScreen
import com.peakform.fitness.ui.screens.MuscleWikiSheet
import com.peakform.fitness.ui.screens.MyCycleScreen
import com.peakform.fitness.ui.screens.OnboardingWizard
import com.peakform.fitness.ui.screens.ProfilesScreen
import com.peakform.fitness.ui.screens.ProgressScreen
import com.peakform.fitness.ui.screens.RecoveryScreen
import com.peakform.fitness.ui.screens.SettingsScreen
import com.peakform.fitness.ui.screens.SnapshotsScreen
import com.peakform.fitness.ui.screens.StudioScreen
import com.peakform.fitness.ui.screens.WorkoutScreen
import com.peakform.fitness.ui.screens.RestTimer
import com.peakform.fitness.ui.screens.LongevityCircleCard
import com.peakform.fitness.ui.screens.StrengthForecastCard
import com.peakform.fitness.ui.screens.GoalCycleCard
import com.peakform.fitness.ui.screens.VolumeBars
import com.peakform.fitness.ui.screens.ComponentRadar
import com.peakform.fitness.ui.screens.FrequencyStrip
import com.peakform.fitness.ui.screens.RpeHistogram
import com.peakform.fitness.ui.screens.VolumeChart
import com.peakform.fitness.ui.screens.VolumeByMuscle7d
import com.peakform.fitness.ui.screens.FiveYearMonteCarlo
import com.peakform.fitness.ui.screens.MuscleSparklines
import com.peakform.fitness.ui.screens.RecordsBoard
import com.peakform.fitness.ui.screens.projection3MonthPublic
import com.peakform.fitness.ui.components.SectionTitle
import com.peakform.fitness.ui.components.GlassCard
import com.peakform.fitness.ui.screens.CompositeChart
import com.peakform.fitness.ui.screens.HealthClearanceCardBody
import com.peakform.fitness.ui.screens.PrivacyCardBody
import com.peakform.fitness.ui.screens.AboutCardBody
import com.peakform.fitness.ui.screens.AppearanceCardBody
import androidx.compose.foundation.layout.fillMaxWidth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot harness (Roborazzi + Robolectric native graphics).
 * Renders the exact Compose screens that ship in the release APK and saves PNGs to
 * docs/verify/ — one screenshot per shipped feature, named after the feature.
 *
 * Method note (disclosed in README.md + CHANGES.md): captures are JVM-rendered from
 * the same Compose code compiled into the APK — this machine has no KVM, so a
 * device emulator could not boot. The renderer exercises the same composables,
 * themes and data paths as the APK.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
class VerifyScreenshots {

    private val out = "/home/z/my-project/work/docs/verify"

    @get:org.junit.Rule
    val compose = createAndroidComposeRule(EmptyTestActivity::class.java)

    private lateinit var ctx: Context

    @Before
    fun setup() {
        System.setProperty("roborazzi.enabled", "true")
        ctx = RuntimeEnvironment.getApplication()
        com.peakform.fitness.ui.Fa.appContext = ctx
        ProState.init(ctx)
        kotlinx.coroutines.runBlocking {
            if (!ProState.initialized) ProState.loadAll()
        }
        Library.ensure(ctx)
        Vocab.init(ctx)
        Badges.init(ctx)
        // seed a realistic athlete profile
        ProState.data = ProState.data.copy(
            user = ProState.data.user.copy(
                name = "Jordan", experience = "intermediate", gender = "male",
                weight = 180.0, height = 70.0, birthDate = "1996-04-12",
            ),
        )
        java.io.File(out).mkdirs()
    }

    // single setContent per test; each snap() swaps the hosted composable under a fresh key
    private val slot = androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
    private var slotId = 0

    private fun snap(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        if (slot.value == null) {
            compose.setContent {
                ProTheme(dark = true, accent = com.peakform.fitness.ui.ACCENTS[0]) {
                    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
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
        // render the activity's decor view onto a PNG (Robolectric native graphics)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val w = view.width.coerceAtLeast(412)
            val h = view.height.coerceAtLeast(892)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            view.draw(canvas)
            java.io.File("$out/$name.png").outputStream().use { os ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
            }
        }
    }

    private fun seedDraft() {
        ProState.currentWorkout = WorkoutRecord(
            id = "w_seed", date = ProState.todayLocal(), type = "push", name = "Push Day A",
            exercises = listOf(
                WorkoutExercise(
                    id = "bench_press", name = "Barbell Bench Press", muscleGroup = listOf("chest"),
                    prescribed = Prescription(sets = 3, reps = "8-12", weight = 135.0), equipment = "barbell",
                    instructions = listOf("Set the bar", "Lower to chest", "Press up"),
                ),
                WorkoutExercise(
                    id = "overhead_press", name = "Overhead Press", muscleGroup = listOf("deltoids"),
                    prescribed = Prescription(sets = 3, reps = "6-10", weight = 85.0), equipment = "barbell",
                ),
            ),
        )
        ProState.performSave()
    }

    // ---- O: onboarding ----

    @Test
    fun onboardingSteps() {
        listOf(0 to "feature-onboarding-welcome", 1 to "feature-onboarding-birthdate",
            2 to "feature-onboarding-reading-level", 3 to "feature-onboarding-theme",
            4 to "feature-onboarding-consent").forEach { (step, file) ->
            // BATCH-4B item 2: draft now lives in per-profile Room meta, not ProPrefs.
            com.peakform.fitness.core.ProfileState.put(ctx, OnboardDraftKey, """{"step":$step,"name":"Jordan","birth":"1996-04-12","gender":"male"}""")
            snap(file) { OnboardingWizard(onFinished = {}) }
        }
    }

    private val OnboardDraftKey get() = com.peakform.fitness.core.ProfileState.K_OB_DRAFT

    @Test
    fun healthScreen() {
        // BATCH-4B item 8: per-profile intro — first-time call shows the intro.
        com.peakform.fitness.core.ProfileState.remove(ctx, Health.K_INTRO_SEEN)
        snap("feature-health-screen-intro") { HealthScreenFlow(onDone = {}) }
        // mark intro seen so subsequent snaps go straight to questions/joints/result
        Health.markIntroSeen(ctx)
        snap("feature-health-screen") { HealthScreenFlow(onDone = {}) }
        // joints phase
        snap("feature-health-joints") { HealthScreenFlow(onDone = {}, startPhase = "joints") }
        // tier C result
        Health.saveScreen(ctx, Health.ScreenData(
            general = mapOf("g2" to true), followup = emptyMap(), joints = listOf("Knee"),
            tier = "C", screenedAt = ProState.nowIso(),
        ))
        snap("feature-health-verdict") { HealthScreenFlow(onDone = {}, startPhase = "result") }
    }

    @Test
    fun dashboard() {
        seedDraft()
        // tier C banner visible — BATCH-4B item 2: per-profile Health state
        com.peakform.fitness.core.ProfileState.put(ctx, Health.K_TIER, "C")
        snap("feature-dashboard") { DashboardScreen(onStartWorkout = {}, onResumeWorkout = {}, onOpenSection = {}) }
        com.peakform.fitness.core.ProfileState.remove(ctx, Health.K_TIER)
    }

    @Test
    fun library() {
        snap("feature-library") { LibraryScreen(onAddToWorkout = {}) }
        val ex = Library.allLibraryExercises("gym").firstOrNull() ?: return
        snap("feature-library-detail") { ExerciseDetailSheet(exercise = Library.augmented(ex), onClose = {}, onAdd = {}) }
    }

    @Test
    fun muscleWiki() {
        snap("feature-muscle-wiki") { MuscleWikiSheet(muscleKey = "chest", onClose = {}) }
    }

    @Test
    fun workoutAndShare() {
        seedDraft()
        snap("feature-workout") { WorkoutScreen(onRequireGenerate = {}, onOpenSection = {}) }
        // BATCH-2: rest timer running — set the app-scope RestTimer state directly
        // (no coroutine needed for a static screenshot)
        RestTimer.total = 120
        RestTimer.secondsLeft = 87
        snap("feature-workout-rest-timer") { WorkoutScreen(onRequireGenerate = {}, onOpenSection = {}) }
        RestTimer.stop()
        val w = ProState.currentWorkout ?: return
        snap("feature-workout-share-qr") { ShareSheet(workout = w, onDismiss = {}) }
    }

    @Test
    fun progressCharts() {
        snap("feature-progress") { ProgressScreen(onOpenSection = {}) }
    }

    // ---- BATCH-2: individual Progress chart screenshots (13 minimum) ----
    @Test
    fun progressChartItems() {
        seedDraft()
        // seed some completed workouts so the charts have data
        ProState.data = ProState.data.copy(
            workouts = ProState.data.workouts + listOf(
                WorkoutRecord(
                    id = "w_done_1", date = ProState.todayLocal(), type = "push", name = "Push Day",
                    dateCompleted = ProState.nowIso(),
                    exercises = listOf(
                        WorkoutExercise(
                            id = "bench_press", name = "Bench Press", muscleGroup = listOf("chest"),
                            prescribed = Prescription(sets = 3, reps = "8-12", weight = 135.0),
                            actual = com.peakform.fitness.core.ActualPerformance(weight = 135.0, sets = 3, reps = listOf(10.0, 8.0, 8.0), firstRPE = 8.0, volume = 3510.0),
                            equipment = "barbell",
                        ),
                        WorkoutExercise(
                            id = "squat", name = "Back Squat", muscleGroup = listOf("quadriceps"),
                            prescribed = Prescription(sets = 3, reps = "5-8", weight = 185.0),
                            actual = com.peakform.fitness.core.ActualPerformance(weight = 185.0, sets = 3, reps = listOf(6.0, 5.0, 5.0), firstRPE = 9.0, volume = 2960.0),
                            equipment = "barbell",
                        ),
                    ),
                    summary = com.peakform.fitness.core.WorkoutSummary(totalVolume = 6470.0),
                ),
            ),
        )
        // 1. longevity circle
        snap("progress-01-longevity-circle") {
            GlassCardHost2 { SectionTitle("fa-heart-pulse", "Longevity Score"); LongevityCircleCard() }
        }
        // 2. strength forecast (forecastTotal + forecastWilks)
        snap("progress-02-strength-forecast") {
            GlassCardHost2 { SectionTitle("fa-weight-hanging", "Strength Forecast"); StrengthForecastCard() }
        }
        // 3. volume bars (last 8 workouts)
        snap("progress-03-volume-bars") {
            GlassCardHost2 { SectionTitle("fa-chart-simple", "Volume — Last 8 Workouts"); VolumeBars(1) }
        }
        // 4. 11-component radar
        snap("progress-04-component-radar") {
            GlassCardHost2 { SectionTitle("fa-bullseye", "11-Component Radar"); ComponentRadar(1) }
        }
        // 5. 8-week frequency chart
        snap("progress-05-frequency-strip") {
            GlassCardHost2 { SectionTitle("fa-calendar-week", "Workout Frequency — 8 Weeks"); FrequencyStrip(1) }
        }
        // 6. RPE histogram
        snap("progress-06-rpe-histogram") {
            GlassCardHost2 { SectionTitle("fa-gauge", "RPE Trends"); RpeHistogram(1) }
        }
        // 7. volume chart (line, BATCH-2 addition)
        snap("progress-07-volume-chart") {
            GlassCardHost2 { SectionTitle("fa-chart-line", "Volume Trend (per session)"); VolumeChart(1) }
        }
        // 8. strength trend (composite chart)
        snap("progress-08-strength-trend") {
            GlassCardHost2 {
                val composite = com.peakform.fitness.engine.Stats.dashboardComposite(null)
                SectionTitle("fa-arrow-trend-up", "Strength Trend (est. 1RM)")
                CompositeChart(points = composite)
            }
        }
        // 9. records board
        snap("progress-09-records-board") {
            GlassCardHost2 { SectionTitle("fa-trophy", "Records Board"); RecordsBoard() }
        }
        // 10. goal cycle status
        snap("progress-10-goal-cycle") {
            GlassCardHost2 { SectionTitle("fa-flag-checkered", "Goal Cycle"); GoalCycleCard() }
        }
        // 11. 3-month projection
        snap("progress-11-3month-projection") {
            GlassCardHost2 {
                val composite = com.peakform.fitness.engine.Stats.dashboardComposite(null)
                SectionTitle("fa-arrow-trend-up", "3-Month Projection")
                val p = projection3MonthPublic(composite)
                androidx.compose.material3.Text(p ?: "Log a few workouts to see your projection.", color = androidx.compose.ui.graphics.Color.White)
            }
        }
        // 12. 5-year Monte Carlo forecast
        snap("progress-12-5yr-monte-carlo") {
            GlassCardHost2 { SectionTitle("fa-dice", "5-Year Forecast (Monte Carlo)"); FiveYearMonteCarlo(1) }
        }
        // 13. per-muscle sparklines
        snap("progress-13-per-muscle-sparklines") {
            GlassCardHost2 { SectionTitle("fa-wave-square", "Per-Muscle Sparklines (4 weeks)"); MuscleSparklines(1) }
        }
        // 14. volume by muscle (7 days, BATCH-2 addition)
        snap("progress-14-volume-by-muscle-7d") {
            GlassCardHost2 { SectionTitle("fa-dumbbell", "Volume by Muscle — Last 7 Days"); VolumeByMuscle7d(1) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun GlassCardHost2(inner: @androidx.compose.runtime.Composable () -> Unit) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxWidth().padding(16.dp),
        ) { inner() }
    }

    @Test
    fun legalCenter() {
        snap("feature-legal-center") { LegalCenterScreen(onClose = {}) }
    }

    @Test
    fun studio() {
        snap("feature-studio") { StudioScreen(onOpenSection = {}) }
    }

    @Test
    fun glossary() {
        snap("feature-glossary") { GlossaryScreen(onClose = {}) }
    }

    @Test
    fun badges() {
        snap("feature-badges") { BadgeGalleryScreen() }
    }

    @Test
    fun profiles() {
        snap("feature-profiles") { ProfilesScreen(onClose = {}) }
    }

    @Test
    fun snapshots() {
        snap("feature-snapshots") { SnapshotsScreen(onClose = {}) }
    }

    @Test
    fun myCycle() {
        snap("feature-my-cycle") { MyCycleScreen(onClose = {}) }
    }

    @Test
    fun settings() {
        snap("feature-settings") { SettingsScreen(onOpenSection = {}) }
    }

    @Test
    fun historyAndRecovery() {
        snap("feature-history") { HistoryScreen(onOpenSection = {}) }
        snap("feature-recovery") { RecoveryScreen(onOpenSection = {}, onRequireGenerate = {}) }
    }

    // ---- 1.4 header, palette, and dialog captures ----

    @Test
    fun headerStates() {
        // full fire: a completed workout today + a live draft
        ProState.data = ProState.data.copy(workouts = listOf(
            WorkoutRecord(
                id = "w_done", date = ProState.todayLocal(), type = "push", name = "Push Day",
                dateCompleted = ProState.nowIso(),
            ),
        ))
        seedDraft()
        snap("feature-header") {
            androidx.compose.foundation.layout.Column {
                com.peakform.fitness.TopBar(
                    onAvatarTap = {}, onFireTap = {}, onResumeTap = {}, onPaletteTap = {}, hasDraft = true,
                )
            }
        }
        // dead fire: no workouts, no draft
        ProState.data = ProState.data.copy(workouts = emptyList())
        ProState.currentWorkout = null
        snap("feature-header-dead-fire") {
            androidx.compose.foundation.layout.Column {
                com.peakform.fitness.TopBar(
                    onAvatarTap = {}, onFireTap = {}, onResumeTap = {}, onPaletteTap = {}, hasDraft = false,
                )
            }
        }
    }

    @Test
    fun commandPalette() {
        snap("feature-command-palette") { com.peakform.fitness.ui.CommandPalette(visible = true, onDismiss = {}, onRun = {}) }
    }

    @Test
    fun dialogs() {
        snap("feature-streak-recovery") { com.peakform.fitness.ui.StreakRecoveryDialog(onDismiss = {}, onShare = {}, onRelight = {}) }
        snap("feature-import-warning") { com.peakform.fitness.ui.ImportWarningDialog(onConfirm = {}, onDismiss = {}) }
        snap("feature-momentum-dialog") { com.peakform.fitness.ui.MomentumDialog(onBigger = {}, onStronger = {}, onDismiss = {}) }
        snap("feature-snapshot-restore-confirm") { com.peakform.fitness.ui.RestoreConfirmDialog("Snapshot · Oct 6, 09:12", onConfirm = {}, onDismiss = {}) }
    }

    @Test
    fun settingsCards() {
        snap("feature-settings-health-clearance") { SettingsCardHost { HealthClearanceCardBody({}, {}) } }
        snap("feature-settings-privacy") { SettingsCardHost { PrivacyCardBody({}) } }
        snap("feature-settings-about") { SettingsCardHost { AboutCardBody({}) } }
        snap("feature-settings-appearance") { SettingsCardHost { AppearanceCardBody({}) } }
    }

    @androidx.compose.runtime.Composable
    private fun SettingsCardHost(inner: @androidx.compose.runtime.Composable () -> Unit) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxWidth().padding(16.dp),
        ) { inner() }
    }
}
