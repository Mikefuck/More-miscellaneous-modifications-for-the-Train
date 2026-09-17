package com.habitrain.lottery.backpack;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.backpack.BackpackManager;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JOIN overlay of world backpack JSON onto SRE memory.
 *
 * <p>Existing JSON (including zeros) is the authority. A missing file seeds JSON from
 * SRE (add-only). Corrupt JSON is not applied and not replaced with zeros.
 */
public final class BackpackJoinService {
    private static final Set<UUID> PENDING_RETRY = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> CORRUPT_UNSAVED = ConcurrentHashMap.newKeySet();

    private BackpackJoinService() {
    }

    public static void apply(ServerPlayer player) {
        apply(player, false);
    }

    private static void apply(ServerPlayer player, boolean retry) {
        if (player == null || !WorldLotteryPaths.ready()) {
            return;
        }
        UUID uuid = player.getUUID();
        boolean ok;
        try {
            ok = applyOnce(player, retry);
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("applyLocalBackpack failed for {}", uuid, e);
            ok = false;
        }
        if (ok) {
            PENDING_RETRY.remove(uuid);
            CORRUPT_UNSAVED.remove(uuid);
            return;
        }
        HabiLotteryMod.LOGGER.error("applyLocalBackpack failed for {}", uuid);
        if (retry) {
            PENDING_RETRY.remove(uuid);
            return;
        }
        if (!PENDING_RETRY.add(uuid)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            PENDING_RETRY.remove(uuid);
            return;
        }
        server.execute(() -> {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online == null) {
                PENDING_RETRY.remove(uuid);
                return;
            }
            apply(online, true);
        });
    }

    private static boolean applyOnce(ServerPlayer player, boolean retry) {
        UUID uuid = player.getUUID();
        if (!retry) {
            LocalBackpackStore.LoadResult load = LocalBackpackStore.loadResult(uuid);
            if (load.corrupt()) {
                HabiLotteryMod.LOGGER.error(
                        "Corrupt backpack JSON for {}, skipping overlay/save (will retry once)",
                        uuid);
                CORRUPT_UNSAVED.add(uuid);
                return false;
            }
            if (load.ok()) {
                overlay(player, load.cards(), load.factionCardsPendingJoin());
            } else if (!LocalBackpackStore.saveFromEnumMap(uuid, BackpackManager.getCards(player))) {
                HabiLotteryMod.LOGGER.error("Failed seeding backpack JSON from SRE for {}", uuid);
                return false;
            }
            DailyFactionCardService.grantLoginCard(player);
        } else if (CORRUPT_UNSAVED.contains(uuid)) {
            HabiLotteryMod.LOGGER.error(
                    "Corrupt backpack JSON for {} still unreadable, leaving unsaved", uuid);
            return false;
        }
        boolean saved = LocalBackpackStore.saveFromEnumMap(uuid, BackpackManager.getCards(player));
        if (!saved) {
            HabiLotteryMod.LOGGER.error("Failed persisting backpack JSON for {}", uuid);
            return false;
        }
        BackpackManager.resend(player);
        return true;
    }

    private static void overlay(ServerPlayer player, Map<String, Integer> local, boolean addOnly) {
        Map<FactionCardType, Integer> current = BackpackManager.getCards(player);
        for (FactionCardType type : FactionCardType.values()) {
            if (type == FactionCardType.NONE) {
                continue;
            }
            int want = local.getOrDefault(type.questKey, 0);
            int have = current.getOrDefault(type, 0);
            int delta = (addOnly ? Math.max(want, have) : want) - have;
            if (delta != 0) {
                BackpackManager.addCard(player, type, delta);
            }
        }
    }
}
