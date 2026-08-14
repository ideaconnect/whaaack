#!/usr/bin/env bash
#
# Pushes supabase/config.toml to the linked project.
#
# Exists because `supabase config push` is unforgiving about missing secrets: any
# env(NAME) it cannot resolve is sent as the literal string "env(NAME)", so forgetting
# one variable silently overwrites a working credential with garbage. Rather than
# trusting anyone to remember all three, this reads them from secrets/ (git-ignored)
# and refuses to run if one is missing.
#
#   bash tools/push-auth-config.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

WEB_CLIENT_JSON="secrets/oauth/web_client_secret_964578061899-aaiu8iv8u6fngd93to4vkma91abip7k8.apps.googleusercontent.com.json"
RESEND_FILE="secrets/resend.txt"

# Web client verifies the ID token; the Android client is the audience Google mints it
# for. The remote splits this on the comma into client_id + "Authorized Client IDs".
# It has to be one variable — the CLI only substitutes env(...) when it is the entire
# value, so "env(A),env(B)" would be pushed as that literal text.
WEB_CLIENT_ID="964578061899-aaiu8iv8u6fngd93to4vkma91abip7k8.apps.googleusercontent.com"
ANDROID_CLIENT_ID="964578061899-o3srji4j87lltkc3bphbsv6o6hj4t9b1.apps.googleusercontent.com"

for f in "$WEB_CLIENT_JSON" "$RESEND_FILE"; do
    if [ ! -f "$f" ]; then
        echo "missing: $f" >&2
        echo "Secrets are not in git. See docs/SETUP.md." >&2
        exit 1
    fi
done

# Pulled out with grep rather than a JSON parser so this needs nothing but a shell —
# python is not on PATH everywhere, and the file is a single flat object from Google.
GOOGLE_CLIENT_SECRET=$(
    grep -o '"client_secret"[[:space:]]*:[[:space:]]*"[^"]*"' "$WEB_CLIENT_JSON" |
        head -1 |
        sed 's/.*:[[:space:]]*"//; s/"$//'
)

RESEND_API_KEY=$(tr -d ' \r\n' < "$RESEND_FILE")

# Shape checks, not just emptiness: a bad grep would yield a plausible-looking string,
# and pushing the wrong value is exactly the failure this script exists to prevent.
case "$GOOGLE_CLIENT_SECRET" in
    GOCSPX-*) ;;
    *) echo "client_secret not found in $WEB_CLIENT_JSON (expected a GOCSPX- value)" >&2
       exit 1 ;;
esac

case "$RESEND_API_KEY" in
    re_*) ;;
    *) echo "$RESEND_FILE does not contain a re_... key" >&2
       exit 1 ;;
esac

export GOOGLE_CLIENT_ID="${WEB_CLIENT_ID},${ANDROID_CLIENT_ID}"
export GOOGLE_CLIENT_SECRET
export RESEND_API_KEY

exec supabase config push "$@"
