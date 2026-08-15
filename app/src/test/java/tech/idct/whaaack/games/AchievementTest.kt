package tech.idct.whaaack.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a run earns. The rule is one line, and every way of getting it wrong costs a player an
 * achievement they did in fact win — which is the failure nobody reports and everybody
 * notices.
 */
class AchievementTest {

    @Test
    fun `a short run earns nothing`() {
        assertEquals(emptyList<Achievement>(), Achievement.earnedBy(0L))
        assertEquals(emptyList<Achievement>(), Achievement.earnedBy(29_999L))
    }

    @Test
    fun `landing exactly on the threshold counts`() {
        // The boundary the description promises: "survive 30 seconds". A player who stops the
        // clock on 30,000 ms has done that, and an exclusive comparison would tell them they
        // had not.
        assertEquals(listOf(Achievement.SURVIVE_30), Achievement.earnedBy(30_000L))
    }

    @Test
    fun `a long run earns every milestone it passed, not just the last`() {
        // Play Games does not infer the lower tiers from the higher one, so a first run of a
        // hundred seconds has to unlock three achievements here or it unlocks one there.
        assertEquals(
            listOf(Achievement.SURVIVE_30, Achievement.SURVIVE_60, Achievement.SURVIVE_90),
            Achievement.earnedBy(100_000L),
        )
    }

    @Test
    fun `two minutes earns the lot`() {
        assertEquals(Achievement.entries, Achievement.earnedBy(120_000L))
    }

    @Test
    fun `every milestone carries a distinct console id`() {
        val ids = PlayGamesManager.CONFIGURED_IDS

        // Present and filled in. A blank id is legal — it is how the app behaved before the
        // achievements were published — but it is no longer the intended state, and a
        // milestone silently awarding nothing is invisible from inside the game.
        for (achievement in Achievement.entries) {
            val id = ids[achievement]
            assertNotNull("no id wired for $achievement", id)
            assertTrue("id for $achievement is blank", !id.isNullOrBlank())
        }

        // The four differ only in their last character, so pasting one of them twice is the
        // mistake that actually happens — and it would show up as a player earning the wrong
        // badge, which is a bug report nobody files clearly.
        assertEquals(
            "two milestones share an id: $ids",
            Achievement.entries.size,
            ids.values.toSet().size,
        )
    }

    @Test
    fun `thresholds are the seconds they are named after`() {
        // Guards the seconds-to-millis conversion itself: the names, the icons and the store
        // copy all promise these four numbers.
        assertEquals(listOf(30_000L, 60_000L, 90_000L, 120_000L), Achievement.entries.map { it.thresholdMillis })
        assertTrue(Achievement.entries.map { it.seconds } == listOf(30, 60, 90, 120))
    }
}
