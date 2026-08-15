"""Pad real device captures to the 1080x1920 the Play listing wants.

    python tools/make_store_screenshots.py --src <dir-of-captures>

The captures are 9:20 phone frames (1440x3200 and 1280x2856); the listing slot is 9:16. So
each frame is scaled to fit the height and centred, which leaves about 110px of bar down
each side. Rather than a flat colour, the bars are the same frame blown up to cover, blurred
and darkened, so they read as a soft continuation of that screenshot's own sky and grass
instead of a letterbox someone forgot to fill.

Play's rules this obeys: 1080x1920 is exactly 9:16, inside the 16:9..9:16 band it allows;
output is 24-bit with no alpha; eight files, which is the maximum it accepts.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "assets" / "store" / "screenshots"

W, H = 1080, 1920

# Source frame -> output name. The order is the order Play shows them in, so it reads as a
# run: the orchard, the countdown, play, the mistake, the score, the board.
SHOTS = [
    ("01_home_phone.png", "01-home"),
    ("02_countdown.png", "02-countdown"),
    ("03_early.png", "03-first-fruit"),
    ("04_splats.png", "04-splats"),
    ("06_late.png", "05-faster"),
    ("strike_03.png", "06-strike"),
    ("07_gameover.png", "07-game-over"),
    ("08_leaderboard.png", "08-leaderboard"),
]


def compose(src: Image.Image) -> Image.Image:
    src = src.convert("RGB")

    # Background: the same frame scaled to cover, blurred hard and dimmed so it never
    # competes with the sharp screenshot sitting on top of it.
    cover = max(W / src.width, H / src.height)
    bg = src.resize((max(1, int(src.width * cover)), max(1, int(src.height * cover))),
                    Image.LANCZOS)
    bg = bg.crop(((bg.width - W) // 2, (bg.height - H) // 2,
                  (bg.width - W) // 2 + W, (bg.height - H) // 2 + H))
    bg = bg.filter(ImageFilter.GaussianBlur(38))
    bg = ImageEnhance.Brightness(bg).enhance(0.55)

    # Foreground: the whole frame, nothing cropped away.
    fit = min(W / src.width, H / src.height)
    fw, fh = max(1, int(src.width * fit)), max(1, int(src.height * fit))
    fg = src.resize((fw, fh), Image.LANCZOS)

    out = bg.copy()
    x, y = (W - fw) // 2, (H - fh) // 2
    # A hairline down each seam so the sharp frame has a defined edge against the blur.
    edge = Image.new("RGB", (fw + 4, fh + 4), (12, 24, 44))
    out.paste(edge, (x - 2, y - 2))
    out.paste(fg, (x, y))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="directory holding the raw captures")
    args = ap.parse_args()
    src_dir = Path(args.src)

    OUT.mkdir(parents=True, exist_ok=True)
    for stale in OUT.glob("*.png"):
        stale.unlink()

    missing = [s for s, _ in SHOTS if not (src_dir / s).exists()]
    if missing:
        print("missing captures:", ", ".join(missing))
        return 1

    for source, name in SHOTS:
        img = compose(Image.open(src_dir / source))
        path = OUT / f"{name}.png"
        img.save(path)
        print(f"  {path.name:<22} {img.size[0]}x{img.size[1]}  {path.stat().st_size // 1024} KiB")
    print(f"{len(SHOTS)} screenshots -> {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
