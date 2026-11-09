package com.habitrain.lottery.bridge;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinEffects;
import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.api.skin.SkinSounds;
import com.habitrain.lottery.skin.SkinComponents;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.SkinTypeKeys;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class InventorySkinApplier {
    record TargetComponents(String skinEntry, Boolean customSound) {
        static final TargetComponents NONE = new TargetComponents(null, null);
    }

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
        DisguiseIdentityBridge.Resolution identity = DisguiseIdentityBridge.resolveServer(player);
        int changed = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (applyStackForIdentity(stack, onlyType, player.getUUID(), identity)) {
                changed++;
            }
        }
        if (changed > 0) player.containerMenu.broadcastChanges();
        return changed;
    }

    static boolean applyStackForIdentity(
            ItemStack stack,
            String onlyType,
            UUID realOwnerUuid,
            DisguiseIdentityBridge.Resolution identity
    ) {
        if (stack == null || stack.isEmpty()) return false;
        String type = SkinItemPolicy.excludesSkin(stack) ? null : SkinItems.typeOf(stack);
        if (type == null) {
            boolean changed = false;
            if (stack.has(SkinComponents.SKIN)) { stack.remove(SkinComponents.SKIN); changed = true; }
            if (stack.has(SkinComponents.CUSTOM_SOUND)) { stack.remove(SkinComponents.CUSTOM_SOUND); changed = true; }
            return changed;
        }
        if (onlyType != null && !onlyType.equals(type)) return false;

        TargetComponents target = resolveTargetComponents(type, stack.getItem(), realOwnerUuid, identity);
        String wanted = target.skinEntry();
        Boolean sound = target.customSound();

        boolean changed = false;
        if (!Objects.equals(stack.get(SkinComponents.SKIN), wanted)) {
            if (wanted == null) stack.remove(SkinComponents.SKIN); else stack.set(SkinComponents.SKIN, wanted);
            changed = true;
        }
        if (!Objects.equals(stack.get(SkinComponents.CUSTOM_SOUND), sound)) {
            if (sound == null) stack.remove(SkinComponents.CUSTOM_SOUND);
            else stack.set(SkinComponents.CUSTOM_SOUND, sound);
            changed = true;
        }
        return changed;
    }

    static TargetComponents resolveTargetComponents(
            String type,
            Item item,
            UUID realOwnerUuid,
            DisguiseIdentityBridge.Resolution identity
    ) {
        if (type == null) {
            return TargetComponents.NONE;
        }
        DisguiseIdentityBridge.Resolution resolved =
                identity == null ? DisguiseIdentityBridge.Resolution.self() : identity;
        if (resolved.kind() == DisguiseIdentityBridge.Kind.HIDDEN_ANONYMOUS) {
            return TargetComponents.NONE;
        }
        UUID skinOwner = resolved.kind() == DisguiseIdentityBridge.Kind.DISGUISED_AS
                ? resolved.targetUuid()
                : realOwnerUuid;
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (skinOwner == null || store.isLoadFailed(skinOwner)) {
            return TargetComponents.NONE;
        }
        String skin = store.getEquipped(skinOwner, type);
        // SkinEffects#restrictToItems may narrow a skin type to a subset of its items
        // (a grenade skin that only belongs on the plain grenade, for instance), so a
        // restriction is as authoritative as "unlocked": a stack of an excluded item
        // gets the component removed just like an unowned skin would.
        boolean usable = HabiSkinApi.find(type, skin).isPresent()
                && store.isSkinUnlocked(skinOwner, type, skin)
                && SkinEffects.allowsItem(type, skin, item);
        if (!usable) {
            return TargetComponents.NONE;
        }
        String wanted = type + "/" + skin;
        Boolean sound = (SkinSounds.supports(type, skin) && !store.isCustomSoundEnabled(skinOwner, type, skin))
                ? Boolean.FALSE
                : null;
        return new TargetComponents(wanted, sound);
    }
}
