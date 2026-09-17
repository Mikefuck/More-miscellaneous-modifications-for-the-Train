package com.habitrain.lottery.title;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.exmo.sre.nametag.NameTagInventoryComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Bridge local title JSON ↔ SRE {@link NameTagInventoryComponent}.
 */
public final class TitleService {
    private static final ThreadLocal<Boolean> APPLYING_LOCAL = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> FORCE_SYNCING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private TitleService() {
    }

    public static boolean isApplyingLocal() {
        return Boolean.TRUE.equals(APPLYING_LOCAL.get());
    }

    public static boolean isForceSyncing() {
        return Boolean.TRUE.equals(FORCE_SYNCING.get());
    }

    public static void onPlayerJoin(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready()) {
            return;
        }
        APPLYING_LOCAL.set(Boolean.TRUE);
        try {
            TitlePaths.ensureDirs();
            LocalTitleStore.PlayerLoad load = LocalTitleStore.get().loadPlayerDetailed(player.getUUID());
            NameTagInventoryComponent c = NameTagInventoryComponent.KEY.get(player);
            JoinPlan plan = planJoin(load, hasCcaTitles(c));
            if (plan == JoinPlan.KEEP_CCA) {
                if (load != null && load.corrupt()) {
                    HabiLotteryMod.LOGGER.error(
                            "Corrupt title JSON for {}, skipping CCA clear/save",
                            player.getUUID());
                }
                forceResync(player);
                return;
            }
            if (plan == JoinPlan.MIGRATE_CCA) {
                PlayerTitleData migrated = snapshot(c);
                if (!LocalTitleStore.get().savePlayer(player.getUUID(), migrated)) {
                    HabiLotteryMod.LOGGER.error(
                            "Failed migrating CCA titles to local JSON for {}", player.getUUID());
                }
                forceResync(player);
                return;
            }
            PlayerTitleData data = load.data();
            c.clear();
            if (data != null && data.owned != null) {
                for (String t : data.owned) {
                    if (t != null && !t.isBlank()) {
                        c.addNameTag(t);
                    }
                }
            }
            if (data != null && data.current != null && !data.current.isBlank()
                    && data.owned != null && data.owned.contains(data.current)) {
                c.setCurrentNameTag(data.current);
            }
            PlayerTitleData next = snapshot(c);
            if (load.usedBackup() || data == null || !data.contentEquals(next)) {
                if (!LocalTitleStore.get().savePlayer(player.getUUID(), next)) {
                    HabiLotteryMod.LOGGER.error("Failed saving titles after JOIN apply for {}", player.getUUID());
                }
            }
            forceResync(player);
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("title load failed for {}",
                    player.getGameProfile().getName(), e);
        } finally {
            APPLYING_LOCAL.set(Boolean.FALSE);
        }
    }

    public static void persistFromComponent(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready()) {
            return;
        }
        try {
            NameTagInventoryComponent c = NameTagInventoryComponent.KEY.get(player);
            if (!LocalTitleStore.get().savePlayer(player.getUUID(), snapshot(c))) {
                HabiLotteryMod.LOGGER.error("title persist failed to write for {}", player.getUUID());
            }
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("title persist failed for {}",
                    player.getGameProfile().getName(), e);
        }
    }

    /**
     * Immediately push the player's title CCA to every client and refresh tab/display-name caches.
     * Safe to call repeatedly; no-ops if CCA is unavailable.
     */
    public static void forceResync(ServerPlayer player) {
        if (player == null || Boolean.TRUE.equals(FORCE_SYNCING.get())) {
            return;
        }
        FORCE_SYNCING.set(Boolean.TRUE);
        try {
            NameTagInventoryComponent c = NameTagInventoryComponent.KEY.get(player);
            // CCA sync (shouldSyncWith is patched to all players)
            c.sync();

            MinecraftServer server = player.getServer();
            if (server != null) {
                // Tab list / some HUDs cache display names until an info update packet arrives
                server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                        EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                        List.of(player)));
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("title forceResync failed for {}: {}",
                    player == null ? "?" : player.getGameProfile().getName(), t.toString());
        } finally {
            FORCE_SYNCING.set(Boolean.FALSE);
        }
    }

    public static boolean grantOnline(ServerPlayer player, String display) {
        if (player == null || display == null || display.isBlank() || !WorldLotteryPaths.ready()) {
            return false;
        }
        try {
            NameTagInventoryComponent c = NameTagInventoryComponent.KEY.get(player);
            if (c.nameTags != null && c.nameTags.contains(display)) {
                return false;
            }
            c.addNameTag(display);
            forceResync(player);
            return true;
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("title grant online failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
            return false;
        }
    }

    public static boolean grantOffline(UUID uuid, String display) {
        if (uuid == null || !WorldLotteryPaths.ready()) {
            return false;
        }
        return LocalTitleStore.get().grant(uuid, display);
    }

    public static boolean revokeOnline(ServerPlayer player, String display) {
        if (player == null || display == null || !WorldLotteryPaths.ready()) {
            return false;
        }
        try {
            NameTagInventoryComponent c = NameTagInventoryComponent.KEY.get(player);
            if (c.nameTags == null || !c.nameTags.contains(display)) {
                return false;
            }
            c.removeNameTag(display);
            forceResync(player);
            return true;
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("title revoke online failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
            return false;
        }
    }

    public static boolean revokeOffline(UUID uuid, String display) {
        if (uuid == null || !WorldLotteryPaths.ready()) {
            return false;
        }
        return LocalTitleStore.get().revoke(uuid, display);
    }

    public static boolean setCurrentOnline(ServerPlayer player, String display) {
        if (player == null || !WorldLotteryPaths.ready()) {
            return false;
        }
        try {
            NameTagInventoryComponent c = NameTagInventoryComponent.KEY.get(player);
            if (display == null || display.isBlank()) {
                if (c.CurrentNameTag != null && !c.CurrentNameTag.isEmpty()) {
                    c.CurrentNameTag = "";
                    persistFromComponent(player);
                }
                forceResync(player);
                return true;
            }
            if (c.nameTags == null || !c.nameTags.contains(display)) {
                return false;
            }
            c.setCurrentNameTag(display);
            forceResync(player);
            return true;
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("title setCurrent online failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
            return false;
        }
    }

    public static boolean setCurrentOffline(UUID uuid, String display) {
        if (uuid == null || !WorldLotteryPaths.ready()) {
            return false;
        }
        return LocalTitleStore.get().setCurrent(uuid, display);
    }

    public static void clearOnline(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready()) {
            return;
        }
        try {
            NameTagInventoryComponent.KEY.get(player).clear();
            forceResync(player);
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("title clear online failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }

    public static void clearOffline(UUID uuid) {
        if (uuid == null || !WorldLotteryPaths.ready()) {
            return;
        }
        LocalTitleStore.get().clear(uuid);
    }

    static PlayerTitleData snapshot(NameTagInventoryComponent c) {
        PlayerTitleData data = new PlayerTitleData();
        data.version = 1;
        data.owned = c.nameTags != null ? new ArrayList<>(c.nameTags) : new ArrayList<>();
        data.current = c.CurrentNameTag != null ? c.CurrentNameTag : "";
        data.updatedAt = System.currentTimeMillis();
        return data;
    }

    enum JoinPlan {
        MIGRATE_CCA,
        APPLY_LOCAL,
        KEEP_CCA
    }

    static JoinPlan planJoin(LocalTitleStore.PlayerLoad load, boolean ccaHasTitles) {
        if (load == null || load.corrupt()) {
            return JoinPlan.KEEP_CCA;
        }
        if (load.missing()) {
            // Non-empty CCA is source of truth; empty CCA must not write an empty JSON.
            return ccaHasTitles ? JoinPlan.MIGRATE_CCA : JoinPlan.KEEP_CCA;
        }
        return JoinPlan.APPLY_LOCAL;
    }

    private static boolean hasCcaTitles(NameTagInventoryComponent c) {
        if (c == null) {
            return false;
        }
        if (c.CurrentNameTag != null && !c.CurrentNameTag.isBlank()) {
            return true;
        }
        return c.nameTags != null && !c.nameTags.isEmpty();
    }
}
