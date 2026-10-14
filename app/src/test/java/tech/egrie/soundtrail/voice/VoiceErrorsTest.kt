package tech.egrie.soundtrail.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentences a listener reads when listening fails. The platform's codes are opaque and some
 * read like faults that are not the listener's to fix, so each one is pinned down here: what is
 * asked of the listener, and never a bare number.
 */
class VoiceErrorsTest {

    @Test
    fun `silence and a missed phrase ask for another try`() {
        assertEquals(
            "I didn't catch that. Try again.",
            VoiceErrors.sentence(SpeechRecognizer.ERROR_NO_MATCH)
        )
        assertEquals(
            "I didn't hear anything. Try again.",
            VoiceErrors.sentence(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        )
    }

    @Test
    fun `a denied microphone names the permission`() {
        val sentence = VoiceErrors.sentence(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertTrue(sentence.contains("microphone access"))
    }

    @Test
    fun `a busy microphone and a missing network say which one it is`() {
        assertTrue(
            VoiceErrors.sentence(SpeechRecognizer.ERROR_RECOGNIZER_BUSY).contains("busy")
        )
        assertTrue(
            VoiceErrors.sentence(SpeechRecognizer.ERROR_AUDIO).contains("microphone")
        )
        assertTrue(
            VoiceErrors.sentence(SpeechRecognizer.ERROR_NETWORK).contains("network")
        )
        assertEquals(
            VoiceErrors.sentence(SpeechRecognizer.ERROR_NETWORK),
            VoiceErrors.sentence(SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
        )
    }

    @Test
    fun `an unknown code still gets a sentence instead of a number`() {
        val sentence = VoiceErrors.sentence(9_999)
        assertTrue(sentence.isNotBlank())
        assertTrue(sentence.none(Char::isDigit))
    }

    @Test
    fun `every sentence ends in a full stop`() {
        val codes = listOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            9_999
        )
        codes.forEach { assertTrue(VoiceErrors.sentence(it).endsWith(".")) }
        assertTrue(VoiceErrors.unsupported().endsWith("."))
    }

    @Test
    fun `a phone with no recognizer is told so plainly`() {
        assertTrue(VoiceErrors.unsupported().contains("no speech recognition"))
    }
}
