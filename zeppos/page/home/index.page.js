import { px } from '@zos/utils'
import { push } from '@zos/router'
import { localStorage } from '@zos/storage'
import { getText } from '@zos/i18n'
import { BasePage } from '@zeppos/zml/base-page'

import { REQ_AUTH, LOCAL_BEST } from '../../shared/protocol.js'
import { secondsLabel } from '../../shared/format.js'
import { ACCENT, CREAM_DIM, CREAM_FAINT } from '../../shared/theme.js'
import { SCREEN_W, TITLE_FONT, BODY_FONT, CAPTION_FONT } from '../../shared/layout.js'
import { ground, text, button, setText } from '../../shared/widgets.js'

// Design pixels on the 480px circle. The bottom line is the one that needs checking: it
// wraps to two lines ending at y=414, where the glass is 330px wide, so its 312px column
// clears the rim. A third line would not - which is why the copy under it is short.
const TITLE_Y = px(62)
const TAGLINE_Y = px(114)
const BEST_Y = px(148)
const PLAY_Y = px(190)
const BOARD_Y = px(278)
const ACCOUNT_Y = px(356)
const ACCOUNT_X = px(84)

let bestLabel = null
let accountLabel = null

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

    onResume() {
      // Coming back from a run: the best may have moved, and a sign-in may have happened
      // on the phone while the watch was showing something else.
      setText(bestLabel, bestText())
      this.refreshAccount()
    },

    refreshAccount() {
      this.request({ method: REQ_AUTH })
        .then((data) => {
          setText(
            accountLabel,
            data && data.signedIn
              ? getText('signedInAs') + (data.name || getText('player'))
              : getText('signInHint'),
          )
        })
        .catch(() => setText(accountLabel, getText('phoneUnreachable')))
    },
  }),
)

function bestText() {
  const best = Number(localStorage.getItem(LOCAL_BEST, 0)) || 0
  return best > 0 ? getText('best') + secondsLabel(best) : getText('noRunsYet')
}
