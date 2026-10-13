package tech.egrie.soundtrail.mood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodMixerTest {
    private val calm = MoodEngine.forMood("calm")!!

    private fun candidate(
        id: String,
        title: String,
        artist: String,
        url: String? = null,
        createdAt: Long = 0L
    ) = MoodCandidate(
        id = id, title = title, artist = artist, url = url, provider = null,
        origin = "Pin", createdAt = createdAt
    )

    @Test fun `songs that sound like the mood outrank songs that do not`() {
        val mix = MoodMixer.build(
            calm,
            listOf(
                candidate("metal", "Hardcore Death Metal Screamo", "Band"),
                candidate("lofi", "Lofi Chill Beats", "Someone"),
                candidate("unknown", "Track 1", "Nobody")
            )
        )
        assertEquals("lofi", mix.first().candidate.id)
        val scores = mix.associate { it.candidate.id to it.score }
        assertTrue(scores.getValue("lofi") > scores.getValue("metal"))
        assertTrue(scores.getValue("lofi") > 0.85)
        assertTrue(mix.first().reasons.any { it.contains("fits") })
        assertTrue(mix.first().reasons.any { it.contains("chill") || it.contains("lofi") })
    }

    @Test fun `one artist cannot take over the mix`() {
        val sameArtist = (1..3).map { index ->
            candidate("same-$index", "Lofi Chill Beats $index", "Same Artist")
        }
        val other = candidate("other", "Hard Rock Loud Screamo", "Different Artist")
        val mix = MoodMixer.build(calm, sameArtist + other, limit = 10)
        assertEquals(4, mix.size)
        assertEquals("Different Artist", mix[2].artist)
        assertEquals("Same Artist", mix.last().artist)
    }

    @Test fun `a song with no metadata is scored neutrally, not buried`() {
        val track = MoodMixer.build(calm, listOf(candidate("unknown", "Track 1", "Nobody"))).single()
        assertTrue("score was ${track.score}", track.score in 0.5..0.56)
        assertTrue(track.reasons.any { it.contains("no sound tags") })
    }

    @Test fun `tags learned from a lookup change the ranking and are credited`() {
        val learned = MoodCandidate(
            id = "a", title = "Track 1", artist = "Band", url = null, provider = null,
            origin = "Pin", tags = listOf("chill", "lofi"), valence = 0.62, energy = 0.26,
            tagSource = "TheAudioDB"
        )
        val mix = MoodMixer.build(calm, listOf(learned, candidate("b", "Track 2", "Band")))
        assertEquals("a", mix.first().candidate.id)
        assertTrue(mix.first().reasons.any { it.contains("TheAudioDB") })
    }

    @Test fun `duplicate links collapse and match percentages stay in range`() {
        val url = "https://open.spotify.com/track/0123456789abcdefghijkl"
        val mix = MoodMixer.build(
            calm,
            listOf(
                candidate("a", "Lofi Chill", "Someone", url),
                candidate("b", "Lofi Chill (slowed)", "Someone", url)
            )
        )
        assertEquals(1, mix.size)
        assertEquals(url, mix.single().url)
        assertTrue(mix.single().matchPercent in 0..100)
    }

    @Test fun `untagged finds the songs a lookup would help with`() {
        val untagged = MoodMixer.untagged(
            listOf(
                candidate("plain", "Track 1", "Nobody"),
                candidate("tagged", "Lofi Chill Beats", "Someone")
            )
        )
        assertEquals(listOf("plain"), untagged.map { it.id })
    }

    @Test fun `an empty library yields an empty mix, never an error`() {
        assertTrue(MoodMixer.build(calm, emptyList()).isEmpty())
        assertTrue(MoodMixer.untagged(emptyList()).isEmpty())
    }
}
