/**
 * A stand-in for `@zos/storage`, used by check-audio.mjs.
 *
 * `getItem` takes a fallback as its second argument, the way the watch's does - audio.js
 * relies on it for the sound switch defaulting to on.
 */

const store = new Map()

export const localStorage = {
  getItem: (key, fallback) => (store.has(key) ? store.get(key) : fallback),
  setItem: (key, value) => store.set(key, value),
  removeItem: (key) => store.delete(key),
}

export function clearAll() {
  store.clear()
}
