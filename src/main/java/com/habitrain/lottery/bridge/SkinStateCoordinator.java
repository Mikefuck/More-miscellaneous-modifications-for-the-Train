package com.habitrain.lottery.bridge;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.SkinTypeKeys;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single orchestration point for durable skin selection and lifecycle repair.
 * World JSON is authoritative; PEM, CCA, client caches and ItemStack components
 * are projections that are always rebuilt from it.
 */
public final class SkinStateCoordinator {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Queue<ScheduledBatch> PENDING = new ConcurrentLinkedQueue<>();

    private SkinStateCoordinator() {
    }

    public static void registerLifecycle() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        OnGameStarted.EVENT.register(level -> {
            MinecraftServer server = level.getServer();
            reassertAll(server, "match_start");
            schedule(server, "match_start_next_tick", 1);
            schedule(server, "match_start_delayed", 5);
        });
        // OnGameEnd fires before SRE clears every player's inventory. Queue the
        // repair for END_SERVER_TICK, after resetPlayerAfterGame has returned.
        OnGameEnd.EVENT.register((level, game) -> {
            MinecraftServer server = level.getServer();
            schedule(server, "match_end", 1);
            schedule(server, "match_end_delayed", 5);
        });
        ServerTickEvents.END_SERVER_TICK.register(SkinStateCoordinator::onEndServerTick);
    }

    public static CommitOutcome commitEquipped(ServerPlayer player, String rawType, String rawSkin) {
        if (player == null) {
            return CommitOutcome.failed("missing_player", "default", "default", null);
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        String type = SkinTypeKeys.canonical(rawType);
        String skin = PlayerLotteryStore.normalizeEquippedSkin(rawSkin);
        String previous = store.getEquipped(player.getUUID(), type);
        boolean unlocked = store.isSkinUnlocked(player.getUUID(), type, skin);

        HabiLotteryMod.LOGGER.info(
                "SKIN_EQUIP_REQUEST player={} type={} canonical={} skin={} unlockedInWorld={} worldBefore={}",
                player.getUUID(), rawType, type, skin, unlocked, previous);

        if (!unlocked) {
            reassertPlayer(player, "equip_rejected_locked");
            player.sendSystemMessage(Component.literal("§c[皮肤] 切换失败：该皮肤尚未解锁"));
            HabiLotteryMod.LOGGER.warn(
                    "SKIN_EQUIP_ROLLBACK player={} type={} requested={} restored={} reason=locked",
                    player.getUUID(), type, skin, previous);
            return CommitOutcome.failed("locked", type, skin, previous);
        }

        PlayerLotteryStore.EquippedCommitResult persisted =
                store.commitEquipped(player.getUUID(), type, skin);
        if (!persisted.committed()) {
            reassertPlayer(player, "equip_rollback_" + persisted.failure());
            player.sendSystemMessage(Component.literal("§c[皮肤] 切换保存失败，已恢复之前的皮肤"));
            HabiLotteryMod.LOGGER.error(
                    "SKIN_EQUIP_ROLLBACK player={} type={} requested={} restored={} reason={}",
                    player.getUUID(), type, skin, persisted.previous(), persisted.failure());
            return CommitOutcome.failed(persisted.failure(), type, skin, persisted.previous());
        }

        EconomyMirror.syncEquippedSkin(player, type, skin);
        int changedStacks = InventorySkinApplier.applyEquippedToInventory(player, type, skin);
        String worldAfter = store.getEquipped(player.getUUID(), type);
        HabiLotteryMod.LOGGER.info(
                "SKIN_EQUIP_COMMIT player={} type={} world={} changedStacks={} durable=true",
                player.getUUID(), type, worldAfter, changedStacks);
        return CommitOutcome.committed(type, skin, persisted.previous(), changedStacks);
    }

    public static ReassertOutcome reassertPlayer(ServerPlayer player, String reason) {
        if (player == null) {
            return new ReassertOutcome(false, 0);
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (!store.isTakeoverActive() || store.isLoadFailed(player.getUUID())) {
            return new ReassertOutcome(false, 0);
        }
        PlayerLotteryData data = store.getOrLoad(player);
        boolean mirrored = EconomyMirror.pushToSre(player, data, false);
        int changed = InventorySkinApplier.applyAllEquipped(player, data.equipped);
        HabiLotteryMod.LOGGER.info(
                "SKIN_REASSERT player={} reason={} mirrored={} changedStacks={} equippedTypes={}",
                player.getUUID(), reason, mirrored, changed,
                data.equipped == null ? 0 : data.equipped.size());
        return new ReassertOutcome(mirrored, changed);
    }

    public static int reassertAll(MinecraftServer server, String reason) {
        if (server == null || !PlayerLotteryStore.get().isTakeoverActive()) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            reassertPlayer(player, reason);
            count++;
        }
        return count;
    }

    public static void clearPending(MinecraftServer server) {
        if (server == null) {
            PENDING.clear();
            return;
        }
        PENDING.removeIf(batch -> batch.server == server);
    }

    private static void schedule(MinecraftServer server, String reason, int ticks) {
        if (server != null) {
            PENDING.add(new ScheduledBatch(server, reason, Math.max(1, ticks)));
        }
    }

    private static void onEndServerTick(MinecraftServer server) {
        int pendingCount = PENDING.size();
        for (int i = 0; i < pendingCount; i++) {
            ScheduledBatch batch = PENDING.poll();
            if (batch == null) {
                break;
            }
            if (batch.server != server) {
                PENDING.add(batch);
                continue;
            }
            if (batch.ticksRemaining <= 1) {
                reassertAll(server, batch.reason);
            } else {
                PENDING.add(new ScheduledBatch(server, batch.reason, batch.ticksRemaining - 1));
            }
        }
    }

    private record ScheduledBatch(MinecraftServer server, String reason, int ticksRemaining) {
    }

    public record CommitOutcome(
            boolean committed,
            String failure,
            String type,
            String skin,
            String previous,
            int changedStacks) {
        static CommitOutcome committed(String type, String skin, String previous, int changedStacks) {
            return new CommitOutcome(true, "", type, skin, previous, changedStacks);
        }

        static CommitOutcome failed(String failure, String type, String skin, String previous) {
            return new CommitOutcome(false, failure, type, skin, previous, 0);
        }
    }

    public record ReassertOutcome(boolean mirrored, int changedStacks) {
    }
}
