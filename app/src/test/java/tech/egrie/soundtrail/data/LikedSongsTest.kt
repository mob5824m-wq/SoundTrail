package tech.egrie.soundtrail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.integrations.MusicProvider

/**
 * One list for likes.
 *
 * An import brings the account's liked videos under a name the app chose, and a listener who also
 * keeps their own liked list ends up with two. These are the rules that make it one list without
 * losing anything: which name means "likes", which list wins, and what happens to a song that is
 * in both.
 */
class LikedSongsTest {

    private fun item(id: String, title: String, url: String?, offline: String? = null) = PlaylistItem(
        id = id,
        title = title,
        artist = "Someone",
        url = url,
        provider = MusicProvider.YOUTUBE,
        createdAt = 0L,
        localFile = offline
    )

    private fun playlist(id: String, name: String, vararg items: PlaylistItem) =
        Playlist(id = id, name = name, items = items.toList(), createdAt = 0L)

    @Test
    fun `the names that mean the likes list`() {
        listOf("Liked", "liked songs", "YouTube Liked", "YouTube Music liked", "Favorites", " My Likes ")
            .forEach { assertTrue(it, LikedSongs.isLikedName(it)) }
        listOf("Road trip", "Liked but not really", "Workout", "YouTube Uploads").forEach {
            assertFalse(it, LikedSongs.isLikedName(it))
        }
    }

    @Test
    fun `a name the listener chose is theirs, one the app chose is not`() {
        assertTrue(LikedSongs.isImportedName("YouTube Liked"))
        assertTrue(LikedSongs.isImportedName("Liked videos"))
        assertTrue(LikedSongs.isImportedName("YouTube Music liked songs"))
        assertFalse(LikedSongs.isImportedName("Liked"))
        assertFalse(LikedSongs.isImportedName("Favorites"))
    }

    @Test
    fun `an import-created list is folded into the listener's own, and gone`() {
        val mine = playlist("mine", "Liked", item("a", "Sunsets", "https://www.youtube.com/watch?v=aaaaaaaaaaa"))
        val imported = playlist(
            "yt-pl:likes", "YouTube Liked",
            item("yt:likes:bbbbbbbbbbb", "Islands", "https://www.youtube.com/watch?v=bbbbbbbbbbb")
        )
        val (consolidated, moved) = consolidateLikes(listOf(mine, imported), "mine")

        assertEquals(1, moved)
        assertEquals(1, consolidated.size)
        assertEquals("mine", consolidated.first().id)
        assertEquals(listOf("Sunsets", "Islands"), consolidated.first().items.map { it.title })
    }

    @Test
    fun `a song that is in both lists is not added twice`() {
        val link = "https://www.youtube.com/watch?v=aaaaaaaaaaa"
        val mine = playlist("mine", "Liked", item("a", "Sunsets", link))
        val imported = playlist(
            "yt-pl:likes", "YouTube Liked",
            // Same song, different stored id: the link is what says it is the same song.
            item("yt:likes:aaaaaaaaaaa", "Sunsets", link),
            item("yt:likes:ccccccccccc", "VCR", "https://www.youtube.com/watch?v=ccccccccccc")
        )
        val (consolidated, moved) = consolidateLikes(listOf(mine, imported), "mine")

        assertEquals(1, moved)
        assertEquals(listOf("Sunsets", "VCR"), consolidated.first().items.map { it.title })
    }

    @Test
    fun `an offline copy travels with the song that moved`() {
        val mine = playlist("mine", "Liked")
        val imported = playlist(
            "yt-pl:likes", "YouTube Liked",
            item("yt:likes:aaaaaaaaaaa", "Sunsets", "https://www.youtube.com/watch?v=aaaaaaaaaaa", "sunsets.mp3")
        )
        val (consolidated, _) = consolidateLikes(listOf(mine, imported), "mine")

        assertEquals("sunsets.mp3", consolidated.first().items.single().localFile)
    }

    @Test
    fun `a list that is not a likes list is left where it is`() {
        val mine = playlist("mine", "Liked")
        val road = playlist(
            "yt-pl:road", "Road trip",
            item("yt:road:aaaaaaaaaaa", "Sunsets", "https://www.youtube.com/watch?v=aaaaaaaaaaa")
        )
        val (consolidated, moved) = consolidateLikes(listOf(mine, road), "mine")

        assertEquals(0, moved)
        assertEquals(2, consolidated.size)
    }

    @Test
    fun `the listener's own list is not folded away when a second one exists`() {
        // Only an app-created list is a source; a list the listener made stays theirs.
        val mine = playlist("mine", "Liked")
        val theirs = playlist("theirs", "My Likes", item("x", "Sunsets", null))
        val (consolidated, moved) = consolidateLikes(listOf(mine, theirs), "mine")

        assertEquals(0, moved)
        assertEquals(2, consolidated.size)
    }
}
