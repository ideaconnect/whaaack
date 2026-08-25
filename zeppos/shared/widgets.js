/**
 * The three or four widgets every page here builds, with the arguments that are always
 * the same already filled in.
 *
 * Nothing clever - the point is that a page reads as its layout rather than as a wall of
 * `createWidget` option objects, and that the type scale lives in one place.
 */

import * as hmUI from '@zos/ui'

import { SCREEN_W, SCREEN_H, CONTENT_X, CONTENT_W, BUTTON_H, BUTTON_RADIUS, BODY_FONT } from './layout.js'
import { BACKGROUND, CREAM, ACCENT, ACCENT_INK, PANEL, PANEL_PRESSED } from './theme.js'

/**
 * The page's ground.
 *
 * Purely a backdrop: a Zepp OS page starts out black, and every page here wants the
 * orchard behind it. Not a touch surface - `addEventListener` on a `FILL_RECT` under the
 * board never fires (verified on a T-Rex 3 Pro), which is why the game page puts BUTTON
 * widgets over the tiles instead.
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

export function button({ x, y, w, h, label, onClick, primary }) {
  return hmUI.createWidget(hmUI.widget.BUTTON, {
    x: x === undefined ? CONTENT_X : x,
    y,
    w: w === undefined ? CONTENT_W : w,
    h: h === undefined ? BUTTON_H : h,
    radius: BUTTON_RADIUS,
    normal_color: primary ? ACCENT : PANEL,
    press_color: primary ? ACCENT_INK : PANEL_PRESSED,
    color: primary ? ACCENT_INK : CREAM,
    text_size: BODY_FONT,
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

export function image({ x, y, w, h, src }) {
  return hmUI.createWidget(hmUI.widget.IMG, { x, y, w, h, src })
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
