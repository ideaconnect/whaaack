"""Rebuild the Zepp OS edition's bitmaps from the assets the phone game already ships.

    python tools/generate_zepp_assets.py            # write the assets
    python tools/generate_zepp_assets.py --preview  # also write tools/zepp-preview.png

Two jobs, and they want opposite resamplers:

fruit sprites  `app/src/main/assets/fruits/*.png` are 32px pixel art. The watch draws
               them at 64px, which is exactly 2x, so NEAREST keeps every pixel a clean
               2x2 block. LANCZOS at this scale would blur the dither into mush and
               cost bytes as well - a watch app's whole bundle is measured in hundreds
               of kilobytes.

app icon       the same master the launcher icons come from, framed the same way: the
               artwork's centroid circle pinned to a fraction of the canvas, over the
               brand background, cut to a circle. Zepp OS shows app icons round on
               every device this build targets.

Sizes here are *design* pixels for a 480px watch, which is what `app.json` declares as
the target platform width. Zepp OS rescales bitmaps for a device whose screen differs
(the 44mm T-Rex 3 Pro is 466), the same way `px()` rescales coordinates - so nothing in
this script needs a per-device branch.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import iconkit as K  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
FRUIT_SRC = ROOT / "app" / "src" / "main" / "assets" / "fruits"
ICON_SRC = ROOT / "assets" / "icon" / "icon-2.png"
OUT = ROOT / "zeppos" / "assets" / "default.r"

# Matches ic_launcher_background / orchard_night, and the watch game's own background.
BACKGROUND = (0x2A, 0x16, 0x33, 0xFF)

# 32px art at exactly 2x. Kept in step with FRUIT_PX in zeppos/shared/layout.js.
FRUIT_PX = 64

# A fully transparent tile-sized bitmap. It exists because a Zepp OS BUTTON is the only
# widget that reliably receives a tap, and a button needs *something* to draw: given this,
# it draws nothing and the tile beneath it shows through. See page/game/index.page.js.
TAP_PX = 4

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
    icon_bytes = build_icon()
    tap_bytes = build_tap_target()

    print(f"{len(fruits)} fruit at {FRUIT_PX}px: {fruit_bytes:,} bytes")
    print(f"icon at {ICON_PX}px: {icon_bytes:,} bytes")
    print(f"transparent tap target at {TAP_PX}px: {tap_bytes:,} bytes")

    if args.preview:
        write_preview(fruits)
        print("wrote tools/zepp-preview.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
