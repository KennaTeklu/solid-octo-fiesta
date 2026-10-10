package com.peakform.fitness.core

import android.content.Context

/**
 * MessageBank — phase-aware coach messages (PF SmartMessageBank L36297–36477), subset port.
 * Tone adapts to sarcasmMode; cycle phases adjust the pool; level-banded selection (G6).
 */
object MessageBank {

    data class Msg(val text: String, val tone: String = "normal", val phase: String? = null)

    private val BANK = listOf(
        Msg("Small wins stack. Show up, log it, repeat."),
        Msg("Form first. The numbers follow."),
        Msg("You don't have to feel 100% to do 80% well."),
        Msg("Consistency beats intensity over any month you pick."),
        Msg("Warm up like it matters — because it does.", phase = null),
        Msg("Light day is still a day. Log it."),
        Msg("Rest is training. Take it seriously.", phase = "menstrual"),
        Msg("Follicular window — great time to push a little.", phase = "follicular"),
        Msg("Ovulatory strength peak — use it, don't chase PRs blindly.", phase = "ovulatory"),
        Msg("Luteal phase: expect heavier perceived effort. That's normal.", phase = "luteal"),
        Msg("Oh, you're back? The weights missed you. They cried.", tone = "sarcastic"),
        Msg("Yes, that set counted. No, the dramatic sigh doesn't burn calories.", tone = "sarcastic"),
        Msg("Bold strategy: skipping leg day since forever.", tone = "sarcastic"),
        Msg("Your excuses called — they want a group chat with your alarms.", tone = "sarcastic"),
    )

    fun pick(ctx: Context, phase: String? = null): String {
        val sarcasm = ProState.data.user.settings.sarcasmMode
        val level = Vocab.currentLevel(ctx)
        val band = Vocab.band(level)
        val pool = BANK.filter { m ->
            val phaseOk = m.phase == null || m.phase == phase
            val toneOk = sarcasm || m.tone != "sarcastic"
            // band 1 keeps it simple: short non-sarcastic lines only when L1-3
            val bandOk = band > 1 || (m.tone == "normal" && m.phase == null && m.text.length < 60)
            phaseOk && toneOk && bandOk
        }.ifEmpty { BANK.filter { it.tone == "normal" } }
        return pool.random().text
    }

    /** Load multipliers by cycle phase (PF L35275–35296). */
    fun phaseMultiplier(phase: String?): Double = when (phase) {
        "menstrual" -> 0.6
        "follicular" -> 1.0
        "ovulatory" -> 1.05
        "luteal" -> 0.8
        else -> 1.0
    }
}
