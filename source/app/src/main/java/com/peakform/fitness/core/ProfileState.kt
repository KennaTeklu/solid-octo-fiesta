package com.peakform.fitness.core

import android.content.Context
import com.peakform.fitness.ProLog
import kotlinx.coroutines.runBlocking

/**
 * ProfileState — per-profile state lives in the active profile's Room `meta` table.
 *
 * BATCH-4B items 2, 6, 7, 8: previously, onboarding flags, draft, health-screen
 * state, and first-launch timestamps were stored in the global `ProPrefs`
 * (SharedPreferences "p4_ls") — meaning every profile shared one onboarded /
 * health-screened flag, so a brand-new profile looked "existing" and skipped
 * the wizard, and a single profile's health answers leaked across switches.
 *
 * Storage boundary (BATCH-4B item 2):
 *   PER-PROFILE  (this file, meta table): onboarding flag, onboarding draft,
 *                 onboarding finished_at, first-launch timestamp, health-screen
 *                 state (screened_at / tier / general / followup / joints /
 *                 unlocked_at / declared), health-intro-seen, emergency draft
 *                 backup, vault snapshots, training preferences.
 *   GLOBAL       (ProPrefs, not here):   theme mode+accent, Material You flag,
 *                 gym mode, language, vocab level, active profile id, default
 *                 profile id, device id, consent log (legal record).
 *
 * New-user detection (BATCH-4B item 6) — a profile is "new" if NONE of:
 *   p4_onboarded, p4_health_screened_at, workout_count > 0,
 *   exercise_count > 0, p4_first_launch are present.
 *
 * Migration (one-shot): on first v18 cold start, every profile in the registry
 * inherits the legacy global p4_onboarded / p4_health_* values from ProPrefs so
 * existing users keep their "already onboarded" state and never see the wizard
 * fire again. The migration is idempotent and gated on `p4_v18_migrated`.
 */
object ProfileState {
    // Per-profile keys (Room `meta` table, active profile namespace)
    const val K_ONBOARDED = "p4_onboarded"
    const val K_OB_DRAFT = "p4_ob_draft"
    const val K_OB_FINISHED_AT = "p4_ob_finished_at"
    const val K_FIRST_LAUNCH = "p4_first_launch"
    const val K_HEALTH_SCREENED = "p4_health_screened_at"
    const val K_HEALTH_TIER = "p4_health_tier"
    const val K_HEALTH_GENERAL = "p4_health_general"
    const val K_HEALTH_FOLLOWUP = "p4_health_followup"
    const val K_HEALTH_JOINTS = "p4_health_joints"
    const val K_HEALTH_UNLOCKED = "p4_health_unlocked_at"
    const val K_HEALTH_DECLARED = "p4_health_declared"
    const val K_HEALTH_INTRO_SEEN = "p4_health_intro_seen"
    const val K_EMERGENCY_BACKUP = "workoutEmergencyBackup"
    const val K_V18_MIGRATED = "p4_v18_migrated"

    private fun dao(ctx: Context): ProDao = ProStore(ctx).db.dao()

    fun get(ctx: Context, key: String, default: String? = null): String? = try {
        dao(ctx).metaSync(key) ?: default
    } catch (_: Exception) { default }

    fun put(ctx: Context, key: String, value: String) = try {
        dao(ctx).putMetaSync(MetaRow(key, value))
    } catch (e: Exception) { ProLog.w("PROFILESTATE", "put $key failed: ${e.message}") }

    fun remove(ctx: Context, key: String) = try {
        dao(ctx).deleteMetaSync(listOf(key))
    } catch (_: Exception) {}

    fun has(ctx: Context, key: String): Boolean = get(ctx, key) != null

    fun keys(ctx: Context): List<String> = try {
        dao(ctx).metaKeysSync()
    } catch (_: Exception) { emptyList() }

    /**
     * New-user detection (BATCH-4B item 6) — a profile is "new" only if NONE of
     * the strong signals are present:
     *   - p4_onboarded (per-profile)
     *   - p4_health_screened_at (per-profile)
     *   - workout count > 0 (active profile Room workouts table)
     *   - exercise record count > 0 (active profile Room exercises table)
     *   - p4_first_launch (per-profile, set on first cold-start of this profile)
     *
     * Combination rule: if ANY signal is set, treat the profile as existing.
     * A user who wiped their workouts but kept their onboarding flag is still
     * existing. A user with only workouts but no flag (rare migration case) is
     * also existing — never re-onboard someone with workout history.
     */
    fun isNewUser(ctx: Context): Boolean {
        if (get(ctx, K_ONBOARDED) == "true") return false
        if (get(ctx, K_HEALTH_SCREENED) != null) return false
        if (ProState.data.workouts.isNotEmpty()) return false
        if (ProState.data.exercises.isNotEmpty()) return false
        if (get(ctx, K_FIRST_LAUNCH) != null) return false
        return true
    }

    /** Mark the first-launch timestamp for the active profile (idempotent —
     *  only writes if the key is missing). */
    fun stampFirstLaunch(ctx: Context) {
        if (get(ctx, K_FIRST_LAUNCH) == null) {
            put(ctx, K_FIRST_LAUNCH, ProState.nowIso())
        }
    }

    /**
     * One-shot v18 migration: copy legacy global p4_onboarded / p4_health_* /
     * workoutEmergencyBackup values from ProPrefs into EVERY existing profile's
     * Room `meta` table, so existing users keep their onboarded / screened
     * state and never see the wizard fire again after upgrade.
     *
     * Idempotent — gated on the global `p4_v18_migrated` flag. New profiles
     * created after v18 do NOT inherit the legacy flag (they're genuinely new),
     * because the migration only touches profiles that exist in the registry
     * at the moment it runs.
     */
    fun migrateFromPrefs(ctx: Context) {
        if (ProPrefs.get(ctx, K_V18_MIGRATED) == "true") return
        val profiles = Profiles.list(ctx)
        val legacyOnboarded = ProPrefs.get(ctx, K_ONBOARDED)
        val legacyObDraft = ProPrefs.get(ctx, K_OB_DRAFT)
        val legacyObFinishedAt = ProPrefs.get(ctx, K_OB_FINISHED_AT)
        val legacyHealth = mapOf(
            K_HEALTH_SCREENED to ProPrefs.get(ctx, K_HEALTH_SCREENED),
            K_HEALTH_TIER to ProPrefs.get(ctx, K_HEALTH_TIER),
            K_HEALTH_GENERAL to ProPrefs.get(ctx, K_HEALTH_GENERAL),
            K_HEALTH_FOLLOWUP to ProPrefs.get(ctx, K_HEALTH_FOLLOWUP),
            K_HEALTH_JOINTS to ProPrefs.get(ctx, K_HEALTH_JOINTS),
            K_HEALTH_UNLOCKED to ProPrefs.get(ctx, K_HEALTH_UNLOCKED),
            K_HEALTH_DECLARED to ProPrefs.get(ctx, K_HEALTH_DECLARED),
            K_EMERGENCY_BACKUP to ProPrefs.get(ctx, K_EMERGENCY_BACKUP),
        )
        for (p in profiles) {
            val dbName = Profiles.dbNameFor(ctx, p.id)
            val db = ProDb.get(ctx, dbName)
            try {
                val dao = db.dao()
                if (legacyOnboarded != null && dao.metaSync(K_ONBOARDED) == null) {
                    dao.putMetaSync(MetaRow(K_ONBOARDED, legacyOnboarded))
                }
                if (legacyObDraft != null && dao.metaSync(K_OB_DRAFT) == null) {
                    dao.putMetaSync(MetaRow(K_OB_DRAFT, legacyObDraft))
                }
                if (legacyObFinishedAt != null && dao.metaSync(K_OB_FINISHED_AT) == null) {
                    dao.putMetaSync(MetaRow(K_OB_FINISHED_AT, legacyObFinishedAt))
                }
                legacyHealth.forEach { (k, v) ->
                    if (v != null && dao.metaSync(k) == null) {
                        dao.putMetaSync(MetaRow(k, v))
                    }
                }
            } catch (e: Exception) {
                ProLog.w("MIGRATE", "v18 meta migration failed for ${p.id}: ${e.message}")
            }
        }
        ProPrefs.put(ctx, K_V18_MIGRATED, "true")
        ProLog.i("MIGRATE", "v18 meta migration complete for ${profiles.size} profiles")
    }
}
