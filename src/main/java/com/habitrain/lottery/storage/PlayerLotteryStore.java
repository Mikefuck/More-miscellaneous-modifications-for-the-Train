package com.habitrain.lottery.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.bridge.InventorySkinApplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Authoritative per-player green apples and skin state stored under the world root.
 */
public final class PlayerLotteryStore {
    private static final PlayerLotteryStore INSTANCE = new PlayerLotteryStore();
    private static final Gson GSON = new Gson();
    private static final ThreadLocal<Integer> DEFERRED_FLUSH = ThreadLocal.withInitial(() -> 0);

    private final Map<UUID, PlayerLotteryData> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> dirty = new ConcurrentHashMap<>();
    private final Set<UUID> loadFailed = ConcurrentHashMap.newKeySet();
    /** Players loaded from .bak must repair the primary without copying a corrupt primary back over it. */
    private final Set<UUID> recoveredFromBackup = ConcurrentHashMap.newKeySet();
    /** True after this world's player store has started. */
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
        if (!scrubAllLegacyBalances()) {
            takeoverActive = false;
            throw new IllegalStateException("Unable to retire all legacy player balances");
        }
        takeoverActive = true;
        HabiLotteryMod.LOGGER.info("Player green-apple store active");
    }

    /** Retire the two old balances in offline accounts without changing other saved assets. */
    private boolean scrubAllLegacyBalances() {
        Path players = WorldLotteryPaths.playersDir();
        if (players == null || !Files.isDirectory(players)) {
            return true;
        }
        boolean[] ok = {true};
        try (var files = Files.list(players)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .matches("[0-9a-fA-F-]{36}\\.json"))
                    .forEach(path -> {
                        try {
                            var loaded = AtomicJsonFiles.readJson(path, JsonObject.class, GSON);
                            if (!loaded.ok() || loaded.value() == null) {
                                HabiLotteryMod.LOGGER.error("Cannot retire unreadable player account {}", path);
                                ok[0] = false;
                            } else if (!scrubLegacyJson(path, loaded.value())) {
                                ok[0] = false;
                            }
                        } catch (RuntimeException e) {
                            ok[0] = false;
                            HabiLotteryMod.LOGGER.error("Failed retiring legacy balances in {}", path, e);
                        }
                        Path backup = AtomicJsonFiles.bakPath(path);
                        if (Files.exists(backup)) {
                            if (!Files.isRegularFile(backup)) {
                                HabiLotteryMod.LOGGER.error("Player account backup is not a regular file {}", backup);
                                ok[0] = false;
                            } else if (!scrubLegacyFileSafely(backup)) {
                                ok[0] = false;
                            }
                        }
                    });
            // A world may have only a recovery copy after an interrupted write. Scrub it
            // too, without treating it as a new authoritative primary file.
            try (var backups = Files.list(players)) {
                backups.filter(path -> path.getFileName().toString()
                                .matches("[0-9a-fA-F-]{36}\\.json\\.bak"))
                        .forEach(path -> {
                            if (!Files.isRegularFile(path)) {
                                HabiLotteryMod.LOGGER.error("Player account backup is not a regular file {}", path);
                                ok[0] = false;
                            } else if (!scrubLegacyFileSafely(path)) {
                                ok[0] = false;
                            }
                        });
            }
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("Unable to scan player accounts for legacy balances", e);
            return false;
        }
        return ok[0];
    }

    /** Remove retired balance fields from both the primary file and its recovery copy. */
    private static boolean scrubLegacyJson(Path path, JsonObject json) {
        if (path == null || json == null) {
            return false;
        }
        boolean changed = json.remove("lootChance") != null;
        changed |= json.remove("coinNum") != null;
        changed |= json.remove("migratedFromSre") != null;
        changed |= json.remove("recentSettledMatches") != null;
        changed |= scrubGreenApples(json);
        int version = legacyVersion(json);
        if (version < 2) {
            json.addProperty("version", 2);
            changed = true;
        }
        if (!changed) {
            return true;
        }
        boolean written = AtomicJsonFiles.writeJson(path, json, GSON, true, false);
        if (!written) {
            HabiLotteryMod.LOGGER.error("Failed retiring legacy balances in {}", path);
        }
        return written;
    }

    /** Canonicalize the new currency during the one-time legacy scrub. */
    private static boolean scrubGreenApples(JsonObject json) {
        if (json == null || !json.has("greenApples")) {
            return false;
        }
        JsonElement element = json.get("greenApples");
        int normalized;
        try {
            long raw = element == null || element.isJsonNull() ? 0L : element.getAsLong();
            normalized = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, raw));
        } catch (RuntimeException malformed) {
            normalized = 0;
        }
        boolean canonicalNumber = element instanceof JsonPrimitive primitive && primitive.isNumber();
        if (canonicalNumber) {
            try {
                if (Integer.toString(normalized).equals(element.getAsString())) {
                    return false;
                }
            } catch (RuntimeException ignored) {
                // Fall through and replace the malformed primitive.
            }
        }
        json.addProperty("greenApples", normalized);
        return true;
    }

    private static boolean scrubLegacyFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return true;
        }
        JsonObject json = readRawJson(path);
        if (json == null) {
            HabiLotteryMod.LOGGER.error("Cannot parse legacy player account {}", path);
            return false;
        }
        return scrubLegacyJson(path, json);
    }

    private static boolean scrubLegacyFileSafely(Path path) {
        try {
            return scrubLegacyFile(path);
        } catch (RuntimeException e) {
            HabiLotteryMod.LOGGER.error("Failed retiring legacy player account {}", path, e);
            return false;
        }
    }

    private static int legacyVersion(JsonObject json) {
        if (json == null || !json.has("version") || !json.get("version").isJsonPrimitive()) {
            return 1;
        }
        try {
            return json.get("version").getAsInt();
        } catch (RuntimeException ignored) {
            // Treat a malformed version as legacy so it is replaced with the current schema.
            return 1;
        }
    }

    private static JsonObject readRawJson(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        var loaded = AtomicJsonFiles.readJson(path, JsonObject.class, GSON);
        return loaded.ok() ? loaded.value() : null;
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (player == null || !isTakeoverActive()) {
            return;
        }
        UUID uuid = player.getUUID();
        loadFailed.remove(uuid);
        PlayerLotteryData data = adoptJoin(uuid);
        if (data == null) {
            HabiLotteryMod.LOGGER.error(
                    "Refusing player data load for {} — primary and .bak are unreadable; will not cache or flush zeros",
                    uuid);
            return;
        }
        boolean stillDirty = isDirty(uuid);
        boolean recovered = recoveredFromBackup.contains(uuid);
        InventorySkinApplier.applyAllEquipped(player, data.equipped);
        // Repair a backup recovery immediately. The first repair write skips copying a
        // corrupt primary back over the valid .bak; later writes use normal backups again.
        if (recovered) {
            if (!flush(uuid)) {
                HabiLotteryMod.LOGGER.error("Failed repairing recovered player account {}", uuid);
            }
        // Do not clobber an unflushed dirty entry with a spurious success flush.
        } else if (!stillDirty && !playerFileExists(uuid)) {
            markDirty(uuid);
            flush(uuid);
        }
        HabiLotteryMod.LOGGER.info(
                "Loaded world player assets for {} (greenApples={}, unlockTypes={}) from {}",
                player.getGameProfile().getName(),
                data.greenApples,
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
            recoveredFromBackup.remove(uuid);
            return null;
        }
        cache.put(uuid, load.data);
        if (load.usedBackup()) {
            recoveredFromBackup.add(uuid);
            dirty.put(uuid, true);
        } else {
            recoveredFromBackup.remove(uuid);
        }
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

    public void onPlayerQuit(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        if (loadFailed.contains(uuid)) {
            loadFailed.remove(uuid);
            cache.remove(uuid);
            dirty.remove(uuid);
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
            recoveredFromBackup.remove(uuid);
            HabiLotteryMod.LOGGER.error(
                    "Refusing to cache a zero lottery account for unreadable player file {}",
                    uuid);
            return normalize(null);
        }
        cache.put(uuid, load.data);
        if (load.usedBackup()) {
            recoveredFromBackup.add(uuid);
            dirty.put(uuid, true);
        } else {
            recoveredFromBackup.remove(uuid);
        }
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

    public int getGreenApples(UUID uuid) {
        return getOrLoad(uuid).greenApples;
    }

    public void addGreenApples(UUID uuid, int delta) {
        update(uuid, d -> d.greenApples = (int) Math.max(0L,
                Math.min(Integer.MAX_VALUE, (long) d.greenApples + delta)));
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
        if (isLoadFailed(uuid) || isFlushDeferred()
                || !Boolean.TRUE.equals(dirty.get(uuid))) {
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
        // A recovered .bak is the only trusted copy when the primary was corrupt. Do not
        // copy that corrupt primary back over the valid backup during the repair write.
        boolean repairBackup = recoveredFromBackup.contains(uuid);
        boolean ok = AtomicJsonFiles.writeJson(file, data, GSON, true, !repairBackup);
        if (ok) {
            dirty.remove(uuid);
            recoveredFromBackup.remove(uuid);
        } else {
            HabiLotteryMod.LOGGER.error("Failed saving player lottery data {}", uuid);
        }
        return ok;
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
        recoveredFromBackup.clear();
        takeoverActive = false;
        DEFERRED_FLUSH.remove();
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

    public void setGreenApples(UUID uuid, int value) {
        update(uuid, d -> d.greenApples = Math.max(0, value));
    }

    /** Number of online players whose green-apple change was durably written. */
    public int setGreenApplesToOnline(MinecraftServer server, int value) {
        if (server == null) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (applyToPlayerWithRollback(sp.getUUID(), d -> d.greenApples = Math.max(0, value))) {
                count++;
            }
        }
        return count;
    }

    /** Number of online players whose green-apple change was durably written. */
    public int addGreenApplesToOnline(MinecraftServer server, int delta) {
        if (server == null) return 0;
        int count = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (applyToPlayerWithRollback(sp.getUUID(), d -> d.greenApples = (int) Math.max(0L,
                    Math.min(Integer.MAX_VALUE, (long) d.greenApples + delta)))) count++;
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
                        d.greenApples,
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
                                                load.data.greenApples,
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
        data.version = 2;
        data.greenApples = Math.max(0, data.greenApples);
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

        boolean usedBackup() {
            return kind == Kind.OK_BACKUP;
        }

        boolean flushable() {
            return kind != Kind.CORRUPT;
        }
    }
}
