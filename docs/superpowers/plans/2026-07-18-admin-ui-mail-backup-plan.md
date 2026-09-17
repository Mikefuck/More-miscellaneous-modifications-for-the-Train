# Admin UI Scroll/Search · Server Backup · Mail Compose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the OP config player list searchable and scroll-safe, make the rates tab scrollable, replace client “download backup” with a world-local timestamped full snapshot under `lottery_backup/`, and fix mail compose for multi-select/offline bulk targets plus independent gold/draw/faction rewards.

**Architecture:** Incremental changes on existing Fabric 1.21 screens and packets. Add a pure-Java backup copier + world path helper; change `handleBackup` to flush + recursive copy and return a status path (no `BackupDataS2C` UI path). Add a small client `ScrollablePanel` for rates fields; reuse `ScrollableButtonList` for mail online players. Keep `MailComposeC2SPayload` shape; raise targets cap; client builds `rewards[]` from three fixed fields.

**Tech Stack:** Java 21, Fabric 1.21, JUnit 5 (existing unit tests), Gradle (`./gradlew` / `gradlew.bat`).

## Global Constraints

- Address user as **Mike**.
- File access only under `D:\Backup\mc mod\` — never touch `D:\Backup\mc mod\backup\`.
- After any mod code change: run `./gradlew clean build` (or `gradlew.bat clean build` on Windows) in `D:\Backup\mc mod\哈比列车抽奖补齐`, then copy the built JAR from `build/libs/` to `D:\Backup\mc mod\临时\`.
- Spec: `docs/superpowers/specs/2026-07-18-admin-ui-mail-backup-design.md`.
- This project directory currently has **no `.git`**. Skip `git commit` steps unless a repo appears; still mark tasks complete after build succeeds.
- Prefer Chinese UI strings matching existing hard-coded labels in screens.
- Do not reintroduce client-side backup download as the primary path.

## File map

| File | Responsibility |
|------|----------------|
| Create: `src/main/java/com/habitrain/lottery/storage/LotteryBackupService.java` | Flush + recursive copy `habitrain_lottery` → `lottery_backup/<ts>/` |
| Create: `src/test/java/com/habitrain/lottery/storage/LotteryBackupServiceTest.java` | Unit test copy into temp dirs |
| Create: `src/main/java/com/habitrain/lottery/client/gui/ScrollablePanel.java` | Vertical scroll offset helper for rates tab |
| Create: `src/test/java/com/habitrain/lottery/mail/MailTargetParseTest.java` | Parse multi offline names |
| Create: `src/main/java/com/habitrain/lottery/mail/MailTargetParse.java` | Shared name-split helper (client + optional server) |
| Modify: `src/main/java/com/habitrain/lottery/storage/WorldLotteryPaths.java` | `worldRoot()`, `backupRoot()` |
| Modify: `src/main/java/com/habitrain/lottery/network/LotteryNetwork.java` | `handleBackup` uses service; status only |
| Modify: `src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java` | Search, rates scroll, 新建备份, backup hint text, scroll paint |
| Modify: `src/main/java/com/habitrain/lottery/client/LotteryClientNetwork.java` | Keep request; ignore/no-op client backup file save if unused |
| Modify: `src/main/java/com/habitrain/lottery/client/gui/MailComposeScreen.java` | Online scroll list, select all/clear, bulk offline, fixed reward form |
| Modify: `src/main/java/com/habitrain/lottery/network/MailComposeC2SPayload.java` | Raise targets max 64 → 256 |

---

### Task 1: World paths + backup service (TDD)

**Files:**
- Create: `src/main/java/com/habitrain/lottery/storage/LotteryBackupService.java`
- Create: `src/test/java/com/habitrain/lottery/storage/LotteryBackupServiceTest.java`
- Modify: `src/main/java/com/habitrain/lottery/storage/WorldLotteryPaths.java`

**Interfaces:**
- Consumes: `WorldLotteryPaths.ready()`, `WorldLotteryPaths.root()`, `PlayerLotteryStore.flushAll()`
- Produces:
  - `WorldLotteryPaths.worldRoot(): Path` — parent of `habitrain_lottery` (server world root)
  - `WorldLotteryPaths.backupRoot(): Path` — `worldRoot().resolve("lottery_backup")`
  - `LotteryBackupService.Result` record `(boolean ok, String message, Path snapshotDir)`
  - `LotteryBackupService.createTimestampedBackup(): Result` — flush + copy when world ready
  - `LotteryBackupService.copyTree(Path source, Path dest): void` — pure copy for tests

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/com/habitrain/lottery/storage/LotteryBackupServiceTest.java`:

```java
package com.habitrain.lottery.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LotteryBackupServiceTest {
    @TempDir
    Path temp;

    @Test
    void copyTreeRecursesAndPreservesRelativePaths() throws Exception {
        Path src = temp.resolve("habitrain_lottery");
        Path nested = src.resolve("players");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("a.json"), "{\"lootChance\":3}", StandardCharsets.UTF_8);
        Files.createDirectories(src.resolve("mail/players"));
        Files.writeString(src.resolve("mail/players/b.json"), "[]", StandardCharsets.UTF_8);

        Path dest = temp.resolve("lottery_backup").resolve("20260718-120000");
        LotteryBackupService.copyTree(src, dest);

        assertTrue(Files.isRegularFile(dest.resolve("players/a.json")));
        assertEquals("{\"lootChance\":3}", Files.readString(dest.resolve("players/a.json")));
        assertTrue(Files.isRegularFile(dest.resolve("mail/players/b.json")));
    }

    @Test
    void copyTreeFailsWhenSourceMissing() {
        Path src = temp.resolve("missing");
        Path dest = temp.resolve("out");
        assertThrows(Exception.class, () -> LotteryBackupService.copyTree(src, dest));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (from project root):

```bat
gradlew.bat test --tests com.habitrain.lottery.storage.LotteryBackupServiceTest
```

Expected: FAIL — `LotteryBackupService` cannot be found / `copyTree` missing.

- [ ] **Step 3: Extend WorldLotteryPaths**

In `WorldLotteryPaths.java`, after `ready()`:

```java
/** World save root (parent of habitrain_lottery). Null if not ready. */
public static Path worldRoot() {
    return root == null ? null : root.getParent();
}

/** world/lottery_backup — sibling of habitrain_lottery. */
public static Path backupRoot() {
    Path wr = worldRoot();
    return wr == null ? null : wr.resolve("lottery_backup");
}
```

- [ ] **Step 4: Implement LotteryBackupService**

```java
package com.habitrain.lottery.storage;

import com.habitrain.lottery.HabiLotteryMod;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class LotteryBackupService {
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private LotteryBackupService() {
    }

    public record Result(boolean ok, String message, Path snapshotDir) {
        public static Result fail(String message) {
            return new Result(false, message, null);
        }

        public static Result ok(String message, Path dir) {
            return new Result(true, message, dir);
        }
    }

    /** Flush dirty player data, then copy world/habitrain_lottery → world/lottery_backup/<ts>/. */
    public static Result createTimestampedBackup() {
        if (!WorldLotteryPaths.ready()) {
            return Result.fail("备份失败: 世界未加载");
        }
        Path source = WorldLotteryPaths.root();
        if (source == null || !Files.isDirectory(source)) {
            return Result.fail("备份失败: habitrain_lottery 不存在");
        }
        Path backupRoot = WorldLotteryPaths.backupRoot();
        if (backupRoot == null) {
            return Result.fail("备份失败: 世界路径无效");
        }
        try {
            PlayerLotteryStore.get().flushAll();
            String ts = LocalDateTime.now().format(TS);
            Path dest = backupRoot.resolve(ts);
            // avoid collision if two clicks in same second
            int n = 0;
            while (Files.exists(dest)) {
                n++;
                dest = backupRoot.resolve(ts + "-" + n);
            }
            copyTree(source, dest);
            String rel = "lottery_backup/" + dest.getFileName();
            HabiLotteryMod.LOGGER.info("Created lottery backup at {}", dest);
            return Result.ok("备份已创建: " + rel, dest);
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("lottery backup failed", e);
            return Result.fail("备份失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    public static void copyTree(Path source, Path dest) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            throw new IOException("source missing: " + source);
        }
        Files.createDirectories(dest);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Path target = dest.resolve(rel.toString());
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                Path target = dest.resolve(rel.toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
```

- [ ] **Step 5: Run unit tests**

```bat
gradlew.bat test --tests com.habitrain.lottery.storage.LotteryBackupServiceTest
```

Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 6: Commit (skip if no git)**

```bat
git add src/main/java/com/habitrain/lottery/storage/WorldLotteryPaths.java src/main/java/com/habitrain/lottery/storage/LotteryBackupService.java src/test/java/com/habitrain/lottery/storage/LotteryBackupServiceTest.java
git commit -m "feat: world lottery_backup timestamped full-tree copy service"
```

---

### Task 2: Wire server handleBackup + UI「新建备份」

**Files:**
- Modify: `src/main/java/com/habitrain/lottery/network/LotteryNetwork.java` (`handleBackup` ~237–254)
- Modify: `src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java` (players tab button + status + render hint; remove client save path usage for this button)
- Modify: `src/main/java/com/habitrain/lottery/client/LotteryClientNetwork.java` only if needed for messages

**Interfaces:**
- Consumes: `LotteryBackupService.createTimestampedBackup()`
- Produces: OP click → C2S `BackupRequestC2S` → server Result → `AdminActionResultS2C` status text; no required `BackupDataS2C`

- [ ] **Step 1: Replace handleBackup body**

In `LotteryNetwork.java`:

```java
private static void handleBackup(ServerPlayer player) {
    if (!isOp(player)) {
        ServerPlayNetworking.send(player, new AdminActionResultS2C("需要 OP 才能备份", false));
        return;
    }
    LotteryBackupService.Result result = LotteryBackupService.createTimestampedBackup();
    ServerPlayNetworking.send(player, new AdminActionResultS2C(result.message(), result.ok()));
}
```

Remove the JSON size check / `BackupDataS2C` send from this path. Leave `BackupDataS2C` type registered so old clients do not crash if unused.

- [ ] **Step 2: Rename button and stop client file download for this flow**

In `buildPlayersTab`:

- Change button label `"下载备份"` → `"新建备份"`.
- Keep `LotteryClientNetwork.clientRequestBackup()` call.
- Status on click: `"已请求服务端新建备份…"`.

In `tick()`: **stop** treating `lastBackupJson` as the primary success path for this feature. Either:
- leave receiver inert (harmless), or
- clear `saveBackupToClient` call so OP no longer sees `config/habitrain_lottery/backups/...` for this button.

Preferred: remove/guard the block that calls `saveBackupToClient` when `lastBackupVersion` changes (or make `saveBackupToClient` a no-op with comment “legacy”). Status should come from `lastAdminMessage`.

In `render` players tab detail line, replace:

```text
备份保存到: config/habitrain_lottery/backups/
```

with:

```text
备份目录: <world>/lottery_backup/<时间戳>/
```

- [ ] **Step 3: Compile check**

```bat
gradlew.bat compileJava compileTestJava
```

Expected: SUCCESS.

- [ ] **Step 4: Commit (skip if no git)**

```bat
git add src/main/java/com/habitrain/lottery/network/LotteryNetwork.java src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java
git commit -m "feat: 新建备份 writes world/lottery_backup snapshot"
```

---

### Task 3: ScrollablePanel + rates tab scrolling

**Files:**
- Create: `src/main/java/com/habitrain/lottery/client/gui/ScrollablePanel.java`
- Modify: `src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java` (`buildRatesTab`, `render` rates labels, `mouseScrolled`)

**Interfaces:**
- Produces: `ScrollablePanel` with `setBounds`, `setContentHeight`, `getScroll`, `setScroll`, `maxScroll`, `mouseScrolled`, `applyY(layoutY)`, `isMouseOver`, `renderScrollbar`, `clamp`

- [ ] **Step 1: Implement ScrollablePanel**

```java
package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** Vertical scroll offset helper for a rectangular viewport of form fields. */
public class ScrollablePanel {
    private static final int SCROLLBAR_W = 3;

    private int x, y, width, viewportH;
    private int contentH;
    private int scroll;

    public void setBounds(int x, int y, int width, int viewportH) {
        this.x = x;
        this.y = y;
        this.width = Math.max(1, width);
        this.viewportH = Math.max(1, viewportH);
        clamp();
    }

    public void setContentHeight(int contentH) {
        this.contentH = Math.max(0, contentH);
        clamp();
    }

    public int getScroll() {
        return scroll;
    }

    public void setScroll(int scroll) {
        this.scroll = scroll;
        clamp();
    }

    public int maxScroll() {
        return Math.max(0, contentH - viewportH);
    }

    public void clamp() {
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    /** Map content-local Y to screen Y. */
    public int applyY(int contentY) {
        return y + contentY - scroll;
    }

    public boolean isMouseOver(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + viewportH;
    }

    public boolean mouseScrolled(double mx, double my, double verticalAmount) {
        if (!isMouseOver(mx, my) || maxScroll() <= 0) {
            return false;
        }
        int delta = verticalAmount > 0 ? -12 : (verticalAmount < 0 ? 12 : 0);
        if (delta == 0) {
            return true;
        }
        scroll = Mth.clamp(scroll + delta, 0, maxScroll());
        return true;
    }

    public void renderScrollbar(GuiGraphics g) {
        if (maxScroll() <= 0) {
            return;
        }
        int trackX = x + width - SCROLLBAR_W;
        g.fill(trackX, y, trackX + SCROLLBAR_W, y + viewportH, 0x30101820);
        int thumbH = Math.max(12, viewportH * viewportH / Math.max(1, contentH));
        int thumbY = y + (viewportH - thumbH) * scroll / maxScroll();
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xA057C6D6);
    }
}
```

- [ ] **Step 2: Wire rates tab**

Add field:

```java
private final ScrollablePanel ratesPanel = new ScrollablePanel();
```

Rewrite `buildRatesTab` to:

1. Define field rows as data: label + current value string (8 rows: 抽次消耗倍率, 重复转币倍率, 金币换抽价格, 连登奖励上限, blackout, murder, repair, OP 权限等级).
2. `int rowH = 36; int contentH = rows * rowH;`
3. `ratesPanel.setBounds(PAD, y, width - PAD * 2, h); ratesPanel.setContentHeight(contentH);`
4. For each row `i`, `int cy = i * rowH; int sy = ratesPanel.applyY(cy);`
5. Create `EditBox` at `(PAD, sy + 12, min(240,w), 20)` only if `sy` intersects viewport (`sy + 32 > y && sy < y + h`); still keep references for all boxes — **problem:** widgets off-screen still need values.

**Simpler approach matching existing code style:** always create all 8 EditBoxes at scrolled Y; set `box.visible = sy + 20 > y && sy < y + h`; store boxes as now (`drawCostBox` etc.). On scroll change in `mouseScrolled`, call `rebuildTabContent()` after `applyRatesFields()` so values persist (same pattern as pool list).

`mouseScrolled` when `selectedTab == 1`:

```java
int before = ratesPanel.getScroll();
if (ratesPanel.mouseScrolled(mouseX, mouseY, verticalAmount)) {
    if (ratesPanel.getScroll() != before) {
        applyRatesFields();
        rebuildTabContent();
    }
    return true;
}
```

`render` rates labels: use same 8 labels and `ratesPanel.applyY`, only draw if inside viewport; call `ratesPanel.renderScrollbar(g)` when `selectedTab == 1`.

Fix the existing bug where render only listed 6 labels while build has 8 fields (missing 金币换抽价格 / 连登奖励上限).

- [ ] **Step 3: Compile**

```bat
gradlew.bat compileJava
```

Expected: SUCCESS.

- [ ] **Step 4: Commit (skip if no git)**

```bat
git add src/main/java/com/habitrain/lottery/client/gui/ScrollablePanel.java src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java
git commit -m "feat: scrollable rates config tab"
```

---

### Task 4: Player list search box

**Files:**
- Modify: `src/main/java/com/habitrain/lottery/client/gui/LotteryConfigRootScreen.java`

**Interfaces:**
- Produces: `playerSearchBox` filters displayed rows by name contains (case-insensitive); bulk ops unchanged

- [ ] **Step 1: Add state**

```java
private EditBox playerSearchBox;
private String playerSearchQuery = "";
```

- [ ] **Step 2: In buildPlayersTab**

After top buttons, add search field (adjust x so it fits; e.g. under the toolbar or right of 全员 buttons):

```java
playerSearchBox = addTab(new EditBox(font, PAD, y + 24, Math.min(160, width / 3), 18, Component.literal("搜索")));
playerSearchBox.setMaxLength(64);
playerSearchBox.setValue(playerSearchQuery);
playerSearchBox.setHint(Component.literal("搜索玩家名"));
playerSearchBox.setResponder(s -> {
    playerSearchQuery = s == null ? "" : s;
    // rebuild list only — avoid wiping responder loops: set flag or rebuild carefully
});
```

**Avoid infinite rebuild:** do not call `rebuildTabContent()` from responder on every key if that recreates the box and steals focus. Prefer:

- Keep `playerSearchQuery` updated in responder **without** full rebuild; filter list labels from query each `buildPlayersTab`.
- On responder: update query, recompute filtered labels, `playerList.setItems(...)`, `playerList.rebuildWidgets` only if you extract list rebuild — **simplest robust approach:** on responder save query + selected uuid, `rebuildTabContent()`, then restore focus to search box:

```java
playerSearchBox.setResponder(s -> {
    String next = s == null ? "" : s;
    if (next.equals(playerSearchQuery)) return;
    playerSearchQuery = next;
    rebuildTabContent();
    if (playerSearchBox != null) {
        playerSearchBox.setFocused(true);
        setFocused(playerSearchBox);
        playerSearchBox.setCursorPosition(playerSearchQuery.length());
        // if API is setCursorToEnd / moveCursorToEnd use that
    }
});
```

- [ ] **Step 3: Filter list**

```java
String q = playerSearchQuery == null ? "" : playerSearchQuery.toLowerCase(Locale.ROOT).trim();
List<PlayerAdminModels.PlayerRow> filtered = new ArrayList<>();
for (PlayerAdminModels.PlayerRow row : players) {
    if (q.isEmpty() || (row.name != null && row.name.toLowerCase(Locale.ROOT).contains(q))) {
        filtered.add(row);
    }
}
```

Build labels from `filtered`. Map selection: keep `selectedPlayerIndex` as index into **filtered** list for UI controls; when applying 此人± use `filtered.get(selectedPlayerIndex).uuid`.

If filter empty: show placeholder button `无匹配玩家`.

Detail `render` must use the same filtered list / selected row (extract helper `currentPlayerRows()` used by build + render).

- [ ] **Step 4: clearTabWidgets** null out `playerSearchBox` if needed; do not clear `playerSearchQuery` on rebuild.

- [ ] **Step 5: Compile**

```bat
gradlew.bat compileJava
```

- [ ] **Step 6: Commit (skip if no git)**

```bat
git commit -am "feat: player admin list name search filter"
```

---

### Task 5: Mail target parse helper (TDD) + payload cap

**Files:**
- Create: `src/main/java/com/habitrain/lottery/mail/MailTargetParse.java`
- Create: `src/test/java/com/habitrain/lottery/mail/MailTargetParseTest.java`
- Modify: `src/main/java/com/habitrain/lottery/network/MailComposeC2SPayload.java` (targets max 64 → 256)

**Interfaces:**
- Produces: `MailTargetParse.splitNames(String raw): List<String>` — split on commas/semicolons/newlines/whitespace; trim; drop empty; de-dupe case-sensitive preserve order; max 256

- [ ] **Step 1: Failing test**

```java
package com.habitrain.lottery.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MailTargetParseTest {
    @Test
    void splitsCommaNewlineAndDedupes() {
        List<String> names = MailTargetParse.splitNames("Alice,Bob\nAlice; Carol  Dave");
        assertEquals(List.of("Alice", "Bob", "Carol", "Dave"), names);
    }

    @Test
    void emptyInput() {
        assertEquals(List.of(), MailTargetParse.splitNames("  \n, ;"));
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

```bat
gradlew.bat test --tests com.habitrain.lottery.mail.MailTargetParseTest
```

- [ ] **Step 3: Implement**

```java
package com.habitrain.lottery.mail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MailTargetParse {
    public static final int MAX_TARGETS = 256;

    private MailTargetParse() {
    }

    public static List<String> splitNames(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[,;\\s\\n\\r]+");
        Set<String> seen = new LinkedHashSet<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (!t.isEmpty()) {
                seen.add(t);
            }
            if (seen.size() >= MAX_TARGETS) {
                break;
            }
        }
        return new ArrayList<>(seen);
    }
}
```

- [ ] **Step 4: In MailComposeC2SPayload.read**

Change:

```java
int n = Math.min(64, Math.max(0, buf.readVarInt()));
```

to:

```java
int n = Math.min(MailTargetParse.MAX_TARGETS, Math.max(0, buf.readVarInt()));
```

(import `MailTargetParse`).

- [ ] **Step 5: Tests pass**

```bat
gradlew.bat test --tests com.habitrain.lottery.mail.MailTargetParseTest --tests com.habitrain.lottery.mail.MailCommandsCodecTest
```

- [ ] **Step 6: Commit (skip if no git)**

---

### Task 6: MailComposeScreen — online scroll, bulk offline, fixed rewards

**Files:**
- Modify: `src/main/java/com/habitrain/lottery/client/gui/MailComposeScreen.java`

**Interfaces:**
- Consumes: `ScrollableButtonList`, `MailTargetParse`, `MultilineTextArea` (offline names)
- Produces: UX per spec §3

- [ ] **Step 1: Replace reward UI with fixed three fields**

Remove cycling `rewardKind` / `addReward` list as the only path. Add:

```java
private EditBox goldBox;
private EditBox drawsBox;
private EditBox cardAmountBox;
// keep factionIdx + FACTIONS cycle button
```

Defaults: all amounts `"0"`.

On `send()`:

```java
List<MailComposeC2SPayload.RewardEntry> built = new ArrayList<>();
int draws = parseIntSafe(drawsBox.getValue(), 0);
int gold = parseIntSafe(goldBox.getValue(), 0);
int cards = parseIntSafe(cardAmountBox.getValue(), 0);
if (draws != 0) built.add(new RewardEntry(DRAWS, draws, ""));
if (gold != 0) built.add(new RewardEntry(COINS, gold, ""));
if (cards != 0) built.add(new RewardEntry(FACTION_CARD, cards, FACTIONS[factionIdx]));
// use built instead of rewards list
```

Optional: keep a live preview in `render` from the three boxes (no separate add list required).

- [ ] **Step 2: Offline bulk names**

When `MODE_OFFLINE_NAME`, use `MultilineTextArea offlineArea` (height ~48–60) instead of single-line only, or keep EditBox but parse with `MailTargetParse.splitNames`. Prefer `MultilineTextArea` for multi-line paste.

```java
List<String> names = MailTargetParse.splitNames(offlineArea.getValue());
if (names.isEmpty()) { status = "请输入至少一个玩家名"; return; }
if (names.size() >= MailTargetParse.MAX_TARGETS) {
    status = "最多 " + MailTargetParse.MAX_TARGETS + " 个目标";
}
targets.addAll(names);
```

- [ ] **Step 3: Online list with ScrollableButtonList**

Add:

```java
private final ScrollableButtonList onlineList = new ScrollableButtonList();
```

In `init` / rebuild when mode is ONLINE_LIST:

- Collect online names from `minecraft.getConnection().getOnlinePlayers()`.
- Labels: `(selectedOnline.contains(name) ? "§a[✓] " : "§7[ ] ") + name`
- On select: toggle name in `selectedOnline`, then rebuild list widgets (preserve scroll).
- Buttons **全选** / **清空** above list.
- Bounds on right panel: `onlineList.setBounds(rx, 116, 180, height - 200)` (tune).
- `mouseScrolled` forward to `onlineList`; on scroll change rebuild widgets.
- Remove hard `y > height - 80` break in render/click for player names; list handles windowing.
- `renderScrollbar` for online list.

- [ ] **Step 4: MODE_ALL_ONLINE unchanged** (no targets needed).

- [ ] **Step 5: Compile**

```bat
gradlew.bat compileJava
```

- [ ] **Step 6: Commit (skip if no git)**

```bat
git commit -am "feat: mail compose multi-target scroll and fixed reward form"
```

---

### Task 7: Full build + copy JAR (mandatory)

**Files:** none new — verify artifacts

- [ ] **Step 1: Full test + clean build**

```bat
cd /d "D:\Backup\mc mod\哈比列车抽奖补齐"
gradlew.bat clean build
```

Expected: `BUILD SUCCESSFUL`. JAR under `build\libs\` (exclude `-sources` if present; use main mod jar).

- [ ] **Step 2: Copy JAR to 临时**

```bat
copy /Y "build\libs\habitrain_lottery-*.jar" "D:\Backup\mc mod\临时\"
```

If multiple jars, copy the non-`-sources` / non-`-dev` artifact matching `gradle.properties` version. Use PowerShell if needed:

```powershell
Get-ChildItem "build\libs\*.jar" | Where-Object { $_.Name -notmatch 'sources|dev|javadoc' } | ForEach-Object {
  Copy-Item $_.FullName -Destination "D:\Backup\mc mod\临时\" -Force
  Write-Host "Copied $($_.Name)"
}
```

- [ ] **Step 3: Manual checklist for Mike (in-game)**

1. 倍率页：缩小窗口，滚轮可改最底 OP 等级并应用。  
2. 玩家页：搜索过滤；列表多人可滚。  
3. 新建备份：出现 `world/lottery_backup/<ts>/` 且含原 `habitrain_lottery` 子树；连点两个时间戳。  
4. 邮件：在线全选/滚动；离线 `A,B\nC`；金币+抽数+阵营同时非 0 领取正确。  

---

## Spec coverage check

| Spec requirement | Task |
|------------------|------|
| Player name search | Task 4 |
| Rates vertical scroll + scrollbar | Task 3 |
| Player list scrollbar (already exists; keep scroll wire) | Task 4 verify / existing mouseScrolled |
| Mail online scrollbar | Task 6 |
| 新建备份 full tree → lottery_backup/ts | Tasks 1–2 |
| Timestamp snapshots no overwrite | Task 1 collision suffix |
| Flush before copy | Task 1 `flushAll` |
| No client download primary path | Task 2 |
| Online multi-select + 全选/清空 | Task 6 |
| Bulk offline any name | Tasks 5–6 |
| Fixed gold/draws/faction form | Task 6 |
| targets cap 256 | Task 5 |
| Build + copy to 临时 | Task 7 |

## Placeholder / consistency self-review

- No TBD steps.
- `MailTargetParse.MAX_TARGETS` used in codec and UI.
- Backup path sibling of `habitrain_lottery` via `root.getParent()`.
- Rates label count 8 matches fields.

## Execution handoff

Plan saved to `docs/superpowers/plans/2026-07-18-admin-ui-mail-backup-plan.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks  
2. **Inline Execution** — this session with executing-plans and checkpoints  

Which approach?
