package tech.idct.whaaack.game

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.random.Random

/**
 * Pure game simulation. Holds no Android types and never touches a Canvas, so it can be
 * stepped from the render thread without contending with the main thread.
 *
 * Rules, per the brief:
 *  - exactly two fruit slots, each cycling independently so they can surface together or
 *    slightly offset, but never more than two at once;
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

    var countdownValue: Int = 0
        private set

    private var startNs = 0L
    private var countdownEndsNs = 0L
    private val nextSpawnNs = LongArray(MAX_TARGETS)
    private val pendingTaps = ConcurrentLinkedQueue<Int>()
    private var result: Result? = null

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
    }

    /** Called from the UI thread when the player taps tile [tile]. */
    fun postTap(tile: Int) {
        if (tile in 0 until TILE_COUNT) pendingTaps.add(tile)
    }

    /** Ends the run early without it counting as a loss (the "End run" button). */
    fun quit(nowNs: Long) {
        if (phase == Phase.RUNNING || phase == Phase.COUNTDOWN) finish(nowNs, quit = true)
    }

    fun consumeResult(): Result? = result

    fun update(nowNs: Long) {
        when (phase) {
            Phase.COUNTDOWN -> {
                val remainingMs = (countdownEndsNs - nowNs) / NS_PER_MS
                countdownValue = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(0)
                if (nowNs >= countdownEndsNs) beginRun(nowNs)
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

        for (i in 0 until MAX_TARGETS) {
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

    private fun spawn(slot: Int, nowNs: Long, lifeMs: Int) {
        val taken = HashSet<Int>(4)
        for (other in slots) other?.let { taken.add(it.tile) }
        // Avoid dropping a fruit onto a tile that is still showing a splat.
        for (s in splats) taken.add(s.tile)

        var tile = random.nextInt(TILE_COUNT)
        var guard = 0
        while (tile in taken && guard++ < TILE_COUNT * 2) tile = random.nextInt(TILE_COUNT)

        slots[slot] = ActiveFruit(
            tile = tile,
            fruit = Fruit.ALL[random.nextInt(Fruit.ALL.size)],
            bornNs = nowNs,
            lifeMs = lifeMs,
        )
    }

    private fun scheduleRespawn(slot: Int, nowNs: Long) {
        val interval = spawnIntervalMs(level)
        // A little jitter keeps the two slots from locking into phase with each other.
        val jitter = (random.nextFloat() * interval * SPAWN_JITTER).toLong()
        nextSpawnNs[slot] = nowNs + (interval * SPAWN_GAP).toLong() * NS_PER_MS + jitter * NS_PER_MS
    }

    private fun expireSplats(nowNs: Long) {
        if (splats.isEmpty()) return
        val it = splats.iterator()
        while (it.hasNext()) {
            if ((nowNs - it.next().bornNs) / NS_PER_MS >= SPLAT_LIFE_MS) it.remove()
        }
    }

    private fun finish(nowNs: Long, quit: Boolean) {
        elapsedMs = if (phase == Phase.RUNNING) (nowNs - startNs) / NS_PER_MS else 0L
        result = Result(
            millisSurvived = elapsedMs,
            hits = hits,
            topSpeedLevel = level,
            ranked = ranked,
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
        const val MAX_TARGETS = 2
        const val MAX_STRIKES = 3
        const val COUNTDOWN_MS = 3_000
        const val OUTRO_MS = 1_500
        const val SPLAT_LIFE_MS = 1_100
        const val STRIKE_FLASH_MS = 420

        /** How long a speed level lasts before the orchard steps up a gear. */
        private const val LEVEL_STEP_MS = 4_000L
        private const val NS_PER_MS = 1_000_000L

        /** Fraction of an interval to wait before a slot refills. */
        private const val SPAWN_GAP = 0.35f
        private const val SPAWN_JITTER = 0.45f

        // Difficulty curve. Both tracks ramp linearly per level and then hold at a floor;
        // the floors are what "top speed" actually means, so they are named rather than
        // repeated as literals — the HUD's speed bar reads its bounds from them too.
        private const val START_INTERVAL_MS = 900
        private const val MIN_INTERVAL_MS = 200
        private const val INTERVAL_STEP_MS = 72

        private const val START_LIFE_MS = 1_550
        private const val MIN_LIFE_MS = 430
        private const val LIFE_STEP_MS = 135

        /** Gap between spawns, tightening as the run goes on. */
        fun spawnIntervalMs(level: Int): Int =
            max(MIN_INTERVAL_MS, START_INTERVAL_MS - level * INTERVAL_STEP_MS)

        /** How long a fruit stays whackable, shrinking as the run goes on. */
        fun fruitLifeMs(level: Int): Int =
            max(MIN_LIFE_MS, START_LIFE_MS - level * LIFE_STEP_MS)

        /** 0..1 progress toward top speed, for the HUD's speed bar. */
        fun speedFraction(level: Int): Float {
            val span = (START_INTERVAL_MS - MIN_INTERVAL_MS).toFloat()
            val current = spawnIntervalMs(level).toFloat()
            return ((START_INTERVAL_MS - current) / span).coerceIn(0f, 1f)
        }
    }
}
