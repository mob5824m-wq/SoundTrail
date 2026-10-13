package tech.egrie.soundtrail.mood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodEngineTest {
    @Test fun `plain words about being calm are read as calm`() {
        val profile = MoodEngine.analyze("i feel calm and relaxed, want something peaceful")
        assertEquals("calm", profile.id)
        assertTrue(profile.energy < 0.4)
        assertTrue(profile.matchedWords.contains("calm"))
        assertEquals(MoodSource.ON_DEVICE, profile.source)
    }

    @Test fun `activity words carry the mood too`() {
        val profile = MoodEngine.analyze("heading to the gym, want something loud and fast")
        assertEquals("hype", profile.id)
        assertTrue(profile.energy > 0.7)
    }

    @Test fun `negation flips the mood to its counterpart`() {
        val profile = MoodEngine.analyze("not happy at all")
        assertEquals("sad", profile.id)
        assertTrue(profile.valence < 0.25)
    }

    @Test fun `intensifiers raise confidence`() {
        val strong = MoodEngine.analyze("i am absolutely furious and angry")
        assertEquals("angry", strong.id)
        assertTrue(strong.confidence > 0.8)

        val mild = MoodEngine.analyze("a bit angry")
        assertTrue(mild.confidence < strong.confidence)
    }

    @Test fun `emoji are read like words`() {
        val profile = MoodEngine.analyze("\uD83D\uDE2D")
        assertEquals("sad", profile.id)
    }

    @Test fun `an empty description still returns a gentle reading, not a crash`() {
        val profile = MoodEngine.analyze("")
        assertEquals("calm", profile.id)
        assertTrue(profile.confidence < 0.3)
        assertTrue(profile.matchedWords.isEmpty())
        assertTrue(profile.note != null)
    }

    @Test fun `time of day is a tilt when nothing else is said`() {
        val late = MoodEngine.analyze("music", MoodContext(hourOfDay = 2))
        assertEquals("sleepy", late.id)
        val eveningOut = MoodEngine.analyze("music", MoodContext(hourOfDay = 19))
        assertEquals("party", eveningOut.id)
    }

    @Test fun `an energy hint steers the reading even with no words`() {
        val high = MoodEngine.analyze("", MoodContext(energyHint = 0.95))
        assertEquals("hype", high.id)
        assertTrue(high.energy > 0.8)

        val low = MoodEngine.analyze("", MoodContext(energyHint = 0.05))
        assertEquals("sleepy", low.id)
        assertTrue(low.energy < 0.2)
    }

    @Test fun `context never overrules the listener's own words`() {
        val profile = MoodEngine.analyze("i am so sad today", MoodContext(hourOfDay = 19))
        assertEquals("sad", profile.id)
    }

    @Test fun `a picked mood is returned directly`() {
        val profile = MoodEngine.forMood("romantic")
        assertEquals("romantic", profile?.id)
        assertEquals("Romantic", profile?.label)
        assertTrue(profile!!.confidence > 0.8)
        assertEquals(null, MoodEngine.forMood("not-a-mood"))
    }

    @Test fun `mood queries are short enough to hand to another app`() {
        MoodCatalog.moods.forEach { mood ->
            val profile = MoodEngine.forMood(mood.id)!!
            assertTrue("query too long for ${mood.id}: ${profile.query}", profile.query.length <= 120)
            assertTrue("no search terms for ${mood.id}", profile.searchTerms.isNotEmpty())
        }
    }

    @Test fun `the catalogue has unique ids and labels and sane ranges`() {
        assertEquals(MoodCatalog.moods.size, MoodCatalog.moods.map { it.id }.toSet().size)
        assertEquals(MoodCatalog.moods.size, MoodCatalog.moods.map { it.label }.toSet().size)
        MoodCatalog.moods.forEach { mood ->
            assertTrue(mood.valence in 0.0..1.0)
            assertTrue(mood.energy in 0.0..1.0)
            assertTrue(mood.tempo in 50..200)
            assertTrue(mood.words.isNotEmpty())
            assertTrue(mood.tags.isNotEmpty())
        }
    }

    @Test fun `normalisation removes accents, apostrophes and punctuation`() {
        assertEquals("dont stop wont stop", MoodEngine.normalize("Don't stop, won't stop!"))
        assertEquals("cafe creme", MoodEngine.normalize("Caf\u00e9 cr\u00e8me"))
    }

    @Test fun `unknown words never produce a confident verdict`() {
        val profile = MoodEngine.analyze("qwerty asdf zxcv")
        assertTrue(profile.confidence < 0.5)
        assertFalse(profile.matchedWords.isNotEmpty())
    }
}
