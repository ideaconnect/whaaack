"""Rebuild the Zepp OS edition's two sounds from the ones the phone game already ships.

    python tools/generate_zepp_audio.py           # write the audio
    python tools/generate_zepp_audio.py --probe   # just report what it would do

Needs `ffmpeg` on PATH. Everything here is a transcode, not a mix, so there is nothing
to check by eye afterwards - which is why this prints the numbers it chose.

Why the watch gets two files and the phone gets fourteen
--------------------------------------------------------

The phone loads every effect into a `SoundPool` and fires them off by id, so a hit can
pick one of nine splats at random for free. A watch has `@zos/media`, which is a *media
player*: one source at a time, `setSource` then an asynchronous `prepare()` before a
single `start()`. Choosing a different splat per hit would mean re-preparing between
taps that are 200ms apart, so the watch gets one splat sound, prepared once and
restarted - and the variety that the phone puts in the audio is put in the *bitmaps*
instead, where the watch can afford it (thirty-six splat sprites; see
generate_zepp_assets.py).

MP3 and Opus are the only formats the player takes, and Opus only for files it recorded
itself, so both of these are MP3. Mono at 32kHz: the speaker in a watch is a few
millimetres across and reproduces nothing near the top of a 44.1kHz band, and 32kHz is
the lowest rate that is still MPEG-1 rather than the MPEG-2 half-rate extension - worth
staying on the format the decoder is certain to have been tested against.

The loop
--------

`GAME.ogg` is exactly 120 seconds and its onset envelope autocorrelates at multiples of
120/112 = 1.0714s, with the strongest phrase-length peak at sixteen of those (17.14s).
Two phrases is the loop: long enough not to nag over a four-minute run, and an exact
number of musical units, so the point where it wraps lands on a downbeat rather than
somewhere in the middle of a bar.

Beyond that the seam is crossfaded, which is the part that makes it *seamless* rather
than merely well-timed. The tail is taken from past the cut and mixed down over the head
with an equal-power curve, so the last sample of the loop and the first sample belong to
the same moment of the recording and there is nothing for a decoder to click on. The
watch cannot do this at runtime: it loops by restarting the player when playback
completes, which is a hard cut.
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

MUSIC_SRC = AUDIO_SRC / "GAME.ogg"
SPLAT_SRC = AUDIO_SRC / "splats" / "splat_quick.wav"

# The musical unit `GAME.ogg` is built from, and how many of them the watch loops.
TRACK_SECONDS = 120.0
TRACK_UNITS = 112
LOOP_UNITS = 32
# Short: the cut is already on a downbeat, so this is insurance against a waveform
# discontinuity rather than a way of hiding a badly chosen one. A long fade here would
# audibly double the music across the seam.
CROSSFADE_S = 0.4

SAMPLE_RATE = 32000
MUSIC_KBPS = 48
# The splat is a third of a second, so its bitrate costs nothing and there is no reason
# to spend transients on saving three kilobytes.
SPLAT_KBPS = 96

# Headroom under full scale for the splat, which is peak-normalised so that it carries
# over the music at whatever relative volume the watch ends up choosing.
SPLAT_PEAK_DBFS = -1.0

UNIT_S = TRACK_SECONDS / TRACK_UNITS
LOOP_S = UNIT_S * LOOP_UNITS


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


def build_music(dest: Path) -> None:
    """One loop of the game track, with its seam crossfaded closed."""
    head_end = LOOP_S
    tail_end = LOOP_S + CROSSFADE_S
    # The head fades *in* across the crossfade and the tail - the music from just past
    # the cut - fades out across the same stretch, then the two are summed. At the top of
    # the loop the head contributes nothing and the tail everything, which is exactly the
    # audio the end of the loop was heading into; by CROSSFADE_S the tail is gone. `qsin`
    # on both sides keeps the sum at constant power instead of dipping through the middle.
    graph = (
        f"[0:a]atrim=0:{head_end},asetpts=N/SR/TB,"
        f"afade=t=in:st=0:d={CROSSFADE_S}:curve=qsin[head];"
        f"[0:a]atrim={head_end}:{tail_end},asetpts=N/SR/TB,"
        f"afade=t=out:st=0:d={CROSSFADE_S}:curve=qsin[tail];"
        f"[head][tail]amix=inputs=2:duration=first:normalize=0[out]"
    )
    ffmpeg(
        "-i", str(MUSIC_SRC),
        "-filter_complex", graph,
        "-map", "[out]",
        "-ac", "1", "-ar", str(SAMPLE_RATE), "-b:a", f"{MUSIC_KBPS}k",
        # No tags: nothing on the watch reads them. The Xing header stays, though - it is
        # the two hundred bytes that tell a decoder how much of the first and last frame
        # is encoder padding rather than music, and this file is going to be played end to
        # end and restarted for as long as a run lasts.
        "-map_metadata", "-1", "-id3v2_version", "0",
        str(dest),
    )


def build_splat(dest: Path) -> float:
    gain = SPLAT_PEAK_DBFS - peak_dbfs(SPLAT_SRC)
    ffmpeg(
        "-i", str(SPLAT_SRC),
        "-af", f"volume={gain:.2f}dB",
        "-ac", "1", "-ar", str(SAMPLE_RATE), "-b:a", f"{SPLAT_KBPS}k",
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
    for src in (MUSIC_SRC, SPLAT_SRC):
        if not src.exists():
            raise SystemExit(f"missing source: {src}")

    print(f"music loop: {LOOP_UNITS} units of {UNIT_S:.4f}s = {LOOP_S:.3f}s")
    print(f"crossfade:  {CROSSFADE_S}s equal-power, taken from {LOOP_S:.3f}s onward")
    print(f"encode:     mono {SAMPLE_RATE}Hz, {MUSIC_KBPS}kbps music / {SPLAT_KBPS}kbps splat")
    if args.probe:
        return 0

    OUT.mkdir(parents=True, exist_ok=True)
    music = OUT / "music.mp3"
    splat = OUT / "splat.mp3"

    build_music(music)
    gain = build_splat(splat)

    print(f"\n{music.name}: {music.stat().st_size:,} bytes from {MUSIC_SRC.name}")
    print(f"{splat.name}: {splat.stat().st_size:,} bytes from {SPLAT_SRC.name} ({gain:+.2f}dB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
