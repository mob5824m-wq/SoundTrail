package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioDbParserTest {
    @Test fun `parses track metadata into canonical playable links`() {
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
        assertEquals("https://open.spotify.com/track/3AJwUDP919kvQ9QcozQPxg", song.spotifyUrl)
        assertEquals("https://www.youtube.com/watch?v=yKNxeF4KMsY", song.youtubeUrl)
        assertEquals("https://open.spotify.com/track/3AJwUDP919kvQ9QcozQPxg", song.url)
    }

    @Test fun `malformed ids null fields and unsupported video hosts yield metadata only`() {
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
        assertNull(song.spotifyUrl)
        assertNull(song.youtubeUrl)
        assertNull(song.url)
    }

    @Test fun `youtube only tracks still offer a pin link`() {
        val payload = """
            {"track":[{"strTrack":"Song","strArtist":"Artist",
              "strMusicVid":"https://youtu.be/dQw4w9WgXcQ"}]}
        """.trimIndent()
        val song = AudioDbParser.parse(payload).single()
        assertNull(song.spotifyUrl)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", song.youtubeUrl)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", song.url)
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
