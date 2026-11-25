package dev.roastbook.app.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android compiles regular expressions with ICU, which rejects a bare `}` that the JVM's engine
 * (used by every unit test) accepts as a literal. A pattern like `($|}|,)` passes here and crashes
 * on a device the first time its class loads. This finds every pattern in the app's sources and
 * checks it for a `}` that neither closes a `{m,n}` quantifier nor sits inside `[...]`.
 *
 * A pattern is any of: `Regex(` followed by string literals (raw `"""…"""` or plain `"…"`, on one
 * line or several, joined with `+`), `Regex(NAME)` for a string constant in the same file, and the
 * same literals followed by `.toRegex(`. Plain strings are unescaped first (`"\\}"` is `\}`), and a
 * template (`$NAME`, `${NAME}`) is filled in from the file's string constants; an unknown template
 * counts as empty and the rest of the pattern is still checked.
 */
class RegexPortabilityTest {
    @Test fun noRegexHasABareClosingBrace() {
        val root = File(System.getProperty("user.dir")!!, "src/main/java")
        val found = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f -> patterns(f.readText()).map { f.name to it } }.toList()
        assertTrue("expected the app's patterns, found ${found.size}", found.size > 30)
        val bad = found.filter { hasBareBrace(it.second) }.map { "${it.first}: ${it.second}" }
        assertTrue("Escape these as \\}: $bad", bad.isEmpty())
    }

    @Test fun theCheckItselfCatchesTheCase() {
        assertTrue(hasBareBrace("""^\s*($|}|,)"""))
        assertTrue(!hasBareBrace("""^\s*($|\}|,)"""))
        assertTrue(!hasBareBrace("""[,}"]"""))
        assertTrue(!hasBareBrace("""\d{2,4}"""))
    }

    @Test fun everyWayOfWritingAPatternIsFound() {
        val q = "\"\"\""
        val source = """
            val A = Regex($q($|}|,)$q)
            val B = Regex(
                ${q}x}$q,
                RegexOption.IGNORE_CASE,
            )
            val C = Regex(${q}a$q +
                ${q}b}$q)
            val D = Regex("c\\}")
            val E = Regex("d}")
            val F = "e}".toRegex()
            val G = ${q}f}$q.toRegex()
            private val NUM = $q\d{1,2}$q
            val H = Regex(NUM)
            val I = Regex("${'$'}{NUM}g}")
            val J = Regex("${'$'}{unknown(1)}h}")
            val M = Regex(${q}^(${'$'}{L.joinToString("|") { it }})\b$q)
            val K = '"' // a quote in a char literal is not a string
            // Regex("in a comment}") is not a pattern
            /* nor "this}".toRegex() */
            val L = "not a pattern}"
        """.trimIndent()
        assertEquals(
            listOf("($|}|,)", "x}", "ab}", "c\\}", "d}", "e}", "f}", "\\d{1,2}", "\\d{1,2}g}", "h}", "^()\\b"),
            patterns(source),
        )
    }

    private sealed interface Tok {
        data class Str(val text: String) : Tok
        data class Word(val text: String) : Tok
        data class Sym(val c: Char) : Tok
    }

    /** The source as string literals (templates left in), words and symbols; comments and char literals dropped. */
    private fun tokens(src: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3)
                    // A raw string may end in extra quotes ("""a""""): they belong to it.
                    var close = end
                    while (close + 3 < src.length && src[close + 3] == '"') close++
                    out += Tok.Str(src.substring(i + 3, close)); i = close + 3
                }
                c == '"' -> {
                    val sb = StringBuilder(); i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            val e = src[i + 1]
                            sb.append(when (e) { 'n' -> '\n'; 't' -> '\t'; '$' -> '\u0000'; else -> e }); i += 2
                        } else { sb.append(src[i]); i++ }
                    }
                    out += Tok.Str(sb.toString()); i++
                }
                c == '\'' -> { i++; while (i < src.length && src[i] != '\'') i += if (src[i] == '\\') 2 else 1; i++ }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    out += Tok.Word(src.substring(start, i))
                }
                c.isWhitespace() -> i++
                else -> { out += Tok.Sym(c); i++ }
            }
        }
        return out
    }

    /** Every pattern in [src], templates filled in from its string constants. */
    private fun patterns(src: String): List<String> {
        val toks = tokens(src)
        // String constants: `val NAME = "..." (+ "...")*`.
        val consts = mutableMapOf<String, String>()
        for (i in toks.indices) {
            val name = (toks.getOrNull(i + 1) as? Tok.Word)?.text ?: continue
            if ((toks[i] as? Tok.Word)?.text == "val" && toks.getOrNull(i + 2) == Tok.Sym('=')) {
                literalsAt(toks, i + 3)?.let { (raw, _) -> consts[name] = raw }
            }
        }
        // A template: `$NAME`, or `${...}` up to its matching brace (the expression may hold a lambda).
        fun fill(raw: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < raw.length) {
                if (raw.startsWith("\${", i)) {
                    var depth = 1; var j = i + 2
                    while (j < raw.length && depth > 0) { if (raw[j] == '{') depth++ else if (raw[j] == '}') depth--; j++ }
                    sb.append(consts[raw.substring(i + 2, j - 1).trim()]?.let(::fill).orEmpty()); i = j
                } else if (raw[i] == '$' && i + 1 < raw.length && (raw[i + 1].isLetter() || raw[i + 1] == '_')) {
                    var j = i + 1
                    while (j < raw.length && (raw[j].isLetterOrDigit() || raw[j] == '_')) j++
                    sb.append(consts[raw.substring(i + 1, j)]?.let(::fill).orEmpty()); i = j
                } else { sb.append(raw[i]); i++ }
            }
            return sb.toString().replace('\u0000', '$')
        }
        fun fillAll(raw: String) = fill(raw.replace("\${'\$'}", "\u0000"))

        val found = mutableListOf<String>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t == Tok.Word("Regex") && toks.getOrNull(i + 1) == Tok.Sym('(')) {
                val lit = literalsAt(toks, i + 2)
                val name = (toks.getOrNull(i + 2) as? Tok.Word)?.text
                when {
                    lit != null -> { found += fillAll(lit.first); i = lit.second; continue }
                    name != null && toks.getOrNull(i + 3) == Tok.Sym(')') && name in consts -> found += fillAll(consts.getValue(name))
                }
            } else if (t is Tok.Str) {
                val lit = literalsAt(toks, i)!!
                val end = lit.second
                if (toks.getOrNull(end) == Tok.Sym('.') && toks.getOrNull(end + 1) == Tok.Word("toRegex")) found += fillAll(lit.first)
                i = end; continue
            }
            i++
        }
        return found
    }

    /** String literals joined with `+` starting at [at]: their text and the index after them, or null. */
    private fun literalsAt(toks: List<Tok>, at: Int): Pair<String, Int>? {
        var i = at
        val sb = StringBuilder()
        while (true) {
            val s = toks.getOrNull(i) as? Tok.Str ?: return if (i == at) null else sb.toString() to i - 1
            sb.append(s.text); i++
            if (toks.getOrNull(i) != Tok.Sym('+')) return sb.toString() to i
            i++
        }
    }

    private fun hasBareBrace(p: String): Boolean {
        var i = 0; var inClass = false; var inQuant = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> inQuant = true
                c == '}' -> if (inQuant) inQuant = false else return true
            }
            i++
        }
        return false
    }
}
