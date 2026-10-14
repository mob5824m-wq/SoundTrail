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

    @Test fun `Arena's portal catalog is read the way its own setup script reads it`() {
        // The portal hands its setup script {"gatewayUrl": …, "data": [ … ]}, the same "data"
        // array an OpenAI-compatible /models answers with — so one parser reads both.
        val catalog = JSONObject().apply {
            put("gatewayUrl", "https://api.preview.arena.ai")
            put(
                "data",
                JSONArray()
                    .put(
                        JSONObject().apply {
                            put("id", "coding-router-preview")
                            put("arena_type", "direct")
                            put("name", "(Arena) Coding Router")
                        }
                    )
                    .put(
                        JSONObject().apply {
                            put("id", "claude-max-v0")
                            put("arena_type", "direct")
                            put("name", "Claude Max")
                        }
                    )
            )
        }
        assertEquals(
            listOf("coding-router-preview", "claude-max-v0"),
            MoodAiParser.parseOpenAiModels(catalog.toString())
        )
    }

    @Test fun `rows that are not callable models are skipped, as Arena's script skips them`() {
        val catalog = JSONObject().apply {
            put(
                "data",
                JSONArray()
                    // A routing row: the router chooses, so there is nothing to call directly.
                    .put(JSONObject().put("id", "routing-row").put("arena_type", "routing"))
                    // Arena's own entry, which its script also refuses to offer.
                    .put(JSONObject().put("id", "exo").put("arena_type", "direct"))
                    .put(JSONObject().put("id", "claude-coding-router-preview").put("arena_type", "direct"))
            )
        }
        assertEquals(
            listOf("claude-coding-router-preview"),
            MoodAiParser.parseOpenAiModels(catalog.toString())
        )
        // An ordinary endpoint's list is untouched: no arena_type, no Arena rules.
        val plain = "{\"data\":[{\"id\":\"exo\"},{\"id\":\"gpt-4o-mini\"}]}"
        assertEquals(listOf("exo", "gpt-4o-mini"), MoodAiParser.parseOpenAiModels(plain))
    }

    @Test fun `the router alias follows the key, the way Arena's script follows it`() {
        // Preferred first, whichever of the four the key actually has.
        assertEquals(
            "coding-router-preview",
            MoodAiUrls.arenaRouterFrom(listOf("claude-max-v0", "coding-router-preview", "gpt-4o"))
        )
        assertEquals("claude-max-v0", MoodAiUrls.arenaRouterFrom(listOf("claude-max-v0", "gpt-4o")))
        assertEquals("claude-coding-router-preview", MoodAiUrls.arenaRouterFrom(listOf("claude-coding-router-preview")))
        assertEquals("max-v0", MoodAiUrls.arenaRouterFrom(listOf("max-v0")))
        // No router alias at all leaves the choice to the listener.
        assertNull(MoodAiUrls.arenaRouterFrom(listOf("gpt-4o", "some-model")))
        assertNull(MoodAiUrls.arenaRouterFrom(emptyList()))
    }

    @Test fun `a key with no router alias falls back to the models it does have`() {
        // A key can be enabled for named models and no router alias at all — the portal shows a
        // list like Claude, GPT, Gemini, Kimi, and "Coding Router" unchecked. setup.sh falls back
        // to models[0] there, and so does the app, rather than leaving an alias that will 404.
        val named = listOf("claude-fable-5-1", "gpt-6-sol", "gemini-3.8-flash", "kimi-k3")
        assertEquals("claude-fable-5-1", MoodAiUrls.arenaModelFrom(named))
        assertNull(MoodAiUrls.arenaRouterFrom(named))
        // With an alias present, the alias still wins, whichever position it is in.
        assertEquals(
            "coding-router-preview",
            MoodAiUrls.arenaModelFrom(listOf("claude-fable-5-1", "coding-router-preview"))
        )
        assertNull(MoodAiUrls.arenaModelFrom(emptyList()))
        assertTrue(MoodAiUrls.isArenaRouter("max-v0"))
        assertTrue(!MoodAiUrls.isArenaRouter("claude-fable-5-1"))
    }

    @Test fun `Arena's gateway is an endpoint like any other, so its preset has to normalise cleanly`() {
        val base = MoodAiUrls.normalizeOpenAi(MoodAiUrls.ARENA_BASE_URL)!!
        assertEquals("https://api.preview.arena.ai/v1", base)
        assertEquals("https://api.preview.arena.ai/v1/chat/completions", MoodAiUrls.openAiChatUrl(base))
        assertEquals("https://api.preview.arena.ai/v1/models", MoodAiUrls.openAiModelsUrl(base))
        assertTrue(MoodAiUrls.isArena(base))
        // The alias Arena's portal documents survives the model-name sanitiser untouched.
        assertEquals("coding-router-preview", MoodStore.sanitizeOpenAiModel(MoodAiUrls.ARENA_MODEL))
        // And another endpoint's address is not mistaken for Arena's.
        assertTrue(!MoodAiUrls.isArena(MoodAiUrls.normalizeOpenAi("api.openai.com")))
        assertTrue(!MoodAiUrls.isArena(null))
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
