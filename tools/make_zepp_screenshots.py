"""Turn raw simulator captures into the 360x360 PNGs the Zepp console wants.

    python tools/make_zepp_screenshots.py square 390x450 raw1.png raw2.png ...
    python tools/make_zepp_screenshots.py round  480x480 raw1.png raw2.png ...

Writes to assets/zepp/screenshots/<shape>/, numbered in the order given, which is the
order the store shows them in.

What the console asks for (docs.zepp.com/docs/distribute):

    "The output size: 360x360px, format: PNG"
    "The background of screenshots should be transparent and not have a fill color."
    round       "Place the application interface screenshot centered within the 360x360px
                 transparent square with no margins around it."
    rectangular "Center the application interface screenshot in the 360x360px transparent
                 square with equal left/right margins and no top/bottom margins."

So the rule is not one rule. A rectangular 390x450 screen is fitted by its *height* - 360
tall, 312 wide, 24px of transparency down each side - which is what "no top/bottom margins"
means and is why nothing is cropped: the whole interface is in there, and the box is
squarer than the watch is. A round screen is square already, so it fills the box; its
corners are not part of the display and are punched out to transparent rather than left
black, which is what "no margins around it" plus "background transparent" together ask for.

The input is a *window* capture of the simulator, not the framebuffer, so the framebuffer
rectangle inside it has to be given. It is stable for a given emulator window; see
calibration.json in the scratch tooling, or measure a widget of known position.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT_ROOT = ROOT / "assets" / "zepp" / "screenshots"

BOX = 360

# Where the watch surface sits inside a simulator window capture.
FRAMEBUFFER_ORIGIN = (136, 78)

# Four times the output, so the circular cut-out for a round screen is smooth once it comes
# back down. A hard mask at 360 leaves a visibly stepped rim on a dark screenshot.
SUPERSAMPLE = 4


def surface(raw: Path, size: tuple[int, int]) -> Image.Image:
    """The watch screen alone, lifted out of a window capture."""
    left, top = FRAMEBUFFER_ORIGIN
    width, height = size
    image = Image.open(raw).convert("RGBA")
    if image.width < left + width or image.height < top + height:
        raise SystemExit(
            f"{raw.name} is {image.width}x{image.height}, too small to hold a "
            f"{width}x{height} framebuffer at {left},{top}"
        )
    return image.crop((left, top, left + width, top + height))


def to_box(screen: Image.Image, shape: str) -> Image.Image:
    canvas = Image.new("RGBA", (BOX, BOX), (0, 0, 0, 0))

    if shape == "round":
        if screen.width != screen.height:
            raise SystemExit("a round screen should be square in pixels")
        art = screen.resize((BOX * SUPERSAMPLE, BOX * SUPERSAMPLE), Image.LANCZOS)
        # The corners are not display. Punching them out is the difference between a
        # screenshot of a watch and a black square with a watch drawn on it.
        mask = Image.new("L", art.size, 0)
        ImageDraw.Draw(mask).ellipse((0, 0, art.size[0] - 1, art.size[1] - 1), fill=255)
        art.putalpha(mask)
        canvas.paste(art.resize((BOX, BOX), Image.LANCZOS), (0, 0))
        return canvas

    # Rectangular: height fills the box, and the slack goes left and right.
    width = round(screen.width * BOX / screen.height)
    if width > BOX:
        raise SystemExit(
            f"a {screen.width}x{screen.height} screen is wider than it is tall; "
            "fitting it by height would overflow the box"
        )
    canvas.paste(screen.resize((width, BOX), Image.LANCZOS), ((BOX - width) // 2, 0))
    return canvas


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("shape", choices=("round", "square"))
    ap.add_argument("size", help="framebuffer size, e.g. 390x450")
    ap.add_argument("raw", nargs="+", help="window captures, in the order to show them")
    args = ap.parse_args()

    try:
        width, height = (int(n) for n in args.size.lower().split("x"))
    except ValueError:
        raise SystemExit(f"could not read a WIDTHxHEIGHT out of {args.size!r}")

    out = OUT_ROOT / args.shape
    out.mkdir(parents=True, exist_ok=True)

    for index, name in enumerate(args.raw, start=1):
        raw = Path(name)
        if not raw.exists():
            raise SystemExit(f"missing: {raw}")
        image = to_box(surface(raw, (width, height)), args.shape)
        dest = out / f"{index:02d}-{raw.stem}.png"
        image.save(dest)
        opaque = image.split()[3].getbbox()
        print(
            f"{dest.relative_to(ROOT)}  {image.width}x{image.height}  "
            f"interface {opaque[2] - opaque[0]}x{opaque[3] - opaque[1]}  "
            f"{dest.stat().st_size:,} bytes"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
