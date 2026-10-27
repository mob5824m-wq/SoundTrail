package tech.egrie.soundtrail.mood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The destination prompt is only honest if it names the place the request actually goes. These
 * tests pin down the endpoint each provider resolves to, and the rule that a new provider or
 * address is a new question rather than a silently reused answer.
 */
class MoodConsentTest {
    @Test
    fun `the on-device engine has no destination and never prompts`() {
        assertNull(MoodAiConsent.destination(MoodAiProvider.ON_DEVICE, "", ""))
    }

    @Test
    fun `ollama names the server the app will call, not the address as typed`() {
        val destination = MoodAiConsent.destination(
            MoodAiProvider.OLLAMA,
            "192.168.0.20:11434/api/chat",
            ""
        )

        assertEquals(MoodAiProvider.OLLAMA, destination?.provider)
        assertEquals("https://192.168.0.20:11434", destination?.endpoint)
    }

    @Test
    fun `an openai-compatible endpoint keeps its version path`() {
        val destination = MoodAiConsent.destination(
            provider = MoodAiProvider.OPENAI_COMPATIBLE,
            ollamaUrl = "",
            openAiUrl = "https://openrouter.ai/api/v1"
        )
        assertEquals("https://openrouter.ai/api/v1", destination?.endpoint)
    }

    @Test
    fun `a pasted chat-completions url is trimmed back to its base`() {
        val destination = MoodAiConsent.destination(
            provider = MoodAiProvider.OPENAI_COMPATIBLE,
            ollamaUrl = "",
            openAiUrl = "https://api.openai.com/v1/chat/completions"
        )
        assertEquals("https://api.openai.com/v1", destination?.endpoint)
    }

    @Test
    fun `a bare host becomes https plus v1, on the listener's own network or not`() {
        val destination = MoodAiConsent.destination(
            provider = MoodAiProvider.OPENAI_COMPATIBLE,
            ollamaUrl = "",
            openAiUrl = "192.168.0.20:8000"
        )
        assertEquals("https://192.168.0.20:8000/v1", destination?.endpoint)
    }

    @Test
    fun `gemini names Google AI Studio's API root`() {
        val destination = MoodAiConsent.destination(MoodAiProvider.GEMINI, "", "")
        assertEquals("https://generativelanguage.googleapis.com/v1beta", destination?.endpoint)
    }

    @Test
    fun `a different provider or endpoint is a different consent record`() {
        val first = MoodAiConsent.destination(
            provider = MoodAiProvider.OLLAMA, ollamaUrl = "http://192.168.0.20:11434", openAiUrl = ""
        )!!
        val moved = MoodAiConsent.destination(
            provider = MoodAiProvider.OLLAMA, ollamaUrl = "http://192.168.0.21:11434", openAiUrl = ""
        )!!
        val gemini = MoodAiConsent.destination(
            provider = MoodAiProvider.GEMINI, ollamaUrl = "", openAiUrl = ""
        )!!

        assertNotEquals(first.fingerprint, moved.fingerprint)
        assertNotEquals(moved.fingerprint, gemini.fingerprint)
        // The same address typed with a trailing slash is still the same destination.
        val same = MoodAiConsent.destination(
            provider = MoodAiProvider.OLLAMA,
            ollamaUrl = "http://192.168.0.20:11434/",
            openAiUrl = ""
        )!!
        assertEquals(first.fingerprint, same.fingerprint)
    }

    @Test
    fun `an address that cannot be used has no destination to approve`() {
        assertNull(
            MoodAiConsent.destination(
                provider = MoodAiProvider.OLLAMA, ollamaUrl = "", openAiUrl = ""
            )
        )
        assertNull(
            MoodAiConsent.destination(
                provider = MoodAiProvider.OPENAI_COMPATIBLE, ollamaUrl = "", openAiUrl = ""
            )
        )
    }
}
