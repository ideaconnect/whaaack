/**
 * Where things go, on a round watch and on a square one.
 *
 * Two screens, two sets of numbers, one set of names. Every page reads the names; only
 * this file knows which shape it is on. The two are written in their own design spaces -
 * 480 wide for round, 390 for square - because that is what `app.json` declares for each
 * platform and what `px()` rescales against. It is also what the *build* rescales bitmaps
 * against: zeus resizes every asset by `device width / design width`, so a 64px fruit
 * drawn for the 480 design lands at 62px on a 466 watch and the coordinates that place it
 * shrink by exactly the same fraction. Artwork and layout stay in step for free, and that
 * is why `assets/default.r` and `assets/default.s` hold the same pictures at different
 * sizes rather than one folder holding a compromise.
 *
 * ROUND - 480x480
 * ---------------
 * The constraint that decides the whole layout is that a round screen has no corners. A
 * 3x3 grid is a square, and a square inscribed in a 480px circle is only 339px on a side -
 * so the grid cannot be pushed down to leave a comfortable header above it without the
 * bottom two corner tiles falling outside the glass. The grid is therefore centred
 * slightly below the middle and the HUD lives in the cap above it, and each corner is
 * checked against the circle rather than eyeballed:
 *
 *   grid spans x 92..388, y 110..406 around a centre of (240, 240)
 *   nearest corner to the rim: (388, 406) -> sqrt(148^2 + 166^2) = 222 < 240  ok
 *
 * SQUARE - 390x450
 * ----------------
 * The opposite problem. There is no rim to clear, so the grid is limited by width alone
 * and the margins shrink from 52 to 24; but the screen is *portrait*, so what is scarce is
 * height, and a square grid as wide as this screen allows is nearly as tall as the screen
 * has left after the HUD. The numbers below spend that budget deliberately:
 *
 *   HUD   0..92     pips at 24, clock 40..92
 *   grid  100..432   3 x 104 tiles + 2 x 10 gaps = 332, centred at x=29
 *   spare 432..450   18px, so the bottom row is not flush against the edge
 *
 * A tile is 104 design pixels, which on the 1.75in glass of an Active 2 Square is about
 * 7.8mm - a little larger than the 7.3mm the round layout gets, because the corners are
 * there to be used. Well above the 60px Zepp OS asks of a touch target either way.
 *
 * Square screens also carry a system status bar across the top, which lands exactly where
 * the miss pips do. `hideStatusBar` in widgets.js turns it off; it is a no-op on round.
 */

import { px } from '@zos/utils'
import { getDeviceInfo, SCREEN_SHAPE_SQUARE } from '@zos/device'
import { TILE_COLUMNS, TILE_ROWS } from './engine.js'

const device = getDeviceInfo()

export const SCREEN_W = device.width
export const SCREEN_H = device.height

/**
 * Which shape this is.
 *
 * `screenShape` is the platform's own answer and is what the documentation points at.
 * The fallback exists because it is one field of an object the device fills in, and a
 * value that never arrives would silently hand a square watch the round layout - whereas
 * a screen that is taller than it is wide is square-ish by construction.
 */
export const IS_SQUARE =
  device.screenShape === SCREEN_SHAPE_SQUARE || SCREEN_H > SCREEN_W

/** The round design, in 480-wide design pixels. Unchanged, and checked against the rim. */
const ROUND = {
  tile: 92,
  tileGap: 10,
  tileRadius: 20,
  fruit: 64,
  splat: 108,
  badge: 50,
  badgeGap: 8,
  gridY: 110,

  pipR: 8,
  pipGap: 30,
  pipY: 34,
  clockY: 52,
  clockH: 52,
  clockFont: 46,

  margin: 52,
  buttonH: 74,

  bestX: 58,
  bestW: 196,
  soundX: 268,
  soundY: 144,
  soundW: 154,
  soundH: 46,

  titleFont: 38,
  bodyFont: 28,
  captionFont: 24,
  bigFont: 64,

  // home
  homeTitleY: 62,
  homeTaglineY: 114,
  homeBestY: 148,
  homePlayY: 190,
  homeBoardY: 278,
  homeAccountY: 356,
  homeAccountX: 84,

  // game, result screen
  countdownFont: 96,
  resultTitleY: 58,
  resultScoreY: 106,
  resultBadgeY: 188,
  resultBestY: 244,
  resultStatusY: 282,
  resultButtonY: 342,
  resultButtonX: 80,

  // board
  boardTitleY: 40,
  boardScopeY: 84,
  boardScopeX: 150,
  boardScopeW: 180,
  boardListX: 56,
  boardListInset: 112,
  boardListY: 138,
  boardListH: 228,
  boardRowH: 64,
  boardStandingY: 372,
  boardStandingX: 96,
  boardRankX: 12,
  boardRankW: 52,
  boardNameX: 70,
  boardNameW: 180,
  boardScoreX: 256,
  boardScoreW: 100,
}

/**
 * The square design, in 390-wide design pixels.
 *
 * Not the round numbers rescaled. A rescale would keep the round layout's rim allowances
 * - a 52px margin exists to keep text off a curve that is not there - and would spend the
 * width they cost on nothing. These are chosen against 390x450 directly, and the
 * arithmetic that matters is written out in the header.
 */
const SQUARE = {
  tile: 104,
  tileGap: 10,
  tileRadius: 22,
  fruit: 72,
  splat: 122,
  badge: 50,
  badgeGap: 8,
  gridY: 100,

  pipR: 8,
  pipGap: 28,
  pipY: 24,
  clockY: 40,
  clockH: 52,
  clockFont: 46,

  margin: 24,
  buttonH: 72,

  // 24 + 180 = 204, then the pill from 216 to 366, leaving the same 24 on the right.
  bestX: 24,
  bestW: 180,
  soundX: 216,
  soundY: 130,
  soundW: 150,
  soundH: 46,

  titleFont: 36,
  bodyFont: 26,
  captionFont: 23,
  bigFont: 62,

  // home: four bands down a 450px screen, with the account line last.
  homeTitleY: 44,
  homeTaglineY: 92,
  homeBestY: 134,
  homePlayY: 178,
  homeBoardY: 262,
  homeAccountY: 348,
  homeAccountX: 30,

  // game, result screen
  countdownFont: 92,
  resultTitleY: 42,
  resultScoreY: 88,
  resultBadgeY: 170,
  resultBestY: 228,
  resultStatusY: 268,
  resultButtonY: 330,
  resultButtonX: 40,

  // board
  boardTitleY: 28,
  boardScopeY: 70,
  boardScopeX: 105,
  boardScopeW: 180,
  boardListX: 24,
  boardListInset: 48,
  boardListY: 122,
  boardListH: 246,
  boardRowH: 62,
  boardStandingY: 378,
  boardStandingX: 40,
  boardRankX: 10,
  boardRankW: 48,
  boardNameX: 64,
  boardNameW: 166,
  // The row is 342 wide, and the score ends 12px short of it - the same breathing space
  // the round layout leaves. Ending at 342 exactly put the time hard against the row's
  // rounded corner, which is where the first square build left it.
  boardScoreX: 234,
  boardScoreW: 96,
}

const M = IS_SQUARE ? SQUARE : ROUND

export const TILE = px(M.tile)
export const TILE_GAP = px(M.tileGap)
export const TILE_RADIUS = px(M.tileRadius)

/** Matches FRUIT_PX for this shape in tools/generate_zepp_assets.py. */
export const FRUIT = px(M.fruit)

/**
 * The splat bitmap, at the phone's proportion of about 1.18 tiles.
 *
 * Deliberately wider than the tile it belongs to. A splat that stopped at the tile edge
 * would read as a coloured tile rather than as something that burst, and the overspill is
 * most of what sells it - the phone splashes across the gutter into its neighbours too.
 *
 * The spill is not symmetrical, which is the part that is easy to get wrong: `SPLAT_DY`
 * shifts the bitmap *down* so it sits below the tile's centre. On round that means 6px of
 * the 16px overhang goes above the tile and 10px below, so the bottom-right splat spans to
 * (396, 416) - sqrt(156^2 + 176^2) = 235 against a rim at 240. Still clear, but by five
 * pixels rather than the eight an even split would give, and this comment is the only
 * place that is checked. On square the rim is not the question and the screen edge is: the
 * bottom row's splat reaches y=443 of 450, and the right column's x=370 of 390.
 */
export const SPLAT = px(M.splat)

/**
 * A result-screen badge, and the space between two of them.
 *
 * Kept in step with BADGE_PX in tools/generate_zepp_assets.py, which is where the bitmaps
 * are cut. Fifty is what the achievement art survives being reduced to: the ring that
 * doubles as a clock still reads, which is what tells the tiers apart, and the number
 * inside it is still legible if you look.
 *
 * Five of them - the four tiers plus the trophy - come to 282px with the gaps, which sits
 * inside the glass on round and inside 390 with 54px to spare on square.
 */
export const BADGE = px(M.badge)
export const BADGE_GAP = px(M.badgeGap)

export const GRID_W = TILE * TILE_COLUMNS + TILE_GAP * (TILE_COLUMNS - 1)
export const GRID_H = TILE * TILE_ROWS + TILE_GAP * (TILE_ROWS - 1)
export const GRID_X = Math.round((SCREEN_W - GRID_W) / 2)
export const GRID_Y = px(M.gridY)

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

export const PIP_R = px(M.pipR)
export const PIP_GAP = px(M.pipGap)
export const PIP_Y = px(M.pipY)

export const CLOCK_Y = px(M.clockY)
export const CLOCK_H = px(M.clockH)
export const CLOCK_FONT = px(M.clockFont)

// ------------------------------------------------------------------- shared chrome

/** A full-width content column: clear of the rim on round, of the edge on square. */
export const MARGIN = px(M.margin)
export const CONTENT_X = MARGIN
export const CONTENT_W = SCREEN_W - MARGIN * 2

export const BUTTON_H = px(M.buttonH)

/**
 * The home screen's status row: the local best on the left, the sound toggle on the
 * right, sharing the band between the tagline and the Play button.
 *
 * It goes here rather than under the two menu buttons because the bottom of a round
 * screen is already spoken for - the account line wraps to two, ends at y=414 where the
 * glass is 330px wide, and there is no third line's worth of chord under it. This band is
 * the widest unspent space on the page: at y=190 the glass is 443px across, so a 154px
 * pill sits at x=268 with its far corner 206px from the centre.
 *
 * On square the band is the same idea and none of the arithmetic: the pill runs to 366 of
 * 390, leaving the same 24px margin the rest of the page uses.
 */
export const BEST_X = px(M.bestX)
export const BEST_W = px(M.bestW)
export const SOUND_X = px(M.soundX)
export const SOUND_Y = px(M.soundY)
export const SOUND_W = px(M.soundW)
export const SOUND_H = px(M.soundH)

export const TITLE_FONT = px(M.titleFont)
export const BODY_FONT = px(M.bodyFont)
export const CAPTION_FONT = px(M.captionFont)
export const BIG_FONT = px(M.bigFont)

// --------------------------------------------------------------------- the home page

export const HOME_TITLE_Y = px(M.homeTitleY)
export const HOME_TAGLINE_Y = px(M.homeTaglineY)
export const HOME_BEST_Y = px(M.homeBestY)
export const HOME_PLAY_Y = px(M.homePlayY)
export const HOME_BOARD_Y = px(M.homeBoardY)
export const HOME_ACCOUNT_Y = px(M.homeAccountY)
export const HOME_ACCOUNT_X = px(M.homeAccountX)

// ------------------------------------------------------- the game, and its result card

export const COUNTDOWN_FONT = px(M.countdownFont)
export const RESULT_TITLE_Y = px(M.resultTitleY)
export const RESULT_SCORE_Y = px(M.resultScoreY)
export const RESULT_BADGE_Y = px(M.resultBadgeY)
export const RESULT_BEST_Y = px(M.resultBestY)
export const RESULT_STATUS_Y = px(M.resultStatusY)
export const RESULT_BUTTON_Y = px(M.resultButtonY)
export const RESULT_BUTTON_X = px(M.resultButtonX)

// -------------------------------------------------------------------- the leaderboard

export const BOARD_TITLE_Y = px(M.boardTitleY)
export const BOARD_SCOPE_Y = px(M.boardScopeY)
export const BOARD_SCOPE_X = px(M.boardScopeX)
export const BOARD_SCOPE_W = px(M.boardScopeW)
export const BOARD_LIST_X = px(M.boardListX)
export const BOARD_LIST_W = SCREEN_W - px(M.boardListInset)
export const BOARD_LIST_Y = px(M.boardListY)
export const BOARD_LIST_H = px(M.boardListH)
export const BOARD_ROW_H = px(M.boardRowH)
export const BOARD_STANDING_Y = px(M.boardStandingY)
export const BOARD_STANDING_X = px(M.boardStandingX)
export const BOARD_RANK_X = px(M.boardRankX)
export const BOARD_RANK_W = px(M.boardRankW)
export const BOARD_NAME_X = px(M.boardNameX)
export const BOARD_NAME_W = px(M.boardNameW)
export const BOARD_SCORE_X = px(M.boardScoreX)
export const BOARD_SCORE_W = px(M.boardScoreW)
