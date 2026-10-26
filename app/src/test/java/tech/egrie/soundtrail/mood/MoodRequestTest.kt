package tech.egrie.soundtrail.mood

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request a mood reading makes, and the songs that come back with the answer.
 *
 * Two things are pinned down here. The request is the one the listener asked for, in those words,
 * with the provider they chose named in it. And a named song is only ever a suggestion: the parser
 * keeps it tidy and bounded so the resolver can then search for it, and nothing downstream is
 * allowed to play a song just because a model wrote its name.
 */
class MoodRequestTest {

    // ----- the request -----

    @Test fun `the request names the provider and the words that were used`() {
        assertEquals(
            "find music on your Jellyfin server that fits: \"tired but warm\"",
            MoodSearchTarget.request("tired but warm", MoodSearchTarget.JELLYFIN)
        )
        assertEquals(
            "find music in your own library that fits: \"quiet focus\"",
            MoodSearchTarget.LIBRARY.let { MoodSearchTarget.request("quiet focus", it) }
        )
        assertEquals(
            "find music on your Jellyfin server that fits: \"rainy sunday\"",
            MoodSearchTarget.request("  rainy sunday  ", MoodSearchTarget.JELLYFIN)
        )
    }

    @Test fun `the first line the model reads is that request`() {
        val prompt = MoodAiPrompts.describe("tired but warm", MoodContext(), MoodSearchTarget.JELLYFIN)
        assertTrue(prompt.startsWith("find music on your Jellyfin server that fits: \"tired but warm\""))
        // The context still travels, because the hour and motion tilt the reading.
        val withContext = MoodAiPrompts.describe(
            "tired but warm",
            MoodContext(energyHint = 0.8, hourOfDay = 22, motion = "walking"),
            MoodSearchTarget.JELLYFIN
        )
        assertTrue(withContext.contains("energy around 80"))
        assertTrue(withContext.contains("Local time: 22:00"))
        assertTrue(withContext.contains("They are walking."))
        // And the contract asks for songs by name, while saying they will be looked up.
        val instruction = MoodAiPrompts.instruction()
        assertTrue(instruction.contains("picks"))
        assertTrue(instruction.contains("title, artist, and why"))
        assertTrue(instruction.contains("the app searches for each title and artist"))
    }

    @Test fun `every target reads as a provider and survives a round trip`() {
        MoodSearchTarget.values().forEach { target ->
            assertEquals(target, MoodSearchTarget.from(target.id))
        }
        // Whatever the phrase is, it has to be the same words in the request and on the tab.
        MoodSearchTarget.values().forEach { target ->
            assertTrue(MoodSearchTarget.request("x", target).contains(target.phrase))
        }
        assertEquals(MoodSearchTarget.LIBRARY, MoodSearchTarget.from(null))
        assertEquals(MoodSearchTarget.LIBRARY, MoodSearchTarget.from("gpt-9000"))
        assertEquals(MoodSearchTarget.JELLYFIN, MoodSearchTarget.from(" Jellyfin "))
        // An id saved by an older version for a service that is gone falls back to the library
        // instead of selecting something that no longer exists.
        listOf("youtube", "youtube_music", "spotify", "soundcloud").forEach { gone ->
            assertEquals(gone, MoodSearchTarget.LIBRARY, MoodSearchTarget.from(gone))
        }
    }

    @Test fun `picking the server without a server says so, and the library stays quiet`() {
        val warned = MoodSearchTarget.JELLYFIN.missingConnection(jellyfin = false)
        assertTrue("Jellyfin should warn without a connection", !warned.isNullOrBlank())
        assertTrue(warned!!.contains(MoodSearchTarget.JELLYFIN.short))
        // The library needs no connection, so it never has anything to warn about.
        assertNull(MoodSearchTarget.LIBRARY.missingConnection(jellyfin = false))
        assertNull(MoodSearchTarget.LIBRARY.missingConnection(jellyfin = true))
        MoodSearchTarget.entries.forEach { target ->
            assertNull("$target should be quiet once connected", target.missingConnection(jellyfin = true))
        }
    }

    // ----- the songs that come back -----

    private fun replyWith(picks: Any): String = JSONObject().apply {
        put("mood", "Calm")
        put("valence", 0.6)
        put("energy", 0.3)
        if (picks is JSONArray) put("picks", picks) else put("picks", picks.toString())
    }.toString()

    @Test fun `named songs are read with their reason`() {
        val picks = JSONArray()
            .put(
                JSONObject().apply {
                    put("title", "Sunsets")
                    put("artist", "The xx")
                    put("why", "Slow, warm, and unhurried.")
                }
            )
            .put(JSONObject().put("name", "Teardrop").put("by", "Massive Attack"))
        val reading = MoodAiParser.parse(replyWith(picks))!!
        assertEquals(2, reading.picks.size)
        assertEquals("Sunsets", reading.picks[0].title)
        assertEquals("The xx", reading.picks[0].artist)
        assertEquals("Slow, warm, and unhurried.", reading.picks[0].why)
        assertEquals("Teardrop", reading.picks[1].title)
        assertEquals("Massive Attack", reading.picks[1].artist)
        assertNull(reading.picks[1].why)
    }

    @Test fun `a model that answers with songs or tracks is still understood`() {
        val one = JSONArray().put(JSONObject().put("title", "Nightcall").put("artist", "Kavinsky"))
        // Three names for the same list, because a model asked for "picks" reaches for the word
        // it likes better often enough that refusing the other two would lose real songs.
        listOf("picks", "songs", "tracks").forEach { key ->
            val reply = JSONObject().apply {
                put("mood", "Calm")
                put("valence", 0.5)
                put("energy", 0.5)
                put(key, one)
            }.toString()
            assertEquals("the $key shape", 1, MoodAiParser.parse(reply)!!.picks.size)
        }
    }

    @Test fun `a plain string is split the way music databases write it`() {
        // A dash follows the convention everywhere: artist, then title.
        val dashed = stringPicks("The xx - Sunsets")
        assertEquals("Sunsets", dashed.title)
        assertEquals("The xx", dashed.artist)
        val emDash = stringPicks("Massive Attack \u2013 Teardrop")
        assertEquals("Teardrop", emDash.title)
        assertEquals("Massive Attack", emDash.artist)
        // "Title by Artist" says which half is which out loud, so nothing needs guessing.
        val byArtist = stringPicks("Sunsets by the xx")
        assertEquals("Sunsets", byArtist.title)
        assertEquals("the xx", byArtist.artist)
        // A title on its own stays a title, with no artist invented for it.
        val alone = stringPicks("Teardrop")
        assertEquals("Teardrop", alone.title)
        assertEquals("", alone.artist)
    }

    @Test fun `songs are capped, blanks dropped, and duplicates collapsed`() {
        val many = JSONArray()
        for (index in 1..12) {
            many.put(JSONObject().put("title", "Song $index").put("artist", "Artist"))
        }
        many.put(JSONObject().put("title", "Song 1").put("artist", "Artist"))
        many.put(JSONObject().put("title", "   "))
        assertEquals(MAX_MOOD_PICKS, MoodAiParser.pickList(objectFor(many)).size)
        val deduped = MoodAiParser.pickList(
            objectFor(
                JSONArray()
                    .put(JSONObject().put("title", "Sunsets").put("artist", "The xx"))
                    .put(JSONObject().put("title", "sunsets").put("artist", "the XX"))
            )
        )
        assertEquals(1, deduped.size)
    }

    private fun objectFor(array: JSONArray): JSONObject = JSONObject().apply {
        put("mood", "Calm")
        put("picks", array)
    }

    /** One song, answered as a plain string instead of an object. */
    private fun stringPicks(raw: String): MoodAiPick =
        MoodAiParser.pickList(objectFor(JSONArray().put(raw))).single()

    // ----- matching a named song to a real one -----

    @Test fun `the song that was named beats a closer-looking remix`() {
        val results = listOf(
            track("Sunsets (Remix)", "The xx"),
            track("Sunsets", "The xx"),
            track("Sunsets", "Some Cover Band")
        )
        val best = MoodPickMatch.best("Sunsets", "The xx", results)!!
        assertEquals("The xx", best.artist)
        assertEquals("Sunsets", best.title)
    }

    @Test fun `a song nothing matches is not found rather than replaced`() {
        val results = listOf(track("Completely Different", "Another Band"))
        assertNull(MoodPickMatch.best("Sunsets", "The xx", results))
        assertNull(MoodPickMatch.best("Sunsets", "The xx", emptyList()))
        // Words in the artist alone are not enough: that would match every song by the band.
        assertNull(MoodPickMatch.best("Teardrop", "Massive Attack", listOf(track("Angel", "Massive Attack"))))
        // A title word in common is enough to be the answer when nothing better exists.
        val partial = MoodPickMatch.best(
            "Sunsets (Remastered)",
            "The xx",
            listOf(track("Sunsets", "The xx"))
        )
        assertEquals("Sunsets", partial?.title)
    }

    @Test fun `the search phrase puts the artist first`() {
        assertEquals("The xx Sunsets", MoodPickMatch.phrase("Sunsets", "The xx"))
        assertEquals("Teardrop", MoodPickMatch.phrase("Teardrop", ""))
        assertEquals("Teardrop", MoodPickMatch.phrase("  Teardrop ", "   "))
    }

    private fun track(title: String, artist: String) = tech.egrie.soundtrail.integrations.TrackResult(
        title = title,
        artist = artist,
        url = "jellyfin:item:4cOdK2wGLETKBW3PvgPWqT",
        provider = tech.egrie.soundtrail.integrations.MusicProvider.JELLYFIN
    )
}
