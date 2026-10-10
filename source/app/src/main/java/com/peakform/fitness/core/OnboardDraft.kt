package com.peakform.fitness.core

import android.content.Context
import com.peakform.fitness.ui.ThemeController
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * OnboardDraft — resumable wizard draft (legacy p4_ob_draft, PF L56524–56531)
 * plus the finish() side effects (L56533–56573): profile write, theme, vocab,
 * consent records, history rescue, confetti trigger flag.
 *
 * BATCH-4B items 2 & 6: the draft and onboarding-complete flag are now
 * PER-PROFILE (stored in the active profile's Room `meta` table via
 * ProfileState). New profiles created after v18 inherit no onboarding state
 * from the default profile — the wizard fires for them on first switch.
 */
data class OnboardDraft(
    val step: Int = 0,
    val name: String = "",
    val birth: String = "",
    val gender: String = "unspecified",
) {
    companion object {
        const val KEY = ProfileState.K_OB_DRAFT

        fun fromPrefs(ctx: Context): OnboardDraft = try {
            val raw = ProfileState.get(ctx, KEY) ?: return OnboardDraft()
            val o = ProJson.json.parseToJsonElement(raw).jsonObject
            OnboardDraft(
                step = (o["step"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.coerceIn(0, 4) ?: 0,
                name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                birth = (o["birth"] as? JsonPrimitive)?.contentOrNull ?: "",
                gender = (o["gender"] as? JsonPrimitive)?.contentOrNull ?: "unspecified",
            )
        } catch (_: Exception) { OnboardDraft() }

        /**
         * Multi-signal new-user detection (BATCH-4B item 6).
         *
         * Returns true ONLY when none of the strong signals (onboarded flag,
         * health-screened-at, workout count, exercise count, first-launch
         * timestamp) are present. Existing users are never treated as new.
         */
        fun needed(ctx: Context): Boolean = ProfileState.isNewUser(ctx)

        fun finish(
            ctx: Context,
            name: String,
            birth: String?,
            gender: String,
            level: Int,
            mode: String,
            accentId: String,
        ) {
            // profile write
            val d = ProState.data
            ProState.data = d.copy(user = d.user.copy(
                name = name,
                birthDate = birth?.takeIf { it.isNotBlank() },
                gender = gender,
            ))
            ProState.saveWorkoutData()
            // theme + vocab
            ThemeController.set(mode, accentId, ctx)
            ProPrefs.put(ctx, "p4_theme", ThemeController.currentJson())
            Vocab.setLevel(ctx, level)
            // consents recorded in one action (L56559–56563)
            Consent.record(ctx, "terms", "Onboarding completed — Terms accepted")
            Consent.record(ctx, "privacy", "Onboarding completed — Privacy Policy accepted")
            Consent.record(ctx, "waiver", "Onboarding completed — Liability Waiver accepted")
            ProfileState.put(ctx, ProfileState.K_ONBOARDED, "true")
            ProfileState.remove(ctx, KEY)
            ProfileState.put(ctx, ProfileState.K_OB_FINISHED_AT, ProState.nowIso())
            ProfileState.stampFirstLaunch(ctx)
            ProState.notifyChanged()
        }
    }
}
