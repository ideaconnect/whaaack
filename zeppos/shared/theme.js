/**
 * The phone game's palette, flattened for a watch.
 *
 * Zepp OS widgets take opaque `0xRRGGBB` colours - there is no per-widget alpha channel
 * on a fill, only a whole-widget `setAlpha`. So the phone's translucent creams and navies
 * are pre-composited here against the background they are always drawn on, which is what
 * they resolve to on screen anyway and costs nothing at runtime.
 */

/** orchard_night, the same background the launcher icon is built on. */
export const BACKGROUND = 0x2a1633

export const CREAM = 0xfff3e6
/** Cream at 70% over BACKGROUND - secondary labels. */
export const CREAM_DIM = 0xbfb1b0
/** Cream at 54% over BACKGROUND - captions and empty states. */
export const CREAM_FAINT = 0x9d8e94

export const ACCENT = 0xffc97a
export const ACCENT_DEEP = 0xf2704f
export const ACCENT_INK = 0x43162f

export const DANGER = 0xe2574c
export const SUCCESS = 0x8fd24e

/** Panel fill for cards and buttons: PanelNavy composited over BACKGROUND. */
export const PANEL = 0x1a1330
export const PANEL_PRESSED = 0x33204a

/**
 * The four states a board tile can be in.
 *
 * A tile is drawn as a hole, so EMPTY is *darker* than the background rather than
 * lighter; everything else is a step up from it. WARN and HIT differ in hue as well as
 * brightness, because on a 46mm screen at arm's length a brightness step alone is not a
 * signal - and these two mean opposite things.
 */
export const TILE_EMPTY = 0x160b1e
export const TILE_ACTIVE = 0x3a2145
export const TILE_WARN = 0x8c4a22
export const TILE_HIT = 0x4e8a2a

/** Strike pips: spent ones go red, unspent ones stay a faint outline. */
export const PIP_SPENT = 0xe2574c
export const PIP_LEFT = 0x4a3355

export const RANK_GOLD = 0xffc94a
export const RANK_SILVER = 0xd6d6e0
export const RANK_BRONZE = 0xd08a4e

/** Top three get their own colour on the board; everyone else gets the cream. */
export function rankColor(rank) {
  if (rank === 1) return RANK_GOLD
  if (rank === 2) return RANK_SILVER
  if (rank === 3) return RANK_BRONZE
  return CREAM_DIM
}
