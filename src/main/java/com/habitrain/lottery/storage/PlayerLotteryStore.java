package com.habitrain.lottery.storage;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.bridge.EconomyMirror;
import com.habitrain.lottery.bridge.InventorySkinApplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Authoritative per-player lottery/skin economy stored under the world root.
 */
public final class PlayerLotteryStore {
    private static final PlayerLotteryStore INSTANCE = new PlayerLotteryStore();
    private static final Gson GSON = new Gson();
    private static final ThreadLocal<Integer> DEFERRED_FLUSH = ThreadLocal.withInitial(() -> 0);

    private final Map<UUID, PlayerLotteryData> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> dirty = new ConcurrentHashMap<>();
    private final Set<UUID> loadFailed = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingSrePush = ConcurrentHashMap.newKeySet();

    /** When true, economy mixins write through to this store instead of only SRE. */
    private volatile boolean takeoverActive;

    private PlayerLotteryStore() {
    }

    public static PlayerLotteryStore get() {
        return INSTANCE;
    }

    public boolean isTakeoverActive() {
        return takeoverActive && WorldLotteryPaths.ready();
    }

    public boolean isLoadFailed(UUID uuid) {
        return uuid != null && loadFailed.contains(uuid);
    }

    public void onServerStarted(MinecraftServer server) {
        takeoverActive = true;
        HabiLotteryMod.LOGGER.info("Player lottery store takeover active");
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (player == null || !isTakeoverActive()) {
            return;
        }
        UUID uuid = player.getUUID();
        loadFailed.remove(uuid);
        boolean keptDirty = cache.containsKey(uuid) && isDirty(uuid);
        PlayerLotteryData data = adoptJoin(uuid);
        if (data == null) {
            HabiLotteryMod.LOGGER.error(
                    "Refusing lottery takeover for {} — player JSON primary and .bak are unreadable; will not cache or flush zeros",
                    uuid);
            return;
        }
        data.migratedFromSre = true;
        boolean stillDirty = isDirty(uuid);

        boolean skipEconomyPush = !keptDirty
                && !stillDirty
                && data.lootChance == 0
                && data.coinNum == 0
                && playerFileExists(uuid)
                && EconomyMirror.sreChanceOrCoinsNonZero(player);
        if (skipEconomyPush) {
            EconomyMirror.copyChanceCoinsFromSre(player, data);
            HabiLotteryMod.LOGGER.error(
                    "All-zero lottery JSON for {} but SRE/CCA chance/coins are non-zero; skip economy overwrite and keep live values",
                    uuid);
        }

        boolean pushed = EconomyMirror.pushToSre(player, data, !skipEconomyPush);
        if (!pushed) {
            pendingSrePush.add(uuid);
            markDirty(uuid);
            HabiLotteryMod.LOGGER.error(
                    "pushToSre failed for {} — JOIN is not a commit; leaving dirty for retry and not flushing as success",
                    uuid);
            MinecraftServer joinServer = player.getServer();
            if (joinServer != null) {
                joinServer.execute(() -> retryPendingSrePush(uuid, joinServer));
            }
            return;
        }
        pendingSrePush.remove(uuid);
        InventorySkinApplier.applyAllEquipped(player, data.equipped);
        // Do not clobber an unflushed dirty entry with a spurious success flush.
        if (!stillDirty && (!playerFileExists(uuid) || skipEconomyPush)) {
            markDirty(uuid);
            flush(uuid);
        }
        HabiLotteryMod.LOGGER.info(
                "Loaded world lottery for {} (coins={}, chance={}, unlockTypes={}) from {}",
                player.getGameProfile().getName(),
                data.coinNum,
                data.lootChance,
                data.unlocked == null ? 0 : data.unlocked.size(),
                WorldLotteryPaths.playerFile(uuid));
    }

    /**
     * JOIN load policy: if a dirty cache entry exists, retry flush; if still dirty keep memory
     * and skip disk overwrite. Otherwise load from disk into cache.
     *
     * @return data that should remain cached, or {@code null} when primary+.bak are corrupt
     */
    PlayerLotteryData adoptJoin(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        if (cache.containsKey(uuid) && isDirty(uuid)) {
            flush(uuid);
            if (isDirty(uuid)) {
                PlayerLotteryData kept = cache.get(uuid);
                HabiLotteryMod.LOGGER.error(
                        "JOIN keeping dirty lottery cache for {}; skipping disk overwrite",
                        uuid);
                return kept;
            }
            PlayerLotteryData cached = cache.get(uuid);
            if (cached != null) {
                return cached;
            }
        }
        DiskLoad load = readPlayer(uuid);
        if (load.isCorrupt()) {
            loadFailed.add(uuid);
            cache.remove(uuid);
            dirty.remove(uuid);
            return null;
        }
        cache.put(uuid, load.data);
        return load.data;
    }

    /**
     * Restore a prior cache snapshot after a failed flush so grant/login keys can retry.
     */
    public void restoreSnapshot(UUID uuid, PlayerLotteryData snapshot, boolean dirtyFlag) {
        if (uuid == null || snapshot == null) {
            return;
        }
        cache.put(uuid, snapshot);
        if (dirtyFlag) {
            dirty.put(uuid, true);
        } else {
            dirty.remove(uuid);
        }
    }

    private static boolean playerFileExists(UUID uuid) {
        Path file = WorldLotteryPaths.playerFile(uuid);
        return file != null && java.nio.file.Files.isRegularFile(file);
    }

    private void retryPendingSrePush(UUID uuid, MinecraftServer server) {
        if (uuid == null || server == null || !pendingSrePush.contains(uuid) || isLoadFailed(uuid)) {
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        PlayerLotteryData data = cache.get(uuid);
        if (online == null || data == null) {
            return;
        }
        boolean pushed = EconomyMirror.pushToSre(online, data, true);
        if (!pushed) {
            HabiLotteryMod.LOGGER.error("pushToSre retry still failed for {}", uuid);
            return;
        }
        pendingSrePush.remove(uuid);
        InventorySkinApplier.applyAllEquipped(online, data.equipped);
        flush(uuid);
    }

    public void onPlayerQuit(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        if (loadFailed.contains(uuid)) {
            loadFailed.remove(uuid);
            cache.remove(uuid);
            dirty.remove(uuid);
            pendingSrePush.remove(uuid);
            return;
        }
        boolean ok = flush(uuid);
        if (!ok && isDirty(uuid)) {
            HabiLotteryMod.LOGGER.error(
                    "Quit flush failed for {}; keeping dirty cache for SERVER_STOPPING retry",
                    uuid);
            return;
        }
        cache.remove(uuid);
        dirty.remove(uuid);
        pendingSrePush.remove(uuid);
    }

    public PlayerLotteryData getOrLoad(UUID uuid) {
        if (isLoadFailed(uuid)) {
            HabiLotteryMod.LOGGER.error("Lottery data load-failed for {}, refusing cached zeros", uuid);
            return normalize(null);
        }
        PlayerLotteryData cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        DiskLoad load = readPlayer(uuid);
        if (load.isCorrupt()) {
            loadFailed.add(uuid);
            HabiLotteryMod.LOGGER.error(
                    "Refusing to cache a zero lottery account for unreadable player file {}",
                    uuid);
            return normalize(null);
        }
        cache.put(uuid, load.data);
        return load.data;
    }

    public PlayerLotteryData getOrLoad(ServerPlayer player) {
        return getOrLoad(player.getUUID());
    }

    public void update(UUID uuid, Consumer<PlayerLotteryData> mutator) {
        if (isLoadFailed(uuid)) {
            HabiLotteryMod.LOGGER.error("Refusing lottery mutation for load-failed {}", uuid);
            return;
        }
        PlayerLotteryData data = getOrLoad(uuid);
        if (isLoadFailed(uuid)) {
            return;
        }
        mutator.accept(data);
        data.updatedAt = System.currentTimeMillis();
        markDirty(uuid);
    }

    public void update(ServerPlayer player, Consumer<PlayerLotteryData> mutator) {
        if (player == null) {
            return;
        }
        update(player.getUUID(), mutator);
    }

    public int getLootChance(UUID uuid) {
        return getOrLoad(uuid).lootChance;
    }

    public void addLootChance(UUID uuid, int delta) {
        update(uuid, d -> d.lootChance = Math.max(0, d.lootChance + delta));
    }

    public int getCoinNum(UUID uuid) {
        return getOrLoad(uuid).coinNum;
    }

    public void addCoinNum(UUID uuid, int delta) {
        update(uuid, d -> d.coinNum = Math.max(0, d.coinNum + delta));
    }

    public boolean isSkinUnlocked(UUID uuid, String type, String skin) {
        PlayerLotteryData d = getOrLoad(uuid);
        if (skin == null) {
            return false;
        }
        if ("default".equals(skin)) {
            return true;
        }
        for (String key : SkinTypeKeys.writeKeys(type)) {
            Map<String, Boolean> map = d.unlocked.get(key);
            if (map != null && Boolean.TRUE.equals(map.get(skin))) {
                return true;
            }
            if (map != null && Boolean.TRUE.equals(map.get(skin.toLowerCase(java.util.Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    public void unlockSkin(UUID uuid, String type, String skin) {
        if (skin == null || skin.isBlank()) {
            return;
        }
        String skinId = skin.toLowerCase(java.util.Locale.ROOT);
        update(uuid, d -> {
            for (String key : SkinTypeKeys.writeKeys(type)) {
                d.unlocked.computeIfAbsent(key, k -> new HashMap<>()).put(skinId, true);
            }
            String c = SkinTypeKeys.canonical(type);
            d.unlocked.computeIfAbsent(c, k -> new HashMap<>()).put(skinId, true);
        });
    }

    public void lockSkin(UUID uuid, String type, String skin) {
        if (skin == null) {
            return;
        }
        String skinId = skin.toLowerCase(java.util.Locale.ROOT);
        update(uuid, d -> {
            for (String key : SkinTypeKeys.writeKeys(type)) {
                Map<String, Boolean> map = d.unlocked.get(key);
                if (map != null) {
                    map.remove(skinId);
                    map.remove(skin);
                }
            }
        });
    }

    /** Persist administrator skin access changes before updating any live mirrors. */
    public boolean commitSkinAccess(UUID uuid, String type, String skin, boolean unlocked) {
        String canonical = SkinTypeKeys.canonical(type);
        String skinId = normalizeEquippedSkin(skin);
        if (uuid == null || "default".equals(canonical) || "default".equals(skinId)
                || !WorldLotteryPaths.ready() || isFlushDeferred() || isLoadFailed(uuid)) {
            return false;
        }
        PlayerLotteryData current = getOrLoad(uuid);
        if (isLoadFailed(uuid)) {
            return false;
        }
        PlayerLotteryData snapshot = current.copy();
        boolean wasDirty = isDirty(uuid);
        if (unlocked) {
            unlockSkin(uuid, canonical, skinId);
        } else {
            update(uuid, data -> {
                // Include historical namespaced keys as well as gun/revolver aliases.
                data.unlocked.forEach((key, skins) -> {
                    if (canonical.equals(SkinTypeKeys.canonical(key)) && skins != null) {
                        skins.keySet().removeIf(id -> skinId.equals(normalizeEquippedSkin(id)));
                    }
                });
                data.equipped.entrySet().removeIf(entry ->
                        canonical.equals(SkinTypeKeys.canonical(entry.getKey()))
                                && skinId.equals(normalizeEquippedSkin(entry.getValue())));
            });
        }
        if (!flush(uuid)) {
            restoreSnapshot(uuid, snapshot, wasDirty);
            return false;
        }
        return true;
    }

    public String getEquipped(UUID uuid, String type) {
        PlayerLotteryData d = getOrLoad(uuid);
        for (String key : SkinTypeKeys.writeKeys(type)) {
            String eq = d.equipped.get(key);
            if (eq != null && !eq.isBlank()) {
                return eq;
            }
        }
        return null;
    }

    public void setEquipped(UUID uuid, String type, String skin) {
        update(uuid, d -> {
            for (String key : SkinTypeKeys.writeKeys(type)) {
                if (skin == null || skin.isBlank() || "default".equals(skin)) {
                    d.equipped.remove(key);
                } else {
                    d.equipped.put(key, skin);
                }
            }
            String c = SkinTypeKeys.canonical(type);
            if (skin == null || skin.isBlank() || "default".equals(skin)) {
                d.equipped.remove(c);
            } else {
                d.equipped.put(c, skin);
            }
        });
    }

    /**
     * Persists an equipped-skin change as one transaction. A successful return
     * means the world JSON has been written; failed/deferred writes restore the
     * complete previous player snapshot so callers cannot display a false
     * commit that will disappear at the next lifecycle boundary.
     */
    public EquippedCommitResult commitEquipped(UUID uuid, String type, String skin) {
        String canonical = SkinTypeKeys.canonical(type);
        String wanted = normalizeEquippedSkin(skin);
        if (uuid == null) {
            return EquippedCommitResult.failed(canonical, wanted, "missing_uuid", null);
        }
        if (isLoadFailed(uuid)) {
            return EquippedCommitResult.failed(canonical, wanted, "load_failed", getEquipped(uuid, canonical));
        }
        if (!WorldLotteryPaths.ready()) {
            return EquippedCommitResult.failed(canonical, wanted, "world_paths_not_ready", getEquipped(uuid, canonical));
        }
        // flush() intentionally reports deferred writes as successful for batch
        // lottery transactions. An equip acknowledgement must be stronger: it
        // may only succeed after durable JSON storage.
        if (isFlushDeferred()) {
            return EquippedCommitResult.failed(canonical, wanted, "flush_deferred", getEquipped(uuid, canonical));
        }

        PlayerLotteryData current = getOrLoad(uuid);
        if (isLoadFailed(uuid)) {
            return EquippedCommitResult.failed(canonical, wanted, "load_failed", null);
        }
        PlayerLotteryData snapshot = current.copy();
        boolean wasDirty = isDirty(uuid);
        String previous = getEquipped(uuid, canonical);

        setEquipped(uuid, canonical, wanted);
        if (!flush(uuid)) {
            restoreSnapshot(uuid, snapshot, wasDirty);
            return EquippedCommitResult.failed(canonical, wanted, "write_failed", previous);
        }
        return EquippedCommitResult.committed(canonical, wanted, previous);
    }

    public static String normalizeEquippedSkin(String skin) {
        if (skin == null || skin.isBlank() || "default".equalsIgnoreCase(skin.trim())) {
            return "default";
        }
        return skin.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public record EquippedCommitResult(
            boolean committed,
            String type,
            String skin,
            String previous,
            String failure) {
        static EquippedCommitResult committed(String type, String skin, String previous) {
            return new EquippedCommitResult(true, type, skin, previous, "");
        }

        static EquippedCommitResult failed(String type, String skin, String failure, String previous) {
            return new EquippedCommitResult(false, type, skin, previous, failure);
        }
    }

    public boolean tryConsumeGrantKey(UUID uuid, String key) {
        if (isLoadFailed(uuid)) {
            return false;
        }
        if (key != null && key.startsWith("login:")) {
            return true;
        }
        PlayerLotteryData d = getOrLoad(uuid);
        if (isLoadFailed(uuid) || !GrantKeys.tryConsume(d, key)) {
            return false;
        }
        d.updatedAt = System.currentTimeMillis();
        markDirty(uuid);
        return true;
    }

    public void beginDeferredFlush() {
        DEFERRED_FLUSH.set(DEFERRED_FLUSH.get() + 1);
    }

    public void endDeferredFlush() {
        int depth = DEFERRED_FLUSH.get() - 1;
        if (depth <= 0) {
            DEFERRED_FLUSH.remove();
        } else {
            DEFERRED_FLUSH.set(depth);
        }
    }

    public boolean isFlushDeferred() {
        return DEFERRED_FLUSH.get() > 0;
    }

    /**
     * Writes the player file only when dirty. Returns {@code true} when there is
     * nothing to write, flush is deferred, or the atomic write succeeded.
     * On failure the dirty flag is kept.
     */
    public boolean flush(UUID uuid) {
        if (uuid == null) {
            return true;
        }
        try {
            if (isLoadFailed(uuid)) {
                return true;
            }
            if (isFlushDeferred()) {
                return true;
            }
            if (!Boolean.TRUE.equals(dirty.get(uuid))) {
                return true;
            }
            PlayerLotteryData data = cache.get(uuid);
            if (data == null) {
                return true;
            }
            if (!WorldLotteryPaths.ready()) {
                return false;
            }
            Path file = WorldLotteryPaths.playerFile(uuid);
            if (file == null) {
                return false;
            }
            boolean ok = AtomicJsonFiles.writeJson(file, data, GSON, true, true);
            if (ok) {
                dirty.remove(uuid);
            } else {
                HabiLotteryMod.LOGGER.error("Failed saving player lottery data {}", uuid);
            }
            return ok;
        } finally {
            if (!isFlushDeferred()) {
                LotteryHistoryStore.get().flushPending(uuid);
            }
        }
    }

    /** Flushes only dirty cache entries. Returns false if any dirty write fails. */
    public boolean flushAll() {
        boolean ok = true;
        for (UUID uuid : new ArrayList<>(cache.keySet())) {
            if (!Boolean.TRUE.equals(dirty.get(uuid))) {
                continue;
            }
            if (!flush(uuid)) {
                ok = false;
            }
        }
        LotteryHistoryStore.get().flushAllPending();
        return ok;
    }

    /**
     * Parent should call this from {@code SERVER_STOPPING} instead of (or after)
     * {@link #flushAll()}. Does not reset {@link WorldLotteryPaths}.
     */
    public void onServerStopping() {
        boolean ok = flushAll();
        if (!ok) {
            HabiLotteryMod.LOGGER.error("flushAll reported failures during server stopping");
        }
        reset();
    }

    public void reset() {
        cache.clear();
        dirty.clear();
        loadFailed.clear();
        pendingSrePush.clear();
        takeoverActive = false;
        DEFERRED_FLUSH.remove();
        LotteryHistoryStore.get().resetForTest();
    }

    public void markDirty(UUID uuid) {
        if (uuid == null || isLoadFailed(uuid)) {
            return;
        }
        dirty.put(uuid, true);
    }

    public boolean isDirty(UUID uuid) {
        return Boolean.TRUE.equals(dirty.get(uuid));
    }

    public void setLootChance(UUID uuid, int value) {
        update(uuid, d -> d.lootChance = Math.max(0, value));
    }

    public void setCoinNum(UUID uuid, int value) {
        update(uuid, d -> d.coinNum = Math.max(0, value));
    }

    /**
     * 批量写在线玩家抽数（审核 B-20）。
     *
     * <p>旧实现逐人 {@code setLootChance + flush} 并丢弃 flush 返回值，
     * 最后把<b>循环次数</b>当成成功人数返回——磁盘满 / 世界只读时会向管理员
     * 虚报「已为在线 N 人调整」而实际一个都没落盘。
     *
     * <p>现在逐人快照 + 检查 flush，失败即回滚该玩家并<b>不计入</b>返回计数，
     * 因此返回值是真实的成功人数。
     *
     * @return 真正写入成功的玩家数
     */
    public int setLootChanceToOnline(MinecraftServer server, int value) {
        if (server == null) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (applyToPlayerWithRollback(sp.getUUID(), d -> d.lootChance = Math.max(0, value))) {
                EconomyMirror.syncChanceAndCoins(sp, getOrLoad(sp.getUUID()));
                count++;
            }
        }
        return count;
    }

    /** 批量加在线玩家抽数（审核 B-20，语义同 {@link #setLootChanceToOnline}）。 */
    public int addLootChanceToOnline(MinecraftServer server, int delta) {
        if (server == null) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (applyToPlayerWithRollback(sp.getUUID(), d -> d.lootChance = Math.max(0, d.lootChance + delta))) {
                EconomyMirror.syncChanceAndCoins(sp, getOrLoad(sp.getUUID()));
                count++;
            }
        }
        return count;
    }

    /** 批量加在线玩家金币（审核 B-20，语义同 {@link #setLootChanceToOnline}）。 */
    public int addCoinsToOnline(MinecraftServer server, int delta) {
        if (server == null) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (applyToPlayerWithRollback(sp.getUUID(), d -> d.coinNum = Math.max(0, d.coinNum + delta))) {
                EconomyMirror.syncChanceAndCoins(sp, getOrLoad(sp.getUUID()));
                count++;
            }
        }
        return count;
    }

    /** 批量清零在线玩家金币（审核 B-20，语义同 {@link #setLootChanceToOnline}）。 */
    public int clearCoinsForOnline(MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (applyToPlayerWithRollback(sp.getUUID(), d -> d.coinNum = 0)) {
                EconomyMirror.syncChanceAndCoins(sp, getOrLoad(sp.getUUID()));
                count++;
            }
        }
        return count;
    }

    /** 设置在线玩家金币（审核 B-20）。 */
    public int setCoinsToOnline(MinecraftServer server, int value) {
        if (server == null) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (applyToPlayerWithRollback(sp.getUUID(), d -> d.coinNum = Math.max(0, value))) {
                EconomyMirror.syncChanceAndCoins(sp, getOrLoad(sp.getUUID()));
                count++;
            }
        }
        return count;
    }

    /**
     * 审核 B-20：单玩家「改动 + 落盘 + 失败回滚」的公共实现。
     *
     * @return {@code true} 表示改动已成功持久化；{@code false} 表示已回滚、调用方不得计为成功
     */
    public boolean applyToPlayerWithRollback(UUID uuid, java.util.function.Consumer<PlayerLotteryData> mutator) {
        if (uuid == null || mutator == null || isLoadFailed(uuid)) {
            return false;
        }
        PlayerLotteryData snapshot = getOrLoad(uuid).copy();
        boolean wasDirty = isDirty(uuid);
        update(uuid, mutator);
        if (flush(uuid)) {
            return true;
        }
        restoreSnapshot(uuid, snapshot, wasDirty);
        HabiLotteryMod.LOGGER.error("bulk write failed for {}; rolled back (audit B-20)", uuid);
        return false;
    }

    public java.util.List<com.habitrain.lottery.network.PlayerAdminModels.PlayerRow> listPlayers(MinecraftServer server) {
        java.util.Map<UUID, com.habitrain.lottery.network.PlayerAdminModels.PlayerRow> rows = new java.util.LinkedHashMap<>();

        if (server != null) {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (isLoadFailed(sp.getUUID())) {
                    continue;
                }
                PlayerLotteryData d = getOrLoad(sp.getUUID());
                com.habitrain.lottery.network.PlayerAdminModels.PlayerRow r = new com.habitrain.lottery.network.PlayerAdminModels.PlayerRow(
                        sp.getGameProfile().getName(),
                        sp.getUUID(),
                        d.lootChance,
                        d.coinNum,
                        countUnlocked(d),
                        true
                );
                rows.put(sp.getUUID(), r);
            }
        }

        if (WorldLotteryPaths.ready() && WorldLotteryPaths.playersDir() != null
                && java.nio.file.Files.isDirectory(WorldLotteryPaths.playersDir())) {
            try (var s = java.nio.file.Files.list(WorldLotteryPaths.playersDir())) {
                s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                    String fn = p.getFileName().toString();
                    String uidStr = fn.substring(0, fn.length() - 5);
                    try {
                        UUID uuid = UUID.fromString(uidStr);
                        if (!rows.containsKey(uuid) && !isLoadFailed(uuid)) {
                            DiskLoad load = readPlayerFile(p);
                            if (load.ok() && load.data != null) {
                                com.habitrain.lottery.network.PlayerAdminModels.PlayerRow r =
                                        new com.habitrain.lottery.network.PlayerAdminModels.PlayerRow(
                                                shortUuid(uuid),
                                                uuid,
                                                load.data.lootChance,
                                                load.data.coinNum,
                                                countUnlocked(load.data),
                                                false
                                        );
                                rows.put(uuid, r);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.warn("listPlayers scan failed: {}", e.toString());
            }
        }

        return new ArrayList<>(rows.values());
    }

    private static int countUnlocked(PlayerLotteryData d) {
        if (d == null || d.unlocked == null) {
            return 0;
        }
        int sum = 0;
        for (Map<String, Boolean> m : d.unlocked.values()) {
            if (m != null) {
                for (Boolean b : m.values()) {
                    if (Boolean.TRUE.equals(b)) {
                        sum++;
                    }
                }
            }
        }
        return sum;
    }

    private static String shortUuid(UUID id) {
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private DiskLoad readPlayer(UUID uuid) {
        if (!WorldLotteryPaths.ready()) {
            return DiskLoad.missingNew();
        }
        Path file = WorldLotteryPaths.playerFile(uuid);
        if (file == null) {
            return DiskLoad.missingNew();
        }
        return readPlayerFile(file);
    }

    static DiskLoad loadFromDiskForTest(Path file) {
        return readPlayerFile(file);
    }

    private static DiskLoad readPlayerFile(Path file) {
        AtomicJsonFiles.JsonLoad<PlayerLotteryData> load =
                AtomicJsonFiles.readJson(file, PlayerLotteryData.class, GSON);
        if (load.corrupt()) {
            return DiskLoad.corrupt();
        }
        if (load.isMissing()) {
            return DiskLoad.missingNew();
        }
        PlayerLotteryData data = normalize(load.value());
        return load.usedBackup() ? DiskLoad.okBackup(data) : DiskLoad.ok(data);
    }

    private static PlayerLotteryData normalize(PlayerLotteryData data) {
        if (data == null) {
            data = new PlayerLotteryData();
            data.updatedAt = System.currentTimeMillis();
            return data;
        }
        if (data.systemItems == null) data.systemItems = new java.util.HashMap<>();
        data.systemItems.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() <= 0);
        if (data.unlocked == null) {
            data.unlocked = new HashMap<>();
        }
        if (data.equipped == null) {
            data.equipped = new HashMap<>();
        }
        GrantKeys.normalize(data);
        return data;
    }

    static final class DiskLoad {
        enum Kind {
            OK,
            OK_BACKUP,
            MISSING,
            CORRUPT
        }

        final Kind kind;
        final PlayerLotteryData data;

        private DiskLoad(Kind kind, PlayerLotteryData data) {
            this.kind = kind;
            this.data = data;
        }

        static DiskLoad ok(PlayerLotteryData data) {
            return new DiskLoad(Kind.OK, data);
        }

        static DiskLoad okBackup(PlayerLotteryData data) {
            return new DiskLoad(Kind.OK_BACKUP, data);
        }

        static DiskLoad missingNew() {
            PlayerLotteryData fresh = new PlayerLotteryData();
            fresh.updatedAt = System.currentTimeMillis();
            return new DiskLoad(Kind.MISSING, fresh);
        }

        static DiskLoad corrupt() {
            return new DiskLoad(Kind.CORRUPT, null);
        }

        boolean ok() {
            return kind == Kind.OK || kind == Kind.OK_BACKUP;
        }

        boolean isMissing() {
            return kind == Kind.MISSING;
        }

        boolean isCorrupt() {
            return kind == Kind.CORRUPT;
        }

        boolean flushable() {
            return kind != Kind.CORRUPT;
        }
    }
}
