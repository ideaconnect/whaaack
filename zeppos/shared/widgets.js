/**
 * The three or four widgets every page here builds, with the arguments that are always
 * the same already filled in.
 *
 * Nothing clever - the point is that a page reads as its layout rather than as a wall of
 * `createWidget` option objects, and that the type scale lives in one place.
 */

import * as hmUI from '@zos/ui'

import { SCREEN_W, SCREEN_H, CONTENT_X, CONTENT_W, BUTTON_H, BODY_FONT } from './layout.js'
import { BACKGROUND, CREAM, ACCENT, ACCENT_INK, PANEL, PANEL_PRESSED } from './theme.js'

/**
 * The page's ground.
 *
 * Now that the theme is black this paints what a Zepp OS page already starts as, which
 * makes it a no-op on every screen here - and it is kept anyway, because it is the one
 * place a page's background is decided and a theme that ever stops being black would
 * otherwise have to add it back to four pages at once.
 *
 * Not a touch surface - `addEventListener` on a `FILL_RECT` under the board never fires
 * (verified on a T-Rex 3 Pro), which is why the game page puts BUTTON widgets over the
 * tiles instead.
 */
export function ground(color) {
  return hmUI.createWidget(hmUI.widget.FILL_RECT, {
    x: 0,
    y: 0,
    w: SCREEN_W,
    h: SCREEN_H,
    color: color === undefined ? BACKGROUND : color,
  })
}

export function text({ x, y, w, h, size, color, content, align, wrap }) {
  return hmUI.createWidget(hmUI.widget.TEXT, {
    x: x === undefined ? CONTENT_X : x,
    y,
    w: w === undefined ? CONTENT_W : w,
    h,
    color: color === undefined ? CREAM : color,
    text_size: size === undefined ? BODY_FONT : size,
    align_h: align === undefined ? hmUI.align.CENTER_H : align,
    align_v: hmUI.align.CENTER_V,
    text_style: wrap ? hmUI.text_style.WRAP : hmUI.text_style.ELLIPSIS,
    text: content === undefined ? '' : content,
  })
}

export function button({ x, y, w, h, size, label, onClick, primary }) {
  const height = h === undefined ? BUTTON_H : h
  return hmUI.createWidget(hmUI.widget.BUTTON, {
    x: x === undefined ? CONTENT_X : x,
    y,
    w: w === undefined ? CONTENT_W : w,
    h: height,
    // Always a full pill. Derived rather than fixed so that the short buttons - the
    // leaderboard's scope switch, the home screen's sound toggle - come out as round at
    // the ends as the tall ones instead of as rectangles with a generous corner.
    radius: Math.round(height / 2),
    normal_color: primary ? ACCENT : PANEL,
    press_color: primary ? ACCENT_INK : PANEL_PRESSED,
    color: primary ? ACCENT_INK : CREAM,
    text_size: size === undefined ? BODY_FONT : size,
    text: label,
    click_func: onClick,
  })
}

export function rect({ x, y, w, h, color, radius }) {
  return hmUI.createWidget(hmUI.widget.FILL_RECT, {
    x,
    y,
    w,
    h,
    color,
    radius: radius === undefined ? 0 : radius,
  })
}

export function circle({ cx, cy, r, color }) {
  return hmUI.createWidget(hmUI.widget.CIRCLE, {
    center_x: cx,
    center_y: cy,
    radius: r,
    color,
  })
}

export function image({ x, y, w, h, src, alpha }) {
  return hmUI.createWidget(hmUI.widget.IMG, {
    x,
    y,
    w,
    h,
    src,
    alpha: alpha === undefined ? 255 : alpha,
  })
}

/** `setProperty(VISIBLE, …)` guarded, so a page can toggle a widget it may not have built. */
export function show(widget, visible) {
  if (widget) widget.setProperty(hmUI.prop.VISIBLE, !!visible)
}

export function setText(widget, content) {
  if (widget) widget.setProperty(hmUI.prop.TEXT, content)
}

export function setColor(widget, color) {
  if (widget) widget.setProperty(hmUI.prop.COLOR, color)
}

export function setSrc(widget, src) {
  if (widget) widget.setProperty(hmUI.prop.SRC, src)
}

/**
 * Opacity, 0 to 255.
 *
 * There is no `prop.ALPHA`, so it goes through `prop.MORE` - which takes a partial bag of
 * properties and leaves the rest alone, so this is a single-property write like the ones
 * above rather than a redefinition of the widget.
 */
export function setAlpha(widget, alpha) {
  if (widget) widget.setProperty(hmUI.prop.MORE, { alpha })
}

/**
 * Moves a widget horizontally. Through `prop.MORE` for the same reason `setAlpha` is: it
 * takes a partial bag of properties and leaves the rest alone, so this stays a
 * single-property write rather than a redefinition of the widget.
 */
export function setX(widget, x) {
  if (widget) widget.setProperty(hmUI.prop.MORE, { x })
}

/**
 * Takes back the strip a square watch spends on a system title bar.
 *
 * Square devices draw the app's name across the top of every page, over the page rather
 * than beside it - and on the game screen it lands exactly on the miss pips, so the first
 * square build showed the lives hidden behind the word "Whaaack!". The API is documented
 * as square-only, hence the guard rather than a shape test: whether it is there at all is
 * the question being asked, and on a round watch the answer is no.
 */
export function hideStatusBar() {
  if (typeof hmUI.setStatusBarVisible === 'function') hmUI.setStatusBarVisible(false)
}
