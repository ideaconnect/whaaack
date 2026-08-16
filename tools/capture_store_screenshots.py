"""Capture the eight raw frames the Play listing is built from, by playing a real run.

    python tools/capture_store_screenshots.py
    python tools/capture_store_screenshots.py --seconds 90     # try longer for a strike

Then pad them to the listing's 9:16 slot:

    python tools/make_store_screenshots.py --src build/store-capture

This is the step that was missing. `make_store_screenshots.py` has always wanted eight
specific filenames and nothing in the repo produced them, so refreshing the listing meant
somebody capturing a run by hand and naming the files right from memory. Everything the store
shows of the game now comes out of one command, off a real device, with no mockups.

## What it does about the awkward parts

**Somebody has to play.** Look at the board, find the fruit, tap them. The hole centres are the
same measured constants `capture_gameplay.py` uses and carry the same caveat — they are for a
1280x2856 screen, and a different one needs re-measuring.

Finding the fruit is *not* the same, and the difference is the whole reason a run used to last
four seconds. `capture_gameplay.py` scores each hole by mean saturation times value and takes
the highest. On this art that measures the background: the board is translucent, the sky behind
the top row is a saturated blue that scores 0.20, and a fruit sitting over the dark trees behind
the bottom row scores 0.12 — so the argmax reliably picked an empty tile full of sky, the bot
tapped nothing for the whole run, and three fruit escaped before it landed a second hit.

What separates fruit from sky is hue, not saturation. The sky occupies a narrow blue band and
every fruit in the set sits outside it, so counting saturated, bright, non-blue pixels scores a
tile with fruit in the thousands and an empty one at exactly zero. There is no threshold to
tune between those.

**Tapping has to be its own thread.** `START_LIFE_MS` is 1250 and falls from there, while a
`screencap` alone is 530ms — so a loop that captures, decides, then taps is looking at a frame
already a third of a fruit's life out of date and lands the tap with milliseconds to spare.
That is why the bot could not hold a run open regardless of where it aimed.

So the two are separated. A background thread sweeps all sixteen tiles every 350ms and never
waits for a capture; the main thread captures at whatever pace adb gives it. Sweeping blind is
allowed because `postTap` queues a tile and `drainTaps` consumes it — a tap on an empty tile
costs nothing at all, no strike and no penalty — and sixteen of them fit in one `adb shell` in
410ms, where the round trip is the expensive part rather than the taps. Nothing in the frames
is faked by this: every fruit is hit the way a player hits it, just by someone with sixteen
thumbs. It is what lets the run climb to the speeds worth photographing.

**The Play button moves.** `capture_gameplay.py` hard-codes it and warns that the ad-free
upsell row shifts it, which is exactly the kind of constant that is wrong on somebody else's
device and fails as a tap into empty sky. It is found here instead: the button is the only
large block of the orange the rest of the UI never uses, so scanning for that is both simpler
and steadier than measuring it.

**Release, not debug.** The listing must not show `REMOVE_ADS_PLACEHOLDER_PRICE`. A debug build
carries one, so Home renders an upsell row that a real ad-free player never sees, and the
screenshots would advertise a price for a thing the screenshot is claiming you already have.
The script refuses a debug build rather than quietly shooting it.

**The status bar.** SystemUI demo mode pins it to 12:00, full battery, one wifi bar and nothing
else, so eight screenshots do not show eight different clocks and a dying battery.

## Choosing the moments

The four gameplay frames used to be picked by eye off a contact sheet. They are scored here
instead — strikes by the lit dots in the HUD's top right, splats by how much saturated colour
is sitting on the board — so the same run produces the same eight files twice running. The
contact sheet is still written, because the scoring picks a *good* frame and a human may want
a better one.

The run has two halves, and the second one exists entirely to get `strike_03.png`. A bot that
taps everything never takes a strike, so it would play until the deadline and the listing would
have no frame showing what losing looks like. So it plays properly for `--seconds`, by which
point the speed has climbed and the board is busy, and then simply stops: three fruit escape,
the dots light one at a time, and the run ends the way a player's does.
"""

from __future__ import annotations

import argparse
import io
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "tech.idct.whaaack"

# Hole centres on a 1280x2856 screen, measured by scanning a real gameplay frame for the dark
# ellipses. Both pitches are 281px. On a different screen size, re-measure.
COLS = (218, 498, 780, 1062)
ROWS = (1256, 1540, 1820, 2100)

# The sample window sits above the hole, where the fruit's body is: a fruit rises out of the
# hole and is drawn with its base on it, so a window centred on the hole is half dark ellipse.
HALF = 95
FRUIT_DY = -55

# What counts as fruit rather than scenery. SKY_HUE is the blue band the background occupies —
# 215 degrees at its bluest — and everything in the fruit set falls outside it, the purple of a
# grape at 275 included. VALUE_MIN drops the trees seen through the translucent board, which
# are the same green as a pear but much darker for it.
SAT_MIN = 0.40
VALUE_MIN = 140
SKY_HUE = (185, 255)
FRUIT_PIXELS = 1200       # an occupied tile scores thousands; an empty one, zero

# The strike dots live in the HUD's top-right corner. A generous box rather than three sampled
# points, because the exact x depends on the display cutout's insets.
STRIKE_BOX = (1020, 120, 1280, 260)     # left, top, right, bottom

# The board panel, for measuring how much splat colour is on screen. Same 1280x2856 caveat.
BOARD_BOX = (60, 1020, 1225, 2180)

# Play's orange, from HomeScreen's gradient: 0xFFFFC46B .. 0xFFE2574C.
ORANGE = np.array([245, 140, 90], np.float32)

# Frame -> the name make_store_screenshots.py expects.
OUTPUTS = {
    "home": "01_home_adfree.png",
    "countdown": "02_countdown.png",
    "early": "03_early.png",
    "splats": "04_splats.png",
    "late": "06_late.png",
    "strike": "strike_03.png",
    "gameover": "07_gameover_adfree.png",
    "leaderboard": "08_leaderboard.png",
}


def adb(*args: str, binary: bool = False):
    out = subprocess.run(["adb", *args], capture_output=True)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def screen() -> np.ndarray:
    return np.asarray(
        Image.open(io.BytesIO(adb("exec-out", "screencap", "-p", binary=True))).convert("RGB"),
        np.float32,
    )


def save(frame: np.ndarray, path: Path) -> None:
    Image.fromarray(frame.astype(np.uint8)).save(path)


# ---- the device ---------------------------------------------------------------------

def demo_bar(on: bool) -> None:
    """Pin the status bar, or hand it back."""
    if not on:
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo",
            "-e", "command", "exit")
        return
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    for args in (
        ("command", "enter"),
        ("command", "clock", "-e", "hhmm", "1200"),
        ("command", "battery", "-e", "level", "100", "-e", "plugged", "false"),
        # Mobile hidden rather than shown: with both on, the emulator draws its own wifi
        # glyph beside the demo one and the bar has two. `fully` is what clears the little
        # "!" Android hangs off wifi that failed its captive-portal probe — which an emulator
        # behind a corporate network does routinely, and which would otherwise be sitting in
        # the corner of all eight store screenshots saying the app has no connection.
        ("command", "network", "-e", "wifi", "show", "-e", "level", "4",
         "-e", "fully", "true", "-e", "mobile", "hide"),
        ("command", "notifications", "-e", "visible", "false"),
        ("command", "status", "-e", "volume", "hide", "-e", "bluetooth", "hide",
         "-e", "location", "hide", "-e", "alarm", "hide", "-e", "sync", "hide",
         "-e", "tty", "hide", "-e", "eri", "hide", "-e", "mute", "hide",
         "-e", "speakerphone", "hide"),
    ):
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", *args)


def launch() -> None:
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(8)


# Google's UMP buttons, which are its own blue rather than anything from this app's palette.
UMP_BLUE = np.array([26, 115, 232], np.float32)


def dismiss_consent() -> bool:
    """Answer the ad-consent sheet if it is up. True if one was dismissed.

    It only appears where there is a network to fetch it over, so it is absent on a device
    that has never had one and unmissable on a fresh emulator the moment it does — which is a
    nasty way for a capture run to fail, because the failure is "could not find the Play
    button" on a home screen that is simply behind a dialog.

    Found by colour, like the Play button: UMP draws its two actions in Google blue, which is
    nowhere in this app's palette, and the top one is the one that consents. Tapping it is the
    right default here — these are screenshots of the app, not of its consent flow — and the
    choice persists, so this fires once per emulator and never again.
    """
    frame = screen()
    dist = np.linalg.norm(frame - UMP_BLUE, axis=2)
    hot = dist < 70
    rows = np.flatnonzero(hot.sum(axis=1) > frame.shape[1] * 0.4)
    if rows.size == 0:
        return False
    y = int(rows[0] + (rows[-1] - rows[0]) * 0.12)   # the first of the two pills
    cols = np.flatnonzero(hot[y])
    if cols.size == 0:
        return False
    x = int((cols[0] + cols[-1]) / 2)
    print(f"  answering the consent sheet at ({x}, {y})")
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(4)
    return True


def is_debug_build() -> bool:
    return ".debug" in adb("shell", "pm", "list", "packages", PACKAGE)


# ---- reading the screen -------------------------------------------------------------

def find_play_button(frame: np.ndarray):
    """(x, y) of the 'Play for fun' pill, found by its colour."""
    dist = np.linalg.norm(frame - ORANGE, axis=2)
    hot = dist < 60
    # Below the halfway line only: the fruit row under the wordmark is orange too, and a
    # strawberry is not a button.
    hot[: frame.shape[0] // 2, :] = False
    rows = np.flatnonzero(hot.sum(axis=1) > frame.shape[1] * 0.4)
    if rows.size == 0:
        return None
    y = int((rows[0] + rows[-1]) / 2)
    cols = np.flatnonzero(hot[y])
    if cols.size == 0:
        return None
    return int((cols[0] + cols[-1]) / 2), y


def fruit_pixels(frame: np.ndarray, x: int, y: int) -> int:
    """Saturated, bright, non-sky-blue pixels above one hole. Fruit score thousands; sky, 0."""
    cell = frame[y + FRUIT_DY - HALF:y + FRUIT_DY + HALF, x - HALF:x + HALF]
    if cell.size == 0:
        return 0
    red, green, blue = cell[..., 0], cell[..., 1], cell[..., 2]
    mx, mn = cell.max(axis=2), cell.min(axis=2)
    span = np.maximum(mx - mn, 1e-6)
    sat = (mx - mn) / np.maximum(mx, 1.0)

    hue = np.zeros_like(mx)
    m = mx == red
    hue[m] = ((green - blue)[m] / span[m]) % 6
    m = mx == green
    hue[m] = ((blue - red)[m] / span[m]) + 2
    m = mx == blue
    hue[m] = ((red - green)[m] / span[m]) + 4
    hue *= 60

    lo, hi = SKY_HUE
    return int(((sat > SAT_MIN) & (mx > VALUE_MIN) & ~((hue >= lo) & (hue <= hi))).sum())


def occupied(frame: np.ndarray) -> list[tuple[int, int]]:
    """Every hole with a fruit over it, brightest first."""
    found = []
    for x in COLS:
        for y in ROWS:
            score = fruit_pixels(frame, x, y)
            if score >= FRUIT_PIXELS:
                found.append((score, x, y))
    found.sort(reverse=True)
    return [(x, y) for _, x, y in found]


def tap_all(points: list[tuple[int, int]]) -> None:
    """Every target in one round trip — the adb hop costs far more than the taps."""
    if not points:
        return
    adb("shell", ";".join(f"input tap {x} {y}" for x, y in points))


ALL_TILES = [(x, y) for y in ROWS for x in COLS]


class Sweeper(threading.Thread):
    """Taps the whole board on a loop, so play never waits on a screencap.

    Gated on a lease rather than free-running, and that is not a nicety.
    `capture_gameplay.py` warns in its docstring that taps which keep landing after a run
    walk straight into the menus and that the auth screens are one of the places they land —
    and a blind sweep is far better at it than a single tap was, because the bottom row of the
    board sits exactly where the summary puts **Play again**. The first version of this
    finished a run, kept sweeping, and captured twenty frames of the password-reset screen.

    So the capture loop hands out a short lease every time it sees a live board, and a sweep
    that cannot show a lease does not happen. If the loop breaks, stalls or simply stops
    liking what it sees, tapping stops on its own within [LEASE].
    """

    PERIOD = 0.35
    LEASE = 1.2

    def __init__(self) -> None:
        super().__init__(daemon=True)
        # Not `_stop`: Thread already has a private method by that name and shadowing it
        # breaks is_alive(), which fails inside threading rather than here.
        self._done = threading.Event()
        self._lease_until = 0.0

    def keep_alive(self) -> None:
        self._lease_until = time.time() + self.LEASE

    def run(self) -> None:
        while not self._done.is_set():
            if time.time() < self._lease_until:
                tap_all(ALL_TILES)
            self._done.wait(self.PERIOD)

    @property
    def sweeping(self) -> bool:
        return not self._done.is_set()

    def stop(self) -> None:
        self._done.set()
        self._lease_until = 0.0


def strike_pixels(frame: np.ndarray) -> int:
    """How much lit-strike red is in the HUD corner. Zero strikes reads as ~0."""
    l, t, r, b = STRIKE_BOX
    box = frame[t:b, l:r]
    if box.size == 0:
        return 0
    red, green, blue = box[..., 0], box[..., 1], box[..., 2]
    return int(((red > 150) & (red > green * 1.7) & (red > blue * 1.7)).sum())


def board_colour(frame: np.ndarray) -> int:
    """Splat and fruit colour on the board.

    Sky-blue is excluded for the same reason it is excluded from fruit detection, and getting
    that wrong here is just as quiet: counting plain saturation made an *empty* early board
    score highest, because what it was really measuring was how much bright blue was visible
    through the translucent panel. The busiest, most splattered frame of the run has clouds
    behind it and scored lower than the first frame of all.
    """
    l, t, r, b = BOARD_BOX
    box = frame[t:b, l:r]
    if box.size == 0:
        return 0
    red, green, blue = box[..., 0], box[..., 1], box[..., 2]
    mx, mn = box.max(axis=2), box.min(axis=2)
    span = np.maximum(mx - mn, 1e-6)
    sat = (mx - mn) / np.maximum(mx, 1.0)

    hue = np.zeros_like(mx)
    m = mx == red
    hue[m] = ((green - blue)[m] / span[m]) % 6
    m = mx == green
    hue[m] = ((blue - red)[m] / span[m]) + 2
    m = mx == blue
    hue[m] = ((red - green)[m] / span[m]) + 4
    hue *= 60

    lo, hi = SKY_HUE
    return int(((sat > 0.35) & (mx > 120) & ~((hue >= lo) & (hue <= hi))).sum())


# Between the bottom of the score card and the top of the board: sky and cloud, always, on
# every frame of every run. Nothing is ever drawn here, which is the point.
SKY_BAND = (700, 1050)


def strike_flash(frame: np.ndarray) -> float:
    """How red the sky is. A lapse tints the whole screen for STRIKE_FLASH_MS.

    Measured on a band that holds nothing but background, rather than on the whole frame.
    Averaged over everything, a board carrying six red splats and a strawberry outscores a
    real flash, and the strike screenshot becomes whichever frame happened to be reddest —
    which is to say, the busiest one, which is already the splats screenshot.
    """
    top, bottom = SKY_BAND
    band = frame[top:bottom]
    return float(band[..., 0].mean() - band[..., 2].mean())


# ---- the run ------------------------------------------------------------------------

def play(seconds: float, frames: Path) -> list[dict]:
    """Sweep for `seconds`, then stop and let the run end. One record per frame."""
    kept: list[dict] = []
    sweeper = Sweeper()
    sweeper.start()
    deadline = time.time() + seconds
    # A hard stop on the second half too: three fruit take a couple of seconds to escape,
    # and if something has gone wrong they never will.
    letting_go_until = None

    try:
        while True:
            frame = screen()
            # The orange pill only exists on Home and on the summary, never mid-run, so it
            # is the tell that the run has ended and every further tap lands in a menu.
            if find_play_button(frame) is not None:
                sweeper.stop()
                break
            # A live board, so the sweeper may go on tapping for another lease.
            if sweeper.sweeping:
                sweeper.keep_alive()

            path = frames / f"f{len(kept):03d}.png"
            save(frame, path)
            playing = sweeper.sweeping
            kept.append({
                "path": path,
                "fruit": bool(occupied(frame)),
                "playing": playing,
                "strikes": strike_pixels(frame),
                "colour": board_colour(frame),
                "flash": strike_flash(frame),
            })

            if playing and time.time() > deadline:
                print(f"  {len(kept)} frames played; letting three escape")
                sweeper.stop()
                letting_go_until = time.time() + 25
            if letting_go_until and time.time() > letting_go_until:
                break
    finally:
        sweeper.stop()
    return kept


def pick(records: list[dict]) -> dict:
    """Choose the four gameplay moments. Falls back rather than failing on a short run."""
    played = [r for r in records if r["playing"]] or records
    live = [r for r in played if r["fruit"]] or played
    if not live:
        return {}
    third = max(1, len(live) // 3)

    # The calmest board in the opening third. Not "the first frame with no strikes": a fruit
    # can escape inside the first second, and then that rule walks the whole run looking for a
    # clean HUD and hands back a frame from the very end.
    early = min(live[: max(2, third)], key=lambda r: (r["strikes"], r["colour"]))
    late = live[-third:][0] if len(live) > third else live[-1]
    # Never the frame already spoken for: on a short run the busiest board can be the opening
    # one, and two identical screenshots in an eight-slot listing is a wasted slot.
    splats = max((r for r in live if r is not early and r is not late),
                 key=lambda r: r["colour"], default=live[-1])
    # The flash, if the run had one. A lapse tints the whole screen red for 420ms and that
    # single frame says "you just lost one" far better than three dots in a corner do; the
    # dots are the fallback for a run where no capture happened to land inside the flash.
    # Relative to the run's own sky, not an absolute number: the background scrolls through
    # cloud and open blue, so what counts as "redder than usual" is a property of the run.
    calm = sorted(r["flash"] for r in records)[len(records) // 2]
    flashes = [r for r in records if r["flash"] > calm + 10]
    taken = {id(early), id(late), id(splats)}
    # Ranked by what is on the board rather than by how red it is, and never a frame already
    # spoken for — the reddest flash and the splattiest board are often the same one, and two
    # identical screenshots waste a slot in an eight-slot listing.
    pool = [r for r in (flashes or records) if id(r) not in taken] or [records[-1]]
    strike = max(pool, key=lambda r: (r["strikes"], r["colour"]))
    return {"early": early, "late": late, "splats": splats, "strike": strike}


# The six frames the site floats in its hero and its fan, and which raw capture each is.
# `capture_gameplay.py` ends by telling whoever ran it to pick these by eye and save them at
# 540px as WebP; the moments are already chosen by then, so it may as well write them.
WEBSITE_SHOTS = {
    "real-countdown": "02_countdown.png",
    "real-hit": "03_early.png",
    "real-burst": "04_splats.png",
    "real-run": "06_late.png",
    "real-strike": "strike_03.png",
    "real-score": "07_gameover_adfree.png",
}
WEBSITE_WIDTH = 540


def website_shots(out: Path) -> int:
    """Write the site's WebP stills from the captures already in `out`."""
    dest = ROOT / "website" / "assets" / "img" / "shots"
    dest.mkdir(parents=True, exist_ok=True)
    written = 0
    for name, source in WEBSITE_SHOTS.items():
        path = out / source
        if not path.exists():
            print(f"  no {source} to make {name}.webp from", file=sys.stderr)
            continue
        img = Image.open(path).convert("RGB")
        height = round(img.height * WEBSITE_WIDTH / img.width)
        img = img.resize((WEBSITE_WIDTH, height), Image.LANCZOS)
        target = dest / f"{name}.webp"
        img.save(target, "WEBP", quality=82, method=6)
        print(f"  {target.name:<22} {img.width}x{img.height}  {target.stat().st_size // 1024} KiB")
        written += 1
    return written


def contact_sheet(records: list[dict], out: Path) -> None:
    if not records:
        return
    thumbs = [Image.open(r["path"]).resize((110, 245)) for r in records]
    cols = 12
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * 110, rows * 245), (18, 35, 61))
    for i, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((i % cols) * 110, (i // cols) * 245))
    sheet.save(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    # Comfortably short of where a swept run dies on its own — around a minute, once the
    # fruit life falls below the sweep period. Stopping first is what puts the end of the run
    # under this script's control instead of the orchard's, and the sweeper is safely idle by
    # the time the summary appears.
    ap.add_argument("--seconds", type=float, default=45, help="how long to keep playing")
    ap.add_argument("--out", default=str(ROOT / "build" / "store-capture"))
    ap.add_argument("--keep-demo-bar", action="store_true",
                    help="leave SystemUI demo mode on afterwards")
    ap.add_argument("--website-only", action="store_true",
                    help="re-derive the site's WebP stills from an existing capture, no device")
    args = ap.parse_args()

    out = Path(args.out)

    if args.website_only:
        print(f"website stills from {out}")
        return 0 if website_shots(out) == len(WEBSITE_SHOTS) else 1

    frames = out / "frames"
    frames.mkdir(parents=True, exist_ok=True)
    for stale in list(frames.glob("f*.png")) + list(out.glob("*.png")):
        stale.unlink()

    if not adb("devices").strip().splitlines()[1:]:
        print("no device attached", file=sys.stderr)
        return 1
    if is_debug_build():
        print("that is a debug build — its placeholder ad price would be in the shots",
              file=sys.stderr)
        return 1

    demo_bar(True)
    try:
        print("launching")
        launch()
        if dismiss_consent():
            # It is drawn over Home rather than replacing it, but the app re-lays-out behind
            # it, so the shot is taken after rather than before.
            time.sleep(2)

        home = screen()
        save(home, out / OUTPUTS["home"])
        button = find_play_button(home)
        if button is None:
            print("could not find the Play button on the home screen "
                  "(a dialog over it, or a debug build's upsell row moving it)",
                  file=sys.stderr)
            return 1
        print(f"  play button at {button}")

        adb("shell", "input", "tap", str(button[0]), str(button[1]))
        # Into the 3-2-1, which runs for three seconds. Late enough that the screen has
        # changed, early enough that the "3" is still the one on it.
        time.sleep(0.7)
        save(screen(), out / OUTPUTS["countdown"])
        time.sleep(2.6)

        print("playing")
        records = play(args.seconds, frames)
        print(f"  {len(records)} frames")

        # The run is over, so the screen is the summary.
        time.sleep(1.5)
        save(screen(), out / OUTPUTS["gameover"])

        chosen = pick(records)
        for name, record in chosen.items():
            shutil.copyfile(record["path"], out / OUTPUTS[name])
            print(f"  {OUTPUTS[name]:<24} frame {record['path'].stem}"
                  f"  strikes~{record['strikes']:>5}  colour~{record['colour']:>7}")

        # Home, then the board. Back twice: the summary goes to Home, and a stray Back on
        # Home would leave the app.
        adb("shell", "input", "keyevent", "4")
        time.sleep(2)
        home2 = screen()
        button2 = find_play_button(home2) or button
        # The Leaderboard chip is the bottom-left of the pair under the ranked button, a
        # little over two button-heights below Play. Measured off the release layout.
        adb("shell", "input", "tap", str(int(home2.shape[1] * 0.27)), str(button2[1] + 435))
        time.sleep(3)
        save(screen(), out / OUTPUTS["leaderboard"])

        contact_sheet(records, out / "sheet.png")
        print("website stills")
        website_shots(out)
    finally:
        if not args.keep_demo_bar:
            demo_bar(False)

    missing = [n for n in OUTPUTS.values() if not (out / n).exists()]
    print(f"\n-> {out}")
    if missing:
        print("missing:", ", ".join(missing), file=sys.stderr)
        return 1
    print(f"contact sheet: {out / 'sheet.png'}")
    print("\nCheck them, then:")
    print(f"  python tools/make_store_screenshots.py --src {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
