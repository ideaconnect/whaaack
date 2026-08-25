/**
 * The side service: the phone-side half of the app, and the only thing that ever holds a
 * token.
 *
 * A Zepp OS watch has no internet of its own - every request goes out through the Zepp app
 * on the paired phone, which is where this file runs. That is a constraint, but it puts
 * the credentials in the right place: the watch asks for a leaderboard and gets rows back,
 * and a token never crosses to the wrist. A watch is the easier of the two devices to hand
 * to somebody for a moment.
 *
 * All of the actual behaviour is in `shared/supabase.js`, which knows nothing about Zepp
 * OS and can therefore be run against the real project from Node (`tools/check-backend.mjs`).
 * What is left here is plumbing: hand it a `fetch` and a key/value store, and route three
 * message methods.
 */

import { BaseSideService, settingsLib } from '@zeppos/zml/base-side'

import { SUPABASE_URL, SUPABASE_ANON_KEY } from '../shared/secrets.js'
import { createBackend, describe } from '../shared/supabase.js'
import {
  REQ_AUTH,
  REQ_BOARD,
  REQ_SUBMIT,
  KEY_AUTH_REQUEST,
  KEY_AUTH_STATUS,
  AUTH_SIGNED_IN,
  AUTH_SIGNED_OUT,
} from '../shared/protocol.js'

let backend = null

/**
 * Built on first use rather than at import: `this.fetch` belongs to the service instance,
 * and there is no instance until the framework has constructed one.
 */
function backendFor(service) {
  if (!backend) {
    backend = createBackend({
      url: SUPABASE_URL,
      anonKey: SUPABASE_ANON_KEY,
      fetch: (options) => service.fetch(options),
      storage: settingsLib,
    })
  }
  return backend
}

AppSideService(
  BaseSideService({
    onInit() {
      // A first install has no status at all, and the settings page renders from it.
      if (!settingsLib.getItem(KEY_AUTH_STATUS)) {
        const signedIn = backendFor(this).authSnapshot().signedIn
        settingsLib.setItem(
          KEY_AUTH_STATUS,
          JSON.stringify({ state: signedIn ? AUTH_SIGNED_IN : AUTH_SIGNED_OUT }),
        )
      }
    },

    /**
     * The settings page and this service only ever meet through storage, so this is the
     * primary sign-in trigger: the page writes a request, and the Zepp app starts this
     * service to see it.
     */
    onSettingsChange({ key }) {
      if (key !== KEY_AUTH_REQUEST) return
      backendFor(this).spendPendingRequest()
    },

    onRequest(req, res) {
      const method = req && req.method
      const params = (req && req.params) || {}
      const api = backendFor(this)

      // Belt and braces for the hook above. A service that was not running when the
      // settings changed may never be told about it: it starts, and the change it missed
      // is simply history. The request is still sitting in storage, though, so every call
      // from the watch is another chance to notice it.
      api.spendPendingRequest()

      if (method === REQ_AUTH) {
        res(null, api.authSnapshot())
        return
      }

      if (method === REQ_BOARD) {
        api
          .board(params.scope)
          .then((data) => res(null, data))
          .catch((error) =>
            res(null, {
              error: describe(error, 'The leaderboard is not answering right now.'),
              signedIn: api.authSnapshot().signedIn,
            }),
          )
        return
      }

      if (method === REQ_SUBMIT) {
        api
          .submit(params.millis, params.hits)
          .then((data) => res(null, data))
          .catch((error) =>
            res(null, {
              saved: false,
              reason: 'offline',
              message: describe(error, 'No connection to the leaderboard.'),
            }),
          )
        return
      }

      res(null, { error: 'Unknown request: ' + method })
    },

    onRun() {},

    onDestroy() {},
  }),
)
