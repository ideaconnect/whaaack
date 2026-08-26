"""Rebuild the Zepp OS edition's bitmaps from the assets the phone game already ships.

    python tools/generate_zepp_assets.py            # write the assets
    python tools/generate_zepp_assets.py --preview  # also write tools/zepp-preview.png

Three jobs, and the first two want opposite resamplers:

fruit sprites  `app/src/main/assets/fruits/*.png` are 32px pixel art. The watch draws
               them at 64px, which is exactly 2x, so NEAREST keeps every pixel a clean
               2x2 block. LANCZOS at this scale would blur the dither into mush and
               cost bytes as well - a watch app's whole bundle is measured in hundreds
               of kilobytes.

splat sprites  the phone keeps a splat as an ALPHA_8 mask and colours it at draw time
               with a gradient shader, picking one of thirty-six masks and a random
               angle per hit. A watch has no shader and no canvas, only bitmap widgets,
               so the colour has to be baked in and every (fruit, mask) pair that could
               appear has to exist as a file. That is a bundle-size budget rather than
               a free parameter, so the thirty-six masks are dealt out one each across
               twelve fruits by three variants: every sprite is a shape no other sprite
               uses, at a twelfth of what the full cross product would weigh. These are
               organic blobs rather than pixel art, so here LANCZOS is the right
               resampler and NEAREST would serrate the rim.

app icon       the same master the launcher icons come from, framed the same way: the
               artwork's centroid circle pinned to a fraction of the canvas, over the
               brand background, cut to a circle. Zepp OS shows app icons round on
               every device this build targets.

badges         the four Play Games achievement icons, cut to their inscribed circle and
               brought down to badge size, plus a trophy drawn to match them. Those
               four are already exactly the milestones the watch wants - 30, 60, 90 and
               120 seconds - and they already read as a ladder at a glance, because the
               ring around each is a clock that fills as the tier climbs. That is what
               survives the trip down to 50 pixels; the number in the middle does not,
               quite, and does not need to.

Sizes here are *design* pixels for a 480px watch, which is what `app.json` declares as
the target platform width. Zepp OS rescales bitmaps for a device whose screen differs
(the 44mm T-Rex 3 Pro is 466), the same way `px()` rescales coordinates - so nothing in
this script needs a per-device branch.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import iconkit as K  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
FRUIT_SRC = ROOT / "app" / "src" / "main" / "assets" / "fruits"
SPLAT_SRC = ROOT / "app" / "src" / "main" / "assets" / "splats"
BADGE_SRC = ROOT / "assets" / "achievements"
FRUIT_KT = ROOT / "app" / "src" / "main" / "java" / "tech" / "idct" / "whaaack" / "game" / "Fruits.kt"
ICON_SRC = ROOT / "assets" / "icon" / "icon-2.png"
OUT = ROOT / "zeppos" / "assets" / "default.r"

# Matches ic_launcher_background / orchard_night. The watch game itself is black now (see
# zeppos/shared/theme.js), but an app icon is drawn on the Zepp app's list rather than on
# one of our pages, so it keeps the brand colour behind it.
BACKGROUND = (0x2A, 0x16, 0x33, 0xFF)

# 32px art at exactly 2x. Kept in step with FRUIT_PX in zeppos/shared/layout.js.
FRUIT_PX = 64

SUPERSAMPLE = 4

# 1.18 tiles, as on the phone. Kept in step with SPLAT in zeppos/shared/layout.js.
SPLAT_PX = 108
# Kept in step with SPLAT_VARIANTS in zeppos/shared/engine.js.
SPLAT_VARIANTS = 3

# Where the phone's gradient stops holding its light colour and starts ramping to the
# dark one, as a fraction along the splat's leading diagonal. From GameRenderer.drawSplat.
SPLAT_LIGHT_STOP = 0.38

# A fully transparent tile-sized bitmap. It exists because a Zepp OS BUTTON is the only
# widget that reliably receives a tap, and a button needs *something* to draw: given this,
# it draws nothing and the tile beneath it shows through. See page/game/index.page.js.
TAP_PX = 4

# Result-screen badges. Kept in step with BADGE in zeppos/shared/layout.js, and with
# SURVIVE_TIERS in zeppos/shared/engine.js - the seconds here are the file names the page
# asks for, so a tier the engine knows about and this script does not is a blank badge.
BADGE_PX = 50
BADGE_TIERS = (30, 60, 90, 120)

# generate_achievement_icons.py's own palette, so the trophy sits in the row as a peer of
# the four rather than as something from another set.
ORCHARD_NIGHT = (0x2A, 0x16, 0x33)
CROWN_GOLD = (0xF3, 0xC3, 0x3C)

# The size zeus scaffolds, and what the Zepp app's list draws from.
ICON_PX = 248
# The artwork sits inside its own tile rather than flush to the edge, so the round
# crop never shaves the splat.
ICON_ART_DIA = 206


def build_fruits() -> tuple[int, list[str]]:
    written = []
    total = 0
    for src in sorted(FRUIT_SRC.glob("*.png")):
        art = Image.open(src).convert("RGBA")
        if art.size != (32, 32):
            raise SystemExit(f"{src.name}: expected 32x32 pixel art, found {art.size}")
        scaled = art.resize((FRUIT_PX, FRUIT_PX), Image.NEAREST)
        dest = OUT / f"fruit-{src.stem}.png"
        total += K.save_png(scaled, dest)
        written.append(src.stem)
    return total, written


def read_fruit_palette() -> list[tuple[str, tuple[int, int, int], tuple[int, int, int]]]:
    """The twelve fruits and their two splat colours, read out of `Fruits.kt`.

    Parsed rather than copied because a copy is a second place for the palette to live
    and no way to notice when the two stop agreeing. If that enum is ever reformatted
    this fails loudly on the next run, which is the outcome worth having: the quiet
    alternative ships thirty-six sprites in the wrong colours.
    """
    pattern = re.compile(
        r'^\s*[A-Z]+\("fruits/([a-z]+)\.png",\s*'
        r"0x([0-9A-Fa-f]{8})\.toInt\(\),\s*"
        r"0x([0-9A-Fa-f]{8})\.toInt\(\)",
        re.MULTILINE,
    )

    def rgb(argb: str) -> tuple[int, int, int]:
        return int(argb[2:4], 16), int(argb[4:6], 16), int(argb[6:8], 16)

    found = [
        (name, rgb(light), rgb(dark))
        for name, light, dark in pattern.findall(FRUIT_KT.read_text())
    ]
    if len(found) != 12:
        raise SystemExit(f"{FRUIT_KT.name}: parsed {len(found)} fruit, expected 12")
    return found


def tint(mask: Image.Image, light: tuple[int, int, int], dark: tuple[int, int, int]) -> Image.Image:
    """Paints `mask`'s alpha with the phone's diagonal light-to-dark gradient."""
    alpha = np.asarray(mask.convert("RGBA"))[..., 3]
    size = alpha.shape[0]
    # The phone's shader runs (0,0) to (1,1) across the splat's own box, so the parameter
    # at a pixel is how far along the leading diagonal it sits.
    diagonal = np.add.outer(np.arange(size), np.arange(size)) / (2 * (size - 1))
    t = np.clip((diagonal - SPLAT_LIGHT_STOP) / (1 - SPLAT_LIGHT_STOP), 0, 1)[..., None]
    colour = np.asarray(light, np.float32) * (1 - t) + np.asarray(dark, np.float32) * t
    out = np.empty((size, size, 4), np.uint8)
    out[..., :3] = np.rint(colour)
    out[..., 3] = alpha
    return Image.fromarray(out, "RGBA")


def build_splats() -> tuple[int, int]:
    masks = sorted(SPLAT_SRC.glob("splat*.png"))
    fruits = read_fruit_palette()
    wanted = len(fruits) * SPLAT_VARIANTS
    if len(masks) < wanted:
        raise SystemExit(f"{SPLAT_SRC.name}: {len(masks)} masks, need {wanted}")

    total = 0
    for index, (name, light, dark) in enumerate(fruits):
        for variant in range(SPLAT_VARIANTS):
            # One mask each, so no two sprites in the set share a silhouette.
            mask = Image.open(masks[index * SPLAT_VARIANTS + variant])
            art = tint(mask, light, dark).resize((SPLAT_PX, SPLAT_PX), Image.LANCZOS)
            total += K.save_png(art, OUT / f"splat-{name}-{variant}.png", allow_palette=False)
    return total, wanted


def build_badges() -> tuple[int, int]:
    """The four survival icons at badge size, and a trophy to stand beside them."""
    total = 0
    for seconds in BADGE_TIERS:
        source = BADGE_SRC / f"survive-{seconds}.png"
        if not source.exists():
            raise SystemExit(f"missing {source} - run tools/generate_achievement_icons.py")
        art = Image.open(source).convert("RGBA").resize((BADGE_PX, BADGE_PX), Image.LANCZOS)
        # Play Games wants an opaque square; a black watch face wants a disc. The corners
        # of that square are the brand purple, and left on they read as a tile rather than
        # as a badge - which on a board made of tiles is exactly the wrong thing to look
        # like.
        art.putalpha(K.circle_mask(BADGE_PX))
        total += K.save_png(art, OUT / f"badge-{seconds}.png", allow_palette=False)

    total += K.save_png(build_trophy(), OUT / "badge-best.png", allow_palette=False)
    return total, len(BADGE_TIERS) + 1


def build_trophy() -> Image.Image:
    """A cup on a plinth, inside the same ring the 120-second icon closes.

    Drawn rather than borrowed. It has to belong to the set - same disc, same gold, same
    weight of ring - and there is no achievement art for "beat your own record" because
    Play Games has no such achievement: it is a thing only this watch knows about, since
    only this watch keeps the previous best.
    """
    s = SUPERSAMPLE
    size = BADGE_PX * s
    art = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    pen = ImageDraw.Draw(art)
    unit = size / 100

    def box(x0, y0, x1, y1):
        return (x0 * unit, y0 * unit, x1 * unit, y1 * unit)

    pen.ellipse(box(0, 0, 99.5, 99.5), fill=ORCHARD_NIGHT + (255,))
    pen.ellipse(box(4, 4, 95.5, 95.5), outline=CROWN_GOLD + (255,), width=round(6 * unit))

    gold = CROWN_GOLD + (255,)
    # Handles first, so the bowl's edge covers where they join it.
    for x0, x1 in ((20, 38), (62, 80)):
        pen.ellipse(box(x0, 30, x1, 56), outline=gold, width=round(5 * unit))
    # The bowl: straight shoulders into a rounded base, which is the silhouette that reads
    # as a trophy at this size where a tapered cup reads as a plant pot.
    pen.polygon(
        [(29 * unit, 26 * unit), (71 * unit, 26 * unit), (65 * unit, 52 * unit), (35 * unit, 52 * unit)],
        fill=gold,
    )
    pen.pieslice(box(35, 38, 65, 64), start=0, end=180, fill=gold)
    pen.rectangle(box(26, 24, 74, 30), fill=gold)
    pen.rectangle(box(45, 62, 55, 72), fill=gold)
    pen.rounded_rectangle(box(32, 71, 68, 79), radius=3 * unit, fill=gold)

    return art.resize((BADGE_PX, BADGE_PX), Image.LANCZOS)


def build_tap_target() -> int:
    blank = Image.new("RGBA", (TAP_PX, TAP_PX), (0, 0, 0, 0))
    return K.save_png(blank, OUT / "tap.png")


def build_icon() -> int:
    src = Image.open(ICON_SRC).convert("RGBA")
    alpha = np.asarray(src)[..., 3]
    circle = K.centroid_circle(alpha, alpha > 40)
    out = Image.new("RGBA", (ICON_PX, ICON_PX), BACKGROUND)
    out.alpha_composite(K.place(src, circle, ICON_PX, ICON_ART_DIA))
    out.putalpha(K.circle_mask(ICON_PX))
    return K.save_png(out, OUT / "icon.png", allow_palette=False)


def write_preview(fruits: list[str]) -> None:
    cols = 6
    cell = FRUIT_PX + 12
    rows = (len(fruits) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell, rows * cell + ICON_PX + 24), BACKGROUND)
    sheet.alpha_composite(Image.open(OUT / "icon.png"), ((sheet.width - ICON_PX) // 2, 12))
    for i, stem in enumerate(fruits):
        img = Image.open(OUT / f"fruit-{stem}.png")
        x = (i % cols) * cell + 6
        y = (i // cols) * cell + ICON_PX + 18
        sheet.alpha_composite(img, (x, y))
    sheet.convert("RGB").save(ROOT / "tools" / "zepp-preview.png")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true", help="also write tools/zepp-preview.png")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    fruit_bytes, fruits = build_fruits()
    splat_bytes, splat_count = build_splats()
    badge_bytes, badge_count = build_badges()
    icon_bytes = build_icon()
    tap_bytes = build_tap_target()

    print(f"{len(fruits)} fruit at {FRUIT_PX}px: {fruit_bytes:,} bytes")
    print(f"{splat_count} splats at {SPLAT_PX}px: {splat_bytes:,} bytes")
    print(f"{badge_count} badges at {BADGE_PX}px: {badge_bytes:,} bytes")
    print(f"icon at {ICON_PX}px: {icon_bytes:,} bytes")
    print(f"transparent tap target at {TAP_PX}px: {tap_bytes:,} bytes")

    if args.preview:
        write_preview(fruits)
        print("wrote tools/zepp-preview.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
