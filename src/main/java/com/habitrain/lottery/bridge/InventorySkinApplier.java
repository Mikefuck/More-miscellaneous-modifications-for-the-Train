package com.habitrain.lottery.bridge;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinEffects;
import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.skin.SkinComponents;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.SkinTypeKeys;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.Map;
import java.util.Objects;

public final class InventorySkinApplier {
    private InventorySkinApplier() {}
    public static int applyAllEquipped(ServerPlayer player, Map<String, String> equipped) {
        return apply(player, null);
    }
    public static int applyEquippedToInventory(ServerPlayer player, String type, String skin) {
        return apply(player, SkinTypeKeys.canonical(type));
    }
    private static int apply(ServerPlayer player, String onlyType) {
        if (player == null) return 0;
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (!store.isTakeoverActive() || store.isLoadFailed(player.getUUID())) return 0;
        int changed = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            String type = SkinItems.typeOf(stack);
            if (type == null) {
                if (stack.has(SkinComponents.SKIN)) { stack.remove(SkinComponents.SKIN); changed++; }
                continue;
            }
            if (onlyType != null && !onlyType.equals(type)) continue;
            String skin = store.getEquipped(player.getUUID(), type);
            // SkinEffects#restrictToItems may narrow a skin type to a subset of its items
            // (a grenade skin that only belongs on the plain grenade, for instance), so a
            // restriction is as authoritative as "unlocked": a stack of an excluded item
            // gets the component removed just like an unowned skin would.
            boolean usable = HabiSkinApi.find(type, skin).isPresent()
                    && store.isSkinUnlocked(player.getUUID(), type, skin)
                    && SkinEffects.allowsItem(type, skin, stack.getItem());
            String wanted = usable ? type + "/" + skin : null;
            if (!Objects.equals(stack.get(SkinComponents.SKIN), wanted)) {
                if (wanted == null) stack.remove(SkinComponents.SKIN); else stack.set(SkinComponents.SKIN, wanted);
                changed++;
            }
        }
        if (changed > 0) player.containerMenu.broadcastChanges();
        return changed;
    }
}
