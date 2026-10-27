package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TheAudioDB as this app uses it: a metadata source.
 *
 * The API answers with a Spotify id and a music-video URL as well as the names and tags. This
 * parser reads the names and tags and ignores both links, because a link to a service the app no
 * longer supports is not something it can pin or play — offering one would be a promise the rest
 * of the app cannot keep. The payloads below still carry those fields, on purpose: they are what
 * the real API sends, and ignoring them is the behaviour under test.
 */
class AudioDbParserTest {
    @Test fun `parses track metadata and ignores the service links the API also sends`() {
        val payload = """
            {"track":[{
              "strTrack":"Yellow",
              "strAlbum":"Parachutes",
              "strArtist":"Coldplay",
              "strGenre":"Pop-Rock",
              "strMood":"Relaxed",
              "strMusicVid":"https://www.youtube.com/watch?v=yKNxeF4KMsY&feature=share",
              "strSpotifyID":"3AJwUDP919kvQ9QcozQPxg"
            }]}
        """.trimIndent()
        val songs = AudioDbParser.parse(payload)
        assertEquals(1, songs.size)
        val song = songs.first()
        assertEquals("Yellow", song.title)
        assertEquals("Coldplay", song.artist)
        assertEquals("Parachutes", song.album)
        assertEquals("Pop-Rock", song.genre)
        assertEquals("Relaxed", song.mood)
    }

    @Test fun `null and blank fields read as empty metadata, never as the string "null"`() {
        val payload = """
            {"track":[{
              "strTrack":"Demo",
              "strArtist":"Someone",
              "strAlbum":null,
              "strGenre":null,
              "strMood":"",
              "strMusicVid":"https://vimeo.com/12345",
              "strSpotifyID":"short-id"
            }]}
        """.trimIndent()
        val songs = AudioDbParser.parse(payload)
        assertEquals(1, songs.size)
        val song = songs.first()
        assertEquals("Demo", song.title)
        assertEquals("", song.album)
        assertEquals("", song.genre)
        assertEquals("", song.mood)
    }

    @Test fun `a track with no service links at all parses exactly the same`() {
        val payload = """
            {"track":[{"strTrack":"Song","strArtist":"Artist","strAlbum":"Album",
              "strGenre":"Ambient","strMood":"Calm"}]}
        """.trimIndent()
        val song = AudioDbParser.parse(payload).single()
        assertEquals("Song", song.title)
        assertEquals("Artist", song.artist)
        assertEquals("Album", song.album)
        assertEquals("Ambient", song.genre)
        assertEquals("Calm", song.mood)
    }

    @Test fun `duplicates collapse and the list stays short`() {
        val track = """{"strTrack":"Same","strArtist":"Artist","strAlbum":"Album"}"""
        val payload = """{"track":[$track,$track,$track]}"""
        assertEquals(1, AudioDbParser.parse(payload).size)
    }

    @Test fun `empty null and malformed responses produce no results`() {
        assertEquals(0, AudioDbParser.parse("""{"track":null}""").size)
        assertEquals(0, AudioDbParser.parse("""{"track":[]}""").size)
        assertEquals(0, AudioDbParser.parse("{}").size)
        assertEquals(0, AudioDbParser.parse("not json").size)
        assertEquals(0, AudioDbParser.parse("").size)
    }

    @Test fun `records without a title or artist are skipped`() {
        val payload = """
            {"track":[{"strAlbum":"No title here"},{"strTrack":"No artist"},{"strTrack":"Kept","strArtist":"Artist"}]}
        """.trimIndent()
        assertEquals(listOf("Kept"), AudioDbParser.parse(payload).map { it.title })
    }
}
