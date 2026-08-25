/**
 * Plays the watch game against a synthetic player, so the difficulty curve can be checked
 * without a watch.
 *
 *     node tools/simulate.mjs            # the default grades
 *     node tools/simulate.mjs 40 200     # 40 runs each, 200ms of reaction time
 *
 * The engine is pure JavaScript with time as an argument, which is the whole reason this
 * is possible: the loop below steps it at the same 40ms tick the page uses, and the
 * "player" is a reaction budget plus a tap rate. Neither models a person well, but they
 * bracket one - and what the curve has to get right is where each *grade* of player runs
 * out, not what any individual does.
 *
 * A grade is two numbers:
 *   reaction  ms between a fruit appearing and this player being able to hit it
 *   rate      taps per second they can sustain, which caps how many they can service
 *
 * What to look for: the median run length should separate the grades by a wide margin and
 * no grade should be able to run for ever. If "expert" never dies, the ladder has stopped
 * carrying the late game and MAX_TARGETS or the slot levels need another look.
 */

import { createEngine, PHASE_OVER, PHASE_RUNNING } from '../shared/engine.js'

const TICK_MS = 40

const GRADES = [
  { name: 'casual', reaction: 520, rate: 2.0 },
  { name: 'decent', reaction: 380, rate: 3.2 },
  { name: 'good', reaction: 280, rate: 4.5 },
  { name: 'expert', reaction: 200, rate: 6.0 },
]

/** A deterministic PRNG, so a run of this script is comparable with the last one. */
function mulberry32(seed) {
  let a = seed >>> 0
  return function () {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function playOne(grade, seed, capMs) {
  const random = mulberry32(seed)
  const engine = createEngine(random)

  let now = 0
  const minGapMs = 1000 / grade.rate
  // The next moment this player may tap, carried forward by whole intervals rather than
  // reset to `now`. The loop only ever sees multiples of TICK_MS, so resetting would round
  // every gap *up* to the next tick and quietly cap the grade below its stated rate — at
  // 6/s the first permissible delta is 200ms, which is 5/s, and the expert grade exists
  // precisely to probe the band above 5/s. Carrying the debt lets the average converge on
  // the nominal rate instead.
  let nextTapAt = -Infinity

  engine.start(now)
  while (engine.phase !== PHASE_OVER && now < capMs) {
    now += TICK_MS
    engine.update(now)
    if (engine.phase !== PHASE_RUNNING) continue

    // Go for the fruit closest to expiring that this player could have reacted to. A real
    // player is not this well informed, but being wrong the other way - picking at random
    // - would measure the picker rather than the curve.
    if (now < nextTapAt) continue

    let target = null
    let leastLeft = Infinity
    for (const active of engine.slots) {
      if (!active) continue
      if (now - active.bornMs < grade.reaction) continue
      const left = active.lifeMs - (now - active.bornMs)
      if (left < leastLeft) {
        leastLeft = left
        target = active
      }
    }
    if (target) {
      engine.tap(target.tile, now)
      // Never bank more than one interval of credit: a player who had nothing to hit for
      // ten seconds does not then get to fire ten seconds' worth of taps at once.
      nextTapAt = Math.max(nextTapAt, now - minGapMs) + minGapMs
    }
  }

  return { millis: engine.elapsedMs, hits: engine.hits, level: engine.level, capped: now >= capMs }
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0
  const index = Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * p))
  return sorted[index]
}

const runs = Number(process.argv[2]) || 25
const capMs = 15 * 60 * 1000

console.log(`${runs} runs per grade, ${TICK_MS}ms tick\n`)
console.log('grade    median      p10       p90      max   hits/s  capped')

for (const grade of GRADES) {
  const results = []
  for (let i = 0; i < runs; i++) results.push(playOne(grade, 1000 + i, capMs))
  const lengths = results.map((r) => r.millis).sort((a, b) => a - b)
  const totalMs = results.reduce((sum, r) => sum + r.millis, 0)
  const totalHits = results.reduce((sum, r) => sum + r.hits, 0)
  const capped = results.filter((r) => r.capped).length

  const s = (ms) => (ms / 1000).toFixed(1).padStart(7) + 's'
  console.log(
    grade.name.padEnd(8) +
      s(percentile(lengths, 0.5)) +
      s(percentile(lengths, 0.1)) +
      s(percentile(lengths, 0.9)) +
      s(lengths[lengths.length - 1]) +
      (totalHits / (totalMs / 1000)).toFixed(2).padStart(8) +
      String(capped).padStart(8),
  )
}
