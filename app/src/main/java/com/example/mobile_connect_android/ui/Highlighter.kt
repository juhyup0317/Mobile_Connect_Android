package com.example.mobile_connect_android.ui

/** What a piece of text is, for coloring. */
enum class SyntaxKind(val color: Int) {
    // Close to Xcode's dark theme, the same as the iOS app.
    KEYWORD(0xFFFF7AB2.toInt()),
    STRING(0xFFFF8170.toInt()),
    COMMENT(0xFF7F8C98.toInt()),
    NUMBER(0xFFD9C97C.toInt()),
    TYPE(0xFF6BDFFF.toInt()),
    SPECIAL(0xFFFFA14F.toInt()),
    URL(0xFF65B2FF.toInt()),
    ERROR(0xFFFF6B6B.toInt()),
    WARNING(0xFFFFB454.toInt()),
    INFO(0xFF6FDC8C.toInt()),
}

/** How to read one kind of file: its words, comments and quotes, plus a few extra patterns. */
class SyntaxLanguage(
    val keywords: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val quotes: Set<Char> = emptySet(),
    val tripleQuotes: Boolean = false,
    val ignoresCase: Boolean = false,
    val coloursTypeNames: Boolean = false,
    val coloursNumbers: Boolean = true,
    val rules: List<Rule> = emptyList()
) {
    class Rule(
        pattern: String,
        val kind: SyntaxKind,
        anchorsMatchLines: Boolean = false,
        /** Also colors text inside strings (JSON keys are strings). */
        val overridesStrings: Boolean = false
    ) {
        val regex = if (anchorsMatchLines) Regex(pattern, RegexOption.MULTILINE) else Regex(pattern)
    }

    companion object {
        private fun words(list: String) = list.split(" ").toSet()

        val python = SyntaxLanguage(
            keywords = words("False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield self match case print"),
            lineComments = listOf("#"), quotes = setOf('"', '\''), tripleQuotes = true, coloursTypeNames = true,
            rules = listOf(Rule("""^\s*@[\w.]+""", SyntaxKind.SPECIAL, anchorsMatchLines = true)))

        val javascript = SyntaxLanguage(
            keywords = words("async await break case catch class const continue debugger default delete do else enum export extends false finally for from function if implements import in instanceof interface let new null of package private protected public return static super switch this throw true try type typeof undefined var void while with yield"),
            lineComments = listOf("//"), blockComments = listOf("/*" to "*/"), quotes = setOf('"', '\'', '`'), coloursTypeNames = true)

        val swift = SyntaxLanguage(
            keywords = words("actor as associatedtype async await break case catch class continue default defer deinit do else enum extension fallthrough false fileprivate for func guard if import in init inout internal is let nil open operator private protocol public repeat rethrows return self Self static struct subscript super switch throw throws true try typealias var where while"),
            lineComments = listOf("//"), blockComments = listOf("/*" to "*/"), quotes = setOf('"'), coloursTypeNames = true,
            rules = listOf(Rule("""@\w+""", SyntaxKind.SPECIAL)))

        /** C, C++, Java, Go, Rust, Kotlin, C# and PHP share enough that one set of words serves them all. */
        val cLike = SyntaxLanguage(
            keywords = words("abstract auto bool boolean break byte case catch char chan class const continue crate default defer delete do double else enum extends extern false final finally float fn for func fun go goto if impl implements import in include inline instanceof int interface let long loop match mod move mut namespace new nil null object override package private protected pub public range readonly ref return select short signed sizeof static string struct super switch template this throw throws trait true try type typedef typeof union unsafe unsigned use using val var virtual void volatile when where while yield"),
            lineComments = listOf("//"), blockComments = listOf("/*" to "*/"), quotes = setOf('"', '\''), coloursTypeNames = true,
            rules = listOf(Rule("""^\s*#\s*\w+""", SyntaxKind.SPECIAL, anchorsMatchLines = true)))

        val shell = SyntaxLanguage(
            keywords = words("if then else elif fi for while until do done case esac in function select time return exit break continue export local readonly declare source alias unset echo cd test"),
            lineComments = listOf("#"), quotes = setOf('"', '\''), coloursNumbers = false,
            rules = listOf(Rule("""\$\{?[A-Za-z_][A-Za-z0-9_]*\}?|\$[0-9@#?*!${'$'}]""", SyntaxKind.SPECIAL, overridesStrings = true)))

        val json = SyntaxLanguage(
            keywords = words("true false null"), quotes = setOf('"'),
            rules = listOf(Rule(""""(?:[^"\\\n]|\\.)*"(?=\s*:)""", SyntaxKind.SPECIAL, overridesStrings = true)))

        val yaml = SyntaxLanguage(
            keywords = words("true false null yes no on off True False"),
            lineComments = listOf("#"), quotes = setOf('"', '\''),
            rules = listOf(Rule("""^\s*-?\s*[\w.\-/ ]+(?=\s*:(\s|${'$'}))""", SyntaxKind.SPECIAL, anchorsMatchLines = true)))

        val ini = SyntaxLanguage(
            keywords = words("true false yes no on off"),
            lineComments = listOf("#", ";"), quotes = setOf('"', '\''),
            rules = listOf(
                Rule("""^\s*\[[^\]\n]+\]""", SyntaxKind.KEYWORD, anchorsMatchLines = true),
                Rule("""^\s*[\w.\-]+(?=\s*[=:])""", SyntaxKind.SPECIAL, anchorsMatchLines = true)))

        val markup = SyntaxLanguage(
            blockComments = listOf("<!--" to "-->"), quotes = setOf('"', '\''), coloursNumbers = false,
            rules = listOf(
                Rule("""</?[A-Za-z][\w:\-]*|/?>""", SyntaxKind.KEYWORD),
                Rule("""\s[\w:\-]+(?==)""", SyntaxKind.SPECIAL),
                Rule("""&\w+;""", SyntaxKind.NUMBER)))

        val css = SyntaxLanguage(
            keywords = words("important none auto inherit initial block inline flex grid absolute relative fixed sticky solid dashed"),
            blockComments = listOf("/*" to "*/"), quotes = setOf('"', '\''),
            rules = listOf(
                Rule("""[\w\-]+(?=\s*:[^{]*;)""", SyntaxKind.SPECIAL),
                Rule("""#[0-9a-fA-F]{3,8}\b""", SyntaxKind.NUMBER),
                Rule("""[.#][A-Za-z_][\w\-]*(?=[^;{}]*\{)""", SyntaxKind.TYPE)))

        val sql = SyntaxLanguage(
            keywords = words("select from where and or not in is null insert into values update set delete create table alter drop index join left right inner outer on group by order having limit offset as distinct union all case when then else end primary key foreign references default unique count sum avg min max like between exists"),
            lineComments = listOf("--"), blockComments = listOf("/*" to "*/"), quotes = setOf('\'', '"'), ignoresCase = true)

        val markdown = SyntaxLanguage(
            coloursNumbers = false,
            rules = listOf(
                Rule("""^#{1,6}\s.*${'$'}""", SyntaxKind.KEYWORD, anchorsMatchLines = true),
                Rule("""`[^`\n]+`""", SyntaxKind.STRING),
                Rule("""^\s*(?:[-*+]|\d+\.)\s""", SyntaxKind.SPECIAL, anchorsMatchLines = true),
                Rule("""\[[^\]\n]+\]\([^)\n]+\)""", SyntaxKind.TYPE),
                Rule("""\*\*[^*\n]+\*\*""", SyntaxKind.SPECIAL)))

        /** Plain text and logs: no code, so make the useful bits stand out. */
        val plain = SyntaxLanguage(
            quotes = setOf('"'),
            rules = listOf(
                Rule("""https?://[^\s<>"')\]]+""", SyntaxKind.URL, overridesStrings = true),
                Rule("""[\w.+\-]+@[\w\-]+(?:\.[\w\-]+)+""", SyntaxKind.URL),
                Rule("""^\s*[A-Z][A-Za-z0-9 _\-/]{1,40}:(?=\s|${'$'})""", SyntaxKind.SPECIAL, anchorsMatchLines = true),
                Rule("""\b(?:ERROR|FATAL|CRITICAL|FAIL(?:ED|URE)?|Exception|Traceback)\b""", SyntaxKind.ERROR),
                Rule("""\b(?:WARN(?:ING)?|DEPRECATED|TODO|FIXME)\b""", SyntaxKind.WARNING),
                Rule("""\b(?:INFO|SUCCESS|OK|PASSED?|DONE)\b""", SyntaxKind.INFO)))

        /** The language for a file path, by extension or well-known name. */
        fun detect(path: String): SyntaxLanguage {
            val name = path.substringAfterLast('/').lowercase()
            when (name) {
                "makefile", "dockerfile", ".bashrc", ".zshrc", ".profile", ".bash_profile" -> return shell
                ".env", ".gitconfig", ".editorconfig" -> return ini
            }
            return when (name.substringAfterLast('.', "")) {
                "py", "pyw", "pyi" -> python
                "js", "jsx", "mjs", "cjs", "ts", "tsx" -> javascript
                "swift" -> swift
                "c", "h", "cc", "cpp", "cxx", "hpp", "m", "mm", "java", "go", "rs", "kt", "kts", "cs", "php", "scala", "dart", "gradle" -> cLike
                "sh", "bash", "zsh", "fish", "command" -> shell
                "json", "jsonc", "geojson" -> json
                "yml", "yaml" -> yaml
                "toml", "ini", "cfg", "conf", "properties", "env" -> ini
                "html", "htm", "xml", "svg", "plist", "xhtml", "vue" -> markup
                "css", "scss", "less" -> css
                "sql" -> sql
                "md", "markdown" -> markdown
                else -> plain
            }
        }
    }
}

/** Finds the colored pieces of a text. Pure, so it runs off the main thread. */
object Highlighter {
    /** Bigger files stay plain: coloring them would cost more than it helps on a phone. */
    const val MAX_LENGTH = 200_000

    private val numberRegex = Regex("""\b0[xX][0-9a-fA-F]+\b|\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""")
    private val wordRegex = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")

    /**
     * Colored runs as (start, end, color) triples, in order and never overlapping, so they can be compared with the
     * spans already on screen. Later rules win where they overlap, as on iOS.
     */
    fun runs(text: String, language: SyntaxLanguage): IntArray {
        val n = text.length
        if (n == 0 || n > MAX_LENGTH) return IntArray(0)
        val mask = ByteArray(n)  // 1 = comment, 2 = string: words inside are left alone
        val colors = IntArray(n)

        fun mark(start: Int, end: Int, kind: SyntaxKind, value: Byte) {
            for (k in start until end) {
                mask[k] = value
                colors[k] = kind.color
            }
        }
        fun paint(range: IntRange, kind: SyntaxKind) {
            for (k in range) colors[k] = kind.color
        }

        val triples = if (language.tripleQuotes) listOf("\"\"\"", "'''") else emptyList()
        var i = 0
        scan@ while (i < n) {
            for ((open, close) in language.blockComments) {
                if (text.startsWith(open, i)) {
                    val found = text.indexOf(close, i + open.length)
                    val end = if (found < 0) n else found + close.length
                    mark(i, end, SyntaxKind.COMMENT, 1)
                    i = end
                    continue@scan
                }
            }
            for (marker in language.lineComments) {
                if (text.startsWith(marker, i)) {
                    val found = text.indexOf('\n', i)
                    val end = if (found < 0) n else found
                    mark(i, end, SyntaxKind.COMMENT, 1)
                    i = end
                    continue@scan
                }
            }
            for (marker in triples) {
                if (text.startsWith(marker, i)) {
                    val found = text.indexOf(marker, i + 3)
                    val end = if (found < 0) n else found + 3
                    mark(i, end, SyntaxKind.STRING, 2)
                    i = end
                    continue@scan
                }
            }
            val c = text[i]
            if (c in language.quotes) {
                var end = i + 1
                while (end < n && text[end] != c) {
                    if (text[end] == '\\') end++ else if (text[end] == '\n' && c != '`') break
                    end++
                }
                end = minOf(end + (if (end < n && text[end] == c) 1 else 0), n)
                mark(i, end, SyntaxKind.STRING, 2)
                i = end
                continue
            }
            i++
        }

        if (language.coloursNumbers) {
            for (m in numberRegex.findAll(text)) if (mask[m.range.first] == 0.toByte()) paint(m.range, SyntaxKind.NUMBER)
        }
        if (language.keywords.isNotEmpty() || language.coloursTypeNames) {
            for (m in wordRegex.findAll(text)) {
                if (mask[m.range.first] != 0.toByte()) continue
                val word = m.value
                if ((if (language.ignoresCase) word.lowercase() else word) in language.keywords) {
                    paint(m.range, SyntaxKind.KEYWORD)
                } else if (language.coloursTypeNames && word[0].isUpperCase() && word.any { it.isLowerCase() }) {
                    paint(m.range, SyntaxKind.TYPE)
                }
            }
        }
        for (rule in language.rules) {
            for (m in rule.regex.findAll(text)) {
                if (m.range.isEmpty()) continue
                if (rule.overridesStrings || mask[m.range.first] == 0.toByte()) paint(m.range, rule.kind)
            }
        }

        // Collapse the per-character colors into runs.
        val out = ArrayList<Int>()
        var k = 0
        while (k < n) {
            val color = colors[k]
            if (color == 0) { k++; continue }
            val start = k
            while (k < n && colors[k] == color) k++
            out.add(start); out.add(k); out.add(color)
        }
        return out.toIntArray()
    }
}
