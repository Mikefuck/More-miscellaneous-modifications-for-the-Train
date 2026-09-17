package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.network.CardUseRequestC2S;
import io.wifi.starrailexpress.backpack.BackpackState;
import io.wifi.starrailexpress.client.data.ClientPlayerDataCache;
import io.wifi.starrailexpress.client.gui.screen.BackpackScreen;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts the upstream card-click command ({@code sre:pass activate <type>}
 * sent by {@link BackpackScreen#sendCommand}) and asks the server for the
 * 直接使用 / 自选角色 menu instead.
 *
 * <p>{@code sendCommand} is a BackpackScreen-private, SRE-specific method only
 * called from the card-click path, so intercepting it is mapping-safe
 * ({@code remap = false}, same as the other SRE-targeted mixins). When the server
 * does not have this mod registered ({@link ClientPlayNetworking#canSend} false),
 * the upstream command is left untouched and the plain SRE activation runs.
 */
@Mixin(value = BackpackScreen.class, remap = false)
public abstract class BackpackScreenMixin {
    @Shadow private net.minecraft.client.gui.screens.Screen parent;

    @Inject(method = "init", at = @At("TAIL"), remap = true)
    private void habi$separateCards(CallbackInfo ci) {
        if (ClientPlayNetworking.canSend(CardUseRequestC2S.TYPE)) {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.habitrain.lottery.client.gui.CardBackpackScreen(parent));
        }
    }


    private static final String ACTIVATE_PREFIX = "sre:pass activate ";

    @Shadow
    private BackpackState backpack;

    @Shadow
    private LocalPlayer player;

    @Inject(method = "count", at = @At("HEAD"))
    private void habi$refreshBackpackBeforeCount(FactionCardType type, CallbackInfoReturnable<Integer> cir) {
        if (this.player != null) {
            this.backpack = ClientPlayerDataCache.backpack(this.player.getUUID());
        }
    }

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void habi$openCardUseMenu(String command, CallbackInfo ci) {
        if (command == null || !command.startsWith(ACTIVATE_PREFIX)) {
            return;
        }
        String questKey = command.substring(ACTIVATE_PREFIX.length()).trim();
        if (questKey.isEmpty()) {
            return;
        }
        try {
            if (ClientPlayNetworking.canSend(CardUseRequestC2S.TYPE)) {
                ClientPlayNetworking.send(new CardUseRequestC2S(questKey));
                // Swallow the upstream command; the server answers with CardUseMenuS2C
                // (or falls back to direct use / rejects with a clear message).
                ci.cancel();
            }
        } catch (Throwable t) {
            // Let the upstream command run on any unexpected client-side failure.
        }
    }
}
