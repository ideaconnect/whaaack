package tech.idct.whaaack.game

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure game simulation. Holds no Android types and never touches a Canvas, so it can be
 * stepped from the render thread without contending with the main thread.
 *
 * Rules, per the brief:
 *  - two fruit slots, each cycling independently so they can surface together or slightly
 *    offset; a third and then a fourth open as the run climbs, on the same terms;
 *  - a fruit that is not whacked before its life expires costs a strike;
 *  - three strikes end the run;
 *  - the score is the number of milliseconds survived;
 *  - fruits arrive faster and live shorter the longer the run goes on.
 */
class GameEngine(private val random: Random = Random.Default) {

    enum class Phase { IDLE, COUNTDOWN, RUNNING, OUTRO, OVER }

    class ActiveFruit(
        @JvmField var tile: Int,
        @JvmField var fruit: Fruit,
        @JvmField var bornNs: Long,
        @JvmField var lifeMs: Int,
    )

    class Splat(
        @JvmField var tile: Int,
        @JvmField var fruit: Fruit,
        @JvmField var variant: Int,
        @JvmField var rotationDeg: Float,
        @JvmField var bornNs: Long,
    )

    /** Callbacks fire on the render thread; implementations must be thread-safe. */
    interface Listener {
        fun onHit(fruit: Fruit)
        fun onStrike(strikes: Int)
        fun onGameOver(result: Result)
    }

    data class Result(
        val millisSurvived: Long,
        val hits: Int,
        val topSpeedLevel: Int,
        val ranked: Boolean,
        /** True when the player ended the run themselves rather than losing it. */
        val quit: Boolean,
    )

    var listener: Listener? = null

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** Written on the render thread, read by the UI for the score readout. */
    @Volatile
    var elapsedMs: Long = 0L
        private set

    @Volatile
    var strikes: Int = 0
        private set

    @Volatile
    var hits: Int = 0
        private set

    @Volatile
    var level: Int = 0
        private set

    @Volatile
    var ranked: Boolean = false
        private set

    val slots = arrayOfNulls<ActiveFruit>(MAX_TARGETS)
    val splats = ArrayList<Splat>(8)

    /** Nanotime the most recent strike landed, so the renderer can flash the background. */
    @Volatile
    var lastStrikeNs: Long = 0L
        private set

    @Volatile
    var outroStartedNs: Long = 0L
        private set

    @Volatile
    var countdownValue: Int = 0
        private set

    /**
     * Nanotime the End-run control disarms itself, or 0 when it is not armed.
     *
     * Read by the renderer every frame so the pill can show that it is waiting for a second
     * press, and how long it will keep waiting.
     */
    @Volatile
    var quitArmedUntilNs: Long = 0L
        private set

    private var startNs = 0L
    private var countdownEndsNs = 0L
    private val nextSpawnNs = LongArray(MAX_TARGETS)
    private val pendingTaps = ConcurrentLinkedQueue<Int>()
    private val quitRequested = AtomicBoolean(false)
    private var result: Result? = null

    /** Nanotime the clock was suspended at, or 0 when the run is live. */
    private var pausedAtNs = 0L

    /**
     * True while [Phase.COUNTDOWN] is handing an interrupted run back rather than opening a
     * new one, so the countdown ends by resuming instead of by starting from zero.
     */
    private var resumingRun = false

    /** Slots cycling right now; grows as the run passes each new target's level. */
    private var openTargets = BASE_TARGETS

    fun start(ranked: Boolean, nowNs: Long) {
        this.ranked = ranked
        phase = Phase.COUNTDOWN
        countdownEndsNs = nowNs + COUNTDOWN_MS * NS_PER_MS
        countdownValue = COUNTDOWN_MS / 1000
        elapsedMs = 0
        strikes = 0
        hits = 0
        level = 0
        lastStrikeNs = 0
        outroStartedNs = 0
        result = null
        slots.fill(null)
        splats.clear()
        pendingTaps.clear()
        quitRequested.set(false)
        pausedAtNs = 0L
        resumingRun = false
        openTargets = BASE_TARGETS
        quitArmedUntilNs = 0L
    }

    /**
     * Called from the UI thread when the player taps tile [tile].
     *
     * Only a live run accepts one. [drainTaps] runs from [stepRun] and nowhere else, so a tap
     * made in any other phase has no frame that would ever consume it: those made during a
     * countdown were banked and applied in one burst on the first live frame, and those made
     * during the outro or after the run ended simply accumulated in the queue.
     */
    fun postTap(tile: Int) {
        if (phase != Phase.RUNNING) return
        if (tile !in 0 until TILE_COUNT) return
        // Going back to hitting fruit disarms the End-run control. This is what makes the
        // two-press guard actually strong: an accidental press is cleared by the very next
        // tap the player makes, and at speed that is milliseconds away — so "two stray
        // presses inside the arming window" additionally requires the player to have hit
        // nothing at all in between. It costs the deliberate path nothing, because someone
        // quitting on purpose is not whacking fruit between the two presses.
        quitArmedUntilNs = 0L
        pendingTaps.add(tile)
    }

    /**
     * The "End run" control. **Two presses**, not one.
     *
     * The board is deliberately forgiving — [postTap] takes a second thumb, and the renderer
     * maps every point inside the board card to the nearest tile so a near-miss is never
     * stolen. Fourteen density-independent pixels below that sits a control that used to end
     * the run instantly, on the first touch, with no way back. At the late levels the player
     * is tapping several times a second across sixteen tiles including the bottom row, an
     * inch above it. One fumble was the worst possible way to lose a record run.
     *
     * So the first press only *arms* it, for [QUIT_ARM_MS]; a second press inside that window
     * confirms. It disarms itself when the window passes, and immediately if the player taps
     * any tile (see [postTap]).
     *
     * Only a flag is set here: the run is torn down on the render thread inside [update],
     * because a caller on the UI thread cannot safely rewrite `slots` and `phase` while a
     * frame is being simulated.
     */
    fun requestQuit(nowNs: Long): QuitPress {
        if (phase != Phase.RUNNING && phase != Phase.COUNTDOWN) return QuitPress.IGNORED

        val armedUntil = quitArmedUntilNs
        if (armedUntil != 0L && nowNs < armedUntil) {
            quitArmedUntilNs = 0L
            quitRequested.set(true)
            return QuitPress.CONFIRMED
        }
        quitArmedUntilNs = nowNs + QUIT_ARM_MS * NS_PER_MS
        return QuitPress.ARMED
    }

    /** What a press of the End-run control did, so the caller can sound it appropriately. */
    enum class QuitPress { ARMED, CONFIRMED, IGNORED }

    /**
     * Suspends the clock. Every deadline the run holds is expressed as an absolute nanotime,
     * so without this the wall clock keeps running while the app is backgrounded: the player
     * would come back to a score inflated by however long they were away and to fruit that
     * expired in their absence.
     *
     * [pause] and [resume] deliberately do no locking. Two callers are safe for two different
     * reasons: [GameSurfaceView.stopRendering] and `surfaceCreated` call them with the render
     * thread stopped, and the render loop itself calls them (when the window becomes too
     * small to hold a board) from the very thread that owns this state. Both are idempotent —
     * each returns immediately if the clock is already in the requested state.
     */
    fun pause(nowNs: Long) {
        if (pausedAtNs != 0L) return
        if (phase != Phase.COUNTDOWN && phase != Phase.RUNNING && phase != Phase.OUTRO) return
        pausedAtNs = nowNs
        // Whatever the player was doing, they are not doing it now. The arm deadline is an
        // absolute nanotime and is deliberately not shifted by resume(), so coming back to a
        // half-armed quit button is not a thing that can happen.
        quitArmedUntilNs = 0L
    }

    /**
     * Milliseconds survived so far if a run is genuinely in progress, otherwise zero.
     *
     * For banking a score that may never reach [Listener.onGameOver]: a backgrounded process
     * can be killed at any moment, and the run then disappears along with it. [Phase.OUTRO]
     * counts — the run is over and the score is final, it is only the animation that has not
     * finished. [Phase.OVER] does not, because `onGameOver` has already been delivered.
     */
    fun survivedMillisIfLive(): Long = when (phase) {
        Phase.RUNNING, Phase.COUNTDOWN, Phase.OUTRO -> elapsedMs
        Phase.IDLE, Phase.OVER -> 0L
    }

    /**
     * Resumes the clock, shifting every deadline forward by the time spent paused.
     *
     * A run that was live when it was suspended does not simply carry on: whatever was on the
     * board when the player left is by then most of the way through its life, so handing
     * control straight back means the first thing they get on return is a strike they had no
     * chance to prevent. Instead the run re-enters [Phase.COUNTDOWN] for [RESUME_COUNTDOWN_MS]
     * and that countdown is folded into the shift, so the clock, the fruit and the spawn
     * schedule all come back exactly where they were left.
     */
    fun resume(nowNs: Long) {
        val pausedAt = pausedAtNs
        if (pausedAt == 0L) return
        pausedAtNs = 0L
        val interrupted = phase == Phase.RUNNING
        var gap = (nowNs - pausedAt).coerceAtLeast(0L)
        if (interrupted) gap += RESUME_COUNTDOWN_MS * NS_PER_MS
        if (gap <= 0L) return

        startNs += gap
        countdownEndsNs += gap
        for (i in nextSpawnNs.indices) nextSpawnNs[i] += gap
        for (active in slots) active?.let { it.bornNs += gap }
        for (splat in splats) splat.bornNs += gap
        // Zero means "never happened", so those two must not be shifted into meaning something.
        if (lastStrikeNs != 0L) lastStrikeNs += gap
        if (outroStartedNs != 0L) outroStartedNs += gap

        if (interrupted) {
            phase = Phase.COUNTDOWN
            resumingRun = true
            countdownEndsNs = nowNs + RESUME_COUNTDOWN_MS * NS_PER_MS
            countdownValue = RESUME_COUNTDOWN_MS / 1000
        }
    }

    fun update(nowNs: Long) {
        if (pausedAtNs != 0L) return
        if (quitRequested.getAndSet(false) &&
            (phase == Phase.RUNNING || phase == Phase.COUNTDOWN)
        ) {
            finish(nowNs, quit = true)
            return
        }
        when (phase) {
            Phase.COUNTDOWN -> {
                val remainingMs = (countdownEndsNs - nowNs) / NS_PER_MS
                countdownValue = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(0)
                if (nowNs >= countdownEndsNs) {
                    // Belt and braces against a tap that raced the phase flip: anything the
                    // player pressed while the board was frozen belongs to the countdown, not
                    // to the frame the run comes back on.
                    pendingTaps.clear()
                    if (resumingRun) {
                        resumingRun = false
                        phase = Phase.RUNNING
                    } else {
                        beginRun(nowNs)
                    }
                }
            }

            Phase.RUNNING -> stepRun(nowNs)

            Phase.OUTRO -> {
                if (nowNs - outroStartedNs >= OUTRO_MS * NS_PER_MS) {
                    phase = Phase.OVER
                    // Only now is the run truly finished. Announcing it earlier would let
                    // the UI swap screens — and tear the surface down — while the render
                    // thread is still animating the burst.
                    result?.let { listener?.onGameOver(it) }
                }
            }

            Phase.IDLE, Phase.OVER -> Unit
        }
        expireSplats(nowNs)
    }

    private fun beginRun(nowNs: Long) {
        phase = Phase.RUNNING
        startNs = nowNs
        // Slot 0 pops immediately; slot 1 lands half an interval later so the pair reads as
        // two independent fruits rather than one synchronised blink.
        nextSpawnNs[0] = nowNs
        nextSpawnNs[1] = nowNs + (spawnIntervalMs(0) / 2) * NS_PER_MS
    }

    private fun stepRun(nowNs: Long) {
        elapsedMs = (nowNs - startNs) / NS_PER_MS
        level = (elapsedMs / LEVEL_STEP_MS).toInt()

        drainTaps(nowNs)

        val lifeMs = fruitLifeMs(level)
        var struck = false

        while (openTargets < targetsAtLevel(level)) {
            // A newly opened slot gets the same delayed, jittered entry every other slot gets
            // once it is cleared, so it reads as one more independent arrival rather than the
            // set suddenly blinking in unison. Left unscheduled until now because a zeroed
            // deadline is already in the past: the slot would fire the instant it opened.
            scheduleRespawn(openTargets, nowNs)
            openTargets++
        }

        for (i in 0 until openTargets) {
            val active = slots[i]
            if (active != null) {
                if ((nowNs - active.bornNs) / NS_PER_MS >= active.lifeMs) {
                    // Escaped: that is a strike.
                    slots[i] = null
                    strikes++
                    struck = true
                    lastStrikeNs = nowNs
                    listener?.onStrike(strikes)
                    scheduleRespawn(i, nowNs)
                }
            } else if (nowNs >= nextSpawnNs[i]) {
                // A full board is not an error and not a strike — the slot just waits. Only
                // reschedule on success, so a blocked slot retries promptly rather than
                // sitting out a whole interval it never got to use.
                spawn(i, nowNs, lifeMs)
            }
        }

        if (struck && strikes >= MAX_STRIKES) {
            finish(nowNs, quit = false)
        }
    }

    private fun drainTaps(nowNs: Long) {
        while (true) {
            val tile = pendingTaps.poll() ?: break
            for (i in 0 until MAX_TARGETS) {
                val active = slots[i] ?: continue
                if (active.tile != tile) continue
                slots[i] = null
                hits++
                splats.add(
                    Splat(
                        tile = tile,
                        fruit = active.fruit,
                        variant = random.nextInt(Fruit.SPLAT_VARIANTS),
                        rotationDeg = random.nextFloat() * 360f,
                        bornNs = nowNs,
                    )
                )
                listener?.onHit(active.fruit)
                scheduleRespawn(i, nowNs)
                break
            }
        }
    }

    /** True when a fruit already occupies [tile]. */
    private fun tileHasFruit(tile: Int): Boolean {
        for (other in slots) if (other != null && other.tile == tile) return true
        return false
    }

    /** True when a fruit already occupies [tile], or a splat is still fading on it. */
    private fun tileBusy(tile: Int): Boolean {
        if (tileHasFruit(tile)) return true
        for (i in splats.indices) if (splats[i].tile == tile) return true
        return false
    }

    /**
     * First tile after [from] that is free, preferring one with no splat on it but settling
     * for a splat-covered tile over a fruit-covered one.
     */
    private fun firstFreeTile(from: Int): Int? {
        var splatOnly = -1
        for (offset in 1..TILE_COUNT) {
            val candidate = (from + offset) % TILE_COUNT
            // A tile still holding fruit is never a candidate, at any price. A splat is
            // cosmetic and can be drawn under a new fruit; a second fruit cannot.
            if (tileHasFruit(candidate)) continue
            if (!tileBusy(candidate)) return candidate
            if (splatOnly < 0) splatOnly = candidate
        }
        return if (splatOnly >= 0) splatOnly else null
    }

    /**
     * Places a fruit, or reports that the board had nowhere to put one.
     *
     * Returning false rather than forcing a placement is what makes a full board safe. Two
     * fruit on one tile is not a cosmetic problem: one tap clears only one of them, and the
     * other is left to expire into a strike the player could not have prevented. That used to
     * be a rare fallback when the random probe ran dry; once the ladder climbs past four
     * concurrent fruit it would be the normal case, and at sixteen — one per tile — it would
     * be guaranteed. So a slot that cannot be filled simply stays empty and tries again on a
     * later frame, which also means the board self-limits: concurrency can never actually
     * exceed the number of tiles, whatever the ladder asks for.
     */
    private fun spawn(slot: Int, nowNs: Long, lifeMs: Int): Boolean {
        // Scanned rather than collected into a set: there are only a few slots and a
        // handful of splats, and this runs several times a second at top speed.
        var tile = random.nextInt(TILE_COUNT)
        var guard = 0
        while (tileBusy(tile) && guard++ < TILE_COUNT * 2) tile = random.nextInt(TILE_COUNT)
        if (tileBusy(tile)) tile = firstFreeTile(tile) ?: return false

        slots[slot] = ActiveFruit(
            tile = tile,
            fruit = Fruit.ALL[random.nextInt(Fruit.ALL.size)],
            bornNs = nowNs,
            lifeMs = lifeMs,
        )
        return true
    }

    private fun scheduleRespawn(slot: Int, nowNs: Long) {
        val interval = spawnIntervalMs(level)
        // A little jitter keeps the slots from locking into phase with each other.
        val jitter = (random.nextFloat() * interval * SPAWN_JITTER).toLong()
        nextSpawnNs[slot] = nowNs + (interval * SPAWN_GAP).toLong() * NS_PER_MS + jitter * NS_PER_MS
    }

    private fun expireSplats(nowNs: Long) {
        // Backwards by index so removal cannot disturb the positions still to be examined.
        // `downTo` rather than `indices.reversed()`: the former is guaranteed to compile to
        // a plain counter, and not allocating per frame is the entire point here.
        for (i in splats.size - 1 downTo 0) {
            if ((nowNs - splats[i].bornNs) / NS_PER_MS >= SPLAT_LIFE_MS) splats.removeAt(i)
        }
    }

    private fun finish(nowNs: Long, quit: Boolean) {
        elapsedMs = when {
            phase == Phase.RUNNING -> (nowNs - startNs) / NS_PER_MS
            // A run paused mid-flight sits in COUNTDOWN too, and it has a score: only one
            // abandoned during the *opening* countdown was never played at all.
            resumingRun -> elapsedMs
            else -> 0L
        }
        resumingRun = false
        result = Result(
            millisSurvived = elapsedMs,
            hits = hits,
            topSpeedLevel = level,
            ranked = ranked,
            quit = quit,
        )
        slots.fill(null)
        if (quit) {
            phase = Phase.OVER
            listener?.onGameOver(result!!)
        } else {
            // Play the burst out first; the listener fires when OUTRO ends.
            phase = Phase.OUTRO
            outroStartedNs = nowNs
        }
    }

    companion object {
        const val TILE_COLUMNS = 4
        const val TILE_ROWS = 4
        const val TILE_COUNT = TILE_COLUMNS * TILE_ROWS
        /**
         * Slots available at the hardest point in a run — one per tile.
         *
         * This is a physical ceiling, not a tuning knob: a seventeenth concurrent fruit has
         * nowhere to go on a 4x4 board. [spawn] enforces the same thing independently by
         * refusing to place a fruit on an occupied tile, so the board self-limits even if the
         * ladder below is ever retuned to ask for more.
         */
        const val MAX_TARGETS = TILE_COUNT

        /** Slots a run opens with. */
        const val BASE_TARGETS = 2

        // Levels at which the third and fourth slots open. The HUD reads `level + 1`, so
        // these are the points where the speed readout ticks to 6 and to 7: two fruit through
        // the fifth gear, three in the sixth, four from the seventh on. Left exactly as tuned.
        const val THIRD_TARGET_LEVEL = 5
        const val FOURTH_TARGET_LEVEL = 6

        /**
         * Level the fifth slot opens. Deliberately well past the fourth rather than three
         * levels after it: the fourth arrives at level 6 and both pace tracks reach their knee
         * at 9 and 10, so a new fruit there stacked three step-changes inside four levels —
         * around thirty seconds in, which is where the run stopped being a ramp and became a
         * wall. Holding at four through level 11 leaves a stretch at the old curve's plateau,
         * the difficulty this game was actually tuned around, before the ladder climbs again.
         */
        const val FIFTH_TARGET_LEVEL = 12

        /** Levels between each further slot opening, once the fifth has landed. */
        const val TARGET_STEP_LEVELS = 4

        /**
         * How many slots cycle at [level].
         *
         * The opening is untouched — two fruit, a third at level 5, a fourth at level 6, all
         * as tuned. What changed is that it no longer stops there: a fifth opens at
         * [FIFTH_TARGET_LEVEL], and one more every [TARGET_STEP_LEVELS] levels after that,
         * until the board is full.
         *
         * That is what makes a run end. The curve used to flatten completely at level 10, so
         * past forty seconds nothing got harder ever again and the score stopped measuring
         * skill and started measuring stamina — the all-time best would be set by whoever was
         * most willing to keep holding a phone, and would be effectively unbeatable. Now the
         * board fills at level [TOP_SPEED_LEVEL], and sixteen fruit sharing sixteen tiles is
         * not survivable by anybody. Runs terminate on their own.
         */
        fun targetsAtLevel(level: Int): Int = when {
            level < THIRD_TARGET_LEVEL -> BASE_TARGETS
            level < FOURTH_TARGET_LEVEL -> 3
            level < FIFTH_TARGET_LEVEL -> 4
            else -> min(5 + (level - FIFTH_TARGET_LEVEL) / TARGET_STEP_LEVELS, MAX_TARGETS)
        }

        const val MAX_STRIKES = 3
        const val COUNTDOWN_MS = 3_000

        /**
         * Countdown replayed when an interrupted run comes back. Shorter than the opening
         * one — the player already knows what they are looking at — but long enough to read
         * the board before it starts moving again.
         */
        const val RESUME_COUNTDOWN_MS = 2_000

        /**
         * How long the End-run control stays armed after a first press. Long enough to be a
         * deliberate double-press, short enough that an accidental arm is gone before the
         * player could stumble into it again.
         */
        const val QUIT_ARM_MS = 1_600L

        const val OUTRO_MS = 1_500
        const val SPLAT_LIFE_MS = 1_100
        const val STRIKE_FLASH_MS = 420

        /** How long a speed level lasts before the orchard steps up a gear. */
        private const val LEVEL_STEP_MS = 4_000L
        private const val NS_PER_MS = 1_000_000L

        /** Fraction of an interval to wait before a slot refills. */
        private const val SPAWN_GAP = 0.35f
        private const val SPAWN_JITTER = 0.45f

        // Difficulty curve. Both tracks ramp linearly per level exactly as tuned, down to a
        // knee; past the knee they keep tightening, but geometrically toward a hard floor
        // rather than linearly into absurdity. So the pace never stops increasing, and it also
        // never reaches a fruit life of zero, which would not be "hard" so much as broken.
        //
        // The knee values are the old flat floors, so every level up to and including the knee
        // behaves precisely as it did before this change.
        private const val START_INTERVAL_MS = 900
        private const val KNEE_INTERVAL_MS = 200
        private const val FLOOR_INTERVAL_MS = 120
        private const val INTERVAL_STEP_MS = 72

        private const val START_LIFE_MS = 1_550
        private const val KNEE_LIFE_MS = 430
        private const val FLOOR_LIFE_MS = 300
        private const val LIFE_STEP_MS = 135

        /**
         * Per-level multiplier applied past the knee. Deliberately gentle: the concurrency
         * ladder is what carries the late difficulty, and compounding a steep pace decay on
         * top of a growing fruit count makes the endgame collapse in a couple of levels
         * instead of tightening.
         */
        // Softened from 0.97 after playing it: at 0.97 the pace was still visibly tightening
        // while the slot count climbed, and the two compounding was too much too soon.
        private const val TAIL_DECAY = 0.985

        /**
         * Linear while [start] - level x [step] is still above [knee], then an asymptotic
         * approach from [knee] down toward [floor]. Continuous at the knee by construction:
         * at zero levels past it the decay term is 1, which yields exactly [knee].
         */
        private fun rampThenDecay(level: Int, start: Int, step: Int, knee: Int, floor: Int): Int {
            val linear = start - level * step
            if (linear >= knee) return linear
            // Ceiling division: the first level whose linear value has dropped to the knee.
            val kneeLevel = (start - knee + step - 1) / step
            var decayed = (knee - floor).toDouble()
            repeat(level - kneeLevel) { decayed *= TAIL_DECAY }
            return floor + decayed.toInt()
        }

        /** Gap between spawns, tightening as the run goes on and never levelling off. */
        fun spawnIntervalMs(level: Int): Int =
            rampThenDecay(level, START_INTERVAL_MS, INTERVAL_STEP_MS, KNEE_INTERVAL_MS, FLOOR_INTERVAL_MS)

        /** How long a fruit stays whackable, shrinking as the run goes on. */
        fun fruitLifeMs(level: Int): Int =
            rampThenDecay(level, START_LIFE_MS, LIFE_STEP_MS, KNEE_LIFE_MS, FLOOR_LIFE_MS)

        /**
         * The level at which the board fills and no further slot can open.
         *
         * This used to mean "both pace tracks have bottomed out", which stopped being a
         * meaningful idea once they stopped bottoming out. It now marks the point at which the
         * *last* thing that can get harder has finished getting harder — every tile occupied.
         * Derived rather than written down so it cannot drift out of step with the ladder.
         * Everything the player reads as speed — the bar, the number, the parallax — is
         * clamped to it.
         */
        val TOP_SPEED_LEVEL: Int = run {
            var candidate = 0
            // Bounded: a ladder retuned so the board never fills would otherwise hang class
            // initialisation here rather than fail somewhere legible.
            while (candidate < 1_000 && targetsAtLevel(candidate) < MAX_TARGETS) candidate++
            candidate
        }

        /** 1-based speed for the HUD, held at [TOP_SPEED_LEVEL] once the curve flattens. */
        fun displaySpeed(level: Int): Int = min(level, TOP_SPEED_LEVEL) + 1

        fun isTopSpeed(level: Int): Boolean = level >= TOP_SPEED_LEVEL

        /** 0..1 progress toward top speed, for the HUD's speed bar. */
        fun speedFraction(level: Int): Float {
            if (TOP_SPEED_LEVEL <= 0) return 1f
            return (min(level, TOP_SPEED_LEVEL).toFloat() / TOP_SPEED_LEVEL).coerceIn(0f, 1f)
        }
    }
}
