"""Rebuild the Zepp OS edition's one sound from the ones the phone game already ships.

    python tools/generate_zepp_audio.py           # write the audio
    python tools/generate_zepp_audio.py --probe   # just report what it would do

Needs `ffmpeg` on PATH. Everything here is a transcode, not a mix, so there is nothing
to check by eye afterwards - which is why this prints the numbers it chose.

Why the watch gets one file and the phone gets fourteen
-------------------------------------------------------

The phone loads every effect into a `SoundPool` and fires them off by id, so a hit can
pick one of nine splats at random for free. A watch has `@zos/media`, which is a *media
player*: one source at a time, `setSource` then an asynchronous `prepare()` before a
single `start()`. Choosing a different splat per hit would mean re-preparing between
taps that are 200ms apart, so the watch gets one splat sound, prepared once and
retriggered - and the variety that the phone puts in the audio is put in the *bitmaps*
instead, where the watch can afford it (thirty-six splat sprites; see
generate_zepp_assets.py).

There was a music track here too, and it never once played. The engine hands out exactly
one media player per app - the second `create` returns `undefined` - and the splat claims
it first, deliberately, because a hit that makes no sound is a hit that feels like it
missed. So the music lost that race on every device and on every run, while costing 206KB
of the package. The loop-building that used to live in this file went with it: an exact
number of musical units so the wrap landed on a downbeat, and an equal-power crossfade
across the seam so there was nothing for a decoder to click on. Both were correct, and
both were solving a problem the watch does not have.

MP3 and Opus are the only formats the player takes, and Opus only for files it recorded
itself, so this is MP3. Mono at 32kHz: the speaker in a watch is a few millimetres across
and reproduces nothing near the top of a 44.1kHz band, and 32kHz is the lowest rate that
is still MPEG-1 rather than the MPEG-2 half-rate extension - worth staying on the format
the decoder is certain to have been tested against.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AUDIO_SRC = ROOT / "app" / "src" / "main" / "assets" / "audio"
OUT = ROOT / "zeppos" / "assets" / "default.r"

SPLAT_SRC = AUDIO_SRC / "splats" / "splat_quick.wav"

SAMPLE_RATE = 32000
# The splat is a third of a second, so its bitrate costs nothing and there is no reason
# to spend transients on saving three kilobytes.
SPLAT_KBPS = 96

# Headroom under full scale. Peak-normalised because it is the only thing the watch plays
# and it has to carry on a speaker the size of a grain of rice.
SPLAT_PEAK_DBFS = -1.0


def ffmpeg(*args: str) -> None:
    result = subprocess.run(["ffmpeg", "-v", "error", "-y", *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit("ffmpeg failed:\n" + result.stderr.strip())


def peak_dbfs(path: Path) -> float:
    """The file's loudest sample, in dBFS, via ffmpeg's own detector."""
    result = subprocess.run(
        ["ffmpeg", "-v", "info", "-i", str(path), "-af", "volumedetect", "-f", "null", "-"],
        capture_output=True,
        text=True,
    )
    found = re.search(r"max_volume:\s*(-?[\d.]+) dB", result.stderr)
    if not found:
        raise SystemExit(f"could not measure the peak of {path.name}")
    return float(found.group(1))


def build_splat(dest: Path) -> float:
    gain = SPLAT_PEAK_DBFS - peak_dbfs(SPLAT_SRC)
    ffmpeg(
        "-i", str(SPLAT_SRC),
        "-af", f"volume={gain:.2f}dB",
        "-ac", "1", "-ar", str(SAMPLE_RATE), "-b:a", f"{SPLAT_KBPS}k",
        # No tags: nothing on the watch reads them. The Xing header stays, though - it is
        # the two hundred bytes that tell a decoder how much of the first and last frame is
        # encoder padding rather than audio, and this file is restarted from the top on
        # every whack.
        "-map_metadata", "-1", "-id3v2_version", "0",
        str(dest),
    )
    return gain


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--probe", action="store_true", help="report the plan and write nothing")
    args = ap.parse_args()

    if shutil.which("ffmpeg") is None:
        raise SystemExit("ffmpeg is not on PATH")
    if not SPLAT_SRC.exists():
        raise SystemExit(f"missing source: {SPLAT_SRC}")

    print(f"source:  {SPLAT_SRC.relative_to(ROOT)}")
    print(f"encode:  mono {SAMPLE_RATE}Hz, {SPLAT_KBPS}kbps, peak-normalised to {SPLAT_PEAK_DBFS}dBFS")
    if args.probe:
        return 0

    OUT.mkdir(parents=True, exist_ok=True)
    splat = OUT / "splat.mp3"
    gain = build_splat(splat)

    print(f"\n{splat.name}: {splat.stat().st_size:,} bytes ({gain:+.2f}dB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
