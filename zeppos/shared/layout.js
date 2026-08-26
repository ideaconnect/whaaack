/**
 * Where things go on a round watch.
 *
 * Everything is written in design pixels for the 480px screen `app.json` declares, and
 * `px()` rescales it for a device whose screen differs (the 44mm T-Rex 3 Pro is 466).
 *
 * The one constraint that decides the whole layout is that a round screen has no corners.
 * A 3x3 grid is a square, and a square inscribed in a 480px circle is only 339px on a
 * side - so the grid cannot be pushed down to leave a comfortable header above it without
 * the bottom two corner tiles falling outside the glass. The grid is therefore centred
 * slightly below the middle and the HUD lives in the cap above it, and each corner is
 * checked against the circle rather than eyeballed:
 *
 *   grid spans x 92..388, y 110..406 around a centre of (240, 240)
 *   nearest corner to the rim: (388, 406) -> sqrt(148^2 + 166^2) = 222 < 240  ok
 *
 * A tile is 92px, which on a 46mm watch is about 8.8mm of glass - above the 60px minimum
 * Zepp OS asks of a touch target, with the gap counted as slop on top.
 */

import { px } from '@zos/utils'
import { getDeviceInfo } from '@zos/device'
import { TILE_COLUMNS, TILE_ROWS } from './engine.js'

const device = getDeviceInfo()

export const SCREEN_W = device.width
export const SCREEN_H = device.height

export const TILE = px(92)
export const TILE_GAP = px(10)
export const TILE_RADIUS = px(20)

/** Matches FRUIT_PX in tools/generate_zepp_assets.py. */
export const FRUIT = px(64)

/**
 * The splat bitmap, at the phone's proportion of 1.18 tiles.
 *
 * Deliberately wider than the tile it belongs to. A splat that stopped at the tile edge
 * would read as a coloured tile rather than as something that burst, and the overspill is
 * most of what sells it - the phone splashes across the gutter into its neighbours too.
 * The far corner of the far tile plus this overspill still clears the rim: the grid ends
 * at (388, 406), the spill takes that to (396, 412), and sqrt(156^2 + 172^2) = 232 < 240.
 */
export const SPLAT = px(108)

/**
 * A result-screen badge, and the space between two of them.
 *
 * Kept in step with BADGE_PX in tools/generate_zepp_assets.py, which is where the bitmaps
 * are cut. Fifty is what the achievement art survives being reduced to: the ring that
 * doubles as a clock still reads, which is what tells the tiers apart, and the number
 * inside it is still legible if you look.
 *
 * Five of them - the four tiers plus the trophy - come to 282px with the gaps, so even a
 * perfect run's row sits well inside the glass.
 */
export const BADGE = px(50)
export const BADGE_GAP = px(8)

export const GRID_W = TILE * TILE_COLUMNS + TILE_GAP * (TILE_COLUMNS - 1)
export const GRID_H = TILE * TILE_ROWS + TILE_GAP * (TILE_ROWS - 1)
export const GRID_X = Math.round((SCREEN_W - GRID_W) / 2)
export const GRID_Y = px(110)

export function tileX(index) {
  return GRID_X + (index % TILE_COLUMNS) * (TILE + TILE_GAP)
}

export function tileY(index) {
  return GRID_Y + Math.floor(index / TILE_COLUMNS) * (TILE + TILE_GAP)
}

/** Centres a `size`-wide box inside the tile, for the fruit bitmap. */
export function tileInset(size) {
  return Math.round((TILE - size) / 2)
}

const SPLAT_DX = Math.round((TILE - SPLAT) / 2)
// Slightly below the tile's centre, as on the phone: a splat is something that fell and
// spread, and centring it exactly reads as a halo instead.
const SPLAT_DY = Math.round(TILE * 0.52 - SPLAT / 2)

export function splatX(index) {
  return tileX(index) + SPLAT_DX
}

export function splatY(index) {
  return tileY(index) + SPLAT_DY
}

// ------------------------------------------------------------------------------ HUD

export const PIP_R = px(8)
export const PIP_GAP = px(30)
export const PIP_Y = px(34)

export const CLOCK_Y = px(52)
export const CLOCK_H = px(52)
export const CLOCK_FONT = px(46)

// ------------------------------------------------------------------- shared chrome

/** A full-width content column that stays clear of the rim on a round screen. */
export const MARGIN = px(52)
export const CONTENT_X = MARGIN
export const CONTENT_W = SCREEN_W - MARGIN * 2

export const BUTTON_H = px(74)

/**
 * The home screen's status row: the local best on the left, the sound toggle on the
 * right, sharing the band between the tagline and the Play button.
 *
 * It goes here rather than under the two menu buttons because the bottom of a round
 * screen is already spoken for - the account line wraps to two, ends at y=414 where the
 * glass is 330px wide, and there is no third line's worth of chord under it. This band is
 * the widest unspent space on the page: at y=190 the glass is 443px across, so a 154px
 * pill sits at x=268 with its far corner 206px from the centre.
 */
export const BEST_X = px(58)
export const BEST_W = px(196)
export const SOUND_X = px(268)
export const SOUND_Y = px(144)
export const SOUND_W = px(154)
export const SOUND_H = px(46)

export const TITLE_FONT = px(38)
export const BODY_FONT = px(28)
export const CAPTION_FONT = px(24)
export const BIG_FONT = px(64)
