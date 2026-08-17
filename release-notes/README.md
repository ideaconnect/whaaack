# Release notes

One file per upload, named `<versionCode>-<Play language code>.txt`, holding exactly the text
pasted into Play Console → *Release* → *Release notes* for that language.

Play caps each language at **500 characters**, counted with whitespace — check before pasting:

```bash
wc -m release-notes/7-en-GB.txt
```

The listing is English only. Adding a language means adding a file here beside the English one and
a translation in the Console; the store listing itself is translated in the Console, not from here.

Player-facing, not a changelog: what changed for someone holding the phone, in their words. The
engineering account of a release lives in the commit messages, and — for versionCode 7 — in
[GOOGLE_CONSOLE_UPGRADE_PLAN.md](../GOOGLE_CONSOLE_UPGRADE_PLAN.md).
