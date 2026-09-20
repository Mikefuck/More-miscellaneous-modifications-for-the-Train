package com.habitrain.lottery.bridge;

import com.habitrain.lottery.api.player.HabiSkinPlayerApi;
import com.habitrain.lottery.skin.SkinNetwork;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.SkinTypeKeys;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** World JSON is authoritative. Vanilla inventory networking transports equipped models. */
public final class SkinStateCoordinator {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    /**
     * Per-player fingerprint of the last inventory the mirror inspected. The scan only
     * runs when the fingerprint changes, so a full 41-slot sweep no longer happens
     * unconditionally for every player on every tick; any item change still triggers a
     * sweep on the next tick, which is the invariant the mirror exists to enforce.
     */
    private static final Map<UUID, Long> LAST_INVENTORY = new HashMap<>();
    private SkinStateCoordinator() {}
    public static void registerLifecycle() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!inventoryChanged(player)) continue;
                InventorySkinApplier.applyAllEquipped(player, null);
            }
        });
    }

    /** True when the player's inventory differs from the last observed one. */
    private static boolean inventoryChanged(ServerPlayer player) {
        if (player == null) return false;
        long fingerprint = fingerprint(player);
        Long previous = LAST_INVENTORY.put(player.getUUID(), fingerprint);
        return previous == null || previous != fingerprint;
    }

    /**
     * Order-sensitive hash over item identities and skin components. Two slots swapping
     * their contents must count as a change, hence the positional mix.
     */
    private static long fingerprint(ServerPlayer player) {
        long hash = 1125899906842597L;
        int size = player.getInventory().getContainerSize();
        hash = hash * 31 + size;
        for (int i = 0; i < size; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            hash = hash * 31 + i;
            if (stack == null || stack.isEmpty()) continue;
            hash = hash * 31 + Objects.hashCode(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
            hash = hash * 31 + stack.getCount();
            hash = hash * 31 + Objects.hashCode(stack.get(com.habitrain.lottery.skin.SkinComponents.SKIN));
        }
        return hash;
    }

    /** Drops the cached fingerprint, e.g. on disconnect, so the next tick rescans. */
    public static void forget(UUID player) {
        if (player != null) LAST_INVENTORY.remove(player);
    }

    public static void forgetAll() { LAST_INVENTORY.clear(); }
    public static CommitOutcome commitEquipped(ServerPlayer player, String rawType, String rawSkin) {
        if (player == null) return new CommitOutcome(false, "missing_player", "", "", "", 0);
        var store = PlayerLotteryStore.get();
        String type = SkinTypeKeys.canonical(rawType);
        String skin = PlayerLotteryStore.normalizeEquippedSkin(rawSkin);
        String previous = store.getEquipped(player.getUUID(), type);
        if (!store.isTakeoverActive() || store.isLoadFailed(player.getUUID()))
            return new CommitOutcome(false, "not_ready", type, skin, previous, 0);
        if (!HabiSkinPlayerApi.isRegistered(type, skin))
            return new CommitOutcome(false, "unknown_skin", type, skin, previous, 0);
        if (!store.isSkinUnlocked(player.getUUID(), type, skin))
            return new CommitOutcome(false, "locked", type, skin, previous, 0);
        var result = store.commitEquipped(player.getUUID(), type, skin);
        if (!result.committed()) return new CommitOutcome(false, result.failure(), type, skin, previous, 0);
        int changed = InventorySkinApplier.applyEquippedToInventory(player, type, skin);
        SkinNetwork.sync(player);
        return new CommitOutcome(true, "", type, skin, previous, changed);
    }
    public static ReassertOutcome reassertPlayer(ServerPlayer player, String reason) {
        if (player == null) return new ReassertOutcome(false, 0);
        int changed = InventorySkinApplier.applyAllEquipped(player, null);
        SkinNetwork.sync(player);
        return new ReassertOutcome(true, changed);
    }
    public static int reassertAll(MinecraftServer server, String reason) {
        if (server == null) return 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) reassertPlayer(player, reason);
        return server.getPlayerList().getPlayerCount();
    }
    public static void clearPending(MinecraftServer server) {}
    public record CommitOutcome(boolean committed, String failure, String type, String skin, String previous, int changedStacks) {}
    public record ReassertOutcome(boolean mirrored, int changedStacks) {}
}
