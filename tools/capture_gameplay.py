"""Record a real run of Whaaack! off a device, and cut the site's gameplay assets from it.

    python tools/capture_gameplay.py                 # record, encode, write into website/
    python tools/capture_gameplay.py --seconds 45    # play for longer before giving up
    python tools/capture_gameplay.py --keep-raw      # leave the full-resolution capture behind

Needs `adb` on PATH with exactly one device attached, `ffmpeg`, and the app installed. The
website's hero video and the four screenshots in the "Screens" fan are the output of this
script — not mockups, not renders, and not the synthetic frames the site used to show.

The awkward part is that somebody has to play. A recording of the board sitting idle is
worse than a still, so this drives a run itself: it looks at the screen, finds the fullest
hole, taps it, and repeats until three fruit have escaped.

Four things that are not obvious, all of them learned the hard way:

* **The board is a fixed 4x4 grid, so finding fruit is sixteen means, not blob detection.**
  The first version labelled connected components over the whole board and took 23 seconds
  per decision, against fruit that live for two. Hole centres are measured constants below.
* **`screencap -p` beats the raw framebuffer here.** Raw avoids a PNG encode but ships
  ~14 MB over adb; the PNG is a third of the time end to end.
* **`screenrecord` cannot encode the emulator's native 1280x2856** — the codec refuses it
  and falls back to a size with the wrong aspect ratio. Pass `--size` explicitly, with both
  dimensions divisible by 16.
* **Pulling the file while `screenrecord` still holds it yields a video with no moov atom**,
  which every player rejects. SIGINT it and give it a moment to finalise.
"""

from __future__ import annotations

import argparse
import io
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "website" / "assets"

PACKAGE = "tech.idct.whaaack"

# Hole centres on a 1280x2856 screen, measured by scanning a real gameplay frame for the
# dark ellipses. Both pitches are 281px. On a different screen size, re-measure.
COLS = (218, 498, 780, 1062)
ROWS = (1256, 1540, 1820, 2100)
HALF = 78
THRESHOLD = 0.16          # mean saturation*value inside a hole that counts as "fruit"

# Both divisible by 16, and 640/1424 is the device's aspect to within a third of a percent.
RECORD_SIZE = "640x1424"

# Where the run's countdown ends and the playing starts, in the recording.
CLIP_START = 5.15
CLIP_LENGTH = 6.35


def adb(*args: str, binary: bool = False):
    out = subprocess.run(["adb", *args], capture_output=True)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def screen() -> np.ndarray:
    return np.asarray(
        Image.open(io.BytesIO(adb("exec-out", "screencap", "-p", binary=True))).convert("RGB"),
        np.float32,
    )


def fullest_hole(frame: np.ndarray):
    """(x, y) of the hole that looks most like it has a fruit in it, or None."""
    best, best_score = None, THRESHOLD
    for x in COLS:
        for y in ROWS:
            cell = frame[y - HALF:y + HALF, x - HALF:x + HALF]
            if cell.size == 0:
                continue
            mx, mn = cell.max(axis=2), cell.min(axis=2)
            score = float((((mx - mn) / np.maximum(mx, 1.0)) * (mx / 255.0)).mean())
            if score > best_score:
                best, best_score = (x, y), score
    return best


def play(seconds: float, frames: Path | None) -> int:
    """Whack fruit until the board goes quiet or the clock runs out. Returns taps made."""
    deadline = time.time() + seconds
    taps = quiet = shot = 0
    while time.time() < deadline:
        frame = screen()
        if frames is not None:
            Image.fromarray(frame.astype(np.uint8)).save(frames / f"f{shot:03d}.png")
            shot += 1
        target = fullest_hole(frame)
        if target is None:
            quiet += 1
            # Four barren looks means the run is over. Stopping matters: taps that keep
            # landing after a run walk into the menus and start poking the auth screens.
            if quiet >= 4:
                break
            continue
        quiet = 0
        adb("shell", "input", "tap", str(target[0]), str(target[1]))
        taps += 1
    return taps


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-v", "error", *args], check=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=60, help="how long to keep playing")
    ap.add_argument("--keep-raw", action="store_true", help="keep the full-resolution capture")
    ap.add_argument("--out", default=str(ROOT / "build" / "capture"), help="scratch directory")
    args = ap.parse_args()

    scratch = Path(args.out)
    scratch.mkdir(parents=True, exist_ok=True)
    frames = scratch / "frames"
    frames.mkdir(exist_ok=True)

    if not adb("devices").strip().splitlines()[1:]:
        print("no device attached", file=sys.stderr)
        return 1

    print("launching")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "rm", "-f", "/sdcard/whaaack-run.mp4")
    adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(7)

    recorder = subprocess.Popen(
        ["adb", "shell", "screenrecord", "--size", RECORD_SIZE, "--time-limit", "180",
         "--bit-rate", "10000000", "/sdcard/whaaack-run.mp4"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    time.sleep(2)

    # "Play for fun" on the home screen. The upsell row shifts this, so a paid device or a
    # REMOVE_ADS_PLACEHOLDER_PRICE build needs the y re-checked.
    adb("shell", "input", "tap", "639", "1996")
    time.sleep(3)

    taps = play(args.seconds, frames)
    print(f"played: {taps} taps")

    adb("shell", "pkill", "-SIGINT", "screenrecord")
    time.sleep(4)
    recorder.wait(timeout=30)
    adb("pull", "/sdcard/whaaack-run.mp4", str(scratch / "run.mp4"))

    source = scratch / "run.mp4"
    if not source.exists():
        print("nothing was recorded", file=sys.stderr)
        return 1

    video_out = WEB / "video" / "gameplay.mp4"
    video_out.parent.mkdir(parents=True, exist_ok=True)
    ffmpeg("-ss", str(CLIP_START), "-t", str(CLIP_LENGTH), "-i", str(source),
           "-an", "-vf", "scale=540:-2,fps=30",
           "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p",
           "-crf", "27", "-preset", "slow", "-movflags", "+faststart",
           "-y", str(video_out))
    print(f"video  {video_out.relative_to(ROOT)}  {video_out.stat().st_size / 1024:.0f} KiB")

    poster = scratch / "poster.png"
    ffmpeg("-ss", str(CLIP_START), "-i", str(source), "-frames:v", "1", "-y", str(poster))
    Image.open(poster).convert("RGB").save(
        WEB / "img" / "shots" / "gameplay-poster.webp", "WEBP", quality=82, method=6
    )

    print(f"\n{len(list(frames.glob('*.png')))} stills in {frames}")
    print("Pick four for the fan and save them as WebP at 540px wide:")
    print("  real-countdown, real-hit, real-strike, real-burst")
    print("A run that ends without a strike flash or a burst is worth re-recording.")

    if not args.keep_raw:
        source.unlink(missing_ok=True)
        poster.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
