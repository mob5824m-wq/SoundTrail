package tech.egrie.soundtrail.mood

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

/**
 * OpenAI-compatible endpoints, minus the network: the address normaliser that has to cope with
 * whatever a README showed the listener, the model list an endpoint may or may not publish, and
 * the reply shape every one of these APIs uses.
 */
class OpenAiCompatibleClientTest {

    // ----- addresses -----

    @Test fun `a bare host gets the v1 prefix every one of these APIs lives under`() {
        assertEquals("https://api.openai.com/v1", MoodAiUrls.normalizeOpenAi("api.openai.com"))
        assertEquals("https://api.openai.com/v1", MoodAiUrls.normalizeOpenAi("  https://api.openai.com  "))
        assertEquals("https://openrouter.ai/api/v1", MoodAiUrls.normalizeOpenAi("openrouter.ai/api/v1"))
        assertEquals("https://api.openai.com/v1", MoodAiUrls.normalizeOpenAi("https://api.openai.com/v1/"))
    }

    @Test fun `the endpoint a README shows is trimmed back to its base`() {
        assertEquals(
            "https://api.openai.com/v1",
            MoodAiUrls.normalizeOpenAi("https://api.openai.com/v1/chat/completions")
        )
        assertEquals(
            "https://api.openai.com/v1",
            MoodAiUrls.normalizeOpenAi("https://api.openai.com/v1/models")
        )
        // A prefix in front of the API — a gateway, a proxy, a path on a shared host — is kept.
        assertEquals(
            "https://gateway.example.com/openai/v1",
            MoodAiUrls.normalizeOpenAi("https://gateway.example.com/openai/v1/chat/completions")
        )
    }

    @Test fun `a machine on the listener's network is assumed to speak plain http`() {
        assertEquals("http://192.168.0.20:8000/v1", MoodAiUrls.normalizeOpenAi("192.168.0.20:8000"))
        assertEquals("http://192.168.0.20:8000/v1", MoodAiUrls.normalizeOpenAi("http://192.168.0.20:8000/v1"))
        assertEquals("http://localhost:8080/v1", MoodAiUrls.normalizeOpenAi("localhost:8080/v1"))
        assertEquals("http://nas.local:8000/v1", MoodAiUrls.normalizeOpenAi("nas.local:8000"))
        assertEquals("http://myserver:8000/v1", MoodAiUrls.normalizeOpenAi("myserver:8000"))
        // A public host with a port is still public, and this provider sends a key to it.
        assertEquals("https://api.example.com:8443/v1", MoodAiUrls.normalizeOpenAi("api.example.com:8443"))
    }

    @Test fun `junk is rejected rather than turned into a request to nowhere`() {
        assertNull(MoodAiUrls.normalizeOpenAi(""))
        assertNull(MoodAiUrls.normalizeOpenAi("   "))
        assertNull(MoodAiUrls.normalizeOpenAi("ftp://api.openai.com"))
        assertNull(MoodAiUrls.normalizeOpenAi("http://"))
        assertNull(MoodAiUrls.normalizeOpenAi("https:///v1"))
        assertNull(MoodAiUrls.normalizeOpenAi("https://api.openai.com/v1/chat/completions?key=x"))
        assertNull(MoodAiUrls.normalizeOpenAi("https://api.openai.com/v1/../admin"))
    }

    @Test fun `the two routes this provider uses are built from the base`() {
        val base = MoodAiUrls.normalizeOpenAi("192.168.0.20:8000")!!
        assertEquals("http://192.168.0.20:8000/v1/chat/completions", MoodAiUrls.openAiChatUrl(base))
        assertEquals("http://192.168.0.20:8000/v1/models", MoodAiUrls.openAiModelsUrl(base))
    }

    // ----- model names -----

    @Test fun `a model name keeps the shape these APIs use`() {
        assertEquals("gpt-4o-mini", MoodStore.sanitizeOpenAiModel("gpt-4o-mini"))
        assertEquals("openai/gpt-4o", MoodStore.sanitizeOpenAiModel("openai/gpt-4o"))
        assertEquals(
            "meta-llama/Llama-3.1-8B-Instruct:latest",
            MoodStore.sanitizeOpenAiModel("  meta-llama/Llama-3.1-8B-Instruct:latest  ")
        )
        // Blank stays blank: there is no model this app could assume some endpoint has.
        assertEquals("", MoodStore.sanitizeOpenAiModel("   "))
    }

    // ----- model lists -----

    @Test fun `the model list is read from either spelling`() {
        val openAi = JSONObject().apply {
            put("object", "list")
            put(
                "data",
                JSONArray()
                    .put(JSONObject().put("id", "gpt-4o-mini"))
                    .put(JSONObject().put("id", "openai/gpt-4o"))
                    .put(JSONObject().put("id", "gpt-4o-mini"))
            )
        }
        assertEquals(
            listOf("gpt-4o-mini", "openai/gpt-4o"),
            MoodAiParser.parseOpenAiModels(openAi.toString())
        )
        val selfHosted = JSONObject().apply {
            put("models", JSONArray().put(JSONObject().put("name", "llama-3.1-8b")))
        }
        assertEquals(listOf("llama-3.1-8b"), MoodAiParser.parseOpenAiModels(selfHosted.toString()))
        assertTrue(MoodAiParser.parseOpenAiModels("not json at all").isEmpty())
        assertTrue(MoodAiParser.parseOpenAiModels("{\"data\":[]}").isEmpty())
    }

    // ----- replies -----

    @Test fun `a chat-completions reply is read like any other`() {
        val content = """
            ```json
            {"mood":"calm focus","valence":0.6,"energy":0.3,"tempo":84,"tags":["lofi","ambient"],
             "search_terms":["lofi study"],"explanation":"Steady and unhurried."}
            ```
        """.trimIndent()
        val reply = JSONObject().apply {
            put("object", "chat.completion")
            put(
                "choices",
                JSONArray().put(
                    JSONObject().apply {
                        put("index", 0)
                        put(
                            "message",
                            JSONObject().apply {
                                put("role", "assistant")
                                put("content", content)
                            }
                        )
                    }
                )
            )
        }
        val reading = MoodAiParser.parse(reply.toString())!!
        assertEquals("calm focus", reading.mood)
        assertEquals(84, reading.tempo)
        assertEquals(0.6, reading.valence, 0.0001)
        assertEquals(0.3, reading.energy, 0.0001)
        assertEquals(listOf("lofi", "ambient"), reading.tags)
        assertEquals(listOf("lofi study"), reading.searchTerms)
        assertEquals("Steady and unhurried.", reading.explanation)
    }

    @Test fun `an endpoint that answers with a refusal yields nothing, so the engine reads instead`() {
        val reply = JSONObject().apply {
            put(
                "choices",
                JSONArray().put(
                    JSONObject().apply {
                        put(
                            "message",
                            JSONObject().apply {
                                put("role", "assistant")
                                put("content", "I'm sorry, I can't help with that.")
                            }
                        )
                    }
                )
            )
        }
        assertNull(MoodAiParser.parse(reply.toString()))
    }

    // ----- failures -----

    @Test fun `the endpoint's own error sentence is the one shown`() {
        assertEquals(
            "Incorrect API key provided: sk-***",
            MoodAiParser.openAiError(
                "{\"error\":{\"message\":\"Incorrect API key provided: sk-***\"," +
                    "\"type\":\"invalid_request_error\"}}"
            )
        )
        assertNull(MoodAiParser.openAiError("{\"detail\":\"nope\"}"))
        assertNull(MoodAiParser.openAiError("not json"))
    }

    @Test fun `an unreachable endpoint says what to check, and localhost is the phone`() {
        val loopback = MoodAiParser.describeOpenAiNetworkFailure(
            "http://localhost:8000/v1",
            ConnectException("Connection refused")
        )
        assertTrue(loopback.contains("this device itself"))
        val remote = MoodAiParser.describeOpenAiNetworkFailure(
            "https://api.openai.com/v1",
            ConnectException("Connection refused")
        )
        assertTrue(remote.contains("api.openai.com/v1"))
        assertTrue(remote.contains("expecting a key"))
    }
}
