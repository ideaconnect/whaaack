#!/usr/bin/env python3
"""
Renders marketing screenshots of Whaaack! straight from the game's own assets.

These are not mockups. Every number below is lifted from GameRenderer.kt — the same
board geometry, the same palette, the same rise curve for a fruit coming out of its
hole — so what the site shows is what the game draws. If the renderer's layout ever
changes, this drifts, and the fix is to re-copy the constants rather than to nudge
these until they look right.

    python tools/generate_screenshots.py

Writes 1080x2340 PNGs into website/assets/img/shots/.
"""

from __future__ import annotations

import math
import os
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = ROOT / "website" / "assets" / "img" / "shots"

# A realistic modern phone: 360dp x 780dp at density 3.
W, H = 1080, 2340
D = 3.0
SAFE_TOP = 34 * D
SAFE_BOTTOM = 24 * D


def dp(v: float) -> float:
    return v * D


# ---- palette, straight out of GameRenderer -----------------------------------------

CREAM = (255, 243, 230)
PANEL = (9, 20, 40, 140)          # 0x8C091428
PANEL_STRONG = (9, 20, 40, 148)   # 0x94091428
HAIRLINE = (255, 243, 230, 36)    # 0x24FFF3E6
ACCENT_LIGHT = (255, 201, 122)
ACCENT_DARK = (242, 112, 79)
STRIKE_ON = (226, 87, 76)
STRIKE_OFF = (255, 243, 230, 56)
TILE_TINTS = [
    (255, 243, 230, 33),
    (255, 201, 122, 33),
    (217, 80, 143, 31),
    (143, 191, 90, 31),
]

FRUITS = [
    "apple", "banana", "cherry", "grape", "kiwi", "lemon",
    "orange", "peach", "pear", "pineapple", "strawberry", "watermelon",
]

# Splat tint pairs (light, dark), from Fruit.kt.
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


def rounded(draw: ImageDraw.ImageDraw, box, radius, fill=None, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


# ---- background --------------------------------------------------------------------

def draw_background(img: Image.Image, sky_off: float, trees_off: float,
                    hills_off: float) -> None:
    """
    Sky over the full viewport, trees and hills hugging the bottom, as in drawBackground.

    The three offsets are independent because the layers scroll at different rates in game,
    so any given frame is an arbitrary combination of the three. Choosing them per shot is
    just framing — picking the moment, not changing what the game draws.
    """
    base = Image.new("RGBA", (W, H), (76, 130, 208, 255))
    img.alpha_composite(base)

    def layer(bitmap: Image.Image, height: float, top: float, offset: float) -> None:
        scale = height / bitmap.height
        tw = max(1, int(bitmap.width * scale))
        th = max(1, int(height))
        tile = bitmap.resize((tw, th), Image.NEAREST)
        x = -int(offset % tw)
        while x < W:
            img.alpha_composite(tile, (x, int(top)))
            x += tw

    layer(load("bg-sky.png"), H, 0, sky_off)
    ground_h = H * 0.63
    ground_top = H - ground_h
    layer(load("bg-trees.png"), ground_h, ground_top, trees_off)
    layer(load("bg-hills.png"), ground_h, ground_top, hills_off)

    # Scrim: 0x6B at the top, 0x1A at 34%, 0x57 at the bottom.
    scrim = Image.new("RGBA", (W, H))
    sd = ImageDraw.Draw(scrim)
    stops = [(0.0, 107), (0.34, 26), (1.0, 87)]
    for y in range(H):
        t = y / (H - 1)
        for i in range(len(stops) - 1):
            t0, a0 = stops[i]
            t1, a1 = stops[i + 1]
            if t0 <= t <= t1:
                k = (t - t0) / (t1 - t0)
                a = int(a0 + (a1 - a0) * k)
                break
        else:
            a = stops[-1][1]
        sd.line([(0, y), (W, y)], fill=(9, 20, 40, a))
    img.alpha_composite(scrim)


# ---- geometry ----------------------------------------------------------------------

class Layout:
    def __init__(self) -> None:
        side = dp(16)
        card_top = SAFE_TOP + dp(14) + dp(28) + dp(12)
        self.card = (side, card_top, W - side, card_top + dp(104))

        self.end_run = (
            W / 2 - dp(52), H - SAFE_BOTTOM - dp(18) - dp(38),
            W / 2 + dp(52), H - SAFE_BOTTOM - dp(18),
        )

        self.inset = dp(14)
        self.gap = dp(10)
        avail_top = self.card[3] + dp(16)
        avail_bottom = self.end_run[1] - dp(14)
        max_w = W - 2 * side
        max_h = avail_bottom - avail_top

        from_w = (max_w - 2 * self.inset - 3 * self.gap) / 4
        from_h = (max_h - 2 * self.inset - 3 * self.gap) / 4
        self.tile = max(0.0, min(from_w, from_h))

        board_w = self.tile * 4 + self.gap * 3 + self.inset * 2
        bx = (W - board_w) / 2
        by = avail_top + (max_h - board_w) / 2
        self.board = (bx, by, bx + board_w, by + board_w)
        self.left = bx + self.inset
        self.top = by + self.inset

    def cell(self, tile: int):
        col, row = tile % 4, tile // 4
        return (self.left + col * (self.tile + self.gap),
                self.top + row * (self.tile + self.gap))


# ---- board -------------------------------------------------------------------------

def draw_board(img: Image.Image, L: Layout) -> None:
    over = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(over)
    rounded(d, L.board, dp(30), fill=PANEL, outline=(255, 243, 230, 41), width=max(1, int(dp(1))))

    for tile in range(16):
        col, row = tile % 4, tile // 4
        x, y = L.cell(tile)
        box = (x, y, x + L.tile, y + L.tile)
        rounded(d, box, dp(20), fill=TILE_TINTS[(tile + row) % 4],
                outline=(255, 243, 230, 33), width=max(1, int(dp(1))))
        hole_h = L.tile * 0.30
        d.ellipse((x + L.tile * 0.10, y + L.tile * 0.88 - hole_h,
                   x + L.tile * 0.90, y + L.tile * 0.88),
                  fill=(14, 20, 34, 153))
    img.alpha_composite(over)


def draw_fruit(img: Image.Image, L: Layout, tile: int, name: str, rise: float) -> None:
    sprite = load(f"fruits/{name}.png")
    x, y = L.cell(tile)
    art = L.tile * 0.76
    scale = 0.72 + rise * 0.28
    size = max(1, int(art * scale))
    cx = x + L.tile / 2
    bottom = y + L.tile * 0.82 + (1 - rise) * L.tile * 0.5

    big = sprite.resize((size, size), Image.NEAREST)
    # Clipped to its tile, exactly as drawFruit does with withClip.
    cell = Image.new("RGBA", (int(L.tile) + 2, int(L.tile) + 2))
    cell.alpha_composite(big, (int(cx - size / 2 - x), int(bottom - size - y)))
    mask = Image.new("L", cell.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, int(L.tile), int(L.tile)), radius=int(dp(20)), fill=255)
    cell.putalpha(Image.composite(cell.getchannel("A"), Image.new("L", cell.size, 0), mask))
    img.alpha_composite(cell, (int(x), int(y)))


def draw_splat(img: Image.Image, L: Layout, tile: int, name: str, variant: int,
               rot: float, progress: float) -> None:
    mask_img = load(f"splats/splat{variant:02d}.png").getchannel("A")
    light, dark = SPLAT_COLORS[name]
    alpha = 1.0 if progress < 0.55 else max(0.0, 1 - (progress - 0.55) / 0.45)
    pop = 0.95 + min(1.0, progress * 12) * 0.15
    size = max(1, int(L.tile * 1.18 * pop))

    grad = Image.new("RGBA", (size, size))
    gd = ImageDraw.Draw(grad)
    for i in range(size):
        t = i / max(1, size - 1)
        k = min(1.0, t / 0.38) if t < 0.38 else 0.0
        if t < 0.38:
            c = light
        else:
            kk = (t - 0.38) / 0.62
            c = tuple(int(light[j] + (dark[j] - light[j]) * kk) for j in range(3))
        gd.line([(0, i), (size, i)], fill=c + (255,))

    m = mask_img.resize((size, size), Image.LANCZOS)
    grad.putalpha(m)
    grad = grad.rotate(rot, resample=Image.BICUBIC, expand=False)
    if alpha < 1.0:
        a = grad.getchannel("A").point(lambda v: int(v * alpha))
        grad.putalpha(a)

    x, y = L.cell(tile)
    cx = x + L.tile / 2
    cy = y + L.tile * 0.52
    img.alpha_composite(grad, (int(cx - size / 2), int(cy - size / 2)))


# ---- HUD ---------------------------------------------------------------------------

def text_w(d: ImageDraw.ImageDraw, s: str, f) -> float:
    return d.textbbox((0, 0), s, font=f)[2]


def draw_hud(img: Image.Image, L: Layout, score: int, hits: int, speed_frac: float,
             speed_label: str, strikes: int, ranked: bool) -> None:
    over = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(over)

    # --- top bar
    y = SAFE_TOP + dp(14)
    chip_h = dp(28)
    label_f = font(dp(10), black=False)
    txt = "RANKED" if ranked else "FOR FUN"
    chip_w = text_w(d, txt, label_f) + dp(24)
    chip = (dp(16), y, dp(16) + chip_w, y + chip_h)
    rounded(d, chip, chip_h / 2,
            fill=(255, 201, 122, 51) if ranked else PANEL,
            outline=HAIRLINE, width=max(1, int(dp(1))))
    d.text((chip[0] + dp(12), y + chip_h / 2), txt, font=label_f,
           fill=ACCENT_LIGHT if ranked else (255, 243, 230, 179), anchor="lm")

    dot = dp(12)
    cx = W - dp(16) - dot / 2
    for i in range(3, 0, -1):
        on = strikes >= i
        if on:
            d.ellipse((cx - dot / 2 - dp(3), y + chip_h / 2 - dot / 2 - dp(3),
                       cx + dot / 2 + dp(3), y + chip_h / 2 + dot / 2 + dp(3)),
                      fill=(226, 87, 76, 56))
        d.ellipse((cx - dot / 2, y + chip_h / 2 - dot / 2,
                   cx + dot / 2, y + chip_h / 2 + dot / 2),
                  fill=STRIKE_ON if on else STRIKE_OFF)
        cx -= dot + dp(6)

    # --- score card
    rounded(d, L.card, dp(26), fill=PANEL_STRONG, outline=HAIRLINE, width=max(1, int(dp(1))))
    score_f = font(dp(46))
    s = str(score)
    baseline = L.card[1] + dp(52)
    ccx = (L.card[0] + L.card[2]) / 2
    sw = text_w(d, s, score_f)
    d.text((ccx - dp(11), baseline), s, font=score_f, fill=CREAM, anchor="ms")
    ms_f = font(dp(11), black=False)
    d.text((ccx + sw / 2 - dp(6), baseline), "MS", font=ms_f,
           fill=(255, 243, 230, 153), anchor="ls")

    bar_l = L.card[0] + dp(16)
    bar_r = L.card[2] - dp(16)
    bar_t = L.card[1] + dp(66)
    bar_h = dp(6)
    rounded(d, (bar_l, bar_t, bar_r, bar_t + bar_h), bar_h / 2, fill=(20, 10, 26, 102))
    if speed_frac > 0:
        filled = (bar_r - bar_l) * speed_frac
        bar = Image.new("RGBA", (max(1, int(filled)), max(1, int(bar_h))))
        bd = ImageDraw.Draw(bar)
        for i in range(bar.width):
            t = i / max(1, bar.width - 1)
            c = tuple(int(ACCENT_LIGHT[j] + (ACCENT_DARK[j] - ACCENT_LIGHT[j]) * t)
                      for j in range(3))
            bd.line([(i, 0), (i, bar.height)], fill=c + (255,))
        rm = Image.new("L", bar.size, 0)
        ImageDraw.Draw(rm).rounded_rectangle((0, 0, bar.width - 1, bar.height - 1),
                                             radius=int(bar_h / 2), fill=255)
        bar.putalpha(rm)
        over.alpha_composite(bar, (int(bar_l), int(bar_t)))

    lab_f = font(dp(11), black=False)
    d.text((bar_l, bar_t + dp(20)), speed_label, font=lab_f, fill=(255, 243, 230, 191))
    d.text((bar_r, bar_t + dp(20)), f"{hits} HITS", font=lab_f,
           fill=(255, 243, 230, 191), anchor="ra")

    # --- end run pill
    rounded(d, L.end_run, dp(14), fill=(9, 20, 40, 51),
            outline=(255, 243, 230, 51), width=max(1, int(dp(1))))
    er_f = font(dp(12), black=False)
    d.text(((L.end_run[0] + L.end_run[2]) / 2, (L.end_run[1] + L.end_run[3]) / 2),
           "End run", font=er_f, fill=(255, 243, 230, 168), anchor="mm")

    img.alpha_composite(over)


def draw_countdown(img: Image.Image, value: str) -> None:
    over = Image.new("RGBA", (W, H), (20, 10, 26, 158))
    d = ImageDraw.Draw(over)
    d.text((W / 2, H / 2 + dp(34)), value, font=font(dp(96)), fill=CREAM, anchor="ms")
    img.alpha_composite(over)


# ---- scenes ------------------------------------------------------------------------

def scene(name: str, build) -> None:
    img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    build(img)
    OUT.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(OUT / f"{name}.png", optimize=True)
    print(f"  {name}.png")


def main() -> None:
    random.seed(7)
    L = Layout()

    # Sky offsets stay small: source x 0..150 of bg-sky is open blue with the cloud bank
    # sitting low, which keeps the board readable instead of putting a 7x-scaled cumulus
    # directly behind it.
    def early(img):
        draw_background(img, 60, 900, 1500)
        draw_board(img, L)
        draw_fruit(img, L, 5, "strawberry", 1.0)
        draw_fruit(img, L, 10, "apple", 0.55)
        draw_hud(img, L, 4820, 11, 0.3, "SPEED 4", 0, ranked=True)

    def busy(img):
        draw_background(img, 210, 1750, 300)
        draw_board(img, L)
        draw_splat(img, L, 2, "watermelon", 4, 31, 0.25)
        draw_splat(img, L, 9, "grape", 17, 200, 0.62)
        draw_fruit(img, L, 0, "orange", 1.0)
        draw_fruit(img, L, 7, "kiwi", 1.0)
        draw_fruit(img, L, 11, "cherry", 0.78)
        draw_fruit(img, L, 13, "banana", 0.35)
        draw_hud(img, L, 41337, 96, 1.0, "TOP SPEED", 2, ranked=True)

    def countdown(img):
        draw_background(img, 120, 400, 2100)
        draw_board(img, L)
        draw_hud(img, L, 0, 0, 0.0, "SPEED 1", 0, ranked=False)
        draw_countdown(img, "3")

    def casual(img):
        draw_background(img, 20, 1300, 900)
        draw_board(img, L)
        draw_splat(img, L, 6, "lemon", 22, 118, 0.4)
        draw_fruit(img, L, 4, "peach", 1.0)
        draw_fruit(img, L, 14, "pineapple", 0.9)
        draw_hud(img, L, 18204, 44, 0.7, "SPEED 8", 1, ranked=False)

    print("Rendering screenshots from the game's own assets:")
    scene("run-early", early)
    scene("run-top-speed", busy)
    scene("countdown", countdown)
    scene("run-casual", casual)
    print(f"-> {OUT}")


if __name__ == "__main__":
    main()
