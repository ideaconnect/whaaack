"""Play a real run of Whaaack! on a device and keep every frame, for the website's screens.

    python tools/capture_gameplay.py                 # play a run, save the frames
    python tools/capture_gameplay.py --seconds 45    # keep trying for longer

Needs `adb` on PATH with one device attached, and the app installed. Everything the site
shows of the game comes out of this: the two floating screens in the hero and the four in
the fan are frames from one run, not mockups and not renders.

The awkward part is that somebody has to play. A capture of an idle board is worse than no
capture, so this drives the run itself: it looks at the screen, finds the fullest hole, taps
it, and repeats until three fruit have escaped.

Three things that are not obvious, all learned the hard way:

* **The board is a fixed 4x4 grid, so finding fruit is sixteen means, not blob detection.**
  The first version labelled connected components across the whole board and took 23 seconds
  per decision, against fruit that live for two. Hole centres are measured constants below.
* **`screencap -p` beats the raw framebuffer.** Raw skips a PNG encode on the device but
  ships ~14 MB over adb; the PNG is roughly a third of the time end to end.
* **Stop tapping the moment the board goes quiet.** Taps that keep landing after a run walk
  straight into the menus, and the auth screens are one of the places they land.

There is no video here any more. The site floats stills instead, so `screenrecord` and the
whole ffmpeg step went with it — along with its own trap, which was that pulling the file
while the recorder still held it produced an mp4 with no moov atom that nothing would open.
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
PACKAGE = "tech.idct.whaaack"

# Hole centres on a 1280x2856 screen, measured by scanning a real gameplay frame for the
# dark ellipses. Both pitches are 281px. On a different screen size, re-measure.
COLS = (218, 498, 780, 1062)
ROWS = (1256, 1540, 1820, 2100)
HALF = 78
THRESHOLD = 0.16          # mean saturation*value inside a hole that counts as "fruit"

# "Play for fun" on the home screen. The ad-free upsell row shifts this, so a paid device or
# a REMOVE_ADS_PLACEHOLDER_PRICE build needs the y re-checked.
PLAY_BUTTON = (639, 1996)


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


def play(seconds: float, frames: Path) -> int:
    """Whack fruit until the board goes quiet or the clock runs out. Returns frames kept."""
    deadline = time.time() + seconds
    kept = quiet = 0
    while time.time() < deadline:
        frame = screen()
        Image.fromarray(frame.astype(np.uint8)).save(frames / f"f{kept:03d}.png")
        kept += 1
        target = fullest_hole(frame)
        if target is None:
            quiet += 1
            if quiet >= 4:
                break
            continue
        quiet = 0
        adb("shell", "input", "tap", str(target[0]), str(target[1]))
    return kept


def contact_sheet(frames: Path, out: Path) -> None:
    """One image of every frame, so the good moments can be picked by eye."""
    shots = sorted(frames.glob("f*.png"))
    if not shots:
        return
    thumbs = [Image.open(p).resize((110, 245)) for p in shots]
    cols = 10
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * 110, rows * 245), (18, 35, 61))
    for i, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((i % cols) * 110, (i // cols) * 245))
    sheet.save(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=60, help="how long to keep playing")
    ap.add_argument("--out", default=str(ROOT / "build" / "capture"), help="where to write")
    args = ap.parse_args()

    frames = Path(args.out) / "frames"
    frames.mkdir(parents=True, exist_ok=True)
    for stale in frames.glob("f*.png"):
        stale.unlink()

    if not adb("devices").strip().splitlines()[1:]:
        print("no device attached", file=sys.stderr)
        return 1

    print("launching")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(7)
    adb("shell", "input", "tap", str(PLAY_BUTTON[0]), str(PLAY_BUTTON[1]))
    time.sleep(3)

    kept = play(args.seconds, frames)
    sheet = Path(args.out) / "sheet.png"
    contact_sheet(frames, sheet)

    print(f"\n{kept} frames in {frames}")
    print(f"contact sheet: {sheet}")
    print("\nPick the moments, then save each at 540px wide as WebP (quality 82) into")
    print("website/assets/img/shots/. The site currently uses six:")
    print("  hero    real-run, real-score")
    print("  fan     real-countdown, real-hit, real-strike, real-burst")
    print("A run with no strike flash and no end-of-run burst is worth re-recording.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
