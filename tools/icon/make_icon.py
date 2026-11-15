#!/usr/bin/env python3
"""Generates Ra2Announcer's mod icon (icon.png) in the Red Alert 2 "Republic's Glory"
(红色警戒2：共和国之辉) style: the gold-starred 八一 emblem on a red flag field, framed by a
dark steel bezel.

Deterministic (fixed noise seed, no network). Run from the repo root:

    python tools/icon/make_icon.py

Output: icon.png (packed into the mod jar root by build.gradle; Mindustry reads
`mod.root.child("icon.png")` as the mod icon).
"""

import math
import os

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

FINAL = 512
SS = 2  # supersample factor
S = FINAL * SS

GOLD = (242, 195, 60)
GOLD_HI = (255, 226, 130)
GOLD_LO = (176, 132, 26)


def hexc(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def radial_red_field(size):
    """Vertical red flag gradient with a warm radial highlight behind the emblem."""
    y = np.linspace(0.0, 1.0, size)[:, None]
    top = np.array(hexc("#9B0D0A"), dtype=np.float64)
    bottom = np.array(hexc("#4A0504"), dtype=np.float64)
    field = top[None, :] * (1.0 - y[..., None]) + bottom[None, :] * y[..., None]

    xs = np.linspace(-1.0, 1.0, size)[None, :]
    ys = np.linspace(-1.0, 1.0, size)[:, None]
    r = np.sqrt(xs ** 2 + (ys * 0.92) ** 2)
    glow = np.clip(1.0 - r / 0.78, 0.0, 1.0) ** 2
    field = field * (1.0 - 0.34 * glow[..., None]) + np.array(hexc("#C4201A")) * (0.34 * glow[..., None])

    field = np.broadcast_to(field, (size, size, 3)).copy()

    # Faint fabric weave so the field is not a flat fill at large sizes.
    field += np.sin(np.linspace(0, size * 0.55, size))[:, None, None] * np.array([3.0, 1.65, 1.5])
    return Image.fromarray(np.clip(field, 0, 255).astype(np.uint8), "RGB")


def star_points(cx, cy, outer, inner, rotation_deg=-90.0):
    points = []
    for i in range(10):
        radius = outer if i % 2 == 0 else inner
        angle = math.radians(rotation_deg + i * 36.0)
        points.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
    return points


def scale_points(points, cx, cy, factor):
    return [(cx + (x - cx) * factor, cy + (y - cy) * factor) for x, y in points]


def draw_bezel(img, size):
    """Dark steel frame with a bevelled highlight and a gold hairline, RA2-ish."""
    band = int(size * 0.030)
    frame = Image.new("RGB", (size, size), hexc("#12161d"))
    draw = ImageDraw.Draw(frame)
    draw.rectangle([0, 0, size - 1, size - 1], outline=hexc("#454e5e"), width=max(1, band // 5))
    draw.rectangle([band // 3, band // 3, size - 1 - band // 3, size - 1 - band // 3],
                   outline=hexc("#0b0e13"), width=max(1, band // 6))

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rectangle([0, 0, size - 1, size - 1], fill=255)
    ImageDraw.Draw(mask).rectangle([band, band, size - 1 - band, size - 1 - band], fill=0)
    img.paste(frame, (0, 0), mask)

    hairline = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inset = band + max(1, band // 4)
    ImageDraw.Draw(hairline).rectangle([inset, inset, size - 1 - inset, size - 1 - inset],
                                       outline=GOLD_LO + (170,), width=max(1, band // 3))
    img.alpha_composite(hairline)


def draw_emblem(img, size):
    cx = cy = size / 2.0
    outer = size * 0.300
    outer_points = star_points(cx, cy, outer, outer * 0.382)
    body_points = scale_points(outer_points, cx, cy, 0.795)

    # Dark halo so a red star still separates from the red flag field.
    halo = Image.new("L", (size, size), 0)
    ImageDraw.Draw(halo).polygon(scale_points(outer_points, cx, cy, 1.10), fill=170)
    halo = halo.filter(ImageFilter.GaussianBlur(size * 0.030))
    img.paste(Image.new("RGB", (size, size), (24, 2, 2)), (0, 0), halo)

    # Gold rim.
    ring = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(ring).polygon(outer_points, fill=GOLD + (255,))
    img.alpha_composite(ring)

    # Rim bevel: light from the upper left, shadow along the lower right.
    bevel = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bevel)
    bd.polygon(outer_points, fill=GOLD_LO + (150,))
    bd.polygon([(x - size * 0.004, y - size * 0.005) for x, y in scale_points(outer_points, cx, cy, 0.955)],
               fill=GOLD_HI + (200,))
    img.alpha_composite(bevel)

    # Red star body.
    ImageDraw.Draw(img).polygon(body_points, fill=hexc("#D21518") + (255,))

    # Soft top-down sheen over the red body.
    hi = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(hi).polygon(body_points, fill=(255, 128, 104, 70))
    ramp = Image.new("L", (size, size), 0)
    rd = ImageDraw.Draw(ramp)
    span = int(size * 0.36)
    for i in range(span):
        rd.rectangle([0, i, size, i + 1], fill=int(150 * (1.0 - i / span)))
    hi.putalpha(Image.composite(hi.getchannel("A"), Image.new("L", (size, size), 0), ramp))
    img.alpha_composite(hi)

    # "八一" in gold, shrunk until every glyph pixel sits inside the star body.
    inner_mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(inner_mask).polygon(scale_points(body_points, cx, cy, 0.90), fill=255)
    glyphs = render_emblem_text(size, inner_mask)
    if glyphs is None:
        raise SystemExit("could not fit 八一 inside the star")
    shadow = glyphs.filter(ImageFilter.GaussianBlur(size * 0.005))
    shadow.putalpha(shadow.getchannel("A").point(lambda v: int(v * 0.55)))
    img.alpha_composite(shadow, (int(size * 0.004), int(size * 0.006)))
    img.alpha_composite(glyphs)


def render_emblem_text(size, clip_mask):
    """Renders 八一 tightly tracked at the largest size that stays inside clip_mask."""
    cx = cy = size / 2.0
    probe = ImageDraw.Draw(Image.new("L", (1, 1)))
    for font_size in range(int(size * 0.16), 8, -2):
        font = load_cjk_font(font_size)
        boxes = [probe.textbbox((0, 0), ch, font=font) for ch in "八一"]
        widths = [b[2] - b[0] for b in boxes]
        heights = [b[3] - b[1] for b in boxes]
        tracking = max(widths) * 1.02

        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(canvas)
        for ch, width in zip("八一", widths):
            x = cx + (-tracking / 2 if ch == "八" else tracking / 2)
            draw.text((x, cy), ch, font=font, fill=GOLD_HI + (255,), anchor="mm")

        alpha = canvas.getchannel("A")
        outside = ImageChops.subtract(alpha, ImageChops.multiply(alpha, clip_mask))
        if outside.getbbox() is None and max(heights) < size * 0.26:
            return canvas
    return None


def load_cjk_font(size):
    candidates = [
        ("C:/Windows/Fonts/msyhbd.ttc", 0),
        ("C:/Windows/Fonts/simhei.ttf", 0),
        ("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", 0),
        ("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 0),
    ]
    for path, index in candidates:
        if os.path.exists(path):
            return ImageFont.truetype(path, size, index=index)
    raise SystemExit("no CJK font found; install Noto Sans CJK or run on Windows")


def main():
    size = S
    base = radial_red_field(size).convert("RGBA")

    # Cheap film grain so the gradients do not band.
    noise = np.random.default_rng(7).normal(0.0, 2.1, (size, size, 1))
    arr = np.asarray(base, dtype=np.float64)[:, :, :3] + noise
    base = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB").convert("RGBA")

    draw_emblem(base, size)

    # Vignette.
    xs = np.linspace(-1.0, 1.0, size)[None, :]
    ys = np.linspace(-1.0, 1.0, size)[:, None]
    r = np.sqrt(xs ** 2 + ys ** 2) / math.sqrt(2.0)
    darken = np.clip((r - 0.55) / 0.45, 0.0, 1.0) ** 1.6
    arr = np.asarray(base.convert("RGB"), dtype=np.float64) * (1.0 - 0.42 * darken[..., None])
    base = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB").convert("RGBA")

    draw_bezel(base, size)

    out = base.convert("RGB").resize((FINAL, FINAL), Image.LANCZOS)
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    path = os.path.join(root, "icon.png")
    out.save(path, "PNG", optimize=True)
    print("wrote", path, out.size)


if __name__ == "__main__":
    main()
