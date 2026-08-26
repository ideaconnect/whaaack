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
const storage = await import(pathToFileURL(path.join(HERE, 'fake-storage.js')).href)
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

  // A hit landing mid-sample cannot play over it and cannot cut it short, so it is held.
  // What must not happen is what used to: `seek(0)` reporting success and the whack
  // vanishing.
  await wait(60)
  audio.playSplat(Date.now())
  check('a hit during playback is not lost', started() === 1, 'not yet: ' + trace())
  await wait(200)
  check('it sounds as soon as the voice frees', started() === 2, trace())
  check('and the player is never stopped to do it', stopped() === 0, trace())

  // The hit after a sample has finished: the one that used to arrive late.
  await wait(500)
  media.calls.length = 0
  audio.playSplat(Date.now())
  check('a hit after the sample ended plays immediately', started() === 1, trace())
  check('with no reload standing in front of it', loaded() === 0, trace())

  // The hole after a sample. Where playback unloads, a reload follows every splat, and a
  // tap landing inside it used to be turned away at the top of playSplat and lost. The
  // pace matters: the suite's other loops tap at 220ms and burst at 50ms, which straddle
  // this window without ever landing in it.
  await wait(400)
  media.calls.length = 0
  audio.playSplat(Date.now())
  await wait(160)
  audio.playSplat(Date.now())
  await wait(400)
  check('a whack landing while the file reloads is not lost', started() === 2, started() + '/2  ' + trace())

  // Sustained tapping at a pace a wrist actually holds. The sample is 150ms, so hits this
  // far apart never meet each other and every one gets a clean start of its own.
  await wait(500)
  media.calls.length = 0
  const hits = 10
  for (let i = 0; i < hits; i++) {
    audio.playSplat(Date.now())
    await wait(220)
  }
  await wait(250)
  check('ten hits in a row all sound', started() === hits, started() + '/' + hits + '  ' + trace())
  check('and none of them stops the player', stopped() === 0, trace())

  // Three fruit smacked inside one sample - the case reported as "only the first sounds".
  //
  // One voice, no mixing, and nothing that can cut a sample short, so three cannot become
  // three noises at the moments they happened. What is promised is narrower and is the
  // whole of what the platform allows: the run does not go quiet. The first sounds at once,
  // the most recent of the rest sounds as soon as the voice frees, and only one whack is
  // ever held - a backlog draining into the next second would be worse than the silence it
  // replaced.
  await wait(400)
  media.calls.length = 0
  for (let i = 0; i < 3; i++) {
    audio.playSplat(Date.now())
    await wait(50)
  }
  await wait(600)
  check('a burst of three is not answered by one splat', started() >= 2, started() + '  ' + trace())
  check('and never by more than the two it can fit', started() <= 2, started() + '  ' + trace())
  check('and none of them takes the player apart', stopped() === 0, trace())

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

// What the app is allowed to conclude about a watch from one bad moment.
//
// `soundOk` drives the home screen's toggle, which says "No sound" instead of going on
// offering something the watch has already refused. It is a claim about the hardware, so
// only the hardware refusing outright may write it - and a reload is not that. A reload
// runs whenever a start is refused, and on a watch that never sends a PREPARE event it can
// be asked to set a source on a player still busy with the last one and answer no. Letting
// that write `0` libels a watch whose splat was audible a second earlier, and the label
// then sticks until the next successful run.
console.log('')
console.log('--- one bad reload is not a mute watch ---')
media.reset({})
audio.release()
audio.setSoundOn(true)
audio.primeSplat()
await wait(60)
check('a watch that loaded once is recorded as working', storage.peek('soundOk') === '1', String(storage.peek('soundOk')))

media.config.refuseNextStart = true
media.config.refuseSetSource = true
audio.playSplat(Date.now())
await wait(60)
check(
  'and a refused reload does not take that back',
  storage.peek('soundOk') === '1',
  String(storage.peek('soundOk')),
)

// And - the part that matters more - the game is not mute from then on.
//
// This block used to stop at the line above: it drove the module into the stuck state and
// then only asked whether the watch had been libelled. It had not. It was also silent for
// the rest of the visit, because the failed reload cleared `ready` and the grace timer and
// left nothing to rearm, so every later whack turned round at the top of playSplat. The
// watch went on saying "Sound on" throughout, which is why nothing noticed.
media.config.refuseSetSource = false
await wait(600)
media.calls.length = 0
audio.playSplat(Date.now())
await wait(120)
check('and sound comes back once the device does', started() === 1, trace())

// A watch that could never load it at all is a different matter, and must be recorded.
//
// A second instance of the module, because "has this ever worked" is per app session and
// the instance above has already answered yes. Importing the same file under a different
// URL is how you get a session that has not.
audio.release()
media.reset({ refuseSetSource: true })
storage.clearAll()
const freshAudio = await import(pathToFileURL(file).href + '?session=2')
freshAudio.setSoundOn(true)
freshAudio.primeSplat()
await wait(60)
check(
  'but a watch that never loaded it is',
  storage.peek('soundOk') === '0',
  String(storage.peek('soundOk')),
)
freshAudio.release()

// A watch that loads the file but never says so.
//
// PREPARE_GRACE_MS exists for exactly this device and nothing exercised it. It is the
// unluckiest combination in the file: with no event, `readyStatus` is never learned, so
// `isLoaded` has no opinion, the COMPLETE handler stops reloading, and the reload that
// follows a refused start blanked `ready` for the full 1500ms grace - six times PENDING_MS,
// so even a held whack expired before the player came back. Ten taps at a pace the suite
// asserts must all sound scored two.
console.log('')
console.log('--- a watch that never sends a PREPARE event ---')
media.reset({ noPrepareEvent: true, stateAfterComplete: media.INITIALIZED })
audio.release()
storage.clearAll()
const quietAudio = await import(pathToFileURL(file).href + '?session=3')
quietAudio.setSoundOn(true)
quietAudio.primeSplat()
await wait(1700)
check('it is armed by the grace timer', media.calls.includes('setVolume:true'), trace())
media.calls.length = 0
const taps = 10
for (let i = 0; i < taps; i++) {
  quietAudio.playSplat(Date.now())
  await wait(220)
}
await wait(400)
check('and most of ten taps still sound', started() >= 8, started() + '/' + taps)
quietAudio.release()

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
