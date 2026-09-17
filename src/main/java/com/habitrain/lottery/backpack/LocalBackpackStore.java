package com.habitrain.lottery.backpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.AtomicJsonFiles.JsonLoad;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardMutationResult;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardOperation;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardStoreStatus;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class LocalBackpackStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LocalBackpackStore() {
    }

    public static final class FileRoot {
        public int version = 1;
        public Map<String, Integer> cards = new HashMap<>();
        public int selfSelectCards = 0;
        public int limitBreakCards = 0;
        /** A protection write happened before the first JOIN card overlay. Merge SRE balances add-only. */
        public boolean factionCardsPendingJoin;
    }

    public static final class LoadResult {
        public enum Status {
            OK,
            FROM_BACKUP,
            MISSING,
            CORRUPT
        }

        private final Status status;
        private final Map<String, Integer> cards;
        private final int selfSelectCards;
        private final int limitBreakCards;
        private final boolean factionCardsPendingJoin;
        private final Path quarantined;

        private LoadResult(Status status, Map<String, Integer> cards, int selfSelectCards,
                           int limitBreakCards, boolean factionCardsPendingJoin, Path quarantined) {
            this.status = status;
            this.cards = cards;
            this.selfSelectCards = Math.max(0, selfSelectCards);
            this.limitBreakCards = Math.max(0, limitBreakCards);
            this.factionCardsPendingJoin = factionCardsPendingJoin;
            this.quarantined = quarantined;
        }

        public static LoadResult ok(Map<String, Integer> cards) {
            return new LoadResult(Status.OK, cards, 0, 0, false, null);
        }

        public static LoadResult fromBackup(Map<String, Integer> cards) {
            return new LoadResult(Status.FROM_BACKUP, cards, 0, 0, false, null);
        }

        public static LoadResult missing() {
            return new LoadResult(Status.MISSING, null, 0, 0, false, null);
        }

        public static LoadResult corrupt(Path quarantined) {
            return new LoadResult(Status.CORRUPT, null, 0, 0, false, quarantined);
        }

        public Status status() {
            return status;
        }

        public Map<String, Integer> cards() {
            return cards;
        }

        public int selfSelectCards() { return selfSelectCards; }
        public int limitBreakCards() { return limitBreakCards; }
        public boolean factionCardsPendingJoin() { return factionCardsPendingJoin; }

        public Path quarantined() {
            return quarantined;
        }

        public boolean ok() {
            return status == Status.OK || status == Status.FROM_BACKUP;
        }

        public boolean usedBackup() {
            return status == Status.FROM_BACKUP;
        }

        public boolean isMissing() {
            return status == Status.MISSING;
        }

        public boolean corrupt() {
            return status == Status.CORRUPT;
        }

        public boolean seedFromSre() {
            return status == Status.MISSING;
        }

        public boolean applyNegativeDeltas() {
            return status == Status.OK || status == Status.FROM_BACKUP;
        }

        public boolean writeBack() {
            return status != Status.CORRUPT;
        }
    }

    public static LoadResult loadResult(UUID uuid) {
        if (!WorldLotteryPaths.ready() || uuid == null) {
            return LoadResult.missing();
        }
        return loadFrom(MetaFeaturePaths.backpackPlayer(uuid));
    }

    /**
     * Legacy Map view for callers that have not switched to {@link #loadResult}.
     * Missing/corrupt are not reported here — JOIN overlay must use {@link #loadResult}.
     */
    public static Map<String, Integer> load(UUID uuid) {
        LoadResult result = loadResult(uuid);
        if (result.ok() && result.cards() != null) {
            return result.cards();
        }
        return defaultCards();
    }

    public static LoadResult loadFromPath(Path path) {
        return loadFrom(path);
    }

    public static LoadResult parseRoot(FileRoot root) {
        if (root == null || root.cards == null) {
            return LoadResult.corrupt(null);
        }
        return new LoadResult(LoadResult.Status.OK, normalize(root), root.selfSelectCards,
                root.limitBreakCards, root.factionCardsPendingJoin, null);
    }

    public static LoadResult loadFrom(Path path) {
        if (path == null) {
            return LoadResult.missing();
        }
        JsonLoad<FileRoot> load = AtomicJsonFiles.readJson(path, FileRoot.class, GSON);
        if (load.corrupt()) {
            HabiLotteryMod.LOGGER.error("Corrupt backpack JSON {}, not treating as all-zero data", path);
            return LoadResult.corrupt(load.quarantined());
        }
        if (load.isMissing()) {
            return LoadResult.missing();
        }
        if (load.value() == null || load.value().cards == null) return LoadResult.corrupt(null);
        Map<String, Integer> cards = normalize(load.value());
        return new LoadResult(load.usedBackup() ? LoadResult.Status.FROM_BACKUP : LoadResult.Status.OK, cards,
                Math.max(0, load.value().selfSelectCards), Math.max(0, load.value().limitBreakCards),
                load.value().factionCardsPendingJoin, null);
    }

    public static boolean save(UUID uuid, Map<String, Integer> cards) {
        if (!WorldLotteryPaths.ready() || uuid == null) {
            return false;
        }
        FileRoot root = new FileRoot();
        root.cards = cards == null ? defaultCards() : new HashMap<>(cards);
        LoadResult existing = loadResult(uuid);
        if (existing.corrupt()) return false;
        root.selfSelectCards = existing.ok() ? existing.selfSelectCards() : 0;
        root.limitBreakCards = existing.ok() ? existing.limitBreakCards() : 0;
        root.factionCardsPendingJoin = false;
        return AtomicJsonFiles.writeJson(MetaFeaturePaths.backpackPlayer(uuid), root, GSON, false);
    }

    public static int selfSelectCards(UUID uuid) {
        LoadResult load = loadResult(uuid);
        return load.ok() ? load.selfSelectCards() : 0;
    }

    public static int limitBreakCards(UUID uuid) {
        LoadResult load = loadResult(uuid);
        return load.ok() ? load.limitBreakCards() : 0;
    }

    /** Atomically changes the virtual limit-break balance. */
    public static boolean addLimitBreakCards(UUID uuid, int amount) {
        if (!WorldLotteryPaths.ready() || uuid == null || amount == 0) return false;
        return changeLimitBreakCards(MetaFeaturePaths.backpackPlayer(uuid), amount, false);
    }

    public static boolean setLimitBreakCards(UUID uuid, int count) {
        if (!WorldLotteryPaths.ready() || uuid == null || count < 0) return false;
        return changeLimitBreakCards(MetaFeaturePaths.backpackPlayer(uuid), count, true);
    }

    static boolean changeLimitBreakCards(Path path, int amount, boolean absolute) {
        if (path == null) return false;
        LoadResult load = loadFrom(path);
        if (load.corrupt()) return false;
        long target = absolute ? amount : (long) load.limitBreakCards() + amount;
        if (target < 0 || target > Integer.MAX_VALUE) return false;
        FileRoot root = new FileRoot();
        root.cards = load.ok() ? new HashMap<>(load.cards()) : defaultCards();
        root.selfSelectCards = load.selfSelectCards();
        root.limitBreakCards = (int) target;
        root.factionCardsPendingJoin = load.isMissing() || load.factionCardsPendingJoin();
        return AtomicJsonFiles.writeJson(path, root, GSON, false);
    }

    public static boolean addSelfSelectCards(UUID uuid, int amount) {
        if (!WorldLotteryPaths.ready() || uuid == null) return false;
        return changeSelfSelectCards(MetaFeaturePaths.backpackPlayer(uuid), amount, false);
    }

    public static boolean setSelfSelectCards(UUID uuid, int count) {
        if (!WorldLotteryPaths.ready() || uuid == null || count < 0) return false;
        return changeSelfSelectCards(MetaFeaturePaths.backpackPlayer(uuid), count, true);
    }

    /** One atomic backpack write; never clamps an insufficient debit into success. */
    static boolean changeSelfSelectCards(Path path, int amount, boolean absolute) {
        if (path == null) return false;
        LoadResult load = loadFrom(path);
        if (load.corrupt()) return false;
        FileRoot root = new FileRoot();
        root.cards = load.ok() ? new HashMap<>(load.cards()) : defaultCards();
        long target = absolute ? amount : (long) load.selfSelectCards() + amount;
        if (target < 0 || target > Integer.MAX_VALUE) return false;
        root.selfSelectCards = (int) target;
        root.limitBreakCards = load.limitBreakCards();
        root.factionCardsPendingJoin = load.isMissing() || load.factionCardsPendingJoin();
        return AtomicJsonFiles.writeJson(path, root, GSON, false);
    }

    public static boolean saveTo(Path path, Map<String, Integer> cards) {
        if (path == null) {
            return false;
        }
        FileRoot root = new FileRoot();
        root.cards = cards == null ? defaultCards() : new HashMap<>(cards);
        LoadResult existing = loadFrom(path);
        if (existing.corrupt()) return false;
        root.selfSelectCards = existing.selfSelectCards();
        root.limitBreakCards = existing.limitBreakCards();
        root.factionCardsPendingJoin = false;
        return AtomicJsonFiles.writeJson(path, root, GSON, false);
    }

    /**
     * Add {@code amount} cards of {@code type} to the player's local JSON.
     * Missing files start from defaults; corrupt files are refused.
     */
    public static boolean addCards(UUID uuid, FactionCardType type, int amount) {
        if (uuid == null || type == null || type == FactionCardType.NONE || amount == 0) {
            return false;
        }
        LoadResult load = loadResult(uuid);
        if (load.corrupt()) {
            HabiLotteryMod.LOGGER.error("Refusing to add cards over corrupt backpack {}", uuid);
            return false;
        }
        Map<String, Integer> cards;
        if (load.ok() && load.cards() != null) {
            cards = new HashMap<>(load.cards());
        } else {
            cards = defaultCards();
        }
        String key = type.questKey;
        int current = cards.getOrDefault(key, cards.getOrDefault(key.toLowerCase(Locale.ROOT), 0));
        if (!cards.containsKey(key) && cards.containsKey(key.toLowerCase(Locale.ROOT))) {
            key = key.toLowerCase(Locale.ROOT);
        }
        cards.put(key, Math.max(0, current + amount));
        return save(uuid, cards);
    }

    public static boolean saveFromEnumMap(UUID uuid, Map<FactionCardType, Integer> cards) {
        Map<String, Integer> map = defaultCards();
        if (cards != null) {
            for (Map.Entry<FactionCardType, Integer> e : cards.entrySet()) {
                if (e.getKey() != null && e.getKey() != FactionCardType.NONE && e.getValue() != null) {
                    map.put(e.getKey().questKey, Math.max(0, e.getValue()));
                }
            }
        }
        return save(uuid, map);
    }

    /**
     * Atomically edits one offline player's card file. Missing files are created from
     * the complete zero-valued schema; corrupt files are quarantined and never replaced.
     */
    public static CardMutationResult mutate(
            UUID uuid,
            FactionCardType type,
            CardOperation operation,
            int value
    ) {
        if (uuid == null || type == null || type == FactionCardType.NONE) {
            return CardMutationResult.failure("无效玩家或角色卡类型", CardStoreStatus.MISSING);
        }
        try {
            PlayerCardMutationPolicy.validate(operation, value);
        } catch (IllegalArgumentException e) {
            return CardMutationResult.failure(e.getMessage(), CardStoreStatus.MISSING);
        }

        LoadResult load = loadResult(uuid);
        if (load.corrupt()) {
            return CardMutationResult.failure("角色卡存档损坏，已拒绝覆盖", CardStoreStatus.CORRUPT);
        }

        Map<String, Integer> cards = load.ok() && load.cards() != null
                ? new HashMap<>(load.cards())
                : defaultCards();
        String key = type.questKey.toLowerCase(Locale.ROOT);
        int current = Math.max(0, cards.getOrDefault(key, 0));
        int target = PlayerCardMutationPolicy.targetCount(current, operation, value);
        cards.put(key, target);
        if (!save(uuid, cards)) {
            CardStoreStatus status = load.isMissing() ? CardStoreStatus.MISSING : CardStoreStatus.STORED;
            return CardMutationResult.failure("角色卡存档写入失败", status);
        }
        return CardMutationResult.success(CardStoreStatus.STORED, target);
    }

    /** Compare local JSON counts with an in-memory enum map (missing keys count as 0). */
    public static boolean sameCounts(Map<String, Integer> local, Map<FactionCardType, Integer> current) {
        return lowerCounts(local).equals(lowerCountsFromEnum(current));
    }

    public static Map<String, Integer> defaultCards() {
        Map<String, Integer> map = new HashMap<>();
        for (FactionCardType type : FactionCardType.values()) {
            if (type != FactionCardType.NONE) {
                map.put(type.questKey, 0);
            }
        }
        return map;
    }

    private static Map<String, Integer> normalize(FileRoot root) {
        Map<String, Integer> out = defaultCards();
        if (root == null || root.cards == null) {
            return out;
        }
        for (Map.Entry<String, Integer> e : root.cards.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey().toLowerCase(Locale.ROOT), Math.max(0, e.getValue()));
            }
        }
        return out;
    }

    private static Map<String, Integer> lowerCounts(Map<String, Integer> src) {
        Map<String, Integer> out = new HashMap<>();
        for (FactionCardType type : FactionCardType.values()) {
            if (type != FactionCardType.NONE) {
                out.put(type.questKey.toLowerCase(Locale.ROOT), 0);
            }
        }
        if (src != null) {
            for (Map.Entry<String, Integer> e : src.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey().toLowerCase(Locale.ROOT), Math.max(0, e.getValue()));
                }
            }
        }
        return out;
    }

    private static Map<String, Integer> lowerCountsFromEnum(Map<FactionCardType, Integer> src) {
        Map<String, Integer> out = new HashMap<>();
        for (FactionCardType type : FactionCardType.values()) {
            if (type != FactionCardType.NONE) {
                out.put(type.questKey.toLowerCase(Locale.ROOT), 0);
            }
        }
        if (src != null) {
            for (Map.Entry<FactionCardType, Integer> e : src.entrySet()) {
                if (e.getKey() != null && e.getKey() != FactionCardType.NONE && e.getValue() != null) {
                    out.put(e.getKey().questKey.toLowerCase(Locale.ROOT), Math.max(0, e.getValue()));
                }
            }
        }
        return out;
    }
}
