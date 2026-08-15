package tech.idct.whaaack.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.whaaack.game.GameEngine
import java.io.File

/**
 * That the app sends exactly what the console was told to expect.
 *
 * Play Games Services validates every uploaded event against `PlayerGameEvent.csv` and
 * **discards** the ones that do not match. No exception, no failed Task, nothing in logcat
 * from our side — a renamed property or an `INT64` sent as a `DOUBLE` simply means a stat that
 * reads zero for every player until somebody notices months later. There is no runtime signal
 * to test against, so this file is the signal: it reads the committed CSV and fails the build
 * when the two have drifted.
 */
class GameStatsTest {

    /**
     * The CSV lives outside the Gradle module, so the module directory is walked up from
     * rather than assumed — `user.dir` is `app/` under Gradle but the IDE has been known to
     * disagree, and a test that silently cannot find its fixture is worse than no test.
     */
    private val declared: List<Triple<String, String, String>> by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val root = requireNotNull(dir) { "could not find the repository root from ${System.getProperty("user.dir")}" }
        val csv = File(root, "assets/game-stats/PlayerGameEvent.csv")
        assertTrue("missing ${csv.path}", csv.isFile)

        csv.readLines()
            .filter { it.isNotBlank() }
            .drop(1) // header
            .map { line ->
                val cells = line.split(",").map { it.trim() }
                assertEquals("expected 3 columns in: $line", 3, cells.size)
                Triple(cells[0], cells[1], cells[2])
            }
    }

    @Test
    fun `the declared events and properties are exactly what the code sends`() {
        val fromCsv: Map<String, List<Pair<String, String>>> =
            declared.groupBy({ it.first }, { it.second to it.third })
        val fromCode: Map<String, List<Pair<String, String>>> =
            GameStats.SCHEMA.mapValues { (_, props) -> props.map { it.name to it.type.name } }

        assertEquals(
            "PlayerGameEvent.csv and GameStats.SCHEMA disagree — an event that does not match " +
                "the declaration is discarded by Play Games without telling the app",
            fromCode,
            fromCsv,
        )
    }

    @Test
    fun `names obey the console's rules`() {
        val reserved = setOf("id", "count", "key", "value", "where", "from", "and", "or", "true", "false")
        val allowed = Regex("^[A-Za-z0-9_]+$")

        assertTrue("the console allows 20 event names", GameStats.SCHEMA.size <= 20)
        for ((event, properties) in GameStats.SCHEMA) {
            assertTrue("'$event' is over 50 characters", event.length <= 50)
            assertTrue("'$event' must be letters, numbers and underscores", allowed.matches(event))
            assertTrue("'$event' declares ${properties.size} properties, limit is 20", properties.size <= 20)

            for (property in properties) {
                assertTrue("'${property.name}' is over 50 characters", property.name.length <= 50)
                assertTrue(
                    "'${property.name}' must be letters, numbers and underscores only",
                    allowed.matches(property.name),
                )
                assertTrue(
                    "'${property.name}' is a console-reserved word",
                    property.name.lowercase() !in reserved,
                )
            }
            assertEquals(
                "property names must be unique within '$event'",
                properties.size,
                properties.map { it.name }.toSet().size,
            )
        }
    }

    @Test
    fun `the progression event is the one the console predefines`() {
        // A progression stat can only be built on this event reading this property, and the
        // type is restricted to INT64 or STRING. Spelling either differently means a stat
        // that never receives a value.
        assertEquals("progressUpdate", GameStats.PROGRESS_UPDATE)
        assertEquals(listOf(GameStats.CURRENT_PROGRESS), GameStats.SCHEMA[GameStats.PROGRESS_UPDATE])
        assertEquals("currentProgress", GameStats.CURRENT_PROGRESS.name)
        assertEquals(GameStats.Type.INT64, GameStats.CURRENT_PROGRESS.type)
    }

    @Test
    fun `the best is reported in seconds, truncated`() {
        // Seconds because the stat carries the SECOND unit; milliseconds under that unit
        // would claim a day and a half. Truncated because rounding 59,900 ms up to a minute
        // would show a best that contradicts the "Minute Made" achievement the player has
        // not earned.
        assertEquals(93L, GameStats.progressUpdate(93_500L)[GameStats.CURRENT_PROGRESS])
        assertEquals(59L, GameStats.progressUpdate(59_900L)[GameStats.CURRENT_PROGRESS])
        // Sent even when there is nothing yet, so the stat exists for every player.
        assertEquals(0L, GameStats.progressUpdate(0L)[GameStats.CURRENT_PROGRESS])
    }

    @Test
    fun `every recorded value has the Kotlin type its declaration promises`() {
        // The trap this exists for: Kotlin resolves addProperty(String, Int) to the `long`
        // overload only after an explicit toLong(), and an Int handed to a DOUBLE declaration
        // uploads a value the console then rejects for its type.
        val values = GameStats.runCompleted(
            GameEngine.Result(
                millisSurvived = 93_500L,
                hits = 41,
                strikes = 3,
                topSpeedLevel = 23,
                ranked = true,
                quit = false,
            ),
        )

        assertEquals(
            "every declared property must be sent",
            GameStats.SCHEMA.getValue(GameStats.RUN_COMPLETED).toSet(),
            values.keys,
        )
        for ((property, value) in values) {
            val expected = when (property.type) {
                GameStats.Type.INT64 -> Long::class
                GameStats.Type.DOUBLE -> Double::class
                GameStats.Type.BOOL -> Boolean::class
                GameStats.Type.STRING -> String::class
            }
            assertEquals(
                "${property.name} is declared ${property.type} but carries ${value::class.simpleName}",
                expected,
                value::class,
            )
        }
    }

    @Test
    fun `a run is described the way the game describes it`() {
        val result = GameEngine.Result(
            millisSurvived = 93_500L,
            hits = 41,
            strikes = 3,
            topSpeedLevel = 23,
            ranked = true,
            quit = false,
        )
        val values = GameStats.runCompleted(result)

        assertEquals(93_500L, values[GameStats.SURVIVED_MS])
        // Truncated, not rounded: 93.5 seconds is 93 whole seconds survived, and rounding up
        // would let a 59.6-second run satisfy the ">= 60" filter behind "Minute Runs".
        assertEquals(93L, values[GameStats.SURVIVED_SECONDS])
        assertEquals(41L, values[GameStats.FRUIT_HIT])
        assertEquals(3L, values[GameStats.FRUIT_MISSED])
        // The HUD's clamped, 1-based number, not the raw level — the card says 24, so does this.
        assertEquals(GameEngine.displaySpeed(23).toLong(), values[GameStats.TOP_SPEED])
        assertEquals(true, values[GameStats.RANKED])
    }
}
