package com.habitrain.lottery.bridge;

import com.habitrain.lottery.skin.SkinComponents;
import io.wifi.starrailexpress.content.item.DerringerItem;
import io.wifi.starrailexpress.index.SREDataComponentTypes;
import net.minecraft.world.item.ItemStack;

/** Keeps the Derringer's own appearance despite its upstream RevolverItem inheritance. */
public final class SkinItemPolicy {
    private SkinItemPolicy() {}

    public static boolean excludesSkin(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof DerringerItem;
    }

    public static void clearSkin(ItemStack stack) {
        stack.remove(SREDataComponentTypes.SKIN);
        stack.remove(SkinComponents.SKIN);
        stack.remove(SkinComponents.CUSTOM_SOUND);
    }
}
