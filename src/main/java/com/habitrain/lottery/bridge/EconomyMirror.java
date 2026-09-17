package com.habitrain.lottery.bridge;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.SkinTypeKeys;
import io.wifi.starrailexpress.cca.SREPlayerSkinsComponent;
import io.wifi.starrailexpress.data.PlayerEconomyManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mirrors world-authoritative lottery data into:
 * <ul>
 *   <li>{@link PlayerEconomyManager} (lottery roll path)</li>
 *   <li>{@link SREPlayerSkinsComponent} (SkinManagementScreen path)</li>
 * </ul>
 *
 * World JSON under {@code {world}/habitrain_lottery/players/} is the only durable store.
 */
public final class EconomyMirror {
    private static final ThreadLocal<Boolean> SUPPRESS = ThreadLocal.withInitial(() -> false);

    private EconomyMirror() {
    }

    public static boolean isSuppressed() {
        return Boolean.TRUE.equals(SUPPRESS.get());
    }

    public static void runSuppressed(Runnable r) {
        boolean prev = isSuppressed();
        SUPPRESS.set(true);
        try {
            r.run();
        } finally {
            SUPPRESS.set(prev);
        }
    }

    /**
     * Reconciles SRE memory + CCA from world data for join/admin import without destructive wipe.
     *
     * @return {@code false} if the reconcile threw; callers must not treat JOIN as COMMIT.
     */
    public static boolean pushToSre(ServerPlayer player, PlayerLotteryData data) {
        return pushToSre(player, data, true);
    }

    public static boolean pushToSre(ServerPlayer player, PlayerLotteryData data, boolean pushChanceCoins) {
        if (player == null || data == null) {
            return false;
        }
        boolean[] ok = {false};
        runSuppressed(() -> {
            int unlockCount = 0;
            if (data.unlocked != null) {
                for (Map<String, Boolean> m : data.unlocked.values()) {
                    if (m != null) unlockCount += m.size();
                }
            }
            int equippedCount = data.equipped != null ? data.equipped.size() : 0;
            HabiLotteryMod.LOGGER.info("pushToSre BEGIN for {} (chance={}, coins={}, unlocks={}, equipped={}, pushEconomy={})",
                    player.getGameProfile().getName(), data.lootChance, data.coinNum, unlockCount, equippedCount, pushChanceCoins);
            try {
                reconcileWorldToSre(player, data, pushChanceCoins);
                syncCca(player);
                ok[0] = true;
                HabiLotteryMod.LOGGER.info("pushToSre COMMIT for {}", player.getGameProfile().getName());
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.error("pushToSre failed for {}", player.getGameProfile().getName(), e);
            }
        });
        return ok[0];
    }

    /** PEM/CCA chance or coins are non-zero (read without takeover mixins). */
    public static boolean sreChanceOrCoinsNonZero(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        boolean[] nonZero = {false};
        runSuppressed(() -> {
            try {
                if (PlayerEconomyManager.getLootChance(player) != 0 || PlayerEconomyManager.getCoinNum(player) != 0) {
                    nonZero[0] = true;
                    return;
                }
                SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
                if (c != null) {
                    Integer ch = c.getLootChance();
                    Integer co = c.getCoinNum();
                    if ((ch != null && ch != 0) || (co != null && co != 0)) {
                        nonZero[0] = true;
                    }
                }
            } catch (Exception ignored) {
            }
        });
        return nonZero[0];
    }

    public static void copyChanceCoinsFromSre(ServerPlayer player, PlayerLotteryData data) {
        if (player == null || data == null) {
            return;
        }
        runSuppressed(() -> {
            try {
                data.lootChance = Math.max(0, PlayerEconomyManager.getLootChance(player));
                data.coinNum = Math.max(0, PlayerEconomyManager.getCoinNum(player));
                SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
                if (c != null) {
                    if (c.getLootChance() != null) {
                        data.lootChance = Math.max(data.lootChance, c.getLootChance());
                    }
                    if (c.getCoinNum() != null) {
                        data.coinNum = Math.max(data.coinNum, c.getCoinNum());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Incremental sync for chance and coins only.
     */
    public static void syncChanceAndCoins(ServerPlayer player, PlayerLotteryData data) {
        if (player == null || data == null) {
            return;
        }
        runSuppressed(() -> {
            try {
                int curChance = PlayerEconomyManager.getLootChance(player);
                int deltaChance = data.lootChance - curChance;
                if (deltaChance != 0) {
                    PlayerEconomyManager.addLootChance(player, deltaChance);
                }
                int curCoins = PlayerEconomyManager.getCoinNum(player);
                int deltaCoins = data.coinNum - curCoins;
                if (deltaCoins != 0) {
                    PlayerEconomyManager.addCoinNum(player, deltaCoins);
                }
                mirrorChanceCoinsToCca(player, data);
                syncCca(player);
            } catch (Throwable t) {
                HabiLotteryMod.LOGGER.debug("syncChanceAndCoins failed: {}", t.toString());
            }
        });
    }

    /**
     * Incremental sync for a newly unlocked skin.
     */
    public static void syncUnlockedSkin(ServerPlayer player, String type, String skin) {
        if (player == null || type == null || skin == null || skin.isBlank()) {
            return;
        }
        runSuppressed(() -> {
            try {
                String canonical = SkinTypeKeys.canonical(type);
                String normSkin = skin.trim().toLowerCase(java.util.Locale.ROOT);
                PlayerEconomyManager.unlockSkinForItemType(player, canonical, normSkin);
                unlockCca(player, canonical, normSkin);
                syncCca(player);
            } catch (Throwable t) {
                HabiLotteryMod.LOGGER.debug("syncUnlockedSkin failed {}/{}: {}", type, skin, t.toString());
            }
        });
    }

    /** Mirrors an already persisted admin access change, including equipment reset. */
    public static boolean syncSkinAccess(ServerPlayer player, PlayerLotteryData data,
                                         String type, String skin, boolean unlocked) {
        boolean[] synced = {false};
        runSuppressed(() -> {
            try {
                SREPlayerSkinsComponent component = SREPlayerSkinsComponent.KEY.get(player);
                if (component == null) {
                    return;
                }
                Set<String> keys = new LinkedHashSet<>(SkinTypeKeys.writeKeys(type));
                keys.addAll(PlayerEconomyManager.getUnlockedSkins(player).keySet());
                keys.addAll(PlayerEconomyManager.getEquippedSkins(player).keySet());
                keys.addAll(component.getUnlockedSkins().keySet());
                keys.addAll(component.getEquippedSkins().keySet());
                keys.removeIf(key -> !type.equals(SkinTypeKeys.canonical(key)));
                String equipped = data.equipped.getOrDefault(type,
                        data.equipped.entrySet().stream()
                                .filter(entry -> type.equals(SkinTypeKeys.canonical(entry.getKey())))
                                .map(Map.Entry::getValue).findFirst().orElse("default"));
                for (String key : keys) {
                    if (unlocked) {
                        PlayerEconomyManager.unlockSkinForItemType(player, key, skin);
                        component.unlockSkinForItemType(key, skin);
                    } else {
                        PlayerEconomyManager.lockSkinForItemType(player, key, skin);
                        component.lockSkinForItemType(key, skin);
                        PlayerEconomyManager.setEquippedSkinForItemType(player, key, equipped);
                        component.setEquippedSkinForItemType(key, equipped);
                    }
                }
                component.syncSkinsToClient();
                synced[0] = true;
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.error("Skin access saved but live sync failed for {} {}/{}",
                        player.getUUID(), type, skin, e);
            }
        });
        if (!unlocked) {
            InventorySkinApplier.applyAllEquipped(player, data.equipped);
        }
        return synced[0];
    }

    /**
     * Incremental sync for equipping a skin.
     */
    public static void syncEquippedSkin(ServerPlayer player, String type, String skin) {
        if (player == null || type == null) {
            return;
        }
        runSuppressed(() -> {
            try {
                String canonical = SkinTypeKeys.canonical(type);
                String want = (skin == null || skin.isBlank() || "default".equals(skin)) ? "default" : skin.trim().toLowerCase(java.util.Locale.ROOT);
                PlayerEconomyManager.setEquippedSkinForItemType(player, canonical, want);
                equipCca(player, canonical, want);
                syncCca(player);
            } catch (Throwable t) {
                HabiLotteryMod.LOGGER.debug("syncEquippedSkin failed {}/{}: {}", type, skin, t.toString());
            }
        });
    }

    /** Clear all unlocks/equipment in economy manager + CCA (used only by force admin reset). */
    public static void clearSreSkins(ServerPlayer player) {
        if (player == null) {
            return;
        }
        runSuppressed(() -> {
            try {
                Map<String, Map<String, Boolean>> unlocked = PlayerEconomyManager.getUnlockedSkins(player);
                if (unlocked != null) {
                    for (Map.Entry<String, Map<String, Boolean>> typeEntry : new HashMap<>(unlocked).entrySet()) {
                        String type = typeEntry.getKey();
                        Map<String, Boolean> skins = typeEntry.getValue();
                        if (skins == null) {
                            continue;
                        }
                        for (String skin : new HashMap<>(skins).keySet()) {
                            PlayerEconomyManager.lockSkinForItemType(player, type, skin);
                        }
                    }
                }
                Map<String, String> equipped = PlayerEconomyManager.getEquippedSkins(player);
                if (equipped != null) {
                    for (String type : new HashMap<>(equipped).keySet()) {
                        PlayerEconomyManager.setEquippedSkinForItemType(player, type, "default");
                    }
                }
                int curChance = PlayerEconomyManager.getLootChance(player);
                if (curChance != 0) {
                    PlayerEconomyManager.addLootChance(player, -curChance);
                }
                int curCoins = PlayerEconomyManager.getCoinNum(player);
                if (curCoins != 0) {
                    PlayerEconomyManager.addCoinNum(player, -curCoins);
                }
            } catch (Throwable t) {
                HabiLotteryMod.LOGGER.debug("clear economy failed: {}", t.toString());
            }
            try {
                SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
                if (c != null) {
                    Map<String, Map<String, Boolean>> unlocked = c.getUnlockedSkins();
                    if (unlocked != null) {
                        for (String type : new HashMap<>(unlocked).keySet()) {
                            c.clearSkinForItemType(type);
                        }
                    }
                    Map<String, String> equipped = c.getEquippedSkins();
                    if (equipped != null) {
                        for (String type : new HashMap<>(equipped).keySet()) {
                            c.setEquippedSkinForItemType(type, "default");
                        }
                    }
                    Integer ch = c.getLootChance();
                    if (ch != null && ch != 0) {
                        c.addLootChance(-ch);
                    }
                    Integer co = c.getCoinNum();
                    if (co != null && co != 0) {
                        c.addCoinNum(-co);
                    }
                }
            } catch (Throwable t) {
                HabiLotteryMod.LOGGER.debug("clear CCA failed: {}", t.toString());
            }
        });
    }

    private static void reconcileWorldToSre(ServerPlayer player, PlayerLotteryData data, boolean pushChanceCoins) {
        if (pushChanceCoins) {
            int curChance = PlayerEconomyManager.getLootChance(player);
            int deltaChance = data.lootChance - curChance;
            if (deltaChance != 0) {
                PlayerEconomyManager.addLootChance(player, deltaChance);
            }
            int curCoins = PlayerEconomyManager.getCoinNum(player);
            int deltaCoins = data.coinNum - curCoins;
            if (deltaCoins != 0) {
                PlayerEconomyManager.addCoinNum(player, deltaCoins);
            }
        }
        if (data.unlocked != null) {
            for (Map.Entry<String, Map<String, Boolean>> typeEntry : data.unlocked.entrySet()) {
                String type = typeEntry.getKey();
                Map<String, Boolean> skins = typeEntry.getValue();
                if (skins == null) {
                    continue;
                }
                for (Map.Entry<String, Boolean> skinEntry : skins.entrySet()) {
                    if (Boolean.TRUE.equals(skinEntry.getValue())) {
                        String skin = skinEntry.getKey();
                        PlayerEconomyManager.unlockSkinForItemType(player, type, skin);
                        unlockCca(player, type, skin);
                    }
                }
            }
        }
        reconcileEquipped(player, data);
        if (pushChanceCoins) {
            mirrorChanceCoinsToCca(player, data);
        }
    }

    /**
     * JSON equipped wins; empty equipped still writes {@code default} for every type
     * currently equipped in PEM/CCA so leftovers are not skipped.
     */
    private static void reconcileEquipped(ServerPlayer player, PlayerLotteryData data) {
        Set<String> types = new LinkedHashSet<>();
        if (data.equipped != null) {
            for (String key : data.equipped.keySet()) {
                if (key != null && !key.isBlank()) {
                    types.add(SkinTypeKeys.canonical(key));
                }
            }
        }
        if (data.unlocked != null) {
            for (String key : data.unlocked.keySet()) {
                if (key != null && !key.isBlank()) {
                    types.add(SkinTypeKeys.canonical(key));
                }
            }
        }
        try {
            Map<String, String> sre = PlayerEconomyManager.getEquippedSkins(player);
            if (sre != null) {
                for (String key : sre.keySet()) {
                    if (key != null && !key.isBlank()) {
                        types.add(SkinTypeKeys.canonical(key));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
            if (c != null && c.getEquippedSkins() != null) {
                for (String key : c.getEquippedSkins().keySet()) {
                    if (key != null && !key.isBlank()) {
                        types.add(SkinTypeKeys.canonical(key));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        for (String type : types) {
            if (type == null || type.isBlank() || "default".equals(type)) {
                continue;
            }
            String skin = "default";
            if (data.equipped != null) {
                for (String key : SkinTypeKeys.writeKeys(type)) {
                    String eq = data.equipped.get(key);
                    if (eq != null && !eq.isBlank()) {
                        skin = eq;
                        break;
                    }
                }
            }
            PlayerEconomyManager.setEquippedSkinForItemType(player, type, skin);
            equipCca(player, type, skin);
        }
    }

    public static void unlockCca(ServerPlayer player, String type, String skin) {
        if (player == null || type == null || skin == null || skin.isBlank()) {
            return;
        }
        try {
            SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
            if (c == null) {
                return;
            }
            for (String key : typeKeys(type)) {
                if (!c.isSkinUnlockedForItemType(key, skin)) {
                    c.unlockSkinForItemType(key, skin);
                }
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("unlockCca failed {}/{}: {}", type, skin, t.toString());
        }
    }

    public static void equipCca(ServerPlayer player, String type, String skin) {
        if (player == null || type == null) {
            return;
        }
        try {
            SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
            if (c == null) {
                return;
            }
            for (String key : typeKeys(type)) {
                c.setEquippedSkinForItemType(key, skin == null ? "default" : skin);
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("equipCca failed: {}", t.toString());
        }
    }

    public static void lockCca(ServerPlayer player, String type, String skin) {
        if (player == null || type == null || skin == null) {
            return;
        }
        try {
            SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
            if (c == null) {
                return;
            }
            for (String key : typeKeys(type)) {
                c.lockSkinForItemType(key, skin);
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("lockCca failed: {}", t.toString());
        }
    }

    public static void syncCca(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
            if (c == null) {
                return;
            }
            try {
                c.syncSkinsToClient();
            } catch (Throwable ignored) {
                c.sync();
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("syncCca failed: {}", t.toString());
        }
    }

    private static Set<String> typeKeys(String type) {
        return SkinTypeKeys.writeKeys(type);
    }

    private static void mirrorChanceCoinsToCca(ServerPlayer player, PlayerLotteryData data) {
        try {
            SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
            if (c == null) {
                return;
            }
            Integer curChance = c.getLootChance();
            int wantChance = data.lootChance;
            if (curChance == null || curChance != wantChance) {
                int delta = wantChance - (curChance == null ? 0 : curChance);
                if (delta != 0) {
                    c.addLootChance(delta);
                }
            }
            Integer curCoins = c.getCoinNum();
            int wantCoins = data.coinNum;
            if (curCoins == null || curCoins != wantCoins) {
                int delta = wantCoins - (curCoins == null ? 0 : curCoins);
                if (delta != 0) {
                    c.addCoinNum(delta);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
