/**
 * The one sound a run makes, and the switch that silences it.
 *
 * `@zos/media` hands out exactly one player per app - the second `create` returns
 * `undefined`, logging `js player instance already created, can't create new one` on the
 * device side - and there is no mixing. One voice, one sample, and no way to lay a second
 * whack over the first. Everything here follows from that.
 *
 * There used to be music too. It never once played: the splat rightly claimed the only
 * player, so the music lost that race on every device and on every run while costing 206KB
 * of the package. It is gone.
 *
 * Things the device does that the documentation does not mention, all found by probing a
 * running watch:
 *
 *   1. `start()`, `seek()` and `stop()` REPORT FAILURE BY RETURNING FALSE, not by throwing.
 *      A guard that only catches exceptions sees a silent success.
 *
 *   2. `stop()` does not pause a loaded file, it UNLOADS it - the status drops back to
 *      INITIALIZED, and `start()` from there answers false. So "stop it and start it
 *      again" is precisely the thing that cannot be done to retrigger a sample; it takes
 *      the player apart and the way back is a reload, which is where the original lag came
 *      from.
 *
 *   3. `seek(0)` answers **true** and does not cut the sound short. It is not used here.
 *      The obvious reading of the API - retrigger by moving the playhead - looks right,
 *      returns success, and leaves the speaker doing exactly what it was doing. That cost a
 *      release: hits landing during a splat were reported as retriggered and were silent.
 *
 * So a whack that lands while the voice is busy cannot interrupt it, and the two honest
 * options are to drop it or to hold it. It is held: `pendingAt` remembers it and it sounds
 * the moment the voice frees, which is at most a sample away.
 *
 * Only ever one, which is the part worth being straight about. A burst of three fruit
 * inside a single sample makes two splats and not three - the one that started it, and the
 * most recent of the rest. A full queue would answer every whack and would spend the next
 * half-second draining into fruit that had nothing to do with it, which is a worse noise
 * than the one it replaced. What is promised is that the run does not go quiet, not that
 * every hit is scored.
 *
 * Which is why the sample is 150ms rather than the 325ms the phone uses. Its length is
 * exactly the length of time a whack cannot be heard, and the source turned out to be two
 * impacts rather than one - so the tail past the first is cut (see
 * tools/generate_zepp_audio.py). At 150ms two ordinary taps no longer collide at all, and
 * shortening the sound did more for this than any amount of cleverness with the player.
 *
 * The status codes are not hard-coded. Only three were ever observed (IDLE 0,
 * INITIALIZED 1, PREPARING 2) and guessing the rest off the documentation's ordering would
 * be a guess in the one place that decides whether a sound plays, so the player is asked
 * what it reports at the one moment it is certainly loaded - inside its own PREPARE
 * handler. Whether the sample is still sounding is not read from the player at all; it is
 * kept by the clock, because a status read straight after `start()` can report a
 * transitional state that never recurs.
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
 * A floor under how often the sample may start, not a policy about merging hits.
 *
 * Two taps on two different tiles cannot land 40ms apart on a wrist, so in a real run this
 * never fires; it is here so that a repeated call within one frame, or a double-fire out of
 * the touch layer, cannot machine-gun the player. It used to be 90ms and it used to decide
 * which hits were heard, which is not a decision this should be making now that the sample
 * is shorter than the gap between two whacks.
 */
const SPLAT_MIN_GAP_MS = 40

/**
 * How long a whack is still worth making a noise about.
 *
 * A hit that arrives while the voice is busy waits for it - at most one sample, so about
 * 150ms - and a hit that arrives while the file is being reloaded waits for that. Past
 * this it is let go, because a splat arriving a quarter of a second after the fruit it
 * belongs to reads as a fault in the game rather than as feedback.
 */
const PENDING_MS = 200

/** Assumed still sounding for this long past the end, since COMPLETE may lag the audio. */
const PLAYING_SLACK_MS = 40

/**
 * How long to leave a refused reload before asking again.
 *
 * Nothing else comes back to a record whose reload failed: `ready` is false, the grace
 * timer was cleared on the way in, and every later whack turns round at the top of
 * `playSplat` before it can reach the recovery. Without this the game is mute for the rest
 * of the visit after one bad moment - which is what shipped, and what nothing noticed,
 * because the watch went on saying "Sound on" the whole time.
 */
const RELOAD_RETRY_MS = 250

/**
 * The grace given to a *reload* on a watch that has never sent a PREPARE event.
 *
 * The full 1500ms is for the first load on an unknown watch, where the question is whether
 * this device sends the event at all. A reload is not that question: the file loaded a
 * moment ago, and 1500ms of `ready === false` outlasts every whack in the meantime -
 * PENDING_MS is 200, so even a held one is thrown away long before the grace expires.
 */
const RELOAD_GRACE_MS = 120

/** Used until `getDuration()` answers; the splat is a 150ms sample. */
const ASSUMED_DURATION_MS = 160

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

/**
 * Whether this watch sends PREPARE events at all.
 *
 * Only knowable in the negative, and only after the fact: it stays false on a watch that
 * has armed purely off the grace timer. Used to shorten the grace on reloads - see
 * RELOAD_GRACE_MS.
 */
let sawPrepareEvent = false

/**
 * Whether this player has ever been loaded and ready.
 *
 * Guards the one place a *later* failure could libel a working watch. `load` runs again
 * whenever a start is refused, and on a watch that never sends a PREPARE event it can be
 * asked to set a source on a player still busy with the last one and answer no - which is
 * a bad moment, not a mute device. Without this, that moment writes `soundOk = 0` and the
 * home screen starts saying "No sound" about a watch whose splat was armed and audible a
 * second earlier.
 */
let everArmed = false

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
    // A whack that has not been heard yet, and the timer that will get to it.
    pendingAt: 0,
    pending: null,
    durationMs: ASSUMED_DURATION_MS,
    // Which playback a COMPLETE belongs to. Counted rather than timed, because the clock
    // cannot tell a late event for the last sample from an on-time one for this sample.
    starts: 0,
    completes: 0,
  }

  player.addEventListener(player.event.PREPARE, (result) => {
    if (!splat) return
    sawPrepareEvent = true
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
    splat.completes++
    // A COMPLETE lagging the audio by more than the slack arrives when a *later* sample is
    // already sounding, and everything below assumes nothing is playing: `isLoaded` would
    // read a started player, answer "not loaded", and set a new source underneath a sample
    // that is still audible. This event belongs to a playback that is already over.
    if (splat.completes < splat.starts) return
    splat.playingUntil = 0
    if (!soundOn()) return
    if (!isLoaded(splat)) {
      load(splat)
      return
    }
    // The voice is free a little earlier than the clock predicted. Anything waiting on it
    // should not sit through the slack.
    flush(splat)
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
  everArmed = true
  noteAudio(true)
  console.log('zepp audio: splat ready (' + why + ')')
  flush(sound)
}

/**
 * Puts the file into the player and starts it loading.
 *
 * Also the way back from a `stop()`, which unloads: the source has to be set again, not
 * merely prepared, because the player is at INITIALIZED with nothing in it.
 */
function load(sound) {
  sound.ready = false
  sound.starts = 0
  sound.completes = 0
  clearWait(sound)
  const ok =
    attempt('setSource', () =>
      sound.player.setSource(sound.player.source.FILE, { file: SPLAT_FILE }),
    ) && attempt('prepare', () => sound.player.prepare())
  if (!ok) {
    console.log('zepp audio: splat would not load')
    // A watch that has never managed this is one that cannot play it, and there is nothing
    // to be gained by asking it again every quarter second. See `everArmed`: a reload
    // refused mid-run says nothing about the speaker.
    if (!everArmed) {
      noteAudio(false)
      return
    }
    // Ask again. `sound.waiting` on purpose: `clearWait` already runs in `load`, `halt`,
    // `hush` and `release`, so leaving the page or switching sound off cancels this.
    sound.waiting = setTimeout(() => load(sound), RELOAD_RETRY_MS)
    return
  }
  const grace = everArmed && !sawPrepareEvent ? RELOAD_GRACE_MS : PREPARE_GRACE_MS
  sound.waiting = setTimeout(() => arm(sound, 'no PREPARE event'), grace)
}

function clearWait(sound) {
  if (!sound.waiting) return
  clearTimeout(sound.waiting)
  sound.waiting = null
}

/** `start()`, and the truth about whether it started. */
function begin(sound, now) {
  if (!attempt('start', () => sound.player.start())) return false
  sound.starts++
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
 * Remembers a whack the voice was too busy to make a noise about, and comes back to it.
 *
 * Only ever one: a second whack arriving while one is already waiting replaces it, because
 * what a player wants to hear at that point is the game keeping up, not a backlog draining
 * into the next run.
 */
function hold(sound, now) {
  sound.pendingAt = now
  if (sound.pending) return
  // From the present, because `setTimeout` counts from the present and `now` may be the
  // timestamp of a whack that has already spent part of its wait. Measuring from it lands
  // the fallback late by exactly the time already waited, and PENDING_MS then discards a
  // whack whose voice was free well before the deadline this meant to set.
  const wait = Math.max(0, sound.playingUntil - Date.now())
  sound.pending = setTimeout(() => {
    sound.pending = null
    flush(sound)
  }, wait + 10)
}

/**
 * Plays whatever was waiting, if it is still recent enough to belong to its fruit.
 *
 * Called from all three places the situation can change: the voice going quiet, the file
 * finishing loading, and the timer that covers the case where neither says so.
 *
 * The order of the guards is the whole of it. "Not loaded yet" is not a reason to throw a
 * whack away - it is the reason it is waiting - so it returns and leaves `pendingAt`
 * standing for `arm` to pick up. Clearing it there is what made a refused hit vanish: the
 * timer fired ten milliseconds into a twenty-millisecond load, found the player not ready,
 * and dropped the very whack the reload had been started for.
 */
function flush(sound) {
  const at = Date.now()
  if (!sound.pendingAt) return
  if (!soundOn() || at - sound.pendingAt > PENDING_MS) {
    sound.pendingAt = 0
    return
  }
  if (!sound.ready) return
  if (isPlaying(sound, at)) {
    hold(sound, sound.pendingAt)
    return
  }
  if (begin(sound, at)) {
    sound.pendingAt = 0
    return
  }
  // Refused, exactly as in `playSplat`: put the file back and let this whack sound when it
  // lands, if the fruit it belongs to is still recent. Re-holding at its own timestamp
  // re-dates nothing, so PENDING_MS still governs how long it may wait.
  hold(sound, sound.pendingAt)
  load(sound)
}

/**
 * A whack.
 *
 * One voice and no mixing, so a hit landing while the last splat is still sounding cannot
 * play over it and cannot cut it short - `seek(0)` claims to and does not, see the header.
 * It is held instead and sounds the moment the voice frees, which is at most a sample
 * away: with a 150ms sample, three fruit smacked in quick succession make three splats in
 * quick succession rather than one.
 *
 * Fire and forget past that. Nothing here is worth failing a tap over.
 */
export function playSplat(now) {
  if (!soundOn() || !splat) return
  if (now - lastSplatMs < SPLAT_MIN_GAP_MS) return
  lastSplatMs = now

  // "The file is reloading" is a reason to wait, for the same reason "the voice is busy"
  // is. On a watch where playback unloads, a reload follows every single splat, so a guard
  // that turned these away dropped every tap landing in the hole after a sound - which is
  // most of them, at the pace the top of the curve reaches.
  if (!splat.ready || isPlaying(splat, now)) {
    hold(splat, now)
    return
  }
  if (begin(splat, now)) return

  // Refused: the player is not where it was thought to be. Put the file back, and let this
  // whack sound when it lands if the fruit it belongs to is still recent.
  hold(splat, now)
  load(splat)
}

/** Stops the sample, which on this platform also unloads it. */
function halt(sound) {
  attempt('stop', () => sound.player.stop())
  sound.ready = false
  sound.starts = 0
  sound.completes = 0
  sound.playingUntil = 0
  sound.pendingAt = 0
  if (sound.pending) {
    clearTimeout(sound.pending)
    sound.pending = null
  }
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
