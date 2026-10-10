package com.peakform.fitness

import com.peakform.fitness.core.Badges
import com.peakform.fitness.core.Health
import com.peakform.fitness.core.JsonHeal
import com.peakform.fitness.core.StreakFire
import com.peakform.fitness.core.Studio
import com.peakform.fitness.core.StudioDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.0 engine tests — pure-logic coverage for the parity features.
 * These run on the JVM (testReleaseUnitTest) with no Android dependencies.
 */
class ParityEngineTest {

    // ---------- JsonHeal (I2) ----------

    @Test
    fun `heals markdown code fences`() {
        val raw = "```json\n[{\"a\":1}]\n```"
        assertEquals("[{\"a\":1}]", JsonHeal.heal(raw))
    }

    @Test
    fun `heals stray leading fence without closing`() {
        val raw = "```\n[{\"a\":1}]"
        assertEquals("[{\"a\":1}]", JsonHeal.heal(raw))
    }

    @Test
    fun `removes trailing commas`() {
        assertEquals("{\"a\":[1,2]}", JsonHeal.heal("{\"a\":[1,2,],}"))
        // whitespace around the removed comma is legal JSON and is preserved
        assertEquals("{\"a\":[1,2 ] }".replace(" ", ""), JsonHeal.heal("{\"a\":[1,2 , ], }").replace(" ", ""))
    }

    @Test
    fun `converts smart quotes and ellipsis`() {
        val healed = JsonHeal.heal("{\u201Ca\u201D: \u2018b\u2019, \u201Clist\u201D: [\u201Cone\u2026two\u201D]}")
        assertTrue(healed.contains("\"a\""))
        assertTrue(healed.contains("'b'"))
        assertTrue(healed.contains("one...two"))
        assertFalse(healed.contains('\u201C'))
        assertFalse(healed.contains('\u2026'))
    }

    @Test
    fun `preserves trailing commas inside strings`() {
        assertEquals("{\"a\":\"x,y,}\"}", JsonHeal.heal("{\"a\":\"x,y,}\"}"))
    }

    @Test
    fun `parses healed json`() {
        val el = JsonHeal.parseHealed("```json\n{\"a\": [1, 2,]}\n```")
        assertNotNull(el)
    }

    @Test
    fun `unwraps exercises wrapper`() {
        val el = JsonHeal.parseHealed("{\"exercises\":[{\"name\":\"X\"}]}")
        assertNotNull(JsonHeal.unwrapExerciseArray(el!!))
    }

    @Test
    fun `normalizes exercise field aliases`() {
        val el = JsonHeal.parseHealed("{\"Name\":\"X\",\"category\":\"chest\",\"primaryMuscles\":[\"pecs\"]}")
        val norm = JsonHeal.normalizeExerciseFields(el!!)
        val obj = norm as kotlinx.serialization.json.JsonObject
        assertTrue(obj.containsKey("name"))
        assertTrue(obj.containsKey("group"))
        assertTrue(obj.containsKey("muscles"))
    }

    // ---------- StreakFire (H1/ST2/ST3) ----------

    @Test
    fun `fire tiers follow days-since thresholds`() {
        assertEquals("full", StreakFire.evaluate(10, 0).tier)
        assertEquals("full", StreakFire.evaluate(10, 2).tier)
        assertEquals("dim", StreakFire.evaluate(10, 3).tier)
        assertEquals("dim", StreakFire.evaluate(10, 4).tier)
        assertEquals("smoke", StreakFire.evaluate(10, 5).tier)
        assertEquals("smoke", StreakFire.evaluate(10, 6).tier)
        assertEquals("dead", StreakFire.evaluate(10, 7).tier)
        assertEquals("dead", StreakFire.evaluate(0, 0).tier)
    }

    @Test
    fun `fire emoji and opacity match legacy`() {
        val full = StreakFire.evaluate(5, 1)
        assertEquals("🔥", full.emoji)
        assertEquals(1f, full.opacity, 0.001f)
        val smoke = StreakFire.evaluate(5, 5)
        assertEquals("🌫️", smoke.emoji)
        assertEquals(0.5f, smoke.opacity, 0.001f)
        val dead = StreakFire.evaluate(0, 9)
        assertEquals("❄️", dead.emoji)
        assertEquals(0.4f, dead.opacity, 0.001f)
    }

    // ---------- Health tiers (HL4) ----------

    @Test
    fun `tier A when no answers`() {
        val (tier, _) = Health.computeTier(emptyMap(), emptyMap())
        assertEquals("A", tier)
    }

    @Test
    fun `tier C for chest pain`() {
        val (tier, why) = Health.computeTier(mapOf("g2" to true), emptyMap())
        assertEquals("C", tier)
        assertTrue(why.isNotEmpty())
    }

    @Test
    fun `tier C when meds changed without obstetric clearance`() {
        val (tierMeds, _) = Health.computeTier(mapOf("g5" to true), mapOf("m1" to true))
        assertEquals("C", tierMeds)
        val (tierPreg, _) = Health.computeTier(mapOf("g6" to true), emptyMap())
        assertEquals("C", tierPreg)
    }

    @Test
    fun `tier B for stable joint problem`() {
        val (tier, _) = Health.computeTier(mapOf("g4" to true), emptyMap())
        assertEquals("B", tier)
    }

    @Test
    fun `tier B upgraded to C by followup`() {
        val (tier, _) = Health.computeTier(mapOf("g1" to true), mapOf("h2" to true))
        assertEquals("C", tier)
    }

    // ---------- Joint protection (HL3) ----------

    @Test
    fun `joint mapping protects knees`() {
        assertTrue(Health.hitsProtectedJoint(listOf("quadriceps"), listOf("Knee")))
        assertFalse(Health.hitsProtectedJoint(listOf("biceps"), listOf("Knee")))
        assertTrue(Health.hitsProtectedJoint(listOf("Biceps", "Quadriceps"), listOf("Knee")))
        assertFalse(Health.hitsProtectedJoint(listOf("quadriceps"), emptyList()))
    }

    // ---------- Badge engine (B1) ----------

    @Test
    fun `badge ladders unlock at thresholds`() {
        val defs = listOf(
            Badges.BadgeDef("w1", "fa-x", "First Rep", "desc", "workouts", 1.0),
            Badges.BadgeDef("w10", "fa-x", "Committed", "desc", "workouts", 10.0),
            Badges.BadgeDef("s7", "fa-x", "Week Flame", "desc", "streak", 7.0),
            Badges.BadgeDef("v1t", "fa-x", "First Ton", "desc", "volume", 1000.0),
            Badges.BadgeDef("early", "fa-x", "Early Bird", "desc", "earlyBird", 1.0),
            Badges.BadgeDef("unknown", "fa-x", "??", "desc", "mysteryType", 1.0),
        )
        val ctx = Badges.Ctx(
            workouts = 10, streak = 3, volume = 1500.0, prs = 0, tried = 5,
            backups = 0, studioAdds = 0, imports = 0, exports = 0,
            earlyBird = false, nightOwl = false, comeback = false,
            gymSessions = 0, vocabChanged = 0, themesTried = 0,
        )
        val unlocked = Badges.evaluateDefs(defs, ctx, emptySet())
        assertEquals(listOf("w1", "w10", "v1t"), unlocked.map { it.id })
        val noneLeft = Badges.evaluateDefs(defs, ctx, setOf("w1", "w10", "v1t"))
        assertTrue(noneLeft.isEmpty())
    }

    // ---------- Studio validation (S1) ----------

    @Test
    fun `studio validation enforces legacy limits`() {
        assertNull(Studio.validate(StudioDraft(name = "X", group = "chest", muscles = listOf("pecs"))))
        assertNotNull(Studio.validate(StudioDraft(name = "", group = "chest", muscles = listOf("pecs"))))
        assertNotNull(Studio.validate(StudioDraft(name = "X", group = "", muscles = listOf("pecs"))))
        assertNotNull(Studio.validate(StudioDraft(name = "X", group = "chest", muscles = emptyList())))
        assertNotNull(Studio.validate(StudioDraft(name = "X", group = "chest", muscles = listOf("pecs"), steps = (1..9).joinToString("\n") { "s$it" })))
        assertNotNull(Studio.validate(StudioDraft(name = "X", group = "chest", muscles = listOf("pecs"), imageUrl = "http://insecure")))
        assertNull(Studio.validate(StudioDraft(name = "X", group = "chest", muscles = listOf("pecs"), imageUrl = "https://ok.example/x.png")))
    }

    @Test
    fun `studio quick validate heals a fenced paste`() {
        assertEquals(2, Studio.quickValidate("```json\n[{\"name\":\"A\"},{\"name\":\"B\"}]\n```"))
        assertEquals(1, Studio.quickValidate("{\"exercises\":[{\"Name\":\"C\",\"category\":\"chest\"}]}"))
        assertNull(Studio.quickValidate("not json"))
        assertNull(Studio.quickValidate("{\"other\":1}"))
    }
}
