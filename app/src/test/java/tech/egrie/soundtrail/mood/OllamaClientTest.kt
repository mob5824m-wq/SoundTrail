package tech.egrie.soundtrail.mood

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Ollama support, minus the network: the address normaliser, the reply shapes a listener's own
 * server can produce, and the messages shown when it cannot be reached.
 */
class OllamaClientTest {

    // ----- addresses -----

    @Test fun `an address is accepted however it was pasted`() {
        assertEquals("http://192.168.1.20:11434", MoodAiUrls.normalize("192.168.1.20:11434"))
        assertEquals("http://192.168.1.20:11434", MoodAiUrls.normalize("  http://192.168.1.20:11434/  "))
        assertEquals("http://192.168.1.20:11434", MoodAiUrls.normalize("http://192.168.1.20:11434/api/chat"))
        assertEquals("http://192.168.1.20:11434", MoodAiUrls.normalize("http://192.168.1.20:11434/api/tags/"))
        assertEquals("http://192.168.1.20:11434", MoodAiUrls.normalize("http://192.168.1.20:11434/v1/chat/completions"))
        assertEquals("http://nas.local:11434", MoodAiUrls.normalize("nas.local:11434"))
        assertEquals("https://ollama.example.com", MoodAiUrls.normalize("https://ollama.example.com"))
    }

    @Test fun `a path prefix in front of the server is kept`() {
        assertEquals("https://example.com/ollama", MoodAiUrls.normalize("https://example.com/ollama/"))
        assertEquals("https://example.com/ollama", MoodAiUrls.normalize("https://example.com/ollama/api/chat"))
    }

    @Test fun `junk is rejected rather than turned into a request to nowhere`() {
        assertNull(MoodAiUrls.normalize(""))
        assertNull(MoodAiUrls.normalize("   "))
        assertNull(MoodAiUrls.normalize("ftp://192.168.1.20"))
        assertNull(MoodAiUrls.normalize("http://"))
        assertNull(MoodAiUrls.normalize(":::"))
    }

    @Test fun `localhost is recognised as this device, which on a phone is worth saying`() {
        assertTrue(MoodAiUrls.isLoopback("http://localhost:11434"))
        assertTrue(MoodAiUrls.isLoopback("http://127.0.0.1:11434"))
        assertFalse(MoodAiUrls.isLoopback("http://192.168.1.20:11434"))
        assertFalse(MoodAiUrls.isLoopback("http://localhost.example.com:11434"))
        assertEquals("192.168.1.20:11434", MoodAiUrls.display("http://192.168.1.20:11434"))
    }

    // ----- reply shapes -----

    @Test fun `a native api chat reply is read`() {
        val reply = JSONObject().apply {
            put("model", "llama3.2")
            put(
                "message",
                JSONObject().apply {
                    put("role", "assistant")
                    put(
                        "content",
                        "{\"mood\":\"Restless\",\"valence\":0.35,\"energy\":0.8,\"tempo\":126," +
                            "\"tags\":[\"lofi\",\"Late Night\"],\"explanation\":\"Buzzing but scattered.\"}"
                    )
                }
            )
            put("done", true)
        }.toString()
        val reading = MoodAiParser.parse(reply)!!
        assertEquals("Restless", reading.mood)
        assertEquals(0.35, reading.valence, 0.0001)
        assertEquals(126, reading.tempo)
        assertEquals(listOf("lofi", "late-night"), reading.tags)
        assertEquals("Buzzing but scattered.", reading.explanation)
    }

    @Test fun `a fenced reply from a chatty model is still read`() {
        val chatty = "Sure! Here you go:\n```json\n{\"mood\":\"Calm\",\"energy\":0.2}\n```"
        val reply = JSONObject().put("message", JSONObject().put("content", chatty)).toString()
        val reading = MoodAiParser.parse(reply)!!
        assertEquals("Calm", reading.mood)
        assertEquals(0.2, reading.energy, 0.0001)
    }

    @Test fun `an openai-compatible gateway is understood too`() {
        val reply = JSONObject().apply {
            put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", "{\"mood\":\"Hyped\",\"energy\":0.95}")
                    )
                )
            )
        }.toString()
        assertEquals("Hyped", MoodAiParser.parse(reply)!!.mood)
    }

    @Test fun `a server that answers with the mood object itself is understood`() {
        assertEquals("Sleepy", MoodAiParser.parse("{\"mood\":\"Sleepy\",\"energy\":0.1}")!!.mood)
    }

    @Test fun `the older generate endpoint is understood`() {
        val reply = JSONObject().put("response", "{\"mood\":\"Focused\",\"energy\":0.4}").toString()
        assertEquals("Focused", MoodAiParser.parse(reply)!!.mood)
    }

    @Test fun `a reply with no mood is refused, not guessed`() {
        assertNull(MoodAiParser.parse("{\"message\":{\"content\":\"I'd rather not.\"}}"))
        assertNull(MoodAiParser.parse("{\"message\":{\"content\":\"\"}}"))
        assertNull(MoodAiParser.parse("not json at all"))
        assertNull(MoodAiParser.parse(""))
    }

    // ----- models and errors -----

    @Test fun `the model list is read, deduplicated, and capped`() {
        val models = JSONObject().apply {
            put(
                "models",
                JSONArray().apply {
                    put(JSONObject().put("name", "llama3.2:latest").put("size", 2019393189L))
                    put(JSONObject().put("name", "qwen2.5:7b"))
                    put(JSONObject().put("name", "llama3.2:latest"))
                    put(JSONObject().put("model", "hf.co/user/repo:Q4_K_M"))
                    put(JSONObject().put("name", "  "))
                }
            )
        }.toString()
        assertEquals(
            listOf("llama3.2:latest", "qwen2.5:7b", "hf.co/user/repo:Q4_K_M"),
            MoodAiParser.parseModels(models)
        )
    }

    @Test fun `an empty or malformed model list is not an error`() {
        assertTrue(MoodAiParser.parseModels("{\"models\":[]}").isEmpty())
        assertTrue(MoodAiParser.parseModels("{}").isEmpty())
        assertTrue(MoodAiParser.parseModels("<html>").isEmpty())
    }

    @Test fun `ollama's own error sentence is surfaced`() {
        val body = "{\"error\":\"model 'llama3.2' not found, try pulling it first\"}"
        assertEquals("model 'llama3.2' not found, try pulling it first", MoodAiParser.ollamaError(body))
        assertNull(MoodAiParser.ollamaError("{\"done\":true}"))
        assertNull(MoodAiParser.ollamaError(""))
    }

    @Test fun `a refused connection on a network address explains the host setting`() {
        val message = MoodAiParser.describeNetworkFailure(
            "http://192.168.1.20:11434",
            ConnectException("Connection refused")
        )
        assertTrue(message.contains("192.168.1.20:11434"))
        assertTrue(message.contains("OLLAMA_HOST=0.0.0.0:11434"))
        // The "it is this phone itself" wording belongs only to the loopback case.
        assertFalse(message.contains("phone itself"))
    }

    @Test fun `localhost on a phone is called out, because it is the phone itself`() {
        val message = MoodAiParser.describeNetworkFailure(
            "http://localhost:11434",
            ConnectException("Connection refused")
        )
        // Covers both readings: Ollama on the phone, and a listener who meant their computer.
        assertTrue(message.contains("this phone itself"))
        assertTrue(message.contains("0.0.0.0:11434"))
        assertFalse(message.contains("same network"))
    }

    @Test fun `a timeout reads as a timeout, not as a missing server`() {
        val message = MoodAiParser.describeNetworkFailure(
            "http://192.168.1.20:11434",
            SocketTimeoutException("Read timed out")
        )
        assertTrue(message.contains("Read timed out"))
        assertTrue(message.contains("Couldn't reach Ollama"))
    }

    // ----- the shared prompt -----

    @Test fun `every provider asks for the same json contract, with the listener's words`() {
        val prompt = MoodAiPrompts.full(
            "tired but I want something warm for the drive home",
            MoodContext(hourOfDay = 17, energyHint = 0.55, motion = "Driving"),
            MoodSearchTarget.JELLYFIN
        )
        assertTrue(prompt.contains("tired but I want something warm for the drive home"))
        // The request it is asked to answer, naming the place the listener picked.
        assertTrue(prompt.startsWith("You are the music finder inside a music app"))
        assertTrue(
            prompt.contains(
                "find music on your Jellyfin server that fits: \"tired but I want something warm for the drive home\""
            )
        )
        assertTrue(prompt.contains("JSON only"))
        listOf("mood", "valence", "energy", "tempo", "danceability", "acousticness",
            "instrumentalness", "tags", "search_terms", "explanation").forEach { key ->
            assertTrue("prompt does not mention $key", prompt.contains(key))
        }
        assertTrue(prompt.contains("energy around 55"))
        assertTrue(prompt.contains("Local time: 17:00"))
        assertTrue(prompt.contains("They are driving."))
    }

    @Test fun `the prompt carries nothing else about the listener`() {
        // The contract the privacy note makes: only the description and the coarse context go out.
        // The keys the answer must use are named — title and artist, so that the app has something
        // to search for — but nothing about what the listener owns is, because nothing else is ever
        // handed to the builder: the prompt is the instruction, the request, and the description.
        val prompt = MoodAiPrompts.full("quiet and calm", MoodContext(), MoodSearchTarget.LIBRARY)
        assertTrue(prompt.length < 1_600)
        assertTrue(prompt.contains("title, artist, and why"))
        assertFalse(prompt.contains("playlist"))
        assertFalse(prompt.contains("pin"))
        assertTrue(prompt.startsWith(MoodAiPrompts.instruction()))
        assertTrue(prompt.contains("find music in your own library that fits: \"quiet and calm\""))
    }

    @Test fun `a quiet context adds no context lines at all`() {
        val prompt = MoodAiPrompts.full(
            "something happy",
            MoodContext(),
            MoodSearchTarget.JELLYFIN
        )
        assertFalse(prompt.contains("Local time"))
        assertFalse(prompt.contains("They are "))
        assertFalse(prompt.contains("energy around"))
    }

    // ----- provider choice -----

    @Test fun `an unknown or missing provider id means the on-device engine`() {
        assertEquals(MoodAiProvider.ON_DEVICE, MoodAiProvider.from(null))
        assertEquals(MoodAiProvider.ON_DEVICE, MoodAiProvider.from(""))
        assertEquals(MoodAiProvider.ON_DEVICE, MoodAiProvider.from("gpt-9000"))
        assertEquals(MoodAiProvider.OLLAMA, MoodAiProvider.from("Ollama"))
        assertEquals(MoodAiProvider.GEMINI, MoodAiProvider.from(" gemini "))
    }

    @Test fun `the default provider sends nothing anywhere`() {
        assertTrue(MoodAiProvider.default.local)
        assertTrue(MoodAiProvider.ON_DEVICE.local)
        assertFalse(MoodAiProvider.OLLAMA.local)
        assertFalse(MoodAiProvider.GEMINI.local)
    }

    @Test fun `an ollama tag keeps what a tag may contain and drops the rest`() {
        assertEquals("llama3.2:3b-instruct-q4_K_M", MoodStore.sanitizeOllamaModel("llama3.2:3b-instruct-q4_K_M"))
        assertEquals("llama3.2", MoodStore.sanitizeOllamaModel("ollama run llama3.2"))
        assertEquals("hf.co/user/repo:Q4_K_M", MoodStore.sanitizeOllamaModel("hf.co/user/repo:Q4_K_M"))
        assertEquals("qwen2.5:7b", MoodStore.sanitizeOllamaModel("qwen2.5:7b\n"))
        assertEquals(DEFAULT_OLLAMA_MODEL, MoodStore.sanitizeOllamaModel("   "))
        assertEquals(DEFAULT_OLLAMA_MODEL, MoodStore.sanitizeOllamaModel("$()"))
    }
}
