import * as hmUI from '@zos/ui'
import { px } from '@zos/utils'
import { getText } from '@zos/i18n'
import { BasePage } from '@zeppos/zml/base-page'

import { REQ_BOARD, SCOPE_ALL_TIME, SCOPE_WEEKLY } from '../../shared/protocol.js'
import { secondsLabel, ordinal } from '../../shared/format.js'
import { ACCENT, CREAM, CREAM_DIM, CREAM_FAINT, PANEL, rankColor } from '../../shared/theme.js'
import { SCREEN_W, CAPTION_FONT, BODY_FONT } from '../../shared/layout.js'
import { ground, text, button, show, setText } from '../../shared/widgets.js'

// The list is inset far enough that its bottom row still clears the rim: it ends at
// y=366, where the glass is 428px wide, and the list is 368.
const TITLE_Y = px(40)
const SCOPE_Y = px(84)
const SCOPE_X = px(150)
const SCOPE_W = px(180)
const LIST_X = px(56)
const LIST_W = SCREEN_W - px(112)
const LIST_Y = px(138)
const LIST_H = px(228)
const ROW_H = px(64)
const STANDING_Y = px(372)
const STANDING_X = px(96)

const RANK_X = px(12)
const RANK_W = px(52)
const NAME_X = px(70)
const NAME_W = px(180)
const SCORE_X = px(256)
const SCORE_W = px(100)

let list = null
let scopeButton = null
let standing = null
let message = null

let scope = SCOPE_ALL_TIME

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
          // The player can flip the scope while a slower request is still in flight;
          // answering the wrong question is worse than answering none.
          if (asked !== scope) return
          this.render(data || {})
        })
        .catch(() => {
          if (asked !== scope) return
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
