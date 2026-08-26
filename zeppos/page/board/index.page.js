import * as hmUI from '@zos/ui'
import { px } from '@zos/utils'
import { getText } from '@zos/i18n'
import { BasePage } from '@zeppos/zml/base-page'

import { REQ_BOARD, SCOPE_ALL_TIME, SCOPE_WEEKLY } from '../../shared/protocol.js'
import { secondsLabel, ordinal } from '../../shared/format.js'
import { ACCENT, CREAM, CREAM_DIM, CREAM_FAINT, PANEL, rankColor } from '../../shared/theme.js'
import {
  SCREEN_W,
  CAPTION_FONT,
  BODY_FONT,
  // In layout.js rather than here: on round the list is inset far enough that its bottom
  // row still clears the rim, and on square there is no rim and the inset is halved.
  BOARD_TITLE_Y as TITLE_Y,
  BOARD_SCOPE_Y as SCOPE_Y,
  BOARD_SCOPE_X as SCOPE_X,
  BOARD_SCOPE_W as SCOPE_W,
  BOARD_LIST_X as LIST_X,
  BOARD_LIST_W as LIST_W,
  BOARD_LIST_Y as LIST_Y,
  BOARD_LIST_H as LIST_H,
  BOARD_ROW_H as ROW_H,
  BOARD_STANDING_Y as STANDING_Y,
  BOARD_STANDING_X as STANDING_X,
  BOARD_RANK_X as RANK_X,
  BOARD_RANK_W as RANK_W,
  BOARD_NAME_X as NAME_X,
  BOARD_NAME_W as NAME_W,
  BOARD_SCORE_X as SCORE_X,
  BOARD_SCORE_W as SCORE_W,
} from '../../shared/layout.js'
import { ground, text, button, show, setText, hideStatusBar } from '../../shared/widgets.js'

let list = null
let scopeButton = null
let standing = null
let message = null

let scope = SCOPE_ALL_TIME

/**
 * False once this page has been torn down.
 *
 * A board request is a live HTTP round trip through the phone, and the promise that
 * resolves it belongs to the *app*, not to the page — ZML's `onDestroy` unsubscribes the
 * message handlers but cannot cancel a call already in flight, and the reply can land up
 * to a minute later. Widgets in Zepp OS attach to whatever page is in front when
 * `createWidget` runs, not to the page whose closure asked for them, so a reply arriving
 * after a back-swipe would draw a leaderboard over the home menu. Every callback here
 * checks this first.
 */
let alive = false

/**
 * The Zepp edition's own board.
 *
 * It is not the phone game's board and never shares a row with it: a 3x3 grid tapped with
 * one finger and a 4x4 grid tapped with two thumbs do not produce comparable milliseconds,
 * so the two are ranked separately (see supabase/migrations/..._zepp_edition.sql). The
 * display name is shared, though - one player, one name, two scores.
 *
 * A `SCROLL_LIST` rather than a column of widgets on a scrolling page, because a round
 * screen has to reserve the rim: the list keeps its own viewport and scrolls inside it, so
 * the title above and the standing below stay put instead of sliding under the bezel.
 */
Page(
  BasePage({
    build() {
      // Square watches draw their app name over the top of the page; see widgets.js.
      hideStatusBar()

      alive = true
      list = null
      scope = SCOPE_ALL_TIME
      ground()

      text({
        y: TITLE_Y,
        h: BODY_FONT + px(12),
        size: px(30),
        color: ACCENT,
        content: getText('leaderboard'),
      })

      scopeButton = button({
        x: SCOPE_X,
        y: SCOPE_Y,
        w: SCOPE_W,
        h: px(44),
        label: getText('allTime'),
        onClick: () => this.toggleScope(),
      })

      message = text({
        x: LIST_X,
        y: LIST_Y + px(70),
        w: LIST_W,
        h: CAPTION_FONT * 2 + px(10),
        size: CAPTION_FONT,
        color: CREAM_FAINT,
        content: getText('loading'),
        wrap: true,
      })

      // Two lines, because the signed-out prompt needs them and a one-line box ellipsised
      // it into "Sign in on your phone to r...". A one-line standing centres inside it.
      standing = text({
        x: STANDING_X,
        y: STANDING_Y,
        w: SCREEN_W - STANDING_X * 2,
        h: CAPTION_FONT * 2 + px(8),
        size: CAPTION_FONT,
        color: CREAM_DIM,
        content: '',
        wrap: true,
      })

      this.load()
    },

    onDestroy() {
      alive = false
      // Not `discardList()`: the page is going away and takes its widgets with it, and
      // deleting one during teardown is asking the runtime to free something twice.
      list = null
    },

    toggleScope() {
      scope = scope === SCOPE_ALL_TIME ? SCOPE_WEEKLY : SCOPE_ALL_TIME
      scopeButton.setProperty(
        hmUI.prop.TEXT,
        scope === SCOPE_ALL_TIME ? getText('allTime') : getText('thisWeek'),
      )
      this.load()
    },

    load() {
      const asked = scope
      setText(standing, '')
      setText(message, getText('loading'))
      show(message, true)
      discardList()

      this.request({ method: REQ_BOARD, params: { scope } })
        .then((data) => {
          // Two ways this answer can be stale: the page is gone, or the player flipped
          // the scope while it was in flight. Answering the wrong question is worse than
          // answering none, and drawing on a page that no longer exists is worse still.
          if (!alive || asked !== scope) return
          this.render(data || {})
        })
        .catch(() => {
          if (!alive || asked !== scope) return
          setText(message, getText('phoneUnreachable'))
          show(message, true)
        })
    },

    render(data) {
      if (data.error) {
        setText(message, data.error)
        show(message, true)
        return
      }

      const rows = data.rows || []
      if (rows.length === 0) {
        setText(message, getText('boardEmpty'))
        show(message, true)
      } else {
        show(message, false)
        showRows(rows)
      }

      if (data.me) {
        setText(
          standing,
          getText('you') + ordinal(data.me.rank) + getText('ofPlayers') + data.me.totalPlayers,
        )
      } else {
        setText(standing, data.signedIn ? getText('noRankedRun') : getText('signInToRank'))
      }
    },
  }),
)

function toItems(rows) {
  return rows.map((row) => ({
    rank: String(row.rank) + '.',
    name: row.name || getText('player'),
    score: secondsLabel(row.millis),
  }))
}

/**
 * Draws the rows, replacing whatever was there.
 *
 * `SCROLL_LIST` cannot be handed a colour per row, only per item *type* - so the top three
 * get a type each and everybody else shares the fourth, and the data-type table says which
 * row is which.
 *
 * The list is deleted and rebuilt rather than updated in place. `prop.UPDATE_DATA` exists
 * and would avoid that, but this runs at most three or four times in a visit (the first
 * load, and each flip of the scope), so the cheaper-looking path buys nothing measurable
 * and costs a second code path that is only ever exercised on the second load.
 */
function showRows(rows) {
  const items = toItems(rows)
  const typeConfig = items.map((item, index) => ({
    start: index,
    end: index + 1,
    type_id: Math.min(index, 3) + 1,
  }))

  discardList()
  list = hmUI.createWidget(hmUI.widget.SCROLL_LIST, {
    x: LIST_X,
    y: LIST_Y,
    w: LIST_W,
    h: LIST_H,
    item_space: px(8),
    item_config: [1, 2, 3, 4].map((typeId) => rowConfig(typeId)),
    item_config_count: 4,
    data_array: items,
    data_count: items.length,
    data_type_config: typeConfig,
    data_type_config_count: typeConfig.length,
  })
}

function discardList() {
  if (!list) return
  hmUI.deleteWidget(list)
  list = null
}

/** One row: rank, name, time. `type_id` 1..3 are the podium, 4 is everybody else. */
function rowConfig(typeId) {
  const tint = rankColor(typeId)
  return {
    type_id: typeId,
    item_bg_color: PANEL,
    item_bg_radius: px(16),
    item_height: ROW_H,
    text_view: [
      {
        x: RANK_X,
        y: 0,
        w: RANK_W,
        h: ROW_H,
        key: 'rank',
        color: tint,
        text_size: CAPTION_FONT,
        align_h: hmUI.align.RIGHT,
        align_v: hmUI.align.CENTER_V,
      },
      {
        x: NAME_X,
        y: 0,
        w: NAME_W,
        h: ROW_H,
        key: 'name',
        color: typeId <= 3 ? tint : CREAM,
        text_size: CAPTION_FONT,
        align_h: hmUI.align.LEFT,
        align_v: hmUI.align.CENTER_V,
      },
      {
        x: SCORE_X,
        y: 0,
        w: SCORE_W,
        h: ROW_H,
        key: 'score',
        color: CREAM_DIM,
        text_size: CAPTION_FONT,
        align_h: hmUI.align.RIGHT,
        align_v: hmUI.align.CENTER_V,
      },
    ],
    text_view_count: 3,
  }
}
