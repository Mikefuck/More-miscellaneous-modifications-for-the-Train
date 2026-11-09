package com.habitrain.lottery.mail;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.card.ServerMatchGate;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.LocalTitleStore;
import com.habitrain.lottery.title.TitleService;
import com.habitrain.lottery.warehouse.SystemItemBalances;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server-thread reward delivery. An immutable intent is saved before any asset changes.
 * Each asset file commits its own receipt together with its rewards. Interrupted deliveries
 * resume unfinished stores; already delivered assets may be used without affecting recovery.
 * A partial failure stays pending rather than attempting an unsafe cross-file rollback.
 */
public final class RewardDelivery {
    private static final Gson GSON = new Gson();

    public static final class Plan {
        public String key;
        public List<MailReward> rewards = List.of();
        public List<String> titles = List.of();
        public MailDraft letter;
        public boolean complete;

        public Plan() {
        }

        public Plan(List<MailReward> rewards, List<String> titles, MailDraft letter) {
            this.rewards = rewards == null ? List.of() : List.copyOf(rewards);
            this.titles = titles == null ? List.of() : List.copyOf(titles);
            this.letter = letter;
        }
    }

    private RewardDelivery() {
    }

    static Path directory(UUID uuid) {
        Path root = WorldLotteryPaths.root();
        if (root == null || uuid == null) {
            return null;
        }
        return WorldLotteryPaths.resolveUnder(root.resolve("reward-deliveries"), uuid.toString());
    }

    static Path path(UUID uuid, String key) {
        Path dir = directory(uuid);
        if (dir == null || key == null) {
            return null;
        }
        return WorldLotteryPaths.resolveChildFile(dir, id(key) + ".json");
    }

    static String id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Arms an unpaid {@code shop:*} delivery intent on disk before the atomic debit commits.
     * Recovery refuses and cleans up any {@code shop:*} intent that lacks a committed payment token.
     */
    public static boolean armShopPlan(UUID uuid, String key, Plan plan) {
        if (uuid == null || key == null || !key.startsWith("shop:") || plan == null
                || !WorldLotteryPaths.ready() || PlayerLotteryStore.get().isFlushDeferred()) {
            return false;
        }
        plan.key = key;
        plan.complete = false;
        if (!validatePreconditions(uuid, plan, true)) {
            return false;
        }
        Path file = path(uuid, key);
        return file != null && AtomicJsonFiles.writeJson(file, plan, GSON, true, false);
    }

    /** Removes a completed (or rolled-back unpaid) intent {@code .json} and {@code .bak}. */
    public static void cleanupCompleted(UUID uuid, String key) {
        Path file = path(uuid, key);
        if (file != null) {
            deletePlanFiles(file);
            if (!Files.exists(file) && !Files.exists(AtomicJsonFiles.bakPath(file))) {
                retirePendingPlayerReceipt(uuid, key);
            }
        }
    }

    static void deletePlanFiles(Path file) {
        if (file == null) {
            return;
        }
        try {
            // Delete the older backup first: never leave only an incomplete backup
            // after removing the authoritative complete plan.
            Files.deleteIfExists(AtomicJsonFiles.bakPath(file));
        } catch (IOException e) {
            HabiLotteryMod.LOGGER.warn("Could not delete reward intent backup {}: {}", file, e.toString());
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            HabiLotteryMod.LOGGER.warn("Could not delete reward intent {}: {}", file, e.toString());
        }
    }

    /** The supplier is only evaluated for a new transaction, so config changes cannot alter a retry. */
    public static boolean grant(ServerPlayer player, String key, Supplier<Plan> create) {
        if (player == null || !WorldLotteryPaths.ready()) return false;
        if (!LocalBackpackStore.prepareOnline(player)) return false;
        boolean result = deliver(player.getUUID(), key, create);
        // Even a partial delivery is committed; mirrors must reflect it before the next operation.
        syncCommitted(player);
        return result;
    }

    public static void syncCommitted(ServerPlayer player) {
        if (player == null) return;
        LocalBackpackStore.syncCommitted(player);
        TitleService.syncCommitted(player);
        com.habitrain.lottery.skin.SkinNetwork.sync(player);
    }

    /** Resume saved intents even when the originating daily task/date or mail has expired. */
    public static void recoverPending(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready()) return;
        boolean deferCards = ServerMatchGate.hasActiveMatch(player) || PlayerStateGate.spectatorRestOrDead(player);
        if (!deferCards && !LocalBackpackStore.prepareOnline(player)) return;
        recoverPending(player.getUUID(), deferCards);
        if (deferCards) {
            TitleService.syncCommitted(player);
            com.habitrain.lottery.skin.SkinNetwork.sync(player);
        } else {
            syncCommitted(player);
        }
    }

    public static void recoverPending(UUID uuid) {
        var server = HabiLotteryMod.getServer();
        var online = server == null || server.getPlayerList() == null ? null : server.getPlayerList().getPlayer(uuid);
        if (online != null) recoverPending(online);
        else recoverPending(uuid, ServerMatchGate.hasActiveMatch(server));
    }

    static void recoverPending(UUID uuid, boolean deferCards) {
        Path directory = directory(uuid);
        if (directory == null || !Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (String name : files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("[0-9a-fA-F-]{36}\\.json(?:\\.bak)?"))
                    .map(n -> n.substring(0, 41)).distinct().toList()) {
                Path file = directory.resolve(name);
                var loaded = AtomicJsonFiles.readJson(file, Plan.class, GSON);
                Plan plan = loaded.value();
                if (loaded.corrupt() || plan == null || plan.key == null
                        || !name.equals(id(plan.key) + ".json")) {
                    continue;
                }
                if (plan.key.startsWith("shop:")) {
                    PlayerLotteryStore store = PlayerLotteryStore.get();
                    store.getOrLoad(uuid);
                    if (store.isLoadFailed(uuid)) {
                        continue;
                    }
                    if (!isShopPlanAuthorized(uuid, plan.key)) {
                        HabiLotteryMod.LOGGER.warn(
                                "Discarding unpaid armed shop intent {} for {}", plan.key, uuid);
                        deletePlanFiles(file);
                        continue;
                    }
                }
                if (plan.complete) {
                    if (plan.key.startsWith("mail:")) {
                        String mailId = plan.key.substring("mail:".length());
                        if (MailService.markClaimedFromRecovery(uuid, mailId)) {
                            cleanupCompleted(uuid, plan.key);
                        }
                    } else {
                        cleanupCompleted(uuid, plan.key);
                        retirePaidShopToken(uuid, plan.key);
                    }
                    continue;
                }
                if (deferCards && planHasCards(plan)) {
                    continue;
                }
                if (deliver(uuid, plan.key, () -> null)) {
                    if (plan.key.startsWith("mail:")) {
                        String mailId = plan.key.substring("mail:".length());
                        if (MailService.markClaimedFromRecovery(uuid, mailId)) {
                            cleanupCompleted(uuid, plan.key);
                        }
                    }
                } else {
                    HabiLotteryMod.LOGGER.warn("Reward delivery {} for {} still awaits recovery", plan.key, uuid);
                }
            }
        } catch (IOException failure) {
            HabiLotteryMod.LOGGER.error("Cannot scan pending rewards for {}", uuid, failure);
        }
    }

    public static boolean deliver(UUID uuid, String key, Supplier<Plan> create) {
        if (uuid == null || key == null || key.isBlank() || !WorldLotteryPaths.ready()
                || PlayerLotteryStore.get().isFlushDeferred()) return false;
        Path file = path(uuid, key);
        if (file == null) return false;
        try {
            var loaded = AtomicJsonFiles.readJson(file, Plan.class, GSON);
            if (loaded.corrupt()) return false;
            Plan plan;
            if (loaded.isMissing()) {
                plan = create == null ? null : create.get();
                if (plan == null) {
                    return hasAnyReceipt(uuid, key);
                }
                plan.key = key;
                plan.complete = false;
                if (!isShopPlanAuthorized(uuid, key)) return false;
                if (!validatePreconditions(uuid, plan, true)) return false;
                if (!AtomicJsonFiles.writeJson(file, plan, GSON, true)) return false;
            } else {
                plan = loaded.value();
            }
            if (plan == null || !key.equals(plan.key) || plan.rewards == null || plan.titles == null) return false;
            if (!isShopPlanAuthorized(uuid, key)) return false;
            if (plan.complete) {
                if (!key.startsWith("mail:")) {
                        cleanupCompleted(uuid, plan.key);
                    retirePaidShopToken(uuid, key);
                }
                return true;
            }
            if (!validatePreconditions(uuid, plan, true)) return false;
            if (plan.letter != null) {
                if (!MailService.sendOnce(uuid, "delivery:" + key, plan.letter)) return false;
            } else {
                if (needsPlayerStore(plan) && !applyPlayer(uuid, key, plan.rewards)) return false;
                if (!applyBackpack(uuid, key, plan.rewards)) return false;
            }
            if (!plan.titles.isEmpty() && !LocalTitleStore.get().grantOnce(uuid, key, plan.titles)) return false;
            plan.complete = true;
            if (!AtomicJsonFiles.writeJson(file, plan, GSON, true, !loaded.usedBackup())) {
                return false;
            }
            if (!key.startsWith("mail:")) {
                cleanupCompleted(uuid, key);
                retirePaidShopToken(uuid, key);
            }
            return true;
        } catch (RuntimeException failure) {
            HabiLotteryMod.LOGGER.error("Reward delivery {} for {} remains pending", key, uuid, failure);
            return false;
        }
    }

    static boolean planHasCards(Plan plan) {
        if (plan == null || plan.letter != null || plan.rewards == null) {
            return false;
        }
        for (MailReward r : plan.rewards) {
            if (r != null && (r.kind() == MailReward.Kind.FACTION_CARD
                    || r.kind() == MailReward.Kind.SELF_SELECT_CARD
                    || r.kind() == MailReward.Kind.LIMIT_BREAK_CARD)) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsPlayerStore(Plan plan) {
        if (plan.key != null && plan.key.startsWith("shop:")) {
            return true;
        }
        if (plan.rewards == null || plan.rewards.isEmpty()) {
            return false;
        }
        for (MailReward r : plan.rewards) {
            if (r == null || r.kind() == null) {
                return true;
            }
            if (r.kind() == MailReward.Kind.GREEN_APPLES
                    || r.kind() == MailReward.Kind.SKIN
                    || r.kind() == MailReward.Kind.CRATE
                    || r.kind() == MailReward.Kind.KEY) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShopPlanAuthorized(UUID uuid, String key) {
        if (key == null || !key.startsWith("shop:")) {
            return true;
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        var data = store.getOrLoad(uuid);
        if (store.isLoadFailed(uuid)) {
            return false;
        }
        return (data.paidShopPurchases != null && data.paidShopPurchases.contains(key))
                || hasAnyReceipt(uuid, key);
    }

    static boolean hasAnyReceipt(UUID uuid, String key) {
        if (uuid == null || key == null || key.isBlank() || !WorldLotteryPaths.ready()) {
            return false;
        }
        PlayerLotteryStore playerStore = PlayerLotteryStore.get();
        var playerData = playerStore.getOrLoad(uuid);
        if (!playerStore.isLoadFailed(uuid)
                && playerData.rewardReceipts != null
                && (playerData.rewardReceipts.contains(key)
                || playerData.pendingRewardReceipts != null && playerData.pendingRewardReceipts.contains(key))) {
            return true;
        }
        var backpack = LocalBackpackStore.loadResult(uuid);
        if (backpack.ok() && backpack.rewardReceipts().contains(key)) {
            return true;
        }
        var titles = LocalTitleStore.get().loadPlayerDetailed(uuid);
        if (titles.ok() && titles.data() != null
                && titles.data().rewardReceipts != null
                && titles.data().rewardReceipts.contains(key)) {
            return true;
        }
        String deliveryMailId = id("delivery:" + key);
        var mailbox = LocalMailboxStore.loadWithStatus(uuid);
        if (mailbox.ok() && mailbox.mails() != null) {
            for (var mail : mailbox.mails()) {
                if (mail != null && deliveryMailId.equals(mail.id)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean validatePreconditions(UUID uuid, Plan plan, boolean checkCapacity) {
        if (plan == null || plan.key == null || plan.key.isBlank()
                || plan.rewards == null || plan.titles == null) {
            return false;
        }
        try {
            if (plan.letter != null) {
                var mailbox = LocalMailboxStore.loadWithStatus(uuid);
                if (mailbox.corrupt()) {
                    return false;
                }
                String deliveryMailId = id("delivery:" + plan.key);
                boolean alreadySent = mailbox.mails() != null
                        && mailbox.mails().stream().anyMatch(m -> m != null && deliveryMailId.equals(m.id));
                if (!alreadySent) {
                    if (plan.letter.rewards() == null) {
                        return false;
                    }
                    for (MailReward r : plan.letter.rewards()) {
                        if (r == null) continue;
                        if (r.amount() <= 0) return false;
                        if (r.kind() == MailReward.Kind.CRATE || r.kind() == MailReward.Kind.KEY) {
                            var entry = CrateCatalog.find(r.factionType());
                            if (entry == null || entry.archived()) {
                                return false;
                            }
                        }
                    }
                }
            } else {
                for (MailReward r : plan.rewards) {
                    if (r == null || r.kind() == null || r.amount() == 0
                            || r.amount() < 0 && (!plan.key.startsWith("mail:") || r.kind() == MailReward.Kind.SKIN)) {
                        return false;
                    }
                }
                if (needsPlayerStore(plan)) {
                    PlayerLotteryStore store = PlayerLotteryStore.get();
                    var current = store.getOrLoad(uuid);
                    if (store.isLoadFailed(uuid)) {
                        return false;
                    }
                    if ((current.rewardReceipts == null || !current.rewardReceipts.contains(plan.key))
                            && (current.pendingRewardReceipts == null || !current.pendingRewardReceipts.contains(plan.key))) {
                        int apples = current.greenApples;
                        Map<String, Integer> tempItems = current.systemItems == null
                                ? new HashMap<>()
                                : new HashMap<>(current.systemItems);
                        for (MailReward reward : plan.rewards) {
                            switch (reward.kind()) {
                                case GREEN_APPLES -> {
                                    if (checkCapacity) {
                                        apples = Math.addExact(apples, reward.amount());
                                        if (apples < 0) return false;
                                    }
                                }
                                case SKIN -> {
                                    if (HabiSkinApi.fromEntry(reward.factionType()).isEmpty()) return false;
                                }
                                case CRATE, KEY -> {
                                    var crate = CrateCatalog.find(reward.factionType());
                                    if (crate == null) return false;
                                    if (checkCapacity) {
                                        String item = reward.kind() == MailReward.Kind.CRATE
                                                ? crate.crateItemId()
                                                : crate.keyItemId();
                                        if (!SystemItemBalances.change(tempItems, item, reward.amount())) {
                                            return false;
                                        }
                                    }
                                }
                                default -> {
                                }
                            }
                        }
                    }
                }
                if (planHasCards(plan)) {
                    var backpack = LocalBackpackStore.loadResult(uuid);
                    if (backpack.corrupt()) {
                        return false;
                    }
                    if (!backpack.rewardReceipts().contains(plan.key)) {
                        Map<String, Integer> tempCards = backpack.ok()
                                ? new HashMap<>(backpack.cards())
                                : LocalBackpackStore.defaultCards();
                        int selfSelect = backpack.selfSelectCards();
                        int limitBreak = backpack.limitBreakCards();
                        for (MailReward reward : plan.rewards) {
                            switch (reward.kind()) {
                                case FACTION_CARD -> {
                                    var type = FactionCardType.fromString(reward.factionType());
                                    if (type == FactionCardType.NONE) return false;
                                    if (checkCapacity) {
                                        String card = type.questKey.toLowerCase(Locale.ROOT);
                                        tempCards.put(card, balance(tempCards.getOrDefault(card, 0), reward.amount()));
                                    }
                                }
                                case SELF_SELECT_CARD -> {
                                    if (checkCapacity) selfSelect = balance(selfSelect, reward.amount());
                                }
                                case LIMIT_BREAK_CARD -> {
                                    if (checkCapacity) limitBreak = balance(limitBreak, reward.amount());
                                }
                                default -> {
                                }
                            }
                        }
                    }
                }
            }
            if (!plan.titles.isEmpty()) {
                var titleLoad = LocalTitleStore.get().loadPlayerDetailed(uuid);
                if (titleLoad.corrupt() || titleLoad.data() == null) {
                    return false;
                }
                if (titleLoad.data().rewardReceipts == null
                        || !titleLoad.data().rewardReceipts.contains(plan.key)) {
                    for (String t : plan.titles) {
                        if (t == null || t.isBlank()) return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean applyPlayer(UUID uuid, String key, List<MailReward> rewards) {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        var current = store.getOrLoad(uuid);
        if (store.isLoadFailed(uuid)) return false;
        if (current.rewardReceipts.contains(key)
                || current.pendingRewardReceipts != null && current.pendingRewardReceipts.contains(key)) return true;
        return store.applyToPlayerWithRollback(uuid, data -> {
            for (MailReward reward : rewards) {
                if (reward == null || reward.kind() == null) throw new IllegalArgumentException("Invalid reward");
                switch (reward.kind()) {
                    case GREEN_APPLES -> {
                        data.greenApples = Math.addExact(data.greenApples, reward.amount());
                        if (data.greenApples < 0) throw new IllegalArgumentException("Insufficient apples");
                    }
                    case SKIN -> {
                        var skin = HabiSkinApi.fromEntry(reward.factionType()).orElseThrow();
                        PlayerLotteryStore.awardSkin(data, skin.type(), skin.id(), reward.amount());
                    }
                    case CRATE, KEY -> {
                        var crate = CrateCatalog.find(reward.factionType());
                        if (crate == null) throw new IllegalArgumentException("Unknown crate attachment");
                        String item = reward.kind() == MailReward.Kind.CRATE ? crate.crateItemId() : crate.keyItemId();
                        if (!SystemItemBalances.change(data.systemItems, item, reward.amount()))
                            throw new IllegalArgumentException("Invalid system item balance");
                    }
                    default -> {
                    }
                }
            }
            data.rewardReceipts.add(key);
            if (data.pendingRewardReceipts == null) data.pendingRewardReceipts = new java.util.LinkedHashSet<>();
            data.pendingRewardReceipts.add(key);
        });
    }

    private static void retirePendingPlayerReceipt(UUID uuid, String key) {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (!WorldLotteryPaths.ready() || store.isFlushDeferred()) return;
        var data = store.getOrLoad(uuid);
        if (store.isLoadFailed(uuid) || data.pendingRewardReceipts == null
                || !data.pendingRewardReceipts.contains(key)) return;
        store.applyToPlayerWithRollback(uuid, next -> next.pendingRewardReceipts.remove(key));
    }

    private static void retirePaidShopToken(UUID uuid, String key) {
        if (uuid == null || key == null || !key.startsWith("shop:") || !WorldLotteryPaths.ready()) {
            return;
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isFlushDeferred() || store.isLoadFailed(uuid)) {
            return;
        }
        var current = store.getOrLoad(uuid);
        if (store.isLoadFailed(uuid) || current.paidShopPurchases == null
                || !current.paidShopPurchases.contains(key)) {
            return;
        }
        try {
            store.applyToPlayerWithRollback(uuid, data -> {
                if (data.paidShopPurchases != null) {
                    data.paidShopPurchases.remove(key);
                }
            });
        } catch (RuntimeException ignored) {
            // Intent is already marked complete / deleted; token is inert if flush is deferred.
        }
    }

    private static boolean applyBackpack(UUID uuid, String key, List<MailReward> rewards) {
        if (rewards.stream().noneMatch(r -> r.kind() == MailReward.Kind.FACTION_CARD
                || r.kind() == MailReward.Kind.SELF_SELECT_CARD || r.kind() == MailReward.Kind.LIMIT_BREAK_CARD)) {
            return true;
        }
        return LocalBackpackStore.applyOnce(uuid, key, next -> {
            for (MailReward reward : rewards) {
                switch (reward.kind()) {
                    case FACTION_CARD -> {
                        var type = FactionCardType.fromString(reward.factionType());
                        if (type == FactionCardType.NONE) throw new IllegalArgumentException("Unknown faction card");
                        String card = type.questKey.toLowerCase(Locale.ROOT);
                        next.cards.put(card, balance(next.cards.getOrDefault(card, 0), reward.amount()));
                    }
                    case SELF_SELECT_CARD -> next.selfSelectCards = balance(next.selfSelectCards, reward.amount());
                    case LIMIT_BREAK_CARD -> next.limitBreakCards = balance(next.limitBreakCards, reward.amount());
                    default -> {
                    }
                }
            }
        });
    }

    private static int balance(int before, int delta) {
        int after = Math.addExact(before, delta);
        if (after < 0) throw new IllegalArgumentException("Insufficient cards");
        return after;
    }
}
