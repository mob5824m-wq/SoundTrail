package tech.egrie.soundtrail.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.integrations.MusicProvider

/**
 * What a spoken request turns into. This is the part of talking to the app that is testable
 * without a microphone, and the part that decides what a listener's words mean — so it is pinned
 * down in both directions: the sentences that must work, and the phrases that must survive
 * untouched.
 */
class AssistantParserTest {

    @Test
    fun `a plain request is the song`() {
        assertEquals("Sunsets", AssistantParser.parse("Play Sunsets")?.phrase)
        assertEquals("Bohemian Rhapsody", AssistantParser.parse("bohemian rhapsody")?.phrase)
    }

    @Test
    fun `command words come off however they were said`() {
        val cases = mapOf(
            "Hey Soundtrail, play Sunsets" to "Sunsets",
            "Okay Soundtrail, could you play Sunsets" to "Sunsets",
            "Can you play Sunsets" to "Sunsets",
            "I want to hear Sunsets" to "Sunsets",
            "please play Sunsets" to "Sunsets",
            "find me Sunsets" to "Sunsets",
            "put on Sunsets" to "Sunsets",
            "search for Sunsets" to "Sunsets",
            "queue up Sunsets" to "Sunsets",
            "start playing Sunsets" to "Sunsets"
        )
        // Sorting the keys makes the failure message name the phrase that broke.
        cases.toSortedMap().forEach { (said, expected) ->
            assertEquals(said, expected, AssistantParser.parse(said)?.phrase)
        }
    }

    @Test
    fun `an artist said after the title is kept as the artist`() {
        val request = AssistantParser.parse("Play Sunsets by The xx")
        assertEquals("Sunsets", request?.title)
        assertEquals("The xx", request?.artist)
        // The artist leads the search, because "sunsets" alone finds covers of it.
        assertEquals("The xx Sunsets", request?.phrase)
    }

    @Test
    fun `a title that starts with a command word keeps it`() {
        // "Play" is dropped only when the song still has two words to stand on.
        assertEquals("Play", AssistantParser.parse("Play Play")?.phrase)
        assertEquals("Play That Funky Music", AssistantParser.parse("Play That Funky Music")?.phrase)
    }

    @Test
    fun `the app a listener names is where the look should go`() {
        assertEquals(
            MusicProvider.YOUTUBE_MUSIC,
            AssistantParser.parse("Play Sunsets on YouTube Music")?.provider
        )
        assertEquals(
            MusicProvider.SPOTIFY,
            AssistantParser.parse("Play Sunsets on Spotify")?.provider
        )
        assertEquals(
            MusicProvider.SOUNDCLOUD,
            AssistantParser.parse("Sunsets by The xx on SoundCloud")?.provider
        )
        assertEquals(
            "Sunsets",
            AssistantParser.parse("Play Sunsets on YouTube Music")?.query
        )
    }

    @Test
    fun `a description of a feeling goes to the mood reader`() {
        assertTrue(AssistantParser.parse("I feel like something calm")?.mood == true)
        assertTrue(AssistantParser.parse("something chill for the drive")?.mood == true)
        assertTrue(AssistantParser.parse("put me in the mood for a party")?.mood == true)
        assertTrue(AssistantParser.parse("make me a mix for cooking")?.mood == true)
    }

    @Test
    fun `a song by an artist is a song, however it feels`() {
        // "Vibes" is a title here, and "by" is what says so.
        assertEquals("Kygo", AssistantParser.parse("play Vibes by Kygo")?.artist)
        assertFalse(AssistantParser.parse("play Vibes by Kygo")?.mood == true)
    }

    @Test
    fun `dictation punctuation is cleaned rather than searched for`() {
        assertEquals(
            "Sunsets",
            AssistantParser.parse("Play Sunsets.")?.phrase
        )
        assertEquals(
            "The xx",
            AssistantParser.parse("play Sunsets by The xx!")?.artist
        )
        // A recogniser's pause is not part of the song.
        assertEquals(
            "Sunsets",
            AssistantParser.parse("Okay Soundtrail, play Sunsets")?.phrase
        )
    }

    @Test
    fun `silence and bare commands ask for a request instead of searching`() {
        assertNull(AssistantParser.parse(""))
        assertNull(AssistantParser.parse("   "))
        assertNull(AssistantParser.parse("Play"))
        assertNull(AssistantParser.parse("Hey Soundtrail"))
        assertNull(AssistantParser.parse("please play"))
    }

    @Test
    fun `the song word is dropped when it is only a label`() {
        assertEquals("Sunsets", AssistantParser.parse("Play the song Sunsets")?.phrase)
        assertEquals("Sunsets", AssistantParser.parse("Play track Sunsets")?.phrase)
    }
}
