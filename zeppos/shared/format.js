/**
 * Score formatting, shared by the watch, the side service and the settings page.
 *
 * The score *is* milliseconds survived - that is the unit the board is ranked on and the
 * unit that crosses the wire. But a five-digit number changing ten times a second is not
 * something anyone can read on a watch, so the HUD and the board show seconds to one
 * decimal and only the result screen spells the raw figure out.
 */

/** 18412 -> "18.4". One decimal: enough to separate two runs, slow enough to read. */
export function seconds(millis) {
  const tenths = Math.floor(Math.max(0, millis) / 100)
  return Math.floor(tenths / 10) + '.' + (tenths % 10)
}

/** 18412 -> "18.4s" */
export function secondsLabel(millis) {
  return seconds(millis) + 's'
}

/** "1st", "2nd", "3rd", "4th" - for the standing line under the board. */
export function ordinal(rank) {
  const mod100 = rank % 100
  if (mod100 >= 11 && mod100 <= 13) return rank + 'th'
  const mod10 = rank % 10
  if (mod10 === 1) return rank + 'st'
  if (mod10 === 2) return rank + 'nd'
  if (mod10 === 3) return rank + 'rd'
  return rank + 'th'
}
