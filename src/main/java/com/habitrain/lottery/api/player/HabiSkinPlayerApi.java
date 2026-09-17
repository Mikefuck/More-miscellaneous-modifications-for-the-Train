package com.habitrain.lottery.api.player;

import com.habitrain.lottery.bridge.EconomyMirror;
import com.habitrain.lottery.bridge.SkinStateCoordinator;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.SkinTypeKeys;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.api.skin.SkinDefinition;
import io.wifi.starrailexpress.util.ItemSkinManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Public API for one player's skin ownership and equipped skin.
 *
 * <p>Unlike {@link com.habitrain.lottery.api.skin.HabiSkinApi}, which registers
 * new skin definitions, this facade edits a player's saved skin state. Unlock
 * and lock changes are persisted through the same transactional commit the
 * in-game {@code /hlt skins} command uses, so the world JSON stays
 * authoritative and online SRE/CCA mirrors are refreshed automatically.</p>
 *
 * <p>Server thread only.</p>
 */
public final class HabiSkinPlayerApi {
    /** Skin used when nothing is equipped. */
    public static final String DEFAULT_SKIN = "default";

    private HabiSkinPlayerApi() {
    }

    /** True when {@code type/id} exists in the SRE registry (or is {@code default}). */
    public static boolean isRegistered(String type, String skin) {
        String canonical = canonicalType(type);
        if (canonical == null) {
            return false;
        }
        String id = PlayerLotteryStore.normalizeEquippedSkin(skin);
        if (DEFAULT_SKIN.equals(id)) {
            return true;
        }
        try {
            Map<String, ?> skins = ItemSkinManager.getSkins(canonical);
            return skins != null && skins.containsKey(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when the player owns {@code type/skin}; {@code default} is always owned. */
    public static boolean isUnlocked(UUID uuid, String type, String skin) {
        if (uuid == null) {
            return false;
        }
        String canonical = canonicalType(type);
        if (canonical == null) {
            return false;
        }
        String id = PlayerLotteryStore.normalizeEquippedSkin(skin);
        if (DEFAULT_SKIN.equals(id)) {
            return true;
        }
        return PlayerLotteryStore.get().isSkinUnlocked(uuid, canonical, id);
    }

    /** Sorted owned skin ids for one type (never includes {@code default}). */
    public static List<String> unlocked(UUID uuid, String type) {
        String canonical = canonicalType(type);
        if (uuid == null || canonical == null) {
            return List.of();
        }
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(uuid);
        return collect(data, canonical);
    }

    /** All owned skins grouped by canonical type. */
    public static Map<String, List<String>> unlockedAll(UUID uuid) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (uuid == null) {
            return out;
        }
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(uuid);
        if (data == null || data.unlocked == null) {
            return out;
        }
        for (String type : data.unlocked.keySet()) {
            if (type == null) {
                continue;
            }
            String canonical = SkinTypeKeys.canonical(type);
            if (DEFAULT_SKIN.equals(canonical) || out.containsKey(canonical)) {
                continue;
            }
            List<String> skins = collect(data, canonical);
            if (!skins.isEmpty()) {
                out.put(canonical, skins);
            }
        }
        return out;
    }

    /** Equipped skin id for one type, or {@value #DEFAULT_SKIN}. */
    public static String equipped(UUID uuid, String type) {
        String canonical = canonicalType(type);
        if (uuid == null || canonical == null) {
            return DEFAULT_SKIN;
        }
        String equipped = PlayerLotteryStore.get().getEquipped(uuid, canonical);
        return equipped == null || equipped.isBlank() ? DEFAULT_SKIN : equipped;
    }

    /** All non-default equipped skins by canonical type. */
    public static Map<String, String> equippedAll(UUID uuid) {
        Map<String, String> out = new LinkedHashMap<>();
        if (uuid == null) {
            return out;
        }
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(uuid);
        if (data == null || data.equipped == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : data.equipped.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            String canonical = SkinTypeKeys.canonical(entry.getKey());
            if (!DEFAULT_SKIN.equals(canonical)) {
                out.put(canonical, entry.getValue());
            }
        }
        return out;
    }

    /** Grants one player ownership of {@code type/skin}. */
    public static HabiAssetResult unlock(UUID uuid, String type, String skin) {
        return changeAccess(uuid, type, skin, true);
    }

    /** Revokes ownership of {@code type/skin} (also clears it from equipped slots). */
    public static HabiAssetResult lock(UUID uuid, String type, String skin) {
        return changeAccess(uuid, type, skin, false);
    }

    public static HabiAssetResult unlock(ServerPlayer player, String type, String skin) {
        return unlock(player == null ? null : player.getUUID(), type, skin);
    }

    public static HabiAssetResult lock(ServerPlayer player, String type, String skin) {
        return lock(player == null ? null : player.getUUID(), type, skin);
    }

    /**
     * Equips an owned skin. Online players get SRE/CCA and inventory updated
     * immediately; offline players get the durable world JSON updated.
     */
    public static HabiAssetResult equip(UUID uuid, String type, String skin) {
        if (uuid == null) {
            return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        String canonical = canonicalType(type);
        if (canonical == null) {
            return HabiAssetResult.fail(HabiFailure.UNKNOWN_SKIN_TYPE);
        }
        String id = PlayerLotteryStore.normalizeEquippedSkin(skin);
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid)) {
            return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        }
        if (!DEFAULT_SKIN.equals(id) && !store.isSkinUnlocked(uuid, canonical, id)) {
            return HabiAssetResult.fail(HabiFailure.SKIN_NOT_UNLOCKED);
        }
        ServerPlayer online = HabiLotteryApi.online(uuid);
        if (online != null) {
            SkinStateCoordinator.CommitOutcome outcome =
                    SkinStateCoordinator.commitEquipped(online, canonical, id);
            if (!outcome.committed()) {
                return HabiAssetResult.fail("locked".equals(outcome.failure())
                        ? HabiFailure.SKIN_NOT_UNLOCKED
                        : HabiFailure.WRITE_FAILED);
            }
            return HabiAssetResult.success();
        }
        PlayerLotteryStore.EquippedCommitResult committed = store.commitEquipped(uuid, canonical, id);
        return committed.committed()
                ? HabiAssetResult.success()
                : HabiAssetResult.fail(HabiFailure.WRITE_FAILED);
    }

    public static HabiAssetResult equip(ServerPlayer player, String type, String skin) {
        return equip(player == null ? null : player.getUUID(), type, skin);
    }

    /** Clears the equipped skin for one type. */
    public static HabiAssetResult clearEquipped(UUID uuid, String type) {
        return equip(uuid, type, DEFAULT_SKIN);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static HabiAssetResult changeAccess(UUID uuid, String type, String skin, boolean unlocked) {
        if (uuid == null) {
            return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        String canonical = canonicalType(type);
        if (canonical == null) {
            return HabiAssetResult.fail(HabiFailure.UNKNOWN_SKIN_TYPE);
        }
        String id = PlayerLotteryStore.normalizeEquippedSkin(skin);
        if (DEFAULT_SKIN.equals(id)) {
            return HabiAssetResult.fail(HabiFailure.INVALID_VALUE);
        }
        if (unlocked && !isRegistered(canonical, id)) {
            return HabiAssetResult.fail(HabiFailure.UNKNOWN_SKIN);
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid)) {
            return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        }
        if (!store.commitSkinAccess(uuid, canonical, id, unlocked)) {
            return HabiAssetResult.fail(store.isLoadFailed(uuid)
                    ? HabiFailure.CORRUPT_STORAGE
                    : HabiFailure.WRITE_FAILED);
        }
        ServerPlayer online = HabiLotteryApi.online(uuid);
        if (online != null) {
            EconomyMirror.syncSkinAccess(online, store.getOrLoad(uuid), canonical, id, unlocked);
        }
        return HabiAssetResult.success();
    }

    private static List<String> collect(PlayerLotteryData data, String canonical) {
        TreeSet<String> ids = new TreeSet<>();
        if (data == null || data.unlocked == null) {
            return List.of();
        }
        for (Map.Entry<String, Map<String, Boolean>> entry : data.unlocked.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!canonical.equals(SkinTypeKeys.canonical(entry.getKey()))) {
                continue;
            }
            for (Map.Entry<String, Boolean> skin : entry.getValue().entrySet()) {
                if (Boolean.TRUE.equals(skin.getValue()) && skin.getKey() != null
                        && !DEFAULT_SKIN.equals(skin.getKey())) {
                    ids.add(skin.getKey());
                }
            }
        }
        return ids.isEmpty() ? List.of() : new ArrayList<>(ids);
    }

    static String canonicalType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String canonical = SkinTypeKeys.canonical(raw);
        return SkinDefinition.SUPPORTED_TYPES.contains(canonical) ? canonical : null;
    }
}
