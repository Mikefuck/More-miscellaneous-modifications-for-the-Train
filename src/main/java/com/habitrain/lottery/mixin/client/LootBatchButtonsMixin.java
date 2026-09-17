package com.habitrain.lottery.mixin.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.agmas.noellesroles.client.screen.LootInfoScreen;
import org.agmas.noellesroles.client.screen.LootInfoScreen.PoolButton;
import org.agmas.noellesroles.packet.Loot.LootMultiRequestC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LootInfoScreen.class, remap = false)
public abstract class LootBatchButtonsMixin extends Screen {
    @Shadow private PoolButton startPoolBtn;
    @Shadow private PoolButton multiPoolBtn;
    @Shadow private PoolButton previewBtn;
    @Unique private PoolButton habi$fiftyButton;

    protected LootBatchButtonsMixin(Component title) { super(title); }

    @Inject(method = "tryToInitPool", at = @At("RETURN"))
    private void habi$installBatchButtons(CallbackInfoReturnable<Boolean> cir) {
        if (habi$fiftyButton != null) removeWidget(habi$fiftyButton);
        habi$fiftyButton = null;
        if (!cir.getReturnValueZ()) return;

        // Fit four actions inside the original three-button span.
        int x = startPoolBtn.getX();
        int y = startPoolBtn.getY();
        int gap = Math.max(2, multiPoolBtn.getX() - x - startPoolBtn.getWidth());
        int span = previewBtn.getX() + previewBtn.getWidth() - x;
        int buttonWidth = Math.max(1, (span - gap * 3) / 4);
        int buttonHeight = startPoolBtn.getHeight();
        int poolId = multiPoolBtn.getPoolID();
        removeWidget(multiPoolBtn);
        startPoolBtn.setWidth(buttonWidth);
        multiPoolBtn = new PoolButton(poolId, x + buttonWidth + gap, y, buttonWidth, buttonHeight,
                Component.translatable("screen.habitrain_lottery.loot.ten"),
                button -> ClientPlayNetworking.send(new LootMultiRequestC2SPacket(button.getPoolID(), 10)));
        multiPoolBtn.active = false;
        multiPoolBtn.setAlpha(0f);
        addRenderableWidget(multiPoolBtn);
        habi$fiftyButton = new PoolButton(poolId, x + (buttonWidth + gap) * 2, y, buttonWidth, buttonHeight,
                Component.translatable("screen.habitrain_lottery.loot.fifty"),
                button -> ClientPlayNetworking.send(new LootMultiRequestC2SPacket(multiPoolBtn.getPoolID(), 50)));
        habi$fiftyButton.active = false;
        habi$fiftyButton.setAlpha(0f);
        addRenderableWidget(habi$fiftyButton);
        previewBtn.setX(x + (buttonWidth + gap) * 3);
        previewBtn.setWidth(buttonWidth);
    }

    // Upstream renders its widgets explicitly; follow the existing multi-button animation.
    @Inject(method = "render", at = @At("TAIL"), remap = true)
    private void habi$renderFifty(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (habi$fiftyButton == null || multiPoolBtn == null) return;
        habi$fiftyButton.active = multiPoolBtn.active;
        habi$fiftyButton.setAlpha(multiPoolBtn.getAlpha());
        habi$fiftyButton.render(graphics, mouseX, mouseY, delta);
    }
}
