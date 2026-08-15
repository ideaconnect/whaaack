"""Build the Play Store feature graphic from the game's own art.

    python tools/generate_store_graphics.py

Writes assets/store/feature-graphic-1024x500.png.

Like generate_screenshots.py, this is not a mockup: the sky, hills and trees are the
same three bitmaps the render thread tiles, the fruit are the shipped sprites, the
splats are the shipped masks tinted with the pairs out of Fruit.kt, and the wordmark
palette is HomeScreen's. If the art changes, re-run this rather than retouching a PNG.

Play's rules this obeys: exactly 1024x500, no alpha channel (it is flattened to RGB on
the way out), and nothing load-bearing near the edges, because the graphic is cropped
to several aspect ratios across the store and can have a play button laid over its
middle. The wordmark therefore sits in the left two thirds and the fruit cluster keeps
clear of the centre line.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = ROOT / "assets" / "store"

W, H = 1024, 500

# HomeScreen / GameRenderer palette.
CREAM = (255, 243, 230)
INK = (26, 16, 38)
PANEL = (9, 20, 40)
ACCENT_LIGHT = (255, 201, 122)
ACCENT_DARK = (242, 112, 79)

SPLAT_COLORS = {
    "apple": ((143, 210, 78), (75, 138, 40)),
    "banana": ((251, 232, 122), (217, 168, 43)),
    "cherry": ((232, 81, 79), (142, 27, 38)),
    "grape": ((185, 139, 217), (110, 69, 148)),
    "kiwi": ((169, 214, 107), (78, 122, 52)),
    "lemon": ((252, 238, 138), (224, 190, 51)),
    "orange": ((255, 176, 85), (208, 106, 24)),
    "peach": ((255, 196, 166), (226, 123, 92)),
    "pear": ((198, 220, 121), (124, 155, 57)),
    "pineapple": ((245, 217, 106), (192, 140, 36)),
    "strawberry": ((240, 104, 92), (163, 43, 51)),
    "watermelon": ((255, 112, 137), (95, 163, 74)),
}


def font(size: float, black: bool = True) -> ImageFont.FreeTypeFont:
    candidates = (
        ["C:/Windows/Fonts/ariblk.ttf", "C:/Windows/Fonts/seguibl.ttf",
         "C:/Windows/Fonts/arialbd.ttf"]
        if black else
        ["C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/segoeuib.ttf"]
    )
    for path in candidates:
        if os.path.exists(path):
            return ImageFont.truetype(path, int(size))
    return ImageFont.load_default()


def load(name: str) -> Image.Image:
    return Image.open(ASSETS / name).convert("RGBA")


def background() -> Image.Image:
    """Sky over the whole panel, trees and hills hugging the bottom, as drawBackground does."""
    img = Image.new("RGBA", (W, H), (76, 130, 208, 255))

    def layer(bitmap: Image.Image, height: float, top: float, offset: float) -> None:
        scale = height / bitmap.height
        tw = max(1, int(bitmap.width * scale))
        th = max(1, int(height))
        tile = bitmap.resize((tw, th), Image.NEAREST)
        x = -int(offset % tw)
        while x < W:
            img.alpha_composite(tile, (x, int(top)))
            x += tw

    layer(load("bg-sky.png"), H * 1.6, -H * 0.42, 140)
    ground_h = H * 0.72
    ground_top = H - ground_h
    layer(load("bg-trees.png"), ground_h, ground_top, 520)
    layer(load("bg-hills.png"), ground_h, ground_top, 980)

    # Darken toward the left so the wordmark has something quiet to sit on, and lift the
    # right a little so the fruit read against it.
    scrim = Image.new("RGBA", (W, H))
    sd = ImageDraw.Draw(scrim)
    for x in range(W):
        t = x / (W - 1)
        a = int(150 - 96 * min(1.0, max(0.0, (t - 0.18) / 0.62)))
        sd.line([(x, 0), (x, H)], fill=PANEL + (a,))
    img.alpha_composite(scrim)

    # A soft vignette top and bottom, matching the in-game scrim's shape.
    vign = Image.new("RGBA", (W, H))
    vd = ImageDraw.Draw(vign)
    for y in range(H):
        t = y / (H - 1)
        a = int(78 * max(0.0, 1 - t / 0.30) + 92 * max(0.0, (t - 0.66) / 0.34))
        vd.line([(0, y), (W, y)], fill=PANEL + (a,))
    img.alpha_composite(vign)
    return img


def splat(name: str, variant: int, size: int, rot: float) -> Image.Image:
    """A shipped splat mask filled with that fruit's light-to-dark ramp, as drawSplat does."""
    mask_img = load(f"splats/splat{variant:02d}.png").getchannel("A")
    light, dark = SPLAT_COLORS[name]
    grad = Image.new("RGBA", (size, size))
    gd = ImageDraw.Draw(grad)
    for i in range(size):
        t = i / max(1, size - 1)
        if t < 0.38:
            c = light
        else:
            k = (t - 0.38) / 0.62
            c = tuple(int(light[j] + (dark[j] - light[j]) * k) for j in range(3))
        gd.line([(0, i), (size, i)], fill=c + (255,))
    grad.putalpha(mask_img.resize((size, size), Image.LANCZOS))
    out = grad.rotate(rot, resample=Image.BICUBIC, expand=False)
    # The masks are organic and not centred in their own bitmaps, and rotating moves the
    # mass again — so on the board, where a splat lands on the tile the fruit occupied,
    # nobody notices. Here it reads as a blob sitting beside its fruit. Re-centre on the
    # painted pixels so each burst sits under the thing that made it.
    # Centred on the alpha *centroid*, not its bounding box: these masks fling droplets well
    # clear of the main blob, so a bbox centre is dragged toward whichever side the droplets
    # landed and the burst still reads as sitting beside its fruit.
    a = np.asarray(out.getchannel("A"), np.float32)
    total = a.sum()
    if total > 0:
        ys, xs = np.nonzero(a)
        w = a[ys, xs]
        cy = float((ys * w).sum() / total)
        cx = float((xs * w).sum() / total)
        out = ImageChops.offset(out, int(round(size / 2 - cx)), int(round(size / 2 - cy)))
    return out


def sprite(name: str, size: int, rot: float = 0.0) -> Image.Image:
    art = load(f"fruits/{name}.png").resize((size, size), Image.NEAREST)
    if rot:
        art = art.rotate(rot, resample=Image.NEAREST, expand=True)
    return art


def drop_shadow(img: Image.Image, art: Image.Image, xy, blur: float = 9, alpha: int = 130):
    shade = Image.new("RGBA", art.size, (0, 0, 0, 0))
    shade.putalpha(art.getchannel("A").point(lambda v: int(v * alpha / 255)))
    shade = shade.filter(ImageFilter.GaussianBlur(blur))
    img.alpha_composite(shade, (int(xy[0]), int(xy[1] + 7)))


def text_with_outline(d: ImageDraw.ImageDraw, xy, s, f, fill, outline, width: int):
    x, y = xy
    for dx in range(-width, width + 1):
        for dy in range(-width, width + 1):
            if dx * dx + dy * dy <= width * width:
                d.text((x + dx, y + dy), s, font=f, fill=outline)
    d.text((x, y), s, font=f, fill=fill)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    img = background()

    # ---- the fruit cluster, right of centre ----------------------------------------
    # Placed by centre so each splat sits under its own fruit rather than beside it, and
    # kept inside x < 990 / y < 440 so the store's crops never clip one in half.
    cluster = [
        # (fruit, variant, centre, fruit size, rotation)
        ("watermelon", 11, (858, 198), 152, -8),
        ("orange", 3, (752, 96), 96, 12),
        ("strawberry", 21, (786, 356), 100, -14),
        ("kiwi", 28, (944, 366), 88, 16),
    ]
    for name, variant, (cx, cy), fsize, rot in cluster:
        ssize = int(fsize * 1.52)
        img.alpha_composite(splat(name, variant, ssize, rot * 3.1),
                            (cx - ssize // 2, cy - ssize // 2))
    for name, variant, (cx, cy), fsize, rot in cluster:
        art = sprite(name, fsize, rot)
        pos = (cx - art.width // 2, cy - art.height // 2)
        drop_shadow(img, art, pos)
        img.alpha_composite(art, pos)

    d = ImageDraw.Draw(img)

    # ---- wordmark -------------------------------------------------------------------
    # Sized to fit the text column rather than hard-coded, so the cluster to its right is
    # never collided with if the wordmark or the font ever changes.
    title = "Whaaack!"
    title_max_w = 620
    size = 124
    while size > 40:
        title_f = font(size)
        if d.textbbox((0, 0), title, font=title_f)[2] <= title_max_w:
            break
        size -= 2
    tb = d.textbbox((0, 0), title, font=title_f)
    tx, ty = 58, 150
    # A soft cast shadow under the letters, then the cream face with an ink rim, which is
    # how the home screen carries it against a moving sky.
    shadow = Image.new("RGBA", (W, H))
    ImageDraw.Draw(shadow).text((tx + 5, ty + 11), title, font=title_f, fill=(9, 20, 40, 170))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(10)))
    d = ImageDraw.Draw(img)
    text_with_outline(d, (tx, ty), title, title_f, CREAM + (255,), INK + (235,), 5)

    # ---- tagline pill ---------------------------------------------------------------
    tag = "SURVIVE THE ORCHARD"
    tag_f = font(30, black=False)
    # Letterspaced by hand; PIL has no tracking.
    space = 5
    widths = [d.textbbox((0, 0), ch, font=tag_f)[2] for ch in tag]
    tag_w = sum(widths) + space * (len(tag) - 1)
    pill_h, pad_x = 62, 30
    px, py = tx + 6, ty + (tb[3] - tb[1]) + 78
    pill = Image.new("RGBA", (W, H))
    ImageDraw.Draw(pill).rounded_rectangle(
        (px, py, px + tag_w + pad_x * 2, py + pill_h), radius=pill_h // 2,
        fill=PANEL + (215,), outline=CREAM + (46,), width=2)
    img.alpha_composite(pill)
    d = ImageDraw.Draw(img)
    cx = px + pad_x
    for ch, w in zip(tag, widths):
        d.text((cx, py + pill_h / 2), ch, font=tag_f, fill=CREAM + (255,), anchor="lm")
        cx += w + space

    # ---- the one line that says what you do -----------------------------------------
    sub_f = font(31, black=False)
    sub = "Three escapes and the run is over."
    sy = py + pill_h + 30
    text_with_outline(d, (tx + 6, sy), sub, sub_f, ACCENT_LIGHT + (255,), INK + (215,), 3)

    # Play wants no alpha channel on the feature graphic.
    flat = Image.new("RGB", (W, H), PANEL)
    flat.paste(img, (0, 0), img)
    path = OUT / "feature-graphic-1024x500.png"
    flat.save(path)
    print(f"{path.relative_to(ROOT)}  {flat.size[0]}x{flat.size[1]}  {path.stat().st_size // 1024} KiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
