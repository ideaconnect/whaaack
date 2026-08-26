"""Build the three pictures the Zepp settings page shows, as base64 data URIs.

    python tools/generate_zepp_settings_assets.py
    python tools/generate_zepp_settings_assets.py --preview   # also tools/zepp-settings-preview.png

Writes `zeppos/setting/assets.js`, which is generated and committed - it is the settings
page's only way to show a picture.

Why base64 and not files
------------------------

The settings page runs inside the Zepp app on the phone, not on the watch, and it is not
part of the watch package: it has no `assets/` directory to read from. Its `Image`
component takes a URL or a base64 string and nothing else. A URL would mean the page went
blank whenever `idct.tech` was slow, unreachable, or the phone was offline - which is a
state a *watch* companion app is in fairly often - so everything is inlined. Three images
at these sizes come to a few tens of kilobytes, which is the right trade for a page that
always draws.

The IDCT mark
-------------

There is no raster of it in this repository, only `logo_idct.xml` - the Android
VectorDrawable generated from `Branding/Logo/XLOGO.svg`. That turns out to be no obstacle:
the mark is flat geometry, and every one of its twelve paths uses only `m`, `l`, `h`, `v`
and `z`. No curves at all, so it can be rasterised exactly by filling polygons, with none
of the flattening error a Bezier would bring.

It is filled by the non-zero winding rule, which is what VectorDrawable defaults to and
what this mark needs: the eyes, the acorn and the whisker are holes wound against the body,
and under an even-odd rule any two shapes that merely overlapped would punch a hole that
should not be there.
"""

from __future__ import annotations

import argparse
import base64
import io
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import iconkit as K  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
LOGO_XML = ROOT / "app" / "src" / "main" / "res" / "drawable" / "logo_idct.xml"
ANDROID_ICON = ROOT / "assets" / "icon" / "play-store-512.png"
OUT = ROOT / "zeppos" / "setting" / "assets.js"
PREVIEW = ROOT / "tools" / "zepp-settings-preview.png"

# Twice the size each is drawn at, so the images stay sharp on a phone's 2x/3x screen while
# the page lays them out in CSS pixels.
LOGO_W = 216
ANDROID_PX = 128
COFFEE_PX = 96

# The settings page's ink, so the mark sits in the same colour as the text under it.
LOGO_INK = (0x4A, 0x4A, 0x57)

# Buy Me a Coffee's cup is black on their yellow, and so is this one.
COFFEE_INK = (0x11, 0x11, 0x11)

SUPERSAMPLE = 4


# ------------------------------------------------------------------ the IDCT mark

TOKEN = re.compile(r"[MmLlHhVvZz]|-?\d*\.?\d+")


def subpaths(data: str) -> list[list[tuple[float, float]]]:
    """Every closed ring in one `pathData` string, in the path's own coordinates."""
    tokens = TOKEN.findall(data)
    rings: list[list[tuple[float, float]]] = []
    ring: list[tuple[float, float]] = []
    x = y = 0.0
    start = (0.0, 0.0)
    command = ""
    i = 0

    while i < len(tokens):
        token = tokens[i]
        if token.isalpha():
            command = token
            i += 1
            if command in "Zz":
                if ring:
                    rings.append(ring)
                    ring = []
                x, y = start
                continue
        if i >= len(tokens):
            break

        if command in "Mm":
            dx, dy = float(tokens[i]), float(tokens[i + 1])
            i += 2
            x, y = (x + dx, y + dy) if command == "m" else (dx, dy)
            if ring:
                rings.append(ring)
            ring = [(x, y)]
            start = (x, y)
            # A moveto followed by more pairs means lineto, per the SVG grammar.
            command = "l" if command == "m" else "L"
        elif command in "Ll":
            dx, dy = float(tokens[i]), float(tokens[i + 1])
            i += 2
            x, y = (x + dx, y + dy) if command == "l" else (dx, dy)
            ring.append((x, y))
        elif command in "Hh":
            dx = float(tokens[i])
            i += 1
            x = x + dx if command == "h" else dx
            ring.append((x, y))
        elif command in "Vv":
            dy = float(tokens[i])
            i += 1
            y = y + dy if command == "v" else dy
            ring.append((x, y))
        else:
            raise SystemExit(f"{LOGO_XML.name}: unsupported path command {command!r}")

    if ring:
        rings.append(ring)
    return rings


def read_logo() -> tuple[list[list[tuple[float, float]]], float, float]:
    source = LOGO_XML.read_text(encoding="utf-8")
    viewport = re.search(
        r'viewportWidth="([\d.]+)"\s*\n\s*android:viewportHeight="([\d.]+)"', source
    )
    if not viewport:
        raise SystemExit(f"{LOGO_XML.name}: no viewport")
    width, height = float(viewport.group(1)), float(viewport.group(2))

    # Each path sits in a <group> that carries the translate its SVG counterpart had as a
    # per-path transform, so the two have to be read together and in order.
    blocks = re.findall(
        r'android:translateX="(-?[\d.]+)"\s*\n\s*android:translateY="(-?[\d.]+)"'
        r'.*?android:pathData="([^"]*)"',
        source,
        re.DOTALL,
    )
    if not blocks:
        raise SystemExit(f"{LOGO_XML.name}: no <group><path> pairs")

    rings = []
    for tx, ty, data in blocks:
        dx, dy = float(tx), float(ty)
        for ring in subpaths(data):
            rings.append([(x + dx, y + dy) for x, y in ring])
    return rings, width, height


def fill_nonzero(rings, width: int, height: int, scale: float) -> np.ndarray:
    """Coverage for `rings`, by the non-zero winding rule, supersampled and boxed down."""
    big_w, big_h = width * SUPERSAMPLE, height * SUPERSAMPLE
    step = scale * SUPERSAMPLE

    x0s, y0s, x1s, y1s = [], [], [], []
    for ring in rings:
        points = [(x * step, y * step) for x, y in ring]
        if points[0] != points[-1]:
            points.append(points[0])
        for (ax, ay), (bx, by) in zip(points, points[1:]):
            if ay == by:
                continue  # horizontal edges cross no scanline
            x0s.append(ax)
            y0s.append(ay)
            x1s.append(bx)
            y1s.append(by)

    x0 = np.array(x0s)
    y0 = np.array(y0s)
    x1 = np.array(x1s)
    y1 = np.array(y1s)
    direction = np.where(y1 > y0, 1, -1)
    low = np.minimum(y0, y1)
    high = np.maximum(y0, y1)

    mask = np.zeros((big_h, big_w), np.uint8)
    for row in range(big_h):
        centre = row + 0.5
        hit = (low <= centre) & (centre < high)
        if not hit.any():
            continue
        t = (centre - y0[hit]) / (y1[hit] - y0[hit])
        crossings = x0[hit] + t * (x1[hit] - x0[hit])
        winding = direction[hit]
        order = np.argsort(crossings, kind="stable")
        crossings = crossings[order]
        inside = np.cumsum(winding[order]) != 0
        for i in np.nonzero(inside[:-1])[0]:
            a = max(int(np.ceil(crossings[i] - 0.5)), 0)
            b = min(int(np.ceil(crossings[i + 1] - 0.5)), big_w)
            if b > a:
                mask[row, a:b] = 255

    # Box filter down: the only antialiasing this needs, and exact.
    return (
        mask.reshape(height, SUPERSAMPLE, width, SUPERSAMPLE).mean(axis=(1, 3)).round().astype(np.uint8)
    )


def build_logo() -> Image.Image:
    rings, view_w, view_h = read_logo()
    scale = LOGO_W / view_w
    height = int(round(view_h * scale))
    alpha = fill_nonzero(rings, LOGO_W, height, scale)

    art = np.zeros((height, LOGO_W, 4), np.uint8)
    art[..., :3] = LOGO_INK
    art[..., 3] = alpha
    return Image.fromarray(art, "RGBA")


# --------------------------------------------------------------------- the others


def build_android() -> Image.Image:
    """The Play Store icon, rounded the way Android rounds it."""
    icon = Image.open(ANDROID_ICON).convert("RGBA").resize((ANDROID_PX, ANDROID_PX), Image.LANCZOS)
    radius = round(ANDROID_PX * 0.22)
    mask = Image.new("L", (ANDROID_PX * SUPERSAMPLE,) * 2, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, ANDROID_PX * SUPERSAMPLE - 1, ANDROID_PX * SUPERSAMPLE - 1),
        radius=radius * SUPERSAMPLE,
        fill=255,
    )
    mask = mask.resize((ANDROID_PX, ANDROID_PX), Image.LANCZOS)
    icon.putalpha(Image.fromarray(np.minimum(np.asarray(icon)[..., 3], np.asarray(mask))))
    return icon


def build_coffee() -> Image.Image:
    """A cup with steam, drawn rather than borrowed: it has to be ours to ship."""
    s = SUPERSAMPLE
    size = COFFEE_PX * s
    art = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    pen = ImageDraw.Draw(art)
    ink = COFFEE_INK + (255,)
    unit = size / 96

    def box(x0, y0, x1, y1):
        return (x0 * unit, y0 * unit, x1 * unit, y1 * unit)

    # Steam: three rising wisps, the middle one taller, so it reads as heat rather than as
    # three tally marks.
    for x, top, bottom in ((28, 10, 30), (46, 4, 30), (64, 10, 30)):
        pen.line(
            [(x * unit, bottom * unit), (x * unit, top * unit)],
            fill=ink,
            width=round(5 * unit),
        )
        pen.ellipse(box(x - 2.5, top - 2.5, x + 2.5, top + 2.5), fill=ink)
        pen.ellipse(box(x - 2.5, bottom - 2.5, x + 2.5, bottom + 2.5), fill=ink)

    # Handle, drawn before the body so the body's edge covers where it joins.
    pen.ellipse(box(62, 46, 88, 72), outline=ink, width=round(6 * unit))

    # The cup: a rounded-off tumbler, wider at the lip than at the base.
    pen.polygon(
        [
            (14 * unit, 40 * unit),
            (72 * unit, 40 * unit),
            (66 * unit, 78 * unit),
            (20 * unit, 78 * unit),
        ],
        fill=ink,
    )
    pen.rounded_rectangle(box(10, 36, 76, 48), radius=6 * unit, fill=ink)

    # Saucer.
    pen.rounded_rectangle(box(6, 82, 80, 90), radius=4 * unit, fill=ink)

    return art.resize((COFFEE_PX, COFFEE_PX), Image.LANCZOS)


# ------------------------------------------------------------------------- output


def data_uri(image: Image.Image, *, allow_palette: bool = True) -> tuple[str, int]:
    buffer = io.BytesIO()
    image.save(buffer, "PNG", optimize=True)
    best = buffer.getvalue()

    if allow_palette:
        # `iconkit.save_png` picks the smallest encoding that survives a fidelity check,
        # but it writes to a path. Round-trip through one rather than repeat its rules.
        scratch = ROOT / "tools" / "_settings_scratch.png"
        K.save_png(image, scratch)
        best = scratch.read_bytes()
        scratch.unlink()

    return "data:image/png;base64," + base64.b64encode(best).decode("ascii"), len(best)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true", help="also write tools/zepp-settings-preview.png")
    args = ap.parse_args()

    logo = build_logo()
    android = build_android()
    coffee = build_coffee()

    logo_uri, logo_bytes = data_uri(logo)
    android_uri, android_bytes = data_uri(android, allow_palette=False)
    coffee_uri, coffee_bytes = data_uri(coffee)

    OUT.write_text(
        "/**\n"
        " * Generated by tools/generate_zepp_settings_assets.py. Do not edit.\n"
        " *\n"
        " * The settings page is not part of the watch package and has no assets directory,\n"
        " * so its `Image` components are fed base64 rather than a path. Inlined rather than\n"
        " * fetched from idct.tech so the page draws the same with the phone offline.\n"
        " */\n"
        "\n"
        f"/** The IDCT mark, rasterised from app/src/main/res/drawable/logo_idct.xml. */\n"
        f"export const LOGO_IDCT = '{logo_uri}'\n"
        "\n"
        f"/** The Android game's Play Store icon. */\n"
        f"export const ICON_ANDROID = '{android_uri}'\n"
        "\n"
        f"/** A cup, for the Buy Me a Coffee row. */\n"
        f"export const ICON_COFFEE = '{coffee_uri}'\n",
        encoding="utf-8",
        newline="\n",
    )

    total = logo_bytes + android_bytes + coffee_bytes
    print(f"logo    {logo.size[0]}x{logo.size[1]}  {logo_bytes:,} bytes")
    print(f"android {android.size[0]}x{android.size[1]}  {android_bytes:,} bytes")
    print(f"coffee  {coffee.size[0]}x{coffee.size[1]}  {coffee_bytes:,} bytes")
    print(f"wrote {OUT.relative_to(ROOT)} - {total:,} bytes of image, {round(total * 4 / 3):,} as base64")

    if args.preview:
        pad = 24
        width = logo.width + android.width + coffee.width + pad * 4
        height = max(logo.height, android.height, coffee.height) + pad * 2
        sheet = Image.new("RGBA", (width, height), (0xFF, 0xDD, 0x00, 0xFF))
        x = pad
        for image in (logo, android, coffee):
            sheet.alpha_composite(image, (x, (height - image.height) // 2))
            x += image.width + pad
        sheet.convert("RGB").save(PREVIEW)
        print(f"wrote {PREVIEW.relative_to(ROOT)}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
