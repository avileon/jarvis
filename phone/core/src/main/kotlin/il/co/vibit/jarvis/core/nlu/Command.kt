package il.co.vibit.jarvis.core.nlu

enum class PhoneType { MOBILE, HOME, WORK, OTHER }

/** A parsed user utterance. New capabilities (Waze, music, Home Assistant, …) are added as new subtypes. */
sealed interface Command {
    /** "תתקשרי לשלי", "תחייגי לגל לנייד". [nameVariants] are alternative readings of the spoken name, best first. */
    data class CallContact(val nameVariants: List<String>, val phoneType: PhoneType?) : Command {
        val spokenName: String get() = nameVariants.first()
    }

    /** "חייגי למספר 0501234567". [number] holds digits (and a leading + if spoken). */
    data class DialNumber(val number: String) : Command

    data object StopListening : Command
    data object StartListening : Command
    data object WhatTime : Command
    data object WhatDate : Command
    data object Cancel : Command

    /** Only the wake word / a greeting was heard — the assistant should ask how it can help. */
    data object Greeting : Command

    data class Unknown(val text: String) : Command
}

/** Short replies used while the assistant waits for a clarification. */
sealed interface Answer {
    data object Yes : Answer
    data object No : Answer
    data class Ordinal(val index: Int) : Answer
    data class Type(val type: PhoneType) : Answer
    data class Free(val text: String) : Answer
}
