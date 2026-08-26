/**
 * A stand-in for `@zos/media`, modelled on what a real player was observed doing.
 *
 * Used by check-audio.mjs. Every rule marked OBSERVED came out of a probe run against the
 * simulator's own engine and can be read back in its log (sim-debug.log):
 *
 *     js_player_constructor js player instance already created, can't create new one
 *     js_player_seek not support at state 0
 *     js_player_get_status status 0 / 1 / 2      (IDLE / INITIALIZED / PREPARING)
 *
 * and, crucially, that `start()`, `seek()` and `stop()` answer **false** in the wrong state
 * rather than throwing, and that `stop()` drops the player to INITIALIZED - it unloads the
 * file rather than pausing it.
 *
 * What a simulator with no audio device can never show is what happens after a file is
 * really PREPARED, so those transitions are parameters rather than assumptions: run the
 * checks against every plausible device and the code has to survive all of them.
 */

export const id = { PLAYER: 1, RECORDER: 2 }

export const IDLE = 0
export const INITIALIZED = 1
export const PREPARING = 2
export const PREPARED = 3
export const STARTED = 4

/** Every call made through this module, in order, as `name:result`. */
export const calls = []

export const config = {
  /** Whether playback ending leaves the file loaded, or unloaded as `stop()` does. */
  stateAfterComplete: PREPARED,
  prepareMs: 20,
  durationMs: 325,
}

const DEFAULTS = { stateAfterComplete: PREPARED, prepareMs: 20, durationMs: 325 }

let live = null

export function reset(overrides) {
  live = null
  calls.length = 0
  Object.assign(config, DEFAULTS, overrides || {})
}

export function create(kind) {
  if (kind !== id.PLAYER) throw new Error('only PLAYER is faked')
  // OBSERVED: one instance per app, and the second `create` returns undefined - it does
  // not throw, which is the distinction the real audio.js has to draw.
  if (live) {
    calls.push('create:refused')
    return undefined
  }
  live = new FakePlayer()
  calls.push('create:ok')
  return live
}

class FakePlayer {
  constructor() {
    // OBSERVED constants.
    this.event = { PREPARE: 513, PLAY: 514, STOP: 515, PAUSE: 516, COMPLETE: 519 }
    this.source = { FILE: 2 }
    this.state = IDLE
    this.listeners = {}
    this.volume = 0
    this.completeTimer = null
    /** How many times a sample has been heard from its beginning. */
    this.playCount = 0
  }

  log(name, result) {
    calls.push(name + ':' + result)
    return result
  }

  fire(type, arg) {
    for (const fn of this.listeners[type] || []) fn(arg)
  }

  addEventListener(type, fn) {
    ;(this.listeners[type] = this.listeners[type] || []).push(fn)
  }

  setSource(kind, obj) {
    if (kind !== this.source.FILE || !obj || !obj.file) return this.log('setSource', false)
    this.state = INITIALIZED
    return this.log('setSource', true)
  }

  prepare() {
    if (this.state !== INITIALIZED) return this.log('prepare', false)
    this.state = PREPARING
    setTimeout(() => {
      if (this.state !== PREPARING) return
      this.state = PREPARED
      this.fire(this.event.PREPARE, true)
    }, config.prepareMs)
    return this.log('prepare', true)
  }

  start() {
    if (this.state !== PREPARED) return this.log('start', false)
    this.state = STARTED
    this.playCount++
    this.armCompletion()
    return this.log('start', true)
  }

  armCompletion() {
    clearTimeout(this.completeTimer)
    this.completeTimer = setTimeout(() => {
      this.state = config.stateAfterComplete
      this.fire(this.event.COMPLETE)
    }, config.durationMs)
  }

  seek() {
    if (this.state !== STARTED) return this.log('seek', false)
    this.playCount++
    this.armCompletion()
    return this.log('seek', true)
  }

  stop() {
    clearTimeout(this.completeTimer)
    this.state = INITIALIZED
    return this.log('stop', true)
  }

  pause() {
    return this.log('pause', false)
  }

  resume() {
    return this.log('resume', false)
  }

  getStatus() {
    return this.state
  }

  getDuration() {
    if (this.state !== PREPARED && this.state !== STARTED) return 0
    return config.durationMs / 1000
  }

  setVolume(value) {
    this.volume = value
    return this.log('setVolume', true)
  }

  getVolume() {
    return this.volume
  }

  release() {
    clearTimeout(this.completeTimer)
    this.state = IDLE
    // OBSERVED: releasing and re-creating on the same tick still fails; by the time the
    // player has walked back to the menu and pressed Play, the slot is free.
    setTimeout(() => {
      if (live === this) live = null
    }, 0)
    return this.log('release', true)
  }
}
