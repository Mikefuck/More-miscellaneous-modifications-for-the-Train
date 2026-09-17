# Title (NameTag) Local Bridge + Mod Menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Localize SRE NameTag (称号/名片) data under `world/habitrain_lottery/titles/`, manage catalog + per-player owned/current via a new Mod Menu config tab, keep vanilla player equip UI, and include titles in existing full-tree backups.

**Architecture:** World JSON is authoritative (catalog + per-player files). On join and after any CCA mutation, sync `NameTagInventoryComponent` ↔ `LocalTitleStore`. Mixin disables nametag MySQL sync. OP UI lives as a new tab on `LotteryConfigRootScreen` with C2S modify/save packets. Player equip stays on SRE `UpdateNameTagSelectedPayload`; persist mixin captures it.

**Tech Stack:** Java 21, Fabric 1.21.1, Mixin, existing Gson stores, JUnit 5, Gradle.

## Global Constraints

- Address user as **Mike**.
- File access only under `D:\Backup\mc mod\` — never `D:\Backup\mc mod\backup\`.
- After any mod code change: `gradlew.bat clean build` from project (use junction `C:\tmp\hlt` if Chinese path breaks tests), copy non-sources JAR to `D:\Backup\mc mod\临时\`.
- Spec: `docs/superpowers/specs/2026-07-18-title-nametag-local-bridge-design.md`.
- No git in project — skip commits.
- Reuse patterns from mail/backpack/skin local bridges; do not reimplement player equip GUI.
- Chinese hard-coded UI labels matching existing config screens.

## File map

| File | Responsibility |
|------|----------------|
| Create: `src/main/java/com/habitrain/lottery/title/TitlePaths.java` | `titles/`, catalog, player file paths |
| Create: `src/main/java/com/habitrain/lottery/title/TitleCatalog.java` | catalog model + Gson helpers |
| Create: `src/main/java/com/habitrain/lottery/title/PlayerTitleData.java` | owned/current DTO |
| Create: `src/main/java/com/habitrain/lottery/title/LocalTitleStore.java` | load/save/flush catalog + players |
| Create: `src/main/java/com/habitrain/lottery/title/TitleService.java` | grant/revoke/set/clear/pushToCca/loadOnJoin |
| Create: `src/main/java/com/habitrain/lottery/mixin/NameTagMysqlBypassMixin.java` | cancel MySQL nametag sync |
| Create: `src/main/java/com/habitrain/lottery/mixin/NameTagInventoryPersistMixin.java` | persist CCA mutations + optional § display fix hook point |
| Create: `src/main/java/com/habitrain/lottery/mixin/NameTagGenerateLiteralMixin.java` | if display has `§`, use literal not translatable |
| Create: `src/test/java/com/habitrain/lottery/title/LocalTitleStoreTest.java` | unit tests for store |
| Modify: `src/main/resources/habitrain_lottery.mixins.json` | register new mixins |
| Modify: `src/main/java/com/habitrain/lottery/storage/MetaFeaturePaths.java` | ensure titles dirs in ensureDirs **or** TitlePaths.ensure only |
| Modify: `src/main/java/com/habitrain/lottery/HabiLotteryMod.java` | join hook TitleService.loadOnJoin |
| Modify: `src/main/java/com/habitrain/lottery/storage/LotteryBackupService.java` | flush titles before copy |
| Modify: `src/main/java/com/habitrain/lottery/network/LotteryNetwork.java` | title packets + handlers |
| Modify: `src/main/java/com/habitrain/lottery/client/LotteryClientNetwork.java` | client send/receive title snapshot |
| Modify: `src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java` | 称号 tab |

---

### Task 1: TitlePaths + models + LocalTitleStore (TDD)

**Files:**
- Create: `title/TitlePaths.java`, `TitleCatalog.java`, `PlayerTitleData.java`, `LocalTitleStore.java`
- Test: `src/test/java/com/habitrain/lottery/title/LocalTitleStoreTest.java`

**Interfaces:**
- Produces:
  - `TitlePaths.ensureDirs()`, `catalogFile()`, `playerFile(UUID)`, `playersDir()` under `WorldLotteryPaths.root()/titles`
  - `TitleCatalog` with `int version`, `List<TitleEntry> titles`; `TitleEntry(id, display, enabled)`
  - `PlayerTitleData` with `List<String> owned`, `String current`, `long updatedAt`
  - `LocalTitleStore.get()` singleton: `loadCatalog()`, `saveCatalog(TitleCatalog)`, `loadPlayer(UUID)`, `savePlayer(UUID, PlayerTitleData)`, `flushAll()`, `grant(UUID, display)`, `revoke(UUID, display)`, `setCurrent(UUID, display)`, `clear(UUID)`

- [ ] **Step 1: Failing test**

```java
package com.habitrain.lottery.title;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalTitleStoreTest {
    @TempDir Path temp;

    @Test
    void catalogRoundTripAndPlayerGrantDedupe() throws Exception {
        // Test store against temp root via package-visible test hook OR
        // pure static helpers: serialize catalog JSON with Gson and PlayerTitleData grant logic.
        PlayerTitleData d = new PlayerTitleData();
        d.owned.add("§6[服主]");
        assertTrue(LocalTitleStore.grantInMemory(d, "§6[服主]")); // false if already owned — first true
        assertFalse(LocalTitleStore.grantInMemory(d, "§6[服主]"));
        assertEquals(1, d.owned.size());
        assertTrue(LocalTitleStore.grantInMemory(d, "§b[赞助]"));
        assertTrue(LocalTitleStore.setCurrentInMemory(d, "§b[赞助]"));
        assertEquals("§b[赞助]", d.current);
        assertFalse(LocalTitleStore.setCurrentInMemory(d, "nope"));
        LocalTitleStore.revokeInMemory(d, "§b[赞助]");
        assertEquals("", d.current);
        assertEquals(1, d.owned.size());
    }
}
```

Implement `grantInMemory` / `revokeInMemory` / `setCurrentInMemory` as package-visible static helpers on `LocalTitleStore` used by both tests and service.

- [ ] **Step 2: Run — expect FAIL**

```bat
cd /d C:\tmp\hlt
gradlew.bat test --tests com.habitrain.lottery.title.LocalTitleStoreTest
```

- [ ] **Step 3: Implement TitlePaths, models, LocalTitleStore**

`TitlePaths`:

```java
public final class TitlePaths {
    public static Path titlesDir() { return WorldLotteryPaths.root().resolve("titles"); }
    public static Path catalogFile() { return titlesDir().resolve("catalog.json"); }
    public static Path playersDir() { return titlesDir().resolve("players"); }
    public static Path playerFile(UUID id) { return playersDir().resolve(id + ".json"); }
    public static void ensureDirs() throws IOException {
        if (!WorldLotteryPaths.ready()) return;
        Files.createDirectories(playersDir());
    }
}
```

`LocalTitleStore`: Gson pretty print; load empty catalog if missing; save creates dirs; in-memory helpers for grant/revoke/set/clear as above; `flushAll` rewrites dirty players + catalog if dirty.

- [ ] **Step 4: Tests pass**

```bat
gradlew.bat test --tests com.habitrain.lottery.title.LocalTitleStoreTest
```

- [ ] **Step 5: Commit skip (no git)**

---

### Task 2: TitleService + join load + mixins (MySQL bypass + persist)

**Files:**
- Create: `title/TitleService.java`
- Create: `mixin/NameTagMysqlBypassMixin.java`
- Create: `mixin/NameTagInventoryPersistMixin.java`
- Create: `mixin/NameTagGenerateLiteralMixin.java` (display § fix)
- Modify: `habitrain_lottery.mixins.json`
- Modify: `HabiLotteryMod.java` (or existing join listener) to call `TitleService.onPlayerJoin`

**Interfaces:**
- `TitleService.onPlayerJoin(ServerPlayer)`: load player JSON → replace CCA nameTags + current → sync()
- `TitleService.grantOnline(ServerPlayer, String display)` / offline UUID variants
- Persist mixin: after `addNameTag`/`removeNameTag`/`setCurrentNameTag`/`clear`/`init` RETURN, if server player and world ready, snapshot CCA → `LocalTitleStore.savePlayer`
- Use a ThreadLocal `boolean applyingLocal` so join push does not double-write thrash (optional but recommended)

- [ ] **Step 1: NameTagMysqlBypassMixin** (mirror skins mixin)

Target `net.exmo.sre.nametag.NameTagInventoryComponent`, remap=false:

- cancel `initializeNetworkSync` (call disableNetworkSync if exists)
- cancel `syncFromLinkedServer`
- cancel `syncToNetwork`
- cancel `flushNetworkSyncBlocking` return true
- cancel `flushNetworkSyncAsyncOnDisconnect`

- [ ] **Step 2: NameTagInventoryPersistMixin**

Inject RETURN on `addNameTag`, `removeNameTag`, `setCurrentNameTag`, `clear` (and `init` if needed):

```java
private void habi$persist(CallbackInfo ci) {
    if (TitleService.isApplyingLocal()) return;
    Player p = /* component player field via @Shadow or getPlayer() */;
    if (!(p instanceof ServerPlayer sp) || !WorldLotteryPaths.ready()) return;
    TitleService.persistFromComponent(sp);
}
```

`persistFromComponent`: read nameTags + CurrentNameTag → savePlayer.

- [ ] **Step 3: TitleService.onPlayerJoin**

```java
public static void onPlayerJoin(ServerPlayer player) {
    if (!WorldLotteryPaths.ready()) return;
    applyingLocal = true;
    try {
        TitlePaths.ensureDirs();
        PlayerTitleData data = LocalTitleStore.get().loadPlayer(player.getUUID());
        NameTagInventoryComponent c = NameTagInventoryComponent.KEY.get(player);
        c.clear(); // or init
        for (String t : data.owned) {
            if (t != null && !t.isBlank()) c.addNameTag(t);
        }
        if (data.current != null && !data.current.isBlank() && data.owned.contains(data.current)) {
            c.setCurrentNameTag(data.current);
        }
        c.sync();
        LocalTitleStore.get().savePlayer(player.getUUID(), snapshot(c));
    } catch (Throwable t) {
        HabiLotteryMod.LOGGER.warn("title load failed for {}: {}", player.getGameProfile().getName(), t.toString());
    } finally {
        applyingLocal = false;
    }
}
```

**Order:** Call **after** world paths ready; ideally after skin/economy join. Wire in same place `PlayerLotteryStore.onPlayerJoin` is called.

- [ ] **Step 4: NameTagGenerateLiteralMixin**

Inject into `NameTagInventoryComponent.generate` or wrap the `Component.translatable(CurrentNameTag)` call: if CurrentNameTag contains `§` or starts with `[`, use `Component.literal` with section-sign formatting (Minecraft `Component.literal` does not auto-parse § — use `net.minecraft.network.chat.Component.nullToEmpty` / manual `Style` or Fabric `Text` util). Practical approach for 1.21:

```java
// Prefer: MutableComponent parsed = Component.Serializer.fromJson("\"" + escape + "\"", registry)
// Or simple: Component.literal(CurrentNameTag.replace('&','§')) if already using § chars — 
// In 1.21, § in literal may still show as color if passed through legacy formatter:
// net.minecraft.util.CommonColors / LegacyComponentSerializer if available via Adventure —
// Fallback accepted: Component.literal(CurrentNameTag) raw; document that operators use § and we mixin to:
cir.setReturnValue(... Component.literal(CurrentNameTag) ...);
```

If Adventure not on classpath, use:

```java
private static Component colored(String s) {
    return net.minecraft.network.chat.Component.literal(s.replace('&', '§'));
}
```

Actually vanilla `Font` / chat often still interprets § in literal strings when rendered. Implement and verify in-game later.

- [ ] **Step 5: Register mixins in JSON**

- [ ] **Step 6: compileJava**

```bat
cd /d C:\tmp\hlt
gradlew.bat compileJava
```

---

### Task 3: Network packets + server handlers

**Files:**
- Modify: `LotteryNetwork.java`, `LotteryClientNetwork.java`
- Possibly extend `ClientLotteryState` with `titleCatalogJson`, `titlePlayerOwned`, `titleVersion`

**Packets (minimal):**

1. `TitleCatalogSaveC2S(String json)` — OP, parse TitleCatalog, save, ack  
2. `TitlePlayerModifyC2S(String mode, String targetUuid, String payload)`  
   - modes: `grant`, `revoke`, `set_current`, `clear`  
3. `TitleSnapshotRequestC2S` — OP  
4. `TitleSnapshotS2C(String catalogJson, String playersJson)` — compact: map uuid→{owned,current} for known players from TitleStore list files + online  

**Handler rules:** same OP level as rates.opPermissionLevel / existing `isOp`.

Offline grant: only `LocalTitleStore` write. Online: TitleService API that updates CCA (persist mixin saves).

- [ ] **Step 1: Register codecs + handlers**  
- [ ] **Step 2: Client helpers** `clientSaveTitleCatalog`, `clientTitleModify`, `clientRequestTitleSnapshot`  
- [ ] **Step 3: compileJava**

---

### Task 4: Config UI「称号」Tab

**Files:**
- Modify: `LotteryConfigRootScreen.java` only (keep cohesive with other tabs)

**UI requirements:**

1. Add tab label `"称号"` to `TABS` array (before `JSON`). Update all `selectedTab` switch indices carefully (JSON was 7 → becomes 8; mail 6; insert titles at 7).  
   **Critical:** search every `selectedTab == N` and `case N` in the file and shift.

2. `buildTitlesTab`:
   - Left: catalog list (`ScrollableButtonList titleCatalogList`)
   - Fields: idBox, displayBox; +模板 -模板; 授予给选中玩家 button
   - Right: player list (filter optional) + owned list + 授予自定义/收回/设佩戴/清空
   - On open tab: `clientRequestTitleSnapshot()`
   - Instant C2S on player ops; catalog uses apply + 保存到服务器 path via `applyCurrentTab` / export if needed

3. State fields: selected catalog index, selected owned index, edit boxes, title snapshot cache from ClientLotteryState.

4. mouseScrolled: forward to new lists.

5. render: labels for 称号 tab.

- [ ] **Step 1: Fix tab indices project-wide in this screen**  
- [ ] **Step 2: buildTitlesTab + applyTitlesFields (catalog only)**  
- [ ] **Step 3: compileJava**

---

### Task 5: Backup flush + integration smoke

**Files:**
- Modify: `LotteryBackupService.createTimestampedBackup` to call `LocalTitleStore.get().flushAll()` after or with player flush
- Modify: `MetaFeaturePaths.ensureDirs` OR server start to `TitlePaths.ensureDirs`

- [ ] **Step 1: flush titles in backup**  
- [ ] **Step 2: ensure dirs on server start next to mail dirs  
- [ ] **Step 3: unit tests still pass; compileJava  

---

### Task 6: Full clean build + JAR copy

- [ ] **Step 1:**

```bat
cd /d C:\tmp\hlt
gradlew.bat clean build
```

Expected: BUILD SUCCESSFUL (tests green via junction).

- [ ] **Step 2:** Copy `build\libs\habitrain_lottery-*.jar` (exclude sources) → `D:\Backup\mc mod\临时\`

- [ ] **Step 3: Manual checklist for Mike**

1. Mod Menu → 称号：加模板 `owner` / `§6[服主]`，保存  
2. 授予在线玩家 → 打开原版称号/名片 UI 可见 → 佩戴 → 名牌前缀  
3. 收回 / 清空  
4. 离线授予后进服  
5. 新建备份含 `lottery_backup/<ts>/titles/`  
6. 重启后 catalog 与 owned 仍在  

---

## Spec coverage

| Spec item | Task |
|-----------|------|
| titles/catalog.json + players/uuid.json | 1 |
| Local grant/revoke/set/clear helpers | 1 |
| Join load → CCA | 2 |
| MySQL bypass | 2 |
| Persist all CCA writes | 2 |
| § display literal fix | 2 |
| OP network | 3 |
| Mod Menu 称号 tab template + player CRUD | 4 |
| Backup includes titles (flush) | 5 |
| Build + 临时 JAR | 6 |

## Self-review notes

- Tab index shift is high-risk — task 4 must grep all selectedTab cases.  
- Persist mixin + join apply needs `applyingLocal` guard.  
- Owned strings are **display** texts not catalog ids.  
- No TBD placeholders.

## Execution handoff

Plan saved to `docs/superpowers/plans/2026-07-18-title-nametag-local-bridge-plan.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task + review  
2. **Inline Execution** — this session with checkpoints  

Which approach?
