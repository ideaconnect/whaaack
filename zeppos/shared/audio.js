/**
 * The two sounds a run makes, and the switch that silences them.
 *
 * `@zos/media` is a *media player*, not a sound bank, and the engine hands out exactly one
 * of them per app - `create` answers `undefined` for the second, logging
 * `js player instance already created, can't create new one` on the device side. So there
 * is one voice, the splat claims it, and the music is atmosphere the watch does not get:
 * of the two, the sound worth having is the one that answers a tap, because a hit that
 * makes no sound is a hit that feels like it missed. `startMusic` is left in and left
 * failing gracefully, so a future device with a second player gets its music for free.
 *
 * `release()` does give the slot back, but not on the same tick: releasing and immediately
 * re-creating still fails, while releasing on the way out of the game page and creating
 * again on the way back in works every time. That is why leaving the page still releases.
 *
 * Everything else here is shaped by three things the device does that the documentation
 * does not mention, all three established by probing a running watch:
 *
 *   1. `start()`, `seek()` and `stop()` REPORT FAILURE BY RETURNING FALSE, not by throwing.
 *      A guard that only catches exceptions sees a silent success. This was the bug behind
 *      splats that lagged and would not overlap: nothing knew they had not played.
 *
 *   2. `stop()` does not pause a loaded file, it UNLOADS it - the status drops from
 *      PREPARING/PREPARED back to INITIALIZED, and `start()` from there answers false. So
 *      "stop it and start it again" is precisely the thing that cannot be done to retrigger
 *      a sample; it takes the player apart.
 *
 *   3. `seek(percentage)` is the retrigger. It leaves the player started and moves the
 *      playhead, which is what "cut the sound short and play it again" actually means here.
 *
 * The status codes are not hard-coded, because only the first three were ever observed
 * (IDLE 0, INITIALIZED 1, PREPARING 2) and guessing the rest off the documentation's
 * ordering would be a guess in the one place that decides whether a sound plays. Instead
 * the player is asked what it reports at the one moment it is certainly loaded - inside its
 * own PREPARE handler - and that one number is remembered. Whether a sound is still
 * sounding is not read from the player at all; it is kept by the clock, because a status
 * read straight after `start()` can report a transitional state that never recurs.
 */

import { create, id } from '@zos/media'
import { localStorage } from '@zos/storage'

import { LOCAL_SOUND, LOCAL_SOUND_OK } from './protocol.js'

const MUSIC_FILE = 'music.mp3'
const SPLAT_FILE = 'splat.mp3'

/**
 * Where the two sit against each other.
 *
 * The music is well under the splat on purpose: it is the only continuous sound in a game
 * whose whole feedback loop is a short one, and a hit has to cut through it on a speaker
 * the size of a grain of rice. Both files are baked at full scale
 * (tools/generate_zepp_audio.py peak-normalises the splat), so this is the only place the
 * balance between them is decided.
 */
const MUSIC_VOLUME = 55
const SPLAT_VOLUME = 100

/**
 * Two hits closer together than this share one sound.
 *
 * Retriggering is a seek to the top, so a hit landing 30ms after the last one would cut
 * the sample to a click and the run would sound like a Geiger counter. Ninety milliseconds
 * is under the fastest tapping anyone sustains, so in practice this only fires on the
 * double-taps that land almost together - where one wetter-sounding splat is a better
 * answer than two clipped ones.
 */
const SPLAT_MIN_GAP_MS = 90

/**
 * How long to wait for a PREPARE event before playing anyway.
 *
 * The event is the documented way to know a file is loaded, and on a watch that sends it
 * this timer is cancelled and never does anything. It exists for the watch that does not -
 * the simulator is one, having no audio device to finish preparing against - because a
 * player that loads perfectly well but stays silent because nothing ever told us it was
 * ready is indistinguishable, from here, from one that cannot play at all.
 *
 * Comfortably inside the three-second countdown, so the splat is armed before the first
 * fruit is whackable either way.
 */
const PREPARE_GRACE_MS = 1500

/** True once `create` has thrown, which it does on a watch with nothing to play through. */
let noAudio = false

let music = null
let splat = null

let musicWanted = false
let lastSplatMs = 0

/**
 * What this player reports when it is loaded.
 *
 * Learned rather than declared - see the header - and read at the one instant it is
 * certain, inside the PREPARE handler. `null` until then, and every reader treats `null`
 * as "no opinion" rather than guessing.
 *
 * There is deliberately no matching reading for "playing". `getStatus()` immediately after
 * a `start()` can just as easily report STARTING as STARTED, and a number captured in a
 * transitional state would never match again - so whether a sound is still sounding is
 * kept here instead, from when it was started and how long it runs for.
 */
let readyStatus = null

/** Assumed still sounding for this long past the end, since COMPLETE may lag the audio. */
const PLAYING_SLACK_MS = 40

/** Used until `getDuration()` answers; the splat is a 325ms sample. */
const ASSUMED_DURATION_MS = 350

export function soundOn() {
  return Number(localStorage.getItem(LOCAL_SOUND, 1)) !== 0
}

export function setSoundOn(value) {
  localStorage.setItem(LOCAL_SOUND, value ? 1 : 0)
  if (value) {
    // Switching sound off stops the player, and stopping unloads it (see the header), so
    // switching it back on has to put the file back before anything will play.
    if (splat && !splat.ready) load(splat)
  } else {
    hush()
  }
}

/**
 * Whether this watch has ever actually played something.
 *
 * Recorded because the answer is only knowable on the game page, seconds after it opens,
 * and the question gets asked on the home screen - where the toggle otherwise promises
 * sound that a player has already found out they are not going to get. `0` is written only
 * when the platform refuses outright; a watch that simply has no spare player for the
 * music still counts as working, because the splats do.
 */
export function soundKnownBroken() {
  return localStorage.getItem(LOCAL_SOUND_OK, '') === '0'
}

function noteAudio(ok) {
  localStorage.setItem(LOCAL_SOUND_OK, ok ? '1' : '0')
}

/**
 * Whether a PREPARE event means "ready".
 *
 * The platform documents this two ways, and they disagree. One reference hands the
 * callback an object and tests `result.isReady`; the current docs' own example tests the
 * argument itself for truth and calls `start()` on that. A guard written for only the
 * first reads a bare `true` as a failure and never starts anything - which is total
 * silence, on every sound, with nothing in the log but a line claiming the file would not
 * prepare.
 *
 * So anything truthy counts, unless it is an object that explicitly says otherwise.
 */
function prepared(result) {
  if (!result) return false
  if (typeof result === 'object' && result.isReady !== undefined) return !!result.isReady
  return true
}

/**
 * A call that answers false rather than throwing.
 *
 * Both halves matter. `false` is how this platform reports a refusal - a `start()` in the
 * wrong state answers false and plays nothing, in silence, with no exception to catch - and
 * some firmware returns nothing at all from calls that worked, so `undefined` has to count
 * as success or every working sound would be treated as broken.
 */
function attempt(sound, label, fn) {
  try {
    return fn() !== false
  } catch (error) {
    console.log('zepp audio: ' + sound.file + ' ' + label + ' threw - ' + error)
    return false
  }
}

function statusOf(sound) {
  try {
    return sound.player.getStatus()
  } catch (error) {
    return null
  }
}

/** Claims the one player the device has, if it is going spare. */
function claim(file, volume) {
  if (noAudio) return null

  let player
  try {
    player = create(id.PLAYER)
  } catch (error) {
    console.log('zepp audio: this watch has no player - ' + error)
    noAudio = true
    noteAudio(false)
    return null
  }
  // Not a throw and not a player: the one instance is already spoken for. Only this sound
  // is lost, so nothing global is set - the player that was handed out is working.
  if (!player) {
    console.log('zepp audio: no player available for ' + file)
    return null
  }

  const sound = {
    player,
    file,
    volume,
    ready: false,
    waiting: null,
    onReady: null,
    // When this sound stops being audible, as far as anything here can tell.
    playingUntil: 0,
    durationMs: ASSUMED_DURATION_MS,
  }

  player.addEventListener(player.event.PREPARE, (result) => {
    if (!prepared(result)) {
      clearWait(sound)
      console.log('zepp audio: ' + file + ' would not prepare - ' + JSON.stringify(result))
      return
    }
    // The one moment this player is known to be loaded, which is the only way to find out
    // what "loaded" looks like to getStatus() on this device.
    if (readyStatus === null) readyStatus = statusOf(sound)
    arm(sound, 'prepared')
  })

  return sound
}

function arm(sound, why) {
  if (sound.ready) return
  clearWait(sound)
  sound.ready = true
  attempt(sound, 'setVolume', () => sound.player.setVolume(sound.volume))
  // Only answerable once the file is loaded, and only worth asking once. Seconds, per the
  // documentation; anything absurd is ignored rather than believed.
  try {
    const seconds = sound.player.getDuration()
    if (seconds > 0 && seconds < 600) sound.durationMs = Math.round(seconds * 1000)
  } catch (error) {
    // The assumed length is close enough for what it decides.
  }
  noteAudio(true)
  console.log('zepp audio: ' + sound.file + ' ready (' + why + ')')
  const then = sound.onReady
  sound.onReady = null
  if (then) then(sound)
}

/**
 * Puts the file into the player and starts it loading.
 *
 * Also the way back from a `stop()`, which unloads: the source has to be set again, not
 * merely prepared, because the player is at INITIALIZED with nothing in it.
 */
function load(sound) {
  sound.ready = false
  clearWait(sound)
  const ok =
    attempt(sound, 'setSource', () =>
      sound.player.setSource(sound.player.source.FILE, { file: sound.file }),
    ) && attempt(sound, 'prepare', () => sound.player.prepare())
  if (!ok) {
    console.log('zepp audio: ' + sound.file + ' would not load')
    noteAudio(false)
    return
  }
  sound.waiting = setTimeout(() => arm(sound, 'no PREPARE event'), PREPARE_GRACE_MS)
}

function clearWait(sound) {
  if (!sound.waiting) return
  clearTimeout(sound.waiting)
  sound.waiting = null
}

/** `start()`, and the truth about whether it started. */
function begin(sound, now) {
  if (!attempt(sound, 'start', () => sound.player.start())) return false
  sound.playingUntil = (now || Date.now()) + sound.durationMs + PLAYING_SLACK_MS
  return true
}

/** Whether this sound is still sounding, by the clock rather than by a status code. */
function isPlaying(sound, now) {
  return (now || Date.now()) < sound.playingUntil
}

/**
 * Whether the player is loaded and would accept a `start()`.
 *
 * `null` means the device never sent a PREPARE event, so there is no reading to compare
 * with; the honest answer is "try it and see", which is what `true` gets the caller.
 */
function isLoaded(sound) {
  if (readyStatus === null) return true
  return statusOf(sound) === readyStatus
}

/**
 * Starts the music, and keeps it going.
 *
 * On every device seen so far this gets nothing, because the splat has already taken the
 * only player - see the header. It is kept because the failure is free and a device with
 * two players would simply work.
 *
 * There is no loop flag in the API, so the loop is a COMPLETE handler that starts the
 * track again. That leaves an audible gap of however long a restart takes - which is why
 * the file is cut to a whole number of musical phrases with its seam crossfaded closed
 * (tools/generate_zepp_audio.py): the wrap should at least land somewhere the music was
 * going to breathe anyway.
 */
export function startMusic() {
  if (!soundOn()) return
  musicWanted = true

  if (music) {
    if (music.ready) begin(music)
    return
  }

  music = claim(MUSIC_FILE, MUSIC_VOLUME)
  if (!music) return

  // A finished track has to be re-loaded rather than merely restarted, for the same reason
  // a stopped one does: playback ending leaves the player somewhere `start()` may refuse.
  music.player.addEventListener(music.player.event.COMPLETE, () => {
    music.playingUntil = 0
    if (!musicWanted || !soundOn()) return
    if (isLoaded(music) && begin(music)) return
    music.onReady = (sound) => musicWanted && soundOn() && begin(sound)
    load(music)
  })

  // The page may be left, or the sound switched off, during the prepare.
  music.onReady = (sound) => musicWanted && soundOn() && begin(sound)
  load(music)
}

export function stopMusic() {
  musicWanted = false
  if (music) halt(music)
}

/**
 * Loads the splat sound without playing it.
 *
 * Called on the countdown so that the first hit of a run is as loud and as prompt as the
 * hundredth. Preparing on the first hit instead would silence it and land its sound on
 * whatever happened next.
 */
export function primeSplat() {
  if (!soundOn() || splat) return
  splat = claim(SPLAT_FILE, SPLAT_VOLUME)
  if (!splat) return

  // Kept armed between hits. Playback ending can leave the player in a state that refuses
  // `start()`, and finding that out on the next tap is exactly the delay this is here to
  // avoid: the reload happens in the quiet after a splat instead of in front of one.
  splat.player.addEventListener(splat.player.event.COMPLETE, () => {
    if (!splat) return
    splat.playingUntil = 0
    if (!soundOn() || isLoaded(splat)) return
    load(splat)
  })

  load(splat)
}

/**
 * A whack.
 *
 * A hit landing while the last splat is still sounding retriggers it from the top rather
 * than queueing behind it or being dropped - one voice is all the device has, so the
 * newest hit gets it. `seek` and not `stop()`+`start()`: stopping unloads the file and
 * leaves `start()` answering false, which is what made hits lag and then fall silent.
 *
 * Fire and forget past that. Nothing here is worth failing a tap over.
 */
export function playSplat(now) {
  if (!soundOn() || !splat || !splat.ready) return
  if (now - lastSplatMs < SPLAT_MIN_GAP_MS) return
  lastSplatMs = now

  if (isPlaying(splat, now) && attempt(splat, 'seek', () => splat.player.seek(0))) {
    splat.playingUntil = now + splat.durationMs + PLAYING_SLACK_MS
    return
  }
  if (begin(splat, now)) return

  // Refused: the player is not where it was thought to be. Put the file back so the next
  // hit is prompt - and deliberately do not chase this one, because a splat that arrives
  // a third of a second after the fruit it belongs to is worse than a splat that is missed.
  load(splat)
}

/** Stops a sound, which on this platform also unloads it. */
function halt(sound) {
  attempt(sound, 'stop', () => sound.player.stop())
  sound.ready = false
  sound.playingUntil = 0
  clearWait(sound)
}

function hush() {
  musicWanted = false
  if (music) halt(music)
  if (splat) halt(splat)
}

/**
 * Hands the hardware back.
 *
 * A page that leaves without doing this keeps the audio route open, which on a watch is
 * both a battery cost and a music player that carries on into whatever the player looks at
 * next. It also hands back the one instance the device has - not immediately, since
 * releasing and re-creating on the same tick still fails, but by the time the player has
 * walked back to the home screen and pressed Play again it is free, which is the only
 * timing that matters.
 */
export function release() {
  musicWanted = false
  for (const sound of [splat, music]) {
    if (!sound) continue
    halt(sound)
    attempt(sound, 'release', () => sound.player.release())
  }
  music = null
  splat = null
}
