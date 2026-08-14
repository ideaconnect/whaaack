"""Shared image helpers for the launcher-icon generator.

Deliberately depends on nothing but Pillow + numpy: the morphology and
connected-component routines below are the handful of scipy.ndimage calls the
generator needs, written out so `generate_launcher_icons.py` runs on a bare
`pip install pillow numpy`.
"""

from __future__ import annotations

import numpy as np
from PIL import Image

# ---------------------------------------------------------------- morphology


def shift_or(mask: np.ndarray) -> np.ndarray:
    """Union of `mask` with its four 4-connected translations (3x3 plus dilate)."""
    out = mask.copy()
    out[1:, :] |= mask[:-1, :]
    out[:-1, :] |= mask[1:, :]
    out[:, 1:] |= mask[:, :-1]
    out[:, :-1] |= mask[:, 1:]
    return out


def dilate(mask: np.ndarray, iterations: int = 1) -> np.ndarray:
    for _ in range(iterations):
        mask = shift_or(mask)
    return mask


def erode(mask: np.ndarray, iterations: int = 1) -> np.ndarray:
    # Erosion is dilation of the complement, with the border treated as outside
    # so shapes touching the edge erode inwards rather than being held up by it.
    inv = ~mask
    for _ in range(iterations):
        inv = shift_or(inv)
        inv[0, :] = inv[-1, :] = inv[:, 0] = inv[:, -1] = True
    return ~inv


def label(mask: np.ndarray) -> tuple[np.ndarray, int]:
    """4-connected components. Returns (labels, count); background is 0.

    Row-wise flood fill with union-find: linear in pixels and fast enough on the
    768px source without pulling in scipy.
    """
    h, w = mask.shape
    parent: list[int] = [0]

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)

    lab = np.zeros((h, w), np.int32)
    nxt = 1
    for y in range(h):
        row = mask[y]
        if not row.any():
            continue
        prev_lab = lab[y - 1] if y else None
        run_start = None
        # Walk the row's runs of True; each run needs at most one new label.
        idx = np.flatnonzero(np.diff(np.r_[False, row, False].astype(np.int8)))
        for s, e in zip(idx[0::2], idx[1::2]):
            above = prev_lab[s:e] if prev_lab is not None else None
            hits = np.unique(above[above > 0]) if above is not None else []
            if len(hits):
                cur = int(hits[0])
                for other in hits[1:]:
                    union(cur, int(other))
            else:
                parent.append(nxt)
                cur = nxt
                nxt += 1
            lab[y, s:e] = cur
            run_start = s
        del run_start
    # Flatten the union-find and renumber densely.
    roots = np.array([find(i) for i in range(nxt)], np.int32)
    remap = {0: 0}
    for r in np.unique(roots[1:]) if nxt > 1 else []:
        remap[int(r)] = len(remap)
    flat = np.zeros(nxt, np.int32)
    for i in range(1, nxt):
        flat[i] = remap[int(roots[i])]
    return flat[lab], len(remap) - 1


def largest_component(mask: np.ndarray) -> np.ndarray:
    lab, n = label(mask)
    if n == 0:
        return mask
    counts = np.bincount(lab.ravel())
    counts[0] = 0
    return lab == int(counts.argmax())


def fill_holes(mask: np.ndarray) -> np.ndarray:
    """Fill regions of False that are not connected to the image border."""
    outside = np.zeros_like(mask)
    outside[0, :] = outside[-1, :] = outside[:, 0] = outside[:, -1] = True
    outside &= ~mask
    lab, _ = label(~mask)
    border_labels = set(np.unique(lab[outside])) - {0}
    if not border_labels:
        return np.ones_like(mask)
    reachable = np.isin(lab, list(border_labels))
    return ~reachable


# --------------------------------------------------------------- geometry


def min_enclosing_circle(mask: np.ndarray, seed: int = 7) -> tuple[float, float, float]:
    """Smallest circle containing every True pixel. Returns (cx, cy, r).

    Welzl's algorithm over the convex hull of the boundary pixels — the hull is
    ~50 points, so the randomised incremental pass is instant.
    """
    import random

    padded = np.pad(mask, 1)
    interior = (
        padded[:-2, 1:-1] & padded[2:, 1:-1] & padded[1:-1, :-2] & padded[1:-1, 2:]
    )
    ys, xs = np.nonzero(mask & ~interior)
    pts = sorted(zip(xs.tolist(), ys.tolist()))

    def half(seq):
        out: list[tuple[int, int]] = []
        for p in seq:
            while len(out) >= 2:
                (ax, ay), (bx, by) = out[-2], out[-1]
                if (bx - ax) * (p[1] - ay) - (by - ay) * (p[0] - ax) <= 0:
                    out.pop()
                else:
                    break
            out.append(p)
        return out

    hull = np.array(half(pts)[:-1] + half(pts[::-1])[:-1], float)

    def from2(a, b):
        return (a + b) / 2, float(np.linalg.norm(a - b) / 2)

    def from3(a, b, c):
        (ax, ay), (bx, by), (cx, cy) = a, b, c
        d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if abs(d) < 1e-12:
            return None
        sa, sb, sc = ax * ax + ay * ay, bx * bx + by * by, cx * cx + cy * cy
        ux = (sa * (by - cy) + sb * (cy - ay) + sc * (ay - by)) / d
        uy = (sa * (cx - bx) + sb * (ax - cx) + sc * (bx - ax)) / d
        ctr = np.array([ux, uy])
        return ctr, float(np.linalg.norm(a - ctr))

    rng = random.Random(seed)
    order = list(range(len(hull)))
    rng.shuffle(order)
    p = hull[order]
    c, r = p[0].copy(), 0.0
    for i in range(1, len(p)):
        if np.linalg.norm(p[i] - c) <= r + 1e-7:
            continue
        c, r = p[i].copy(), 0.0
        for j in range(i):
            if np.linalg.norm(p[j] - c) <= r + 1e-7:
                continue
            c, r = from2(p[i], p[j])
            for k in range(j):
                if np.linalg.norm(p[k] - c) <= r + 1e-7:
                    continue
                got = from3(p[i], p[j], p[k])
                if got:
                    c, r = got
    return float(c[0]), float(c[1]), float(r)


# ----------------------------------------------------------------- raster


def place(
    art: Image.Image,
    circle: tuple[float, float, float],
    canvas: int,
    target_diameter: float,
    offset: tuple[float, float] = (0.0, 0.0),
) -> Image.Image:
    """Scale `art` so its enclosing `circle` becomes `target_diameter` px, centred.

    Everything is resampled once, straight from the full-resolution source, so
    no density ever inherits another density's resampling error.
    """
    cx, cy, r = circle
    scale = target_diameter / (2 * r)
    w, h = art.size
    scaled = art.resize(
        (max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS
    )
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    left = round(canvas / 2 - cx * scale + offset[0])
    top = round(canvas / 2 - cy * scale + offset[1])
    out.alpha_composite(scaled, (left, top))
    return out


def place_bbox(
    art: Image.Image, mask: np.ndarray, canvas: int, target: float
) -> Image.Image:
    """Scale `art` so the ink bounding box of `mask` fits `target` px, centred.

    Used for surfaces that apply no mask (the in-app logo), where fitting the
    box rather than the enclosing circle buys back a lot of apparent size.
    """
    ys, xs = np.nonzero(mask)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    scale = target / max(x1 - x0 + 1, y1 - y0 + 1)
    w, h = art.size
    scaled = art.resize(
        (max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS
    )
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.alpha_composite(
        scaled,
        (
            round(canvas / 2 - (x0 + x1 + 1) / 2 * scale),
            round(canvas / 2 - (y0 + y1 + 1) / 2 * scale),
        ),
    )
    return out


def rounded_rect_mask(size: int, radius: float, supersample: int = 8) -> Image.Image:
    from PIL import ImageDraw

    big = size * supersample
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        (0, 0, big - 1, big - 1), radius=radius * supersample, fill=255
    )
    return m.resize((size, size), Image.LANCZOS)


def circle_mask(size: int, supersample: int = 8) -> Image.Image:
    from PIL import ImageDraw

    big = size * supersample
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).ellipse((0, 0, big - 1, big - 1), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def save_png(img: Image.Image, path, *, allow_palette: bool = True) -> int:
    """Write the smallest PNG encoding that survives a fidelity check.

    This artwork is pixel art with a speckled dither. Resampling turns that
    dither into high-frequency noise, which true-colour PNG compresses badly,
    so PNG-8 is worth roughly 4x here - but only where the alpha channel
    survives it, and that depends on what alpha is being asked to carry.
    """
    import io
    import os

    path = str(path)
    os.makedirs(os.path.dirname(path), exist_ok=True)

    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    best = buf.getvalue()

    rgba = np.asarray(img.convert("RGBA"))
    lit = rgba[..., 3] > 0
    if not allow_palette or not lit.any():
        with open(path, "wb") as fh:
            fh.write(best)
        return len(best)

    colours = rgba[..., :3][lit]
    if np.array_equal(colours.max(axis=0), colours.min(axis=0)):
        # A single-colour tint layer (the monochrome icon) is pure alpha, so
        # index *by* alpha: 256 identical palette entries plus a full tRNS
        # table round trips bit-exactly at a fraction of the size. Generic
        # quantisation is skipped here - it spends its palette on colour it
        # does not need and collapses alpha to ~9 levels.
        flat = Image.fromarray(rgba[..., 3], "P")
        flat.putpalette(list(colours[0]) * 256)
        buf = io.BytesIO()
        flat.save(buf, "PNG", optimize=True, transparency=bytes(range(256)))
        if len(buf.getvalue()) < len(best):
            best = buf.getvalue()
        with open(path, "wb") as fh:
            fh.write(best)
        return len(best)

    try:
        pal = img.quantize(colors=256, method=Image.FASTOCTREE, dither=Image.NONE)
        buf = io.BytesIO()
        pal.save(buf, "PNG", optimize=True)
        a = np.asarray(img.convert("RGBA"), np.int16)
        b = np.asarray(pal.convert("RGBA"), np.int16)
        rim = (a[..., 3] > 0) & (a[..., 3] < 255)
        rim_err = np.abs(a[..., 3] - b[..., 3])[rim]
        rgb_err = np.abs(a[..., :3] - b[..., :3])[a[..., 3] > 128]
        # The alpha-level count is what separates the safe cases from the
        # damaged ones. Artwork floating on transparency keeps 60-80 levels
        # and its organic rim stays smooth; the legacy tiles are opaque behind
        # a mask, so the quantiser spends nothing on their handful of rim
        # pixels, drops to ~8 levels and visibly stair-steps the tile outline.
        ok = (
            len(np.unique(b[..., 3])) >= 32
            and (rim.sum() == 0 or np.percentile(rim_err, 95) <= 16)
            and rgb_err.mean() <= 6.0
            and np.percentile(rgb_err, 99.9) <= 48
        )
        if ok and len(buf.getvalue()) < len(best):
            best = buf.getvalue()
    except Exception:
        pass

    with open(path, "wb") as fh:
        fh.write(best)
    return len(best)
