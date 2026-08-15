"""Build the four Play Games achievement icons from the game's own art.

    python tools/generate_achievement_icons.py            # write the four icons
    python tools/generate_achievement_icons.py --preview  # also write a contact sheet

Writes assets/achievements/survive-{30,60,90,120}.png, ready to drop into the "Icon"
field of the Play Console's achievement form. The names and descriptions that go with
them are in docs/PLAY-GAMES.md.

Like generate_store_graphics.py this is not a mockup: the splat behind each number is
a shipped splat mask filled with that fruit's own light-to-dark ramp out of Fruit.kt,
the fruit set into the ring is the shipped 32px sprite scaled with NEAREST so it stays
pixel art, and the palette is Theme.kt's. If the art changes, re-run this rather than
retouching a PNG.

What the four have to do as a *set* is show a progression, because they are seen
together in a list and read as a ladder or not at all. Two things carry it. The ring
is a clock: a quarter lit at 30 seconds, half at 60, three quarters at 90, the whole
way round at 120 — so the tier is legible before the number is, which matters because
Play Games draws these small and circular. And the fruit set at twelve o'clock, where
the arc starts, ripens as the tier climbs, with the lit colour warming from the
Success green to the crown gold the ad-free badge uses.

Play's rules this obeys: exactly 512x512, PNG, comfortably under 1 MB. The icon is
masked to a circle on some Play Games surfaces and left square on others, so the
background is opaque corner to corner — no alpha to punch a hole in a light theme —
and everything that has to be read stays inside the inscribed circle.
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = ROOT / "assets" / "achievements"

SIZE = 512

# Theme.kt, plus CrownIcon.kt's gold.
CREAM = (255, 243, 230)
ORCHARD_NIGHT = (42, 22, 51)
SUCCESS = (143, 210, 78)
ACCENT_LIGHT = (255, 201, 122)
ACCENT_DARK = (242, 112, 79)
CROWN_GOLD = (243, 195, 60)

# Fruit.kt's splat ramps, for the four fruits used below.
SPLAT_COLORS = {
    "strawberry": ((240, 104, 92), (163, 43, 51)),
    "orange": ((255, 176, 85), (208, 106, 24)),
    "apple": ((143, 210, 78), (75, 138, 40)),
    "watermelon": ((255, 112, 137), (95, 163, 74)),
}

# The ring is drawn 4x oversize and downsampled, which is what gives it and its round
# caps clean edges with no anti-aliasing code of our own. The pixel art is composited
# after that downsample, so it keeps its hard edges.
SS = 4

RING_RADIUS = 188
RING_WIDTH = 28

# The number is fitted to this width rather than to a fixed point size, so the
# three-digit 120 cannot barge into the ring. Matching widths across a numeric set
# reads as deliberate in a way that a 120 overhanging its ring does not.
MAX_NUMBER_WIDTH = 300

# Native size of the splat masks. Drawn at exactly that, so the ink stays crisp
# instead of being softened by an upscale.
SPLAT_SIZE = 256
SPLAT_ALPHA = 0.82

# (seconds, fruit, lit colour, splat variant). The variants are hand-picked from the
# 36 shipped masks: the round, full-bodied ones, because a spidery splat behind a
# number reads as damage to the number.
TIERS = (
    (30, "strawberry", SUCCESS, 0),
    (60, "orange", ACCENT_LIGHT, 5),
    (90, "apple", ACCENT_DARK, 15),
    (120, "watermelon", CROWN_GOLD, 12),
)

# What a full turn of the ring is worth. 120 seconds closes the circle, so 30 is a
# quarter of it.
FULL_TURN_SECONDS = 120


def font(size: float) -> ImageFont.FreeTypeFont:
    for path in (
        "C:/Windows/Fonts/ariblk.ttf",
        "C:/Windows/Fonts/seguibl.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
    ):
        if os.path.exists(path):
            return ImageFont.truetype(path, int(size))
    return ImageFont.load_default()


def polar(degrees: float, radius: float) -> tuple[float, float]:
    """A point on the ring. Zero is three o'clock and angles run clockwise, as PIL's do."""
    rad = math.radians(degrees)
    return SIZE / 2 + radius * math.cos(rad), SIZE / 2 + radius * math.sin(rad)


def ground() -> Image.Image:
    """OrchardNight, lifted in the middle so the badge has a centre to sit in."""
    img = Image.new("RGBA", (SIZE, SIZE), ORCHARD_NIGHT + (255,))
    draw = ImageDraw.Draw(img)
    centre = SIZE / 2
    # Painted outside in as filled circles, one per radius step. The banding that
    # leaves is finer than the blur that follows.
    for r in range(int(centre), 0, -2):
        t = 1 - r / centre
        tone = tuple(
            int(base + (lift - base) * (t ** 1.7))
            for base, lift in zip((30, 16, 38), (64, 34, 76))
        )
        draw.ellipse([centre - r, centre - r, centre + r, centre + r], fill=tone + (255,))
    return img.filter(ImageFilter.GaussianBlur(18))


def ring(seconds: int, lit: tuple[int, int, int]) -> Image.Image:
    """The clock: a full faint track with this tier's share of it lit."""
    big = SIZE * SS
    layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    box = [
        (SIZE / 2 - RING_RADIUS) * SS, (SIZE / 2 - RING_RADIUS) * SS,
        (SIZE / 2 + RING_RADIUS) * SS, (SIZE / 2 + RING_RADIUS) * SS,
    ]
    width = RING_WIDTH * SS

    # The unlit remainder, so a quarter-lit badge reads as a quarter *of something*
    # rather than as a stray tick.
    draw.arc(box, 0, 360, fill=CREAM + (36,), width=width)

    # Clockwise from twelve, which is where a clock starts and where the eye looks.
    sweep = 360 * seconds / FULL_TURN_SECONDS
    draw.arc(box, -90, -90 + sweep, fill=lit + (255,), width=width)

    # Round caps: PIL's arc has none, and a square-ended arc laid on a circle reads as
    # a broken ring rather than as a measurement.
    for angle in (-90, -90 + sweep):
        x, y = polar(angle, RING_RADIUS)
        r = width / 2
        draw.ellipse([x * SS - r, y * SS - r, x * SS + r, y * SS + r], fill=lit + (255,))

    return layer.resize((SIZE, SIZE), Image.LANCZOS)


def splat(fruit: str, variant: int) -> Image.Image:
    """A shipped splat mask filled with that fruit's ramp, as drawSplat does."""
    mask = Image.open(ASSETS / "splats" / f"splat{variant:02d}.png").convert("RGBA")
    mask = mask.getchannel("A")

    light, dark = SPLAT_COLORS[fruit]
    grad = Image.new("RGBA", (SPLAT_SIZE, SPLAT_SIZE))
    gd = ImageDraw.Draw(grad)
    for y in range(SPLAT_SIZE):
        # Only the top third of the ramp. In game a splat is opaque on its own
        # background; here it is a stain behind cream type, and running the full ramp
        # took the orange to brown and the watermelon to mauve — the fruit stopped
        # being identifiable, which is the one job this layer has.
        t = (y / (SPLAT_SIZE - 1)) * 0.3
        gd.line(
            [(0, y), (SPLAT_SIZE, y)],
            fill=tuple(int(l + (d - l) * t) for l, d in zip(light, dark)) + (255,),
        )
    grad.putalpha(mask.point(lambda a: int(a * SPLAT_ALPHA)))
    return grad


def number(seconds: int) -> Image.Image:
    """The tier, as large as its width budget allows, optically centred by cap height."""
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    text = str(seconds)
    size = 260
    while size > 40 and draw.textlength(text, font=font(size)) > MAX_NUMBER_WIDTH:
        size -= 2
    fitted = font(size)

    # Centre the digits' cap height, not the font's em box: digits have no descender,
    # so trusting the em box would hang the whole number high by the depth of one.
    cap = -draw.textbbox((0, 0), text, font=fitted, anchor="ls")[1]
    draw.text(
        (SIZE / 2, SIZE / 2 + cap / 2), text,
        font=fitted, fill=CREAM + (255,), anchor="ms",
    )
    return layer


def shadow(layer: Image.Image, blur: float, alpha: int, drop: int) -> Image.Image:
    """A soft dark copy of `layer`, to lift cream type off a bright splat."""
    dark = Image.new("RGBA", layer.size, (12, 6, 18, 0))
    dark.putalpha(layer.getchannel("A").point(lambda a: min(alpha, a)))
    dark = dark.filter(ImageFilter.GaussianBlur(blur))
    out = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    out.alpha_composite(dark, (0, drop))
    return out


def fruit_gem(fruit: str, lit: tuple[int, int, int]) -> Image.Image:
    """The sprite set into the ring at twelve o'clock, where the lit arc begins."""
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    x, y = polar(-90, RING_RADIUS)
    draw = ImageDraw.Draw(layer)
    # A dark disc with a lit rim, so a pale fruit still has an edge against a pale arc.
    draw.ellipse([x - 52, y - 52, x + 52, y + 52], fill=(24, 12, 30, 255))
    draw.ellipse([x - 52, y - 52, x + 52, y + 52], outline=lit + (255,), width=6)

    sprite = Image.open(ASSETS / "fruits" / f"{fruit}.png").convert("RGBA")
    sprite = sprite.resize((88, 88), Image.NEAREST)
    layer.alpha_composite(sprite, (int(x - 44), int(y - 44)))
    return layer


def badge(seconds: int, fruit: str, lit: tuple[int, int, int], variant: int) -> Image.Image:
    img = ground()

    ink = splat(fruit, variant)
    img.alpha_composite(ink, ((SIZE - SPLAT_SIZE) // 2, (SIZE - SPLAT_SIZE) // 2))

    img.alpha_composite(ring(seconds, lit))

    digits = number(seconds)
    img.alpha_composite(shadow(digits, blur=14, alpha=210, drop=9))
    img.alpha_composite(digits)

    img.alpha_composite(fruit_gem(fruit, lit))
    return img


def contact_sheet(made: list[Image.Image]) -> Image.Image:
    """The full square, then the circle at the two sizes Play Games actually lists them."""
    pad = 20
    sheet = Image.new(
        "RGBA",
        (len(made) * (SIZE + pad) + pad, SIZE + 160 + pad * 3),
        (18, 10, 24, 255),
    )
    mask = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, SIZE - 1, SIZE - 1], fill=255)
    for i, img in enumerate(made):
        x = pad + i * (SIZE + pad)
        sheet.paste(img, (x, pad))
        for j, px in enumerate((144, 72)):
            small = img.resize((px, px), Image.LANCZOS)
            small.putalpha(mask.resize((px, px), Image.LANCZOS))
            sheet.alpha_composite(small, (x + 40 + j * 190, SIZE + pad * 2))
    return sheet


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", action="store_true")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    made: list[Image.Image] = []
    for seconds, fruit, lit, variant in TIERS:
        img = badge(seconds, fruit, lit, variant)
        path = OUT / f"survive-{seconds}.png"
        img.save(path)
        made.append(img)
        print(
            f"{path.relative_to(ROOT)}  {img.size[0]}x{img.size[1]}  "
            f"{path.stat().st_size / 1024:.0f} KB"
        )

    if args.preview:
        preview = Path(__file__).parent / "achievements-preview.png"
        contact_sheet(made).save(preview)
        print(preview.relative_to(ROOT))

    return 0


if __name__ == "__main__":
    sys.exit(main())
