package tech.idct.whaaack.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [GameEngine] holds no Android types, so its rules can be driven directly on the JVM with
 * a hand-cranked clock — which is the only way to test the things that actually broke:
 * behaviour that depends on real elapsed time.
 */
class GameEngineTest {

    private companion object {
        const val MS = 1_000_000L
        const val COUNTDOWN = GameEngine.COUNTDOWN_MS * MS
        const val FRAME = 16 * MS
    }

    private var results = mutableListOf<GameEngine.Result>()

    private fun engine(): GameEngine = GameEngine(Random(1)).apply {
        listener = object : GameEngine.Listener {
            override fun onHit(fruit: Fruit) = Unit
            override fun onStrike(strikes: Int) = Unit
            override fun onGameOver(result: GameEngine.Result) {
                results += result
            }
        }
    }

    /** Runs the countdown out and returns the clock at the first live frame. */
    private fun GameEngine.begin(): Long {
        start(ranked = true, nowNs = 0L)
        update(COUNTDOWN)
        update(COUNTDOWN + FRAME)
        assertEquals(GameEngine.Phase.RUNNING, phase)
        return COUNTDOWN + FRAME
    }

    // ---- the clock ---------------------------------------------------------------------

    @Test
    fun `time spent paused is not counted as time survived`() {
        val engine = engine()
        var now = engine.begin()

        now += 1_000 * MS
        engine.update(now)
        assertEquals(1_000L + 16L, engine.elapsedMs)

        // Away for an hour, which is what backgrounding the app looks like to the engine.
        engine.pause(now)
        now += 3_600_000L * MS
        engine.resume(now)
        engine.update(now)

        assertEquals(
            "the hour away must not be added to the score",
            1_000L + 16L,
            engine.elapsedMs,
        )
    }

    @Test
    fun `fruit alive at pause is still alive at resume`() {
        val engine = engine()
        var now = engine.begin()

        now += 500 * MS
        engine.update(now)
        val airborne = engine.slots.filterNotNull()
        assertTrue("expected a fruit on the board", airborne.isNotEmpty())
        val strikesBefore = engine.strikes

        engine.pause(now)
        now += 3_600_000L * MS
        engine.resume(now)
        engine.update(now)

        assertEquals(
            "fruit must not expire while the game is not being played",
            strikesBefore,
            engine.strikes,
        )
        assertEquals(airborne.size, engine.slots.filterNotNull().size)
    }

    @Test
    fun `an interrupted run comes back through a countdown, not mid-air`() {
        val engine = engine()
        var now = engine.begin()

        // Let a fruit get most of the way through its life, then leave.
        now += 1_200 * MS
        engine.update(now)
        assertTrue("expected a fruit on the board", engine.slots.any { it != null })
        val elapsedAtPause = engine.elapsedMs

        engine.pause(now)
        now += 30_000L * MS
        engine.resume(now)
        engine.update(now)

        // Handing the board straight back would cost a strike the player could not have
        // prevented, so the run is held in a countdown first.
        assertEquals(GameEngine.Phase.COUNTDOWN, engine.phase)
        assertEquals(0, engine.strikes)

        // The clock does not move while the countdown runs.
        now += GameEngine.RESUME_COUNTDOWN_MS / 2 * MS
        engine.update(now)
        assertEquals(GameEngine.Phase.COUNTDOWN, engine.phase)
        assertEquals(elapsedAtPause, engine.elapsedMs)

        now += (GameEngine.RESUME_COUNTDOWN_MS / 2 + 16) * MS
        engine.update(now)
        assertEquals(GameEngine.Phase.RUNNING, engine.phase)

        // The run picks up from where it was left: neither the thirty seconds away nor the
        // two seconds of countdown are time the player survived.
        now += FRAME
        engine.update(now)
        assertTrue(
            "elapsed ran to ${engine.elapsedMs} from $elapsedAtPause",
            engine.elapsedMs in elapsedAtPause..(elapsedAtPause + 100L),
        )
        assertEquals("nothing may expire while the player was away", 0, engine.strikes)
    }

    @Test
    fun `quitting during the resume countdown keeps the score`() {
        val engine = engine()
        var now = engine.begin()
        now += 2_000 * MS
        engine.update(now)
        val elapsedAtPause = engine.elapsedMs

        engine.pause(now)
        now += 10_000L * MS
        engine.resume(now)
        engine.update(now)
        assertEquals(GameEngine.Phase.COUNTDOWN, engine.phase)

        engine.requestQuit()
        engine.update(now)

        val result = results.single()
        assertTrue(result.quit)
        assertEquals(
            "a run paused mid-flight still has everything it survived",
            elapsedAtPause,
            result.millisSurvived,
        )
    }

    @Test
    fun `taps outside a live run are not banked`() {
        val engine = engine()
        engine.start(ranked = true, nowNs = 0L)
        engine.update(FRAME)
        assertEquals(GameEngine.Phase.COUNTDOWN, engine.phase)

        // Mashing every tile through the countdown must not buy a board-clearing burst on
        // the first live frame.
        repeat(4) {
            for (tile in 0 until GameEngine.TILE_COUNT) engine.postTap(tile)
        }

        var now = COUNTDOWN + FRAME
        engine.update(now)
        assertEquals(GameEngine.Phase.RUNNING, engine.phase)
        now += FRAME
        engine.update(now)

        assertEquals("countdown taps must not score", 0, engine.hits)
    }

    @Test
    fun `pausing outside a run does nothing`() {
        val engine = engine()
        engine.pause(0L)
        engine.resume(10_000L * MS)
        assertEquals(GameEngine.Phase.IDLE, engine.phase)

        var now = engine.begin()
        // resume without a matching pause must not shift the clock
        engine.resume(now + 10_000L * MS)
        now += 100 * MS
        engine.update(now)
        assertEquals(100L + 16L, engine.elapsedMs)
    }

    // ---- ending a run ------------------------------------------------------------------

    @Test
    fun `quitting is reported as a quit and keeps the score`() {
        val engine = engine()
        var now = engine.begin()
        now += 2_000 * MS
        engine.update(now)

        engine.requestQuit()
        engine.update(now)

        assertEquals(GameEngine.Phase.OVER, engine.phase)
        val result = results.singleOrNull()
        assertNotNull("quitting should report a result", result)
        assertTrue("a quit must not read as a loss", result!!.quit)
        // Some fruit will have escaped by now — the point is that the run ended because the
        // player asked it to, not because they ran out of strikes.
        assertTrue(engine.strikes < GameEngine.MAX_STRIKES)
        assertEquals(2_000L + 16L, result.millisSurvived)
    }

    @Test
    fun `losing three strikes is not reported as a quit`() {
        val engine = engine()
        var now = engine.begin()

        // Never tap: every fruit escapes, and three escapes end the run.
        var guard = 0
        while (results.isEmpty() && guard++ < 10_000) {
            now += FRAME
            engine.update(now)
        }

        val result = results.singleOrNull()
        assertNotNull("the run should have ended on strikes", result)
        assertTrue("a loss must not read as a quit", !result!!.quit)
        assertEquals(GameEngine.MAX_STRIKES, engine.strikes)
    }

    @Test
    fun `quitting during the countdown scores nothing`() {
        val engine = engine()
        engine.start(ranked = true, nowNs = 0L)
        engine.update(FRAME)
        assertEquals(GameEngine.Phase.COUNTDOWN, engine.phase)

        engine.requestQuit()
        engine.update(2 * FRAME)

        val result = results.single()
        assertTrue(result.quit)
        assertEquals(0L, result.millisSurvived)
    }

    // ---- splats ------------------------------------------------------------------------

    @Test
    fun `splats are cleared once they expire`() {
        val engine = engine()
        var now = engine.begin()

        // Whack whatever is up, twice, so more than one splat is in flight at once.
        repeat(2) {
            now += FRAME
            engine.update(now)
            engine.slots.filterNotNull().forEach { engine.postTap(it.tile) }
            now += FRAME
            engine.update(now)
        }
        assertTrue("expected splats on the board", engine.splats.isNotEmpty())

        now += (GameEngine.SPLAT_LIFE_MS + 100) * MS
        engine.update(now)
        assertTrue("every splat should have expired", engine.splats.isEmpty())
    }

    @Test
    fun `a whacked fruit scores a hit and leaves the board`() {
        val engine = engine()
        var now = engine.begin()
        now += FRAME
        engine.update(now)

        val target = engine.slots.filterNotNull().first()
        engine.postTap(target.tile)
        now += FRAME
        engine.update(now)

        assertEquals(1, engine.hits)
        assertEquals(0, engine.strikes)
        assertTrue(engine.slots.none { it?.tile == target.tile })
    }

    // ---- the difficulty curve ----------------------------------------------------------

    @Test
    fun `difficulty and the speed readout stop at the same level`() {
        val top = GameEngine.TOP_SPEED_LEVEL
        assertTrue("top speed should be reached during a plausible run", top in 1..60)

        // TOP_SPEED_LEVEL is meant to be the exact point both tracks bottom out.
        assertTrue(
            GameEngine.spawnIntervalMs(top - 1) > GameEngine.spawnIntervalMs(top) ||
                GameEngine.fruitLifeMs(top - 1) > GameEngine.fruitLifeMs(top),
        )
        assertEquals(GameEngine.spawnIntervalMs(top), GameEngine.spawnIntervalMs(top + 25))
        assertEquals(GameEngine.fruitLifeMs(top), GameEngine.fruitLifeMs(top + 25))

        assertEquals(1f, GameEngine.speedFraction(top), 0.0001f)
        assertEquals(1f, GameEngine.speedFraction(top + 25), 0.0001f)
        assertEquals(0f, GameEngine.speedFraction(0), 0.0001f)

        assertEquals(top + 1, GameEngine.displaySpeed(top + 25))
        assertTrue(GameEngine.isTopSpeed(top))
        assertTrue(!GameEngine.isTopSpeed(top - 1))
    }

    /**
     * Taps only what is nearly out of life, so fruit occupy the board for as long as they
     * would for a real player. Whacking each one the instant it appears survives the run but
     * keeps the slots from ever overlapping — which is the very thing these two tests watch
     * for — and leaves the board too empty to stress tile allocation.
     */
    private fun GameEngine.tapExpiring(nowNs: Long) {
        for (fruit in slots) {
            fruit ?: continue
            if ((nowNs - fruit.bornNs) / MS >= fruit.lifeMs - 3 * 16) postTap(fruit.tile)
        }
    }

    @Test
    fun `each further slot joins the board at its own level`() {
        val engine = engine()
        var now = engine.begin()
        val last = GameEngine.FOURTH_TARGET_LEVEL

        val mostAtLevel = mutableMapOf<Int, Int>()
        var guard = 0
        while (engine.level <= last + 2 && engine.phase == GameEngine.Phase.RUNNING && guard++ < 20_000) {
            now += FRAME
            engine.update(now)
            val level = engine.level
            val airborne = engine.slots.count { it != null }
            mostAtLevel[level] = maxOf(mostAtLevel[level] ?: 0, airborne)
            engine.tapExpiring(now)
        }

        assertEquals("the run should have survived the whole ladder", 0, engine.strikes)
        // The level the loop stopped inside was only partly played, so it had no fair chance
        // to fill every slot.
        val partial = mostAtLevel.keys.max()
        assertTrue("expected to climb past level $last", partial > last)
        for ((level, most) in mostAtLevel) {
            if (level == partial) continue
            assertEquals(
                "fruit on the board at level $level",
                GameEngine.targetsAtLevel(level),
                most,
            )
        }
    }

    @Test
    fun `two fruit never share a tile`() {
        val engine = engine()
        var now = engine.begin()

        var guard = 0
        while (engine.level <= GameEngine.FOURTH_TARGET_LEVEL + 5 &&
            engine.phase == GameEngine.Phase.RUNNING && guard++ < 20_000
        ) {
            now += FRAME
            engine.update(now)
            val tiles = engine.slots.filterNotNull().map { it.tile }
            // A doubled-up tile would make one of them unwhackable: a tap clears a single
            // slot, so the other is left to expire into a strike nothing could have stopped.
            assertEquals("fruit stacked on one tile: $tiles", tiles.size, tiles.distinct().size)
            engine.tapExpiring(now)
        }
    }

    @Test
    fun `starting a run clears the previous one`() {
        val engine = engine()
        var now = engine.begin()
        now += 2_000 * MS
        engine.update(now)
        engine.requestQuit()
        engine.update(now)

        engine.start(ranked = false, nowNs = now)
        assertEquals(GameEngine.Phase.COUNTDOWN, engine.phase)
        assertEquals(0L, engine.elapsedMs)
        assertEquals(0, engine.strikes)
        assertEquals(0, engine.hits)
        assertTrue(engine.splats.isEmpty())
        assertTrue(engine.slots.all { it == null })
    }
}
