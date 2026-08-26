/**
 * The two sounds a run makes, and the switch that silences them.
 *
 * `@zos/media` is a *media player*, not a sound bank. Each instance holds one source at a
 * time, `setSource` has to be followed by an asynchronous `prepare()`, and only then does
 * `start()` play anything. That shapes everything below:
 *
 *   - one player per sound rather than one player switched between them, because
 *     switching means re-preparing and a run whacks fruit five times a second;
 *   - both loaded during the countdown, so the first hit of a run plays a sound already
 *     in memory instead of waiting on a file system;
 *   - one splat sound rather than the phone's nine, because picking a different file per
 *     hit is exactly the re-prepare this design cannot afford. The variety the phone puts
 *     in the audio is put in the sprites instead - thirty-six of them.
 *
 * Players are not guaranteed, and there are two quite different ways to be refused. A
 * device with no speaker throws out of `create`, and there is nothing more to try: the
 * game plays silently from then on. A device that *has* audio but will only hand out one
 * player at a time returns `undefined` from the second `create` - no throw, no message -
 * and that must not be read as "this watch has no sound", because the first player it
 * gave out is working perfectly.
 *
 * Which is why the splat is claimed before the music. If only one player exists on a
 * watch, the sound worth having is the one that answers a tap: the music is atmosphere
 * and its absence is a quiet game, where a hit that makes no sound is a hit that feels
 * like it missed.
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
 * At the top of the curve the board can be cleared faster than a 325ms sample can play,
 * and restarting a player mid-sample gives a stutter rather than a second splat. Ninety
 * milliseconds is under the fastest tapping anyone sustains, so in practice this only
 * fires on the double-taps that land almost together - where one wetter-sounding splat is
 * a better answer than two clipped ones.
 */
const SPLAT_MIN_GAP_MS = 90

/**
 * How long to wait for a PREPARE event before playing anyway.
 *
 * The event is the documented way to know a file is loaded, and on a watch that sends it
 * this timer is cancelled and never does anything. It exists for the watch that does not:
 * a player that loads perfectly well but stays silent because nothing ever told us it was
 * ready is indistinguishable, from here, from one that cannot play at all - and of the two
 * possible mistakes, starting a sound that was not quite ready is much the smaller.
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

export function soundOn() {
  return Number(localStorage.getItem(LOCAL_SOUND, 1)) !== 0
}

export function setSoundOn(value) {
  localStorage.setItem(LOCAL_SOUND, value ? 1 : 0)
  if (!value) hush()
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
 * A sound: a player, the file behind it, and whether it is loaded and startable.
 *
 * Kept as a small record rather than as the bare player because `prepare()` can have to
 * be run again - after a `stop()`, a player may be back to needing one before it will
 * start - and doing that means still knowing what the source and the volume were.
 */
function load(file, volume, onReady, onComplete) {
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
  // Not a throw and not a player: the watch has audio but no spare instance. Only this
  // sound is lost, so nothing global is set and the next caller may still be served.
  if (!player) {
    console.log('zepp audio: no player available for ' + file)
    return null
  }

  const sound = { player, file, volume, ready: false, waiting: null }

  const arm = (why) => {
    if (sound.ready) return
    clearWait(sound)
    sound.ready = true
    try {
      player.setVolume(volume)
    } catch (error) {
      // Volume is a nicety; a player that will not take one still plays.
      console.log('zepp audio: no volume control - ' + error)
    }
    noteAudio(true)
    console.log('zepp audio: ' + file + ' ready (' + why + ')')
    if (onReady) onReady(sound)
  }

  player.addEventListener(player.event.PREPARE, (result) => {
    if (!prepared(result)) {
      clearWait(sound)
      console.log('zepp audio: ' + file + ' would not prepare - ' + JSON.stringify(result))
      return
    }
    arm('prepared')
  })
  if (onComplete) player.addEventListener(player.event.COMPLETE, () => onComplete(sound))

  prepare(sound, arm)
  return sound
}

/**
 * Loads the file. `setSource` before the listeners are attached would be the other order
 * the platform's examples show; this one attaches first so a PREPARE that arrives
 * synchronously cannot land before anything is listening.
 */
function prepare(sound, arm) {
  sound.ready = false
  clearWait(sound)
  try {
    sound.player.setSource(sound.player.source.FILE, { file: sound.file })
    sound.player.prepare()
  } catch (error) {
    console.log('zepp audio: ' + sound.file + ' would not load - ' + error)
    noteAudio(false)
    return
  }
  if (arm) sound.waiting = setTimeout(() => arm('no PREPARE event'), PREPARE_GRACE_MS)
}

function clearWait(sound) {
  if (!sound.waiting) return
  clearTimeout(sound.waiting)
  sound.waiting = null
}

/**
 * Starts the music, and keeps it going.
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
    if (music.ready) play(music)
    return
  }

  music = load(
    MUSIC_FILE,
    MUSIC_VOLUME,
    // The page may have been left, or the sound switched off, during the prepare.
    (sound) => musicWanted && soundOn() && play(sound),
    (sound) => musicWanted && soundOn() && play(sound),
  )
}

export function stopMusic() {
  musicWanted = false
  if (music) stop(music)
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
  splat = load(SPLAT_FILE, SPLAT_VOLUME)
}

/** A whack. Fire and forget: nothing here is worth failing a tap over. */
export function playSplat(now) {
  if (!soundOn() || !splat || !splat.ready) return
  if (now - lastSplatMs < SPLAT_MIN_GAP_MS) return
  lastSplatMs = now
  // Stopped first because a player already part-way through the sample ignores `start()`,
  // and in a fast exchange that would silence every second hit.
  stop(splat)
  play(splat)
}

function play(sound) {
  try {
    sound.player.start()
  } catch (error) {
    console.log('zepp audio: ' + sound.file + ' would not start - ' + error)
    // A player can come back from `stop()` needing to be prepared again. Reloading is the
    // only way back, and it is cheap next to giving up on the sound for the rest of the
    // run - but it happens at most once per failure, because `ready` is false until it is
    // armed again and nothing calls this until it is.
    prepare(sound, null)
  }
}

function stop(sound) {
  try {
    sound.player.stop()
  } catch (error) {
    // Nothing depends on a stop having worked, and the caller is usually about to start.
  }
}

function hush() {
  musicWanted = false
  if (music) stop(music)
  if (splat) stop(splat)
}

/**
 * Hands the hardware back.
 *
 * A page that leaves without doing this keeps the audio route open, which on a watch is
 * both a battery cost and a music player that carries on into whatever the player looks
 * at next. It also hands back the *instance*, which on a watch that only has one matters
 * to whatever wants to play something after this.
 */
export function release() {
  musicWanted = false
  for (const sound of [splat, music]) {
    if (!sound) continue
    clearWait(sound)
    sound.ready = false
    stop(sound)
    try {
      sound.player.release()
    } catch (error) {
      console.log('zepp audio: release failed - ' + error)
    }
  }
  music = null
  splat = null
}
