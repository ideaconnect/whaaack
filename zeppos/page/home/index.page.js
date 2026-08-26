import * as hmUI from '@zos/ui'
import { px } from '@zos/utils'
import { push } from '@zos/router'
import { localStorage } from '@zos/storage'
import { getText } from '@zos/i18n'
import { BasePage } from '@zeppos/zml/base-page'

import { REQ_AUTH, LOCAL_BEST } from '../../shared/protocol.js'
import { secondsLabel } from '../../shared/format.js'
import { ACCENT, CREAM_DIM, CREAM_FAINT } from '../../shared/theme.js'
import {
  SCREEN_W,
  TITLE_FONT,
  BODY_FONT,
  CAPTION_FONT,
  // Where this page's rows sit. In layout.js rather than here because the answer differs
  // between a round screen and a square one, and only that file knows which this is.
  HOME_TITLE_Y as TITLE_Y,
  HOME_TAGLINE_Y as TAGLINE_Y,
  HOME_BEST_Y as BEST_Y,
  HOME_PLAY_Y as PLAY_Y,
  HOME_BOARD_Y as BOARD_Y,
  HOME_ACCOUNT_Y as ACCOUNT_Y,
  HOME_ACCOUNT_X as ACCOUNT_X,
} from '../../shared/layout.js'
import { ground, text, button, setText, hideStatusBar } from '../../shared/widgets.js'

let bestLabel = null
let accountLabel = null

/** False once the page is gone; the account lookup is a round trip to the phone. */
let alive = false

/**
 * The menu.
 *
 * Two things it shows that it has to go and find out: the best run this watch has seen
 * (local storage, instant) and whether anybody is signed in (a round trip to the phone,
 * which may simply not be there). The second is deliberately not blocking - the page
 * builds with an empty line and rewrites it if and when an answer arrives, so an unpaired
 * watch is a game you can still play rather than a spinner.
 */
Page(
  BasePage({
    build() {
      // Square watches draw their app name over the top of the page; see widgets.js.
      hideStatusBar()

      alive = true
      ground()

      text({
        y: TITLE_Y,
        h: TITLE_FONT + px(14),
        size: TITLE_FONT,
        color: ACCENT,
        content: getText('appName'),
      })

      text({
        y: TAGLINE_Y,
        h: CAPTION_FONT + px(8),
        size: CAPTION_FONT,
        color: CREAM_FAINT,
        content: getText('tagline'),
      })

      // Centred, and alone on its line again. It shared this band with a sound toggle
      // until the game stopped making any.
      bestLabel = text({
        y: BEST_Y,
        h: BODY_FONT + px(8),
        size: BODY_FONT,
        color: CREAM_DIM,
        content: bestText(),
      })

      button({
        y: PLAY_Y,
        label: getText('play'),
        primary: true,
        onClick: () => push({ url: 'page/game/index.page' }),
      })

      button({
        y: BOARD_Y,
        label: getText('leaderboard'),
        onClick: () => push({ url: 'page/board/index.page' }),
      })

      accountLabel = text({
        x: ACCOUNT_X,
        y: ACCOUNT_Y,
        w: SCREEN_W - ACCOUNT_X * 2,
        h: CAPTION_FONT * 2 + px(10),
        size: CAPTION_FONT,
        color: CREAM_FAINT,
        content: '',
        wrap: true,
      })

      this.refreshAccount()
    },

    onDestroy() {
      alive = false
    },

    onResume() {
      // Coming back from a run: the best may have moved, and a sign-in may have happened
      // on the phone while the watch was showing something else.
      //
      // Both have to be re-read here and nowhere else. The game page is opened with
      // `push`, so this page is never torn down and `build` never runs a second time - a
      // label written once at build time is written for the lifetime of the app.
      setText(bestLabel, bestText())
      this.refreshAccount()
    },

    refreshAccount() {
      this.request({ method: REQ_AUTH })
        .then((data) => {
          if (!alive) return
          setText(
            accountLabel,
            data && data.signedIn
              ? getText('signedInAs') + (data.name || getText('player'))
              : getText('signInHint'),
          )
        })
        .catch(() => {
          if (!alive) return
          setText(accountLabel, getText('phoneUnreachable'))
        })
    },
  }),
)

function bestText() {
  const best = Number(localStorage.getItem(LOCAL_BEST, 0)) || 0
  return best > 0 ? getText('best') + secondsLabel(best) : getText('noRunsYet')
}

