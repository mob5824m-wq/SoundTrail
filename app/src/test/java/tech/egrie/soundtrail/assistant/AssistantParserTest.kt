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
        // Nothing to strip, so the words are searched exactly as they were said.
        assertEquals("bohemian rhapsody", AssistantParser.parse("bohemian rhapsody")?.phrase)
        // A verb in front of a two-word title is a command, and comes off.
        assertEquals("Bohemian Rhapsody", AssistantParser.parse("Play Bohemian Rhapsody")?.phrase)
    }

    @Test
    fun `a one-word title keeps the verb, because the title may be the verb`() {
        // "Play Sunsets" searches for "Play Sunsets": YouTube finds the song either way, and a
        // song actually called "Play" is still findable. Matching drops the command word, so the
        // library still answers (see AssistantLibraryTest).
        assertEquals("Play Sunsets", AssistantParser.parse("Play Sunsets")?.phrase)
        assertEquals("Play Play", AssistantParser.parse("Play Play")?.phrase)
    }

    @Test
    fun `command words come off however they were said`() {
        val cases = mapOf(
            "Hey Soundtrail, play Bohemian Rhapsody" to "Bohemian Rhapsody",
            "Okay Soundtrail, could you play Bohemian Rhapsody" to "Bohemian Rhapsody",
            "Can you play Bohemian Rhapsody" to "Bohemian Rhapsody",
            "I want to hear Bohemian Rhapsody" to "Bohemian Rhapsody",
            "please play Bohemian Rhapsody" to "Bohemian Rhapsody",
            "find me Bohemian Rhapsody" to "Bohemian Rhapsody",
            "put on Bohemian Rhapsody" to "Bohemian Rhapsody",
            "search for Bohemian Rhapsody" to "Bohemian Rhapsody",
            "queue up Bohemian Rhapsody" to "Bohemian Rhapsody",
            "start playing Bohemian Rhapsody" to "Bohemian Rhapsody"
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
    fun `a title that starts with a command word is not read as two commands`() {
        // What follows the verb begins with a command word of its own, so nothing comes off.
        assertEquals("Play Search and Destroy", AssistantParser.parse("Play Search and Destroy")?.phrase)
        assertEquals("Play Start Me Up", AssistantParser.parse("Play Start Me Up")?.phrase)
        assertEquals("Play Queue the Night", AssistantParser.parse("Play Queue the Night")?.phrase)
    }

    @Test
    fun `the words as said are kept for a title a command word was hiding`() {
        val request = AssistantParser.parse("Play That Funky Music")
        // The command comes off the phrase that is searched first...
        assertEquals("That Funky Music", request?.phrase)
        // ...and the sentence as spoken stays, for a search that finds nothing to try again.
        assertEquals("Play That Funky Music", request?.spoken)
    }

    @Test
    fun `the app a listener names is where the look should go`() {
        assertEquals(
            MusicProvider.YOUTUBE_MUSIC,
            AssistantParser.parse("Play Bohemian Rhapsody on YouTube Music")?.provider
        )
        assertEquals(
            MusicProvider.SPOTIFY,
            AssistantParser.parse("Play Bohemian Rhapsody on Spotify")?.provider
        )
        assertEquals(
            MusicProvider.SOUNDCLOUD,
            AssistantParser.parse("Sunsets by The xx on SoundCloud")?.provider
        )
        // Where to look is not part of the song.
        assertEquals(
            "Bohemian Rhapsody",
            AssistantParser.parse("Play Bohemian Rhapsody on YouTube Music")?.query
        )
        assertEquals(
            "Bohemian Rhapsody",
            AssistantParser.parse("Play Bohemian Rhapsody on YouTube Music.")?.query
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
        // A recogniser's full stop, exclamation or pause is not part of the song.
        assertEquals("Play Sunsets", AssistantParser.parse("Play Sunsets.")?.phrase)
        assertEquals("Bohemian Rhapsody", AssistantParser.parse("Play Bohemian Rhapsody!")?.phrase)
        assertEquals("The xx", AssistantParser.parse("play Sunsets by The xx!")?.artist)
        assertEquals("Bohemian Rhapsody", AssistantParser.parse("Okay Soundtrail, play Bohemian Rhapsody")?.phrase)
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
        // "the song X" is how people say it, so the label comes off...
        assertEquals("Sunsets", AssistantParser.parse("Play the song Sunsets")?.phrase)
        assertEquals("Sunsets", AssistantParser.parse("Play the track Sunsets")?.phrase)
        // ...and a bare "song" only when a title still has two words to stand on, so "Song 2"
        // keeps its name.
        assertEquals("Bohemian Rhapsody", AssistantParser.parse("Play song Bohemian Rhapsody")?.phrase)
        assertEquals("Song 2", AssistantParser.parse("Play Song 2")?.phrase)
        assertEquals("Song 2", AssistantParser.parse("Play Song 2 by Blur")?.title)
    }
}
