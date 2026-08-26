import * as hmUI from '@zos/ui'
import { px } from '@zos/utils'
import { push } from '@zos/router'
import { localStorage } from '@zos/storage'
import { getText } from '@zos/i18n'
import { BasePage } from '@zeppos/zml/base-page'

import { REQ_AUTH, LOCAL_BEST } from '../../shared/protocol.js'
import { secondsLabel } from '../../shared/format.js'
import { soundOn, setSoundOn, soundKnownBroken } from '../../shared/audio.js'
import { ACCENT, CREAM_DIM, CREAM_FAINT } from '../../shared/theme.js'
import {
  SCREEN_W,
  TITLE_FONT,
  BODY_FONT,
  CAPTION_FONT,
  BEST_X,
  BEST_W,
  SOUND_X,
  SOUND_Y,
  SOUND_W,
  SOUND_H,
} from '../../shared/layout.js'
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
let soundButton = null
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

      // Left-aligned rather than centred, because it now shares its band with the sound
      // toggle: two things centred on one line read as one wobbly thing.
      bestLabel = text({
        x: BEST_X,
        y: BEST_Y,
        w: BEST_W,
        h: BODY_FONT + px(8),
        size: BODY_FONT,
        color: CREAM_DIM,
        align: hmUI.align.LEFT,
        content: bestText(),
      })

      soundButton = button({
        x: SOUND_X,
        y: SOUND_Y,
        w: SOUND_W,
        h: SOUND_H,
        size: CAPTION_FONT,
        label: soundText(),
        onClick: () => this.toggleSound(),
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
      setText(bestLabel, bestText())
      this.refreshAccount()
    },

    /**
     * Flips the sound and says so on the button.
     *
     * Written to local storage rather than held here, because every page that makes a
     * noise reads it fresh: the game page is a separate module with its own lifetime, and
     * a preference in a variable would not survive the walk between the two.
     */
    toggleSound() {
      setSoundOn(!soundOn())
      setText(soundButton, soundText())
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

/**
 * What the toggle says.
 *
 * Three labels for two states, because a watch that will not play anything is worth saying
 * out loud: the alternative is a button that reads "Sound on" over a game that has never
 * made a noise, which sends a player looking for the volume control they do not have.
 * Whether it plays is only discoverable during a run, so the answer is remembered from the
 * last one (see `soundKnownBroken`).
 */
function soundText() {
  if (!soundOn()) return getText('soundOff')
  return soundKnownBroken() ? getText('soundNone') : getText('soundOn')
}
