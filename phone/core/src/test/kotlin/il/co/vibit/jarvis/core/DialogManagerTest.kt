package il.co.vibit.jarvis.core

import il.co.vibit.jarvis.core.contacts.Contact
import il.co.vibit.jarvis.core.contacts.PhoneNumber
import il.co.vibit.jarvis.core.dialog.Action
import il.co.vibit.jarvis.core.dialog.DialogManager
import il.co.vibit.jarvis.core.dialog.DialogSettings
import il.co.vibit.jarvis.core.nlu.PhoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DialogManagerTest {
    private val book = listOf(
        Contact(1, "שלי כהן", listOf(PhoneNumber("050-111-1111", PhoneType.MOBILE))),
        Contact(2, "בר לוי", listOf(PhoneNumber("052-222-2222", PhoneType.MOBILE))),
        Contact(3, "גל אברהם", listOf(
            PhoneNumber("054-333-3333", PhoneType.MOBILE),
            PhoneNumber("03-6333333", PhoneType.HOME),
        )),
        Contact(4, "גל מזרחי", listOf(PhoneNumber("054-444-4444", PhoneType.MOBILE))),
        Contact(5, "אמא", listOf(PhoneNumber("+972 50-555-5555", PhoneType.MOBILE), PhoneNumber("050-555-5555", PhoneType.MOBILE))),
        Contact(6, "Danny Green", listOf(PhoneNumber("050-666-6666", PhoneType.MOBILE))),
        Contact(7, "יוסי", emptyList()),
        Contact(8, "ליאור", listOf(PhoneNumber("050-888-8888", PhoneType.MOBILE))),
    )

    private fun dm(settings: DialogSettings = DialogSettings()) =
        DialogManager({ book }, { settings }, { LocalDateTime.of(2026, 10, 14, 12, 30) })

    private fun call(r: il.co.vibit.jarvis.core.dialog.Reply) = r.action as? Action.PlaceCall

    @Test fun `exact unique first name dials immediately`() {
        val r = dm().handle("היי ג׳ארביס, תתקשרי לשלי")
        assertEquals("בשמחה, מחייגת לשלי כהן.", r.speech)
        assertEquals("050-111-1111", call(r)!!.number)
    }

    @Test fun `bar and the double lamed`() {
        assertEquals("052-222-2222", call(dm().handle("ג׳ארביס, תחייגי לבר"))!!.number)
        assertEquals("050-888-8888", call(dm().handle("תתקשרי לליאור"))!!.number)
        // Recogniser dropped one ל: "לליאור" → "ליאור" still works.
        assertEquals("050-888-8888", call(dm().handle("תתקשרי ליאור"))!!.number)
    }

    @Test fun `two contacts named Gal - asks which, then picks`() {
        val d = dm()
        val r1 = d.handle("ג׳ארביס, תתקשרי לגל")
        assertNull(r1.action); assertTrue(r1.expectAnswer)
        assertTrue(r1.speech, r1.speech.contains("גל אברהם") && r1.speech.contains("גל מזרחי"))
        val r2 = d.handle("גל מזרחי")
        assertEquals("054-444-4444", call(r2)!!.number)
    }

    @Test fun `gal to mobile still asks which gal, then uses the mobile`() {
        val d = dm()
        assertNull(d.handle("ג׳ארביס, תתקשרי לגל לנייד").action)
        val r = d.handle("הראשון")
        assertEquals("054-333-3333", call(r)!!.number)
    }

    @Test fun `several numbers - asks type`() {
        val d = dm()
        val r1 = d.handle("תתקשרי לגל אברהם")
        assertNull(r1.action); assertTrue(r1.speech, r1.speech.contains("נייד") && r1.speech.contains("בית"))
        assertEquals("03-6333333", call(d.handle("לבית"))!!.number)
    }

    @Test fun `requested type picks directly`() {
        assertEquals("054-333-3333", call(dm().handle("תתקשרי לגל אברהם לנייד"))!!.number)
    }

    @Test fun `duplicate numbers collapse`() {
        assertEquals("+972 50-555-5555", call(dm().handle("תתקשרי לאמא"))!!.number)
        assertEquals("+972 50-555-5555", call(dm().handle("תתקשרי לאמא שלי"))!!.number)
    }

    @Test fun `transcription error never dials without confirmation`() {
        val d = dm()
        val r1 = d.handle("תתקשרי לשלו כהן") // misheard
        assertNull(r1.action); assertTrue(r1.expectAnswer)
        assertTrue(r1.speech, r1.speech.contains("שלי כהן"))
        assertEquals("050-111-1111", call(d.handle("כן"))!!.number)
    }

    @Test fun `uncertain and user says no`() {
        val d = dm()
        assertNull(d.handle("תתקשרי לשלו כהן").action)
        val r = d.handle("לא")
        assertNull(r.action); assertFalse(d.isAwaitingAnswer)
    }

    @Test fun `english contact with hebrew speech is only suggested`() {
        val d = dm()
        val r = d.handle("תתקשרי לדני גרין")
        assertNull(r.action)
        assertTrue(r.speech, r.speech.contains("Danny Green"))
    }

    @Test fun `unknown name`() {
        val r = dm().handle("תתקשרי לזבולון")
        assertNull(r.action); assertEquals("לא מצאתי איש קשר בשם זבולון.", r.speech)
    }

    @Test fun `contact without phone`() {
        assertNull(dm().handle("תתקשרי ליוסי").action)
    }

    @Test fun `dial number asks to confirm then dials`() {
        val d = dm()
        val r1 = d.handle("ג׳ארביס, חייגי למספר 0501234567")
        assertNull(r1.action)
        assertEquals("לחייג למספר אפס חמש אפס, אחת שתיים שלוש, ארבע חמש שש שבע?", r1.speech)
        assertEquals("0501234567", call(d.handle("כן"))!!.number)
    }

    @Test fun `dial number without confirmation when disabled`() {
        assertEquals("0501234567", call(dm(DialogSettings(confirmSpokenNumbers = false)).handle("חייגי 0501234567"))!!.number)
    }

    @Test fun `confirm every call setting`() {
        val d = dm(DialogSettings(confirmEveryCall = true))
        assertNull(d.handle("תתקשרי לשלי").action)
        assertEquals("050-111-1111", call(d.handle("כן"))!!.number)
    }

    @Test fun `two failed answers cancel`() {
        val d = dm()
        d.handle("תתקשרי לגל")
        assertNull(d.handle("בננה").action)
        assertTrue(d.isAwaitingAnswer)
        val r = d.handle("תפוח")
        assertNull(r.action); assertFalse(d.isAwaitingAnswer)
    }

    @Test fun `new command breaks out of clarification`() {
        val d = dm()
        d.handle("תתקשרי לגל")
        assertEquals("052-222-2222", call(d.handle("תתקשרי לבר"))!!.number)
    }

    @Test fun `time and stop`() {
        assertEquals("השעה שתים עשרה וחצי.", dm().handle("היי ג׳ארביס, מה השעה?").speech)
        assertEquals(Action.StopListening, dm().handle("ג׳ארביס, תפסיקי להקשיב").action)
        assertEquals("היום יום רביעי, ארבעה עשר באוקטובר.", dm().handle("מה התאריך").speech)
    }

    @Test fun `no contacts permission`() {
        val d = DialogManager({ null })
        val r = d.handle("תתקשרי לשלי")
        assertNull(r.action); assertTrue(r.speech.contains("הרשאה"))
    }
}
