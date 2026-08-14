"""Turn the ad-free crown master into the in-app badge drawable.

    python tools/generate_pro_badge.py            # write the drawables
    python tools/generate_pro_badge.py --preview  # also write tools/pro-badge-preview.png

The master (`assets/icon/pro_remove_ads_crown_only.png`) is a flat gold crown
with a soft glow, painted on an *opaque* dark ground. Shipped as-is it would
show that ground as a square tile in the middle of a translucent panel, so this
lifts the artwork off its background and back onto transparency.

The lift is an exact inverse of the composite that produced the master, not a
threshold: every lit pixel is the crown's single gold over the ground, so

    observed = colour*alpha + background*(1 - alpha)

can be solved for `alpha` (from whichever channel has the most headroom, since
the ground is dark blue and the ink is gold, giving red the largest span) and
then for the unpremultiplied `colour`. Composited back over a dark navy panel
the result is indistinguishable from the master - which is the point: the glow
is part of the artwork and a hard alpha cut-out would throw it away.

The background is measured per row from the outermost columns rather than
assumed flat: the master is a vertical gradient, ~(12,30,46) at the top and
~(6,13,22) at the bottom. Whatever faint glow reaches those columns is folded
into the background estimate, which is deliberate - it costs under 1% alpha and
buys a badge with no visible film over the panel behind it.
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
SOURCE = ROOT / "assets" / "icon" / "pro_remove_ads_crown_only.png"
RES = ROOT / "app" / "src" / "main" / "res"
NAME = "badge_ad_free.png"

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

# The size AdBreakDialog draws it at. The crown itself is a little over half of
# that; the rest is the glow, which needs the room to fall off.
BADGE_DP = 104

# The crown's fill, sampled from the master's interior. Every lit pixel is this
# colour at some opacity.
GOLD = np.array([243.0, 195.0, 60.0])

# Columns taken as background, per row. Wide enough to be a stable median,
# narrow enough to stay clear of the glow.
MARGIN_PX = 40

# Below this the pixel is glow so faint it is only worth its own storage in
# banding artefacts.
ALPHA_FLOOR = 0.006


def lift(src: Image.Image) -> Image.Image:
    """Undo the composite onto the dark ground, returning crown + glow on alpha."""
    a = np.asarray(src.convert("RGBA"), np.float64)[..., :3]
    edges = np.concatenate([a[:, :MARGIN_PX, :], a[:, -MARGIN_PX:, :]], axis=1)
    background = np.median(edges, axis=1)[:, None, :]

    # Per channel: how far this pixel has travelled from the ground towards the
    # gold, as a fraction of the whole trip. Red spans 6->243, blue only 22->60,
    # so red carries the answer and the max picks it without hard-coding that.
    reach = np.clip((a - background) / np.clip(GOLD - background, 1e-6, None), 0.0, 1.0)
    alpha = reach.max(axis=-1)
    alpha[alpha < ALPHA_FLOOR] = 0.0

    lit = alpha > 0
    colour = np.zeros_like(a)
    # out = colour*alpha + bg*(1-alpha), solved for colour. Clipped because the
    # glow's brightest pixels sit slightly above the flat gold (the master has a
    # touch of bloom), which would otherwise resolve to out-of-range colour.
    unmixed = (a - np.broadcast_to(background, a.shape) * (1 - alpha)[..., None]) / np.where(
        lit, alpha, 1.0
    )[..., None]
    colour[lit] = np.clip(unmixed[lit], 0.0, 255.0)

    out = np.zeros(a.shape[:2] + (4,), np.uint8)
    out[..., :3] = np.rint(colour).astype(np.uint8)
    out[..., 3] = np.rint(alpha * 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def framed(art: Image.Image, canvas: int) -> Image.Image:
    """Trim the dead margin, then fit what is left to a square canvas."""
    alpha = np.asarray(art)[..., 3]
    ys, xs = np.nonzero(alpha > 5)
    box = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)
    cropped = art.crop(box)
    side = max(cropped.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.alpha_composite(
        cropped, ((side - cropped.size[0]) // 2, (side - cropped.size[1]) // 2)
    )
    return square.resize((canvas, canvas), Image.LANCZOS)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true", help="also write tools/pro-badge-preview.png")
    args = ap.parse_args()

    src = Image.open(SOURCE).convert("RGBA")
    art = lift(src)
    print(f"{SOURCE.name}: {src.size[0]}px master lifted onto alpha")

    total = 0
    for bucket, density in DENSITIES.items():
        size = round(BADGE_DP * density)
        written = K.save_png(framed(art, size), RES / f"drawable-{bucket}" / NAME)
        total += written
        print(f"  {bucket:<8} {size}px  {written / 1024:.1f} KiB")
    print(f"total {total / 1024:.0f} KiB")

    if args.preview:
        # Over the panel the dialog actually uses, at the size it actually uses,
        # because a glow only misbehaves against a background.
        panel = (0x09, 0x14, 0x28, 0xFF)
        sheet = Image.new("RGBA", (BADGE_DP * 4 + 80, BADGE_DP * 4 + 80), panel)
        sheet.alpha_composite(framed(art, BADGE_DP * 4), (40, 40))
        path = ROOT / "tools" / "pro-badge-preview.png"
        K.save_png(sheet, path, allow_palette=False)
        print(f"  preview  -> {path.relative_to(ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
