package com.peakform.fitness.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JsonHeal — native port of the legacy SEC.healJson (PF/index.html L55091–55150).
 * Tolerates exactly the malformations the legacy app tolerated, no more, no less:
 *   1. markdown code fences (```json … ```) stripped
 *   2. trailing commas before ] or } removed
 *   3. curly/smart double quotes U+201C/U+201D → " and smart singles U+2018/U+2019 → '
 *   4. ellipsis character … → ...
 *   5. {exercises:[...]} wrapper accepted as the array itself
 *   6. exercise field aliases: Name/title → name, category → group, primaryMuscles → muscles
 *   7. BATCH-2A P0 fix: string-form special floating-point values ("NaN", NaN,
 *      "-NaN", "Infinity", "-Infinity", quoted OR bare, in value positions) → null
 * NOT tolerated (matching legacy): BOM, em-dash keys, ASCII single-quoted keys are left
 * to the strict parser (isLenient already handles unquoted keys in kotlinx-serialization).
 */
object JsonHeal {

    /** Heal an arbitrary pasted JSON string; returns the healed text. */
    fun heal(raw: String): String {
        var s = raw
        // 3. smart quotes → ASCII (before fence stripping; a fence may carry them)
        s = s.replace('\u201C', '"').replace('\u201D', '"')
        s = s.replace('\u2018', '\'').replace('\u2019', '\'')
        // 4. ellipsis
        s = s.replace("\u2026", "...")
        // 1. markdown code fences
        s = stripFences(s)
        // 2. trailing commas
        s = stripTrailingCommas(s)
        // 7. BATCH-2A: special floating-point values in value positions → null
        //    (quoted "NaN" from the HTML writer + bare NaN from non-conforming writers)
        s = replaceSpecialValues(s)
        return s.trim()
    }

    private val SPECIAL_VALUES = setOf("NaN", "-NaN", "Infinity", "-Infinity")

    /**
     * BATCH-2A P0 fix (part 3). Replace special floating-point VALUE tokens —
     * both the quoted form the HTML writer emits ("averageRPE": "NaN") and the
     * bare unquoted form ("averageRPE": NaN) — with null, so the strict parser
     * never sees a special float. String-aware: tokens inside string literals
     * are only replaced when the literal itself is the value (i.e. not followed
     * by ':' which marks a key, and only when preceded by ':', '[' or ','), so
     * prose like "set 1, NaN, felt heavy" in a notes field is left untouched.
     */
    fun replaceSpecialValues(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var i = 0
        fun prevMeaningful(idx: Int): Char {
            var j = idx - 1
            while (j >= 0 && s[j].isWhitespace()) j--
            return if (j >= 0) s[j] else ' '
        }
        fun nextMeaningful(idx: Int): Char {
            var j = idx
            while (j < s.length && s[j].isWhitespace()) j++
            return if (j < s.length) s[j] else ' '
        }
        while (i < s.length) {
            val c = s[i]
            if (c == '"') {
                // copy the whole string literal verbatim, then decide by context
                val start = i
                i++
                while (i < s.length) {
                    if (s[i] == '\\' && i + 1 < s.length) { i += 2; continue }
                    if (s[i] == '"') { i++; break }
                    i++
                }
                val literal = s.substring(start, i)
                val isKey = nextMeaningful(i) == ':'
                val body = if (literal.length >= 2) literal.substring(1, literal.length - 1) else ""
                if (!isKey && body in SPECIAL_VALUES) sb.append("null") else sb.append(literal)
                continue
            }
            if (c == 'N' || c == 'I' || c == '-') {
                val prev = prevMeaningful(i)
                if (prev == ':' || prev == '[' || prev == ',') {
                    var j = i
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '-')) j++
                    val token = s.substring(i, j)
                    if (token in SPECIAL_VALUES) { sb.append("null"); i = j; continue }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun stripFences(s: String): String {
        val t = s.trim()
        // ```json\n...\n``` or ```\n...\n``` (3+ backticks tolerated on the fence lines)
        val fenced = Regex("^`{3,}[a-zA-Z]*\\s*([\\s\\S]*?)\\s*`{3,}$").find(t)
        if (fenced != null) return fenced.groupValues[1].trim()
        // stray leading fence line without a closing fence (truncated paste)
        if (t.startsWith("```")) {
            val firstBreak = t.indexOf('\n')
            if (firstBreak > 0) return t.substring(firstBreak + 1).trim()
        }
        return t
    }

    fun stripTrailingCommas(s: String): String {
        val sb = StringBuilder(s.length)
        var inStr = false
        var quote = ' '
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                sb.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1]); i += 2; continue
                }
                if (c == quote) inStr = false
                i++; continue
            }
            when (c) {
                '"', '\'' -> { inStr = true; quote = c; sb.append(c) }
                ',' -> {
                    // remove when followed only by whitespace and a closing bracket (string-aware)
                    var j = i + 1
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j < s.length && (s[j] == '}' || s[j] == ']')) {
                        // skip the comma entirely
                    } else {
                        sb.append(c)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Parse healed JSON into a JsonElement, applying the legacy wrapper sniffing. */
    fun parseHealed(raw: String): JsonElement? {
        val healed = heal(raw)
        return try {
            ProJson.json.parseToJsonElement(healed)
        } catch (_: Exception) {
            null
        }
    }

    /** Legacy exercise payload aliases (SEC.validateExercisePayload normalization). */
    fun normalizeExerciseFields(el: JsonElement): JsonObject {
        if (el !is JsonObject) return el as? JsonObject ?: JsonObject(emptyMap())
        val map = el.toMutableMap()
        fun alias(target: String, vararg sources: String) {
            if (map.containsKey(target)) return
            for (srcKey in sources) {
                val v = map[srcKey]
                if (v is JsonPrimitive && v.content.isNotBlank()) {
                    map[target] = JsonPrimitive(v.content)
                    break
                } else if (v != null && v !is JsonNull) {
                    map[target] = v
                    break
                }
            }
        }
        alias("name", "Name", "title")
        alias("group", "category")
        alias("muscles", "primaryMuscles")
        return JsonObject(map)
    }

    /**
     * Accept an exercise payload that is either a bare array, an {exercises:[...]} wrapper,
     * or an {items:[...]} wrapper — legacy Studio import sniffing (L55111–55113).
     */
    fun unwrapExerciseArray(el: JsonElement): JsonArray? = when {
        el is JsonArray -> el
        el is JsonObject && el["exercises"] is JsonArray -> el["exercises"] as JsonArray
        el is JsonObject && el["items"] is JsonArray -> el["items"] as JsonArray
        else -> null
    }
}
