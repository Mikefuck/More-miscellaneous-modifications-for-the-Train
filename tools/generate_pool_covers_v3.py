#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate pool_bg0..4 cover PNGs for type pools."""
from __future__ import annotations
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/assets/noellesroles/textures/gui/loot"

# SRE LootInfoScreen sketch area is wide; use 512x288 covers
W, H = 512, 288

COVERS = [
    # id, title, subtitle, colors (bg1, bg2, accent, text)
    (0, "刀 池", "KNIFE", ((28, 18, 42), (70, 30, 90), (220, 90, 140), (255, 240, 250))),
    (1, "枪 池", "GUN", ((12, 28, 48), (20, 70, 110), (80, 190, 255), (230, 245, 255))),
    (2, "棍 池", "BAT", ((22, 36, 20), (50, 90, 40), (160, 220, 90), (240, 255, 230))),
    (3, "手雷池", "GRENADE", ((48, 24, 12), (120, 55, 20), (255, 160, 60), (255, 245, 230))),
    (4, "完全随机", "RANDOM ALL", ((18, 18, 40), (55, 30, 100), (180, 120, 255), (245, 240, 255))),
]


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(bg1, bg2):
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        t = y / (H - 1)
        c = lerp(bg1, bg2, t)
        for x in range(W):
            # slight horizontal vignette
            edge = min(x, W - 1 - x) / (W / 2)
            f = 0.85 + 0.15 * edge
            px[x, y] = tuple(min(255, int(c[i] * f)) for i in range(3))
    return img


def try_font(size: int):
    candidates = [
        r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\msyhbd.ttc",
        r"C:\Windows\Fonts\simhei.ttf",
        r"C:\Windows\Fonts\arial.ttf",
    ]
    for p in candidates:
        try:
            return ImageFont.truetype(p, size=size)
        except Exception:
            continue
    return ImageFont.load_default()


def draw_icon(draw: ImageDraw.ImageDraw, pool_id: int, accent, cx, cy):
    if pool_id == 0:  # knife blade
        draw.polygon([(cx - 10, cy + 40), (cx + 10, cy + 40), (cx + 6, cy - 50), (cx - 6, cy - 50)], fill=accent)
        draw.rectangle([cx - 18, cy + 35, cx + 18, cy + 50], fill=(200, 200, 210))
    elif pool_id == 1:  # gun silhouette simple
        draw.rectangle([cx - 50, cy - 8, cx + 40, cy + 12], fill=accent)
        draw.rectangle([cx + 20, cy - 20, cx + 55, cy - 4], fill=accent)
        draw.rectangle([cx - 35, cy + 12, cx - 15, cy + 40], fill=accent)
    elif pool_id == 2:  # bat
        draw.rounded_rectangle([cx - 8, cy - 55, cx + 8, cy + 45], radius=6, fill=accent)
        draw.ellipse([cx - 16, cy + 40, cx + 16, cy + 58], fill=(180, 160, 120))
    elif pool_id == 3:  # grenade
        draw.ellipse([cx - 28, cy - 20, cx + 28, cy + 40], fill=accent)
        draw.rectangle([cx - 10, cy - 40, cx + 10, cy - 18], fill=(90, 90, 90))
        draw.arc([cx + 5, cy - 55, cx + 35, cy - 25], 200, 340, fill=(200, 200, 200), width=4)
    else:  # random stars
        for ox, oy, r in [(-40, -20, 10), (30, -30, 8), (-20, 30, 12), (40, 25, 7), (0, 0, 14)]:
            draw.ellipse([cx + ox - r, cy + oy - r, cx + ox + r, cy + oy + r], fill=accent)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    title_font = try_font(54)
    sub_font = try_font(22)
    hint_font = try_font(18)

    for pool_id, title, subtitle, (bg1, bg2, accent, text) in COVERS:
        img = gradient(bg1, bg2)
        # soft panel
        overlay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        od = ImageDraw.Draw(overlay)
        od.rounded_rectangle([24, 24, W - 24, H - 24], radius=24, fill=(0, 0, 0, 70), outline=accent + (180,), width=3)
        img = Image.alpha_composite(img.convert("RGBA"), overlay)
        draw = ImageDraw.Draw(img)

        draw_icon(draw, pool_id, accent, 120, H // 2)

        # titles
        draw.text((210, 90), title, font=title_font, fill=text)
        draw.text((214, 155), subtitle, font=sub_font, fill=accent)
        if pool_id < 4:
            draw.text((214, 200), "皮肤 30%  ·  金币 70%", font=hint_font, fill=(220, 220, 230))
        else:
            draw.text((214, 200), "皮肤 50%  ·  金币 50%", font=hint_font, fill=(220, 220, 230))

        # decorative corner marks
        draw.line([(40, 40), (80, 40)], fill=accent, width=3)
        draw.line([(40, 40), (40, 80)], fill=accent, width=3)
        draw.line([(W - 40, H - 40), (W - 80, H - 40)], fill=accent, width=3)
        draw.line([(W - 40, H - 40), (W - 40, H - 80)], fill=accent, width=3)

        out = img.convert("RGB")
        path = OUT / f"pool_bg{pool_id}.png"
        out.save(path, format="PNG", optimize=True)
        print("wrote", path)

    # leave higher pool_bg unused or dim placeholders so old IDs don't look broken
    for i in range(5, 19):
        p = OUT / f"pool_bg{i}.png"
        if p.exists():
            # small neutral placeholder
            img = Image.new("RGB", (W, H), (30, 30, 36))
            d = ImageDraw.Draw(img)
            d.text((40, 120), f"UNUSED #{i}", font=sub_font, fill=(100, 100, 110))
            img.save(p, format="PNG", optimize=True)
            print("placeholder", p)


if __name__ == "__main__":
    main()
