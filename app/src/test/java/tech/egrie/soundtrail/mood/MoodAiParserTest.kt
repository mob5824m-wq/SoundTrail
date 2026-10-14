package tech.egrie.soundtrail.mood

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a model sends back is not trusted: the response is located inside the API envelope, the
 * JSON is extracted from whatever prose or code fence surrounds it, and every number and tag is
 * clamped before it can reach the mixer.
 */
class MoodAiParserTest {
    private fun envelope(reply: String): String = JSONObject().apply {
        put(
            "candidates",
            JSONArray().put(
                JSONObject().apply {
                    put(
                        "content",
                        JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", reply)))
                        }
                    )
                }
            )
        )
    }.toString()

    @Test fun `a fenced json reply is read`() {
        val reply = "Sure!\n```json\n" +
            "{\"mood\":\"Restless\",\"valence\":0.32,\"energy\":0.78,\"tempo\":128," +
            "\"danceability\":0.5,\"acousticness\":0.2,\"instrumentalness\":0.1," +
            "\"tags\":[\"Lo-Fi Beats\",\"Late Night\"],\"search_terms\":[\"late night drive\"]," +
            "\"explanation\":\"Buzzing but scattered.\"}\n```"
        val reading = MoodAiParser.parse(envelope(reply))!!
        assertEquals("Restless", reading.mood)
        assertEquals(0.32, reading.valence, 0.0001)
        assertEquals(0.78, reading.energy, 0.0001)
        assertEquals(128, reading.tempo)
        assertEquals(listOf("lo-fi-beats", "late-night"), reading.tags)
        assertEquals(listOf("late night drive"), reading.searchTerms)
        assertEquals("Buzzing but scattered.", reading.explanation)
    }

    @Test fun `percentages are scaled and out-of-range values are clamped`() {
        val reply = JSONObject().apply {
            put("mood", "Up")
            put("valence", 80)
            put("energy", 120)
            put("tempo", 900)
            put("tags", JSONArray().put("Party!"))
        }.toString()
        val reading = MoodAiParser.parse(envelope(reply))!!
        assertEquals(0.8, reading.valence, 0.0001)
        assertEquals(1.0, reading.energy, 0.0001)
        assertEquals(220, reading.tempo)
        assertEquals(listOf("party"), reading.tags)
    }

    @Test fun `a missing tempo is estimated from the energy`() {
        val reply = "{\"mood\":\"Chill\",\"valence\":0.6,\"energy\":0.2}"
        val reading = MoodAiParser.parse(envelope(reply))!!
        assertTrue(reading.tempo > 60 && reading.tempo < 100)
    }

    @Test fun `a reply with no mood is refused instead of guessed at`() {
        assertNull(MoodAiParser.parse(envelope("{\"valence\":0.5}")))
    }

    @Test fun `a gemini envelope and an ollama reply are read by the same parser`() {
        // One parser for every provider: the app should not care which server answered.
        val gemini = MoodAiParser.textFrom(envelope("{\"mood\":\"Calm\"}"))
        val ollama = MoodAiParser.textFrom("""{"message":{"content":"{\"mood\":\"Calm\"}"}}""")
        assertEquals(gemini, ollama)
        assertEquals("Calm", MoodAiParser.parse(envelope("{\"mood\":\"Calm\"}"))!!.mood)
    }

    @Test fun `an envelope with no usable text yields nothing`() {
        assertNull(MoodAiParser.parse("{}"))
        assertNull(MoodAiParser.parse("{\"candidates\":[]}"))
        assertNull(MoodAiParser.parse(envelope("   ")))
        assertNull(MoodAiParser.parse(envelope("no json at all")))
    }

    @Test fun `the json scanner ignores braces inside strings`() {
        val text = "prefix {\"a\": \"} not the end\", \"b\": {\"c\": 1}} suffix"
        val json = MoodAiParser.extractJson(text)!!
        assertEquals("} not the end", JSONObject(json).getString("a"))
        assertNull(MoodAiParser.extractJson("no object here"))
        assertNull(MoodAiParser.extractJson("{\"unterminated\": 1"))
    }

    @Test fun `tags are capped, sanitised, and de-duplicated`() {
        val tags = JSONArray().apply {
            put("Lofi"); put("lofi"); put("a-very-long-tag-that-should-be-cut"); put("ok")
        }
        val reply = JSONObject().apply {
            put("mood", "Calm")
            put("tags", tags)
        }.toString()
        val reading = MoodAiParser.parse(envelope(reply))!!
        assertEquals(3, reading.tags.size)
        assertTrue(reading.tags.all { it.length <= 24 })
        assertTrue(reading.tags.all { it == it.lowercase() })
    }
}
