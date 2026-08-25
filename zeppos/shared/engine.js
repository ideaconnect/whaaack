/**
 * Whaaack! for Zepp OS - the simulation.
 *
 * Pure JavaScript: no `@zos/*` import, no widget, no timer of its own. Time arrives as a
 * millisecond argument on every call, so the page can drive it from a render tick and a
 * test can drive it from a loop. That is the same split the phone game makes between
 * GameEngine and GameSurfaceView, and it exists for the same reason - the rules are the
 * part worth being able to reason about on its own.
 *
 * What is simplified against the phone game
 *
 *   - a 3x3 board instead of 4x4, so nine tiles rather than sixteen;
 *   - five concurrent fruit at the very top instead of sixteen;
 *   - no splats, no outro animation, no pause/resume - a watch app that loses the
 *     foreground has lost the run, and pretending otherwise would bank a score the
 *     player did not finish;
 *   - a single tap ends a run early (the phone's two-press arming guards against a
 *     fumbled thumb an inch below a 4x4 board; here the control is the hardware BACK
 *     button, which is nowhere near the tiles).
 *
 * What is not simplified is the shape of the difficulty curve, because that is what
 * makes the score mean something. Both pace tracks ramp linearly to a knee and then
 * decay geometrically toward a floor, and a slot ladder carries the late game - exactly
 * as on the phone. Only the numbers are retuned, around one finger on a 46mm circle
 * instead of two thumbs on a phone.
 */

export const TILE_COLUMNS = 3
export const TILE_ROWS = 3
export const TILE_COUNT = TILE_COLUMNS * TILE_ROWS

export const MAX_STRIKES = 3
export const COUNTDOWN_MS = 3000

/** Slots a run opens with. */
export const BASE_TARGETS = 2

/**
 * Slots at the hardest point in a run.
 *
 * Five of nine tiles occupied is the point at which one finger cannot keep up, which is
 * what makes runs end on their own rather than measuring who is most willing to keep
 * tapping. It is well under the physical ceiling of nine - `spawn` refuses to place a
 * fruit on an occupied tile, so the board self-limits regardless - because a watch is
 * tapped with one finger that also covers a third of the screen while it travels.
 */
export const MAX_TARGETS = 5

/** Levels at which the third, fourth and fifth slots open. */
const THIRD_TARGET_LEVEL = 4
const FOURTH_TARGET_LEVEL = 10
const FIFTH_TARGET_LEVEL = 18

/** How long a speed level lasts before the orchard steps up a gear. */
const LEVEL_STEP_MS = 4000

// Difficulty curve. The yardstick is pressure - the fruit arrivals per second a player
// must match to take no strikes, which is targets x 1000 / (interval x SPAWN_GAP + life).
// Sustained aimed tapping on a watch tops out lower than on a phone: roughly 2/s for a
// casual player and 5/s for an expert, because the finger that taps also hides the tile
// it is travelling to. So the curve sweeps that band and then keeps creeping:
//
//   level  0   (0s)    2 slots   1.17/s
//   level  4   (16s)   3 slots   1.93/s
//   level 10   (40s)   4 slots   3.04/s
//   level 18   (72s)   5 slots   4.38/s
//   level 30   (120s)  5 slots   4.94/s
//   level 45   (180s)  5 slots   5.64/s
//
// Against synthetic players held to exactly those rates (tools/simulate.mjs), a casual
// run ends around 38s and an expert's around four minutes — a spread of better than six
// to one, which is what the leaderboard needs in order to rank anything, and none of them
// runs for ever.
const START_INTERVAL_MS = 900
const KNEE_INTERVAL_MS = 560
const FLOOR_INTERVAL_MS = 220
const INTERVAL_STEP_MS = 28

const START_LIFE_MS = 1400
const KNEE_LIFE_MS = 1000
const FLOOR_LIFE_MS = 460
const LIFE_STEP_MS = 30

/** Per-level multiplier past the knee. Gentle: the slot ladder carries the late game. */
const TAIL_DECAY = 0.98

/** Fraction of an interval a slot waits before refilling, plus the jitter on top. */
const SPAWN_GAP = 0.35
const SPAWN_JITTER = 0.45

/**
 * Minimum gap between any two arrivals, board-wide.
 *
 * Without one, two slots can surface fruit in the same instant - at opposite corners
 * that is a strike no reaction could prevent, because one finger cannot be in two
 * places. It yields to the ladder rather than binding it: `life / targets` is the widest
 * spacing at which the level's own fruit count is still reachable, so late levels taper
 * it instead of capping the board.
 */
const SPAWN_SPACING_MS = 120

/**
 * The least remaining life every other airborne fruit is granted when a strike lands.
 *
 * Fruit that spawns together expires together, so without this the moment that took
 * strike one was the moment strikes two and three were due, and a run ended in a blink.
 * It is no use as a lifeline: letting fruit escape on purpose buys under half a second
 * per strike, and there are only three.
 */
const STRIKE_GRACE_MS = 450

/**
 * How long a whacked tile stays lit, and stays out of the spawn pool.
 *
 * Doing both with one number is deliberate. The flash is the only hit feedback the
 * board gives, and a fruit landing on the tile that is mid-flash would eat it - the
 * player would see one animation and have to guess which event it belonged to.
 */
export const HIT_FLASH_MS = 220

/** Remaining fraction of its life at which a fruit's tile starts warning. */
export const WARN_FRACTION = 0.38

/**
 * The most a single tick may advance the run by. Anything past this is a clock step, not
 * elapsed time, and is absorbed rather than played (see `syncClock`).
 *
 * The page ticks every 40ms, so a second is twenty-five frames of slack: enough that a
 * slow frame, a garbage collection or a busy side service never trips it, tight enough
 * that a real correction is caught on the tick it arrives. A tick that genuinely took
 * longer than this loses the excess, which costs the player a little score — the safe
 * direction, and the one the alternative does not have.
 */
export const MAX_TICK_MS = 1000

export const FRUITS = [
  'apple',
  'banana',
  'cherry',
  'grape',
  'kiwi',
  'lemon',
  'orange',
  'peach',
  'pear',
  'pineapple',
  'strawberry',
  'watermelon',
]

export const PHASE_IDLE = 'idle'
export const PHASE_COUNTDOWN = 'countdown'
export const PHASE_RUNNING = 'running'
export const PHASE_OVER = 'over'

/**
 * Linear while `start - level * step` is still above `knee`, then an asymptotic approach
 * from `knee` down toward `floor`. Continuous at the knee by construction: at zero levels
 * past it the decay term is 1, which yields exactly `knee`.
 */
function rampThenDecay(level, start, step, knee, floor) {
  const linear = start - level * step
  if (linear >= knee) return linear
  // Ceiling division: the first level whose linear value has dropped to the knee.
  const kneeLevel = Math.ceil((start - knee) / step)
  let decayed = knee - floor
  for (let i = 0; i < level - kneeLevel; i++) decayed *= TAIL_DECAY
  return floor + Math.floor(decayed)
}

/** Gap between spawns, tightening as the run goes on and never levelling off. */
export function spawnIntervalMs(level) {
  return rampThenDecay(level, START_INTERVAL_MS, INTERVAL_STEP_MS, KNEE_INTERVAL_MS, FLOOR_INTERVAL_MS)
}

/** How long a fruit stays whackable, shrinking as the run goes on. */
export function fruitLifeMs(level) {
  return rampThenDecay(level, START_LIFE_MS, LIFE_STEP_MS, KNEE_LIFE_MS, FLOOR_LIFE_MS)
}

/** How many slots cycle at `level`: two to open, then one more at each rung above. */
export function targetsAtLevel(level) {
  if (level < THIRD_TARGET_LEVEL) return BASE_TARGETS
  if (level < FOURTH_TARGET_LEVEL) return 3
  if (level < FIFTH_TARGET_LEVEL) return 4
  return MAX_TARGETS
}

/**
 * The level at which the last thing that can get harder has finished getting harder.
 * Derived rather than written down, so it cannot drift out of step with the ladder.
 */
export const TOP_SPEED_LEVEL = (() => {
  let candidate = 0
  while (candidate < 1000 && targetsAtLevel(candidate) < MAX_TARGETS) candidate++
  return candidate
})()

/** 1-based speed for the HUD, held at the top once the ladder runs out. */
export function displaySpeed(level) {
  return Math.min(level, TOP_SPEED_LEVEL) + 1
}

function spacingFor(lifeMs, targets) {
  return Math.min(SPAWN_SPACING_MS, Math.floor(lifeMs / targets))
}

export function createEngine(random) {
  const rand = random || Math.random

  const engine = {
    phase: PHASE_IDLE,
    elapsedMs: 0,
    strikes: 0,
    hits: 0,
    level: 0,
    countdownValue: 0,
    /** Nulls and `{ tile, fruit, bornMs, lifeMs }`; index is the slot, not the tile. */
    slots: new Array(MAX_TARGETS).fill(null),
    /** When each tile was last whacked, so the flash and the spawn cooldown agree. */
    clearedAt: new Array(TILE_COUNT).fill(0),
    /** True when the run ended because the player left rather than lost. */
    quit: false,

    start,
    update,
    tap,
    quitRun,
    fruitAt,
    remainingFraction,
  }

  let startMs = 0
  let countdownEndsMs = 0
  let openTargets = BASE_TARGETS
  let lastSpawnMs = 0
  const nextSpawnMs = new Array(MAX_TARGETS).fill(0)

  /** The clock reading the last call saw, so the next one can measure the step. */
  let lastNow = 0

  /**
   * Absorbs a jump in the clock, so the run neither gains nor loses time across one.
   *
   * The page drives this engine from `Date.now()`, because a watch offers nothing else —
   * there is no monotonic counter in the Zepp OS API, and the wall clock is resynced from
   * the phone whenever the two are in touch. So a correction of any size can land between
   * two ticks, and every deadline a run holds is an absolute reading of that same clock.
   *
   * Left alone it is wrong in both directions. Forward, the gap is banked as score: a
   * ninety-second correction is ninety seconds the player never played, added to the run
   * and then to the board. Backward, the run freezes — every deadline is suddenly in the
   * future, so nothing spawns, nothing expires, and the fruit already on the board can be
   * whacked at no risk until the clock catches up.
   *
   * The cure is the one the phone engine already applies when it resumes from a pause
   * (`GameEngine.resume`, which shifts every deadline by the time spent away): move the
   * whole run with the clock rather than trying to reconcile it afterwards. A step costs
   * one tick's worth of play and nothing else.
   */
  function syncClock(now) {
    if (lastNow === 0) {
      lastNow = now
      return
    }
    const delta = now - lastNow
    lastNow = now
    const jump = delta - Math.max(0, Math.min(delta, MAX_TICK_MS))
    if (jump === 0) return

    startMs += jump
    countdownEndsMs += jump
    // Zero means "never happened", so it must not be shifted into meaning something.
    if (lastSpawnMs !== 0) lastSpawnMs += jump
    for (let i = 0; i < nextSpawnMs.length; i++) nextSpawnMs[i] += jump
    for (let i = 0; i < engine.slots.length; i++) {
      const active = engine.slots[i]
      if (active) active.bornMs += jump
    }
    for (let i = 0; i < engine.clearedAt.length; i++) {
      if (engine.clearedAt[i] !== 0) engine.clearedAt[i] += jump
    }
  }

  function start(now) {
    engine.phase = PHASE_COUNTDOWN
    engine.elapsedMs = 0
    engine.strikes = 0
    engine.hits = 0
    engine.level = 0
    engine.quit = false
    engine.slots.fill(null)
    engine.clearedAt.fill(0)
    countdownEndsMs = now + COUNTDOWN_MS
    engine.countdownValue = COUNTDOWN_MS / 1000
    openTargets = BASE_TARGETS
    lastSpawnMs = 0
    nextSpawnMs.fill(0)
    lastNow = now
  }

  function update(now) {
    syncClock(now)
    if (engine.phase === PHASE_COUNTDOWN) {
      const remaining = countdownEndsMs - now
      engine.countdownValue = Math.max(0, Math.ceil(remaining / 1000))
      if (now >= countdownEndsMs) beginRun(now)
      return
    }
    if (engine.phase === PHASE_RUNNING) stepRun(now)
  }

  function beginRun(now) {
    engine.phase = PHASE_RUNNING
    startMs = now
    // Slot 0 pops immediately; slot 1 lands half an interval later, so the pair reads as
    // two independent fruit rather than one synchronised blink.
    nextSpawnMs[0] = now
    nextSpawnMs[1] = now + spawnIntervalMs(0) / 2
  }

  function stepRun(now) {
    engine.elapsedMs = now - startMs
    engine.level = Math.floor(engine.elapsedMs / LEVEL_STEP_MS)

    const lifeMs = fruitLifeMs(engine.level)
    let struck = false

    while (openTargets < targetsAtLevel(engine.level)) {
      // A newly opened slot gets the same delayed, jittered entry every other slot gets
      // once it is cleared, so it reads as one more independent arrival rather than the
      // set blinking in unison. Left unscheduled until now because a zeroed deadline is
      // already in the past: the slot would fire the instant it opened.
      scheduleRespawn(openTargets, now)
      openTargets++
    }

    const spacing = spacingFor(lifeMs, openTargets)

    for (let i = 0; i < openTargets; i++) {
      const active = engine.slots[i]
      if (active) {
        if (now - active.bornMs >= active.lifeMs) {
          engine.slots[i] = null
          engine.strikes++
          struck = true
          scheduleRespawn(i, now)
          // One lapse costs one strike.
          graceOtherFruit(now)
        }
      } else if (now >= nextSpawnMs[i] && now - lastSpawnMs >= spacing) {
        // A full board is not an error and not a strike - the slot just waits. Only
        // reschedule on success, so a blocked slot retries promptly rather than sitting
        // out a whole interval it never got to use.
        spawn(i, now, lifeMs)
      }
    }

    if (struck && engine.strikes >= MAX_STRIKES) finish(now, false)
  }

  function tileBusy(tile, now) {
    for (let i = 0; i < engine.slots.length; i++) {
      const active = engine.slots[i]
      if (active && active.tile === tile) return true
    }
    return now - engine.clearedAt[tile] < HIT_FLASH_MS
  }

  function tileHasFruit(tile) {
    for (let i = 0; i < engine.slots.length; i++) {
      const active = engine.slots[i]
      if (active && active.tile === tile) return true
    }
    return false
  }

  /**
   * Places a fruit, or reports that the board had nowhere to put one.
   *
   * Refusing rather than forcing a placement is what makes a full board safe: two fruit
   * on one tile means one tap clears one of them and the other expires into a strike the
   * player could not have prevented.
   */
  function spawn(slot, now, lifeMs) {
    let tile = Math.floor(rand() * TILE_COUNT)
    let guard = 0
    while (tileBusy(tile, now) && guard++ < TILE_COUNT * 2) {
      tile = Math.floor(rand() * TILE_COUNT)
    }
    if (tileBusy(tile, now)) {
      // The random probe ran dry. Take the first tile that is merely flashing over one
      // that still holds fruit; a flash can be interrupted, a second fruit cannot.
      let flashingOnly = -1
      let found = -1
      for (let offset = 1; offset <= TILE_COUNT; offset++) {
        const candidate = (tile + offset) % TILE_COUNT
        if (tileHasFruit(candidate)) continue
        if (!tileBusy(candidate, now)) {
          found = candidate
          break
        }
        if (flashingOnly < 0) flashingOnly = candidate
      }
      if (found < 0) found = flashingOnly
      if (found < 0) return false
      tile = found
    }

    engine.slots[slot] = {
      tile,
      fruit: FRUITS[Math.floor(rand() * FRUITS.length)],
      bornMs: now,
      lifeMs,
    }
    lastSpawnMs = now
    return true
  }

  /**
   * After a strike, tops the remaining life of every airborne fruit up to
   * `STRIKE_GRACE_MS` - never past the fruit's own full life, so a rewound birth cannot
   * sit in the future. The board reads `bornMs`, so a reprieved fruit visibly un-warns
   * instead of being reprieved silently.
   */
  function graceOtherFruit(now) {
    for (let i = 0; i < engine.slots.length; i++) {
      const other = engine.slots[i]
      if (!other) continue
      const grace = Math.min(STRIKE_GRACE_MS, other.lifeMs)
      const remaining = other.lifeMs - (now - other.bornMs)
      if (remaining < grace) other.bornMs = now - (other.lifeMs - grace)
    }
  }

  function scheduleRespawn(slot, now) {
    const interval = spawnIntervalMs(engine.level)
    // A little jitter keeps the slots from locking into phase with each other.
    const jitter = rand() * interval * SPAWN_JITTER
    nextSpawnMs[slot] = now + interval * SPAWN_GAP + jitter
  }

  /** Applies a tap on `tile`. Returns true when it landed on fruit. */
  function tap(tile, now) {
    // A tap arrives between ticks and stamps `clearedAt`, so it has to see the same
    // corrected clock the tick will; otherwise a step landing mid-tap is applied twice.
    syncClock(now)
    if (engine.phase !== PHASE_RUNNING) return false
    for (let i = 0; i < engine.slots.length; i++) {
      const active = engine.slots[i]
      if (!active || active.tile !== tile) continue
      engine.slots[i] = null
      engine.hits++
      engine.clearedAt[tile] = now
      scheduleRespawn(i, now)
      return true
    }
    return false
  }

  /** Ends the run the player is leaving. The score still stands: they survived it. */
  function quitRun(now) {
    syncClock(now)
    if (engine.phase !== PHASE_RUNNING && engine.phase !== PHASE_COUNTDOWN) return
    finish(now, true)
  }

  function finish(now, quit) {
    engine.elapsedMs = engine.phase === PHASE_RUNNING ? now - startMs : 0
    engine.quit = quit
    engine.slots.fill(null)
    engine.phase = PHASE_OVER
  }

  /** The fruit currently on `tile`, or null. */
  function fruitAt(tile) {
    for (let i = 0; i < engine.slots.length; i++) {
      const active = engine.slots[i]
      if (active && active.tile === tile) return active
    }
    return null
  }

  /** 1 at birth down to 0 at expiry, for the tile's warning state. */
  function remainingFraction(active, now) {
    if (!active) return 0
    const left = active.lifeMs - (now - active.bornMs)
    return Math.max(0, Math.min(1, left / active.lifeMs))
  }

  return engine
}
