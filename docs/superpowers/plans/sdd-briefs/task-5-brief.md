### Task 5: Full build + verify

**Files:** none new

- [ ] **Step 1: Validate pools with Python**

```bat
python -c "import json;from pathlib import Path;from collections import Counter
p=json.loads(Path('src/main/resources/data/habitrain_lottery/defaults/pools.json').read_text(encoding='utf-8'))
assert len(p['Pools'])==18
seen=set(); gold=set()
for pool in p['Pools']:
    bands=pool['QualityListGroup']
    assert abs(sum(b['Probability'] for b in bands)-1)<1e-9
    assert bands[0]['Probability']==0.01
    assert len(bands[0]['ItemList'])==1
    g=bands[0]['ItemList'][0]; assert g not in gold; gold.add(g)
    for b in bands[1:5]:
        for it in b['ItemList']:
            if it=='coin': continue
            assert it not in seen; seen.add(it)
print('gold',len(gold),'filler',len(seen))"
```

- [ ] **Step 2: Build and copy JAR**

```bat
gradlew.bat clean build
```
Expected: BUILD SUCCESSFUL  
Then ensure JAR is in `D:\Backup\mc mod\临时\` (build.gradle may already copy; if not, copy `build\libs\habitrain_lottery-*.jar` excluding `-sources`).

- [ ] **Step 3: Final user report**

Tell Mike:
- What changed
- Where configs are
- How to set up (JAR-only vs manual copy from 临时)
- 160 exchange
- Migration behavior for old worlds

---

## Spec coverage checklist

| Spec requirement | Task |
|------------------|------|
| World-local config layout | Task 3 (paths), Task 4 docs |
| JAR seed missing files | Task 3 `seedIfMissing` |
| meta config_version=2 | Task 1 meta, Task 2/3 |
| v1 backup + migrate | Task 3 |
| coinPerDraw 160 default + ≤10 migrate | Task 1 rates, Task 2, Task 3 |
| 18 gold pools, 1% gold, band split | Task 1 generator |
| Mutual exclusion by quality | Task 1 generator |
| skins_quality.json | Task 1, Task 3 seed |
| Export to 临时 + setup notes | Task 4 |
| Build + copy JAR | Task 5 |
| Player data untouched | Task 3 migrate scope |

## Self-review notes

- No TBD steps; generator script is complete.
- Method name kept as `loadOrSeed` to avoid missing call sites (`HabiLotteryMod`, `LotteryManagerBridge.reloadFromDisk`).
- Gold gun keys use `gun/` in pools/quality; list file uses `revolver/` — generator maps both.
