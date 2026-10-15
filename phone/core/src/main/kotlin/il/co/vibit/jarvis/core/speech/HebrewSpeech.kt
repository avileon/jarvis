package il.co.vibit.jarvis.core.speech

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Turns times, dates and numbers into natural spoken Hebrew (for the TTS engine). */
object HebrewSpeech {
    private val FEM_UNITS = arrayOf("", "אחת", "שתיים", "שלוש", "ארבע", "חמש", "שש", "שבע", "שמונה", "תשע")
    private val FEM_TEENS = arrayOf("עשר", "אחת עשרה", "שתים עשרה", "שלוש עשרה", "ארבע עשרה", "חמש עשרה",
        "שש עשרה", "שבע עשרה", "שמונה עשרה", "תשע עשרה")
    private val MASC_UNITS = arrayOf("", "אחד", "שניים", "שלושה", "ארבעה", "חמישה", "שישה", "שבעה", "שמונה", "תשעה")
    private val MASC_TEENS = arrayOf("עשרה", "אחד עשר", "שנים עשר", "שלושה עשר", "ארבעה עשר", "חמישה עשר",
        "שישה עשר", "שבעה עשר", "שמונה עשר", "תשעה עשר")
    private val TENS = arrayOf("", "", "עשרים", "שלושים", "ארבעים", "חמישים")

    /** Feminine cardinal 1..59 ("עשרים ושלוש"). */
    fun feminine(n: Int): String = number(n, FEM_UNITS, FEM_TEENS)

    /** Masculine cardinal 1..59 ("חמישה עשר"). */
    fun masculine(n: Int): String = number(n, MASC_UNITS, MASC_TEENS)

    private fun number(n: Int, units: Array<String>, teens: Array<String>): String {
        require(n in 1..59) { "unsupported: $n" }
        return when {
            n < 10 -> units[n]
            n < 20 -> teens[n - 10]
            n % 10 == 0 -> TENS[n / 10]
            else -> "${TENS[n / 10]} ו${units[n % 10]}"
        }
    }

    private fun hourWord(h24: Int): String {
        val h = h24 % 12
        return if (h == 0) "שתים עשרה" else FEM_TEENS.getOrNull(h - 10) ?: FEM_UNITS[h]
    }

    /** 12:30 → "שתים עשרה וחצי", 15:45 → "רבע לארבע", 9:07 → "תשע ושבע דקות". */
    fun time(t: LocalTime): String {
        val h = t.hour
        val m = t.minute
        val next = hourWord(h + 1)
        return when {
            m == 0 -> hourWord(h)
            m == 15 -> "${hourWord(h)} ורבע"
            m == 30 -> "${hourWord(h)} וחצי"
            m == 45 -> "רבע ל$next"
            m == 40 -> "עשרים ל$next"
            m == 50 -> "עשרה ל$next"
            m == 55 -> "חמישה ל$next"
            m == 1 -> "${hourWord(h)} ודקה"
            m == 2 -> "${hourWord(h)} ושתי דקות"
            m % 5 == 0 -> "${hourWord(h)} ו${feminine(m)}"
            else -> "${hourWord(h)} ו${feminine(m)} דקות"
        }
    }

    /** Adds a part of day so "שמונה" is unambiguous: "שמונה וחצי בערב". */
    fun timeWithPeriod(t: LocalTime): String {
        val period = when (t.hour) {
            in 5..11 -> "בבוקר"
            in 12..15 -> "בצהריים"
            in 16..18 -> "אחר הצהריים"
            in 19..22 -> "בערב"
            else -> "בלילה"
        }
        // "רבע לשתים עשרה" at 11:45 is still morning; period follows the actual clock hour.
        return "${time(t)} $period"
    }

    private val DAYS = mapOf(
        DayOfWeek.SUNDAY to "ראשון", DayOfWeek.MONDAY to "שני", DayOfWeek.TUESDAY to "שלישי",
        DayOfWeek.WEDNESDAY to "רביעי", DayOfWeek.THURSDAY to "חמישי", DayOfWeek.FRIDAY to "שישי",
        DayOfWeek.SATURDAY to "שבת",
    )
    private val MONTHS = arrayOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט",
        "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")

    /** "יום רביעי, חמישה עשר באוקטובר". */
    fun date(d: LocalDate): String {
        val day = DAYS.getValue(d.dayOfWeek)
        val dayPart = if (d.dayOfWeek == DayOfWeek.SATURDAY) "שבת" else "יום $day"
        return "$dayPart, ${masculine(d.dayOfMonth)} ב${MONTHS[d.monthValue - 1]}"
    }
}
