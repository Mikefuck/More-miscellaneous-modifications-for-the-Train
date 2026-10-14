package com.habitrain.lottery.card;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.UUID;

/** Each card activation owns an independent, retryable balance/quota refund intent. */
public final class FactionCardRefunds {
    private static final Gson GSON = new Gson();
    private static final class Intent {
        String receipt;
        String type;
        long day;
        boolean complete;
    }

    private FactionCardRefunds() { }

    public static boolean refund(UUID uuid, FactionCardType type, ServerPlayer online) {
        if (uuid == null || type == null || type == FactionCardType.NONE || !WorldLotteryPaths.ready()) return false;
        UUID activation = ActiveCardForces.refundId(uuid);
        if (activation == null) return false;
        Path file = directory().resolve(uuid.toString()).resolve(activation + ".json");
        return finish(uuid, file, "faction-refund:" + activation, type, online);
    }

    /** A missing intent is created only for a known current activation, never during recovery. */
    private static boolean finish(UUID uuid, Path file, String expectedReceipt,
                                  FactionCardType requestedType, ServerPlayer online) {
        try {
            var load = AtomicJsonFiles.readJson(file, Intent.class, GSON);
            if (load.corrupt()) return false;
            Intent intent = load.value();
            if (load.isMissing()) {
                if (requestedType == null || expectedReceipt == null) return false;
                intent = new Intent();
                intent.receipt = expectedReceipt;
                intent.type = requestedType.questKey;
                intent.day = LoginRewardService.todayEpochDayUtc();
                if (!AtomicJsonFiles.writeJson(file, intent, GSON, true, !load.usedBackup())) return false;
            }
            if (intent == null || intent.receipt == null || intent.receipt.isBlank()
                    || FactionCardType.fromString(intent.type) == FactionCardType.NONE
                    || expectedReceipt != null && !expectedReceipt.equals(intent.receipt)
                    || requestedType != null && FactionCardType.fromString(intent.type) != requestedType) return false;
            if (intent.complete) return true;
            final Intent pending = intent;
            if (online != null && !LocalBackpackStore.prepareOnline(online)) return false;
            if (!LocalBackpackStore.applyOnce(uuid, intent.receipt, next -> next.cards.put(pending.type,
                    Math.addExact(next.cards.getOrDefault(pending.type, 0), 1)))) return false;
            if (online != null) LocalBackpackStore.syncCommitted(online);
            var store = PlayerLotteryStore.get();
            if (!store.applyToPlayerWithRollback(uuid, data -> {
                if (data.rewardReceipts.contains(pending.receipt)) return;
                if (data.lastFactionCardUseEpochDay == pending.day)
                    data.factionCardUsesToday = Math.max(0, data.factionCardUsesToday - 1);
                data.rewardReceipts.add(pending.receipt);
            })) return false;
            intent.complete = true;
            return AtomicJsonFiles.writeJson(file, intent, GSON, true, !load.usedBackup());
        } catch (RuntimeException failure) {
            HabiLotteryMod.LOGGER.error("Faction-card refund {} remains pending for {}", file, uuid, failure);
            return false;
        }
    }

    private static Path directory() {
        return WorldLotteryPaths.root().resolve("faction-refunds");
    }

    /** Also retry on join when the startup recovery ran during a temporary storage failure. */
    public static void recoverPending(ServerPlayer player) {
        if (player != null && WorldLotteryPaths.ready()) recoverPending(player.getUUID(), player);
    }

    private static void recoverPending(UUID uuid, ServerPlayer online) {
        // 1.1.54 used one file per player. Resume it independently; it can never stand in for a new use.
        Path legacy = directory().resolve(uuid + ".json");
        if (Files.exists(legacy) || Files.exists(AtomicJsonFiles.bakPath(legacy))) {
            if (!finish(uuid, legacy, null, null, online))
                HabiLotteryMod.LOGGER.warn("Legacy faction-card refund still awaits recovery for {}", uuid);
        }
        Path playerDir = directory().resolve(uuid.toString());
        if (!Files.isDirectory(playerDir)) return;
        try (var files = Files.list(playerDir)) {
            for (UUID activation : files.filter(Files::isRegularFile)
                    .map(p -> intentId(p.getFileName().toString())).filter(java.util.Objects::nonNull).distinct().toList()) {
                if (!finish(uuid, playerDir.resolve(activation + ".json"), "faction-refund:" + activation, null, online))
                    HabiLotteryMod.LOGGER.warn("Faction-card refund {} still awaits recovery for {}", activation, uuid);
            }
        } catch (Exception failure) {
            HabiLotteryMod.LOGGER.error("Cannot scan pending faction-card refunds for {}", uuid, failure);
        }
    }

    public static void recover() {
        if (!WorldLotteryPaths.ready()) return;
        Path dir = directory();
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            var players = new LinkedHashSet<UUID>();
            for (Path file : files.toList()) {
                UUID uuid = Files.isDirectory(file) ? parseUuid(file.getFileName().toString())
                        : intentId(file.getFileName().toString());
                if (uuid != null) players.add(uuid);
            }
            for (UUID uuid : players) {
                var server = HabiLotteryMod.getServer();
                recoverPending(uuid, server == null ? null : server.getPlayerList().getPlayer(uuid));
            }
        } catch (Exception failure) {
            HabiLotteryMod.LOGGER.error("Cannot scan pending faction-card refunds", failure);
        }
    }

    private static UUID intentId(String name) {
        if (name.endsWith(".json.bak")) return parseUuid(name.substring(0, name.length() - 9));
        if (name.endsWith(".json")) return parseUuid(name.substring(0, name.length() - 5));
        return null;
    }

    private static UUID parseUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equals(value) ? uuid : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
