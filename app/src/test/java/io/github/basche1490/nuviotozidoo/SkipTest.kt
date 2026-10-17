package io.github.basche1490.nuviotozidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipTest {

    private val nuvioJson =
        """[{"type":"intro","start":30.0,"end":95.5},{"type":"recap","start":0,"end":20},""" +
            """{"type":"end-credits","start":2500.0,"end":2600.0},{"type":"post-credits","start":2600,"end":2650},""" +
            """{"type":"op","start":"bad","end":3},{"type":"ed","start":50,"end":40}]"""

    @Test
    fun parsesNuvioSegments() {
        val segments = SkipSegments.parse(nuvioJson)
        assertEquals(
            listOf(
                SkipSegment(SkipKind.RECAP, 0L, 20_000L),
                SkipSegment(SkipKind.INTRO, 30_000L, 95_500L),
                SkipSegment(SkipKind.CREDITS, 2_500_000L, 2_600_000L),
            ),
            segments,
        )
    }

    @Test
    fun badJsonIsEmpty() {
        assertTrue(SkipSegments.parse(null).isEmpty())
        assertTrue(SkipSegments.parse("").isEmpty())
        assertTrue(SkipSegments.parse("{}").isEmpty())
        assertTrue(SkipSegments.parse("nope").isEmpty())
    }

    @Test
    fun creditsStartOnlyNearTheEnd() {
        val segments = SkipSegments.parse(nuvioJson)
        assertEquals(2_500_000L, SkipSegments.creditsStartMs(segments, 2_640_000L))
        // An "ending" in the first 60% (for example a cold-open song) does not count.
        assertNull(SkipSegments.creditsStartMs(listOf(SkipSegment(SkipKind.CREDITS, 100_000L, 200_000L)), 2_640_000L))
        assertNull(SkipSegments.creditsStartMs(segments, 0L))
    }

    @Test
    fun skipsEachSegmentOnceAndRespectsSettings() {
        val planner = SkipPlanner(
            SkipSegments.parse(nuvioJson),
            SkipSettings(skipIntro = true, skipRecap = false, skipCredits = false),
        )
        assertNull(planner.nextAction(5_000L, 2_640_000L)) // recap: off
        val intro = planner.nextAction(31_000L, 2_640_000L)
        assertEquals(SkipAction(1, SkipKind.INTRO, 95_500L), intro)
        assertNull(planner.nextAction(32_000L, 2_640_000L)) // once only
        assertNull(planner.nextAction(2_550_000L, 2_640_000L)) // credits: off
    }

    @Test
    fun failedSeekCanRetry() {
        val planner = SkipPlanner(
            listOf(SkipSegment(SkipKind.INTRO, 10_000L, 60_000L)),
            SkipSettings(skipIntro = true, skipRecap = true, skipCredits = true),
        )
        val action = planner.nextAction(11_000L, 1_000_000L)!!
        planner.release(action)
        assertEquals(action, planner.nextAction(12_000L, 1_000_000L))
    }

    @Test
    fun creditsJumpStopsShortOfTheEnd() {
        val planner = SkipPlanner(
            listOf(SkipSegment(SkipKind.CREDITS, 1_900_000L, 2_100_000L)),
            SkipSettings(skipIntro = false, skipRecap = false, skipCredits = true),
        )
        val action = planner.nextAction(1_950_000L, 2_000_000L)!!
        assertEquals(2_000_000L - SkipPlanner.FINAL_MARGIN_MS, action.targetMs)
    }
}
