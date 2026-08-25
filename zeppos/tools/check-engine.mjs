/**
 * Assertions about the engine that a play-through would not catch.
 *
 *     node tools/check-engine.mjs
 *
 * `simulate.mjs` next door answers "is the curve tuned right", which is a question about
 * averages over many runs. This answers "does the engine hold together at its edges",
 * which is a question about single, specific moments — and the sharpest of those is the
 * clock.
 *
 * A watch drives the run from `Date.now()`, because Zepp OS offers nothing else: there is
 * no monotonic counter in the API, and the wall clock is resynced from the phone whenever
 * the two are in touch. Every deadline a run holds is an absolute reading of that clock,
 * so a correction landing mid-run is not a rounding error — forward it is banked as score
 * the player never played, backward it freezes the board into a no-risk shooting gallery.
 * Both are checked below against the sizes a phone sync actually produces.
 */

import {
  createEngine,
  COUNTDOWN_MS,
  MAX_STRIKES,
  MAX_TARGETS,
  MAX_TICK_MS,
  TILE_COUNT,
  PHASE_RUNNING,
  PHASE_OVER,
  targetsAtLevel,
  fruitLifeMs,
  spawnIntervalMs,
} from '../shared/engine.js'

const TICK = 40

let failures = 0

function check(label, ok, detail) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${label}${detail !== undefined ? '  - ' + detail : ''}`)
  if (!ok) failures++
}

function mulberry32(seed) {
  let a = seed >>> 0
  return function () {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** Runs the engine for `playMs` of real time, optionally stepping the clock partway. */
function run({ playMs, stepAt, stepBy, seed = 7, tapEvery = 0 }) {
  const engine = createEngine(mulberry32(seed))
  let now = 1_700_000_000_000
  let played = 0
  let offset = 0
  let lastTap = -Infinity

  engine.start(now)
  while (played < playMs && engine.phase !== PHASE_OVER) {
    now += TICK
    played += TICK
    if (stepAt !== undefined && played === stepAt) offset += stepBy
    engine.update(now + offset)

    if (tapEvery && engine.phase === PHASE_RUNNING && played - lastTap >= tapEvery) {
      for (const active of engine.slots) {
        if (!active) continue
        engine.tap(active.tile, now + offset)
        lastTap = played
        break
      }
    }
  }
  // What the run should claim: the real time it was alive, less the countdown it opened
  // with. A run that ends on three strikes stops early, so this is the yardstick rather
  // than `playMs`.
  return { engine, played, expected: Math.max(0, played - COUNTDOWN_MS) }
}

console.log('engine edges\n')

// ------------------------------------------------------------------- baseline
{
  const { engine, expected } = run({ playMs: 20_000, tapEvery: 200 })
  const drift = Math.abs(engine.elapsedMs - expected)
  check('an undisturbed run reports the time it played', drift <= TICK, `drift ${drift}ms`)
}

// -------------------------------------------------------- forward clock step
//
// A phone resync jumps the watch forward mid-run. Left alone the whole gap is added to
// the score outright; absorbed, a step of any size costs at most one tick, and a tick is
// allowed to be MAX_TICK_MS. So the bound below is a second of drift against a run
// measured in tens of seconds — not zero, but the same order as a dropped frame.
for (const stepBy of [5_000, 90_000, 30 * 60_000]) {
  const { engine, expected } = run({ playMs: 60_000, stepAt: 20_000, stepBy, tapEvery: 160 })
  const over = engine.elapsedMs - expected
  check(
    `a +${stepBy / 1000}s clock step is not banked as score`,
    over <= MAX_TICK_MS,
    `claimed ${engine.elapsedMs}ms for ${expected}ms played`,
  )
}

// ------------------------------------------------------- backward clock step
//
// Backward is the quieter failure: every deadline lands in the future, so the board
// stops spawning and stops expiring while the player taps freely.
for (const stepBy of [-5_000, -30_000]) {
  const { engine, expected } = run({ playMs: 60_000, stepAt: 20_000, stepBy, tapEvery: 160 })
  const under = expected - engine.elapsedMs
  check(
    `a ${stepBy / 1000}s clock step does not freeze the run`,
    under <= MAX_TICK_MS,
    `claimed ${engine.elapsedMs}ms for ${expected}ms played`,
  )
}

// A step must not be able to end a run by itself: the fruit on the board move with it.
{
  const { engine } = run({ playMs: 12_000, stepAt: 6_000, stepBy: 10 * 60_000, tapEvery: 150 })
  check('a clock step does not mass-expire the board', engine.strikes < MAX_STRIKES, `${engine.strikes} strikes`)
}

// ---------------------------------------------------------------- invariants
//
// Sampled every tick rather than at the end: when a run finishes the board is cleared, so
// the final state is the one moment these could not possibly be violated.
{
  let collisions = 0
  let mostLive = 0
  let overLadder = 0
  const engine = createEngine(mulberry32(11))
  let now = 1_700_000_000_000
  let lastTap = -Infinity
  engine.start(now)
  for (let played = 0; played < 240_000 && engine.phase !== PHASE_OVER; played += TICK) {
    now += TICK
    engine.update(now)
    const live = engine.slots.filter(Boolean)
    const tiles = new Set(live.map((f) => f.tile))
    if (tiles.size !== live.length) collisions++
    if (live.length > targetsAtLevel(engine.level)) overLadder++
    mostLive = Math.max(mostLive, live.length)
    if (engine.phase === PHASE_RUNNING && played - lastTap >= 130) {
      if (live.length) {
        engine.tap(live[0].tile, now)
        lastTap = played
      }
    }
  }
  check('two fruit never share a tile', collisions === 0, `${collisions} collisions`)
  check('never more fruit than the ladder opens', overLadder === 0, `peak ${mostLive} of ${MAX_TARGETS}`)
  check('the board actually fills up', mostLive >= 3, `peak ${mostLive}`)
}

// The ladder has to actually top out, or the late curve is unreachable.
{
  let top = 0
  for (let level = 0; level < 2000; level++) top = Math.max(top, targetsAtLevel(level))
  check('the slot ladder reaches MAX_TARGETS', top === MAX_TARGETS, `${top} of ${MAX_TARGETS}`)
  check('and never exceeds the board', top <= TILE_COUNT)
}

// Both pace tracks must stay above their floors and keep tightening, for ever.
{
  let monotonic = true
  let aboveFloor = true
  for (let level = 1; level < 5000; level++) {
    if (fruitLifeMs(level) > fruitLifeMs(level - 1)) monotonic = false
    if (spawnIntervalMs(level) > spawnIntervalMs(level - 1)) monotonic = false
    if (fruitLifeMs(level) < 460 || spawnIntervalMs(level) < 220) aboveFloor = false
  }
  check('the pace never eases off', monotonic)
  check('and never reaches zero', aboveFloor, `life ${fruitLifeMs(4999)}ms at level 4999`)
}

// A quit during the countdown is not a run, and must not submit a score.
{
  const engine = createEngine(mulberry32(3))
  const now = 1_700_000_000_000
  engine.start(now)
  engine.update(now + 500)
  engine.quitRun(now + 500)
  check('quitting during the countdown scores nothing', engine.elapsedMs === 0, `${engine.elapsedMs}ms`)
}

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed')
process.exit(failures ? 1 : 0)
