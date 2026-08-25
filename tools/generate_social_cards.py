"""Build the LinkedIn and Facebook promo cards from the game's own art and screenshots.

    python tools/generate_social_cards.py              # write all four cards
    python tools/generate_social_cards.py --preview    # ...and a contact sheet to eyeball

Writes assets/social/{linkedin,facebook}-{en,pl}.png.

Like generate_store_graphics.py, nothing here is a mockup. The sky, trees and hills are
the three bitmaps the render thread tiles; the fruit and splats are the shipped sprites
and masks tinted from Fruit.kt's pairs; and the three phones are real capture frames out
of assets/store/screenshots. If the game's look changes, re-run this rather than
retouching a PNG.

Three things that are not obvious:

* **The screenshots are wider than the screen they show.** The store frames a 864x1920
  capture inside a 1080x1920 canvas on a blurred copy of itself, so pasting one whole
  puts a blurry halo around every phone. SCREEN_L/SCREEN_R below cut back to the crisp
  pixels; they were measured off a frame, not guessed.
* **There is no Google Play badge drawn here, on purpose.** The badge and wordmark are
  Google's trademarks and are meant to be used as supplied - not redrawn, recoloured or
  cropped. The CTA is therefore a plain accent pill in the game's own palette, which
  makes no claim to be the badge. To use the real one, download it from Google's brand
  pages and pass --play-badge. Either way the post copy still needs the
  "Google Play and the Google Play logo are trademarks of Google LLC." attribution.
* **Facebook and LinkedIn crop differently.** Both cards are ~1.91:1, but LinkedIn rounds
  the corners of a feed image and Facebook can letterbox one into 4:5 on mobile. Nothing
  load-bearing sits within 40px of an edge, and the headline column stays inside the left
  55% so a centre crop never eats it.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))

from generate_store_graphics import (  # noqa: E402  (needs the path above)
    ACCENT_DARK,
    ACCENT_LIGHT,
    CREAM,
    INK,
    PANEL,
    drop_shadow,
    font,
    load,
    splat,
    sprite,
)

ROOT = Path(__file__).resolve().parent.parent
SHOTS = ROOT / "assets" / "store" / "screenshots"
OUT = ROOT / "assets" / "social"

# The crisp screen inside a 1080x1920 store screenshot. Measured by scanning a frame for
# the vertical edges where the blurred surround stops and the capture starts.
SCREEN_L, SCREEN_R = 108, 972


# --------------------------------------------------------------------------------------
# pieces
# --------------------------------------------------------------------------------------

def background(w: int, h: int) -> Image.Image:
    """Sky over everything, trees and hills along the bottom, as drawBackground does."""
    img = Image.new("RGBA", (w, h), (76, 130, 208, 255))

    def layer(bitmap: Image.Image, height: float, top: float, offset: float) -> None:
        scale = height / bitmap.height
        tw = max(1, int(bitmap.width * scale))
        th = max(1, int(height))
        tile = bitmap.resize((tw, th), Image.NEAREST)
        x = -int(offset % tw)
        while x < w:
            img.alpha_composite(tile, (x, int(top)))
            x += tw

    layer(load("bg-sky.png"), h * 1.7, -h * 0.46, 210)
    ground_h = h * 0.66
    ground_top = h - ground_h
    layer(load("bg-trees.png"), ground_h, ground_top, 660)
    layer(load("bg-hills.png"), ground_h, ground_top, 1180)

    # Dark on the left so the headline has something quiet to sit on, opening up to the
    # right where the phones go. Then a vignette top and bottom, the in-game scrim's shape.
    #
    # Two layers, not one: ImageDraw.line *replaces* the pixels it covers rather than
    # blending into them, so drawing the horizontal vignette over the vertical gradient
    # wipes the gradient out everywhere the vignette is transparent - which is the middle
    # of the card, where the copy sits.
    column = Image.new("RGBA", (w, h))
    cd = ImageDraw.Draw(column)
    for x in range(w):
        t = x / (w - 1)
        a = int(232 - 200 * min(1.0, max(0.0, (t - 0.06) / 0.52)))
        cd.line([(x, 0), (x, h)], fill=PANEL + (a,))
    img.alpha_composite(column)

    vignette = Image.new("RGBA", (w, h))
    vd = ImageDraw.Draw(vignette)
    for y in range(h):
        t = y / (h - 1)
        a = int(76 * max(0.0, 1 - t / 0.26) + 112 * max(0.0, (t - 0.62) / 0.38))
        vd.line([(0, y), (w, y)], fill=(10, 6, 18, a))
    img.alpha_composite(vignette)
    return img


def phone(name: str, height: int) -> Image.Image:
    """One capture frame as a phone: cropped to the crisp screen, rounded, with a rim."""
    src = Image.open(SHOTS / f"{name}.png").convert("RGB")
    art = src.crop((SCREEN_L, 0, SCREEN_R, src.height))
    w = max(1, round(height * art.width / art.height))
    art = art.resize((w, height), Image.LANCZOS).convert("RGBA")

    # Supersampled corners: a radius this large shows every jagged step drawn at 1x.
    ss = 4
    radius = w * 0.085
    mask = Image.new("L", (w * ss, height * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, w * ss - 1, height * ss - 1), radius=radius * ss, fill=255)
    art.putalpha(mask.resize((w, height), Image.LANCZOS))

    # A cream hairline for the bezel highlight, over an ink one so it reads on pale sky.
    rim = Image.new("RGBA", (w * ss, height * ss), (0, 0, 0, 0))
    rd = ImageDraw.Draw(rim)
    rd.rounded_rectangle((0, 0, w * ss - 1, height * ss - 1),
                         radius=radius * ss, outline=INK + (170,), width=4 * ss)
    rd.rounded_rectangle((2 * ss, 2 * ss, w * ss - 1 - 2 * ss, height * ss - 1 - 2 * ss),
                         radius=(radius - 2) * ss, outline=CREAM + (74,), width=2 * ss)
    art.alpha_composite(rim.resize((w, height), Image.LANCZOS))
    return art


def place_phone(canvas: Image.Image, art: Image.Image, centre, rot: float = 0.0) -> None:
    if rot:
        art = art.rotate(rot, resample=Image.BICUBIC, expand=True)
    pos = (int(centre[0] - art.width / 2), int(centre[1] - art.height / 2))
    shade = Image.new("RGBA", art.size)
    shade.putalpha(art.getchannel("A").point(lambda v: int(v * 0.62)))
    shade = shade.filter(ImageFilter.GaussianBlur(22))
    canvas.alpha_composite(shade, (pos[0] + 4, pos[1] + 20))
    canvas.alpha_composite(art, pos)


def scatter(img: Image.Image, cluster) -> None:
    """Fruit with their own splat behind them, for the corners the phones leave empty."""
    for name, variant, (cx, cy), size, rot in cluster:
        s = int(size * 1.5)
        img.alpha_composite(splat(name, variant, s, rot * 3.1), (cx - s // 2, cy - s // 2))
    for name, variant, (cx, cy), size, rot in cluster:
        art = sprite(name, size, rot)
        pos = (cx - art.width // 2, cy - art.height // 2)
        drop_shadow(img, art, pos)
        img.alpha_composite(art, pos)


# --------------------------------------------------------------------------------------
# type
# --------------------------------------------------------------------------------------

def wrap(d: ImageDraw.ImageDraw, text: str, f: ImageFont.FreeTypeFont, max_w: int):
    lines, line = [], ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if d.textlength(trial, font=f) <= max_w or not line:
            line = trial
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def fit(d: ImageDraw.ImageDraw, lines, max_w: int, start: int, floor: int, black=True):
    """Largest size at which every line clears max_w. Copy length varies by language."""
    size = start
    while size > floor:
        f = font(size, black=black)
        if all(d.textlength(s, font=f) <= max_w for s in lines):
            return f
        size -= 2
    return font(floor, black=black)


def paragraph(d: ImageDraw.ImageDraw, text: str, max_w: int, start: int, floor: int):
    """Set body copy on the breaks the copy asks for, shrinking only if they overflow.

    Newlines in COPY are deliberate: the same sentence is longer in Polish than in
    English, and letting it wrap where it likes gives one card an orphan and the other a
    clean pair. So shrink until the text needs no break the copy did not ask for, and
    only fall back to natural wrapping at the floor.
    """
    parts = [p for p in text.split("\n") if p.strip()]
    size = start
    while size > floor:
        f = font(size, black=False)
        lines = [line for part in parts for line in wrap(d, part, f, max_w)]
        if len(lines) == len(parts):
            return f, lines
        size -= 1
    f = font(floor, black=False)
    return f, [line for part in parts for line in wrap(d, part, f, max_w)]


def tracked(d: ImageDraw.ImageDraw, xy, text, f, fill, space: int = 4) -> None:
    """Letterspaced text on a left/middle anchor. PIL has no tracking of its own."""
    x, y = xy
    for ch in text:
        d.text((x, y), ch, font=f, fill=fill, anchor="lm")
        x += d.textlength(ch, font=f) + space


def tracked_width(d: ImageDraw.ImageDraw, text, f, space: int = 4) -> float:
    return sum(d.textlength(c, font=f) for c in text) + space * (len(text) - 1)


def soft_text(img: Image.Image, xy, text, f, fill, blur: float = 9, alpha: int = 165):
    """A cast shadow under the glyphs, then the glyphs. How HomeScreen carries the title."""
    shadow = Image.new("RGBA", img.size)
    ImageDraw.Draw(shadow).text((xy[0] + 3, xy[1] + 7), text, font=f, fill=PANEL + (alpha,))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(blur)))
    ImageDraw.Draw(img).text(xy, text, font=f, fill=fill)


def eyebrow(img: Image.Image, xy, text: str) -> int:
    f = font(21, black=False)
    d = ImageDraw.Draw(img)
    w = tracked_width(d, text, f, 4)
    h, pad = 40, 22
    pill = Image.new("RGBA", img.size)
    ImageDraw.Draw(pill).rounded_rectangle(
        (xy[0], xy[1], xy[0] + w + pad * 2, xy[1] + h),
        radius=h // 2, fill=PANEL + (222,), outline=CREAM + (62,), width=2)
    img.alpha_composite(pill)
    tracked(ImageDraw.Draw(img), (xy[0] + pad, xy[1] + h / 2), text, f, CREAM + (255,), 4)
    return h


def chips(img: Image.Image, xy, items, filled: bool, max_w: int) -> int:
    """The three denials, side by side. Outlined reads calmer, filled reads louder."""
    d = ImageDraw.Draw(img)
    size, pad, gap = 23, 20, 12

    def row_width(f):
        return sum(d.textlength(t, font=f) + pad * 2 for t in items) + gap * (len(items) - 1)

    f = font(size, black=False)
    while size > 15 and row_width(f) > max_w:
        size -= 1
        f = font(size, black=False)

    h = size + 22
    x = xy[0]
    for text in items:
        w = d.textlength(text, font=f)
        layer = Image.new("RGBA", img.size)
        ld = ImageDraw.Draw(layer)
        if filled:
            ld.rounded_rectangle((x, xy[1], x + w + pad * 2, xy[1] + h),
                                 radius=h // 2, fill=ACCENT_LIGHT + (240,))
            ink = INK + (255,)
        else:
            ld.rounded_rectangle((x, xy[1], x + w + pad * 2, xy[1] + h), radius=h // 2,
                                 fill=PANEL + (208,), outline=CREAM + (68,), width=2)
            ink = CREAM + (240,)
        img.alpha_composite(layer)
        ImageDraw.Draw(img).text((x + pad, xy[1] + h / 2 + 1), text, font=f, fill=ink,
                                 anchor="lm")
        x += w + pad * 2 + gap
    return h


def cta(img: Image.Image, xy, text: str, badge: Path | None) -> int:
    """The home screen's Play button, borrowed: a gradient face over a darker ledge."""
    if badge is not None:
        art = Image.open(badge).convert("RGBA")
        h = 68
        art = art.resize((round(h * art.width / art.height), h), Image.LANCZOS)
        img.alpha_composite(art, (int(xy[0]), int(xy[1])))
        return h

    f = font(28, black=False)
    d = ImageDraw.Draw(img)
    label = f"{text}   →"
    w = int(d.textlength(label, font=f) + 64)
    h = 60

    face = Image.new("RGBA", (w, h))
    fd = ImageDraw.Draw(face)
    for i in range(w):
        t = i / max(1, w - 1)
        c = tuple(int(ACCENT_LIGHT[j] + (ACCENT_DARK[j] - ACCENT_LIGHT[j]) * t)
                  for j in range(3))
        fd.line([(i, 0), (i, h)], fill=c + (255,))
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=h // 2, fill=255)
    face.putalpha(mask)

    ledge = Image.new("RGBA", img.size)
    ImageDraw.Draw(ledge).rounded_rectangle(
        (xy[0], xy[1] + 9, xy[0] + w, xy[1] + h + 9), radius=h // 2, fill=(186, 62, 66, 255))
    img.alpha_composite(ledge)
    img.alpha_composite(face, (int(xy[0]), int(xy[1])))
    ImageDraw.Draw(img).text((xy[0] + w / 2, xy[1] + h / 2 + 1), label, font=f,
                             fill=(67, 22, 47, 255), anchor="mm")
    return h + 9


# --------------------------------------------------------------------------------------
# the copy
# --------------------------------------------------------------------------------------
# Every claim here is one the app actually keeps: no tutorial (the home screen's first
# button starts a run), no gate (there is no energy, no timer and nothing to wait for),
# one interstitial at most every two minutes and only between runs, and a single
# non-subscription purchase to remove it. Do not add a claim without the code for it.

COPY = {
    ("linkedin", "en"): dict(
        eyebrow="WHAAACK!  ·  ANDROID  ·  FREE",
        head=["Open it.", "Play."],
        sub="No tutorial. No energy bar.\nNo “wait 3 hours or watch an ad”.",
        chips=["Zero tutorials", "Zero timers", "Zero pay-to-win"],
        cta="Free on Google Play",
        foot="Deceptively simple. Quietly ruthless.",
    ),
    ("linkedin", "pl"): dict(
        eyebrow="WHAAACK!  ·  ANDROID  ·  ZA DARMO",
        head=["Włączasz.", "Grasz."],
        sub="Bez tutoriala. Bez energii.\nBez „czekaj 3 godziny albo obejrzyj reklamę”.",
        chips=["Zero tutoriali", "Zero liczników", "Zero pay-to-win"],
        cta="Za darmo w Google Play",
        foot="Nie samą pracą człowiek żyje — trzeba się zrelaksować.",
    ),
    ("facebook", "en"): dict(
        eyebrow="WHAAACK!  ·  ANDROID  ·  FREE",
        head=["There’s more to life", "than work."],
        sub="Turn it on and play. Deceptively\nsimple — which is why it keeps you.",
        chips=["No tutorial", "No waiting", "No paywall"],
        cta="Free on Google Play",
        foot="„Nie samą pracą człowiek żyje.”",
    ),
    ("facebook", "pl"): dict(
        eyebrow="WHAAACK!  ·  ANDROID  ·  ZA DARMO",
        head=["Nie samą pracą", "człowiek żyje."],
        sub="Włączasz i grasz. Banalnie proste —\ni właśnie dlatego wciąga.",
        chips=["Bez tutoriala", "Bez czekania", "Bez paywalla"],
        cta="Za darmo w Google Play",
        foot="Trzeba się zrelaksować.",
    ),
}


# --------------------------------------------------------------------------------------
# the cards
# --------------------------------------------------------------------------------------

def linkedin(lang: str, badge: Path | None) -> Image.Image:
    """1200x627. Upright, evenly lit, product-shaped: three phones in a row, text left."""
    w, h = 1200, 627
    img = background(w, h)

    # Overlapped by ~30px each, no more: the home screen's wordmark sits in the middle
    # 70% of its phone, and a hero placed any further left starts eating the K.
    place_phone(img, phone("01-home", 452), (692, 330))
    place_phone(img, phone("06-strike", 452), (1062, 330))
    place_phone(img, phone("04-splats", 530), (880, 314))

    copy = COPY[("linkedin", lang)]
    d = ImageDraw.Draw(img)
    col_x, col_w = 68, 496

    y = 46
    y += eyebrow(img, (col_x, y), copy["eyebrow"]) + 24

    # Sized so the longest of the four cards - Polish, which runs about a fifth longer
    # than the English - still lands its last line clear of the bottom safe margin.
    hf = fit(d, copy["head"], col_w, 76, 46)
    for line in copy["head"]:
        soft_text(img, (col_x, y), line, hf, CREAM + (255,))
        y += hf.size + 6
    y += 28

    sf, lines = paragraph(d, copy["sub"], col_w, 26, 20)
    for line in lines:
        soft_text(img, (col_x + 2, y), line, sf, CREAM + (232,), blur=6, alpha=200)
        y += sf.size + 9
    y += 22

    y += chips(img, (col_x, y), copy["chips"], filled=False, max_w=col_w) + 30
    y += cta(img, (col_x, y), copy["cta"], badge) + 24

    ff, lines = paragraph(d, copy["foot"], col_w + 20, 22, 17)
    for line in lines:
        soft_text(img, (col_x + 2, y), line, ff, ACCENT_LIGHT + (240,), blur=6, alpha=205)
        y += ff.size + 6
    return img


def facebook(lang: str, badge: Path | None) -> Image.Image:
    """1200x630. Louder: the phones fan out, fruit in the gaps, filled chips."""
    w, h = 1200, 630
    img = background(w, h)

    scatter(img, [
        ("watermelon", 11, (1104, 126), 84, -8),
        ("strawberry", 21, (592, 552), 70, -14),
    ])

    # Rotation widens a phone by its own height times sin(rot), which is what pushed the
    # home screen's wordmark under the hero on the first pass. These three are spaced on
    # the rotated half-widths, not the upright ones.
    place_phone(img, phone("01-home", 390), (734, 368), rot=8)
    place_phone(img, phone("06-strike", 390), (1062, 340), rot=-8)
    place_phone(img, phone("04-splats", 510), (926, 358))

    copy = COPY[("facebook", lang)]
    d = ImageDraw.Draw(img)
    col_x, col_w = 68, 500

    y = 52
    y += eyebrow(img, (col_x, y), copy["eyebrow"]) + 22

    hf = fit(d, copy["head"], col_w, 74, 40)
    for line in copy["head"]:
        soft_text(img, (col_x, y), line, hf, CREAM + (255,))
        y += hf.size + 6
    y += 28

    sf, lines = paragraph(d, copy["sub"], col_w, 25, 19)
    for line in lines:
        soft_text(img, (col_x + 2, y), line, sf, CREAM + (232,), blur=6, alpha=200)
        y += sf.size + 9
    y += 22

    y += chips(img, (col_x, y), copy["chips"], filled=True, max_w=col_w) + 30
    y += cta(img, (col_x, y), copy["cta"], badge) + 24

    ff, lines = paragraph(d, copy["foot"], col_w, 22, 17)
    for line in lines:
        soft_text(img, (col_x + 2, y), line, ff, ACCENT_LIGHT + (240,), blur=6, alpha=205)
        y += ff.size + 6
    return img


CARDS = {"linkedin": linkedin, "facebook": facebook}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--preview", action="store_true",
                    help="also write tools/social-preview.png, all four cards stacked")
    ap.add_argument("--play-badge", type=Path, default=None,
                    help="official 'Get it on Google Play' artwork to use instead of the "
                         "plain CTA pill; download it from Google's brand pages")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    made = []
    for card, build in CARDS.items():
        for lang in ("en", "pl"):
            img = build(lang, args.play_badge)
            # Feeds re-encode anyway, and an alpha channel is one more thing for them to
            # get wrong, so these go out flat.
            flat = Image.new("RGB", img.size, PANEL)
            flat.paste(img, (0, 0), img)
            path = OUT / f"{card}-{lang}-{flat.width}x{flat.height}.png"
            flat.save(path)
            made.append((path, flat))
            print(f"{path.relative_to(ROOT)}  {flat.width}x{flat.height}  "
                  f"{path.stat().st_size // 1024} KiB")

    if args.preview:
        scale = 0.62
        tw = max(int(im.width * scale) for _, im in made)
        sheet = Image.new("RGB", (tw, sum(int(im.height * scale) + 16 for _, im in made)),
                          (10, 6, 18))
        y = 0
        for _, im in made:
            small = im.resize((int(im.width * scale), int(im.height * scale)),
                              Image.LANCZOS)
            sheet.paste(small, (0, y))
            y += small.height + 16
        out = ROOT / "tools" / "social-preview.png"
        sheet.save(out)
        print(f"{out.relative_to(ROOT)}  {sheet.width}x{sheet.height}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
