import { BaseApp } from '@zeppos/zml/base-app'

/**
 * `BaseApp` is what wires the watch app to the side service running on the phone; without
 * it `this.request(...)` on a page has nothing to talk to.
 *
 * `globalData.result` is how the game page hands a finished run to itself across the
 * router - see page/game. Nothing else lives here: a Zepp OS app can be killed between
 * two pages, so app-level state is a convenience, never a store.
 */
App(
  BaseApp({
    globalData: {
      result: null,
    },

    onCreate() {},

    onDestroy() {},
  }),
)
