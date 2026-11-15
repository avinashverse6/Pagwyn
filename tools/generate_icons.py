#!/usr/bin/env python3
"""Generates every Pagwyn launcher asset from one vector-ish definition.

Run from the repository root:  python3 tools/generate_icons.py
Requires Pillow (dev-only dependency, not shipped in the app).

Outputs:
  app/src/main/res/mipmap-*/ic_launcher.png            legacy launcher icon (API 24-25)
  app/src/main/res/mipmap-*/ic_launcher_round.png      legacy round icon
  app/src/main/res/mipmap-*/ic_launcher_foreground.png adaptive icon foreground (API 26+)
  docs/assets/icon-*.png                               documentation assets
"""
from __future__ import annotations

import os

from PIL import Image, ImageDraw

INK_TOP = (39, 45, 66)
INK_BOTTOM = (22, 25, 36)
PAPER = (251, 250, 247, 255)
AMBER = (232, 164, 85, 255)
AMBER_DEEP = (196, 118, 30, 255)
FOLD_SHADE = (211, 206, 194, 255)
RULE = (178, 173, 160, 255)

SS = 8  # supersampling factor


def _gradient(size: int) -> Image.Image:
    """Smooth vertical gradient used as the icon background."""
    img = Image.new("RGB", (1, size))
    px = img.load()
    for y in range(size):
        t = y / max(1, size - 1)
        px[0, y] = tuple(
            round(INK_TOP[i] + (INK_BOTTOM[i] - INK_TOP[i]) * t) for i in range(3)
        )
    return img.resize((size, size), Image.BILINEAR).convert("RGBA")


def _page(draw: ImageDraw.ImageDraw, box, radius: float, fold: float) -> None:
    x0, y0, x1, y1 = box
    cut = fold * 0.55
    draw.rounded_rectangle(
        (x0, y0, x1 - cut, y1), radius=radius, fill=PAPER, corners=(True, False, True, True)
    )
    draw.rounded_rectangle(
        (x1 - cut, y0 + cut, x1, y1), radius=radius, fill=PAPER, corners=(False, True, True, False)
    )
    draw.polygon([(x1 - cut, y0), (x1, y0 + cut), (x1 - cut, y0 + cut)], fill=PAPER)
    # Folded corner shading.
    draw.polygon(
        [(x1 - cut, y0), (x1, y0 + cut), (x1 - cut, y0 + cut)], fill=FOLD_SHADE
    )


def _artwork(size: int, scale: float = 1.0) -> Image.Image:
    """Draws the page + wing mark on a transparent canvas of `size` px.

    `scale` shrinks the artwork around the centre, which is what adaptive icons
    need so the mark stays inside the 66dp safe zone.
    """
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = s / 108.0

    def p(v: float) -> float:
        return v * u

    # Artwork is authored in a 108x108 canvas and then scaled about the centre.
    def c(v: float) -> float:
        return p(54) + (p(v) - p(54)) * scale

    def r_box(x0, y0, x1, y1):
        return (c(x0), c(y0), c(x1), c(y1))

    def r_len(v: float) -> float:
        return p(v) * scale

    _page(d, r_box(31, 24, 77, 84), r_len(6), r_len(15))

    for i, (lx0, lx1, ly) in enumerate([(40, 68, 46), (40, 68, 55), (40, 68, 64), (40, 58, 73)]):
        d.rounded_rectangle(
            (*r_box(lx0, ly, lx1, ly + 3.2)[:2], *r_box(lx0, ly, lx1, ly + 3.2)[2:]),
            radius=r_len(1.6),
            fill=AMBER_DEEP if i == 0 else RULE,
        )

    for (ax, ay), (bx, by), w in [
        ((70, 66), (98, 50), 5.0),
        ((72, 74), (97, 62), 4.2),
        ((74, 82), (94, 74), 3.4),
    ]:
        ww = r_len(w)
        d.line([c(ax), c(ay), c(bx), c(by)], fill=AMBER, width=max(1, int(ww)))
        for (cx, cy) in ((ax, ay), (bx, by)):
            d.ellipse((c(cx) - ww / 2, c(cy) - ww / 2, c(cx) + ww / 2, c(cy) + ww / 2), fill=AMBER)

    return img.resize((size, size), Image.LANCZOS)


def legacy_icon(size: int) -> Image.Image:
    base = _gradient(size).convert("RGB")
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size * 4 - 1, size * 4 - 1), radius=int(size * 4 * 0.22), fill=255
    )
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0))
    art = _artwork(size)
    out.paste(art, (0, 0), art)
    out.putalpha(mask.resize((size, size), Image.LANCZOS))
    return out


def round_icon(size: int) -> Image.Image:
    base = _gradient(size).convert("RGB")
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0))
    art = _artwork(size, scale=0.86)
    out.paste(art, (0, 0), art)
    out.putalpha(mask.resize((size, size), Image.LANCZOS))
    return out


DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Adaptive icon foreground layers are 108dp; draw at the matching pixel size.
FOREGROUND_DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main() -> None:
    for density, px in DENSITIES.items():
        out_dir = os.path.join(ROOT, "app", "src", "main", "res", f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        legacy_icon(px).save(os.path.join(out_dir, "ic_launcher.png"))
        round_icon(px).save(os.path.join(out_dir, "ic_launcher_round.png"))
        _artwork(FOREGROUND_DENSITIES[density], scale=0.72).save(
            os.path.join(out_dir, "ic_launcher_foreground.png")
        )

    docs = os.path.join(ROOT, "docs", "assets")
    os.makedirs(docs, exist_ok=True)
    legacy_icon(512).save(os.path.join(docs, "icon-512.png"))
    legacy_icon(192).save(os.path.join(docs, "icon-192.png"))
    print("Pagwyn launcher assets generated.")


if __name__ == "__main__":
    main()
