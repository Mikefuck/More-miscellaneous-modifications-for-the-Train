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
     * Per-player fingerprint of the last inventory the mirror inspected after applying
     * equipped skins ({@code I-17}). Only recorded once {@link PlayerLotteryStore}
     * takeover is active and the player's profile is healthy, so early ticks never
     * suppress retries.
     */
    private static final Map<UUID, Long> LAST_INVENTORY = new HashMap<>();
    private SkinStateCoordinator() {}
    public static void registerLifecycle() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var store = PlayerLotteryStore.get();
            if (!store.isTakeoverActive()) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == null || store.isLoadFailed(player.getUUID())) continue;
                long before = fingerprint(player);
                Long previous = LAST_INVENTORY.get(player.getUUID());
                if (previous != null && previous == before) continue;
                int changed = InventorySkinApplier.applyAllEquipped(player, null);
                if (!store.isLoadFailed(player.getUUID())) {
                    LAST_INVENTORY.put(player.getUUID(), changed > 0 ? fingerprint(player) : before);
                }
            }
        });
    }

    /**
     * Order-sensitive hash over item identities and skin components. Two slots swapping
     * their contents must count as a change, hence the positional mix.
     */
    private static long fingerprint(ServerPlayer player) {
        long hash = 1125899906842597L;
        DisguiseIdentityBridge.Resolution identity = DisguiseIdentityBridge.resolveServer(player);
        hash = hash * 31 + identity.kind().ordinal();
        hash = hash * 31 + Objects.hashCode(identity.targetUuid());
        int size = player.getInventory().getContainerSize();
        hash = hash * 31 + size;
        for (int i = 0; i < size; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            hash = hash * 31 + i;
            if (stack == null || stack.isEmpty()) continue;
            hash = hash * 31 + Objects.hashCode(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
            hash = hash * 31 + stack.getCount();
            hash = hash * 31 + Objects.hashCode(stack.get(com.habitrain.lottery.skin.SkinComponents.SKIN));
            hash = hash * 31 + Objects.hashCode(stack.get(com.habitrain.lottery.skin.SkinComponents.CUSTOM_SOUND));
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
        LAST_INVENTORY.put(player.getUUID(), fingerprint(player));
        SkinNetwork.sync(player);
        return new CommitOutcome(true, "", type, skin, previous, changed);
    }
    public static ReassertOutcome reassertPlayer(ServerPlayer player, String reason) {
        if (player == null) return new ReassertOutcome(false, 0);
        var store = PlayerLotteryStore.get();
        int changed = InventorySkinApplier.applyAllEquipped(player, null);
        if (store.isTakeoverActive() && !store.isLoadFailed(player.getUUID())) {
            LAST_INVENTORY.put(player.getUUID(), fingerprint(player));
        }
        SkinNetwork.sync(player);
        return new ReassertOutcome(true, changed);
    }
    public static int reassertAll(MinecraftServer server, String reason) {
        if (server == null) return 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) reassertPlayer(player, reason);
        return server.getPlayerList().getPlayerCount();
    }
    public static void clearPending(MinecraftServer server) { forgetAll(); }
    public record CommitOutcome(boolean committed, String failure, String type, String skin, String previous, int changedStacks) {}
    public record ReassertOutcome(boolean mirrored, int changedStacks) {}
}
