#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate skins_quality.json and pools.json for habitrain_lottery defaults."""
from __future__ import annotations
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULTS = ROOT / "src/main/resources/data/habitrain_lottery/defaults"
LIST_PATH = DEFAULTS / "skins_list.txt"

GOLD = [
    ("bat/excalibur", "金色·圣剑"),
    ("bat/jin_gu_bang", "金色·金箍棒"),
    ("bat/platinum_arm", "金色·白金之臂"),
    ("bat/ultimate_gold_weapon", "金色·终极黄金武器"),
    ("knife/anubis", "金色·阿努比斯"),
    ("knife/echoium_sword", "金色·回声合金剑"),
    ("knife/galaxy_spark", "金色·银河火花"),
    ("knife/gamma_doppler_claw_knife", "金色·伽马多普勒爪刀"),
    ("knife/gold_claw_knife", "金色·黄金爪刀"),
    ("knife/golden_shear", "金色·金剪刀"),
    ("knife/quenched_titanium", "金色·淬炼钛金"),
    ("gun/blackgold", "金色·黑金"),  # list file uses revolver/; pool uses gun/
    ("gun/divine", "金色·神圣"),
    ("gun/gold_defibrillator", "金色·金色除颤器"),
    ("gun/golden_gun", "金色·金枪"),
    ("gun/golden_gyration", "金色·黄金回旋"),
    ("gun/white_gold_gun", "金色·白金枪"),
    ("gun/white_gun", "金色·钛金枪"),
]

# Map pool gun/* to skins_list revolver/*
def list_key(pool_key: str) -> str:
    if pool_key.startswith("gun/"):
        return "revolver/" + pool_key.split("/", 1)[1]
    return pool_key

EPIC_HINTS = (
    "plasma", "astral", "excalibur", "platinum", "divine", "legend", "ultimate",
    "echoium", "galaxy", "quenched", "dragon", "purgatory", "between_limits",
    "anubis", "black_king", "snow_king", "jin_gu", "white_gold", "blackgold",
)
RARE_HINTS = (
    "diamond", "gold", "golden", "titanium", "rune", "ice_", "blood", "gamma",
    "doppler", "fractal", "emperor", "tyrant", "devil", "halo", "omega", "crystal",
    "amethyst", "obsidian", "void", "neon", "cyber", "royal", "king", "queen",
)
UNCOMMON_HINTS = (
    "iron", "steel", "silver", "copper", "advanced", "combat", "composite",
    "wrench", "hammer", "axe", "sword", "claw", "pipe", "cylinder", "guitar",
    "musical", "instrument", "pickaxe", "sickle", "bat_", "revolver",
)

def score_quality(skin_id: str) -> str:
    s = skin_id.lower()
    name = s.split("/", 1)[-1]
    if any(h in s for h in EPIC_HINTS):
        return "epic"
    if any(h in s for h in RARE_HINTS):
        return "rare"
    if any(h in name for h in UNCOMMON_HINTS):
        return "uncommon"
    return "common"


def rebalance(qualities: dict[str, str]) -> None:
    """Nudge non-gold distribution toward ~45/30/18/7 without touching gold."""
    nongold = [k for k, v in qualities.items() if v != "gold"]
    nongold.sort()
    n = len(nongold)
    # target counts
    n_epic = max(1, round(n * 0.07))
    n_rare = max(1, round(n * 0.18))
    n_uncommon = max(1, round(n * 0.30))
    # rank by heuristic rank then id
    rank = {"epic": 3, "rare": 2, "uncommon": 1, "common": 0}
    ordered = sorted(nongold, key=lambda k: (-rank[qualities[k]], k))
    for i, k in enumerate(ordered):
        if i < n_epic:
            qualities[k] = "epic"
        elif i < n_epic + n_rare:
            qualities[k] = "rare"
        elif i < n_epic + n_rare + n_uncommon:
            qualities[k] = "uncommon"
        else:
            qualities[k] = "common"


def main() -> None:
    raw = LIST_PATH.read_text(encoding="utf-8-sig").splitlines()
    skins = [ln.strip().replace("\\", "/") for ln in raw if ln.strip()]
    gold_list_keys = {list_key(g) for g, _ in GOLD}
    gold_pool_keys = {g for g, _ in GOLD}

    qualities: dict[str, str] = {}
    for s in skins:
        if s in gold_list_keys:
            # store under pool key for gold guns
            pool = s
            if s.startswith("revolver/"):
                pool = "gun/" + s.split("/", 1)[1]
            qualities[pool] = "gold"
        else:
            qualities[s] = score_quality(s)
    # ensure all 18 gold present even if list uses revolver
    for g, _ in GOLD:
        qualities[g] = "gold"
    rebalance(qualities)

    # Build assignment: non-gold by quality, round-robin 18 pools
    by_q: dict[str, list[str]] = defaultdict(list)
    for k, q in qualities.items():
        if q == "gold":
            continue
        by_q[q].append(k)
    for q in by_q:
        by_q[q].sort()

    pool_items: list[dict[str, list[str]]] = [
        {"epic": [], "rare": [], "uncommon": [], "common": []} for _ in range(18)
    ]
    for q in ("epic", "rare", "uncommon", "common"):
        for i, skin in enumerate(by_q.get(q, [])):
            pool_items[i % 18][q].append(skin)

    pools = []
    for idx, (gkey, gname) in enumerate(GOLD):
        bands = []
        def band(p: float, items: list[str]):
            return {"Probability": p, "ItemList": items if items else ["coin"]}
        bands.append(band(0.01, [gkey]))
        bands.append(band(0.04, pool_items[idx]["epic"]))
        bands.append(band(0.12, pool_items[idx]["rare"]))
        bands.append(band(0.28, pool_items[idx]["uncommon"]))
        bands.append(band(0.45, pool_items[idx]["common"]))
        bands.append(band(0.10, ["coin"]))
        pools.append({
            "PoolID": idx,
            "Enable": True,
            "PoolName": gname,
            "PoolType": "gold",
            "QualityListGroup": bands,
        })

    quality_out = {"version": 1, "qualities": dict(sorted(qualities.items()))}
    pools_out = {"Pools": pools}

    (DEFAULTS / "skins_quality.json").write_text(
        json.dumps(quality_out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (DEFAULTS / "pools.json").write_text(
        json.dumps(pools_out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    # validation
    assert len(pools) == 18
    seen = set()
    for p in pools:
        probs = sum(b["Probability"] for b in p["QualityListGroup"])
        assert abs(probs - 1.0) < 1e-9, probs
        assert p["QualityListGroup"][0]["Probability"] == 0.01
        for b in p["QualityListGroup"][1:5]:
            for it in b["ItemList"]:
                if it == "coin":
                    continue
                assert it not in seen, it
                seen.add(it)
                assert qualities.get(it) != "gold" or it == p["QualityListGroup"][0]["ItemList"][0]
    print("OK pools=18 non_gold_unique=", len(seen), "qualities=", len(qualities))

if __name__ == "__main__":
    main()
