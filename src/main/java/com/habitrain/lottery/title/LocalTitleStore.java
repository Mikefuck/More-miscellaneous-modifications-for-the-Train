package com.habitrain.lottery.title;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.AtomicJsonFiles.JsonLoad;
import com.habitrain.lottery.storage.WorldLotteryPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Authoritative title catalog + per-player owned/current under world titles/.
 */
public final class LocalTitleStore {
    private static final LocalTitleStore INSTANCE = new LocalTitleStore();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TitleCatalog catalogCache;
    private boolean catalogDirty;
    private final Map<UUID, PlayerTitleData> playerCache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> playerDirty = new ConcurrentHashMap<>();
    private final Map<UUID, JsonLoad.Status> playerLoadStatus = new ConcurrentHashMap<>();

    private LocalTitleStore() {
    }

    public static LocalTitleStore get() {
        return INSTANCE;
    }

    public static final class PlayerLoad {
        private final PlayerTitleData data;
        private final JsonLoad.Status status;

        private PlayerLoad(PlayerTitleData data, JsonLoad.Status status) {
            this.data = data;
            this.status = status;
        }

        public PlayerTitleData data() {
            return data;
        }

        public JsonLoad.Status status() {
            return status;
        }

        public boolean ok() {
            return status == JsonLoad.Status.OK || status == JsonLoad.Status.OK_BACKUP;
        }

        public boolean usedBackup() {
            return status == JsonLoad.Status.OK_BACKUP;
        }

        public boolean missing() {
            return status == JsonLoad.Status.MISSING;
        }

        public boolean corrupt() {
            return status == JsonLoad.Status.CORRUPT;
        }
    }

    public TitleCatalog loadCatalog() {
        if (!WorldLotteryPaths.ready()) return emptyCatalog();
        if (catalogCache == null) catalogCache = readCatalogFromDisk();
        return GSON.fromJson(GSON.toJson(catalogCache), TitleCatalog.class);
    }

    public void saveCatalog(TitleCatalog catalog) {
        saveCatalogChecked(catalog);
    }

    public boolean saveCatalogChecked(TitleCatalog catalog) {
        if (catalog == null) {
            catalog = emptyCatalog();
        }
        if (catalog.titles == null) {
            catalog.titles = new ArrayList<>();
        }
        if (!writeCatalogToDisk(catalog)) return false;
        catalogCache = GSON.fromJson(GSON.toJson(catalog), TitleCatalog.class);
        catalogDirty = false;
        return true;
    }

    public PlayerTitleData loadPlayer(UUID uuid) {
        PlayerLoad load = loadPlayerDetailed(uuid);
        return load.data() != null ? load.data() : emptyPlayer();
    }

    public PlayerLoad loadPlayerDetailed(UUID uuid) {
        // Pre-world defaults are temporary, including their MISSING status.
        if (uuid == null || !WorldLotteryPaths.ready()) {
            return new PlayerLoad(emptyPlayer(), JsonLoad.Status.MISSING);
        }
        PlayerTitleData cached = playerCache.get(uuid);
        if (cached != null) {
            JsonLoad.Status status = playerLoadStatus.getOrDefault(uuid, JsonLoad.Status.OK);
            return new PlayerLoad(cached.copy(), status);
        }
        if (playerLoadStatus.get(uuid) == JsonLoad.Status.CORRUPT) {
            return new PlayerLoad(null, JsonLoad.Status.CORRUPT);
        }
        JsonLoad<PlayerTitleData> load = readPlayerFile(TitlePaths.playerFile(uuid));
        if (load.corrupt()) {
            HabiLotteryMod.LOGGER.error(
                    "Corrupt player title file {}, not treating as empty owned", uuid);
            playerLoadStatus.put(uuid, JsonLoad.Status.CORRUPT);
            return new PlayerLoad(null, JsonLoad.Status.CORRUPT);
        }
        PlayerTitleData data = load.ok() ? normalizePlayer(load.value()) : emptyPlayer();
        JsonLoad.Status status = load.usedBackup()
                ? JsonLoad.Status.OK_BACKUP
                : (load.ok() ? JsonLoad.Status.OK : JsonLoad.Status.MISSING);
        playerCache.put(uuid, data);
        playerLoadStatus.put(uuid, status);
        return new PlayerLoad(data.copy(), status);
    }

    public boolean savePlayer(UUID uuid, PlayerTitleData data) {
        if (uuid == null || !WorldLotteryPaths.ready()) {
            return false;
        }
        PlayerLoad existing = loadPlayerDetailed(uuid);
        if (existing.corrupt()) {
            HabiLotteryMod.LOGGER.error("Refusing to overwrite corrupt title file {}", uuid);
            return false;
        }
        if (data == null) {
            data = emptyPlayer();
        }
        data = normalizePlayer(data.copy());
        if (existing.data() != null && existing.data().rewardReceipts != null)
            data.rewardReceipts.addAll(existing.data().rewardReceipts);
        data.updatedAt = System.currentTimeMillis();
        if (writePlayerToDisk(uuid, data)) {
            playerCache.put(uuid, data);
            playerDirty.remove(uuid);
            playerLoadStatus.put(uuid, JsonLoad.Status.OK);
            return true;
        }
        return false;
    }

    public boolean flushAll() {
        boolean ok = true;
        if (catalogDirty && catalogCache != null) {
            if (writeCatalogToDisk(catalogCache)) {
                catalogDirty = false;
            } else {
                ok = false;
            }
        }
        for (UUID uuid : new HashMap<>(playerCache).keySet()) {
            if (!Boolean.TRUE.equals(playerDirty.get(uuid))) {
                continue;
            }
            PlayerTitleData data = playerCache.get(uuid);
            if (data == null) {
                playerDirty.remove(uuid);
                continue;
            }
            if (writePlayerToDisk(uuid, data)) {
                playerDirty.remove(uuid);
                playerLoadStatus.put(uuid, JsonLoad.Status.OK);
            } else {
                ok = false;
            }
        }
        return ok;
    }

    public void reset() {
        if (WorldLotteryPaths.ready()) {
            flushAll();
        }
        catalogCache = null;
        catalogDirty = false;
        playerCache.clear();
        playerDirty.clear();
        playerLoadStatus.clear();
    }

    public boolean grant(UUID uuid, String display) {
        PlayerLoad load = loadPlayerDetailed(uuid);
        if (load.corrupt() || load.data() == null) {
            return false;
        }
        boolean ok = grantInMemory(load.data(), display);
        if (ok) {
            return savePlayer(uuid, load.data());
        }
        return false;
    }

    public boolean revoke(UUID uuid, String display) {
        PlayerLoad load = loadPlayerDetailed(uuid);
        if (load.corrupt() || load.data() == null) {
            return false;
        }
        boolean ok = revokeInMemory(load.data(), display);
        if (ok) {
            return savePlayer(uuid, load.data());
        }
        return false;
    }

    public boolean setCurrent(UUID uuid, String display) {
        PlayerLoad load = loadPlayerDetailed(uuid);
        if (load.corrupt() || load.data() == null) {
            return false;
        }
        boolean ok = setCurrentInMemory(load.data(), display);
        if (ok) {
            return savePlayer(uuid, load.data());
        }
        return false;
    }

    public void clear(UUID uuid) {
        clearChecked(uuid);
    }

    public boolean clearChecked(UUID uuid) {
        PlayerLoad load = loadPlayerDetailed(uuid);
        if (load.corrupt() || load.data() == null) {
            return false;
        }
        clearInMemory(load.data());
        return savePlayer(uuid, load.data());
    }

    /** Receipt and title ownership share one atomic file replacement. */
    public boolean grantOnce(UUID uuid, String receipt, List<String> titles) {
        PlayerLoad load = loadPlayerDetailed(uuid);
        if (load.corrupt() || load.data() == null || receipt == null) return false;
        PlayerTitleData next = load.data();
        if (next.rewardReceipts.contains(receipt)) return true;
        for (String title : titles) {
            if (title == null || title.isBlank()) return false;
            grantInMemory(next, title);
        }
        next.rewardReceipts.add(receipt);
        return savePlayer(uuid, next);
    }

    /**
     * UUIDs with a player title file on disk (and any currently cached).
     */
    public List<UUID> listKnownPlayerIds() {
        Set<UUID> ids = new HashSet<>(playerCache.keySet());
        if (WorldLotteryPaths.ready()) {
            Path dir = TitlePaths.playersDir();
            if (Files.isDirectory(dir)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    for (Path p : stream.collect(Collectors.toList())) {
                        String name = p.getFileName().toString();
                        if (!name.endsWith(".json")) {
                            continue;
                        }
                        try {
                            ids.add(UUID.fromString(name.substring(0, name.length() - 5)));
                        } catch (IllegalArgumentException ignored) {
                            // skip non-uuid files
                        }
                    }
                } catch (IOException e) {
                    HabiLotteryMod.LOGGER.warn("Failed listing title player files: {}", e.toString());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /** Package-visible helper: grant display if not already owned. */
    static boolean grantInMemory(PlayerTitleData data, String display) {
        if (data == null || display == null || display.isBlank()) {
            return false;
        }
        if (data.owned == null) {
            data.owned = new ArrayList<>();
        }
        if (data.owned.contains(display)) {
            return false;
        }
        data.owned.add(display);
        data.updatedAt = System.currentTimeMillis();
        return true;
    }

    /** Package-visible helper: remove display; clear current if it matched. */
    static boolean revokeInMemory(PlayerTitleData data, String display) {
        if (data == null || display == null) {
            return false;
        }
        if (data.owned == null) {
            data.owned = new ArrayList<>();
            return false;
        }
        boolean removed = data.owned.remove(display);
        if (removed) {
            if (display.equals(data.current)) {
                data.current = "";
            }
            data.updatedAt = System.currentTimeMillis();
        }
        return removed;
    }

    /** Package-visible helper: set current only if owned. */
    static boolean setCurrentInMemory(PlayerTitleData data, String display) {
        if (data == null) {
            return false;
        }
        if (data.owned == null) {
            data.owned = new ArrayList<>();
        }
        if (display == null || display.isBlank()) {
            data.current = "";
            data.updatedAt = System.currentTimeMillis();
            return true;
        }
        if (!data.owned.contains(display)) {
            return false;
        }
        data.current = display;
        data.updatedAt = System.currentTimeMillis();
        return true;
    }

    /** Package-visible helper: empty owned + current. */
    static void clearInMemory(PlayerTitleData data) {
        if (data == null) {
            return;
        }
        if (data.owned == null) {
            data.owned = new ArrayList<>();
        } else {
            data.owned.clear();
        }
        data.current = "";
        data.updatedAt = System.currentTimeMillis();
    }

    static JsonLoad<PlayerTitleData> readPlayerFile(Path file) {
        JsonLoad<PlayerTitleData> load = AtomicJsonFiles.readJson(file, PlayerTitleData.class, GSON);
        if (load.ok() && load.value() != null) {
            normalizePlayer(load.value());
        }
        return load;
    }

    private TitleCatalog readCatalogFromDisk() {
        if (!WorldLotteryPaths.ready()) {
            return emptyCatalog();
        }
        JsonLoad<TitleCatalog> load = AtomicJsonFiles.readJson(TitlePaths.catalogFile(), TitleCatalog.class, GSON);
        if (load.corrupt()) {
            HabiLotteryMod.LOGGER.error("Corrupt title catalog, not treating as empty authority");
            return emptyCatalog();
        }
        if (!load.ok()) {
            return emptyCatalog();
        }
        TitleCatalog catalog = load.value();
        if (catalog.titles == null) {
            catalog.titles = new ArrayList<>();
        }
        return catalog;
    }

    private boolean writeCatalogToDisk(TitleCatalog catalog) {
        if (!WorldLotteryPaths.ready()) {
            return false;
        }
        try {
            TitlePaths.ensureDirs();
        } catch (IOException e) {
            HabiLotteryMod.LOGGER.error("Failed creating title dirs", e);
            return false;
        }
        boolean saved = AtomicJsonFiles.writeJson(TitlePaths.catalogFile(), catalog, GSON, false);
        if (!saved) {
            HabiLotteryMod.LOGGER.error("Failed saving title catalog");
        }
        return saved;
    }

    private boolean writePlayerToDisk(UUID uuid, PlayerTitleData data) {
        if (!WorldLotteryPaths.ready()) {
            return false;
        }
        try {
            TitlePaths.ensureDirs();
        } catch (IOException e) {
            HabiLotteryMod.LOGGER.error("Failed creating title dirs", e);
            return false;
        }
        boolean saved = AtomicJsonFiles.writeJson(TitlePaths.playerFile(uuid), data, GSON, true,
                playerLoadStatus.get(uuid) != JsonLoad.Status.OK_BACKUP);
        if (!saved) {
            HabiLotteryMod.LOGGER.error("Failed saving player titles {}", uuid);
        }
        return saved;
    }

    private static TitleCatalog emptyCatalog() {
        TitleCatalog c = new TitleCatalog();
        c.version = 1;
        c.titles = new ArrayList<>();
        return c;
    }

    private static PlayerTitleData emptyPlayer() {
        PlayerTitleData d = new PlayerTitleData();
        d.version = 1;
        d.owned = new ArrayList<>();
        d.current = "";
        d.updatedAt = System.currentTimeMillis();
        return d;
    }

    private static PlayerTitleData normalizePlayer(PlayerTitleData data) {
        if (data == null) {
            return emptyPlayer();
        }
        if (data.owned == null) {
            data.owned = new ArrayList<>();
        }
        if (data.current == null) {
            data.current = "";
        }
        if (data.rewardReceipts == null) data.rewardReceipts = new HashSet<>();
        return data;
    }
}
