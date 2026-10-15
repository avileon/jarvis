package il.co.vibit.jarvis.core.text

/**
 * Hebrew text helpers used by command parsing and contact matching.
 * Everything here is pure Kotlin so it is unit-tested on the JVM.
 */
object HebrewText {
    // Niqqud and cantillation marks (keeps maqaf U+05BE, paseq U+05C0, sof pasuq U+05C3, nun hafukha U+05C6).
    private val MARKS = Regex("[֑-ׇֽֿׁׂׅׄ]")
    private val GERESH = Regex("[׳״'`‘’´\"“”]")
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
    private val SPACES = Regex("\\s+")

    private val FINALS = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

    /** Lowercase, drop niqqud and geresh, keep only letters/digits separated by single spaces. */
    fun normalize(s: String): String {
        var t = s.lowercase()
        t = MARKS.replace(t, "")
        t = GERESH.replace(t, "")
        t = NON_WORD.replace(t, " ")
        return SPACES.replace(t, " ").trim()
    }

    /** ך→כ, ם→מ, ן→נ, ף→פ, ץ→צ (a recogniser may split/join words, moving where final forms appear). */
    fun foldFinals(s: String): String = buildString(s.length) { for (c in s) append(FINALS[c] ?: c) }

    fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    fun isHebrew(c: Char) = c in 'א'..'ת'

    fun startsWithHebrew(s: String) = s.firstOrNull()?.let(::isHebrew) == true

    /**
     * A rough consonant skeleton that survives common speech-to-text confusions:
     * ת/ט, כ/ק/ח, ס/ש/שׂ, א/ע/ה and vowel letters are dropped, וו → ב, and Latin transliterations
     * ("Shelly", "Gal") map onto the same skeleton. Used only to *suggest* candidates that then need confirmation.
     */
    fun phoneticKey(word: String): String {
        val n = foldFinals(normalize(word).replace(" ", ""))
        val heb = if (n.any { it in 'a'..'z' }) latinToHebrew(n) else n
        val sb = StringBuilder()
        var i = 0
        while (i < heb.length) {
            val c = heb[i]
            if (c == 'ו' && i + 1 < heb.length && heb[i + 1] == 'ו') { sb.append('ב'); i += 2; continue }
            val m = when (c) {
                'ת', 'ט' -> 'ט'
                'ק', 'כ', 'ח' -> 'כ'
                'ש', 'ס' -> 'ס'
                'פ' -> 'פ'
                'ב' -> 'ב'
                'צ' -> 'צ'
                'ז' -> 'ז'
                'א', 'ע', 'ה', 'ו', 'י' -> null
                else -> c
            }
            if (m != null && (sb.isEmpty() || sb.last() != m)) sb.append(m)
            i++
        }
        return sb.toString()
    }

    private fun latinToHebrew(s: String): String {
        val sb = StringBuilder()
        var i = 0
        fun at(k: Int) = if (k < s.length) s[k] else ' '
        while (i < s.length) {
            val c = s[i]
            val two = "$c${at(i + 1)}"
            when (two) {
                "sh" -> { sb.append('ש'); i += 2; continue }
                "ch", "kh" -> { sb.append('כ'); i += 2; continue }
                "tz", "ts" -> { sb.append('צ'); i += 2; continue }
                "th" -> { sb.append('ט'); i += 2; continue }
                "ph" -> { sb.append('פ'); i += 2; continue }
            }
            when (c) {
                'b', 'v', 'w' -> sb.append('ב')
                'g', 'j' -> sb.append('ג')
                'd' -> sb.append('ד')
                'z' -> sb.append('ז')
                't' -> sb.append('ט')
                'k', 'c', 'q' -> sb.append('כ')
                'l' -> sb.append('ל')
                'm' -> sb.append('מ')
                'n' -> sb.append('נ')
                's' -> sb.append('ס')
                'p', 'f' -> sb.append('פ')
                'r' -> sb.append('ר')
                'x' -> sb.append("כס")
                'h' -> sb.append('ה')
                in '0'..'9' -> sb.append(c)
                else -> {} // vowels
            }
            i++
        }
        return sb.toString()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 1.0 = identical, 0.0 = completely different. */
    fun similarity(a: String, b: String): Double {
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / max
    }

    // ---- Spoken digits ----

    private val DIGIT_WORDS = mapOf(
        "אפס" to '0',
        "אחת" to '1', "אחד" to '1',
        "שתיים" to '2', "שתים" to '2', "שניים" to '2', "שנים" to '2', "שני" to '2', "שתי" to '2',
        "שלוש" to '3', "שלושה" to '3',
        "ארבע" to '4', "ארבעה" to '4',
        "חמש" to '5', "חמישה" to '5', "חמשה" to '5',
        "שש" to '6', "שישה" to '6', "ששה" to '6',
        "שבע" to '7', "שבעה" to '7',
        "שמונה" to '8',
        "תשע" to '9', "תשעה" to '9',
    )

    /** Digit for a single spoken Hebrew digit word (with optional ו/ל prefix), or null. */
    fun digitOf(word: String): Char? {
        val w = normalize(word)
        DIGIT_WORDS[w]?.let { return it }
        if (w.length > 2 && (w[0] == 'ו' || w[0] == 'ל')) DIGIT_WORDS[w.substring(1)]?.let { return it }
        return null
    }

    private val DIGIT_NAMES = arrayOf("אפס", "אחת", "שתיים", "שלוש", "ארבע", "חמש", "שש", "שבע", "שמונה", "תשע")

    /** "0501234567" → "אפס חמש אפס, אחת שתיים שלוש, ארבע חמש שש שבע" (grouped like Israeli numbers). */
    fun spellDigits(number: String): String {
        val digits = number.filter { it.isDigit() }
        val groups = when {
            digits.length == 10 -> listOf(digits.take(3), digits.substring(3, 6), digits.substring(6))
            digits.length == 9 -> listOf(digits.take(2), digits.substring(2, 5), digits.substring(5))
            else -> digits.chunked(3)
        }
        val plus = if (number.trim().startsWith("+")) "פלוס " else ""
        return plus + groups.joinToString(", ") { g -> g.map { DIGIT_NAMES[it - '0'] }.joinToString(" ") }
    }

    /** Prefix the Hebrew preposition ל to a name ("שלי" → "לשלי", "Shelly" → "ל־Shelly"). */
    fun withLamed(name: String): String = if (startsWithHebrew(name)) "ל$name" else "ל־$name"
}
