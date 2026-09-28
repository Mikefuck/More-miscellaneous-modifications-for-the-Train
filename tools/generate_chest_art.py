#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成开箱界面 3D MC 箱子的贴图图集。

输出（256x256 RGBA PNG）：
    src/main/resources/assets/habitrain_lottery/textures/gui/crate/chest.png

设计约定：
  * 几何沿用原版箱子：14x9x14 空心底座 + 14x5x14 箱盖（后铰链）+ 2x4x1 锁扣；
  * 每个 MC 像素 4 个贴图像素，保持方块像素感的同时给木纹、板缝、钉头、倒角留出细节；
  * 颜色全部量化到原版橡木箱的色阶，贴图只烘焙材质与接缝处的环境光遮蔽，
    受光（顶亮 / 正面中 / 侧面暗、暖色主光）由 CrateArt 按面法线实时计算；
  * 锁扣是灰阶金属，运行时按箱子强调色染色；
  * 箱沿（底座顶面）中间透明，露出空心内腔。

区域坐标必须与 CrateArt.ChestModel 中的 UV 常量一致。

用法：
    python tools/generate_chest_art.py            # 写入 resources
    python tools/generate_chest_art.py --preview  # 额外输出放大预览（build/crate-art-work/）
"""

from __future__ import annotations

import argparse
import math
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "habitrain_lottery", "textures", "gui", "crate", "chest.png")
PREVIEW = os.path.join(ROOT, "build", "crate-art-work", "chest-atlas-preview.png")

T = 4  # 每个 MC 像素的贴图像素数
ATLAS = 256

# 区域：name -> (u, v, w, h)，与 CrateArt 保持一致
REGIONS = {
    "base_front": (0, 0, 56, 36),
    "base_side": (56, 0, 56, 36),
    "lid_front": (0, 36, 56, 20),
    "lid_side": (56, 36, 56, 20),
    "lid_top": (112, 0, 56, 56),
    "lid_under": (168, 0, 56, 56),
    "rim": (112, 56, 56, 56),
    "floor": (168, 56, 48, 48),
    "wall_back": (0, 112, 48, 32),
    "wall_side": (48, 112, 48, 32),
    "lock_front": (96, 112, 8, 16),
    "lock_side": (104, 112, 4, 16),
    "lock_top": (108, 112, 8, 4),
}

# 原版橡木箱色阶（由暗到亮）
PLANK = [(0x6B, 0x45, 0x1D), (0x83, 0x56, 0x25), (0x9C, 0x6A, 0x31), (0xB2, 0x7E, 0x3F), (0xC6, 0x93, 0x52)]
FRAME = [(0x26, 0x17, 0x0A), (0x36, 0x22, 0x10), (0x46, 0x2D, 0x16), (0x58, 0x3A, 0x1D)]
INNER = [(0x1E, 0x13, 0x09), (0x2A, 0x1B, 0x0D), (0x37, 0x24, 0x12), (0x45, 0x2E, 0x17)]
METAL = [(0x30, 0x30, 0x33), (0x5A, 0x5A, 0x5E), (0x8E, 0x8E, 0x92), (0xBE, 0xBE, 0xC2), (0xEC, 0xEC, 0xEE)]


def rng_for(name: str) -> np.random.Generator:
    return np.random.default_rng(sum(ord(c) * 131 ** i for i, c in enumerate(name)) % (2 ** 32))


def quantize(values: np.ndarray, palette) -> np.ndarray:
    """values 0..1 -> palette RGB（按色阶取整，保证像素画的硬边）。"""
    idx = np.clip((values * len(palette)).astype(int), 0, len(palette) - 1)
    pal = np.array(palette, dtype=np.float32)
    return pal[idx]


def grain_field(w: int, h: int, rng: np.random.Generator, along_x: bool = True, stretch: float = 1.0) -> np.ndarray:
    """顺纹方向拉长的木纹：低频横向条纹 + 沿纹理的慢变扰动 + 少量细噪点。"""
    if not along_x:
        return grain_field(h, w, rng, True, stretch).T
    ys = np.arange(h)[:, None].astype(np.float32)
    xs = np.arange(w)[None, :].astype(np.float32)
    field = np.zeros((h, w), np.float32)
    for k in range(3):
        freq = rng.uniform(0.55, 1.25) * (k + 1)
        wobble = rng.uniform(0.02, 0.06) / stretch
        phase = rng.uniform(0, math.tau)
        field += np.sin(ys * freq + np.sin(xs * wobble * (k + 1) + phase) * 2.2 + phase) / (k + 1)
    field = (field - field.min()) / max(1e-6, field.max() - field.min())
    field += rng.normal(0, 0.07, (h, w))
    return np.clip(field, 0, 1)


def knot(arr: np.ndarray, cx: float, cy: float, rx: float, ry: float, strength: float) -> None:
    h, w = arr.shape
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.sqrt(((xs - cx) / rx) ** 2 + ((ys - cy) / ry) ** 2)
    ring = np.exp(-((d - 1.0) ** 2) / 0.08) * 0.5 + np.exp(-(d ** 2) / 0.12)
    arr -= ring * strength


def planks(w: int, h: int, heights, rng: np.random.Generator, palette, bias: float = 0.0,
           along_x: bool = True, joints: bool = True) -> np.ndarray:
    """横向木板：每块板一个基调 + 木纹，板缝上沿提亮、下沿压暗，偶尔一处对接缝和节疤。"""
    tone = np.zeros((h, w), np.float32)
    y = 0
    for i, ph in enumerate(heights):
        ph = min(ph, h - y)
        if ph <= 0:
            break
        g = grain_field(w, ph, rng)
        base = rng.uniform(0.40, 0.62) + bias
        board = base + (g - 0.5) * 0.46
        if rng.random() < 0.55 and w > 24:
            knot(board, rng.uniform(8, w - 8), rng.uniform(1.5, ph - 1.5), rng.uniform(2.2, 3.6), rng.uniform(1.0, 1.6), 0.22)
        board[0, :] += 0.20          # 上沿倒角受光
        if ph > 2:
            board[1, :] += 0.06
        board[-1, :] = 0.02          # 板缝
        if ph > 3:
            board[-2, :] -= 0.12
        if joints and w > 30 and rng.random() < 0.7:
            jx = int(rng.uniform(w * 0.25, w * 0.75))
            board[:-1, jx] = 0.06
            board[:-1, jx + 1] += 0.14
            board[:, jx + 1:] += rng.uniform(-0.08, 0.08)
        tone[y:y + ph] = board
        y += ph
    out = quantize(np.clip(tone, 0, 0.999), palette)
    if not along_x:
        out = out.transpose(1, 0, 2)
    return out


def frame(rgb: np.ndarray, border: int, rng: np.random.Generator, top: bool = True, bottom: bool = True,
          left: bool = True, right: bool = True, nails: bool = True) -> np.ndarray:
    """深色外框：外沿描边、内沿倒角（上/左亮，下/右暗）、内侧 AO、四角铁钉。"""
    h, w, _ = rgb.shape
    mask = np.zeros((h, w), bool)
    if top: mask[:border, :] = True
    if bottom: mask[h - border:, :] = True
    if left: mask[:, :border] = True
    if right: mask[:, w - border:] = True
    ys, xs = np.mgrid[0:h, 0:w]
    g = grain_field(w, h, rng) * 0.55 + 0.25
    # 外沿一圈最暗
    edge = np.zeros((h, w), bool)
    if top: edge[0, :] = True
    if bottom: edge[-1, :] = True
    if left: edge[:, 0] = True
    if right: edge[:, -1] = True
    g[edge] = 0.02
    # 外框内沿倒角
    if top: g[border - 1, :] = np.maximum(g[border - 1, :], 0.80)
    if left: g[:, border - 1] = np.maximum(g[:, border - 1], 0.72)
    if bottom: g[h - border, :] = np.minimum(g[h - border, :], 0.18)
    if right: g[:, w - border] = np.minimum(g[:, w - border], 0.22)
    # 外沿第二圈的磨损亮点（棱角被磨亮）
    wear = rng.random((h, w)) < 0.16
    ring2 = np.zeros((h, w), bool)
    if top: ring2[1, :] = True
    if left: ring2[:, 1] = True
    g[ring2 & wear] = 0.95
    fr = quantize(np.clip(g, 0, 0.999), FRAME)
    out = rgb.copy()
    out[mask] = fr[mask]
    # 内侧 AO：紧贴外框的两圈木板压暗
    inside = ~mask
    for d, k in ((1, 0.70), (2, 0.86)):
        near = np.zeros((h, w), bool)
        if top: near |= ys == border - 1 + d
        if bottom: near |= ys == h - border - d
        if left: near |= xs == border - 1 + d
        if right: near |= xs == w - border - d
        sel = near & inside
        out[sel] *= k
    if nails and border >= 4:
        for cx in ([1] if left else []) + ([w - 3] if right else []):
            for cy in ([1] if top else []) + ([h - 3] if bottom else []):
                out[cy:cy + 2, cx:cx + 2] = METAL[1]
                out[cy, cx] = METAL[3]
                out[cy + 1, cx + 1] = METAL[0]
    return out


def speckle(rgb: np.ndarray, rng: np.random.Generator, amount: float = 0.05) -> np.ndarray:
    """逐像素 ±亮度抖动与少量深色划痕，避免大面积同色。"""
    h, w, _ = rgb.shape
    jitter = 1 + rng.choice([-amount, 0, 0, 0, amount], (h, w))[:, :, None]
    out = rgb * jitter
    scratches = rng.random((h, w)) < 0.012
    out[scratches] *= 0.72
    return np.clip(out, 0, 255)


def face_body(name: str, w: int, h: int, heights, top_border=True, bottom_border=True) -> np.ndarray:
    rng = rng_for(name)
    rgb = planks(w, h, heights, rng, PLANK)
    rgb = frame(rgb, T, rng, top=top_border, bottom=bottom_border)
    return speckle(rgb, rng)


def lid_top() -> np.ndarray:
    rng = rng_for("lid_top")
    w = h = 14 * T
    rgb = planks(w, h, [4] + [12] * 4 + [4], rng, PLANK, bias=0.04)
    rgb = frame(rgb, T, rng)
    # 顶面中心一道很淡的斜向磨光，像被手来回摸过
    ys, xs = np.mgrid[0:h, 0:w]
    rub = np.exp(-(((xs - ys * 0.35) - w * 0.45) / (w * 0.22)) ** 2) * 0.06
    rgb *= (1 + rub)[:, :, None]
    return speckle(rgb, rng)


def lid_under() -> np.ndarray:
    rng = rng_for("lid_under")
    w = h = 14 * T
    rgb = planks(w, h, [4] + [12] * 4 + [4], rng, INNER, bias=0.10)
    rgb = frame(rgb, T, rng, nails=False)
    return speckle(rgb, rng, 0.04)


def rim() -> np.ndarray:
    """底座顶沿：四周 1px 厚的端面，中间透明露出内腔。"""
    rng = rng_for("rim")
    w = h = 14 * T
    g = grain_field(w, h, rng) * 0.5 + 0.35
    ys, xs = np.mgrid[0:h, 0:w]
    outer = (xs == 0) | (ys == 0) | (xs == w - 1) | (ys == h - 1)
    inner_edge = ((xs == T - 1) | (ys == T - 1) | (xs == w - T) | (ys == h - T))
    g[inner_edge] = 0.15
    g[(xs == 1) | (ys == 1)] = np.maximum(g[(xs == 1) | (ys == 1)], 0.85)
    g[outer] = 0.05
    rgb = quantize(np.clip(g, 0, 0.999), FRAME)
    alpha = np.full((h, w), 255, np.uint8)
    alpha[T:h - T, T:w - T] = 0
    return np.dstack([speckle(rgb, rng, 0.04), alpha])


def interior(name: str, w: int, h: int, heights, along_x=True) -> np.ndarray:
    rng = rng_for(name)
    rgb = planks(w, h, heights, rng, INNER, bias=0.06, along_x=along_x, joints=False)
    return speckle(rgb, rng, 0.04)


def lock(name: str, w: int, h: int, keyhole: bool) -> np.ndarray:
    """灰阶金属锁扣（运行时染色）：暗描边、上/左高光、下/右阴影、拉丝噪点、钥匙孔。"""
    rng = rng_for(name)
    g = np.full((h, w), 0.62, np.float32)
    g += rng.normal(0, 0.05, (h, w))
    g[:, :] += np.linspace(0.10, -0.12, h)[:, None]
    g[1, 1:-1] = 0.92
    g[1:-1, 1] = np.maximum(g[1:-1, 1], 0.80)
    g[-2, 1:-1] = 0.30
    g[1:-1, -2] = np.minimum(g[1:-1, -2], 0.34)
    g[0, :] = g[-1, :] = 0.05
    g[:, 0] = g[:, -1] = 0.05
    if keyhole and w >= 8 and h >= 12:
        kx = w // 2 - 1
        g[h - 9:h - 6, kx:kx + 2] = 0.0
        g[h - 6:h - 4, kx:kx + 2] = 0.0
        g[h - 9, kx:kx + 2] = 0.12
        g[h - 4, kx:kx + 2] = 0.88  # 孔下沿被磨亮
        g[h - 13, 2:w - 2] = 0.95   # 顶部横向高光条
    rgb = quantize(np.clip(g, 0, 0.999), METAL)
    return rgb


def build() -> Image.Image:
    atlas = np.zeros((ATLAS, ATLAS, 4), np.uint8)

    def put(name, arr):
        u, v, w, h = REGIONS[name]
        assert arr.shape[0] == h and arr.shape[1] == w, (name, arr.shape, (h, w))
        if arr.shape[2] == 3:
            arr = np.dstack([arr, np.full((h, w), 255)])
        atlas[v:v + h, u:u + w] = np.clip(arr, 0, 255).astype(np.uint8)

    body_h = 9 * T
    put("base_front", face_body("base_front", 14 * T, body_h, [4, 10, 11, 11]))
    put("base_side", face_body("base_side", 14 * T, body_h, [4, 11, 10, 11]))
    put("lid_front", face_body("lid_front", 14 * T, 5 * T, [4, 12, 4]))
    put("lid_side", face_body("lid_side", 14 * T, 5 * T, [4, 12, 4]))
    put("lid_top", lid_top())
    put("lid_under", lid_under())
    put("rim", rim())
    put("floor", interior("floor", 12 * T, 12 * T, [12, 12, 12, 12]))
    put("wall_back", interior("wall_back", 12 * T, 8 * T, [11, 11, 10]))
    put("wall_side", interior("wall_side", 12 * T, 8 * T, [10, 11, 11]))
    put("lock_front", lock("lock_front", 2 * T, 4 * T, True))
    put("lock_side", lock("lock_side", 1 * T, 4 * T, False))
    put("lock_top", lock("lock_top", 2 * T, 1 * T, False))
    return Image.fromarray(atlas, "RGBA")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", action="store_true")
    args = parser.parse_args()
    image = build()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    image.save(OUT, optimize=True)
    print("wrote", OUT)
    if args.preview:
        os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
        bg = Image.new("RGBA", image.size, (70, 70, 80, 255))
        bg.alpha_composite(image)
        bg.resize((ATLAS * 4, ATLAS * 4), Image.NEAREST).save(PREVIEW)
        print("wrote", PREVIEW)


if __name__ == "__main__":
    main()
