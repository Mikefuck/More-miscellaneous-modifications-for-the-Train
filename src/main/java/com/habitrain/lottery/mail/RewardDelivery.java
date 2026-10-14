package com.habitrain.lottery.mail;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.LocalTitleStore;
import com.habitrain.lottery.title.TitleService;
import com.habitrain.lottery.warehouse.SystemItemBalances;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
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

        public Plan(List<MailReward> rewards, List<String> titles, MailDraft letter) {
            this.rewards = List.copyOf(rewards);
            this.titles = List.copyOf(titles);
            this.letter = letter;
        }
    }

    private RewardDelivery() { }

    static Path path(UUID uuid, String key) {
        return WorldLotteryPaths.root().resolve("reward-deliveries").resolve(uuid.toString())
                .resolve(id(key) + ".json");
    }

    static String id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
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

    private static void syncCommitted(ServerPlayer player) {
        LocalBackpackStore.syncCommitted(player);
        TitleService.syncCommitted(player);
        com.habitrain.lottery.skin.SkinNetwork.sync(player);
    }

    /** Resume saved intents even when the originating daily task/date or mail has expired. */
    public static void recoverPending(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready() || !LocalBackpackStore.prepareOnline(player)) return;
        recoverPending(player.getUUID());
        syncCommitted(player);
    }

    static void recoverPending(UUID uuid) {
        Path directory = WorldLotteryPaths.root().resolve("reward-deliveries").resolve(uuid.toString());
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (String name : files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("[0-9a-fA-F-]{36}\\.json(?:\\.bak)?"))
                    .map(n -> n.substring(0, 41)).distinct().toList()) {
                var loaded = AtomicJsonFiles.readJson(directory.resolve(name), Plan.class, GSON);
                Plan plan = loaded.value();
                if (loaded.corrupt() || plan == null || plan.complete || plan.key == null
                        || !name.equals(id(plan.key) + ".json")) continue;
                if (!deliver(uuid, plan.key, () -> null))
                    HabiLotteryMod.LOGGER.warn("Reward delivery {} for {} still awaits recovery", plan.key, uuid);
            }
        } catch (java.io.IOException failure) {
            HabiLotteryMod.LOGGER.error("Cannot scan pending rewards for {}", uuid, failure);
        }
    }

    static boolean deliver(UUID uuid, String key, Supplier<Plan> create) {
        if (uuid == null || key == null || key.isBlank() || !WorldLotteryPaths.ready()
                || PlayerLotteryStore.get().isFlushDeferred()) return false;
        try {
            Path file = path(uuid, key);
            var loaded = AtomicJsonFiles.readJson(file, Plan.class, GSON);
            if (loaded.corrupt()) return false;
            Plan plan;
            if (loaded.isMissing()) {
                plan = create.get();
                if (plan == null) return false;
                plan.key = key;
                if (!AtomicJsonFiles.writeJson(file, plan, GSON, true)) return false;
            } else {
                plan = loaded.value();
            }
            if (plan == null || !key.equals(plan.key) || plan.rewards == null || plan.titles == null) return false;
            if (plan.complete) return true;
            if (plan.letter != null) {
                if (!MailService.sendOnce(uuid, "delivery:" + key, plan.letter)) return false;
            } else {
                if (!applyPlayer(uuid, key, plan.rewards)) return false;
                if (!applyBackpack(uuid, key, plan.rewards)) return false;
            }
            if (!plan.titles.isEmpty() && !LocalTitleStore.get().grantOnce(uuid, key, plan.titles)) return false;
            plan.complete = true;
            return AtomicJsonFiles.writeJson(file, plan, GSON, true, !loaded.usedBackup());
        } catch (RuntimeException failure) {
            HabiLotteryMod.LOGGER.error("Reward delivery {} for {} remains pending", key, uuid, failure);
            return false;
        }
    }

    private static boolean applyPlayer(UUID uuid, String key, List<MailReward> rewards) {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        var current = store.getOrLoad(uuid);
        if (store.isLoadFailed(uuid)) return false;
        if (current.rewardReceipts.contains(key)) return true;
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
                    default -> { }
                }
            }
            data.rewardReceipts.add(key);
        });
    }

    private static boolean applyBackpack(UUID uuid, String key, List<MailReward> rewards) {
        if (rewards.stream().noneMatch(r -> r.kind() == MailReward.Kind.FACTION_CARD
                || r.kind() == MailReward.Kind.SELF_SELECT_CARD || r.kind() == MailReward.Kind.LIMIT_BREAK_CARD)) return true;
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
                    default -> { }
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
