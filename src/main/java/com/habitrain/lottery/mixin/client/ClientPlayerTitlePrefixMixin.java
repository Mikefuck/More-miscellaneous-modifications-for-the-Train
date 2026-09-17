package com.habitrain.lottery.mixin.client;

import net.exmo.sre.nametag.NameTagInventoryComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SRE's {@code PlayerPrefixMixin} only injects on the logical server ({@code ServerPlayer}).
 * Floating name tags and inventory-model labels on the client call {@code getDisplayName} on
 * client players, so titles never appear above heads even when CCA has a current title.
 *
 * <p>Skin management screen draws the title as a separate GUI string (which is why it works
 * there). This mixin bridges the same CCA current-title into client display names.
 */
@Mixin(Player.class)
public class ClientPlayerTitlePrefixMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void habi$clientTitlePrefix(CallbackInfoReturnable<Component> cir) {
        Player player = (Player) (Object) this;
        if (player.level() == null || !player.level().isClientSide) {
            return;
        }
        try {
            NameTagInventoryComponent tags = NameTagInventoryComponent.KEY.get(player);
            MutableComponent prefix = tags.generate();
            if (prefix == null) {
                return;
            }
            Component original = cir.getReturnValue();
            if (original == null) {
                original = player.getName();
            }
            // Avoid double-prefix if something else already prepended the same title text.
            String prefixPlain = prefix.getString();
            String originalPlain = original.getString();
            if (!prefixPlain.isEmpty() && originalPlain.startsWith(prefixPlain.trim())) {
                return;
            }
            cir.setReturnValue(prefix.copy().append(original));
        } catch (Throwable ignored) {
            // CCA missing / generate failed — leave vanilla display name
        }
    }
}
