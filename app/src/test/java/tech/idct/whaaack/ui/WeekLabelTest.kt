package tech.idct.whaaack.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The weekly board is cut server-side at Monday 00:00 UTC. This label is the only thing that
 * tells a player which week they are looking at, so it has to be right at the boundaries —
 * which is exactly where a "Mon d - Sun d MMM" format is easiest to get wrong.
 */
class WeekLabelTest {

    private fun label(iso: String) = utcWeekLabel(LocalDate.parse(iso))

    @Test
    fun `every day of a week names the same window`() {
        val expected = "Mon 10 - Sun 16 Aug, UTC"
        assertEquals("monday itself", expected, label("2026-08-10"))
        assertEquals("midweek", expected, label("2026-08-13"))
        assertEquals("saturday", expected, label("2026-08-15"))
        assertEquals("sunday, the last day", expected, label("2026-08-16"))
        // ...and the next Monday has rolled over to the following window.
        assertEquals("Mon 17 - Sun 23 Aug, UTC", label("2026-08-17"))
    }

    @Test
    fun `the month is repeated only when the week straddles one`() {
        assertEquals("Mon 10 - Sun 16 Aug, UTC", label("2026-08-12"))
        assertEquals("Mon 27 Jul - Sun 2 Aug, UTC", label("2026-07-30"))
        assertEquals("Mon 31 Aug - Sun 6 Sep, UTC", label("2026-09-02"))
    }

    @Test
    fun `a week straddling new year still reads correctly`() {
        assertEquals("Mon 28 Dec - Sun 3 Jan, UTC", label("2026-12-31"))
        assertEquals("Mon 28 Dec - Sun 3 Jan, UTC", label("2027-01-01"))
    }

    @Test
    fun `a leap day is just another tuesday`() {
        assertEquals("Mon 28 Feb - Sun 5 Mar, UTC", label("2028-02-29"))
    }
}
