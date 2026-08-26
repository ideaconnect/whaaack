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
  FRUITS,
  MAX_STRIKES,
  MAX_TARGETS,
  MAX_TICK_MS,
  SPLAT_LIFE_MS,
  SPLAT_VARIANTS,
  SURVIVE_TIERS,
  tiersCleared,
  TILE_COUNT,
  PHASE_RUNNING,
  PHASE_OVER,
  targetsAtLevel,
  fruitLifeMs,
  spawnIntervalMs,
} from '../shared/engine.js'

import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const TICK = 40

const ASSETS = resolve(dirname(fileURLToPath(import.meta.url)), '../assets/default.r')

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

// -------------------------------------------------------------------- splats
//
// A splat is the only record of a hit that outlives the hit, and the page draws straight
// from it - one bitmap widget per tile, named `splat-<fruit>-<variant>.png`. So a fruit
// name or a variant the engine invents that the asset set does not have is not a wrong
// colour, it is a missing file and a blank tile.
{
  const engine = createEngine(mulberry32(5))
  let now = 1_700_000_000_000
  let hits = 0
  let badName = 0
  let badVariant = 0
  let wrongTile = 0
  let wrongFruit = 0
  let outlived = 0

  engine.start(now)
  for (let played = 0; played < 90_000 && engine.phase !== PHASE_OVER; played += TICK) {
    now += TICK
    engine.update(now)
    if (engine.phase !== PHASE_RUNNING) continue

    // Whatever is on the board, whacked the instant it appears.
    for (const active of engine.slots.filter(Boolean)) {
      const { tile, fruit } = active
      if (!engine.tap(tile, now)) continue
      hits++
      const splat = engine.splats[tile]
      if (!splat) {
        wrongTile++
        continue
      }
      if (splat.fruit !== fruit) wrongFruit++
      if (!FRUITS.includes(splat.fruit)) badName++
      if (!(splat.variant >= 0 && splat.variant < SPLAT_VARIANTS)) badVariant++
    }

    for (let tile = 0; tile < TILE_COUNT; tile++) {
      const splat = engine.splats[tile]
      if (splat && now - splat.bornMs > SPLAT_LIFE_MS) outlived++
    }
  }

  check('every hit leaves a splat on the tile it was hit on', wrongTile === 0, `${hits} hits`)
  check('and the splat is the fruit that was whacked', wrongFruit === 0)
  check('splat fruit names all have sprites', badName === 0)
  check('splat variants stay inside the set', badVariant === 0, `0..${SPLAT_VARIANTS - 1}`)
  check('no splat outlives SPLAT_LIFE_MS', outlived === 0, `${outlived} overstayed`)
}

// A splat holds an absolute birth time like everything else here, so a clock step has to
// carry it too. What is checked is the *age*, not whether the splat survives: a step is
// absorbed down to one tick of real time, and a tick may be MAX_TICK_MS, which is longer
// than a splat lives - so a forward step legitimately ages every splat out, and counting
// survivors would be asserting that decoration outlives the rule that governs it.
//
// The failure this does catch is the backward one, which has no such excuse: unshifted,
// a splat's birth ends up in the future, its age goes negative, the expiry test never
// fires and it sits on the board for as long as the correction was wide.
for (const stepBy of [-5 * 60_000, -30_000, 30_000]) {
  const engine = createEngine(mulberry32(9))
  let now = 1_700_000_000_000
  engine.start(now)
  for (let i = 0; i < 200; i++) {
    now += TICK
    engine.update(now)
    const live = engine.slots.filter(Boolean)
    if (live.length) engine.tap(live[0].tile, now)
  }
  const before = engine.splats.map((s) => (s ? now - s.bornMs : null))

  now += stepBy + TICK
  engine.update(now)

  let drifted = 0
  let inFuture = 0
  for (let tile = 0; tile < TILE_COUNT; tile++) {
    const splat = engine.splats[tile]
    if (!splat) continue
    const age = now - splat.bornMs
    if (age < 0) inFuture++
    // Every surviving splat must have aged by the real time that passed - one tick -
    // and by no more, whatever the clock did.
    if (before[tile] === null || Math.abs(age - before[tile] - TICK) > TICK) drifted++
  }
  check(
    `a ${stepBy / 1000}s clock step carries the splats with it`,
    inFuture === 0 && drifted === 0,
    `${inFuture} in the future, ${drifted} drifted`,
  )
}

// A fresh run starts on a clean board, whatever the last one left on it.
{
  const engine = createEngine(mulberry32(13))
  let now = 1_700_000_000_000
  engine.start(now)
  for (let i = 0; i < 200; i++) {
    now += TICK
    engine.update(now)
    const live = engine.slots.filter(Boolean)
    if (live.length) engine.tap(live[0].tile, now)
  }
  const left = engine.splats.filter(Boolean).length
  engine.start(now)
  check(
    'starting a run clears the last one’s splats',
    engine.splats.every((s) => s === null),
    `${left} were on the board`,
  )
}

// ------------------------------------------------------------- survival tiers
//
// The result screen builds a file name out of every tier it is given
// (`badge-<seconds>.png`), so a tier with no art is not an error anywhere - it is a gap
// in a centred row, on the one screen a player looks at after doing something well.
{
  let ascending = true
  for (let i = 1; i < SURVIVE_TIERS.length; i++) {
    if (SURVIVE_TIERS[i] <= SURVIVE_TIERS[i - 1]) ascending = false
  }
  check('the survival tiers climb', ascending, SURVIVE_TIERS.join(' < '))

  const missing = SURVIVE_TIERS.map((tier) => 'badge-' + tier / 1000 + '.png')
    .concat('badge-best.png')
    .filter((name) => !existsSync(resolve(ASSETS, name)))
  check(
    'every tier has a badge, and so does a new best',
    missing.length === 0,
    missing.length ? 'missing ' + missing.join(', ') : SURVIVE_TIERS.length + 1 + ' present',
  )

  // Boundaries, because "above 30 seconds" and "30 seconds" are the same run to a player
  // and the badge has to agree with the number printed above it.
  const top = SURVIVE_TIERS[SURVIVE_TIERS.length - 1]
  check('a run of exactly one tier earns it', tiersCleared(SURVIVE_TIERS[0]).length === 1)
  check('a millisecond short earns nothing', tiersCleared(SURVIVE_TIERS[0] - 1).length === 0)
  check('a run past the top earns them all', tiersCleared(top + 1).length === SURVIVE_TIERS.length)
  check('and a zero-length run earns none', tiersCleared(0).length === 0)
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
