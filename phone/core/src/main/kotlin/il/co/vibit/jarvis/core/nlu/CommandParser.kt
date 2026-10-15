package il.co.vibit.jarvis.core.nlu

import il.co.vibit.jarvis.core.text.HebrewText

/**
 * Rule-based Hebrew command parser. Works fully offline and is tolerant to the variations that
 * speech recognisers produce ("ג'ארביס" / "גארביס" / "Jarvis", "תתקשרי" / "תתקשר", "לשלי" / "ל שלי").
 */
object CommandParser {

    private val LEADING_FILLERS = setOf("היי", "הי", "hey", "hi", "אוקיי", "אוקי", "ok", "okay", "אהלן", "יו", "הלו", "שלום")
    private val POLITE = setOf("בבקשה", "אפשר", "תוכלי", "תוכל", "את", "יכולה", "רק", "בואי", "בוא", "נא", "מהר", "עכשיו", "תודה")

    private val CALL_VERBS = setOf(
        "תתקשרי", "תתקשר", "התקשרי", "התקשר", "להתקשר", "תתקשרו", "תקשרי",
        "תחייגי", "תחייג", "חייגי", "חייג", "לחייג", "תחיגי", "חיגי",
        "תצלצלי", "צלצלי", "תצלצל", "לצלצל", "טלפני", "תטלפני",
        "call", "dial", "phone",
    )
    private val CALL_NOUN_VERBS = setOf("תעשי", "תוציאי", "תפתחי", "צרי")
    private val CALL_NOUNS = setOf("שיחה", "שיחת")

    private val STOP_VERBS = setOf("תפסיקי", "הפסיקי", "תפסיק", "הפסק", "די", "מספיק", "תכבי", "כבי", "תפסקי", "להפסיק", "לכבות", "תעצרי", "עצרי")
    private val LISTEN_WORDS = setOf("להקשיב", "להאזין", "הקשבה", "האזנה", "ההקשבה", "ההאזנה", "לשמוע", "מיקרופון", "המיקרופון")
    private val START_VERBS = setOf("תתחילי", "התחילי", "תחזרי", "חזרי", "תפעילי", "הפעילי")
    private val SLEEP_PHRASES = listOf(listOf("לכי", "לישון"), listOf("תלכי", "לישון"))
    private val CANCEL_WORDS = setOf("ביטול", "בטלי", "תבטלי", "בטל", "עזבי", "תעזבי", "שכחי", "תשכחי", "cancel")

    private val NUMBER_WORDS = setOf("למספר", "מספר", "המספר", "לטלפון", "טלפון", "number")
    private val NAME_PREFIX_DROP = setOf("אל", "ל", "עם", "את", "ה")
    private val POSSESSIVES = setOf("שלו", "שלה", "שלי")
    private val TYPE_CONNECTORS = setOf("ל", "ב", "על", "במספר", "למספר", "בטלפון", "לטלפון", "של", "בקו", "לקו")

    private val TYPE_WORDS: Map<String, PhoneType> = buildMap {
        listOf("נייד", "הנייד", "לנייד", "בנייד", "סלולרי", "הסלולרי", "לסלולרי", "בסלולרי", "פלאפון", "הפלאפון",
            "לפלאפון", "בפלאפון", "פלאפון", "סלולר", "לסלולר", "בסלולר", "מובייל", "למובייל", "במובייל", "mobile", "cell")
            .forEach { put(it, PhoneType.MOBILE) }
        listOf("בית", "הבית", "לבית", "בבית", "הביתה", "home").forEach { put(it, PhoneType.HOME) }
        listOf("עבודה", "העבודה", "לעבודה", "בעבודה", "משרד", "המשרד", "למשרד", "במשרד", "work", "office")
            .forEach { put(it, PhoneType.WORK) }
    }

    // ---- wake word handling ----

    private val WAKE_KEYS = setOf("גרבס", "צרבס", "גרבז", "גרבסט")

    /** True if the token sounds like "ג'ארביס" in either script. */
    fun isWakeToken(token: String): Boolean {
        val key = HebrewText.phoneticKey(token)
        if (key.length < 4) return false
        if (key in WAKE_KEYS) return true
        return key.length in 4..5 && key[0] in "גצד" && WAKE_KEYS.any { HebrewText.levenshtein(it, key) <= 1 }
    }

    /** Remove a leading "היי ג'ארביס" / "ג'ארביס," etc. Returns remaining tokens and whether the wake word was present. */
    fun stripWakeWord(tokens: List<String>): Pair<List<String>, Boolean> {
        var i = 0
        while (i < tokens.size && i < 2 && tokens[i] in LEADING_FILLERS) i++
        if (i < tokens.size && isWakeToken(tokens[i])) return tokens.drop(i + 1) to true
        // Some recognisers split the word: "ג'אר ביס".
        if (i + 1 < tokens.size && isWakeToken(tokens[i] + tokens[i + 1])) return tokens.drop(i + 2) to true
        return tokens to false
    }

    fun parse(text: String): Command {
        val (afterWake, _) = stripWakeWord(HebrewText.tokens(text))
        var t = afterWake.dropWhile { it in POLITE || it in LEADING_FILLERS }
        while (t.isNotEmpty() && t.last() in setOf("בבקשה", "תודה", "עכשיו")) t = t.dropLast(1)
        if (t.isEmpty()) return Command.Greeting
        val set = t.toSet()

        if (SLEEP_PHRASES.any { p -> t.windowed(p.size).any { it == p } }) return Command.StopListening
        if (set.any { it in STOP_VERBS } && set.any { it in LISTEN_WORDS }) return Command.StopListening
        if (set.any { it in START_VERBS } && set.any { it in LISTEN_WORDS }) return Command.StartListening

        findCallArgs(t)?.let { args -> return parseCallArgs(args) ?: Command.Unknown(text) }

        if (t.size <= 3 && t.first() in CANCEL_WORDS) return Command.Cancel
        if (t.size <= 2 && t.first() == "לא" && t.getOrNull(1) in setOf(null, "משנה", "צריך")) return Command.Cancel
        if ("השעה" in set || (set.contains("כמה") && set.contains("שעה") && !set.contains("עוד"))) return Command.WhatTime
        if ("התאריך" in set || "תאריך" in set || (("איזה" in set || "מה" in set) && "יום" in set && "היום" in set)) return Command.WhatDate
        return Command.Unknown(text)
    }

    private fun findCallArgs(t: List<String>): List<String>? {
        for (i in 0 until minOf(t.size, 3)) {
            if (t[i] in CALL_VERBS) return t.drop(i + 1)
            if (t[i] in CALL_NOUN_VERBS && t.getOrNull(i + 1) in CALL_NOUNS) return t.drop(i + 2)
            if (i == 0 && t[i] in CALL_NOUNS) return t.drop(1)
        }
        return null
    }

    private fun parseCallArgs(argsIn: List<String>): Command? {
        var args = argsIn.toMutableList()
        while (args.isNotEmpty() && args.first() in NAME_PREFIX_DROP) args.removeAt(0)
        var numberMode = false
        while (args.isNotEmpty() && args.first() in NUMBER_WORDS) { args.removeAt(0); numberMode = true }
        if (args.isEmpty()) return null

        parseDigits(args)?.let { return Command.DialNumber(it) }
        if (numberMode && args.any { it.any(Char::isDigit) || HebrewText.digitOf(it) != null }) return null

        // Phone type at the end: "לגל לנייד", "לגל במספר הנייד שלו".
        var type: PhoneType? = null
        if (args.size >= 2 && args.last() in POSSESSIVES && args[args.size - 2] in TYPE_WORDS) args.removeAt(args.size - 1)
        if (args.isNotEmpty() && args.last() in TYPE_WORDS) {
            val candidate = TYPE_WORDS.getValue(args.last())
            val rest = args.dropLast(1).toMutableList()
            while (rest.isNotEmpty() && rest.last() in TYPE_CONNECTORS) rest.removeAt(rest.size - 1)
            if (rest.isEmpty()) {
                // "תתקשרי הביתה" → a contact literally named "בית".
                return if (candidate == PhoneType.HOME) Command.CallContact(listOf("בית", "הבית", "home"), null) else null
            }
            type = candidate
            args = rest
        }
        if (args.isEmpty()) return null
        return Command.CallContact(nameVariants(args), type)
    }

    /** The spoken name with/without the leading ל preposition and trailing possessive ("לאמא שלי" → "אמא"). */
    private fun nameVariants(args: List<String>): List<String> {
        val base = mutableListOf<List<String>>()
        val first = args.first()
        if (first.length > 1 && first[0] == 'ל') base += listOf(first.substring(1)) + args.drop(1)
        base += args
        val out = LinkedHashSet<String>()
        for (b in base) out += b.joinToString(" ")
        for (b in base) if (b.size > 1 && b.last() in POSSESSIVES) out += b.dropLast(1).joinToString(" ")
        return out.filter { it.isNotBlank() }
    }

    /** "050 123 4567", "ל0501234567", "אפס חמש אפס אחת…", "פלוס 972 …" → digits. Null if any word is not a digit. */
    fun parseDigits(tokens: List<String>): String? {
        val sb = StringBuilder()
        for ((i, tok) in tokens.withIndex()) {
            when {
                tok.all(Char::isDigit) -> sb.append(tok)
                tok.length > 1 && (tok[0] == 'ל' || tok[0] == 'ו') && tok.substring(1).all(Char::isDigit) -> sb.append(tok.substring(1))
                i == 0 && tok in setOf("פלוס", "plus") -> sb.append('+')
                HebrewText.digitOf(tok) != null -> sb.append(HebrewText.digitOf(tok))
                else -> return null
            }
        }
        val digits = sb.count(Char::isDigit)
        return if (digits >= 3 && digits <= 15) sb.toString() else null
    }

    // ---- answers to clarification questions ----

    private val YES = setOf("כן", "נכון", "בדיוק", "אישור", "מאשר", "מאשרת", "יאללה", "בטח", "חייגי", "תחייגי",
        "תתקשרי", "אוקיי", "אוקי", "ok", "okay", "yes", "סבבה", "נכון", "בהחלט", "כמובן", "תמשיכי", "המשיכי", "מעולה")
    private val NO = setOf("לא", "no", "שלילי", "טעות", "ממש") + CANCEL_WORDS
    private val ORDINALS: Map<String, Int> = buildMap {
        listOf("ראשון", "ראשונה", "אחד", "אחת", "1").forEach { put(it, 0) }
        listOf("שני", "שנייה", "שניה", "שתיים", "שתים", "2").forEach { put(it, 1) }
        listOf("שלישי", "שלישית", "שלוש", "3").forEach { put(it, 2) }
        listOf("רביעי", "רביעית", "ארבע", "4").forEach { put(it, 3) }
        listOf("חמישי", "חמישית", "חמש", "5").forEach { put(it, 4) }
    }

    fun parseAnswer(text: String): Answer {
        val t = stripWakeWord(HebrewText.tokens(text)).first.dropWhile { it in POLITE }
        if (t.isEmpty()) return Answer.Free("")
        if (t.first() in NO) return Answer.No
        if (t.first() in YES) return Answer.Yes
        for (tok in t) TYPE_WORDS[tok]?.let { return Answer.Type(it) }
        if (t.size <= 3) {
            for (tok in t) {
                ORDINALS[tok]?.let { return Answer.Ordinal(it) }
                ORDINALS[tok.removePrefix("ה")]?.let { return Answer.Ordinal(it) }
                ORDINALS[tok.removePrefix("ל").removePrefix("ה")]?.let { return Answer.Ordinal(it) }
            }
        }
        return Answer.Free(t.joinToString(" "))
    }

    /** Whether an utterance looks like a fresh command rather than an answer (used to break out of a clarification). */
    fun looksLikeCommand(text: String): Boolean = when (parse(text)) {
        is Command.CallContact, is Command.DialNumber, Command.StopListening, Command.WhatTime, Command.WhatDate -> true
        else -> false
    }
}
