package tech.egrie.soundtrail.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.integrations.MusicProvider

class PlaybackQueueTest {
    private val track = "jellyfin:item:4cOdK2wGLETKBW3PvgPWqT"

    private fun entry(id: String) = QueueEntry(
        mediaId = id, title = id, artist = "Someone", provider = MusicProvider.JELLYFIN, url = track
    )

    private val three = listOf(entry("a"), entry("b"), entry("c"))

    @Test fun `starting clamps the requested index into the queue`() {
        val queue = PlaybackQueue()
        assertEquals("a", queue.start(three, 0)?.mediaId)
        assertEquals(0, queue.index)
        assertEquals("c", queue.start(three, 9)?.mediaId)
        assertEquals(2, queue.index)
        assertNull(queue.start(emptyList(), 0))
        assertEquals(-1, queue.index)
    }

    @Test fun `next and previous walk the queue without falling off either end`() {
        val queue = PlaybackQueue()
        queue.start(three, 1)
        assertTrue(queue.hasNext())
        assertTrue(queue.hasPrevious())

        assertEquals("c", queue.next()?.mediaId)
        assertFalse(queue.hasNext())
        assertEquals("c", queue.current?.mediaId)
        assertNull(queue.next())

        assertEquals("b", queue.previous()?.mediaId)
        assertEquals("a", queue.previous()?.mediaId)
        assertFalse(queue.hasPrevious())
        assertEquals("a", queue.current?.mediaId)
        assertNull(queue.previous())
    }

    @Test fun `a song is found by the id the media session uses`() {
        val queue = PlaybackQueue()
        queue.start(three, 0)
        assertEquals("b", queue.jumpTo("b")?.mediaId)
        assertEquals(1, queue.index)
        // An id that is not in this queue must not move the cursor.
        assertNull(queue.jumpTo("st:song:elsewhere"))
        assertEquals(1, queue.index)
    }

    @Test fun `replacing the queue keeps playing the same song`() {
        val queue = PlaybackQueue()
        queue.start(three, 1)
        queue.replace(listOf(entry("x"), entry("b"), entry("c")))
        assertEquals("b", queue.current?.mediaId)
        assertEquals(1, queue.index)
        // A song that left the queue leaves the cursor empty rather than pointing at a stranger.
        queue.replace(listOf(entry("x")))
        assertNull(queue.current)
        assertEquals(-1, queue.index)
    }

    @Test fun `clearing empties the queue and the cursor`() {
        val queue = PlaybackQueue()
        queue.start(three, 2)
        queue.clear()
        assertNull(queue.current)
        assertFalse(queue.isNotEmpty)
        assertEquals(0, queue.entries.size)
    }

    @Test fun `an entry is only worth queueing when it has a source`() {
        val bare = QueueEntry(mediaId = "st:song:1", title = "No link")
        assertFalse(bare.hasSource)
        assertTrue(entry("a").hasSource)
        assertTrue(bare.copy(localFile = "1.mp3").hasSource)
        assertTrue(bare.copy(url = track).hasSource)
        // The second line of a row prefers the artist, and falls back to where it came from.
        assertEquals("Someone", entry("a").subtitle)
        assertEquals("Trip mix", bare.copy(detail = "Trip mix").subtitle)
        // With neither, the row simply has one line.
        assertEquals("", bare.subtitle)
    }
}
