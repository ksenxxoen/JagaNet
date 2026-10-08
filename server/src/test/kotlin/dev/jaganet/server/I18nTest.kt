package dev.jaganet.server

import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every product's texts: each `t("…")` / `tp(n, "…")` in the apps, the server and the website
 * has Russian and German, and the house style holds (no long dashes, no "·" separators,
 * at most one colon per text).
 */
class I18nTest {
    private val root = File("..").canonicalFile
    private val sources: List<File> = listOf("composeApp/src", "server/src/main", "shared/src/commonMain")
        .flatMap { File(root, it).walk().filter { f -> f.isFile && (f.extension == "kt" || f.extension == "js") }.toList() }
        .filterNot { "/i18n/strings/" in it.path }

    /** t("…"), tp(n, "…"), and server errors: notFound("…"), badRequest("…"), AppError(…, "…"), translate(…, "…"). */
    private val keyCall = Regex(
        """\b(?:t\(|tp\([^,()]+,\s*|notFound\(|badRequest\(|AppError\(\d+,\s*ErrorCode\.\w+,\s*|translate\([^,()]+(?:\([^)]*\))?,\s*|HealthCheck\("\w+",\s*[^",]+?,\s*)"((?:[^"\\]|\\.)*)"""",
    )

    private fun keysIn(f: File): List<String> = keyCall.findAll(f.readText())
        .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n") }.toList()

    @Test fun `every text in the code has Russian and German`() {
        val known = I18n.all.map { it.en }.toSet()
        val missing = sources.flatMap { f -> keysIn(f).filter { it !in known }.map { "${f.relativeTo(root)}: \"$it\"" } }.distinct()
        assertTrue(missing.isEmpty(), "Untranslated (add them to shared/.../i18n/strings):\n" + missing.joinToString("\n"))
        val templated = sources.flatMap { f -> keysIn(f).filter { '$' in it }.map { "${f.relativeTo(root)}: \"$it\"" } }
        assertTrue(templated.isEmpty(), "Use {placeholders}, not \$templates, inside t(): \n" + templated.joinToString("\n"))
    }

    @Test fun `translations are consistent`() {
        val dup = I18n.all.groupBy { it.en }.filter { (_, v) -> v.map { it.ru to it.de }.distinct().size > 1 }.keys
        assertTrue(dup.isEmpty(), "Same English text with different translations: $dup")
        val ph = Regex("""\{[a-zA-Z]+}""")
        for (s in I18n.all) {
            val want = ph.findAll(s.en).map { it.value }.toSet()
            assertEquals(want, ph.findAll(s.ru).map { it.value }.toSet(), "placeholders in RU of \"${s.en}\"")
            assertEquals(want, ph.findAll(s.de).map { it.value }.toSet(), "placeholders in DE of \"${s.en}\"")
            if ('|' in s.en) {
                assertEquals(3, s.ru.split('|').size, "Russian needs 3 plural forms: \"${s.en}\"")
                assertEquals(2, s.de.split('|').size, "German needs 2 plural forms: \"${s.en}\"")
            }
        }
    }

    @Test fun `house style has no long dashes, dot separators or several colons`() {
        val bad = I18n.all.flatMap { listOf(it.en, it.ru, it.de) }.filter { styleProblem(it) != null }.map { "\"$it\": ${styleProblem(it)}" }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        // Hard-coded strings in the UI code too.
        val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
        val inCode = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                literal.findAll(code).map { it.groupValues[1] }.firstOrNull { it.any { c -> c in BANNED } }
                    ?.let { "${f.relativeTo(root)}:${i + 1}: \"$it\"" }
            }
        }
        assertTrue(inCode.isEmpty(), "Long dash or · in UI strings:\n" + inCode.joinToString("\n"))
    }

    @Test fun `Russian is the default`() = assertEquals(Lang.RU, Lang.DEFAULT)

    private companion object {
        const val BANNED = "—–·"
        fun styleProblem(s: String): String? = when {
            s.any { it in BANNED } -> "long dash or ·"
            s.count { it == ':' } > 1 -> "more than one colon"
            else -> null
        }
    }
}
