"""Build the Game Stats icons from the game's own palette and art.

    python tools/generate_stat_icons.py            # write the icons
    python tools/generate_stat_icons.py --preview  # also write a contact sheet

Writes one 512x512 PNG per row of assets/game-stats/RepetitiveStatsConfig.csv, into
assets/game-stats/icons/, named by that row's Icon File Name column. The CSV is the
source of truth for *which* icons exist; this file only decides what each looks like,
so adding a stat means adding a row there and a glyph here under the same name.

Unlike the achievement badges, these are deliberately **not** a family. Play's stats
validation flags visual duplicates, and seven variations on one ring would be exactly
that — so each stat gets its own silhouette and its own hue out of Theme.kt, and the
only thing they share is the orchard ground they sit on. That ground and the font
helper are imported from the achievement generator rather than copied, so a change to
the house style reaches both.

Play's rules this obeys: exactly 512x512, PNG, well under the 1 MB per-icon cap, no
text baked in (the display name is localised separately, the icon is not), and an
opaque background, since a stat icon is composited onto surfaces this file cannot see.
"""

from __future__ import annotations

import argparse
import csv
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_achievement_icons import SIZE, ground  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
STATS_DIR = ROOT / "assets" / "game-stats"
OUT = STATS_DIR / "icons"

CREAM = (255, 243, 230)

# One hue per stat, all lifted from Theme.kt, Fruit.kt or the sky bitmap. Seven distinct
# hues rather than seven shades of one, so the icons stay apart at profile size and
# nothing trips the duplicate check.
HUES = {
    "runs_played": (255, 201, 122),        # AccentLight
    "best_run": (143, 210, 78),            # Success
    "time_survived": (243, 195, 60),       # CrownGold
    "fruit_whacked": (255, 112, 137),      # watermelon
    "top_speed_reached": (242, 112, 79),   # AccentDark
    "minute_runs": (108, 162, 232),        # the sky bitmap, lifted for contrast
    "ranked_runs": (185, 139, 217),        # grape
}

# Every glyph is drawn at this multiple and downsampled, which is where the clean
# curves come from; there is no anti-aliasing code below.
SS = 4


def layer() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    big = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
    return big, ImageDraw.Draw(big)


def flatten(big: Image.Image) -> Image.Image:
    return big.resize((SIZE, SIZE), Image.LANCZOS)


def s(*values: float) -> list[float]:
    """Scale 512-space coordinates into the supersampled canvas."""
    return [v * SS for v in values]


def glow(art: Image.Image, colour: tuple[int, int, int]) -> Image.Image:
    """A soft halo in the glyph's own hue, so it lifts off the dark ground."""
    halo = Image.new("RGBA", art.size, colour + (0,))
    halo.putalpha(art.getchannel("A").point(lambda a: int(a * 0.5)))
    return halo.filter(ImageFilter.GaussianBlur(26))


# ------------------------------------------------------------------ glyphs
# Each returns a 512x512 RGBA layer. They are kept deliberately geometric: a stat icon
# is read at a couple of dozen pixels on a profile, where a silhouette survives and an
# illustration does not.


def runs_played(c: tuple[int, int, int]) -> Image.Image:
    """Three stacked cards — one run, then another, then another."""
    big, d = layer()
    for i, (dx, dy, alpha) in enumerate(((-46, -52, 90), (-14, 4, 165), (18, 60, 255))):
        x0, y0, x1, y1 = s(150 + dx, 150 + dy, 362 + dx, 300 + dy)
        d.rounded_rectangle([x0, y0, x1, y1], radius=28 * SS, fill=c + (alpha,))
        if i == 2:
            # A tick on the front card: a run that finished, not one in progress.
            d.line(s(196, 232, 232, 268), fill=(26, 14, 32, 255), width=16 * SS)
            d.line(s(232, 268, 312, 188), fill=(26, 14, 32, 255), width=16 * SS)
    return flatten(big)


def best_run(c: tuple[int, int, int]) -> Image.Image:
    """A planted flag — the furthest point reached.

    Deliberately not the stopwatch this obviously wants to be: `minute_runs` is already a
    dial, and two circles with hands on one profile is what Play's duplicate check is
    looking for. A flag reads as "how far you got" and shares no silhouette with anything
    else in the set. This is the progression stat, so it heads the profile.
    """
    big, d = layer()
    # Ground, then the pole planted in it.
    d.rounded_rectangle(s(168, 356, 344, 388), radius=16 * SS, fill=CREAM + (255,))
    d.rounded_rectangle(s(186, 128, 214, 368), radius=14 * SS, fill=CREAM + (255,))
    # The pennant. Notched at the fly so it reads as cloth rather than as an arrow.
    d.polygon(s(214, 142, 372, 190, 316, 214, 372, 238, 214, 286), fill=c + (255,))
    return flatten(big)


def time_survived(c: tuple[int, int, int]) -> Image.Image:
    """An hourglass: time that accumulates rather than time that runs out."""
    big, d = layer()
    d.polygon(s(168, 132, 344, 132, 256, 250), fill=c + (110,))
    d.polygon(s(168, 380, 344, 380, 256, 262), fill=c + (255,))
    for y in (120, 380):
        d.rounded_rectangle(s(150, y, 362, y + 32), radius=14 * SS, fill=CREAM + (255,))
    # The falling grain, so the shape reads as an hourglass and not a bow tie.
    d.ellipse(s(246, 258, 266, 290), fill=c + (255,))
    return flatten(big)


def fruit_whacked(c: tuple[int, int, int]) -> Image.Image:
    """The game's own signature: a splat with the fruit still in it."""
    big, _ = layer()
    mask = Image.open(ASSETS / "splats" / "splat05.png").convert("RGBA").getchannel("A")
    ink = Image.new("RGBA", mask.size, c + (255,))
    ink.putalpha(mask)
    ink = ink.resize((330, 330), Image.LANCZOS)
    art = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    art.alpha_composite(ink, ((SIZE - 330) // 2, (SIZE - 330) // 2))
    sprite = Image.open(ASSETS / "fruits" / "watermelon.png").convert("RGBA")
    sprite = sprite.resize((176, 176), Image.NEAREST)
    art.alpha_composite(sprite, ((SIZE - 176) // 2, (SIZE - 176) // 2))
    return art


def top_speed_reached(c: tuple[int, int, int]) -> Image.Image:
    """Three chevrons, brightening — speed, without a speedometer's clock face."""
    big, d = layer()
    for i, (x, alpha) in enumerate(((150, 90), (232, 170), (314, 255))):
        d.line(s(x, 156, x + 76, 256), fill=c + (alpha,), width=30 * SS)
        d.line(s(x + 76, 256, x, 356), fill=c + (alpha,), width=30 * SS)
    return flatten(big)


def minute_runs(c: tuple[int, int, int]) -> Image.Image:
    """A dial with its ticks, hands closed at the top: one full minute."""
    big, d = layer()
    cx, cy, r = 256, 256, 150
    d.ellipse(s(cx - r, cy - r, cx + r, cy + r), outline=c + (255,), width=24 * SS)
    for i in range(12):
        a = math.radians(i * 30 - 90)
        inner = 108 if i % 3 else 92
        d.line(
            s(cx + inner * math.cos(a), cy + inner * math.sin(a),
              cx + 128 * math.cos(a), cy + 128 * math.sin(a)),
            fill=c + (255 if i % 3 == 0 else 120,), width=(14 if i % 3 == 0 else 9) * SS,
        )
    d.line(s(cx, cy, cx, cy - 96), fill=CREAM + (255,), width=18 * SS)
    d.line(s(cx, cy, cx, cy - 62), fill=CREAM + (255,), width=24 * SS)
    d.ellipse(s(cx - 16, cy - 16, cx + 16, cy + 16), fill=CREAM + (255,))
    return flatten(big)


def ranked_runs(c: tuple[int, int, int]) -> Image.Image:
    """A podium. The leaderboard is what ranked runs are for."""
    big, d = layer()
    bars = ((150, 300, 214, 392), (222, 210, 290, 392), (298, 262, 362, 392))
    for i, (x0, y0, x1, y1) in enumerate(bars):
        d.rounded_rectangle(
            s(x0, y0, x1, y1), radius=16 * SS,
            fill=c + (255 if i == 1 else 150,),
        )
    # A marker over the winner's step, so it is a podium and not a bar chart.
    d.ellipse(s(238, 128, 274, 164), fill=CREAM + (255,))
    d.polygon(s(226, 176, 286, 176, 256, 200), fill=CREAM + (255,))
    return flatten(big)


GLYPHS = {
    "runs_played": runs_played,
    "best_run": best_run,
    "time_survived": time_survived,
    "fruit_whacked": fruit_whacked,
    "top_speed_reached": top_speed_reached,
    "minute_runs": minute_runs,
    "ranked_runs": ranked_runs,
}


def icon(stat_id: str) -> Image.Image:
    colour = HUES[stat_id]
    art = GLYPHS[stat_id](colour)
    img = ground()
    img.alpha_composite(glow(art, colour))
    img.alpha_composite(art)
    return img


def wanted() -> list[tuple[str, str]]:
    """(stat id, icon filename) out of both stat configs, so the CSVs and icons cannot drift.

    Read rather than listed: the console rejects an icon nothing references and a reference
    with no icon, so the set of files to produce is exactly what the configs ask for.
    """
    out: list[tuple[str, str]] = []
    for name in ("RepetitiveStatsConfig.csv", "ProgressionStatConfig.csv"):
        path = STATS_DIR / name
        if not path.exists():
            continue
        with path.open(encoding="utf-8", newline="") as f:
            out += [(row["Stat Id"], row["Icon File Name"]) for row in csv.DictReader(f)]
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", action="store_true")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    made: list[tuple[str, Image.Image]] = []
    for stat_id, filename in wanted():
        if stat_id not in GLYPHS:
            print(f"!! no glyph for stat '{stat_id}' — add one to GLYPHS", file=sys.stderr)
            return 1
        img = icon(stat_id)
        path = OUT / filename
        img.convert("RGB").save(path)  # No alpha: the ground is opaque anyway.
        made.append((stat_id, img))
        print(f"{path.relative_to(ROOT)}  {img.size[0]}x{img.size[1]}  "
              f"{path.stat().st_size / 1024:.0f} KB")

    if args.preview:
        pad = 18
        cell = SIZE + pad
        sheet = Image.new("RGBA", (len(made) * cell + pad, SIZE + 128 + pad * 3), (18, 10, 24, 255))
        mask = Image.new("L", (SIZE, SIZE), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, SIZE - 1, SIZE - 1], fill=255)
        for i, (_, img) in enumerate(made):
            x = pad + i * cell
            sheet.paste(img, (x, pad))
            small = img.resize((96, 96), Image.LANCZOS)
            small.putalpha(mask.resize((96, 96), Image.LANCZOS))
            sheet.alpha_composite(small, (x + (SIZE - 96) // 2, SIZE + pad * 2))
        preview = Path(__file__).parent / "stat-icons-preview.png"
        sheet.save(preview)
        print(preview.relative_to(ROOT))

    return 0


if __name__ == "__main__":
    sys.exit(main())
