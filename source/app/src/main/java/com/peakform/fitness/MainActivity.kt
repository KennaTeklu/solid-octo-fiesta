package com.peakform.fitness

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.engine.Library
import com.peakform.fitness.core.MigrationBridge
import com.peakform.fitness.ProLog
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.ProPrefs
import com.peakform.fitness.ui.components.BtnStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.DisposableEffect
import com.peakform.fitness.engine.Generator
import com.peakform.fitness.engine.Stats
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.LuxuryStartButton
import com.peakform.fitness.ui.components.P4Button
import com.peakform.fitness.ui.components.SectionTitle
import com.peakform.fitness.ui.screens.*
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.type
import com.peakform.fitness.core.Backup
import com.peakform.fitness.core.Badges
import com.peakform.fitness.core.Health
import com.peakform.fitness.core.Notify
import com.peakform.fitness.core.OnboardDraft
import com.peakform.fitness.core.Profiles
import com.peakform.fitness.core.ProfileRegistry
import com.peakform.fitness.core.StreakFire
import com.peakform.fitness.core.Vocab

/**
 * MainActivity — 100% native shell. No WebView UI anywhere; the only WebView is the
 * invisible one-time migration shuttle (MigrationBridge).
 * 1.4.0: onboarding gate, ask-on-boot profile picker, command palette, confetti host,
 * share-to-app import, deep-link section routing (shortcuts/notifications/tile).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Fa.appContext = applicationContext
        ProState.init(this)
        // FIX (audit): EngineHooks.rebuildDerived was never assigned — after imports the
        // derived aggregates/fatigue were not rebuilt until some other engine call happened.
        com.peakform.fitness.core.EngineHooks.rebuildDerived = {
            com.peakform.fitness.engine.Fatigue.rebuildFromHistory()
            com.peakform.fitness.engine.Fatigue.calculateMuscleLastTrained()
            com.peakform.fitness.engine.Sessions.recomputeAggregates()
        }
        ThemeController.load(ProPrefs.get(this, "p4_theme"))
        ThemeController.loadDynamic(ProPrefs.get(this, "p4_dynamic"))
        Vocab.init(this)
        Badges.init(this)
        applyScreenshotSafeMode()
        // resume section from launcher shortcut / notification / tile
        if (intent?.getStringExtra("section") != null) {
            ProPrefs.put(this, "p4_pending_section", intent.getStringExtra("section")!!)
        }
        // share-to-app (NA6): stash the payload for the import dialog
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                ProPrefs.put(this, "p4_share_text", it)
            }
        }

        setContent {
            val scope = rememberCoroutineScope()
            val ctx = LocalContext.current

            // boot: hydrate data, then run migration if needed
            var booted by remember { mutableStateOf(false) }
            var migrationPhase by remember { mutableStateOf<String?>(null) }
            var migrationInfo by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                // BATCH-4B item 2/6: one-shot v18 meta migration — copy legacy
                // global p4_onboarded / p4_health_* into every profile's Room
                // `meta` table so existing users keep their onboarded state.
                com.peakform.fitness.core.ProfileState.migrateFromPrefs(ctx)
                ProState.loadAll()
                // BATCH-4B item 6: stamp the active profile's first-launch
                // timestamp (a new-user signal — never overwrites if present).
                com.peakform.fitness.core.ProfileState.stampFirstLaunch(ctx)
                Library.ensure(ctx)
                booted = true
                if (!MigrationBridge.isDone(ctx) && !MigrationBridge.running) {
                    migrationPhase = "checking"
                    val found = MigrationBridge.run(ctx) { p ->
                        migrationPhase = p.phase
                        migrationInfo = when {
                            p.workouts > 0 -> "${p.workouts} workouts · ${p.exercises} exercise records"
                            p.profile.isNotBlank() -> p.profile
                            else -> null
                        }
                    }
                    ProLog.i("MIGRATE", "migration finished: $found")
                    migrationPhase = if (found) "complete" else "skipped"
                    kotlinx.coroutines.delay(1800)
                    migrationPhase = null
                    ProState.notifyChanged()
                }
            }

            ProTheme(dark = ThemeController.dark, accent = ThemeController.accent(), dynamic = ThemeController.dynamic) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(LocalProColors.current.bg, LocalProColors.current.bg)
                            )
                        )
                ) {
                    if (!booted) {
                        BootSplash()
                    } else {
                        AppShell(
                            migrationPhase = migrationPhase,
                            migrationInfo = migrationInfo,
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        ProState.flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // shortcuts/notifications route while running
        intent.getStringExtra("section")?.let {
            ProPrefs.put(this, "p4_pending_section", it)
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                ProPrefs.put(this, "p4_share_text", it)
            }
        }
        setIntent(intent)
    }

    /** NA7: screenshot-safe mode hides content in the recents strip and screenshots. */
    private fun applyScreenshotSafeMode() {
        if (ProPrefs.get(this, "p4_secure") == "on") {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
    }
}

@Composable
fun BootSplash() {
    val c = LocalProColors.current
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.peakform.fitness.ui.FaIcon("fa-dumbbell", size = 26.sp, tint = c.accent)
                Spacer(Modifier.width(10.dp))
                Text("Pro", style = ProType.brand, color = c.text)
            }
            Spacer(Modifier.height(12.dp))
            Text("Loading your training data…", style = ProType.body2, color = c.text3)
        }
    }
}

@Composable
fun AppShell(
    migrationPhase: String?,
    migrationInfo: String?,
) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    var section by remember { mutableStateOf(ProPrefs.get(ctx, "p4_pending_section") ?: "dashboard") }
    // consume the pending route
    LaunchedEffect(Unit) {
        if (!ProPrefs.get(ctx, "p4_pending_section").isNullOrBlank()) ProPrefs.remove(ctx, "p4_pending_section")
    }
    var sheetOpen by remember { mutableStateOf(false) }
    var timeWizard by remember { mutableStateOf(false) }
    var genLongevity by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    var showStreakRecovery by remember { mutableStateOf(false) }
    var confettiTick by remember { mutableIntStateOf(0) }
    val snack = remember { mutableStateOf<String?>(null) }
    val shellScope = rememberCoroutineScope()

    // badge award queue: staggered toasts + confetti(90)
    var badgeQueue by remember { mutableStateOf(listOf<com.peakform.fitness.core.Badges.BadgeDef>()) }
    LaunchedEffect(Unit) {
        val res = com.peakform.fitness.core.Badges.evaluate(ctx)
        if (res.unlocked.isNotEmpty()) {
            res.unlocked.forEach { b ->
                kotlinx.coroutines.delay(1600)
                badgeQueue = badgeQueue + b
                confettiTick++
            }
        }
    }

    // share-to-app: pre-import confirmation with the shared payload
    val shareText = remember { ProPrefs.get(ctx, "p4_share_text") }
    var showShareImport by remember { mutableStateOf(!shareText.isNullOrBlank()) }

    // ask-on-boot profile picker (only when multiple profiles and boot picker enabled)
    val askProfiles = remember { Profiles.shouldAskOnBoot(ctx) }
    var showProfilePicker by remember { mutableStateOf(askProfiles) }

    // onboarding gate: BATCH-4B item 6/7 — multi-signal new-user detection.
    // Re-evaluates on every ProState.notifyChanged() so a freshly switched
    // profile (e.g. brand-new "Alex" with no data) re-runs the wizard.
    val onboardingVer = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { onboardingVer.intValue++ }
        ProState.listeners.add(l)
        onDispose { ProState.listeners.remove(l) }
    }
    val needOnboarding = remember(onboardingVer.intValue) {
        OnboardDraft.needed(ctx) && ProState.data.workouts.isEmpty()
    }
    var onboardingActive by remember { mutableStateOf(needOnboarding) }
    LaunchedEffect(needOnboarding) {
        // BATCH-4B item 7: when the active profile flips to "new" (e.g. a freshly
        // created profile with no data), surface the wizard; when it flips back
        // to "existing", dismiss it. Idempotent — the wizard's own finish also
        // dismisses via onFinished.
        if (needOnboarding && !onboardingActive) onboardingActive = true
    }

    // POST_NOTIFICATIONS runtime request (Android 13+), once, after onboarding
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ProLog.i("NOTIFY", "POST_NOTIFICATIONS granted=$granted")
    }
    LaunchedEffect(onboardingActive) {
        if (!onboardingActive && !Notify.hasPermission(ctx) && android.os.Build.VERSION.SDK_INT >= 33 &&
            ProPrefs.get(ctx, "p4_notif_asked") == null) {
            ProPrefs.put(ctx, "p4_notif_asked", "1")
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // hardware keyboard Ctrl/Cmd+K (legacy palette trigger)
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyUp &&
            (e.key == Key.K) &&
            (e.isCtrlPressed || e.isMetaPressed)) {
            paletteOpen = !paletteOpen
            true
        } else false
    }) {

    // FIX (audit): the shell must re-compose when data changes (draft badge, hasDraft wizard step).
    val ver = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { ver.intValue++ }
        ProState.listeners.add(l)
        onDispose { ProState.listeners.remove(l) }
    }

    val hasDraft = remember(ver.intValue) {
        ProState.currentWorkout != null && ProState.currentWorkout!!.exercises.any { !it.isLogged }
    }

    /** Generate with an optional time budget, then land the user on the workout screen (legacy showSection('workout')). */
    fun generateAndGo(budget: Int?) {
        // Tier C soft lock (HL5): browsing stays free; generation blocked until clearance
        if (Health.isGenerationLocked(ctx)) {
            snack.value = "A doctor needs to clear you first — open Health to review."
            section = "health"
            return
        }
        shellScope.launch {
            Generator.performGenerateWorkout(budget)
            section = "workout"
        }
    }

    fun go(target: String) {
        sheetOpen = false
        if (target == "commands") { paletteOpen = true; return }
        section = when (target) {
            "resume" -> "workout"
            "export", "backup" -> "settings"
            "themes" -> "settings"
            "long" -> "recovery"
            else -> target
        }
    }

    // FIX (audit): the Longevity Workout was a dead end — the flag was written but never read.
    LaunchedEffect(genLongevity) {
        if (genLongevity) {
            genLongevity = false
            Generator.generateLongevityWorkout()
            section = "workout"
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ---- onboarding gate (full-screen until finished) ----
        if (onboardingActive) {
            OnboardingWizard(onFinished = {
                onboardingActive = false
                confettiTick++ // confetti(140) at finish
                snack.value = "Welcome to Pro, ${ProState.data.user.name.ifBlank { "athlete" }}!"
            })
        } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- top bar (56dp compact navbar) ----
            TopBar(
                onAvatarTap = { section = "profiles" },
                onFireTap = {
                    val streakNow = Stats.calculateStreak()
                    if (streakNow <= 0 && !StreakFire.recoveryUsedToday()) showStreakRecovery = true
                    else section = "dashboard"
                },
                onResumeTap = { section = "workout" },
                onPaletteTap = { paletteOpen = true },
                hasDraft = hasDraft,
            )

            // ---- migration banner ----
            AnimatedVisibility(visible = migrationPhase != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.accentSoft)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.peakform.fitness.ui.FaIcon("fa-cloud-arrow-down", size = 13.sp, tint = c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (migrationPhase) {
                            "checking" -> "Checking for data from the old app…"
                            "reading" -> "Importing legacy data${if (migrationInfo.isNullOrBlank()) "" else " — $migrationInfo"}…"
                            "importing" -> "Merging legacy data…"
                            "complete" -> "Legacy data imported ✓${if (migrationInfo.isNullOrBlank()) "" else " — $migrationInfo"}"
                            "skipped" -> "No legacy data found — starting fresh"
                            else -> ""
                        },
                        style = ProType.small, color = c.accent,
                    )
                }
            }

            // ---- screen content ----
            Box(Modifier.weight(1f)) {
                val scroll = rememberScrollState()
                Crossfade(targetState = section, animationSpec = androidx.compose.animation.core.tween(180), label = "screens") { s ->
                    when (s) {
                        "dashboard" -> DashboardScreen(
                            onStartWorkout = {
                                // FIX (audit): the wizard always shows first (legacy flow); the
                                // Continue/Replace step only applies when a draft exists.
                                timeWizard = true
                            },
                            onResumeWorkout = { section = "workout" },
                            onOpenSection = { go(it) },
                        )
                        "workout" -> WorkoutScreen(
                            onRequireGenerate = { generateAndGo(null) },
                            onOpenSection = { go(it) },
                        )
                        "history" -> HistoryScreen(onOpenSection = { go(it) })
                        "progress" -> ProgressScreen(onOpenSection = { go(it) })
                        "recovery" -> RecoveryScreen(
                            onOpenSection = { go(it) },
                            onRequireGenerate = { kind ->
                                if (kind == "longevity") genLongevity = true else generateAndGo(null)
                            },
                        )
                        "library" -> LibraryScreen(onAddToWorkout = { ex ->
                            val cw = ProState.currentWorkout
                            if (cw == null) {
                                snack.value = "Generate a workout first, then add exercises."
                            } else {
                                // FIX (audit): weight = ex.defaultDuration assigned SECONDS as lbs
                                // ("plank @ 480 lbs"). Use the engine's next recommended weight,
                                // and keep duration where it belongs.
                                val rec = ProState.data.exercises[ex.id]
                                val isTime = ex.prescriptionType == "time"
                                val newEx = com.peakform.fitness.core.WorkoutExercise(
                                    id = ex.id, name = ex.name, muscleGroup = ex.muscles,
                                    prescribed = com.peakform.fitness.core.Prescription(
                                        sets = ex.defaultSets, reps = ex.defaultReps,
                                        weight = if (isTime) null else rec?.nextWeight,
                                        duration = if (isTime) ex.defaultDuration else null,
                                    ),
                                    progressionNotes = ex.progression, equipment = ex.equipment,
                                    instructions = ex.instructions, prescriptionType = ex.prescriptionType,
                                    loadDistribution = ex.muscles.associateWith { 1.0 / ex.muscles.size },
                                    noFatigue = ex.noFatigue, fitnessComponents = ex.fitnessComponents,
                                    defaultDuration = ex.defaultDuration, strengthIndex = ex.strengthIndex,
                                )
                                ProState.currentWorkout = cw.copy(exercises = cw.exercises + newEx)
                                ProState.performSave()
                                ProState.notifyChanged()
                                snack.value = "${ex.name} added to today's workout"
                                section = "workout"
                            }
                        })
                        "settings" -> SettingsScreen(onOpenSection = { go(it) })
                        "health" -> HealthScreenFlow(onDone = { go("dashboard") }, onCancel = { go("dashboard") })
                        "legal" -> LegalCenterScreen(onClose = { go("settings") })
                        "studio" -> StudioScreen(onOpenSection = { go(it) })
                        "glossary" -> GlossaryScreen(onClose = { go("settings") })
                        "profiles" -> ProfilesScreen(onClose = { go("dashboard") })
                        "snapshots" -> SnapshotsScreen(onClose = { go("settings") })
                        "pasteimport" -> PasteJsonScreen(onClose = { go("settings") })
                        "feedback" -> FeedbackScreen(onClose = { go("settings") })
                        "mycycle" -> MyCycleScreen(onClose = { go("dashboard") })
                        "badges" -> BadgeGalleryScreen()
                    }
                }
            }

            // ---- dock (outside scroll content — cannot disappear) ----
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).navigationBarsPadding()) {
                GlassDock(
                    activeTab = if (section == "more") "dashboard" else section,
                    hasDraft = hasDraft,
                    onSelect = { id ->
                        if (id == "more") sheetOpen = true else go(id)
                    },
                )
            }
        }

        // More sheet
        MoreSheet(
            visible = sheetOpen,
            onDismiss = { sheetOpen = false },
            onSelect = { go(it) },
            quickChips = {
                P4Button(if (ThemeController.dark) "Light" else "Dark", onClick = {
                    val newMode = if (ThemeController.dark) "light" else "dark"
                    ThemeController.set(newMode, ThemeController.accentId, ctx)
                }, icon = if (ThemeController.dark) "fa-sun" else "fa-moon", style = BtnStyle.SECONDARY, minHeight = 36)
                P4Button("Export", onClick = { go("settings") }, icon = "fa-file-export", style = BtnStyle.SECONDARY, minHeight = 36)
                P4Button("Resume", onClick = { go("workout") }, icon = "fa-play", style = BtnStyle.SECONDARY, minHeight = 36)
            },
        )

        // time wizard
        if (timeWizard) {
            TimeWizardDialog(
                hasDraft = hasDraft,
                onDismiss = { timeWizard = false },
                onContinue = { timeWizard = false; section = "workout" },
                onGenerate = { budget ->
                    timeWizard = false
                    generateAndGo(budget)
                },
            )
        }

        // snack
        snack.value?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2200)
                snack.value = null
            }
            Box(Modifier.fillMaxWidth().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
                com.peakform.fitness.ui.screens.ToastBanner(msg)
            }
        }
        } // end else (onboarding gate)

        // confetti host — triggers: badge unlocks, onboarding finish, completion, PRs
        ConfettiOverlay(trigger = confettiTick)

        // badge award toast (staggered queue)
        badgeQueue.lastOrNull()?.let { b ->
            LaunchedEffect(b.id) {
                kotlinx.coroutines.delay(2600)
                badgeQueue = badgeQueue.dropLast(1)
            }
            Box(Modifier.fillMaxWidth().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
                com.peakform.fitness.ui.screens.ToastBanner("Badge unlocked: ${b.name} — ${b.desc}")
            }
        }

        // H2/ST3/ST4: honor-system streak recovery modal (legacy copy verbatim)
        if (showStreakRecovery) {
            StreakRecoveryDialog(
                onDismiss = { showStreakRecovery = false },
                onShare = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, StreakFire.RECOVERY_SHARE_TEXT)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(send, "Share"))
                },
                onRelight = {
                    showStreakRecovery = false
                    StreakFire.performRecovery()
                    snack.value = StreakFire.RECOVERY_TOAST
                    confettiTick++
                },
            )
        }

        // command palette (Ctrl+K / long-press)
        CommandPalette(
            visible = paletteOpen,
            onDismiss = { paletteOpen = false },
            onRun = { id ->
                paletteOpen = false
                if (id != "palette_close") go(id)
            },
        )

        // share-to-app pre-import confirmation (I1 copy, verbatim legacy)
        if (showShareImport && shareText != null) {
            ImportWarningDialog(
                onConfirm = {
                    showShareImport = false
                    shellScope.launch {
                        val res = Backup.import(ctx, shareText)
                        ProPrefs.remove(ctx, "p4_share_text")
                        snack.value = res.message
                        Badges.bumpCounter(ctx, "imports")
                    }
                },
                onDismiss = {
                    showShareImport = false
                    ProPrefs.remove(ctx, "p4_share_text")
                },
            )
        }

        // ask-on-boot profile picker
        if (showProfilePicker && !onboardingActive) {
            Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.92f))) {
                ProfilesScreen(onClose = { showProfilePicker = false })
            }
        }
        } // end key-handler Box
    }
}

/** 56dp compact glass navbar — 1.4 header: fire tiers, emoji avatar, resume pill, palette. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopBar(
    onAvatarTap: () -> Unit,
    onFireTap: () -> Unit = {},
    onResumeTap: () -> Unit = {},
    onPaletteTap: () -> Unit = {},
    hasDraft: Boolean = false,
) {
    val c = LocalProColors.current
    // FIX (audit): streak/name were remember{} — frozen forever. Re-read on every ProState change.
    val ver = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { ver.intValue++ }
        ProState.listeners.add(l)
        onDispose { ProState.listeners.remove(l) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(c.glass)
            .combinedClickable(onClick = onPaletteTap, onLongClick = onPaletteTap)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // brand doubles as the palette trigger (H6)
        com.peakform.fitness.ui.FaIcon("fa-dumbbell", size = 17.sp, tint = c.accent)
        Spacer(Modifier.width(8.dp))
        Text("Pro", style = ProType.brand, fontSize = 19.sp, color = c.text)
        Spacer(Modifier.width(12.dp))
        // H1: streak fire with 4-state intensity tiers
        val streak = remember(ver.intValue) { Stats.calculateStreak() }
        val last = ProState.data.workouts.lastOrNull { it.isCompleted }?.date
        val daysSince = if (last == null) 99 else ((System.currentTimeMillis() - ProState.utcDayMillis(last)) / (24L * 3600 * 1000)).toInt()
        val fire = StreakFire.evaluate(streak, daysSince)
        Text(
            fire.emoji,
            fontSize = 15.sp,
            modifier = Modifier.alpha(fire.opacity).clickable { onFireTap() },
        )
        Spacer(Modifier.width(3.dp))
        Text("${streak}", style = ProType.label, color = c.text, modifier = Modifier.clickable { onFireTap() })
        Spacer(Modifier.width(12.dp))
        // H5: Resume Workout pill
        if (hasDraft) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.accentSoft)
                    .border(1.dp, c.accentLine, RoundedCornerShape(999.dp))
                    .clickable { onResumeTap() }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.peakform.fitness.ui.FaIcon("fa-play", size = 9.sp, tint = c.accent)
                    Spacer(Modifier.width(4.dp))
                    Text("Resume Workout", fontSize = 10.sp, color = c.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val user = ProState.data.user
        val name = user.name.trim().split(Regex("\\s+")).firstOrNull().takeUnless { it.isNullOrEmpty() } ?: "Athlete"
        val exp = user.experience.replaceFirstChar { it.uppercase() }.ifBlank { "Beginner" }
        // H3: emoji animal avatar (profile emoji, legacy animal set)
        val emoji = remember(ver.intValue) {
            val (profiles, active, _) = ProfileRegistry.read(com.peakform.fitness.ui.Fa.appContext ?: return@remember null)
            profiles.firstOrNull { it.id == active }?.emoji
        } ?: Profiles.animalEmojiFor(user.name.ifBlank { "Athlete" })
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.clickable { onAvatarTap() }) {
            Text(name, style = ProType.small, color = c.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("$exp • $streak Day Streak", fontSize = 9.sp, color = c.text3)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.accentGradient)
                .clickable { onAvatarTap() },
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 18.sp)
        }
    }
}

/** Start-flow wizard: Continue-or-Replace + time budget (FIX #15, 1:1). */
@Composable
fun TimeWizardDialog(hasDraft: Boolean, onDismiss: () -> Unit, onContinue: () -> Unit, onGenerate: (Int?) -> Unit) {
    val c = LocalProColors.current
    var step by remember { mutableIntStateOf(if (hasDraft) 0 else 1) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.glass2,
        shape = RoundedCornerShape(24.dp),
        title = { Text(if (step == 0) "Start a workout" else "How long do you have?", style = ProType.cardTitle, color = c.text) },
        text = {
            if (step == 0) {
                Text(
                    "You already have a workout in progress. Continue it, or replace it with a fresh one?",
                    style = ProType.body2, color = c.text2,
                )
            } else {
                Column {
                    Text("We'll fit today's session to your available time — warm-up always stays.", style = ProType.body2, color = c.text2)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 25, 40).forEach { m -> TimeCell(m.toString(), Modifier.weight(1f), onClick = { onGenerate(m) }) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(60, 90).forEach { m -> TimeCell("$m", Modifier.weight(1f), onClick = { onGenerate(m) }) }
                        TimeCell("All day", Modifier.weight(1f), onClick = { onGenerate(null) }, showMin = false)
                    }
                }
            }
        },
        confirmButton = {
            if (step == 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // FIX (audit): Continue now actually navigates to the workout screen.
                    P4Button("Continue Workout", onClick = onContinue, style = BtnStyle.PRIMARY)
                    P4Button("Generate New", onClick = { step = 1 }, style = BtnStyle.SECONDARY)
                }
            }
        },
        dismissButton = {
            if (step == 0) P4Button("Cancel", onClick = onDismiss, style = BtnStyle.GHOST)
        },
    )
}

@Composable
private fun TimeCell(label: String, modifier: Modifier, onClick: () -> Unit, showMin: Boolean = true) {
    val c = LocalProColors.current
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface2)
            .border(2.dp, c.hairline2, RoundedCornerShape(14.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (showMin) "$label min" else label, style = ProType.label, color = c.text, textAlign = TextAlign.Center)
    }
}
