/**
 * The one sound a run makes, and the switch that silences it.
 *
 * There used to be music as well, and it never once played. `@zos/media` hands out exactly
 * one player per app - the second `create` returns `undefined`, logging
 * `js player instance already created, can't create new one` on the device side - and the
 * splat rightly claimed it, so the music lost that race on every device, on every run. A
 * 206KB file, a loop crossfaded closed on a musical phrase and a restart-on-COMPLETE
 * handler, all of it dead weight in the package and dead code here. It is gone. This module
 * is one player playing one sample.
 *
 * That sample has to be *reliable*, which on a single voice means one thing: a whack
 * landing while the last splat is still sounding cuts it short and starts again. It does
 * not queue behind it, and it is not dropped. Everything below serves that, and is shaped
 * by three things the device does that the documentation does not mention, all three
 * established by probing a running watch:
 *
 *   1. `start()`, `seek()` and `stop()` REPORT FAILURE BY RETURNING FALSE, not by throwing.
 *      A guard that only catches exceptions sees a silent success. This was the bug behind
 *      splats that lagged and would not overlap: nothing knew they had not played.
 *
 *   2. `stop()` does not pause a loaded file, it UNLOADS it - the status drops from
 *      PREPARED back to INITIALIZED, and `start()` from there answers false. So "stop it
 *      and start it again" is precisely the thing that cannot be done to retrigger a
 *      sample; it takes the player apart. Nothing on the whack path stops the player.
 *
 *   3. `seek(percentage)` is the retrigger. It leaves the player started and moves the
 *      playhead, which is what "cut the sound short and play it again" actually means here.
 *
 * The status codes are not hard-coded. Only three were ever observed (IDLE 0,
 * INITIALIZED 1, PREPARING 2) and guessing the rest off the documentation's ordering would
 * be a guess in the one place that decides whether a sound plays, so the player is asked
 * what it reports at the one moment it is certainly loaded - inside its own PREPARE
 * handler - and that number is remembered. Whether the sample is still sounding is not read
 * from the player at all; it is kept by the clock, because a status read straight after
 * `start()` can report a transitional state that never recurs.
 *
 * `release()` does give the one instance back, but not on the same tick: releasing and
 * re-creating immediately still fails, while releasing on the way out of the game page and
 * creating again on the way back in works every time.
 *
 * tools/check-audio.mjs runs all of this against a fake device built from that probe,
 * because the simulator cannot: it has no audio device, so `prepare()` never leaves
 * PREPARING and every path past "the file is loaded" is unreachable there.
 */

import { create, id } from '@zos/media'
import { localStorage } from '@zos/storage'

import { LOCAL_SOUND, LOCAL_SOUND_OK } from './protocol.js'

const SPLAT_FILE = 'splat.mp3'
const SPLAT_VOLUME = 100

/**
 * A floor under how often the sample may restart, not a policy about merging hits.
 *
 * Two taps on two different tiles cannot land 50ms apart on a wrist, so in an actual run
 * this never fires; it is here so that a repeated call within one frame, or a double-fire
 * out of the touch layer, cannot chop the sample into a click. It used to be 90ms and it
 * used to be a policy - "two hits closer than this share one sound" - which was the right
 * trade only while retriggering was broken. A whack that lands is a whack to be heard.
 */
const SPLAT_MIN_GAP_MS = 50

/**
 * How long a whack is still worth making a noise about.
 *
 * Only reached when the player refuses a start and the file has to go back in - which the
 * COMPLETE handler exists to prevent, and which should therefore be rare. When it does
 * happen the hit is chased rather than dropped, but not indefinitely: a splat arriving a
 * third of a second after the fruit it belongs to reads as a fault in the game rather than
 * as feedback, so past this it is let go.
 */
const CHASE_MS = 140

/** Assumed still sounding for this long past the end, since COMPLETE may lag the audio. */
const PLAYING_SLACK_MS = 40

/** Used until `getDuration()` answers; the splat is a 325ms sample. */
const ASSUMED_DURATION_MS = 350

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

let splat = null
let lastSplatMs = 0

/**
 * What this player reports when it is loaded.
 *
 * Learned rather than declared, and read at the one instant it is certain: inside the
 * PREPARE handler. `null` until then, and every reader treats `null` as "no opinion".
 */
let readyStatus = null

export function soundOn() {
  return Number(localStorage.getItem(LOCAL_SOUND, 1)) !== 0
}

export function setSoundOn(value) {
  localStorage.setItem(LOCAL_SOUND, value ? 1 : 0)
  if (!value) {
    hush()
    return
  }
  // Switching sound off stops the player, and stopping unloads it, so switching it back on
  // has to put the file back before anything will play.
  if (splat && !splat.ready) load(splat)
}

/**
 * Whether this watch has ever actually played something.
 *
 * Recorded because the answer is only knowable on the game page, seconds after it opens,
 * and the question gets asked on the home screen - where the toggle otherwise promises
 * sound that a player has already found out they are not going to get.
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
 * silence, with nothing in the log but a line claiming the file would not prepare.
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
function attempt(label, fn) {
  try {
    return fn() !== false
  } catch (error) {
    console.log('zepp audio: ' + label + ' threw - ' + error)
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

/**
 * Loads the splat without playing it.
 *
 * Called on the countdown so that the first hit of a run is as loud and as prompt as the
 * hundredth. Preparing on the first hit instead would silence it and land its sound on
 * whatever happened next.
 */
export function primeSplat() {
  if (!soundOn() || splat || noAudio) return

  let player
  try {
    player = create(id.PLAYER)
  } catch (error) {
    console.log('zepp audio: this watch has no player - ' + error)
    noAudio = true
    noteAudio(false)
    return
  }
  // Not a throw and not a player. With the music gone, nothing else in this app asks for
  // one - so this is something outside the game holding the device's only instance, and it
  // says nothing about whether this watch can play audio. Deliberately not recorded as a
  // refusal: the home screen must not start claiming the watch is mute because of it.
  if (!player) {
    console.log('zepp audio: no player available')
    return
  }

  splat = {
    player,
    ready: false,
    waiting: null,
    playingUntil: 0,
    chaseUntil: 0,
    durationMs: ASSUMED_DURATION_MS,
  }

  player.addEventListener(player.event.PREPARE, (result) => {
    if (!splat) return
    if (!prepared(result)) {
      clearWait(splat)
      console.log('zepp audio: splat would not prepare - ' + JSON.stringify(result))
      return
    }
    // The one moment this player is known to be loaded, and so the only chance to learn
    // what "loaded" looks like to getStatus() on this device.
    if (readyStatus === null) readyStatus = statusOf(splat)
    arm(splat, 'prepared')
  })

  // Kept armed between hits. Playback ending can leave the player somewhere that refuses
  // `start()`, and finding that out on the next tap is exactly the delay this exists to
  // avoid: the reload happens in the quiet after a splat rather than in front of one.
  player.addEventListener(player.event.COMPLETE, () => {
    if (!splat) return
    splat.playingUntil = 0
    if (!soundOn() || isLoaded(splat)) return
    load(splat)
  })

  load(splat)
}

function arm(sound, why) {
  if (sound.ready) return
  clearWait(sound)
  sound.ready = true
  attempt('setVolume', () => sound.player.setVolume(SPLAT_VOLUME))
  // Only answerable once the file is loaded, and only worth asking once. Seconds, per the
  // documentation; anything absurd is ignored rather than believed.
  try {
    const seconds = sound.player.getDuration()
    if (seconds > 0 && seconds < 600) sound.durationMs = Math.round(seconds * 1000)
  } catch (error) {
    // The assumed length is close enough for what it decides.
  }
  noteAudio(true)
  console.log('zepp audio: splat ready (' + why + ')')

  // A whack refused while this was loading, still recent enough to belong to the fruit
  // that caused it.
  const now = Date.now()
  const chasing = now < sound.chaseUntil
  sound.chaseUntil = 0
  if (chasing) begin(sound, now)
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
    attempt('setSource', () =>
      sound.player.setSource(sound.player.source.FILE, { file: SPLAT_FILE }),
    ) && attempt('prepare', () => sound.player.prepare())
  if (!ok) {
    console.log('zepp audio: splat would not load')
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
  if (!attempt('start', () => sound.player.start())) return false
  sound.playingUntil = now + sound.durationMs + PLAYING_SLACK_MS
  return true
}

/** Whether the sample is still sounding, by the clock rather than by a status code. */
function isPlaying(sound, now) {
  return now < sound.playingUntil
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
 * A whack.
 *
 * One voice, so the newest hit takes it: a whack landing while the last splat is sounding
 * seeks back to the top rather than queueing behind it or being dropped. `seek` and not
 * `stop()`+`start()` - stopping unloads the file and leaves `start()` answering false,
 * which is what made hits lag and then fall silent.
 *
 * Fire and forget past that. Nothing here is worth failing a tap over.
 */
export function playSplat(now) {
  if (!soundOn() || !splat || !splat.ready) return
  if (now - lastSplatMs < SPLAT_MIN_GAP_MS) return
  lastSplatMs = now

  if (isPlaying(splat, now) && attempt('seek', () => splat.player.seek(0))) {
    splat.playingUntil = now + splat.durationMs + PLAYING_SLACK_MS
    return
  }
  if (begin(splat, now)) return

  // Refused: the player is not where it was thought to be. Put the file back, and play
  // this hit when it lands if the fruit it belongs to is still recent - see CHASE_MS.
  splat.chaseUntil = now + CHASE_MS
  load(splat)
}

/** Stops the sample, which on this platform also unloads it. */
function halt(sound) {
  attempt('stop', () => sound.player.stop())
  sound.ready = false
  sound.playingUntil = 0
  sound.chaseUntil = 0
  clearWait(sound)
}

function hush() {
  if (splat) halt(splat)
}

/**
 * Hands the hardware back.
 *
 * A page that leaves without doing this keeps the audio route open, which on a watch is a
 * battery cost. It also hands back the one player instance the device has - not
 * immediately, since releasing and re-creating on the same tick still fails, but by the
 * time the player has walked back to the home screen and pressed Play again it is free,
 * which is the only timing that matters.
 */
export function release() {
  if (splat) {
    halt(splat)
    attempt('release', () => splat.player.release())
  }
  splat = null
}
