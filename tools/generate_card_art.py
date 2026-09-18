#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「角色卡背包」六张卡牌立绘贴图。

输出（128x128 RGBA PNG）：
    src/main/resources/assets/habitrain_lottery/textures/gui/cards/{id}.png

设计约定（与自选卡页 RoleSelectScreen 的调色板/几何语言保持一致）：
  * 深蓝黑底 + 阵营色柔光 + 斜向高光 + 极细网格，营造「现代扁平卡片」的质感；
  * 中心徽记使用与自选卡相同的图形语义（菱形 / 十字 / 五角星 / 火花 / 上升箭头）；
  * 所有渐变与柔光都在 128x128 上逐像素完成，徽记与描边在 4 倍超采样下绘制后
    降采样，因此缩放到任意 GUI 尺寸都不会出现锯齿。

用法：
    python tools/generate_card_art.py            # 写入 resources
    python tools/generate_card_art.py --preview  # 额外生成拼接预览图（build/ 下）
"""

from __future__ import annotations

import argparse
import math
import os

from PIL import Image, ImageDraw

# ---------------------------------------------------------------------------
# 基础常量
# ---------------------------------------------------------------------------

SIZE = 128          # 贴图边长
SS = 4              # 超采样倍数（徽记 / 描边 / 圆角遮罩）
RADIUS = 9          # 圆角半径（像素）
CORNER = 7          # 徽记区圆角

OUT_DIR = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "src", "main", "resources", "assets", "habitrain_lottery",
    "textures", "gui", "cards"))

PREVIEW_PATH = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "build", "card_art_preview.png"))

CARD_TOP = (0x1A, 0x21, 0x30)
CARD_BOTTOM = (0x08, 0x0B, 0x12)
INK = (0x0A, 0x0E, 0x16)

# id -> (accent, 主光方向)
CARDS = {
    "killer": 0xE2555F,
    "civilian": 0x44BB66,
    "neutral": 0xD9B23C,
    "neutral_for_killer": 0xB07CE8,
    "self_select": 0xFFD76A,
    "limit_break": 0xFFB56B,
}


def rgb(value: int) -> tuple[int, int, int]:
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)


def mix(a, b, t: float):
    t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def clamp01(x: float) -> float:
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


def rgba(color, alpha: float) -> tuple[int, int, int, int]:
    return (color[0], color[1], color[2], int(round(clamp01(alpha) * 255)))


# ---------------------------------------------------------------------------
# 遮罩工具
# ---------------------------------------------------------------------------

def rounded_mask(size: int, radius: float) -> Image.Image:
    """超采样绘制圆角矩形遮罩，返回 L 模式图。"""
    big = Image.new("L", (size * SS, size * SS), 0)
    draw = ImageDraw.Draw(big)
    draw.rounded_rectangle(
        (0, 0, size * SS - 1, size * SS - 1), radius=radius * SS, fill=255)
    return big.resize((size, size), Image.LANCZOS)


def pastel(color, amount: float):
    """提亮（amount>0）或压暗（amount<0）。"""
    return mix(color, (255, 255, 255) if amount >= 0 else (0, 0, 0), abs(amount))


# ---------------------------------------------------------------------------
# 底：渐变 + 柔光 + 网格 + 暗角
# ---------------------------------------------------------------------------

def base_layer(accent) -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE))
    px = img.load()

    top = mix(CARD_TOP, accent, 0.26)
    bottom = mix(CARD_BOTTOM, accent, 0.05)
    glow_cx, glow_cy = SIZE * 0.5, SIZE * 0.44
    glow_r = SIZE * 0.62
    grid = mix(accent, (255, 255, 255), 0.45)

    for y in range(SIZE):
        base = mix(top, bottom, y / (SIZE - 1))
        for x in range(SIZE):
            fx, fy = x + 0.5 - glow_cx, y + 0.5 - glow_cy
            dist = math.sqrt(fx * fx + fy * fy) / glow_r
            glow = max(0.0, 1.0 - dist) ** 2.2

            vx, vy = (x + 0.5) / SIZE * 2.0 - 1.0, (y + 0.5) / SIZE * 2.0 - 1.0
            vignette = 1.0 - 0.40 * clamp01((vx * vx + vy * vy) * 0.78)

            color = mix(base, accent, glow * 0.60)
            r = color[0] * vignette
            g = color[1] * vignette
            b = color[2] * vignette

            if x % 8 == 0 or y % 8 == 0:
                r += grid[0] * 0.045
                g += grid[1] * 0.045
                b += grid[2] * 0.045

            px[x, y] = (min(255, int(r)), min(255, int(g)), min(255, int(b)), 255)
    return img


# ---------------------------------------------------------------------------
# 徽记（在 4 倍超采样图层上用归一化坐标绘制）
# ---------------------------------------------------------------------------

def _p(x: float, y: float) -> tuple[float, float]:
    """归一化坐标 -> 超采样像素坐标。"""
    return (x * SIZE * SS, y * SIZE * SS)


def _poly(draw, points, fill):
    draw.polygon([_p(x, y) for x, y in points], fill=fill)


def _star_points(cx, cy, outer, inner, points, rotation=-90.0):
    pts = []
    for i in range(points * 2):
        angle = math.radians(rotation + i * 180.0 / points)
        r = outer if i % 2 == 0 else inner
        pts.append((cx + math.cos(angle) * r, cy + math.sin(angle) * r))
    return pts


def _blade(draw, cx, cy, length, width, angle_deg, fill):
    """一把带尖端的窄刃。"""
    a = math.radians(angle_deg)
    ux, uy = math.cos(a), math.sin(a)
    nx, ny = -uy, ux
    tip = (cx + ux * length * 0.5, cy + uy * length * 0.5)
    base_l = (cx - ux * length * 0.5 + nx * width * 0.5,
              cy - uy * length * 0.5 + ny * width * 0.5)
    base_r = (cx - ux * length * 0.5 - nx * width * 0.5,
              cy - uy * length * 0.5 - ny * width * 0.5)
    mid_l = (cx + nx * width * 0.30, cy + ny * width * 0.30)
    mid_r = (cx - nx * width * 0.30, cy - ny * width * 0.30)
    _poly(draw, [tip, mid_l, base_l, base_r, mid_r], fill)


def emblem_layer(card_id: str, accent) -> Image.Image:
    layer = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    bright = pastel(accent, 0.34)
    hot = pastel(accent, 0.72)
    deep = rgba(mix(accent, INK, 0.62), 1.0)
    dark = rgba(mix(INK, accent, 0.12), 0.92)

    cx = cy = 0.5

    if card_id == "killer":
        # 交叉双刃 + 中心火花
        _blade(draw, cx, cy, 0.70, 0.115, -52.0, rgba(bright, 1.0))
        _blade(draw, cx, cy, 0.70, 0.115, 52.0, rgba(bright, 1.0))
        _blade(draw, cx, cy, 0.70, 0.045, -52.0, rgba(hot, 0.85))
        _blade(draw, cx, cy, 0.70, 0.045, 52.0, rgba(hot, 0.85))
        # 中央菱形护手
        _poly(draw, [(cx, cy - 0.115), (cx + 0.115, cy), (cx, cy + 0.115), (cx - 0.115, cy)],
              rgba(mix(accent, INK, 0.35), 1.0))
        _poly(draw, [(cx, cy - 0.055), (cx + 0.055, cy), (cx, cy + 0.055), (cx - 0.055, cy)],
              rgba(hot, 1.0))

    elif card_id == "civilian":
        # 盾牌：外盾亮色，内盾挖空，中央竖脊
        outer = [(cx, cy - 0.36), (cx + 0.31, cy - 0.24), (cx + 0.27, cy + 0.13),
                 (cx, cy + 0.37), (cx - 0.27, cy + 0.13), (cx - 0.31, cy - 0.24)]
        inner = [(cx, cy - 0.25), (cx + 0.21, cy - 0.165), (cx + 0.185, cy + 0.10),
                 (cx, cy + 0.27), (cx - 0.185, cy + 0.10), (cx - 0.21, cy - 0.165)]
        _poly(draw, outer, rgba(bright, 1.0))
        _poly(draw, inner, dark)
        draw.rectangle([_p(cx - 0.032, cy - 0.235), _p(cx + 0.032, cy + 0.255)],
                       fill=rgba(hot, 1.0))
        draw.rectangle([_p(cx - 0.115, cy - 0.045), _p(cx + 0.115, cy + 0.045)],
                       fill=rgba(hot, 1.0))

    elif card_id == "neutral":
        # 双层菱形 + 中心点
        _poly(draw, [(cx, cy - 0.38), (cx + 0.34, cy), (cx, cy + 0.38), (cx - 0.34, cy)],
              rgba(bright, 1.0))
        _poly(draw, [(cx, cy - 0.245), (cx + 0.22, cy), (cx, cy + 0.245), (cx - 0.22, cy)],
              dark)
        _poly(draw, [(cx, cy - 0.125), (cx + 0.112, cy), (cx, cy + 0.125), (cx - 0.112, cy)],
              rgba(hot, 1.0))

    elif card_id == "neutral_for_killer":
        # 五角星 + 深色内环
        draw.polygon([_p(x, y) for x, y in _star_points(cx, cy, 0.40, 0.168, 5)],
                     fill=rgba(bright, 1.0))
        draw.polygon([_p(x, y) for x, y in _star_points(cx, cy, 0.235, 0.10, 5)],
                     fill=dark)
        draw.ellipse([_p(cx - 0.055, cy - 0.055), _p(cx + 0.055, cy + 0.055)],
                     fill=rgba(hot, 1.0))

    elif card_id == "self_select":
        # 轨道环 + 四角火花 + 三个候选取向点
        draw.ellipse([_p(cx - 0.375, cy - 0.375), _p(cx + 0.375, cy + 0.375)],
                     outline=rgba(mix(accent, (255, 255, 255), 0.18), 0.85),
                     width=int(0.026 * SIZE * SS))
        draw.polygon([_p(x, y) for x, y in _star_points(cx, cy, 0.235, 0.062, 4, -90.0)],
                     fill=rgba(hot, 1.0))
        for angle in (-150.0, -30.0, 90.0):
            a = math.radians(angle)
            dx, dy = cx + math.cos(a) * 0.375, cy + math.sin(a) * 0.375
            draw.ellipse([_p(dx - 0.036, dy - 0.036), _p(dx + 0.036, dy + 0.036)],
                         fill=rgba(bright, 1.0))

    elif card_id == "limit_break":
        # 三级上升箭羽 + 断裂的天花板横条
        for i, top in enumerate((0.10, 0.235, 0.37)):
            alpha = 1.0 - i * 0.16
            pts = [(cx - 0.30, top + 0.10), (cx, top), (cx + 0.30, top + 0.10),
                   (cx + 0.30, top + 0.185), (cx, top + 0.085), (cx - 0.30, top + 0.185)]
            _poly(draw, pts, rgba(bright, alpha))
        bar_y = 0.80
        for sign in (-1, 1):
            x0 = cx + sign * 0.105
            x1 = cx + sign * 0.40
            draw.rectangle([_p(min(x0, x1), bar_y), _p(max(x0, x1), bar_y + 0.062)],
                           fill=rgba(hot, 0.95))
        draw.rectangle([_p(cx - 0.055, bar_y - 0.02), _p(cx + 0.055, bar_y + 0.082)],
                       fill=rgba(mix(accent, INK, 0.55), 1.0))

    else:  # pragma: no cover - 防御性分支
        raise ValueError(f"unknown card id: {card_id}")

    return layer.resize((SIZE, SIZE), Image.LANCZOS)


# ---------------------------------------------------------------------------
# 装饰：斜向高光、内描边、顶部高光线
# ---------------------------------------------------------------------------

def decoration_layer(accent) -> Image.Image:
    layer = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    # 左上到右下的斜向光带
    _poly(draw, [(-0.05, 1.05), (0.52, -0.05), (0.74, -0.05), (0.17, 1.05)],
          (255, 255, 255, 22))

    small = layer.resize((SIZE, SIZE), Image.LANCZOS)

    outline = ImageDraw.Draw(small)
    border = rgba(mix(accent, (255, 255, 255), 0.28), 0.55)
    outline.rounded_rectangle((0, 0, SIZE - 1, SIZE - 1), radius=RADIUS,
                              outline=border, width=1)
    outline.rounded_rectangle((1, 1, SIZE - 2, SIZE - 2), radius=RADIUS - 1,
                              outline=(255, 255, 255, 14), width=1)
    # 顶部高光线
    outline.line((RADIUS, 2, SIZE - RADIUS, 2), fill=(255, 255, 255, 30))
    return small


# ---------------------------------------------------------------------------
# 组装
# ---------------------------------------------------------------------------

def build(card_id: str) -> Image.Image:
    accent = rgb(CARDS[card_id])
    img = base_layer(accent)
    img = Image.alpha_composite(img, emblem_layer(card_id, accent))
    img = Image.alpha_composite(img, decoration_layer(accent))
    img.putalpha(rounded_mask(SIZE, RADIUS))
    return img


def main() -> None:
    parser = argparse.ArgumentParser(description="生成角色卡立绘贴图")
    parser.add_argument("--preview", action="store_true", help="额外生成拼接预览图")
    args = parser.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    images = []
    for card_id in CARDS:
        image = build(card_id)
        path = os.path.join(OUT_DIR, f"{card_id}.png")
        image.save(path, "PNG", optimize=True)
        images.append(image)
        print(f"wrote {path} ({os.path.getsize(path)} bytes)")

    if args.preview:
        scale = 2
        cell = SIZE * scale
        sheet = Image.new("RGBA", (cell * 6 + 7 * 8, cell + 16), (0x0B, 0x0F, 0x18, 255))
        for i, image in enumerate(images):
            sheet.alpha_composite(image.resize((cell, cell), Image.NEAREST),
                                  (8 + i * (cell + 8), 8))
        os.makedirs(os.path.dirname(PREVIEW_PATH), exist_ok=True)
        sheet.save(PREVIEW_PATH, "PNG")
        print(f"wrote {PREVIEW_PATH}")


if __name__ == "__main__":
    main()
