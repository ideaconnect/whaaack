/**
 * Checks the one thing on the watch that nothing else can: whether a hit makes a sound.
 *
 *     node tools/check-audio.mjs
 *
 * The simulator cannot answer this. It has no audio device, so `prepare()` never leaves
 * PREPARING and every path past "the file is loaded" is unreachable there - which is
 * exactly the stretch of code where the sound was broken. The first version of audio.js
 * stopped the player before starting it, and on this platform `stop()` unloads the file
 * rather than pausing it, so every hit was a stop followed by a `start()` that answered
 * **false** without throwing. Nothing caught it, on any device, and the game shipped
 * playing splats late and one at a time.
 *
 * So the player is faked instead (fake-media.js), from a probe of the real engine, and the
 * real audio.js is run against it. The module imports `@zos/*` directly, the way a watch
 * module has to, so it is copied to a temporary file with those three imports rewritten to
 * point at the fakes - a transformation small enough to read in one line below, and the
 * only alternative to restructuring the module purely to be testable.
 *
 * Both plausible devices are exercised, because one of them cannot be settled without
 * hardware: whether playback ending leaves the file loaded or unloads it the way `stop()`
 * does. The code has to be right either way.
 */

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SHARED = path.join(HERE, '..', 'shared')

const url = (p) => JSON.stringify(pathToFileURL(p).href)

/** The real module, with only its three imports redirected. Nothing else is touched. */
function underTest(sourcePath) {
  const source = fs.readFileSync(sourcePath, 'utf8')
  const rewritten = source
    .replace("'@zos/media'", url(path.join(HERE, 'fake-media.js')))
    .replace("'@zos/storage'", url(path.join(HERE, 'fake-storage.js')))
    .replace("'./protocol.js'", url(path.join(SHARED, 'protocol.js')))
  if (rewritten === source) throw new Error('no imports were rewritten in ' + sourcePath)

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'whaaack-audio-'))
  const file = path.join(dir, 'audio.mjs')
  fs.writeFileSync(file, rewritten, 'utf8')
  return { file, dir }
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

let failures = 0
function check(label, ok, detail) {
  if (!ok) failures++
  console.log((ok ? 'ok    ' : 'FAIL  ') + label + (detail ? '  - ' + detail : ''))
}

const target = process.argv[2] ? path.resolve(process.argv[2]) : path.join(SHARED, 'audio.js')
const { file, dir } = underTest(target)
const media = await import(pathToFileURL(path.join(HERE, 'fake-media.js')).href)
const audio = await import(pathToFileURL(file).href)

console.log('module  ' + path.relative(path.join(HERE, '..'), target).replace(/\\/g, '/'))

const started = () => media.calls.filter((c) => c === 'start:true').length
const sought = () => media.calls.filter((c) => c === 'seek:true').length
const stopped = () => media.calls.filter((c) => c === 'stop:true').length
const loaded = () => media.calls.filter((c) => c === 'setSource:true').length
const trace = () => media.calls.join(' ')

async function scenario(label, stateAfterComplete) {
  console.log('')
  console.log('--- ' + label + ' ---')
  media.reset({ stateAfterComplete })
  audio.release()
  audio.setSoundOn(true)

  audio.primeSplat()
  await wait(60)

  check('the splat takes the one player the device has', media.calls.includes('create:ok'), trace())
  check(
    'and asks for exactly one - nothing else competes for it now',
    media.calls.filter((c) => c.startsWith('create:')).length === 1,
    trace(),
  )
  check('the splat is armed before the first hit', started() === 0 && loaded() === 1, trace())

  // A hit, then another while the first is still sounding. The wait is not padding: the
  // module keeps one gap timer for the life of the app, and a scenario starting inside the
  // previous one's SPLAT_MIN_GAP_MS would have its first hit swallowed - which is what the
  // second scenario did on the first run of this file, and read as a code fault.
  await wait(120)
  media.calls.length = 0
  audio.playSplat(Date.now())
  check('the first hit starts the sample', started() === 1, trace())

  await wait(150)
  audio.playSplat(Date.now())
  check('a hit during playback retriggers from the top', sought() === 1, trace())
  check('and the sample is heard twice, not once', media.calls.includes('seek:true'))
  check('and the player is never stopped to do it', stopped() === 0, trace())

  // The hit after a sample has finished: the one that used to arrive late.
  await wait(500)
  media.calls.length = 0
  audio.playSplat(Date.now())
  check('a hit after the sample ended plays immediately', started() === 1, trace())
  check('with no reload standing in front of it', loaded() === 0, trace())

  // Sustained tapping, faster than the sample is long.
  await wait(500)
  media.calls.length = 0
  const hits = 10
  for (let i = 0; i < hits; i++) {
    audio.playSplat(Date.now())
    await wait(120)
  }
  const sounded = started() + sought()
  check('ten hits in a row all sound', sounded === hits, sounded + '/' + hits + '  ' + trace())
  check('and none of them stops the player', stopped() === 0, trace())

  // The promise, at the speed the top of the curve actually reaches: hits arriving faster
  // than the 325ms sample can play. Every one has to be heard, which on a single voice
  // means every one after the first cuts its predecessor short.
  await wait(500)
  media.calls.length = 0
  const fast = 8
  for (let i = 0; i < fast; i++) {
    audio.playSplat(Date.now())
    await wait(70)
  }
  const heard = started() + sought()
  check('eight hits inside one sample all sound', heard === fast, heard + '/' + fast + '  ' + trace())
  check('by seeking rather than taking the player apart', sought() >= fast - 1 && stopped() === 0, trace())

  // A hit the player refuses is the one case where a whack could make no noise at all.
  // It is chased instead: the file goes back in and the hit sounds when it lands, as long
  // as the fruit it belongs to is still recent.
  await wait(500)
  media.calls.length = 0
  media.config.refuseNextStart = true
  audio.playSplat(Date.now())
  check('a refused hit puts the file back', started() === 0 && loaded() === 1, trace())
  await wait(80)
  check('and is chased rather than lost', started() === 1, trace())

  // Silence, and coming back from it.
  media.calls.length = 0
  audio.setSoundOn(false)
  check('switching sound off stops the player', stopped() === 1, trace())
  audio.playSplat(Date.now())
  check('and nothing plays while it is off', started() === 0 && sought() === 0, trace())

  audio.setSoundOn(true)
  await wait(60)
  media.calls.length = 0
  audio.playSplat(Date.now())
  check('switching it back on makes hits sound again', started() === 1, trace())
}

await scenario('playback ends with the file still loaded', media.PREPARED)
await scenario('playback ends with the file unloaded', media.INITIALIZED)

// Leaving the page and coming back: the device only has one player, and it does not come
// back on the same tick that releases it.
console.log('')
console.log('--- a second run in the same session ---')
media.reset({})
audio.release()
audio.setSoundOn(true)
audio.primeSplat()
await wait(60)
check('run one gets a player', media.calls.includes('create:ok'), trace())
audio.release()
await wait(20)
media.calls.length = 0
audio.primeSplat()
await wait(60)
media.calls.length = 0
audio.playSplat(Date.now())
check('and so does run two', started() === 1, trace())

fs.rmSync(dir, { recursive: true, force: true })

console.log('')
console.log(failures ? failures + ' failed' : 'all checks passed')
process.exit(failures ? 1 : 0)
