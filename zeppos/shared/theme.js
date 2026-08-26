/**
 * The phone game's palette, re-grounded on black for an AMOLED screen.
 *
 * Every watch this build targets has an AMOLED panel, where a black pixel is an *unlit*
 * pixel rather than a dark one. That makes the background the cheapest thing on screen to
 * get right: the phone's orchard-night purple covers the whole 480px circle and lights
 * every pixel of it for nothing, where black costs no power at all and gives the fruit a
 * contrast ratio the purple could not. So the ground is `#000000` and the colour is spent
 * where it earns something - the fruit, the splats, the accent.
 *
 * Two consequences run through the rest of this file.
 *
 * Zepp OS fills are opaque `0xRRGGBB` - there is no per-widget alpha on a fill, only a
 * whole-widget one - so the phone's translucent creams are pre-composited here against the
 * background they are drawn on. That background is now black, which is why the dim creams
 * below are darker than the values a purple ground produced.
 *
 * And a board tile can no longer be a hole. Against orchard-night an empty tile was
 * *darker* than its surroundings; against black there is nothing darker, so the tile
 * states invert - an empty tile is the faintest thing that still reads as a target, and
 * every other state is a step up from it into light.
 */

/** Unlit. The whole point of the exercise. */
export const BACKGROUND = 0x000000

export const CREAM = 0xfff3e6
/** Cream at 70% over black - secondary labels. */
export const CREAM_DIM = 0xb3aaa1
/** Cream at 54% over black - captions and empty states. */
export const CREAM_FAINT = 0x8a837c

export const ACCENT = 0xffc97a
export const ACCENT_DEEP = 0xf2704f
export const ACCENT_INK = 0x43162f

export const DANGER = 0xe2574c
export const SUCCESS = 0x8fd24e

/**
 * Panel fill for cards and buttons.
 *
 * Neutral rather than the phone's navy: on black, a fill this dark reads as its hue long
 * before it reads as its brightness, and a blue-violet card behind cream text tinted the
 * text as well. Barely-lit grey stays out of the way of the accent, which is the only
 * colour on these screens that is meant to be noticed.
 */
export const PANEL = 0x24242e
export const PANEL_PRESSED = 0x3c3c4a

/**
 * The three states a board tile can be in.
 *
 * EMPTY is a little above black rather than at it: nine invisible tiles are a nine-way
 * guess about where to aim, and the whole run is aimed tapping.
 *
 * How *far* above black is set by the dimmest the screen gets, not by how these look at
 * full brightness. A watch spends most of its life below half, and the first values here
 * were chosen on a simulator pinned at 100%: at a tenth of that they went to black and the
 * board disappeared out from under the fruit. Screen brightness scales light, so a colour
 * has to survive being multiplied by 0.1 and still be distinguishable from the ground -
 * which puts the floor a good deal higher than it looks like it needs to be on a desk.
 *
 * ACTIVE and WARN differ in hue as well as brightness, because on a 46mm screen at arm's
 * length a brightness step alone is not a signal - and these two mean opposite things.
 *
 * There is no hit state. A whacked tile is covered by its splat, which says the same
 * thing in the fruit's own colour and says it for three times as long.
 */
export const TILE_EMPTY = 0x352e43
export const TILE_ACTIVE = 0x64459a
export const TILE_WARN = 0xba5a1e

/**
 * Strike pips: spent ones go red, unspent ones stay a faint outline.
 *
 * "Faint" has the same floor as the tiles do. Three pips that vanish at low brightness are
 * three pips that cannot be counted, and how many strikes are left is the one thing on
 * this screen a player checks rather than reacts to.
 */
export const PIP_SPENT = 0xe2574c
export const PIP_LEFT = 0x4a4458

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
