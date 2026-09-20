package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.client.SkinClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Applies our component at the vanilla renderer boundary, including held and dropped items. */
@Mixin(ItemRenderer.class)
public abstract class IndependentSkinRendererMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private BakedModel habi$skinModel(BakedModel model, ItemStack stack, ItemDisplayContext context,
            boolean leftHand, PoseStack poses, MultiBufferSource buffers, int light, int overlay, BakedModel original) {
        boolean inHand = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        return SkinClient.model(stack, inHand, model);
    }
}
