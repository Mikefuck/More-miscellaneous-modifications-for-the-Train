#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate type-based pools.json for habitrain_lottery defaults (config v3)."""
from __future__ import annotations
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULTS = ROOT / "src/main/resources/data/habitrain_lottery/defaults"
LIST_PATH = DEFAULTS / "skins_list.txt"


def pool_key(list_key: str) -> str:
    """skins_list uses revolver/; SRE pools must use gun/."""
    if list_key.startswith("revolver/"):
        return "gun/" + list_key.split("/", 1)[1]
    return list_key


def main() -> None:
    raw = LIST_PATH.read_text(encoding="utf-8-sig").splitlines()
    list_keys = [ln.strip().replace("\\", "/") for ln in raw if ln.strip()]
    pool_keys = [pool_key(k) for k in list_keys]

    by_type: dict[str, list[str]] = defaultdict(list)
    for k in pool_keys:
        t = k.split("/", 1)[0]
        by_type[t].append(k)
    for t in by_type:
        by_type[t] = sorted(set(by_type[t]))

    all_skins = sorted(set(pool_keys))
    assert "gun" in by_type and by_type["gun"], "gun skins missing"
    assert "knife" in by_type and "bat" in by_type and "grenade" in by_type

    def type_pool(pool_id: int, name: str, ptype: str, items: list[str]) -> dict:
        return {
            "PoolID": pool_id,
            "Enable": True,
            "PoolName": name,
            "PoolType": ptype,
            "QualityListGroup": [
                {"Probability": 0.30, "ItemList": items},
                {"Probability": 0.70, "ItemList": ["coin"]},
            ],
        }

    pools = [
        type_pool(0, "刀池", "knife", by_type["knife"]),
        type_pool(1, "枪池", "gun", by_type["gun"]),
        type_pool(2, "棍池", "bat", by_type["bat"]),
        type_pool(3, "手雷池", "grenade", by_type["grenade"]),
        {
            "PoolID": 4,
            "Enable": True,
            "PoolName": "完全随机",
            "PoolType": "all",
            "QualityListGroup": [
                {"Probability": 0.50, "ItemList": all_skins},
                {"Probability": 0.50, "ItemList": ["coin"]},
            ],
        },
    ]

    for p in pools:
        s = sum(b["Probability"] for b in p["QualityListGroup"])
        assert abs(s - 1.0) < 1e-9, (p["PoolName"], s)
        assert p["QualityListGroup"][0]["ItemList"], p["PoolName"]

    out = {"Pools": pools}
    path = DEFAULTS / "pools.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "OK pools=5",
        f"knife={len(by_type['knife'])}",
        f"gun={len(by_type['gun'])}",
        f"bat={len(by_type['bat'])}",
        f"grenade={len(by_type['grenade'])}",
        f"all={len(all_skins)}",
    )


if __name__ == "__main__":
    main()
