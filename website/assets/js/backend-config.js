/*
  Which backend this site talks to, and the key it presents.

  Exactly one page talks to one at all: /whaaack/auth/, which spends the recovery token
  out of a password-reset email and PUTs the new password. Everything else here is static
  and reaches nothing.

  The key is a Supabase *publishable* key (`sb_publishable_…`). It is designed for clients
  where anybody can read it, and it already sits inside the Android APK and the Zepp
  bundle; on its own it confers nothing, because row-level security decides what any
  request may see, and a password change additionally needs the one-shot recovery token
  from the email.

  It is still not committed, for the reason local.properties gives and zeppos/shared/
  secrets.js repeats: rotating a key should not mean rewriting history. The literal below
  is a placeholder, and .github/workflows/pages.yml swaps in the SUPABASE_PUBLISHABLE_KEY
  repository secret on the way to Pages — failing the deploy if that secret is unset,
  because a reset page that silently cannot reach the backend is worse than one that is
  visibly not configured. Locally the placeholder stays, and the page says so rather than
  offering a form that could not work.

  The project URL is not a secret and never was: `project_id` is committed in
  supabase/config.toml a few directories from here, and the host is in every request the
  game has ever made.
*/
window.WHAAACK_BACKEND = {
  url: 'https://pklrfcbyseitdbxkmsnw.supabase.co',
  key: '__SUPABASE_PUBLISHABLE_KEY__',
}
