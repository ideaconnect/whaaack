"""Rebuild every icon the app ships from the single master artwork.

    python tools/generate_launcher_icons.py            # write the assets
    python tools/generate_launcher_icons.py --preview  # also write a contact sheet

The master (`assets/icon/icon-2.png`) is a splat with the fruit sitting well
inside it, so each surface gets its own crop and scale rather than one bitmap
stretched across all of them:

adaptive foreground  108dp canvas, artwork's *minimum enclosing circle* pinned
                     to 71dp. Launcher masks are inscribed in the middle 72dp,
                     so an enclosing circle of 71dp cannot be clipped by a
                     circle, squircle, teardrop or rounded-square mask.
monochrome           same framing, but redrawn for Android 13+ themed icons,
                     which use *only* the alpha channel: the flesh is knocked
                     out and a gap ring separates the slice from the splat, so
                     it reads as a melon instead of a solid blob.
legacy 48dp          pre-adaptive `ic_launcher`/`ic_launcher_round`. Nothing
                     masks these, so the background is baked in and the
                     artwork is inset to leave its own margin.
in-app logo          no mask and no background, so the *bounding box* is fitted
                     rather than the enclosing circle - the art is drawn as
                     large as the box allows.
Play Store 512       Google re-masks it, so key content stays inside 80%.

Every output is resampled once from the full-resolution master; no density is
derived from another density.
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
SOURCE = ROOT / "assets" / "icon" / "icon-2.png"
RES = ROOT / "app" / "src" / "main" / "res"

# Matches @color/ic_launcher_background, which the adaptive icon uses as its
# background layer. Kept in sync by hand - it is also the app's orchard_night.
BACKGROUND = (0x2A, 0x16, 0x33, 0xFF)

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

ADAPTIVE_DP = 108
# The masked viewport is the middle 72dp; 71 leaves a hairline for antialiasing.
FOREGROUND_DIA_DP = 71
LEGACY_DP = 48
LEGACY_SQUARE_DIA_DP = 42  # inset so the splat is not flush with the tile edge
LEGACY_ROUND_DIA_DP = 40  # a round tile crowds the art on every side, so more
LOGO_DP = 132  # the size AboutScreen draws it at
STORE_PX = 512
STORE_DIA_PX = 410  # 80%: Google applies its own rounding to the store icon


def segment(src: Image.Image) -> dict[str, np.ndarray]:
    """Split the artwork into splat / rind / flesh / seeds.

    Colour rules rather than hand-drawn masks, so re-running against a tweaked
    master still works. The discriminator between the splat's pink and the
    flesh's red is blue: the splat sits around B=70-110, the flesh around B=30-50.
    """
    a = np.asarray(src, dtype=np.int16)
    r, g, b, alpha = a[..., 0], a[..., 1], a[..., 2], a[..., 3]
    ink = alpha > 40

    green = ink & (g > r + 20) & (g > 45)
    pale = ink & (r > 195) & (g > 195) & (b < 215)
    flesh_red = ink & (r > 150) & (b < 58) & (g < 95)

    # Close the melon's speckled interior before taking it as one region.
    melon = K.fill_holes(K.largest_component(K.erode(K.dilate(green | pale, 3) | K.dilate(flesh_red, 3), 3)))
    rind = melon & (green | pale)
    flesh = K.fill_holes(K.largest_component(melon & ~K.dilate(rind, 1)))

    # Seeds: dark blobs that sit inside the flesh rather than on the outline.
    interior = K.erode(melon, 12)
    dark = melon & (a[..., :3].max(axis=-1) < 95)
    labels, count = K.label(dark)
    seeds = np.zeros_like(dark)
    for i in range(1, count + 1):
        blob = labels == i
        size = int(blob.sum())
        if size < 200:
            continue
        if (blob & interior).sum() / size > 0.9 and (blob & flesh).sum() / size > 0.5:
            seeds |= blob
    seeds = K.fill_holes(seeds)

    return {"ink": ink, "melon": melon, "rind": rind, "flesh": flesh, "seeds": seeds}


def monochrome_art(src: Image.Image, masks: dict[str, np.ndarray]) -> Image.Image:
    """White-on-alpha layer for Android 13+ themed icons.

    The system tints this by alpha alone, so the shape has to carry the whole
    design. Two subtractions turn the flat silhouette into a readable slice:
    the flesh becomes negative space (seeds survive as dots inside it), and a
    gap ring detaches the rind from the surrounding splat.
    """
    alpha = np.asarray(src, dtype=np.uint8)[..., 3].copy()
    gap = K.dilate(masks["melon"], 12) & ~masks["melon"]
    alpha[(masks["flesh"] & ~masks["seeds"]) | gap] = 0

    out = np.zeros(alpha.shape + (4,), np.uint8)
    out[..., 0:3] = 255
    out[..., 3] = alpha
    return Image.fromarray(out, "RGBA")


def tile(art: Image.Image, circle, size: int, diameter: float, mask: Image.Image) -> Image.Image:
    """Artwork over the brand background, cut to `mask`. Used by the legacy icons."""
    out = Image.new("RGBA", (size, size), BACKGROUND)
    out.alpha_composite(K.place(art, circle, size, diameter))
    out.putalpha(mask)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true", help="also write tools/icon-preview.png")
    args = ap.parse_args()

    src = Image.open(SOURCE).convert("RGBA")
    masks = segment(src)
    circle = K.min_enclosing_circle(masks["ink"])
    mono = monochrome_art(src, masks)
    cx, cy, radius = circle
    print(f"{SOURCE.name}: {src.size[0]}px, enclosing circle r={radius:.1f} at ({cx:.1f},{cy:.1f})")

    total = 0
    for bucket, density in DENSITIES.items():
        adaptive = round(ADAPTIVE_DP * density)
        legacy = round(LEGACY_DP * density)
        logo = round(LOGO_DP * density)
        fg_dia = FOREGROUND_DIA_DP * density

        written = {
            RES / f"mipmap-{bucket}" / "ic_launcher_foreground.png": K.place(
                src, circle, adaptive, fg_dia
            ),
            RES / f"mipmap-{bucket}" / "ic_launcher_monochrome.png": K.place(
                mono, circle, adaptive, fg_dia
            ),
            RES / f"mipmap-{bucket}" / "ic_launcher.png": tile(
                src, circle, legacy, LEGACY_SQUARE_DIA_DP * density,
                K.rounded_rect_mask(legacy, legacy * 0.20),
            ),
            RES / f"mipmap-{bucket}" / "ic_launcher_round.png": tile(
                src, circle, legacy, LEGACY_ROUND_DIA_DP * density,
                K.circle_mask(legacy),
            ),
            RES / f"drawable-{bucket}" / "logo_whaaack.png": K.place_bbox(
                src, masks["ink"], logo, logo
            ),
        }
        for path, image in written.items():
            total += K.save_png(image, path)
        print(f"  {bucket:<8} adaptive {adaptive}px  legacy {legacy}px  logo {logo}px")

    store = Image.new("RGBA", (STORE_PX, STORE_PX), BACKGROUND)
    store.alpha_composite(K.place(src, circle, STORE_PX, STORE_DIA_PX))
    store_path = ROOT / "assets" / "icon" / "play-store-512.png"
    total += K.save_png(store, store_path, allow_palette=False)
    print(f"  store    {STORE_PX}px -> {store_path.relative_to(ROOT).as_posix()}")
    print(f"total {total / 1024:.0f} KiB")

    if args.preview:
        write_preview(src, mono, circle)
    return 0


def write_preview(src: Image.Image, mono: Image.Image, circle) -> None:
    """Contact sheet of every masked shape at real launcher pixel sizes."""
    from PIL import ImageDraw

    shapes = {
        "circle": lambda n: K.circle_mask(n),
        "squircle": lambda n: K.rounded_rect_mask(n, n * 0.42),
        "rounded": lambda n: K.rounded_rect_mask(n, n * 0.22),
        "square": lambda n: Image.new("L", (n, n), 255),
    }
    sizes = [72, 108, 144, 192]
    cell, pad = 210, 14
    rows = len(shapes) + 2
    sheet = Image.new("RGB", (pad + (cell + pad) * len(sizes), pad + (cell + 30) * rows), (18, 18, 20))
    draw = ImageDraw.Draw(sheet)

    def paste(row: int, col: int, image: Image.Image, label: str) -> None:
        y = pad + row * (cell + 30)
        x = pad + col * (cell + pad)
        sheet.paste(image.convert("RGB"), (x + (cell - image.width) // 2, y + (cell - image.height) // 2), image)
        if col == 0:
            draw.text((pad, y + cell + 8), label, fill=(190, 190, 190))

    for row, (name, make) in enumerate(shapes.items()):
        for col, size in enumerate(sizes):
            canvas = round(size * ADAPTIVE_DP / 72)
            icon = Image.new("RGBA", (canvas, canvas), BACKGROUND)
            icon.alpha_composite(K.place(src, circle, canvas, FOREGROUND_DIA_DP * canvas / ADAPTIVE_DP))
            off = (canvas - size) // 2
            view = icon.crop((off, off, off + size, off + size))
            view.putalpha(make(size))
            paste(row, col, view, f"adaptive / {name}")

    for col, size in enumerate(sizes):
        canvas = round(size * ADAPTIVE_DP / 72)
        layer = K.place(mono, circle, canvas, FOREGROUND_DIA_DP * canvas / ADAPTIVE_DP)
        for row, (bg, fg) in enumerate([((218, 226, 249), (27, 27, 31)), ((43, 41, 48), (230, 225, 229))]):
            icon = Image.new("RGBA", (canvas, canvas), bg + (255,))
            tint = Image.new("RGBA", (canvas, canvas), fg + (255,))
            tint.putalpha(layer.getchannel("A"))
            icon.alpha_composite(tint)
            off = (canvas - size) // 2
            view = icon.crop((off, off, off + size, off + size))
            view.putalpha(K.circle_mask(size))
            paste(len(shapes) + row, col, view, f"themed / {'light' if row == 0 else 'dark'}")

    out = Path(__file__).resolve().parent / "icon-preview.png"
    sheet.save(out)
    print(f"preview -> {out.relative_to(ROOT).as_posix()}  (columns: {', '.join(f'{s}px' for s in sizes)})")


if __name__ == "__main__":
    raise SystemExit(main())
