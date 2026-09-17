package com.habitrain.lottery.bridge;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.SkinTypeKeys;
import io.wifi.starrailexpress.content.item.SkinableItem;
import io.wifi.starrailexpress.index.SREDataComponentTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Applies equipped skins onto existing ItemStacks in the player inventory so
 * in-match rendering updates immediately after equip (not only on new items).
 */
public final class InventorySkinApplier {
    private InventorySkinApplier() {
    }

    public static int applyAllEquipped(ServerPlayer player, Map<String, String> equipped) {
        if (player == null) {
            return 0;
        }
        Map<String, String> map = equipped == null ? Map.of() : equipped;
        Set<String> types = new LinkedHashSet<>();
        for (String key : map.keySet()) {
            if (key != null && !key.isBlank()) {
                types.add(SkinTypeKeys.canonical(key));
            }
        }
        try {
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof SkinableItem skinable)) {
                    continue;
                }
                String itemType = skinable.getItemSkinType();
                if (itemType != null && !itemType.isBlank()) {
                    types.add(SkinTypeKeys.canonical(itemType));
                }
            }
        } catch (Exception ignored) {
        }
        int changed = 0;
        for (String canonical : types) {
            if (canonical == null || canonical.isBlank() || "default".equals(canonical)) {
                continue;
            }
            String skin = "default";
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                if (SkinTypeKeys.canonical(e.getKey()).equals(canonical)
                        && e.getValue() != null && !e.getValue().isBlank()) {
                    skin = e.getValue();
                    break;
                }
            }
            changed += applyEquippedToInventory(player, canonical, skin);
        }
        return changed;
    }

    public static int applyEquippedToInventory(ServerPlayer player, String type, String skin) {
        if (player == null || type == null) {
            return 0;
        }
        String want = (skin == null || skin.isBlank()) ? "default" : skin;
        String canonical = SkinTypeKeys.canonical(type);
        int changed = 0;
        try {
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (!(stack.getItem() instanceof SkinableItem skinable)) {
                    continue;
                }
                String itemType = skinable.getItemSkinType();
                if (itemType == null) {
                    continue;
                }
                if (!SkinTypeKeys.canonical(itemType).equals(canonical)
                        && !SkinTypeKeys.writeKeys(itemType).contains(canonical)
                        && !SkinTypeKeys.writeKeys(type).contains(SkinTypeKeys.canonical(itemType))) {
                    continue;
                }
                String cur = stack.get(SREDataComponentTypes.SKIN);
                if (!want.equals(cur)) {
                    stack.set(SREDataComponentTypes.SKIN, want);
                    changed++;
                }
            }
            if (changed > 0) {
                player.containerMenu.broadcastChanges();
            }
            HabiLotteryMod.LOGGER.info("Applied skin {}/{} to {} stacks in inventory of {}",
                    canonical, want, changed, player.getGameProfile().getName());
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("applyEquippedToInventory failed: {}", e.toString());
        }
        return changed;
    }
}
