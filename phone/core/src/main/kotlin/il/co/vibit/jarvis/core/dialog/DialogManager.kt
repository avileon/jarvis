package il.co.vibit.jarvis.core.dialog

import il.co.vibit.jarvis.core.contacts.Candidate
import il.co.vibit.jarvis.core.contacts.Contact
import il.co.vibit.jarvis.core.contacts.ContactMatcher
import il.co.vibit.jarvis.core.contacts.PhoneNumber
import il.co.vibit.jarvis.core.contacts.Resolution
import il.co.vibit.jarvis.core.nlu.Answer
import il.co.vibit.jarvis.core.nlu.Command
import il.co.vibit.jarvis.core.nlu.CommandParser
import il.co.vibit.jarvis.core.nlu.PhoneType
import il.co.vibit.jarvis.core.skills.Skill
import il.co.vibit.jarvis.core.speech.HebrewSpeech
import il.co.vibit.jarvis.core.text.HebrewText
import java.time.LocalDateTime

sealed interface Action {
    /** Place a call. [displayName] is null for a raw number. */
    data class PlaceCall(val number: String, val displayName: String?) : Action
    data object StopListening : Action
    data object StartListening : Action
}

data class Reply(
    /** What the assistant says (spoken Hebrew, digits spelled out). */
    val speech: String,
    val action: Action? = null,
    /** True when the assistant asked a question and should listen again right after speaking. */
    val expectAnswer: Boolean = false,
    /** What is shown on screen (may contain digits instead of spelled numbers). */
    val display: String = speech,
)

/** Returns the address book, or null when READ_CONTACTS is not granted. */
fun interface ContactSource { fun load(): List<Contact>? }

data class DialogSettings(
    /** Ask "להתקשר ל…?" even for exact, unambiguous matches. */
    val confirmEveryCall: Boolean = false,
    /** Read back numbers that were dictated digit by digit before dialling. */
    val confirmSpokenNumbers: Boolean = true,
)

/**
 * The conversation state machine. Input: recognised text. Output: what to say + what to do.
 * It never dials on an approximate match: those always go through a question first.
 */
class DialogManager(
    private val contacts: ContactSource,
    private val settings: () -> DialogSettings = { DialogSettings() },
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
    private val skills: List<Skill> = emptyList(),
    private val matcher: ContactMatcher = ContactMatcher(),
) {
    private sealed interface State {
        data object Idle : State
        data class ChooseContact(val candidates: List<Candidate>, val type: PhoneType?, val uncertain: Boolean, val tries: Int = 0) : State
        data class ChoosePhone(val contact: Contact, val phones: List<PhoneNumber>, val tries: Int = 0) : State
        data class Confirm(val number: String, val name: String?, val spoken: String, val tries: Int = 0) : State
    }

    private var state: State = State.Idle

    val isAwaitingAnswer: Boolean get() = state != State.Idle

    fun reset() { state = State.Idle }

    fun handle(text: String): Reply {
        if (text.isBlank()) return if (state != State.Idle) reprompt() else Reply("לא שמעתי. נסה שוב.")
        if (state != State.Idle) {
            if (CommandParser.looksLikeCommand(text)) state = State.Idle else return handleAnswer(text)
        }
        return handleCommand(CommandParser.parse(text), text)
    }

    private fun handleCommand(cmd: Command, text: String): Reply = when (cmd) {
        is Command.CallContact -> callContact(cmd)
        is Command.DialNumber -> dialNumber(cmd.number)
        Command.StopListening -> Reply("בסדר, הפסקתי להקשיב.", Action.StopListening)
        Command.StartListening -> Reply("אני מקשיבה. אפשר לומר: היי ג'ארביס.", Action.StartListening)
        Command.WhatTime -> clock().toLocalTime().let { t ->
            Reply("השעה ${HebrewSpeech.time(t)}.", display = "השעה %02d:%02d".format(t.hour, t.minute))
        }
        Command.WhatDate -> Reply("היום ${HebrewSpeech.date(clock().toLocalDate())}.")
        Command.Cancel -> Reply("בסדר.")
        Command.Greeting -> Reply("כן? במה אפשר לעזור?", expectAnswer = true)
        is Command.Unknown -> skills.firstNotNullOfOrNull { it.handle(text, cmd) }
            ?: Reply("סליחה, לא הבנתי. אפשר לומר למשל: תתקשרי לשלי, או: מה השעה.")
    }

    // ---- calling a contact ----

    private fun callContact(cmd: Command.CallContact): Reply {
        val book = contacts.load()
            ?: return Reply("אין לי הרשאה לקרוא את אנשי הקשר. אפשר לאשר אותה במסך ההגדרות של ג'ארביס.")
        return when (val r = matcher.resolve(cmd.nameVariants, book)) {
            is Resolution.Unique -> withContact(r.candidate.contact, cmd.phoneType, confirmed = false)
            is Resolution.Ambiguous -> {
                state = State.ChooseContact(r.candidates, cmd.phoneType, uncertain = false)
                val names = joinOr(r.candidates.map { it.contact.displayName }, "ו")
                ask("מצאתי כמה אנשי קשר: $names. למי להתקשר?")
            }
            is Resolution.Uncertain -> {
                state = State.ChooseContact(r.candidates, cmd.phoneType, uncertain = true)
                ask(uncertainQuestion(r.candidates))
            }
            Resolution.NotFound -> Reply("לא מצאתי איש קשר בשם ${cmd.spokenName}.")
        }
    }

    private fun uncertainQuestion(c: List<Candidate>): String =
        if (c.size == 1) "לא בטוחה ששמעתי נכון. להתקשר ${HebrewText.withLamed(c[0].contact.displayName)}?"
        else "לא בטוחה ששמעתי נכון. התכוונת ${joinOr(c.map { HebrewText.withLamed(it.contact.displayName) }, "או ")}?"

    private fun withContact(contact: Contact, type: PhoneType?, confirmed: Boolean): Reply {
        val phones = contact.distinctPhones
        if (phones.isEmpty()) return Reply("${HebrewText.withLamed(contact.displayName)} אין מספר טלפון שמור.").also { state = State.Idle }
        val pool = if (type != null) phones.filter { it.type == type } else phones
        val chosen: PhoneNumber? = when {
            type != null && pool.isEmpty() -> {
                state = State.ChoosePhone(contact, phones)
                return ask("${HebrewText.withLamed(contact.displayName)} אין מספר ${typeName(type)}. יש ${describePhones(phones)}. לאיזה מספר?")
            }
            pool.size == 1 -> pool[0]
            type == null && pool.count { it.isPrimary } == 1 -> pool.first { it.isPrimary }
            else -> null
        }
        if (chosen == null) {
            state = State.ChoosePhone(contact, pool)
            return ask("${HebrewText.withLamed(contact.displayName)} יש ${pool.size} מספרים: ${describePhones(pool)}. לאיזה מספר?")
        }
        return dial(chosen, contact, confirmed)
    }

    private fun dial(phone: PhoneNumber, contact: Contact, confirmed: Boolean): Reply {
        val multi = contact.distinctPhones.size > 1
        val target = HebrewText.withLamed(contact.displayName) + if (multi) ", ${HebrewText.withLamed(typeName(phone.type))}" else ""
        if (settings().confirmEveryCall && !confirmed) {
            state = State.Confirm(phone.number, contact.displayName, target)
            return ask("להתקשר $target?")
        }
        state = State.Idle
        return Reply("בשמחה, מחייגת $target.", Action.PlaceCall(phone.number, contact.displayName))
    }

    // ---- dialling a number ----

    private fun dialNumber(number: String): Reply {
        val digits = number.count(Char::isDigit)
        if (digits < 3) return Reply("לא הצלחתי להבין את המספר.")
        val spoken = HebrewText.spellDigits(number)
        if (settings().confirmSpokenNumbers) {
            state = State.Confirm(number, null, "למספר $spoken")
            return ask("לחייג למספר $spoken?", display = "לחייג ל־$number?")
        }
        return Reply("מחייגת למספר $spoken.", Action.PlaceCall(number, null), display = "מחייגת ל־$number")
    }

    // ---- answers ----

    private fun handleAnswer(text: String): Reply {
        val answer = CommandParser.parseAnswer(text)
        if (answer == Answer.No) { state = State.Idle; return Reply("בסדר, לא מחייגת.") }
        return when (val s = state) {
            is State.ChooseContact -> answerContact(s, answer)
            is State.ChoosePhone -> answerPhone(s, answer)
            is State.Confirm -> when (answer) {
                Answer.Yes -> {
                    state = State.Idle
                    Reply("בשמחה, מחייגת ${s.spoken}.", Action.PlaceCall(s.number, s.name))
                }
                else -> reprompt()
            }
            State.Idle -> handle(text)
        }
    }

    private fun answerContact(s: State.ChooseContact, answer: Answer): Reply {
        val picked: Contact? = when (answer) {
            Answer.Yes -> s.candidates.singleOrNull()?.contact
            is Answer.Ordinal -> s.candidates.getOrNull(answer.index)?.contact
            is Answer.Type -> s.candidates.singleOrNull()?.contact
            is Answer.Free -> {
                // The user repeated a name — it must clearly match one of the offered options.
                when (val r = matcher.resolve(listOf(answer.text), s.candidates.map { it.contact })) {
                    is Resolution.Unique -> r.candidate.contact
                    else -> null
                }
            }
            Answer.No -> null
        }
        if (picked == null) return reprompt()
        val type = (answer as? Answer.Type)?.type ?: s.type
        return withContact(picked, type, confirmed = true)
    }

    private fun answerPhone(s: State.ChoosePhone, answer: Answer): Reply {
        val picked: PhoneNumber? = when (answer) {
            is Answer.Type -> s.phones.filter { it.type == answer.type }.singleOrNull()
            is Answer.Ordinal -> s.phones.getOrNull(answer.index)
            Answer.Yes -> s.phones.singleOrNull()
            is Answer.Free -> {
                // "זה שמסתיים ב-67"
                val d = CommandParser.parseDigits(HebrewText.tokens(answer.text).filter { it.any(Char::isDigit) || HebrewText.digitOf(it) != null })
                    ?: answer.text.filter(Char::isDigit).ifEmpty { null }
                d?.let { tail -> s.phones.filter { it.key.endsWith(tail.filter(Char::isDigit)) }.singleOrNull() }
            }
            Answer.No -> null
        }
        if (picked == null) {
            if (answer is Answer.Type && s.phones.count { it.type == answer.type } > 1) {
                val same = s.phones.filter { it.type == answer.type }
                state = State.ChoosePhone(s.contact, same, s.tries + 1)
                return ask("יש ${same.size} מספרים מסוג ${typeName(answer.type)}: ${describePhones(same)}. לאיזה?")
            }
            return reprompt()
        }
        return dial(picked, s.contact, confirmed = true)
    }

    private fun reprompt(): Reply {
        val s = state
        val tries = when (s) {
            is State.ChooseContact -> s.tries
            is State.ChoosePhone -> s.tries
            is State.Confirm -> s.tries
            State.Idle -> 0
        }
        if (tries >= 1) { state = State.Idle; return Reply("לא הצלחתי להבין, אז לא חייגתי. אפשר לנסות שוב.") }
        return when (s) {
            is State.ChooseContact -> {
                state = s.copy(tries = tries + 1)
                if (s.uncertain) ask(uncertainQuestion(s.candidates) + " אפשר לומר כן או לא.")
                else ask("למי להתקשר? ${joinOr(s.candidates.mapIndexed { i, c -> "${ORDINAL_NAMES[i]}: ${c.contact.displayName}" }, "")}.")
            }
            is State.ChoosePhone -> {
                state = s.copy(tries = tries + 1)
                ask("לאיזה מספר? ${describePhones(s.phones)}.")
            }
            is State.Confirm -> {
                state = s.copy(tries = tries + 1)
                ask("לחייג ${s.spoken}? אפשר לומר כן או לא.")
            }
            State.Idle -> Reply("סליחה, לא הבנתי.")
        }
    }

    // ---- wording helpers ----

    private fun ask(speech: String, display: String = speech) = Reply(speech, expectAnswer = true, display = display)

    private fun describePhones(phones: List<PhoneNumber>): String {
        val byType = phones.groupBy { it.type }
        return joinOr(phones.map { p ->
            val name = p.label?.takeIf { p.type == PhoneType.OTHER && it.isNotBlank() } ?: typeName(p.type)
            if ((byType[p.type]?.size ?: 0) > 1) "$name שמסתיים ב${HebrewText.spellDigits(p.key.takeLast(2))}" else name
        }, "ו")
    }

    private fun typeName(t: PhoneType) = when (t) {
        PhoneType.MOBILE -> "נייד"
        PhoneType.HOME -> "בית"
        PhoneType.WORK -> "עבודה"
        PhoneType.OTHER -> "אחר"
    }

    /** "א, ב ו־ג" / "א או ב". */
    private fun joinOr(items: List<String>, last: String): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " " + last + items.last()
    }

    companion object {
        private val ORDINAL_NAMES = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי")
    }
}
