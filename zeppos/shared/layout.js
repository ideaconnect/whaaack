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
export const BUTTON_RADIUS = px(37)

export const TITLE_FONT = px(38)
export const BODY_FONT = px(28)
export const CAPTION_FONT = px(24)
export const BIG_FONT = px(64)
