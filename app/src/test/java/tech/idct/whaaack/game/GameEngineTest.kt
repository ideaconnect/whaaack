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

    /**
     * Ends the run the way a player does: the End-run control takes two presses, the first
     * only arming it. Tests that care about the guard itself press explicitly instead.
     */
    private fun GameEngine.endRun(nowNs: Long) {
        assertEquals(GameEngine.QuitPress.ARMED, requestQuit(nowNs))
        assertEquals(GameEngine.QuitPress.CONFIRMED, requestQuit(nowNs))
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

        engine.endRun(now)
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

        engine.endRun(now)
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

        engine.endRun(FRAME)
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
    fun `top speed is the level the board fills, and the readout clamps there`() {
        val top = GameEngine.TOP_SPEED_LEVEL
        assertTrue("the board should fill during a plausible run", top in 1..60)

        // TOP_SPEED_LEVEL now means "the last slot has opened", not "the pace stopped".
        assertEquals(GameEngine.MAX_TARGETS, GameEngine.targetsAtLevel(top))
        assertTrue(GameEngine.targetsAtLevel(top - 1) < GameEngine.MAX_TARGETS)

        // Everything the player *reads* still clamps, because there is nothing further to show.
        assertEquals(1f, GameEngine.speedFraction(top), 0.0001f)
        assertEquals(1f, GameEngine.speedFraction(top + 25), 0.0001f)
        assertEquals(0f, GameEngine.speedFraction(0), 0.0001f)
        assertEquals(top + 1, GameEngine.displaySpeed(top + 25))
        assertTrue(GameEngine.isTopSpeed(top))
        assertTrue(!GameEngine.isTopSpeed(top - 1))

        // And the score column it feeds stays inside the server-side plausibility check,
        // which rejects top_speed above 64.
        assertTrue("displaySpeed must stay under the scores.top_speed constraint",
            GameEngine.displaySpeed(100_000) <= 64)
    }

    @Test
    fun `the run never stops getting harder`() {
        // This is the whole point of the curve rewrite. It used to flatten completely at
        // level 10, so past forty seconds the score measured stamina rather than skill and
        // the all-time best would have been set by whoever kept holding the phone longest.
        var previousPressure = Double.MAX_VALUE
        // Asserted across the whole reachable range and then some. The board fills at
        // TOP_SPEED_LEVEL, and sixteen fruit sharing sixteen tiles ends any run long before
        // this loop does — so every level tested here is already past what anyone survives.
        for (level in 0..GameEngine.TOP_SPEED_LEVEL) {
            val targets = GameEngine.targetsAtLevel(level)
            val life = GameEngine.fruitLifeMs(level)
            val interval = GameEngine.spawnIntervalMs(level)
            // Fruit arriving per second: the rate the player actually has to keep up with.
            val pressure = targets * 1000.0 / (interval * 0.35 + life)
            assertTrue(
                "difficulty must never plateau — level $level matched level ${level - 1}",
                level == 0 || pressure > previousPressure,
            )
            previousPressure = pressure
        }

        // Past that point the pace tail does eventually flatten, because life and interval are
        // whole milliseconds and the geometric decay per level falls below 1ms somewhere past
        // level 100. That is not the old problem coming back: concurrency has been pinned at one
        // fruit per tile since TOP_SPEED_LEVEL, which is the thing that actually ends runs. It
        // is recorded here so the flattening is understood rather than rediscovered as a bug.
        assertEquals(GameEngine.MAX_TARGETS, GameEngine.targetsAtLevel(GameEngine.TOP_SPEED_LEVEL))
        assertTrue(GameEngine.fruitLifeMs(200) < GameEngine.fruitLifeMs(GameEngine.TOP_SPEED_LEVEL))
    }

    @Test
    fun `past the fourth slot, no level is a third harder than the one before`() {
        // A discrete new fruit is always a step, but it should read as a gear change
        // rather than a wall. The old ladder jumped +73% and then +57% four seconds
        // apart as the third and fourth slots arrived, which was the wall almost every
        // run died against.
        //
        // Measured from the fourth slot on. The two steps before it are deliberately
        // bigger in relative terms (+55% into the third slot, +38% into the fourth) —
        // (N+1)/N makes anything under +33% mathematically impossible there — but they
        // land while absolute pressure is still low, which is what makes them playable.
        var previous = 0.0
        for (level in GameEngine.FOURTH_TARGET_LEVEL..GameEngine.TOP_SPEED_LEVEL) {
            val pressure = GameEngine.targetsAtLevel(level) * 1000.0 /
                (GameEngine.spawnIntervalMs(level) * 0.35 + GameEngine.fruitLifeMs(level))
            if (level > GameEngine.FOURTH_TARGET_LEVEL) {
                val jump = pressure / previous
                val percent = ((jump - 1) * 100).toInt()
                assertTrue(
                    "level " + level + " is " + percent + "% harder than the one before it",
                    jump <= 1.30,
                )
            }
            previous = pressure
        }
    }

    @Test
    fun `the pace tightens for ever without ever degenerating`() {
        for (level in 1..500) {
            val life = GameEngine.fruitLifeMs(level)
            val interval = GameEngine.spawnIntervalMs(level)
            // Monotonically decreasing, never a step back up...
            assertTrue(
                "life rose at level $level",
                life <= GameEngine.fruitLifeMs(level - 1),
            )
            assertTrue(
                "interval rose at level $level",
                interval <= GameEngine.spawnIntervalMs(level - 1),
            )
            // ...but never into nonsense. A zero-millisecond fruit is not difficulty, it is a
            // broken game: it would expire on the frame it spawned, unhittable by anyone.
            assertTrue("fruit life collapsed at level $level", life >= 250)
            assertTrue("spawn interval collapsed at level $level", interval >= 100)
        }
    }

    @Test
    fun `the opening ramp is exactly as retuned`() {
        // Regression guard for the 2026-08 retune. The old opening spent twenty seconds
        // under 1.5 arrivals per second — half of a typical run, all filler — and then
        // doubled the pressure in eight seconds. The new one climbs a few percent per
        // level from the first frame, with the knee at level 10.
        val expectedLife = intArrayOf(1250, 1218, 1186, 1154, 1122, 1090, 1058, 1026, 994, 962, 930)
        val expectedInterval = intArrayOf(850, 820, 790, 760, 730, 700, 670, 640, 610, 580, 550)
        for (level in expectedLife.indices) {
            assertEquals("life at level $level", expectedLife[level], GameEngine.fruitLifeMs(level))
            assertEquals("interval at level $level", expectedInterval[level], GameEngine.spawnIntervalMs(level))
        }
    }

    @Test
    fun `the slot ladder climbs one rung at a time to a full board`() {
        // One slot step per stretch of plain pace-ramp, never two rungs back to back —
        // the old ladder's third and fourth slots arrived four seconds apart, and that
        // double step was the wall almost every run died against.
        assertEquals(2, GameEngine.targetsAtLevel(0))
        assertEquals(2, GameEngine.targetsAtLevel(3))
        assertEquals(3, GameEngine.targetsAtLevel(4))
        assertEquals(3, GameEngine.targetsAtLevel(9))
        assertEquals(4, GameEngine.targetsAtLevel(10))
        assertEquals(4, GameEngine.targetsAtLevel(15))
        assertEquals(5, GameEngine.targetsAtLevel(16))
        assertEquals(5, GameEngine.targetsAtLevel(19))
        assertEquals(6, GameEngine.targetsAtLevel(20))

        // The cap is physical: a seventeenth fruit has no tile to stand on.
        assertEquals(GameEngine.TILE_COUNT, GameEngine.MAX_TARGETS)
        assertEquals(GameEngine.MAX_TARGETS, GameEngine.targetsAtLevel(GameEngine.TOP_SPEED_LEVEL))
        assertEquals(GameEngine.MAX_TARGETS, GameEngine.targetsAtLevel(10_000))
        assertEquals(GameEngine.MAX_TARGETS, engine().slots.size)
    }

    @Test
    fun `pressure sweeps the human band instead of leaping across it`() {
        // The yardstick the curve was retuned against: sustained aimed tapping on a 4x4
        // phone grid runs from ~2.5/s (casual) to ~6/s (expert). Where a run ends should
        // be decided by where a player sits in that band, so the curve must move through
        // it slowly — not spend twenty seconds below it and then leap past it, which is
        // what compressed every death into the same sixteen-second window before.
        fun pressure(level: Int) = GameEngine.targetsAtLevel(level) * 1000.0 /
            (GameEngine.spawnIntervalMs(level) * 0.35 + GameEngine.fruitLifeMs(level))

        assertTrue("the opening should not be comatose", pressure(0) > 1.0)
        assertTrue("no wall inside the first half minute",
            (0..7).all { pressure(it) < 3.0 })
        assertTrue("a good player must still be alive past a minute", pressure(17) < 5.5)
        assertTrue("but nobody outlasts the ladder — terminal by three minutes",
            pressure(45) > 8.0)
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
    fun `no two fruit ever surface in the same instant`() {
        // Two fruit blinking in together at opposite corners is a strike no reaction can
        // prevent: one thumb cannot be in two places, and even two cannot launch at once.
        // Board-wide spawn spacing turns every pair into an order — this one, then that
        // one. Driven with the stalling player so the board stays as crowded as it can
        // get, across the slot openings where simultaneous spawns were most likely.
        val engine = engine()
        var now = engine.begin()

        var guard = 0
        while (engine.level <= GameEngine.FOURTH_TARGET_LEVEL + 2 &&
            engine.phase == GameEngine.Phase.RUNNING && guard++ < 20_000
        ) {
            now += FRAME
            engine.update(now)
            val spacing = GameEngine.spawnSpacingMs(engine.level)
            val born = engine.slots.filterNotNull().map { it.bornNs }.sorted()
            for (i in 1 until born.size) {
                assertTrue(
                    "two spawns ${(born[i] - born[i - 1]) / MS}ms apart",
                    born[i] - born[i - 1] >= spacing * MS,
                )
            }
            engine.tapExpiring(now)
        }
        assertEquals("the run should have survived the whole window", 0, engine.strikes)
        assertTrue(
            "expected to play through the four-slot band",
            engine.level > GameEngine.FOURTH_TARGET_LEVEL,
        )
    }

    @Test
    fun `the spawn gap yields to the ladder instead of capping it`() {
        // The trap in the spacing rule, found by simulating it rather than reading it: a gap
        // of g between arrivals means at most life/g fruit can ever be airborne together,
        // whatever the ladder asks for. At a flat 100ms the board topped out around twelve
        // fruit and arrivals around ten a second — an endgame cap, which is the plateau the
        // ladder exists to abolish, and it handed anyone who could sustain that rate a run
        // that never ended.
        for (level in 0..GameEngine.TOP_SPEED_LEVEL) {
            val spacing = GameEngine.spawnSpacingMs(level)
            val targets = GameEngine.targetsAtLevel(level)
            val life = GameEngine.fruitLifeMs(level)
            assertTrue("spacing collapsed to nothing at level $level", spacing >= 1L)
            assertTrue(
                "at level $level the ${spacing}ms gap caps the board at ${life / spacing} " +
                    "fruit, but the ladder asks for $targets",
                life / spacing >= targets,
            )
        }

        // And it is not paid for early: the full gap holds through the levels any real run
        // reaches, and only yields once the board is meant to be drowning the player.
        for (level in 0..27) {
            assertEquals(
                "the gap should still be intact at level $level",
                GameEngine.SPAWN_SPACING_MS,
                GameEngine.spawnSpacingMs(level),
            )
        }
        assertTrue(
            "the gap should taper once the ladder needs the room",
            GameEngine.spawnSpacingMs(GameEngine.TOP_SPEED_LEVEL) < GameEngine.SPAWN_SPACING_MS,
        )
    }

    @Test
    fun `one lapse costs one strike, never a cascade`() {
        // Fruits that spawn near each other expire near each other, so the moment that
        // took strike one was exactly the moment strikes two and three were due — a run
        // could end in a blink the player experienced as a single mistake. The grace
        // spreads them: every strike buys the rest of the board a beat, so a loss is
        // three readable events at least STRIKE_GRACE_MS apart.
        val engine = engine()
        val strikeTimes = mutableListOf<Long>()
        var now = engine.begin()
        engine.listener = object : GameEngine.Listener {
            override fun onHit(fruit: Fruit) = Unit
            override fun onStrike(strikes: Int) {
                strikeTimes += now
            }
            override fun onGameOver(result: GameEngine.Result) = Unit
        }

        // Never tap: every fruit escapes, exactly the overwhelmed endgame.
        var guard = 0
        while (engine.strikes < GameEngine.MAX_STRIKES && guard++ < 10_000) {
            now += FRAME
            engine.update(now)
        }

        assertEquals(GameEngine.MAX_STRIKES, strikeTimes.size)
        for (i in 1 until strikeTimes.size) {
            val gapMs = (strikeTimes[i] - strikeTimes[i - 1]) / MS
            assertTrue(
                "strikes ${gapMs}ms apart — the run can still end in a blink",
                gapMs >= GameEngine.STRIKE_GRACE_MS,
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
    fun `a saturated board never stacks two fruit on one tile`() {
        // The endgame the old curve could never reach. Once the ladder asks for one fruit per
        // tile, "pick a free tile" has no slack left in it — and the previous implementation
        // fell back to placing a second fruit on an occupied tile when the search ran dry,
        // which makes one of them unwhackable and costs a strike nothing could prevent.
        val engine = engine()
        var now = engine.begin()

        // A perfect player: tap everything on the board every frame. Nothing ever expires, so
        // strikes stay at zero and the clock can be driven all the way to a full board.
        var guard = 0
        while (engine.level < GameEngine.TOP_SPEED_LEVEL && guard++ < 40_000) {
            now += FRAME
            engine.update(now)
            for (fruit in engine.slots) fruit?.let { engine.postTap(it.tile) }
        }
        assertEquals("perfect play must not accrue strikes", 0, engine.strikes)
        assertEquals(GameEngine.Phase.RUNNING, engine.phase)
        assertEquals(GameEngine.MAX_TARGETS, GameEngine.targetsAtLevel(engine.level))

        // Now stop tapping and let it fill up. The run will end on three strikes shortly, but
        // the board saturates first — that window is the thing under test.
        var mostSeen = 0
        guard = 0
        while (engine.phase == GameEngine.Phase.RUNNING && guard++ < 2_000) {
            now += FRAME
            engine.update(now)
            val tiles = engine.slots.filterNotNull().map { it.tile }
            mostSeen = maxOf(mostSeen, tiles.size)
            assertEquals("fruit stacked on one tile: $tiles", tiles.size, tiles.distinct().size)
            assertTrue("more fruit than tiles: ${tiles.size}", tiles.size <= GameEngine.TILE_COUNT)
        }
        assertTrue(
            "expected the board to crowd up; only ever saw $mostSeen fruit at once",
            mostSeen >= GameEngine.TILE_COUNT / 2,
        )
    }

    // ---- the End-run guard ---------------------------------------------------------------

    @Test
    fun `one press of End run does not end the run`() {
        val engine = engine()
        var now = engine.begin()
        now += 2_000 * MS
        engine.update(now)

        // The whole point. Fourteen dp below a board the player is hammering sits a control
        // that used to end the run on first touch.
        assertEquals(GameEngine.QuitPress.ARMED, engine.requestQuit(now))
        engine.update(now)
        assertEquals(GameEngine.Phase.RUNNING, engine.phase)
        assertTrue("nothing should have been reported", results.isEmpty())
        assertTrue("the control should show as armed", engine.quitArmedUntilNs > now)
    }

    @Test
    fun `a second press inside the window ends it`() {
        val engine = engine()
        var now = engine.begin()
        now += 2_000 * MS
        engine.update(now)

        engine.requestQuit(now)
        now += 400 * MS
        assertEquals(GameEngine.QuitPress.CONFIRMED, engine.requestQuit(now))
        engine.update(now)

        assertEquals(GameEngine.Phase.OVER, engine.phase)
        assertTrue(results.single().quit)
    }

    @Test
    fun `the arm lapses, so a stray press cannot be completed much later`() {
        val engine = engine()
        var now = engine.begin()
        now += 2_000 * MS
        engine.update(now)

        engine.requestQuit(now)
        now += (GameEngine.QUIT_ARM_MS + 50) * MS
        engine.update(now)
        // Past the window the next press is a fresh arm, not a confirmation.
        assertEquals(GameEngine.QuitPress.ARMED, engine.requestQuit(now))
        engine.update(now)
        assertEquals(GameEngine.Phase.RUNNING, engine.phase)
    }

    @Test
    fun `whacking a fruit disarms it`() {
        val engine = engine()
        var now = engine.begin()
        now += 500 * MS
        engine.update(now)

        engine.requestQuit(now)
        assertTrue(engine.quitArmedUntilNs > now)

        // This is what makes the guard strong rather than merely present: a player who
        // clipped the pill by accident is back on the board within milliseconds, and that
        // clears the arm — so ending a run by accident needs two stray presses AND no fruit
        // tapped in between.
        val fruit = engine.slots.filterNotNull().first()
        engine.postTap(fruit.tile)
        assertEquals(0L, engine.quitArmedUntilNs)

        now += 100 * MS
        assertEquals(GameEngine.QuitPress.ARMED, engine.requestQuit(now))
        engine.update(now)
        assertEquals(GameEngine.Phase.RUNNING, engine.phase)
    }

    @Test
    fun `being interrupted disarms it`() {
        val engine = engine()
        var now = engine.begin()
        now += 1_000 * MS
        engine.update(now)

        engine.requestQuit(now)
        engine.pause(now)
        assertEquals("coming back to a half-armed quit button is not a thing", 0L, engine.quitArmedUntilNs)
    }

    @Test
    fun `a press outside a run is ignored`() {
        val engine = engine()
        assertEquals(GameEngine.QuitPress.IGNORED, engine.requestQuit(0L))
        assertEquals(0L, engine.quitArmedUntilNs)
    }

    @Test
    fun `starting a run clears the previous one`() {
        val engine = engine()
        var now = engine.begin()
        now += 2_000 * MS
        engine.update(now)
        engine.endRun(now)
        engine.update(now)

        engine.start(ranked = false, nowNs = now)
        assertEquals(GameEngine.Phase.COUNTDOWN, engine.phase)
        assertEquals(0L, engine.elapsedMs)
        assertEquals(0, engine.strikes)
        assertEquals(0, engine.hits)
        assertTrue(engine.splats.isEmpty())
        assertTrue(engine.slots.all { it == null })
    }

    // ---- banking an interrupted run ------------------------------------------------------

    @Test
    fun `an idle engine has nothing to bank`() {
        assertEquals(0L, engine().survivedMillisIfLive())
    }

    @Test
    fun `a live run reports what it has survived so far`() {
        val engine = engine()
        var now = engine.begin()
        now += 5_000 * MS
        engine.update(now)

        assertEquals(engine.elapsedMs, engine.survivedMillisIfLive())
        assertTrue("a live run must have something to bank", engine.survivedMillisIfLive() > 0L)
    }

    @Test
    fun `a run interrupted during the opening countdown banks nothing`() {
        val engine = engine()
        engine.start(ranked = true, nowNs = 0L)
        assertEquals(GameEngine.Phase.COUNTDOWN, engine.phase)
        // Nothing has been survived yet, so there is no score to rescue from a process kill.
        assertEquals(0L, engine.survivedMillisIfLive())
    }

    @Test
    fun `a finished run banks nothing — onGameOver already delivered the score`() {
        val engine = engine()
        var now = engine.begin()
        now += 3_000 * MS
        engine.update(now)
        engine.endRun(now)
        engine.update(now)
        // Quitting runs the outro out before the phase settles on OVER.
        now += GameEngine.OUTRO_MS * MS + FRAME
        engine.update(now)

        assertEquals(GameEngine.Phase.OVER, engine.phase)
        assertEquals(
            "a run that already reported its result must not be banked a second time",
            0L,
            engine.survivedMillisIfLive(),
        )
    }

    @Test
    fun `banking is unaffected by the clock being paused`() {
        val engine = engine()
        var now = engine.begin()
        now += 2_000 * MS
        engine.update(now)
        val live = engine.survivedMillisIfLive()

        // This is the real sequence: the render thread stops, the clock is suspended, and
        // only then does the score get banked.
        engine.pause(now)
        assertEquals(live, engine.survivedMillisIfLive())
        assertTrue(live > 0L)
    }
}
