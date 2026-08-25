/**
 * Template for `secrets.js`, which is generated and not committed.
 *
 *     python tools/sync_zepp_secrets.py
 *
 * copies the two values out of `local.properties` - the same pair the Android build reads
 * into BuildConfig - so the watch app and the phone app always talk to the same project.
 *
 * The anon key is a public client key: it identifies the project, and row-level security
 * is the actual boundary (see supabase/migrations). It is kept out of git anyway, for the
 * reason local.properties gives - so rotating it does not mean rewriting history.
 */

export const SUPABASE_URL = 'https://YOUR-PROJECT.supabase.co'
export const SUPABASE_ANON_KEY = 'YOUR-ANON-KEY'
