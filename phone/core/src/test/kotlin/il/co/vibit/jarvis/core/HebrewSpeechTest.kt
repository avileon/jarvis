package il.co.vibit.jarvis.core

import il.co.vibit.jarvis.core.speech.HebrewSpeech
import il.co.vibit.jarvis.core.text.HebrewText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class HebrewSpeechTest {
    private fun t(h: Int, m: Int) = HebrewSpeech.time(LocalTime.of(h, m))

    @Test fun times() {
        assertEquals("שתים עשרה וחצי", t(12, 30))
        assertEquals("שתים עשרה", t(0, 0))
        assertEquals("שלוש ורבע", t(15, 15))
        assertEquals("רבע לארבע", t(15, 45))
        assertEquals("עשרים לשתים עשרה", t(11, 40))
        assertEquals("תשע ושבע דקות", t(9, 7))
        assertEquals("שמונה ועשרים וחמש", t(20, 25))
        assertEquals("עשר ודקה", t(10, 1))
        assertEquals("אחת עשרה ושלושים ושלוש דקות", t(23, 33))
    }

    @Test fun dates() {
        assertEquals("יום רביעי, חמישה עשר באוקטובר", HebrewSpeech.date(LocalDate.of(2025, 10, 15)))
        assertEquals("שבת, אחד בנובמבר", HebrewSpeech.date(LocalDate.of(2025, 11, 1)))
    }

    @Test fun normalisation() {
        assertEquals("גארביס תתקשרי לשלי", HebrewText.normalize("ג׳ארביס, תתקשרי לשלי!"))
        assertEquals("שלום", HebrewText.normalize("שָׁלוֹם"))
        assertEquals(HebrewText.phoneticKey("שלי"), HebrewText.phoneticKey("Shelly"))
        assertEquals(HebrewText.phoneticKey("גל"), HebrewText.phoneticKey("Gal"))
    }
}
