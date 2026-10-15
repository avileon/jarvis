package il.co.vibit.jarvis.core

import il.co.vibit.jarvis.core.nlu.Answer
import il.co.vibit.jarvis.core.nlu.Command
import il.co.vibit.jarvis.core.nlu.CommandParser
import il.co.vibit.jarvis.core.nlu.PhoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private fun call(text: String) = CommandParser.parse(text) as Command.CallContact

    @Test fun `call examples from the spec`() {
        assertEquals("שלי", call("היי ג׳ארביס, תתקשרי לשלי").spokenName)
        assertEquals("בר", call("ג׳ארביס, תחייגי לבר").spokenName)
        val gal = call("ג׳ארביס, תתקשרי לגל לנייד")
        assertEquals("גל", gal.spokenName)
        assertEquals(PhoneType.MOBILE, gal.phoneType)
    }

    @Test fun `dial a dictated number`() {
        assertEquals(Command.DialNumber("0501234567"), CommandParser.parse("ג׳ארביס, חייגי למספר 0501234567"))
        assertEquals(Command.DialNumber("0501234567"), CommandParser.parse("תחייגי ל 050-123-4567"))
        assertEquals(Command.DialNumber("0501234567"), CommandParser.parse("חייגי ל0501234567"))
        assertEquals(Command.DialNumber("0501234567"),
            CommandParser.parse("חייגי למספר אפס חמש אפס אחת שתיים שלוש ארבע חמש שש שבע"))
        assertEquals(Command.DialNumber("+972501234567"), CommandParser.parse("חייגי פלוס 972 50 123 4567"))
    }

    @Test fun `stop listening`() {
        assertEquals(Command.StopListening, CommandParser.parse("ג׳ארביס, תפסיקי להקשיב"))
        assertEquals(Command.StopListening, CommandParser.parse("הפסיקי להאזין"))
        assertEquals(Command.StopListening, CommandParser.parse("ג'ארביס לכי לישון"))
    }

    @Test fun `wake word spellings`() {
        for (w in listOf("ג׳ארביס", "ג'ארביס", "גארביס", "ג'רביס", "Jarvis", "jarvis", "ג'ארוויס", "צ'ארביס")) {
            assertTrue(w, CommandParser.isWakeToken(w))
        }
        for (w in listOf("גל", "שלי", "בר", "תתקשרי", "גרב")) {
            assertTrue(w, !CommandParser.isWakeToken(w))
        }
        assertEquals(Command.Greeting, CommandParser.parse("היי ג'ארביס"))
    }

    @Test fun `recogniser variations`() {
        assertEquals("שלי", call("ג'ארביס תתקשר לשלי").spokenName)
        assertEquals("שלי", call("Jarvis תתקשרי ל שלי").spokenName)
        assertEquals("שלי כהן", call("תוכלי להתקשר לשלי כהן בבקשה").spokenName)
        assertEquals(listOf("אמא שלי", "לאמא שלי", "אמא", "לאמא"), call("תתקשרי לאמא שלי").nameVariants)
        assertEquals(listOf("אה", "לאה"), call("תתקשרי לאה").nameVariants)
        val home = call("תתקשרי לדני בבית")
        assertEquals("דני", home.spokenName); assertEquals(PhoneType.HOME, home.phoneType)
        val work = call("תתקשרי לרון במספר העבודה שלו")
        assertEquals("רון", work.spokenName); assertEquals(PhoneType.WORK, work.phoneType)
        assertEquals("בית", call("תתקשרי הביתה").spokenName)
        assertEquals("גל", call("תעשי שיחה לגל").spokenName)
    }

    @Test fun `time and date`() {
        assertEquals(Command.WhatTime, CommandParser.parse("היי ג'ארביס, מה השעה?"))
        assertEquals(Command.WhatTime, CommandParser.parse("כמה השעה עכשיו"))
        assertEquals(Command.WhatDate, CommandParser.parse("מה התאריך היום"))
        assertEquals(Command.WhatDate, CommandParser.parse("איזה יום היום"))
    }

    @Test fun answers() {
        assertEquals(Answer.Yes, CommandParser.parseAnswer("כן"))
        assertEquals(Answer.Yes, CommandParser.parseAnswer("כן, תחייגי"))
        assertEquals(Answer.No, CommandParser.parseAnswer("לא"))
        assertEquals(Answer.No, CommandParser.parseAnswer("ביטול"))
        assertEquals(Answer.Ordinal(1), CommandParser.parseAnswer("השני"))
        assertEquals(Answer.Ordinal(0), CommandParser.parseAnswer("לראשונה"))
        assertEquals(Answer.Type(PhoneType.MOBILE), CommandParser.parseAnswer("לנייד"))
        assertEquals(Answer.Free("גל כהן"), CommandParser.parseAnswer("גל כהן"))
    }
}
